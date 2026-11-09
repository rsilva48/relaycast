// Draws the app icon (a rounded square with a play triangle) as build/icon.png
// and build/icon.ico, with no image library: PNG chunks are written by hand.
import { deflateSync } from 'node:zlib';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', 'build');
const size = 256;
const pixels = Buffer.alloc(size * size * 4);
const inside = (x, y, radius) => {
  const dx = Math.max(radius - x, 0, x - (size - 1 - radius)); const dy = Math.max(radius - y, 0, y - (size - 1 - radius));
  return dx * dx + dy * dy <= radius * radius;
};
const triangle = [[96, 70], [96, 186], [196, 128]];
const side = (a, b, p) => (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0]);
const inTriangle = (x, y) => {
  const p = [x, y]; const signs = [side(triangle[0], triangle[1], p), side(triangle[1], triangle[2], p), side(triangle[2], triangle[0], p)];
  return signs.every((v) => v >= 0) || signs.every((v) => v <= 0);
};
for (let y = 0; y < size; y += 1) {
  for (let x = 0; x < size; x += 1) {
    const offset = (y * size + x) * 4;
    if (!inside(x, y, 52)) continue;
    const mix = (x + y) / (size * 2);
    const color = inTriangle(x, y) ? [255, 255, 255] : [Math.round(108 - 40 * mix), Math.round(107 - 50 * mix), Math.round(242 - 30 * mix)];
    pixels.set([...color, 255], offset);
  }
}
const crcTable = Array.from({ length: 256 }, (_, n) => { let c = n; for (let k = 0; k < 8; k += 1) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
const crc = (buffer) => { let c = 0xffffffff; for (const byte of buffer) c = crcTable[(c ^ byte) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
const chunk = (type, data) => {
  const head = Buffer.alloc(8); head.writeUInt32BE(data.length, 0); head.write(type, 4, 'latin1');
  const tail = Buffer.alloc(4); tail.writeUInt32BE(crc(Buffer.concat([head.subarray(4), data])), 0);
  return Buffer.concat([head, data, tail]);
};
const header = Buffer.alloc(13); header.writeUInt32BE(size, 0); header.writeUInt32BE(size, 4); header.set([8, 6, 0, 0, 0], 8);
const rows = Buffer.alloc((size * 4 + 1) * size);
for (let y = 0; y < size; y += 1) pixels.copy(rows, y * (size * 4 + 1) + 1, y * size * 4, (y + 1) * size * 4);
const png = Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk('IHDR', header), chunk('IDAT', deflateSync(rows, { level: 9 })), chunk('IEND', Buffer.alloc(0))]);

// An .ico may hold a PNG directly (256 is written as 0).
const ico = Buffer.alloc(22); ico.writeUInt16LE(1, 2); ico.writeUInt16LE(1, 4); ico.set([0, 0, 0, 0], 6); ico.writeUInt16LE(1, 10); ico.writeUInt16LE(32, 12);
ico.writeUInt32LE(png.length, 14); ico.writeUInt32LE(22, 18);
mkdirSync(root, { recursive: true });
writeFileSync(join(root, 'icon.png'), png);
writeFileSync(join(root, 'icon.ico'), Buffer.concat([ico, png]));
console.log(`Icon written to ${root}`);
