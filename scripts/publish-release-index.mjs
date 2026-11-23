#!/usr/bin/env node
// Publish the immutable updater index, then promote this exact release to Latest.
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { publishedBaseline } from './selective-builds.mjs';
import { checkoutSource, commands, parseOptions, shaPattern, tagCount, verifyStable } from './release-publication.mjs';

const platforms = ['android', 'windows'];
const name = 'latest-versions.json';
const indexLimit = 16 * 1024;
const manifestLimit = 1024 * 1024;
const start = '<!-- music-release-downloads:start -->';
const end = '<!-- music-release-downloads:end -->';
const installer = 'MusicWindows-win-x64-Setup.exe';
const redirects = new Set([301, 302, 303, 307, 308]);
const assetHosts = new Set(['release-assets.githubusercontent.com', 'objects.githubusercontent.com']);

class PublicIndexError extends Error {
  constructor(message, retry = false) {
    super(message);
    this.retry = retry;
  }
}

async function verifyPublicIndex(url, tagged, bytes, label, fetch, pause) {
  const tagPrefix = tagged.slice(0, tagged.lastIndexOf('/download/') + '/download/'.length);
  const publicTagCount = href => href.startsWith(tagPrefix) && href.endsWith(`/${name}`)
    ? tagCount(href.slice(tagPrefix.length, -name.length - 1)) : NaN;
  const read = async () => {
    const signal = AbortSignal.timeout(10_000); // Whole redirect chain and body, per attempt.
    let current = url;
    for (let hop = 0; ; hop++) {
      const isLatest = current === url && url !== tagged;
      const response = await fetch(current, { redirect: 'manual', credentials: 'omit', signal });
      if (redirects.has(response.status)) {
        await response.body?.cancel();
        const location = response.headers.get('location');
        if (hop >= 4 || !location || !URL.canParse(location, current)) throw new PublicIndexError('unexpected redirect');
        const next = new URL(location, current);
        if (next.protocol !== 'https:' || next.port || next.username || next.password || next.hash) throw new PublicIndexError('unexpected redirect');
        if (isLatest) {
          if (next.href !== tagged) {
            // An older cached Latest redirect can clear on retry; never follow it.
            const olderTag = publicTagCount(next.href) < publicTagCount(tagged);
            throw new PublicIndexError(olderTag ? 'redirect still points to an older tag' : 'unexpected redirect', olderTag);
          }
        } else if (!assetHosts.has(next.hostname)) throw new PublicIndexError('unexpected redirect');
        current = next.href;
        continue;
      }
      if (response.status !== 200) {
        await response.body?.cancel();
        throw new PublicIndexError(`HTTP ${response.status}`, response.status === 404 || response.status === 429 || response.status >= 500);
      }
      if (!response.body) throw new PublicIndexError('response has no index bytes');
      if (Number(response.headers.get('content-length')) > indexLimit) {
        await response.body.cancel();
        throw new PublicIndexError('response exceeds the index size limit');
      }
      const chunks = [];
      let size = 0;
      for await (const chunk of response.body) {
        size += chunk.length;
        if (size > indexLimit) throw new PublicIndexError('response exceeds the index size limit');
        chunks.push(chunk);
      }
      if (!Buffer.concat(chunks, size).equals(bytes)) throw new PublicIndexError('index bytes differ');
      return;
    }
  };
  for (let attempt = 1; ; attempt++) {
    try { await read(); return; }
    catch (error) {
      // Do not log fetch errors or redirect URLs: CDN queries can contain signatures.
      if (attempt === 3 || (error instanceof PublicIndexError && !error.retry)) {
        throw new Error(`Public ${label} index verification failed: ${error instanceof PublicIndexError ? error.message : 'network or timeout error'}.`);
      }
      await pause(attempt * 1000);
    }
  }
}

function downloadNotes(original, repository, index) {
  if (original !== null && typeof original !== 'string') throw new Error('Invalid release notes.');
  const body = original ?? '';
  const link = (tag, asset, label) => `[${label}](https://github.com/${repository}/releases/download/${tag}/${asset})`;
  const apk = `Music-${index.android.slice(1)}.apk`;
  const block = [start, '## Current downloads',
    `- Android ${index.android.slice(1)}: ${link(index.android, apk, 'Signed APK')} · ${link(index.android, `${apk}.sha256`, 'SHA-256')}`,
    `- Windows ${index.windows.slice(1)}: ${link(index.windows, installer, 'Installer')} · ${link(index.windows, `${installer}.sha256`, 'SHA-256')} (unsigned; Windows may warn)`, end].join('\n');
  const a = body.indexOf(start), b = body.indexOf(end);
  if (a === -1 && b === -1) return `${body}${body ? '\n\n' : ''}${block}`;
  if (a === -1 || b < a || a !== body.lastIndexOf(start) || b !== body.lastIndexOf(end)) throw new Error('Invalid release download notes markers.');
  return body.slice(0, a) + block + body.slice(b + end.length);
}

function discover(platform, releases, io, cachedAssets) {
  let manifest, currentAssets;
  // Reuse the existing full inventory, metadata and dereferenced-tag verifier.
  // Unlike the build baseline, the new index only points to normal stable tags.
  const sha = publishedBaseline(platform, { api: (path, options) => {
    if (path === 'releases?per_page=100') return [releases];
    const inventory = /^releases\/(\d+)\/assets\?per_page=100$/.exec(path);
    if (inventory) {
      currentAssets = cachedAssets(Number(inventory[1]));
      return [currentAssets];
    }
    if (options?.binaryJson) {
      const id = Number(path.split('/').at(-1));
      const asset = currentAssets.find(asset => asset.id === id);
      if (!asset) throw new Error('Publication manifest is missing from candidate assets.');
      manifest = JSON.parse(io.readAsset(asset, manifestLimit));
      return manifest;
    }
    return io.api(path, options);
  } });
  if (sha === null) throw new Error(`No verified stable ${platform === 'android' ? 'Android' : 'Windows'} publication.`);
  const tag = `v${manifest.version}`;
  const release = releases.find(r => r.tag_name === tag);
  verifyStable(release, { tag, sha });
  if (platform === 'windows' && !manifest.artifacts.some(asset => asset.name === installer)) throw new Error('Missing Windows installer.');
  const artifacts = platform === 'android' ? [{ name: manifest.apk, sha256: manifest.sha256 }] : manifest.artifacts;
  const get = name => {
    const matches = cachedAssets(release.id).filter(asset => asset.name === name);
    if (matches.length !== 1) throw new Error(`Expected one published ${name}.`);
    io.verifyAssetMetadata(matches[0], tag);
    return matches[0];
  };
  const manifestName = platform === 'android' ? 'release.json' : 'windows-release.json';
  get(manifestName);
  const setup = platform === 'android' ? manifest.apk : installer;
  const checksum = io.readAsset(get(`${setup}.sha256`), 1024).toString();
  const line = ({ name, sha256 }) => `${sha256}  ${name}`;
  const expected = new Map(artifacts.map(asset => [asset.name, asset]));
  if (checksum.replace(/\r\n/g, '\n') !== `${line(expected.get(setup))}\n`) throw new Error('Installer checksum conflict: ' + setup);
  if (platform === 'windows') {
    const required = [setup, `MusicWindows-${manifest.version}-win-x64-full.nupkg`, 'releases.win-x64.json', 'assets.win-x64.json', 'RELEASES-win-x64'];
    const sums = io.readAsset(get('SHA256SUMS'), 4096).toString().split(/\r?\n/);
    if (artifacts.length !== required.length || sums.pop() !== '' || sums.length !== required.length || required.some(name => !expected.has(name) || !sums.includes(line(expected.get(name))))) throw new Error('Windows checksum inventory conflict.');
  }
  for (const { name, sha256 } of artifacts) io.verifyDigest(get(name), sha256);
  return { tag, sha };
}

export async function main(argv = process.argv.slice(2), { root = fileURLToPath(new URL('..', import.meta.url)), run, fetch = globalThis.fetch, pause = delay, output = console.log } = {}) {
  const args = parseOptions(argv, ['repository', 'source', ...platforms.flatMap(p => [`${p}-required`, `${p}-published`])]);
  const io = commands(args.repository, run);
  if (!shaPattern.test(args.source) || platforms.some(p => ['required', 'published'].some(key => !['true', 'false'].includes(args[`${p}-${key}`])))) throw new Error('Exact source and explicit platform publication states are required.');
  const land = result => { output(JSON.stringify(result)); return result; };
  if (platforms.every(p => args[`${p}-published`] === 'false')) return land({ skipped: true, reason: 'no native publisher succeeded' });
  for (const p of platforms) if (args[`${p}-required`] === 'true' && args[`${p}-published`] !== 'true') throw new Error(`Required ${p === 'android' ? 'Android' : 'Windows'} publication did not succeed; fix or rerun with component=all.`);
  const source = checkoutSource(root, io.invoke);
  if (source.sha !== args.source) throw new Error('Promotion source does not match exact workflow checkout.');
  const latest = () => {
    const release = io.api('releases/latest', { optional: true });
    if (!release) return null;
    const count = tagCount(release.tag_name);
    if (!Number.isFinite(count) || release.draft !== false || release.prerelease !== false) throw new Error('Unexpected Latest release; refusing automatic promotion.');
    return release;
  };
  const skipOlder = value => value && tagCount(value.tag_name) > source.count;
  if (skipOlder(latest())) return land({ skipped: true, reason: 'newer Music release is already Latest' });
  const verifyMain = () => {
    const head = io.api('git/ref/heads/main').object;
    if (head?.type !== 'commit' || !shaPattern.test(head.sha)) throw new Error('Invalid remote main source.');
    if (source.sha === head.sha) return;
    const comparison = io.api(`compare/${source.sha}...${head.sha}?per_page=1`);
    if (comparison.status !== 'ahead' || comparison.merge_base_commit?.sha !== source.sha) throw new Error('Promotion source is not on remote main.');
  };
  verifyMain();
  const path = `releases/tags/${source.tag}`;
  const candidate = io.api(path);
  verifyStable(candidate, source);
  io.verifyTag(source.tag, source.sha);
  const releases = io.api('releases?per_page=100', { paginate: true }).flat().filter(release => release.draft === false && release.prerelease === false && tagCount(release.tag_name) <= source.count);
  if (new Set(releases.map(r => r.tag_name)).size !== releases.length || new Set(releases.map(r => r.id)).size !== releases.length || !releases.some(r => r.id === candidate.id && r.tag_name === source.tag && r.target_commitish === source.sha)) throw new Error('Conflicting stable release inventory.');
  const cache = new Map();
  const cachedAssets = id => {
    if (!cache.has(id)) cache.set(id, io.assets(id));
    return cache.get(id);
  };

  const index = { schema: 1 };
  for (const p of platforms) {
    const publication = discover(p, releases, io, cachedAssets);
    if (args[`${p}-published`] === 'true' && (publication.tag !== source.tag || publication.sha !== source.sha)) throw new Error(`Expected verified ${p === 'android' ? 'Android' : 'Windows'} publication on ${source.tag}.`);
    if (publication.sha !== source.sha) io.invoke('git', ['-C', root, 'merge-base', '--is-ancestor', publication.sha, source.sha]);
    index[p] = publication.tag;
  }
  const bytes = Buffer.from(JSON.stringify(index) + '\n');
  if (bytes.length > indexLimit) throw new Error('Release index exceeds 16 KiB.');
  const readIndex = () => {
    const assets = io.assets(candidate.id).filter(asset => asset.name === name);
    if (!assets.length) return false;
    try {
      if (assets.length !== 1) throw new Error('duplicate asset');
      io.verifyAssetMetadata(assets[0], source.tag);
      if (!io.readAsset(assets[0], indexLimit).equals(bytes)) throw new Error('different bytes');
    } catch (error) { throw new Error(`Release index conflict: ${error.message}`); }
    return true;
  };
  const body = downloadNotes(candidate.body, args.repository, index);
  if (!readIndex()) {
    const directory = mkdtempSync(join(tmpdir(), 'music-release-index-'));
    try {
      const file = join(directory, name);
      writeFileSync(file, bytes, { flag: 'wx' });
      io.gh('release', 'upload', source.tag, file, '--repo', args.repository);
    } finally { rmSync(directory, { recursive: true, force: true }); }
    if (!readIndex()) throw new Error('Release index missing after upload.');
  }
  const publicBase = `https://github.com/${args.repository}/releases`;
  const tagged = `${publicBase}/download/${source.tag}/${name}`;
  await verifyPublicIndex(tagged, tagged, bytes, 'tagged', fetch, pause);
  const before = io.api(path);
  verifyStable(before, { ...source, id: candidate.id });
  io.verifyTag(source.tag, source.sha);
  if (before.body !== candidate.body) throw new Error('Release description changed before promotion.');
  verifyMain();
  const previous = latest();
  if (skipOlder(previous)) return land({ skipped: true, reason: 'newer Music release is already Latest' });
  if (previous?.id !== candidate.id || before.body !== body) {
    // Serialized automated writer. Keep notes and Latest in one PATCH; GitHub
    // has no atomic protection against overlapping manual description edits.
    io.api(`releases/${candidate.id}`, { fields: ['--method', 'PATCH', '-f', 'make_latest=true', '-f', `body=${body}`] });
  }
  const promoted = latest();
  verifyStable(promoted, { ...source, id: candidate.id });
  if (promoted.body !== body || !readIndex()) throw new Error('Latest release description or index failed readback.');
  await verifyPublicIndex(`${publicBase}/latest/download/${name}`, tagged, bytes, 'Latest', fetch, pause);
  return land({ tag: source.tag, index });
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try { await main(); } catch (error) { console.error(error.message); process.exitCode = 1; }
}
