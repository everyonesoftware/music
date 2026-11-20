#!/usr/bin/env node
// Embedded history is produced from local full Git history and verified publications.
import { spawnSync } from 'node:child_process';
import { isDeepStrictEqual } from 'node:util';
import { readFileSync, writeFileSync, lstatSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { affectedComponents, publishedBaseline } from './selective-builds.mjs';

// Reuse the selector's complete platform-inventory + manifest + dereferenced-tag
// verification, but visit EVERY published version rather than only the newest.
export function discoverPublications({ platform, api, manifests = new Map() }) {
  if (!['android', 'windows'].includes(platform)) throw new Error('Invalid changelog platform.');
  const cache = new Map();
  const cached = (path, options) => {
    const key = JSON.stringify([path, options]);
    if (!cache.has(key)) cache.set(key, api(path, options));
    return cache.get(key);
  };
  const pages = cached('releases?per_page=100', { paginate: true });
  if (!Array.isArray(pages) || pages.some(page => !Array.isArray(page))) throw new Error('Incomplete release pages.');
  const pattern = platform === 'windows' ? /^(?:windows-)?v(0\.1\.[1-9][0-9]*)$/ : /^v(0\.1\.[1-9][0-9]*)$/;
  const found = new Map();
  for (const release of pages.flat()) {
    const match = pattern.exec(release?.tag_name);
    if (!match || release.draft || release.prerelease !== release.tag_name.startsWith('windows-')) continue;
    let manifest;
    const sha = publishedBaseline(platform, { api: (path, options) => {
      if (path === 'releases?per_page=100') return [[release]];
      const value = cached(path, options);
      if (options?.binaryJson) manifest = value;
      return value;
    } });
    if (sha === null) continue; // incomplete inventories never establish a publication
    const entry = { version: match[1], sha };
    const previous = found.get(entry.version);
    if (previous && previous.sha !== sha) throw new Error('Conflicting published source.');
    found.set(entry.version, entry);
    // Stable wins ties, matching installed-client discovery.
    if (!manifests.has(entry.version) || !release.tag_name.startsWith('windows-')) manifests.set(entry.version, manifest);
  }
  return [...found.values()].sort((a, b) => number(a.version) - number(b.version));
}
export function enrichManifest({ manifest, changelog, root, platform, run = spawnSync, compatibilityLimit = MAX_MANIFEST_BYTES }) {
  if (manifest.count !== number(manifest.version) || !isDeepStrictEqual(identity(manifest), changelog?.head) || changelog?.platform !== platform || !Array.isArray(changelog?.releases) || !changelog.releases.length) throw new Error('Changelog/manifest identity mismatch.');
  const expected = buildChangelog({ root, platform, head: identity(manifest), publications: changelog.releases.map(identity), run });
  if (!isDeepStrictEqual(changelog, expected)) throw new Error('Changelog does not match complete local Git history.');
  if (Object.hasOwn(manifest, 'changelog') && !isDeepStrictEqual(manifest.changelog, changelog)) throw new Error('Existing changelog history conflict.');
  const bytes = JSON.stringify({ ...manifest, changelog }) + '\n';
  const size = Buffer.byteLength(bytes);
  if (size > MAX_MANIFEST_BYTES) throw new Error('Complete manifest exceeds 1 MiB size limit; never truncate history.');
  if (!Number.isSafeInteger(compatibilityLimit) || compatibilityLimit < 1 || compatibilityLimit > MAX_MANIFEST_BYTES || size > compatibilityLimit) throw new Error('Complete manifest exceeds old-client 64 KiB compatibility limit; rollout required, never truncate history.');
  return bytes;
}
export const MAX_MANIFEST_BYTES = 1024 * 1024;
const shaPattern = /^[a-f0-9]{40}$/;
function invoke(run, command, args) {
  const result = run(command, args, { encoding: 'utf8', shell: false, timeout: 120_000, maxBuffer: 32 * 1024 * 1024 });
  if (result.error || result.status !== 0) throw new Error(`${command} failed: ${result.error?.message || result.stderr || result.status}`);
  return String(result.stdout);
}
function number(version) {
  if (typeof version !== 'string' || !/^0\.1\.[1-9][0-9]*$/.test(version)) throw new Error('Invalid changelog version.');
  const n = Number(version.slice(4));
  if (!Number.isSafeInteger(n)) throw new Error('Invalid changelog version.');
  return n;
}
function identity(value) {
  number(value?.version);
  if (!shaPattern.test(value?.sha)) throw new Error('Invalid changelog SHA.');
  return { version: value.version, sha: value.sha };
}
function summary(subject, paths, platform) {
  const relevant = paths.filter(path => !path.startsWith('.github/') && !path.startsWith('docs/') && !/(?:^|\/)(?:README|RELEASING|UPDATING|VALIDATION|NOTES|AGENTS|CLAUDE)\.md$/.test(path));
  if (paths.length && !affectedComponents(relevant).includes(platform)) return { kind: null, text: null };
  const original = subject.replace(/^(?:\[verified\]\s*)+/i, '');
  const conventional = /^(\w+)(?:\([^\n)]*\))?!?:\s*/.exec(original);
  const text = original.replace(/^(?:\w+)(?:\([^\n)]*\))?!?:\s*/, '').replace(/[\r\0]/g, ' ').trim();
  return text ? { kind: conventional?.[1].toLowerCase() === 'fix' || /^(?:fix|fixes|fixed|repair|resolve)\b/i.test(original) ? 'fix' : 'improvement', text } : { kind: null, text: null };
}
export function buildChangelog({ root, platform, head, publications, run = spawnSync }) {
  if (!['android', 'windows'].includes(platform)) throw new Error('Invalid changelog platform.');
  head = identity(head);
  const git = (...args) => invoke(run, 'git', ['-C', root, ...args]).trimEnd();
  if (git('rev-parse', '--is-shallow-repository') !== 'false') throw new Error('Full history required for changelog.');
  const releases = [...publications.map(identity), head].sort((a, b) => number(a.version) - number(b.version));
  const unique = [];
  for (const entry of releases) {
    if (number(entry.version) > number(head.version)) throw new Error('Publication is newer than offered head.');
    const previous = unique.at(-1);
    if (previous?.version === entry.version) {
      if (previous.sha !== entry.sha) throw new Error('Conflicting published source.');
      continue;
    }
    if (Number(git('rev-list', '--count', entry.sha)) !== number(entry.version)) throw new Error('Published version/Git commit count mismatch.');
    if (previous) git('merge-base', '--is-ancestor', previous.sha, entry.sha);
    unique.push(entry);
  }
  const seen = new Set();
  const segments = unique.map((entry, index) => {
    const commits = index === 0 ? [] : git('rev-list', '--reverse', '--topo-order', `${unique[index - 1].sha}..${entry.sha}`).split('\n').filter(Boolean).map(sha => {
      if (!shaPattern.test(sha) || seen.has(sha)) throw new Error('Duplicate/invalid changelog commit.');
      seen.add(sha);
      const raw = invoke(run, 'git', ['-C', root, 'cat-file', 'commit', sha]);
      const separator = raw.indexOf('\n\n');
      if (separator < 0) throw new Error('Invalid Git commit message.');
      const subject = raw.slice(separator + 2).split('\n', 1)[0];
      // --no-renames retains BOTH names, including cross-platform moves. -m
      // inspects merge parent diffs, not only the usually empty combined diff.
      const paths = git('diff-tree', '--root', '-m', '--no-commit-id', '--name-only', '-r', '-z', '--no-renames', sha).split('\0').filter(Boolean);
      return { sha, subject, ...summary(subject, paths, platform) };
    });
    if (index && commits.at(-1)?.sha !== entry.sha) throw new Error('Incomplete changelog segment.');
    return { ...entry, commits };
  });
  return { schema: 1, platform, head, releases: segments };
}

export function generateChangelog({ root, platform, head, repository, run = spawnSync, api }) {
  if (typeof repository !== 'string' || !/^[A-Za-z0-9_][A-Za-z0-9_.-]*\/[A-Za-z0-9_][A-Za-z0-9_.-]*$/.test(repository) || /\s/.test(repository) || repository.split('/').some(p => p === '.' || p === '..')) throw new Error('Invalid GitHub repository.');
  api ??= (path, { paginate = false, binaryJson = false } = {}) => {
    const args = ['api', `repos/${repository}/${path}`];
    if (paginate) args.push('--paginate', '--slurp');
    // Release asset IDs are immutable. Cache only these bytes, never mutable
    // release inventories or tag refs; revalidate provenance on every invocation.
    if (binaryJson) args.push('-H', 'Accept: application/octet-stream', '--cache', '24h');
    const text = invoke(run, 'gh', args);
    if (binaryJson && Buffer.byteLength(text) > MAX_MANIFEST_BYTES) throw new Error('Publication manifest exceeds 1 MiB.');
    return JSON.parse(text);
  };
  const manifests = new Map();
  const publications = discoverPublications({ platform, api, manifests }).filter(entry => number(entry.version) <= number(head.version));
  const changelog = buildChangelog({ root, platform, head, publications, run });
  return { changelog, publishedManifest: manifests.get(head.version) ?? null };
}

export function main(argv = process.argv.slice(2), { root = fileURLToPath(new URL('..', import.meta.url)), run = spawnSync, output = text => process.stdout.write(text) } = {}) {
  const options = {};
  for (let i = 0; i < argv.length; i += 2) {
    const key = argv[i], value = argv[i + 1];
    if (!['--platform', '--repository', '--directory', '--root', '--output'].includes(key) || Object.hasOwn(options, key) || !value || value.startsWith('-')) throw new Error('Usage: release-changelog.mjs --platform android|windows --repository OWNER/REPO --directory PATH [--root PATH] [--output PATH]');
    options[key] = value;
  }
  const platform = options['--platform'];
  if (!['android', 'windows'].includes(platform) || !options['--directory']) throw new Error('Platform and directory required.');
  root = resolve(options['--root'] ?? root);
  const path = join(resolve(options['--directory']), platform === 'android' ? 'release.json' : 'windows-release.json');
  if (!lstatSync(path).isFile() || lstatSync(path).isSymbolicLink() || lstatSync(path).size > MAX_MANIFEST_BYTES) throw new Error('Invalid manifest file/size.');
  const original = readFileSync(path);
  const manifest = JSON.parse(original);
  const localHead = invoke(run, 'git', ['-C', root, 'rev-parse', 'HEAD']).trim();
  if (manifest.sha !== localHead) throw new Error('Manifest is not the exact checked-out source.');
  const { changelog, publishedManifest } = generateChangelog({ root, platform, head: identity(manifest), repository: options['--repository'], run });
  // A completed historical publication is immutable, including absence of notes.
  // Read-only previews may still inspect the newly computed cumulative payload.
  const preserve = publishedManifest && !Object.hasOwn(publishedManifest, 'changelog') && !options['--output'];
  const bytes = preserve ? original : enrichManifest({ manifest, changelog: publishedManifest?.changelog ?? changelog, root, platform, run, compatibilityLimit: 65536 });
  const destination = resolve(options['--output'] ?? path);
  if (platform === 'windows' && !options['--output']) throw new Error('Windows enrichment must follow legacy validation in the Node publisher; use --output for preview.');
  writeFileSync(destination, bytes);
  output(JSON.stringify({ platform, version: manifest.version, bytes: Buffer.byteLength(bytes), preserved: Boolean(preserve), output: destination }) + '\n');
  return { changelog, bytes: Buffer.byteLength(bytes), preserved: Boolean(preserve) };
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try { main(); } catch (error) { console.error(error.message); process.exitCode = 1; }
}
