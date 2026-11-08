const $ = (id) => document.getElementById(id);
let current = null; let editingPort = false; let shownAddress = null; let shownAdmin = null;

function text(node, value) { if (node.textContent !== value) node.textContent = value; }

function renderFolders(folders) {
  const list = $('folders');
  const signature = JSON.stringify(folders);
  if (list.dataset.signature === signature) return;
  list.dataset.signature = signature; list.replaceChildren();
  for (const folder of folders) {
    const item = document.createElement('li');
    const name = document.createElement('span'); name.textContent = folder;
    const remove = document.createElement('button'); remove.className = 'link'; remove.textContent = 'Quitar';
    remove.addEventListener('click', () => run(() => window.companion.removeFolder(folder)));
    item.append(name, remove); list.append(item);
  }
}
async function renderQr() {
  const address = $('address').value; const withAdmin = $('withAdmin').checked;
  if (!address || (address === shownAddress && withAdmin === shownAdmin)) return;
  shownAddress = address; shownAdmin = withAdmin;
  try { $('qr').src = (await window.companion.qr(address, withAdmin)).dataUrl; } catch { $('qr').removeAttribute('src'); }
}
function renderAddresses(addresses) {
  const select = $('address'); const previous = select.value;
  const urls = addresses.map((item) => item.url);
  if (select.dataset.signature !== JSON.stringify(urls)) {
    select.dataset.signature = JSON.stringify(urls); select.replaceChildren();
    for (const item of addresses) { const option = document.createElement('option'); option.value = item.url; option.textContent = `${item.url}   (${item.name})`; select.append(option); }
    if (urls.includes(previous)) select.value = previous;
  }
  if (!addresses.length) { const option = document.createElement('option'); option.textContent = 'Sin conexión de red'; select.replaceChildren(option); }
}
function render(state) {
  current = state;
  const badge = $('badge');
  badge.className = `badge ${state.running && !state.error ? 'ok' : 'bad'}`;
  text(badge, state.running ? (state.error ? 'Servidor con problemas' : 'Servidor activo') : 'Servidor detenido');

  const problem = $('problem'); const issues = [];
  if (!state.running) issues.push('El servidor no está en marcha. Se reintentará solo; si no arranca, mira el registro más abajo (¿otro programa usa el puerto?).');
  else if (state.error) issues.push(`No se pudo consultar el servidor: ${state.error}`);
  if (state.tools && !state.tools.ffmpeg) issues.push('Falta ffmpeg, que prepara los vídeos para reproducirlos. Reinstala Relaycast.');
  if (state.library?.error) issues.push(`No se pudo leer la biblioteca: ${state.library.error}`);
  problem.hidden = !issues.length; problem.className = `notice ${state.running ? '' : 'bad'}`; text(problem, issues.join(' '));

  renderFolders(state.library?.folders ?? []);
  const counts = state.library
    ? `${state.library.scanning ? 'Buscando… ' : ''}${state.library.series} series o películas · ${state.library.files} vídeos`
    : '';
  text($('counts'), counts);
  renderAddresses(state.addresses);
  renderQr();
  $('openAtLogin').checked = state.openAtLogin;
  if (!editingPort) $('port').value = state.port;
  text($('version'), `Relaycast ${state.version}`);
}
async function refresh() {
  try { render(await window.companion.state()); } catch { /* the window is closing */ }
  const log = await window.companion.log();
  const node = $('log'); const atEnd = node.scrollTop + node.clientHeight >= node.scrollHeight - 8;
  text(node, log.join('\n'));
  if (atEnd) node.scrollTop = node.scrollHeight;
}
async function run(action) {
  try { await action(); } catch (error) { text($('problem'), error.message); $('problem').hidden = false; }
  refresh();
}

$('add').addEventListener('click', () => run(() => window.companion.addFolder()));
$('rescan').addEventListener('click', () => run(() => window.companion.rescan()));
$('copy').addEventListener('click', () => window.companion.copy($('address').value));
$('copyAdmin').addEventListener('click', () => window.companion.copyAdmin());
$('address').addEventListener('change', renderQr);
$('withAdmin').addEventListener('change', renderQr);
$('openAtLogin').addEventListener('change', () => run(() => window.companion.settings({ openAtLogin: $('openAtLogin').checked })));
$('port').addEventListener('input', () => { editingPort = true; text($('portError'), ''); });
$('savePort').addEventListener('click', async () => {
  try { await window.companion.settings({ port: Number($('port').value) }); editingPort = false; text($('portError'), ''); shownAddress = null; }
  catch (error) { text($('portError'), String(error.message).replace(/^Error invoking remote method '[^']+': (?:Error: )?/, '')); }
  refresh();
});
$('restart').addEventListener('click', () => run(() => window.companion.restart()));
$('openData').addEventListener('click', () => window.companion.openData());
$('openLogs').addEventListener('click', () => window.companion.openLogs());
window.companion.onChange(refresh);
refresh();
