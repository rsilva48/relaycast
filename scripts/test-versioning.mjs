import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { cpSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

// Runs the real post-commit hook in a throwaway repository.
const scripts = dirname(fileURLToPath(import.meta.url));
const repo = mkdtempSync(join(tmpdir(), 'relaycast-version-test-'));
const git = (...args) => execFileSync('git', args, { cwd: repo, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
const write = (file, text) => { mkdirSync(dirname(join(repo, file)), { recursive: true }); writeFileSync(join(repo, file), text); };
const read = (file) => readFileSync(join(repo, file), 'utf8');
const commit = (message, ...files) => { git('add', ...(files.length ? files : ['-A'])); git('commit', '-q', '-m', message); };
const tagsAtHead = () => git('tag', '--points-at', 'HEAD');

cpSync(join(scripts, 'version.mjs'), join(repo, 'scripts/version.mjs'));
cpSync(join(scripts, 'post-commit-version.mjs'), join(repo, 'scripts/post-commit-version.mjs'));
cpSync(join(scripts, 'changed-components.mjs'), join(repo, 'scripts/changed-components.mjs'));
write('.githooks/post-commit', '#!/bin/sh\nnode "$(git rev-parse --show-toplevel)/scripts/post-commit-version.mjs"\n');
git('init', '-q', '-b', 'main');
for (const [key, value] of [['user.email', 'version-test@example.invalid'], ['user.name', 'Version test'], ['commit.gpgsign', 'false'], ['tag.gpgsign', 'false'], ['core.hooksPath', '.githooks'], ['core.autocrlf', 'false']]) git('config', key, value);

// 1. The first version.txt is tagged as written.
write('version.txt', '0.1.0\n');
write('ios/AirPlaySubtitlePlayer.xcodeproj/project.pbxproj', '{ MARKETING_VERSION = 0.1.0; }\n');
write('server/package.json', '{\n  "name": "server",\n  "private": true\n}\n');
commit('chore: start');
assert.equal(tagsAtHead(), 'v0.1.0');

// 2. A normal commit gets the next patch amended into itself.
write('a.txt', 'a\n');
commit('feat: a');
assert.equal(tagsAtHead(), 'v0.1.1');
assert.equal(git('show', 'HEAD:version.txt'), '0.1.1');
assert.match(git('show', 'HEAD:ios/AirPlaySubtitlePlayer.xcodeproj/project.pbxproj'), /MARKETING_VERSION = 0\.1\.1;/);
assert.match(git('show', 'HEAD:server/package.json'), /"name": "server",\n {2}"version": "0\.1\.1",/);
assert.equal(git('show', '-s', '--format=%s', 'HEAD'), 'feat: a');
assert.equal(git('rev-list', '--count', 'HEAD'), '2');
assert.equal(git('status', '--porcelain'), '');
// Version-only manifest changes do not count as app or server changes in CI.
const components = (base, head) => execFileSync(process.execPath, [join(repo, 'scripts/changed-components.mjs'), base, head], { cwd: repo, encoding: 'utf8' }).trim();
assert.equal(components('v0.1.0', 'v0.1.1'), 'app=false\nserver=false\nandroid=false');
write('server/server.mjs', 'export {};\n');
commit('feat: server');
assert.equal(components('v0.1.1', 'HEAD'), 'app=false\nserver=true\nandroid=false');
git('tag', '-d', 'v0.1.2');
git('reset', '-q', '--hard', 'v0.1.1');

// 3. A minor bump made with the task is kept, without an extra patch.
execFileSync(process.execPath, [join(repo, 'scripts/version.mjs'), 'bump', 'minor'], { cwd: repo });
assert.equal(read('version.txt'), '0.2.0\n');
write('b.txt', 'b\n');
commit('feat: b');
assert.equal(tagsAtHead(), 'v0.2.0');
assert.equal(git('show', 'HEAD:version.txt'), '0.2.0');

// 4. Amending a commit moves its unpublished tag instead of bumping again.
write('c.txt', 'c\n');
commit('fix: c');
assert.equal(tagsAtHead(), 'v0.2.1');
git('commit', '-q', '--amend', '-m', 'fix: c, reworded');
assert.equal(tagsAtHead(), 'v0.2.1');
assert.equal(git('tag', '--list', 'v0.2.2'), '');

// 5. A bump left out of the commit is reported and nothing is tagged.
execFileSync(process.execPath, [join(repo, 'scripts/version.mjs'), 'bump', 'major'], { cwd: repo });
write('d.txt', 'd\n');
commit('docs: d', 'd.txt');
assert.equal(tagsAtHead(), '');
assert.equal(read('version.txt'), '1.0.0\n');

console.log('Versioning verified: initial tag, automatic patch amend, task bumps, amend retag and unstaged-bump guard.');
