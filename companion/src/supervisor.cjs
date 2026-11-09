// Runs the Relay Server as a child process and keeps it alive. The server
// is plain Node code: it runs on the Electron executable in "run as node" mode,
// so nothing else has to be installed on the computer.
const { spawn } = require('node:child_process');
const { appendFileSync, mkdirSync, renameSync, statSync } = require('node:fs');
const { join } = require('node:path');

function createSupervisor({ serverEntry, binDirectory, dataDirectory, logDirectory, getSettings, onChange = () => {} }) {
  let child = null; let stopping = false; let restartTimer = null; let attempts = 0; let startedAt = 0;
  const lines = [];
  const logPath = join(logDirectory, 'server.log');
  mkdirSync(logDirectory, { recursive: true });
  try { if (statSync(logPath).size > 2_000_000) renameSync(logPath, `${logPath}.1`); } catch { /* no log yet */ }

  function record(text) {
    for (const line of String(text).split(/\r?\n/).filter(Boolean)) {
      const stamped = `${new Date().toLocaleTimeString()}  ${line}`;
      lines.push(stamped); if (lines.length > 300) lines.shift();
      try { appendFileSync(logPath, `${new Date().toISOString()}  ${line}\n`); } catch { /* the log is best effort */ }
    }
  }
  function start() {
    if (child) return;
    stopping = false;
    const { port, adminToken } = getSettings();
    const exe = (name) => join(binDirectory, process.platform === 'win32' ? `${name}.exe` : name);
    child = spawn(process.execPath, [serverEntry], {
      cwd: dataDirectory, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'],
      env: {
        ...process.env, ELECTRON_RUN_AS_NODE: '1', SERVER_HOST: '0.0.0.0', SERVER_PORT: String(port), SERVER_ADMIN_TOKEN: adminToken,
        HISTORY_STORE_PATH: join(dataDirectory, 'history.json'), SERVER_USER_STORE_PATH: join(dataDirectory, 'users.json'), SERVER_LIBRARY_CONFIG_PATH: join(dataDirectory, 'library.json'),
        FFMPEG_PATH: exe('ffmpeg'), FFPROBE_PATH: exe('ffprobe'),
      },
    });
    startedAt = Date.now();
    record(`Servidor iniciado (puerto ${port}).`);
    child.stdout.on('data', record); child.stderr.on('data', record);
    child.on('error', (error) => record(`No se pudo iniciar el servidor: ${error.message}`));
    child.on('exit', (code) => {
      child = null;
      record(`El servidor se detuvo (código ${code}).`);
      onChange();
      if (stopping) return;
      // A server that keeps dying at once (port taken, broken install) backs off.
      attempts = Date.now() - startedAt > 30_000 ? 0 : attempts + 1;
      restartTimer = setTimeout(start, Math.min(30_000, 1000 * 2 ** attempts));
    });
    onChange();
  }
  function stop() {
    stopping = true; clearTimeout(restartTimer);
    return new Promise((resolve) => {
      if (!child) return resolve();
      const timer = setTimeout(() => { child?.kill('SIGKILL'); }, 4000);
      child.once('exit', () => { clearTimeout(timer); resolve(); });
      child.kill();
    });
  }
  async function restart() { await stop(); start(); }
  return { start, stop, restart, running: () => Boolean(child), log: () => lines.slice() };
}

module.exports = { createSupervisor };
