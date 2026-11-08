// version.txt is the single source of truth for the project version. This
// script reads and bumps it and keeps the project manifests in sync:
//   node scripts/version.mjs current
//   node scripts/version.mjs bump patch|minor|major
//   node scripts/version.mjs set 1.2.3
//   node scripts/version.mjs sync
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const versionFile = 'version.txt';
const xcodeProject = 'ios/AirPlaySubtitlePlayer.xcodeproj/project.pbxproj';
const packageFiles = ['server/package.json', 'server/package-lock.json', 'companion/package.json', 'companion/package-lock.json'];
// Files whose only per-release change is the version; see sameIgnoringVersion.
export const syncedFiles = [xcodeProject, ...packageFiles];

export function parseVersion(value) {
  const match = /^v?(\d+)\.(\d+)\.(\d+)$/.exec(String(value ?? '').trim());
  if (!match) throw new Error(`Versión semántica inválida: "${String(value ?? '').trim()}"`);
  return match.slice(1).map(Number);
}
export function formatVersion(parts) { return parts.join('.'); }
export function compareVersions(left, right) {
  const a = parseVersion(left), b = parseVersion(right);
  return a[0] - b[0] || a[1] - b[1] || a[2] - b[2];
}
export function bumpVersion(value, level) {
  const [major, minor, patch] = parseVersion(value);
  if (level === 'major') return formatVersion([major + 1, 0, 0]);
  if (level === 'minor') return formatVersion([major, minor + 1, 0]);
  if (level === 'patch') return formatVersion([major, minor, patch + 1]);
  throw new Error(`Nivel desconocido "${level}"; usa patch, minor o major.`);
}

export function readVersion(root) {
  return formatVersion(parseVersion(readFileSync(join(root, versionFile), 'utf8')));
}

// Sets `"version"` right after the package's own `"name"` (the root object in
// package.json and both root entries in package-lock.json), keeping formatting.
function withPackageVersion(text, version) {
  const name = /"name":\s*"([^"]+)"/.exec(text)?.[1];
  if (!name) return text;
  const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const pattern = new RegExp(`("name":\\s*"${escaped}",(\\r?\\n?[ \\t]*))(?:"version":\\s*"[^"]*",\\2)?`, 'g');
  return text.replace(pattern, (_, prefix, gap) => `${prefix}"version": "${version}",${gap}`);
}

/** Writes version.txt and the synced manifests; returns the files that changed. */
export function writeVersion(root, version) {
  const value = formatVersion(parseVersion(version));
  const changed = [];
  const update = (file, transform) => {
    const path = join(root, file);
    if (!existsSync(path)) return;
    const before = readFileSync(path, 'utf8');
    const after = transform(before);
    if (after !== before) { writeFileSync(path, after); changed.push(file); }
  };
  update(versionFile, () => `${value}\n`);
  update(xcodeProject, (text) => text.replace(/MARKETING_VERSION = [^;]+;/g, `MARKETING_VERSION = ${value};`));
  for (const file of packageFiles) update(file, (text) => withPackageVersion(text, value));
  return changed;
}

/** True when two revisions of a synced file differ only in the version. */
export function sameIgnoringVersion(file, before, after) {
  if (before === null || after === null) return before === after;
  if (file === xcodeProject) {
    const strip = (text) => text.replace(/MARKETING_VERSION = [^;]+;/g, 'MARKETING_VERSION = X;');
    return strip(before) === strip(after);
  }
  if (packageFiles.includes(file)) {
    const strip = (text) => {
      try {
        const value = JSON.parse(text);
        delete value.version;
        if (value.packages?.['']) delete value.packages[''].version;
        return JSON.stringify(value);
      } catch { return text; }
    };
    return strip(before) === strip(after);
  }
  return before === after;
}

function main([command = 'current', argument]) {
  const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
  const current = readVersion(root);
  if (command === 'current') return console.log(current);
  const next = command === 'bump' ? bumpVersion(current, argument)
    : command === 'set' ? formatVersion(parseVersion(argument))
      : command === 'sync' ? current
        : (() => { throw new Error(`Comando desconocido "${command}". Usa current, bump, set o sync.`); })();
  const changed = writeVersion(root, next);
  console.log(command === 'sync' ? `Versión ${next} sincronizada.` : `Versión ${current} → ${next}.`);
  if (changed.length) console.log(`Archivos actualizados: ${changed.join(', ')}. Inclúyelos en tu próximo commit.`);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { main(process.argv.slice(2)); } catch (error) { console.error(error.message); process.exit(1); }
}
