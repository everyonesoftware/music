#!/usr/bin/env node
// Attach only the verified installer and its dedicated checksum to an existing release.
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { lstatSync, readFileSync, mkdtempSync, writeFileSync, rmSync, openSync, closeSync, readdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { isDeepStrictEqual } from 'node:util';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { resolve, join, dirname } from 'node:path';
import { enrichManifest } from './release-changelog.mjs';

export const INSTALLER = 'MusicWindows-win-x64-Setup.exe';
const shaPattern = /^(?:[a-f0-9]{40}|[a-f0-9]{64})$/;
function invoke(run, command, args, extra = {}) {
  const result = run(command, args, { encoding: 'utf8', shell: false, maxBuffer: 16 * 1024 * 1024, ...extra });
  if (result.error || result.status !== 0) throw new Error(`${command} failed: ${result.error?.message || result.stderr || `exit ${result.status}`}`);
  return String(result.stdout ?? '').trim();
}
export function deriveSource(root, { run = spawnSync } = {}) {
  const git = (...args) => invoke(run, 'git', ['-C', resolve(root), ...args]);
  if (git('rev-parse', '--is-shallow-repository') !== 'false') throw new Error('Full Git history required; shallow checkout rejected.');
  const text = git('rev-list', '--count', 'HEAD');
  const count = Number(text);
  if (!/^[1-9][0-9]*$/.test(text) || !Number.isSafeInteger(count) || count > 65534) throw new Error('Commit count must be 1..65534.');
  const sha = git('rev-parse', 'HEAD');
  if (!shaPattern.test(sha)) throw new Error('Invalid HEAD SHA.');
  const version = `0.1.${count}`;
  return { count, version, tag: `windows-v${version}`, sha };
}
function regular(path) {
  const info = lstatSync(path);
  if (!info.isFile() || info.isSymbolicLink()) throw new Error('Expected regular nonsymlink file: ' + path);
  return info;
}
export function validateLocal(directory, source) {
  if (!Number.isInteger(source.count) || source.count < 1 || source.count > 65534 || source.version !== `0.1.${source.count}` || source.tag !== `windows-v${source.version}` || !shaPattern.test(source.sha)) throw new Error('Invalid source identity.');
  directory = resolve(directory);
  if (!lstatSync(directory).isDirectory() || lstatSync(directory).isSymbolicLink()) throw new Error('Expected nonsymlink directory.');
  const manifestPath = join(directory, 'windows-release.json');
  regular(manifestPath);
  const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
  const required = { ...source, platform: 'windows-x64', package_id: 'MusicWindows', runtime: 'win-x64', channel: 'win-x64', signing: 'unsigned-preview' };
  for (const [key, value] of Object.entries(required)) if (manifest[key] !== value) throw new Error('Windows source metadata mismatch: ' + key);
  if (!Array.isArray(manifest.artifacts)) throw new Error('Missing artifact inventory.');
  const entries = manifest.artifacts.filter(a => a?.name === INSTALLER);
  if (entries.length !== 1) throw new Error('Expected exactly one installer entry.');
  const entry = entries[0];
  const path = join(directory, INSTALLER);
  const info = regular(path);
  const sha256 = createHash('sha256').update(readFileSync(path)).digest('hex');
  if (!Number.isSafeInteger(entry.bytes) || entry.bytes <= 0 || entry.bytes !== info.size || !/^[a-f0-9]{64}$/.test(entry.sha256) || entry.sha256 !== sha256) throw new Error('Installer size/hash mismatch.');
  return { path, bytes: info.size, sha256, checksum: `${sha256}  ${INSTALLER}\n` };
}
// The legacy tag/signing fields are packaging compatibility metadata, not the publication tag/state.
export function validateFullPackage(directory, root, { run = spawnSync } = {}) {
  const clean = invoke(run, 'git', ['-C', resolve(root), 'status', '--porcelain', '--untracked-files=all'], { timeout: 120_000 });
  if (clean) throw new Error('Publication requires a clean Git checkout.');
  invoke(run, process.platform === 'win32' ? 'python' : 'python3', [join(resolve(root), 'scripts/windows-release.py'), 'validate', '--root', resolve(root), '--directory', resolve(directory)], { timeout: 120_000 });
}
export function fullInventory(directory, source) {
  validateLocal(directory, source);
  directory = resolve(directory);
  const names = [`MusicWindows-${source.version}-win-x64-full.nupkg`, INSTALLER, 'releases.win-x64.json', 'assets.win-x64.json', 'RELEASES-win-x64', 'windows-release.json', 'SHA256SUMS'];
  if (!isDeepStrictEqual(readdirSync(directory).sort(), [...names].sort())) throw new Error('Unexpected or missing full Windows inventory.');
  const manifest = JSON.parse(readFileSync(join(directory, 'windows-release.json'), 'utf8'));
  if (manifest.artifacts.length !== 5 || new Set(manifest.artifacts.map(a => a.name)).size !== 5 || manifest.artifacts.some(a => !names.slice(0, 5).includes(a.name))) throw new Error('Invalid full Windows artifact inventory.');
  return names.map(name => {
    const path = join(directory, name), info = regular(path);
    const sha256 = createHash('sha256').update(readFileSync(path)).digest('hex');
    const entry = manifest.artifacts.find(a => a.name === name);
    if (entry && (entry.bytes !== info.size || entry.sha256 !== sha256)) throw new Error('Full Windows inventory size/hash mismatch: ' + name);
    return { name, path, bytes: info.size, sha256 };
  });
}
export async function attachWindowsInstaller({ directory, repository, source, includeUpdateFeed = false, changelog = null, root = resolve(dirname(fileURLToPath(import.meta.url)), '..'), run = spawnSync, clock = () => performance.now(), sleep = ms => new Promise(r => setTimeout(r, ms)), timeoutMs = 600_000, pollMs = 5_000 }) {
  if (typeof repository !== 'string' || !/^[A-Za-z0-9_][A-Za-z0-9_.-]*\/[A-Za-z0-9_][A-Za-z0-9_.-]*$/.test(repository) || repository.split('/').some(p => p === '.' || p === '..')) throw new Error('Invalid GitHub repository.');
  if (!Number.isFinite(timeoutMs) || timeoutMs < 0 || timeoutMs > 600_000 || !Number.isFinite(pollMs) || pollMs <= 0) throw new Error('Invalid bounded wait.');
  if (includeUpdateFeed) {
    const originalRun = run;
    run = (command, args, extra) => originalRun(command, args, { ...extra, shell: false, timeout: 120_000 });
    validateFullPackage(directory, root, { run });
  }
  const local = validateLocal(directory, source);
  const inventory = includeUpdateFeed ? fullInventory(directory, source) : [];
  const expectedFiles = new Map(inventory.map(item => [item.name, item]));
  if (changelog !== null && !includeUpdateFeed) throw new Error('Changelog requires full validated update inventory.');
  // Validate the additive payload against Git, but never pass an enriched
  // manifest into the unchanged exact-equality Python validator.
  const enriched = changelog === null ? null : Buffer.from(enrichManifest({ manifest: JSON.parse(readFileSync(join(directory, 'windows-release.json'), 'utf8')), changelog, root, platform: 'windows', run, compatibilityLimit: 65536 }));
  if (enriched) expectedFiles.set('windows-release.json', { bytes: enriched.length, sha256: createHash('sha256').update(enriched).digest('hex') });
  expectedFiles.set(INSTALLER, local);
  expectedFiles.set(`${INSTALLER}.sha256`, { bytes: Buffer.byteLength(local.checksum), sha256: createHash('sha256').update(local.checksum).digest('hex') });
  const tag = `v${source.version}`;
  const prefix = `repos/${repository}`;
  const names = includeUpdateFeed ? [...inventory.map(item => item.name), `${INSTALLER}.sha256`] : [INSTALLER, `${INSTALLER}.sha256`];
  const api = (path, { optional = false, ...extra } = {}) => {
    const args = ['api', `${prefix}/${path}`];
    const result = run('gh', args, { encoding: 'utf8', shell: false, maxBuffer: 16 * 1024 * 1024, ...extra });
    if (result.error || result.status !== 0) {
      if (!result.error && optional && /\bHTTP 404\b/i.test(String(result.stderr))) return null;
      throw new Error('GitHub command failed: ' + (result.error?.message || result.stderr || `exit ${result.status}`));
    }
    return JSON.parse(String(result.stdout));
  };
  const view = optional => {
    const release = api(`releases/tags/${tag}`, { optional, ...(optional ? { timeout: Math.max(1, Math.ceil(deadline - clock())) } : {}) });
    if (release !== null && (!release || !Number.isSafeInteger(release.id) || release.id <= 0 || typeof release.draft !== 'boolean' || release.tag_name !== tag || typeof release.target_commitish !== 'string')) throw new Error('Invalid release identity/state.');
    return release;
  };
  const assets = release => {
    const pages = JSON.parse(invoke(run, 'gh', ['api', `${prefix}/releases/${release.id}/assets`, '--paginate', '--slurp']));
    if (!Array.isArray(pages) || pages.some(p => !Array.isArray(p))) throw new Error('Invalid remote asset pages.');
    const items = pages.flat();
    if (items.some(a => !a || !Number.isSafeInteger(a.id) || a.id <= 0 || typeof a.name !== 'string' || !a.name || !Number.isSafeInteger(a.size) || a.size < 0)) throw new Error('Invalid remote asset metadata.');
    if (new Set(items.map(a => a.name)).size !== items.length || new Set(items.map(a => a.id)).size !== items.length) throw new Error('Duplicate remote assets.');
    return items;
  };
  const identity = release => {
    if (includeUpdateFeed && (release?.prerelease !== false || typeof release?.published_at !== 'string' || !Number.isFinite(Date.parse(release.published_at)))) throw new Error('Expected published stable main release.');
    if (!release || release.draft !== false || release.tag_name !== tag || release.target_commitish !== source.sha) throw new Error('Main release source/commit/draft mismatch.');
    let object = api(`git/ref/tags/${tag}`).object;
    const seen = new Set();
    while (object?.type === 'tag') {
      if (!shaPattern.test(object.sha) || seen.has(object.sha) || seen.size >= 16) throw new Error('Invalid annotated tag chain.');
      seen.add(object.sha);
      object = api(`git/tags/${object.sha}`).object;
    }
    if (object?.type !== 'commit' || object.sha !== source.sha) throw new Error('Main release tag commit mismatch.');
  };
  const deadline = clock() + timeoutMs;
  let initial;
  while (true) {
    if (clock() >= deadline) throw new Error('Timed out waiting for published main release (missing/draft).');
    initial = view(true);
    if (initial && initial.target_commitish !== source.sha) throw new Error('Main release source/commit mismatch.');
    if (initial && initial.draft === false) break;
    if (includeUpdateFeed) throw new Error('Expected existing published stable main release (missing/draft).');
    const remaining = deadline - clock();
    if (remaining <= 0) throw new Error('Timed out waiting for published main release (missing/draft).');
    await sleep(Math.min(pollMs, remaining));
  }
  identity(initial);
  const before = assets(initial);
  const metadata = release => Object.fromEntries(Object.entries(release).filter(([key]) => !['assets', 'updated_at'].includes(key)));
  const unrelated = items => immutableAssets(items.filter(a => !names.includes(a.name)));
  // Concurrent downloads can advance any asset's counter. Preserve all other fields.
  const immutableAssets = items => items.map(asset =>
    Object.fromEntries(Object.entries(asset).filter(([key]) => key !== 'download_count'))).sort((a, b) => a.id - b.id);
  const temporary = mkdtempSync(join(tmpdir(), 'music-windows-attach-'));
  try {
    // Revalidate after waiting; stage private immutable bytes, never upload the live build path.
    const fresh = validateLocal(directory, source);
    if (!isDeepStrictEqual(fresh, local)) throw new Error('Local installer changed before upload.');
    const staged = readFileSync(local.path);
    if (staged.length !== local.bytes || createHash('sha256').update(staged).digest('hex') !== local.sha256) throw new Error('Local installer changed before upload.');
    writeFileSync(join(temporary, INSTALLER), staged, { flag: 'wx' });
    writeFileSync(join(temporary, `${INSTALLER}.sha256`), local.checksum, { flag: 'wx' });
    if (includeUpdateFeed) {
      if (!isDeepStrictEqual(fullInventory(directory, source), inventory)) throw new Error('Local inventory changed before upload.');
      for (const item of inventory.filter(item => item.name !== INSTALLER)) {
        const bytes = readFileSync(item.path);
        if (bytes.length !== item.bytes || createHash('sha256').update(bytes).digest('hex') !== item.sha256) throw new Error('Local inventory changed before upload.');
        writeFileSync(join(temporary, item.name), item.name === 'windows-release.json' && enriched ? enriched : bytes, { flag: 'wx' });
      }
    }
    const verifyAsset = asset => {
      if (asset.state !== 'uploaded') throw new Error('Remote asset is not completely uploaded.');
      const expected = expectedFiles.get(asset.name);
      if (asset.size !== expected.bytes) throw new Error('Remote asset size conflict: ' + asset.name);
      const destination = join(temporary, `download-${asset.id}`);
      const fd = openSync(destination, 'w');
      try {
        invoke(run, 'gh', ['api', `${prefix}/releases/assets/${asset.id}`, '-H', 'Accept: application/octet-stream'], { encoding: null, stdio: ['ignore', fd, 'pipe'] });
      } finally { closeSync(fd); }
      const bytes = readFileSync(destination);
      if (bytes.length !== expected.bytes || createHash('sha256').update(bytes).digest('hex') !== expected.sha256 || (asset.name === `${INSTALLER}.sha256` && !bytes.equals(Buffer.from(local.checksum)))) throw new Error('Remote asset bytes/checksum conflict: ' + asset.name);
    };
    // Check all existing bytes before any write: a missing checksum is repairable, a conflict is not.
    for (const asset of before.filter(a => names.includes(a.name))) verifyAsset(asset);
    const current = view(false);
    identity(current);
    const preUpload = assets(current);
    if (!isDeepStrictEqual(metadata(current), metadata(initial)) || !isDeepStrictEqual(immutableAssets(preUpload), immutableAssets(before))) throw new Error('Release changed before upload.');
    const uploaded = names.filter(name => !before.some(a => a.name === name));
    if (uploaded.length) invoke(run, 'gh', ['release', 'upload', tag, ...uploaded.map(name => join(temporary, name)), '--repo', repository]);
    const final = view(false);
    identity(final);
    const after = assets(final);
    for (const existing of before.filter(a => names.includes(a.name))) {
      if (!isDeepStrictEqual(immutableAssets([existing]), immutableAssets(after.filter(a => a.id === existing.id)))) throw new Error('Existing immutable asset changed.');
    }
    if (!isDeepStrictEqual(metadata(final), metadata(initial)) || !isDeepStrictEqual(unrelated(after), unrelated(before))) throw new Error('Release metadata or unrelated Android assets changed.');
    for (const name of names) {
      const asset = after.find(a => a.name === name);
      if (!asset) throw new Error('Uploaded asset missing: ' + name);
      verifyAsset(asset);
    }
    // Downloads can take time: recheck identity and untouched assets again on completion.
    const completion = view(false);
    identity(completion);
    const completedAssets = assets(completion);
    if (!isDeepStrictEqual(metadata(completion), metadata(initial)) || !isDeepStrictEqual(unrelated(completedAssets), unrelated(before)) || !isDeepStrictEqual(immutableAssets(completedAssets), immutableAssets(after))) throw new Error('Release changed during readback.');
    return { tag, sha: source.sha, uploaded };
  } finally { rmSync(temporary, { recursive: true, force: true }); }
}
export async function main(argv = process.argv.slice(2), { root = resolve(dirname(fileURLToPath(import.meta.url)), '..'), run = spawnSync, output = text => process.stdout.write(text), clock, sleep } = {}) {
  const options = {};
  for (let i = 0; i < argv.length; i += 2) {
    const key = argv[i];
    const value = argv[i + 1];
    if (!['--directory', '--repository'].includes(key) || Object.hasOwn(options, key) || typeof value !== 'string' || !value || value.startsWith('-')) throw new Error('Usage: node scripts/attach-windows-installer.mjs --directory PATH --repository OWNER/REPO');
    options[key] = value;
  }
  if (!options['--directory'] || !options['--repository']) throw new Error('--directory and --repository are required.');
  const repository = options['--repository'];
  if (!/^[A-Za-z0-9_][A-Za-z0-9_.-]*\/[A-Za-z0-9_][A-Za-z0-9_.-]*$/.test(repository) || repository.split('/').some(p => p === '.' || p === '..')) throw new Error('Invalid GitHub repository.');
  const source = deriveSource(root, { run });
  const result = await attachWindowsInstaller({ directory: options['--directory'], repository, source, run, clock, sleep });
  output(JSON.stringify(result, null, 2) + '\n');
  return result;
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main().catch(error => { console.error(error.message); process.exitCode = 1; });
}
