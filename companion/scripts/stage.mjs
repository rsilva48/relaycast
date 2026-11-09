// Collects what the installer carries next to the app, into companion/stage:
//   stage/server   the Relaycast server (../server without tests and data)
//   stage/bin      ffmpeg and ffprobe (the same pinned FFmpeg 6.1.1 build)
//   stage/licenses Relaycast's license and notices, and the GPL v3 of the
//                  ffmpeg and ffprobe builds with where their source is
import { copyFileSync, cpSync, existsSync, mkdirSync, rmSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
import { ffprobePath } from './ffprobe.mjs';

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
const ffmpeg = require('ffmpeg-static'); const ffprobe = await ffprobePath();
// Refuse mismatched binaries, including environment overrides of ffmpeg-static.
for (const [binary, name] of [[ffmpeg, 'ffmpeg'], [ffprobe, 'ffprobe']]) {
  const version = execFileSync(binary, ['-version'], { encoding: 'utf8' }).split(/\r?\n/)[0];
  if (!version.startsWith(`${name} version 6.1.1-essentials_build-www.gyan.dev `)) {
    throw new Error(`Unexpected ${name} build: ${version}`);
  }
  console.log(version);
}
for (const [source, name] of [[ffmpeg, 'ffmpeg.exe'], [ffprobe, 'ffprobe.exe']]) {
  if (!source || !existsSync(source)) throw new Error(`${name} was not found; run npm install (and approve the ffmpeg-static install script).`);
  copyFileSync(source, join(stage, 'bin', name));
}
// ffmpeg and ffprobe are GPL v3 builds: the installer carries their license
// and where their source code is (THIRD_PARTY_NOTICES.md lists them too).
const licenses = join(stage, 'licenses');
mkdirSync(licenses, { recursive: true });
for (const name of ['LICENSE', 'NOTICE', 'THIRD_PARTY_NOTICES.md']) copyFileSync(join(root, '..', name), join(licenses, name));
const ffmpegDir = dirname(ffmpeg);
for (const [source, name] of [['ffmpeg.exe.LICENSE', 'ffmpeg-LICENSE.txt'], ['ffmpeg.exe.README', 'ffmpeg-README.txt']]) {
  if (!existsSync(join(ffmpegDir, source))) throw new Error(`${source} was not found next to ffmpeg-static's binary.`);
  copyFileSync(join(ffmpegDir, source), join(licenses, name));
}
console.log(`Staged server, ffmpeg and licenses in ${stage}`);
