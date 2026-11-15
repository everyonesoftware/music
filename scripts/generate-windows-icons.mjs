// Reuse Android's source artwork. No image packages or platform tooling required.
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { deflateSync } from 'node:zlib';

const root = fileURLToPath(new URL('../', import.meta.url));
const args = process.argv.slice(2);
if (args.length && (args.length !== 2 || args[0] !== '--output-directory')) throw new Error('Usage: node scripts/generate-windows-icons.mjs [--output-directory PATH]');
const output = args.length ? resolve(args[1]) : resolve(root, 'apps/windows/Music.App/Assets');
const foreground = readFileSync(resolve(root, 'android/app/src/main/res/drawable/ic_audio_foreground.xml'), 'utf8');
const colors = readFileSync(resolve(root, 'android/app/src/main/res/values/icon_colors.xml'), 'utf8');
const attribute = name => {
  const value = foreground.match(new RegExp(`android:${name}="([^"]+)"`))?.[1];
  if (!value) throw new Error(`Android vector is missing ${name}`);
  return value;
};
const width = Number(attribute('viewportWidth')), height = Number(attribute('viewportHeight'));
const path = attribute('pathData');
// Fail closed if the upstream artwork stops being rectangular bars: never silently
// generate a stale approximation after an Android redesign.
const rectangles = [...path.matchAll(/M([\d.]+),([\d.]+)h([\d.]+)v([\d.]+)h-([\d.]+)z/g)];
if (!rectangles.length || rectangles.map(match => match[0]).join('') !== path || rectangles.some(match => match[3] !== match[5])) throw new Error('Unsupported Android icon geometry; extend the rasterizer for the new artwork');
const bars = rectangles.map(match => match.slice(1, 5).map(Number));
const backgroundHex = colors.match(/name="audio_icon_background">(#[0-9A-Fa-f]{6})</)?.[1];
const foregroundHex = attribute('fillColor');
const rgb = hex => {
  if (!/^#[0-9a-f]{6}$/i.test(hex ?? '')) throw new Error('Icon colors must be opaque RGB');
  return [1, 3, 5].map(offset => parseInt(hex.slice(offset, offset + 2), 16));
};
const background = rgb(backgroundHex), ink = rgb(foregroundHex);
function crc32(bytes) {
  let crc = 0xffffffff;
  for (const byte of bytes) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0);
  }
  return (crc ^ 0xffffffff) >>> 0;
}
function chunk(type, data) {
  const bytes = Buffer.alloc(data.length + 12);
  bytes.writeUInt32BE(data.length); bytes.write(type, 4); data.copy(bytes, 8);
  bytes.writeUInt32BE(crc32(bytes.subarray(4, -4)), bytes.length - 4);
  return bytes;
}
function png(size) {
  const raw = Buffer.alloc(size * (1 + size * 4));
  for (let y = 0; y < size; y++) for (let x = 0; x < size; x++) {
    // Exact area coverage antialiasing of the original vector rectangles.
    const left = x * width / size, top = y * height / size;
    const right = (x + 1) * width / size, bottom = (y + 1) * height / size;
    let area = 0;
    for (const [bx, by, bw, bh] of bars) area += Math.max(0, Math.min(right, bx + bw) - Math.max(left, bx)) * Math.max(0, Math.min(bottom, by + bh) - Math.max(top, by));
    const coverage = Math.min(1, area / ((right - left) * (bottom - top)));
    const offset = y * (1 + size * 4) + 1 + x * 4;
    for (let channel = 0; channel < 3; channel++) raw[offset + channel] = Math.round(background[channel] + (ink[channel] - background[channel]) * coverage);
    raw[offset + 3] = 255;
  }
  const header = Buffer.alloc(13);
  header.writeUInt32BE(size); header.writeUInt32BE(size, 4); header[8] = 8; header[9] = 6;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', header), chunk('IDAT', deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))]);
}
const sizes = [16, 20, 24, 32, 40, 48, 64, 96, 128, 256];
const images = sizes.map(png);
const directory = Buffer.alloc(6 + sizes.length * 16);
directory.writeUInt16LE(1, 2); directory.writeUInt16LE(sizes.length, 4);
let position = directory.length;
for (const [index, size] of sizes.entries()) {
  const offset = 6 + index * 16;
  directory[offset] = directory[offset + 1] = size % 256;
  directory.writeUInt16LE(1, offset + 4); directory.writeUInt16LE(32, offset + 6);
  directory.writeUInt32LE(images[index].length, offset + 8); directory.writeUInt32LE(position, offset + 12);
  position += images[index].length;
}
mkdirSync(output, { recursive: true });
writeFileSync(resolve(output, 'Music.ico'), Buffer.concat([directory, ...images]));
writeFileSync(resolve(output, 'Music.png'), images.at(-1));
writeFileSync(resolve(output, 'Music.svg'), `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${width} ${height}"><rect width="${width}" height="${height}" fill="${backgroundHex}"/><path fill="${foregroundHex}" d="${path}"/></svg>\n`);
console.log(`Generated Android equalizer branding: ${sizes.join(', ')}px ICO, 256px PNG, source SVG in ${output}`);
