// Collects what the installer carries next to the app, into companion/stage:
//   stage/server   the Relaycast server (../server without tests and data)
//   stage/bin      ffmpeg and ffprobe (from the ffmpeg-static / ffprobe-static packages)
import { copyFileSync, cpSync, existsSync, mkdirSync, rmSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..'); const stage = join(root, 'stage');
const require = createRequire(import.meta.url);

rmSync(stage, { recursive: true, force: true });
mkdirSync(join(stage, 'bin'), { recursive: true });
const serverSource = join(root, '..', 'server');
cpSync(serverSource, join(stage, 'server'), {
  recursive: true,
  filter: (path) => !/[\\/](?:node_modules|data|test|\.npmrc|\.nvmrc)(?:[\\/]|$)/.test(path.slice(serverSource.length)),
});
const ffmpeg = require('ffmpeg-static'); const ffprobe = require('ffprobe-static').path;
for (const [source, name] of [[ffmpeg, 'ffmpeg.exe'], [ffprobe, 'ffprobe.exe']]) {
  if (!source || !existsSync(source)) throw new Error(`${name} was not found; run npm install (and approve the ffmpeg-static install script).`);
  copyFileSync(source, join(stage, 'bin', name));
}
console.log(`Staged server and ffmpeg in ${stage}`);
