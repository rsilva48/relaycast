const assert = require('node:assert/strict');
const { mkdtempSync, readFileSync, rmSync } = require('node:fs');
const { tmpdir } = require('node:os');
const { join } = require('node:path');
const { test } = require('node:test');
const { createConfig, validPort } = require('../src/config.cjs');

test('The first run creates a private admin token and keeps it', () => {
  const directory = mkdtempSync(join(tmpdir(), 'relaycast-config-'));
  try {
    const first = createConfig(directory).get();
    assert.match(first.adminToken, /^[a-f0-9]{48}$/);
    assert.equal(first.port, 8787);
    assert.equal(createConfig(directory).get().adminToken, first.adminToken);
    assert.equal(JSON.parse(readFileSync(join(directory, 'config.json'), 'utf8')).adminToken, first.adminToken);
  } finally { rmSync(directory, { recursive: true, force: true }); }
});

test('Only usable ports are accepted', () => {
  const directory = mkdtempSync(join(tmpdir(), 'relaycast-config-'));
  try {
    const config = createConfig(directory);
    assert.equal(config.set({ port: 9000 }).port, 9000);
    assert.throws(() => config.set({ port: 80 }), /1024/);
    assert.throws(() => config.set({ port: 'abc' }), /1024/);
    assert.equal(config.get().port, 9000);
    assert.equal(config.set({ openAtLogin: false }).openAtLogin, false);
    assert.ok(validPort('8787') && !validPort(70000));
  } finally { rmSync(directory, { recursive: true, force: true }); }
});
