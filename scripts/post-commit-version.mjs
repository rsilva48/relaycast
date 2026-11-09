// Local post-commit hook (.githooks/post-commit). It only touches this clone:
// - A commit that does not change version.txt gets the next patch version:
//   version.txt and the synced manifests are amended into that same commit.
// - A commit that already changes version.txt (for example after the
//   "Versión: subir menor/mayor" tasks) keeps that version as is.
// Either way the commit receives the annotated tag vX.Y.Z; pushing with
// --follow-tags publishes it. Rebases, merges and cherry-picks are skipped.
import { execFileSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { join } from 'node:path';
import { bumpVersion, compareVersions, formatVersion, parseVersion, syncedFiles, versionFile, writeVersion } from './version.mjs';

const git = (...args) => execFileSync('git', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
const tryGit = (...args) => { try { return git(...args); } catch { return null; } };
const log = (message) => console.log(`[versión] ${message}`);

function run() {
  if (process.env.RELAYCAST_VERSION_HOOK) return; // The amend below re-enters this hook.
  const root = git('rev-parse', '--show-toplevel');
  const gitDir = git('rev-parse', '--absolute-git-dir');
  const replaying = ['rebase-merge', 'rebase-apply', 'sequencer', 'MERGE_HEAD', 'CHERRY_PICK_HEAD', 'REVERT_HEAD'];
  if (replaying.some((name) => existsSync(join(gitDir, name)))) return;
  if (tryGit('symbolic-ref', '-q', 'HEAD') === null) return;
  if (git('tag', '--points-at', 'HEAD').split(/\r?\n/).some((tag) => /^v\d+\.\d+\.\d+$/.test(tag))) return;

  const committed = tryGit('show', `HEAD:${versionFile}`);
  if (committed === null) return log(`No hay ${versionFile} en el commit; no se crea versión.`);
  const previous = tryGit('show', `HEAD^:${versionFile}`);
  let version = formatVersion(parseVersion(committed));

  if (previous !== null && compareVersions(previous, version) === 0) {
    const tracked = [versionFile, ...syncedFiles].filter((file) => existsSync(join(root, file)));
    if (git('status', '--porcelain', '--', ...tracked)) {
      return log(`${tracked.join(', ')} tiene cambios sin confirmar; inclúyelos en el commit o descártalos. No se creó versión.`);
    }
    // Never reuse a published number, even if version.txt fell behind the tags.
    const highest = git('tag', '--list', 'v[0-9]*').split(/\r?\n/)
      .filter((tag) => /^v\d+\.\d+\.\d+$/.test(tag))
      .reduce((max, tag) => (compareVersions(tag, max) > 0 ? tag.slice(1) : max), version);
    version = bumpVersion(highest, 'patch');
    const changed = writeVersion(root, version);
    execFileSync('git', ['commit', '--amend', '--no-edit', '--no-verify', '--only', '--', ...changed], {
      cwd: root, stdio: ['ignore', 'ignore', 'inherit'], env: { ...process.env, RELAYCAST_VERSION_HOOK: '1' },
    });
    log(`Parche automático: ${version} (incluido en el mismo commit).`);
  }

  const tag = `v${version}`;
  const existing = tryGit('rev-parse', '-q', '--verify', `refs/tags/${tag}^{commit}`);
  if (existing) {
    // An amended commit moves its own unpublished tag; published tags never move.
    if (tryGit('branch', '-r', '--contains', existing)) {
      return log(`${tag} ya existe en un commit publicado; usa una tarea de versión para elegir otra.`);
    }
    git('tag', '-f', '-a', tag, '-m', `Release ${tag}`);
    return log(`${tag} movida al commit actual.`);
  }
  git('tag', '-a', tag, '-m', `Release ${tag}`);
  log(`Etiqueta ${tag} creada. Publica con "Git: publicar commits y releases".`);
}

try {
  run();
} catch (error) {
  console.error(`[versión] ${error.message}`);
  process.exitCode = 1;
}
