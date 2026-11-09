// Companion settings: a small JSON file in the user's data folder. The admin
// token is created on first run and never leaves this computer except inside
// the setup QR the user chooses to show.
const { randomBytes } = require('node:crypto');
const { mkdirSync, readFileSync, renameSync, writeFileSync } = require('node:fs');
const { dirname, join } = require('node:path');

const defaults = { port: 8787, openAtLogin: true, adminToken: '' };

function createConfig(directory) {
  const path = join(directory, 'config.json');
  let value = { ...defaults };
  try { value = { ...defaults, ...JSON.parse(readFileSync(path, 'utf8')) }; } catch { /* first run */ }
  if (!/^[a-f0-9]{32,}$/.test(value.adminToken)) value.adminToken = randomBytes(24).toString('hex');
  value.port = validPort(value.port) ? Number(value.port) : defaults.port;
  function save() {
    mkdirSync(dirname(path), { recursive: true });
    const temporary = `${path}.${process.pid}.tmp`;
    writeFileSync(temporary, JSON.stringify(value, null, 2), 'utf8');
    renameSync(temporary, path);
  }
  save();
  return {
    get: () => ({ ...value }),
    set(changes) {
      if ('port' in changes) {
        if (!validPort(changes.port)) throw new Error('El puerto debe estar entre 1024 y 65535.');
        value.port = Number(changes.port);
      }
      if ('openAtLogin' in changes) value.openAtLogin = Boolean(changes.openAtLogin);
      save();
      return { ...value };
    },
  };
}
function validPort(port) {
  const number = Number(port);
  return Number.isInteger(number) && number >= 1024 && number <= 65535;
}

module.exports = { createConfig, validPort };
