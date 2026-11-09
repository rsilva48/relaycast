// ffprobe from the same b6.1.1 Windows x64 build as ffmpeg-static 5.3.0.
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gunzipSync } from 'node:zlib';

const url = 'https://github.com/eugeneware/ffmpeg-static/releases/download/b6.1.1/ffprobe-win32-x64.gz';
const sha256 = 'f309e6223ad89d2fe54bccd420a7709b66fd27540674e92309578ed491a43c8d';

export async function ffprobePath() {
  if (process.platform !== 'win32' || process.arch !== 'x64') {
    throw new Error('The companion packages Windows x64 binaries; build it on Windows x64.');
  }
  const cache = join(dirname(fileURLToPath(import.meta.url)), '../node_modules/.cache/relaycast');
  mkdirSync(cache, { recursive: true });
  const archive = join(cache, 'ffprobe-win32-x64.gz');
  let bytes;
  if (existsSync(archive)) bytes = readFileSync(archive);
  else {
    const response = await fetch(url, { signal: AbortSignal.timeout(120_000) });
    if (!response.ok) throw new Error(`ffprobe download failed: HTTP ${response.status}`);
    bytes = Buffer.from(await response.arrayBuffer());
  }
  if (createHash('sha256').update(bytes).digest('hex') !== sha256) {
    throw new Error('ffprobe checksum mismatch; remove the cached archive and retry.');
  }
  writeFileSync(archive, bytes);
  const binary = join(cache, 'ffprobe.exe');
  writeFileSync(binary, gunzipSync(bytes));
  return binary;
}
