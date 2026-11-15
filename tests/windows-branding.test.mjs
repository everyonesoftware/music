import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, existsSync, mkdtempSync, rmSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { inflateSync } from 'node:zlib';

const root = new URL('../', import.meta.url);
const sizes = [16, 20, 24, 32, 40, 48, 64, 96, 128, 256];

test('executable and runtime branding assets are included in build and publish', () => {
  const project = readFileSync(new URL('apps/windows/Music.App/Music.App.csproj', root), 'utf8');
  assert.match(project, /<ApplicationIcon>Assets[\\/]Music\.ico<\/ApplicationIcon>/);
  const removal = project.indexOf('<Content Remove="Assets/Music.ico;Assets/Music.png"');
  assert.ok(removal >= 0, 'remove implicit WinUI/SDK icon content before adding publish metadata');
  assert.ok(removal < project.indexOf('<Content Include="Assets/Music.ico"'));
  for (const asset of ['Music.ico', 'Music.png']) {
    const content = project.match(new RegExp(`<Content Include="Assets[\\\\/]${asset.replace('.', '\\.')}"[^>]+/>`))?.[0];
    assert.ok(content, `${asset} must be runtime content`);
    assert.match(content, /CopyToOutputDirectory="PreserveNewest"/);
    assert.match(content, /CopyToPublishDirectory="PreserveNewest"/);
  }
});
test('release packaging passes the shared ICO through the pinned VPK_ICON option', () => {
  const workflow = readFileSync(new URL('.github/workflows/windows-release.yml', root), 'utf8');
  const build = workflow.split('- name: Build real self-contained payload, installer and integrity manifest')[1]?.split('\n      - name:')[0];
  assert.ok(build, 'native installer build step must exist');
  assert.match(build, /VPK_ICON:.*github\.workspace.*\/apps\/windows\/Music\.App\/Assets\/Music\.ico/);
  assert.match(workflow, /node --test[^\n]*tests\/windows-branding\.test\.mjs/);
});

function pngPixels(bytes, size) {
  assert.deepEqual([...bytes.subarray(0, 8)], [137, 80, 78, 71, 13, 10, 26, 10]);
  assert.equal(bytes.readUInt32BE(16), size);
  assert.equal(bytes.readUInt32BE(20), size);
  assert.equal(bytes[24], 8);
  assert.equal(bytes[25], 6);
  const chunks = [];
  for (let offset = 8; offset < bytes.length;) {
    const length = bytes.readUInt32BE(offset);
    if (bytes.toString('ascii', offset + 4, offset + 8) === 'IDAT') chunks.push(bytes.subarray(offset + 8, offset + 8 + length));
    offset += length + 12;
  }
  const raw = inflateSync(Buffer.concat(chunks));
  assert.equal(raw.length, size * (size * 4 + 1));
  for (let row = 0; row < size; row++) assert.equal(raw[row * (size * 4 + 1)], 0);
  return (x, y) => [...raw.subarray(y * (size * 4 + 1) + 1 + x * 4, y * (size * 4 + 1) + 5 + x * 4)];
}

test('generator creates a deterministic multi-size ICO from Android equalizer artwork', () => {
  const generator = new URL('scripts/generate-windows-icons.mjs', root);
  assert.ok(existsSync(generator), 'Android-to-Windows icon generator is missing');
  const output = mkdtempSync(join(tmpdir(), 'music-branding-'));
  try {
    const run = spawnSync(process.execPath, [fileURLToPath(generator), '--output-directory', output], { encoding: 'utf8' });
    assert.equal(run.status, 0, run.stderr);
    const ico = readFileSync(join(output, 'Music.ico'));
    assert.equal(ico.readUInt16LE(0), 0);
    assert.equal(ico.readUInt16LE(2), 1);
    assert.equal(ico.readUInt16LE(4), sizes.length);
    for (const [index, size] of sizes.entries()) {
      const offset = 6 + index * 16;
      assert.equal(ico[offset] || 256, size);
      assert.equal(ico[offset + 1] || 256, size);
      assert.equal(ico.readUInt16LE(offset + 6), 32);
      const bytes = ico.subarray(ico.readUInt32LE(offset + 12), ico.readUInt32LE(offset + 12) + ico.readUInt32LE(offset + 8));
      const pixel = pngPixels(bytes, size);
      assert.deepEqual(pixel(0, 0), [28, 33, 31, 255]);
      // Sample all four bars and both gaps, using Android's original 108dp viewport.
      for (const [x, y] of [[30, 54], [46, 54], [62, 54], [78, 54]]) {
        const actual = pixel(Math.floor(x * size / 108), Math.floor(y * size / 108));
        if (size >= 32) assert.deepEqual(actual, [192, 215, 174, 255]);
        else assert.ok(actual[0] > 90 && actual[1] > 100 && actual[3] === 255, 'small equalizer bars must survive antialiasing');
      }
      assert.deepEqual(pixel(Math.floor(54 * size / 108), Math.floor(20 * size / 108)), [28, 33, 31, 255]);
    }
    for (const name of ['Music.ico', 'Music.png', 'Music.svg']) {
      assert.deepEqual(readFileSync(join(output, name)), readFileSync(new URL(`apps/windows/Music.App/Assets/${name}`, root)), `${name} is stale; regenerate it`);
    }
    pngPixels(readFileSync(join(output, 'Music.png')), 256);
    const svg = readFileSync(join(output, 'Music.svg'), 'utf8');
    const android = readFileSync(new URL('android/app/src/main/res/drawable/ic_audio_foreground.xml', root), 'utf8');
    assert.ok(svg.includes(android.match(/android:pathData="([^"]+)"/)[1]), 'SVG must preserve the actual Android path');
  } finally { rmSync(output, { recursive: true, force: true }); }
});
