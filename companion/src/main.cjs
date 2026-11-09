// Relaycast companion: installs next to the Relay Server, keeps it running
// in the background and lets the user choose the video folders to share.
const { app, BrowserWindow, Menu, Tray, clipboard, dialog, ipcMain, nativeImage, shell } = require('electron');
const { networkInterfaces } = require('node:os');
const { existsSync, mkdirSync, readFileSync, statSync } = require('node:fs');
const { extname, join } = require('node:path');
const QRCode = require('qrcode');
const { createConfig } = require('./config.cjs');
const { createSupervisor } = require('./supervisor.cjs');

const packaged = app.isPackaged;
const resources = packaged ? process.resourcesPath : join(__dirname, '..', 'stage');
const serverEntry = join(resources, 'server', 'server.mjs');
const binDirectory = join(resources, 'bin');
const userData = app.getPath('userData');
const dataDirectory = join(userData, 'data');
mkdirSync(dataDirectory, { recursive: true });

if (!app.requestSingleInstanceLock()) { app.quit(); process.exit(0); }

const config = createConfig(userData);
const supervisor = createSupervisor({
  serverEntry, binDirectory, dataDirectory, logDirectory: join(userData, 'logs'), getSettings: config.get, onChange: () => notify(),
});
let window = null; let tray = null; let quitting = false;

const base = () => `http://127.0.0.1:${config.get().port}`;
async function admin(path, options = {}) {
  const response = await fetch(`${base()}${path}`, {
    ...options, headers: { 'content-type': 'application/json', 'x-bridge-admin-token': config.get().adminToken, ...options.headers }, signal: AbortSignal.timeout(options.timeoutMs ?? 8000),
  });
  if (!response.ok) throw new Error((await response.json().catch(() => ({}))).error || `HTTP ${response.status}`);
  return response.json();
}
// The catalog editor: titles, synopsis, genres, seasons and relations, posters,
// and each episode's number, title, language and intro/outro ranges. The
// server stores them and sends them to the apps through the usual protocol.
const seriesPath = (id) => `/api/admin/library/series/${encodeURIComponent(id)}`;
const posterTypes = { '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.png': 'image/png', '.webp': 'image/webp' };
ipcMain.handle('catalog-list', () => admin('/api/admin/library/series'));
ipcMain.handle('catalog-detail', (_event, id) => admin(seriesPath(id)));
ipcMain.handle('catalog-save', async (_event, id, changes) => {
  const saved = await admin(seriesPath(id), { method: 'PUT', body: JSON.stringify(changes), timeoutMs: 60000 });
  notify();
  return saved;
});
ipcMain.handle('catalog-poster', async (_event, id) => {
  const picked = await dialog.showOpenDialog(window, { title: 'Elige la portada', properties: ['openFile'], filters: [{ name: 'Imágenes', extensions: ['jpg', 'jpeg', 'png', 'webp'] }] });
  if (picked.canceled || !picked.filePaths.length) return null;
  const file = picked.filePaths[0]; const type = posterTypes[extname(file).toLowerCase()];
  if (!type) throw new Error('La portada debe ser JPG, PNG o WebP');
  if (statSync(file).size > 8 * 1024 * 1024) throw new Error('La portada pesa más de 8 MB');
  const saved = await admin(`${seriesPath(id)}/poster`, { method: 'PUT', body: readFileSync(file), headers: { 'content-type': type }, timeoutMs: 60000 });
  notify();
  return saved;
});
ipcMain.handle('catalog-poster-clear', async (_event, id) => {
  const saved = await admin(`${seriesPath(id)}/poster`, { method: 'DELETE' });
  notify();
  return saved;
});
ipcMain.handle('catalog-marks', (_event, fileId) => admin(`/api/admin/library/episodes/${encodeURIComponent(fileId)}/marks`, { timeoutMs: 90000 }));
// Posters are public on the server, but the window may only load its own files.
ipcMain.handle('catalog-art', async (_event, id, version) => {
  try {
    const response = await fetch(`${base()}/art/${encodeURIComponent(id)}.jpg?v=${encodeURIComponent(version ?? '')}`, { signal: AbortSignal.timeout(60000) });
    if (!response.ok) return null;
    return `data:${response.headers.get('content-type') || 'image/jpeg'};base64,${Buffer.from(await response.arrayBuffer()).toString('base64')}`;
  } catch { return null; }
});

function addresses() {
  const found = [];
  for (const [name, list] of Object.entries(networkInterfaces())) {
    for (const item of list ?? []) {
      if (item.family === 'IPv4' && !item.internal && !item.address.startsWith('169.254.')) found.push({ name, address: item.address });
    }
  }
  return found;
}
async function state() {
  const { port, openAtLogin } = config.get();
  const result = { running: supervisor.running(), port, openAtLogin, addresses: addresses().map((item) => ({ ...item, url: `http://${item.address}:${port}` })), library: null, tools: null, error: null, version: app.getVersion() };
  if (!result.running) return result;
  try {
    const library = await admin('/api/admin/library');
    result.library = { folders: library.folders, series: library.series, files: library.files, scanning: library.scanning, error: library.error };
    result.tools = library.tools;
  } catch (error) { result.error = error.message; }
  return result;
}
function notify() { if (window && !window.isDestroyed()) window.webContents.send('changed'); updateTray(); }

async function saveFolders(folders) {
  await admin('/api/admin/library', { method: 'PUT', body: JSON.stringify({ folders }) });
  notify();
}
ipcMain.handle('state', () => state());
ipcMain.handle('log', () => supervisor.log());
ipcMain.handle('add-folder', async () => {
  const picked = await dialog.showOpenDialog(window, { title: 'Elige una carpeta con vídeos', properties: ['openDirectory', 'multiSelections'] });
  if (picked.canceled || !picked.filePaths.length) return;
  const current = (await admin('/api/admin/library')).folders;
  await saveFolders([...new Set([...current, ...picked.filePaths])]);
});
ipcMain.handle('remove-folder', async (_event, folder) => {
  const current = (await admin('/api/admin/library')).folders;
  await saveFolders(current.filter((item) => item !== folder));
});
ipcMain.handle('rescan', async () => { await admin('/api/admin/library/rescan', { method: 'POST' }); notify(); });
ipcMain.handle('restart', async () => { await supervisor.restart(); });
ipcMain.handle('settings', async (_event, changes) => {
  const before = config.get();
  const after = config.set(changes);
  if ('openAtLogin' in changes) applyLogin();
  if (after.port !== before.port) await supervisor.restart();
  notify();
});
// The QR the apps scan: server address and administrator token, so the app
// is configured and can create its account in one step.
ipcMain.handle('qr', async (_event, address, withAdmin) => {
  const target = String(address || '').replace(/\/+$/, '');
  if (!/^https?:\/\/[\w.\-:[\]]+$/.test(target)) throw new Error('Dirección no válida');
  let link = `relaycast://provision?bridge=${encodeURIComponent(target)}`;
  if (withAdmin) link += `&admin=${encodeURIComponent(config.get().adminToken)}`;
  return { dataUrl: await QRCode.toDataURL(link, { margin: 2, width: 320, errorCorrectionLevel: 'M' }) };
});
ipcMain.handle('copy', (_event, text) => { clipboard.writeText(String(text)); });
ipcMain.handle('copy-admin', () => { clipboard.writeText(config.get().adminToken); });
ipcMain.handle('open-logs', () => shell.openPath(join(userData, 'logs')));
ipcMain.handle('open-data', () => shell.openPath(dataDirectory));

function applyLogin() {
  if (!packaged) return;
  app.setLoginItemSettings({ openAtLogin: config.get().openAtLogin, args: ['--hidden'] });
}
function icon() {
  const path = join(__dirname, '..', 'build', 'icon.png');
  return existsSync(path) ? nativeImage.createFromPath(path) : nativeImage.createEmpty();
}
function createWindow() {
  window = new BrowserWindow({
    width: 860, height: 700, minWidth: 640, minHeight: 520, show: false, title: 'Relaycast', icon: icon(), backgroundColor: '#14141a',
    webPreferences: { preload: join(__dirname, 'preload.cjs'), contextIsolation: true, sandbox: true, nodeIntegration: false },
  });
  window.setMenuBarVisibility(false);
  window.loadFile(join(__dirname, 'renderer', 'index.html'));
  window.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  window.webContents.on('will-navigate', (event) => event.preventDefault());
  window.on('close', (event) => { if (!quitting) { event.preventDefault(); window.hide(); } });
  window.once('ready-to-show', () => { if (!process.argv.includes('--hidden')) window.show(); });
}
function showWindow() {
  if (!window || window.isDestroyed()) createWindow();
  window.show(); window.focus();
}
function updateTray() {
  if (!tray) return;
  tray.setToolTip(`Relaycast — servidor ${supervisor.running() ? 'activo' : 'detenido'}`);
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: 'Abrir Relaycast', click: showWindow },
    { label: supervisor.running() ? 'Servidor activo' : 'Servidor detenido', enabled: false },
    { type: 'separator' },
    { label: 'Reiniciar el servidor', click: () => supervisor.restart() },
    { label: 'Salir', click: () => { quitting = true; app.quit(); } },
  ]));
}

app.on('second-instance', showWindow);
app.on('before-quit', (event) => {
  quitting = true;
  if (!supervisor.running()) return;
  event.preventDefault();
  supervisor.stop().then(() => app.exit(0));
});
app.on('window-all-closed', () => { /* stays in the tray */ });
app.whenReady().then(() => {
  applyLogin();
  tray = new Tray(icon().resize({ width: 16, height: 16 }));
  tray.on('click', showWindow);
  supervisor.start();
  createWindow();
  updateTray();
  setInterval(notify, 4000).unref();
});
