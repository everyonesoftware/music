#!/usr/bin/env node
// Publish the unchanged Velopack inventory on the normal stable Music release.
// windows-v/signing=unsigned-preview inside the manifest are legacy packaging
// compatibility fields only. Never create/delete a legacy bridge release here.
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdtempSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, resolve, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { deriveSource, fullInventory, validateFullPackage, attachWindowsInstaller } from './attach-windows-installer.mjs';
import { main as ensureMainRelease } from './ensure-windows-main-release.mjs';
import { generateChangelog, enrichManifest } from './release-changelog.mjs';

export async function main(argv = process.argv.slice(2), { root = resolve(dirname(fileURLToPath(import.meta.url)), '..'), run = spawnSync, generate = generateChangelog, output = text => process.stdout.write(text) } = {}) {
  const options = {};
  for (let i = 0; i < argv.length; i += 2) {
    const key = argv[i], value = argv[i + 1];
    if (!['--directory', '--repository', '--android-changed', '--changelog'].includes(key) || Object.hasOwn(options, key) || typeof value !== 'string' || !value || value.startsWith('-')) throw new Error('Usage: node scripts/publish-windows-main-release.mjs --directory PATH --repository OWNER/REPO --android-changed true|false');
    options[key] = value;
  }
  const repository = options['--repository'];
  if (typeof repository !== 'string' || !/^[A-Za-z0-9_][A-Za-z0-9_.-]*\/[A-Za-z0-9_][A-Za-z0-9_.-]*$/.test(repository) || /\s/.test(repository) || repository.split('/').some(p => p === '.' || p === '..')) throw new Error('Invalid GitHub repository.');
  if (!options['--directory']) throw new Error('--directory is required.');
  if (!['true', 'false'].includes(options['--android-changed'])) throw new Error('--android-changed true|false is required.');
  if (options['--changelog'] !== undefined && options['--changelog'] !== 'true') throw new Error('--changelog must be true when supplied.');
  root = resolve(root);
  const boundedRun = (command, args, extra) => run(command, args, { ...extra, shell: false, timeout: 120_000 });
  const source = deriveSource(root, { run: boundedRun });
  const clean = () => {
    const status = boundedRun('git', ['-C', root, 'status', '--porcelain', '--untracked-files=all'], { encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 });
    if (status.error || status.status !== 0 || String(status.stdout ?? '').trim()) throw new Error('Publication requires a clean Git checkout.');
  };
  clean();
  const directory = resolve(options['--directory']);
  // Full existing validator checks PE, NuGet payload, runtime, indexes and hashes.
  // It is invoked only as validate, never manifest/publish; source bytes stay intact.
  validateFullPackage(directory, root, { run: boundedRun });
  const inventory = fullInventory(directory, source);
  const snapshot = mkdtempSync(join(tmpdir(), 'music-windows-main-'));
  try {
    for (const item of inventory) {
      const bytes = readFileSync(item.path);
      if (bytes.length !== item.bytes || createHash('sha256').update(bytes).digest('hex') !== item.sha256) throw new Error('Local inventory changed before snapshot.');
      writeFileSync(join(snapshot, item.name), bytes, { flag: 'wx' });
    }
    validateFullPackage(snapshot, root, { run: boundedRun });
    clean();
    let changelog = null;
    if (options['--changelog'] === 'true') {
      const generated = generate({ root, platform: 'windows', head: { version: source.version, sha: source.sha }, repository, run: boundedRun });
      // A rerun must not add notes to an already complete old publication.
      if (!generated.publishedManifest || Object.hasOwn(generated.publishedManifest, 'changelog')) changelog = generated.publishedManifest?.changelog ?? generated.changelog;
      if (changelog) enrichManifest({ manifest: JSON.parse(readFileSync(join(snapshot, 'windows-release.json'), 'utf8')), changelog, root, platform: 'windows', run: boundedRun, compatibilityLimit: 65536 });
    }
    if (options['--android-changed'] === 'false') await ensureMainRelease(['--repository', repository, '--android-changed', 'false'], { root, run: boundedRun, output: () => {} });
    // Android publication is a completed dependency, not a ten-minute poll.
    const result = await attachWindowsInstaller({ directory: snapshot, repository, source, root, run: boundedRun, includeUpdateFeed: true, changelog });
    output(JSON.stringify(result, null, 2) + '\n');
    return result;
  } finally { rmSync(snapshot, { recursive: true, force: true }); }
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main().catch(error => { console.error(error.message); process.exitCode = 1; });
}
