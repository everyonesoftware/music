import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { closeSync, mkdtempSync, openSync, readSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

export const shaPattern = /^[a-f0-9]{40}$/;
export const tagCount = tag => {
  const count = typeof tag === 'string' && /^v0\.1\.[1-9][0-9]*$/.test(tag) ? Number(tag.slice(5)) : NaN;
  return Number.isSafeInteger(count) && count <= 2_100_000_000 ? count : NaN;
};
export const hash = bytes => createHash('sha256').update(bytes).digest('hex');

export function parseOptions(argv, names) {
  const options = Object.create(null);
  for (let i = 0; i < argv.length; i += 2) {
    const key = argv[i].slice(2), value = argv[i + 1];
    if (`--${key}` !== argv[i] || !names.includes(key) || Object.hasOwn(options, key) || !value || value.startsWith('-')) throw new Error(`Invalid option: ${argv[i]}`);
    options[key] = value;
  }
  return options;
}

export function commands(repository, run = spawnSync) {
  if (typeof repository !== 'string' || !/^[A-Za-z0-9_][A-Za-z0-9_.-]*\/[A-Za-z0-9_][A-Za-z0-9_.-]*$/.test(repository) || /\s/.test(repository)) throw new Error('Invalid repository.');
  const invoke = (cmd, args, { optional = false, ...options } = {}) => {
    const result = run(cmd, args, { encoding: 'utf8', shell: false, timeout: 120_000, maxBuffer: 32 * 1024 * 1024, ...options });
    if (!result.error && optional && result.status !== 0 && /\bHTTP 404\b/i.test(String(result.stderr))) return null;
    if (result.error || result.status !== 0) throw new Error(`${cmd} failed: ${result.error?.message || result.stderr || result.status}`);
    return result.stdout;
  };
  const gh = (...args) => invoke('gh', args);
  const api = (path, { optional = false, paginate = false, binaryJson = false, fields = [] } = {}) => {
    const args = ['api', `repos/${repository}/${path}`];
    if (paginate) args.push('--paginate', '--slurp');
    if (binaryJson) args.push('-H', 'Accept: application/octet-stream');
    args.push(...fields);
    const data = invoke('gh', args, { optional, ...(binaryJson ? { maxBuffer: 1024 * 1024 } : {}) });
    if (data === null) return null;
    const json = JSON.parse(data);
    if (paginate && (!Array.isArray(json) || json.some(page => !Array.isArray(page)))) throw new Error('Invalid paginated release response.');
    return json;
  };
  const assets = id => api(`releases/${id}/assets?per_page=100`, { paginate: true }).flat();
  const assetArgs = id => ['api', `repos/${repository}/releases/assets/${id}`, '-H', 'Accept: application/octet-stream'];
  const verifyAssetMetadata = (asset, tag) => {
    if (!asset || !Number.isSafeInteger(asset.id) || asset.id <= 0 || asset.state !== 'uploaded' || !Number.isSafeInteger(asset.size) || asset.size < 1 || (tag && asset.browser_download_url !== `https://github.com/${repository}/releases/download/${tag}/${asset.name}`)) throw new Error('Invalid published asset metadata: ' + asset?.name);
  };
  const readAsset = (asset, limit) => {
    if (asset.size > limit) throw new Error('Release asset exceeds size limit: ' + asset.name);
    const bytes = Buffer.from(invoke('gh', assetArgs(asset.id), { encoding: null, maxBuffer: limit }));
    if (bytes.length !== asset.size || (asset.digest && asset.digest !== `sha256:${hash(bytes)}`)) throw new Error('Release asset readback conflict: ' + asset.name);
    return bytes;
  };
  const verifyDigest = (asset, sha256, { readBack = false } = {}) => {
    if (asset.digest && asset.digest !== `sha256:${sha256}`) throw new Error('Release asset digest conflict: ' + asset.name);
    if (asset.digest && !readBack) return;
    // Older assets may not have provider digests; stream via a private file
    // rather than retaining a complete installer or nupkg in process memory.
    const directory = mkdtempSync(join(tmpdir(), 'music-asset-readback-'));
    try {
      const path = join(directory, 'asset');
      const output = openSync(path, 'wx');
      try { invoke('gh', assetArgs(asset.id), { encoding: null, stdio: ['ignore', output, 'pipe'] }); }
      finally { closeSync(output); }
      const input = openSync(path, 'r');
      const buffer = Buffer.alloc(1024 * 1024), digest = createHash('sha256');
      let size = 0;
      try {
        for (let count; (count = readSync(input, buffer)) > 0;) {
          size += count;
          digest.update(buffer.subarray(0, count));
        }
      } finally { closeSync(input); }
      if (size !== asset.size || digest.digest('hex') !== sha256) throw new Error('Release asset readback conflict: ' + asset.name);
    } finally { rmSync(directory, { recursive: true, force: true }); }
  };
  const verifyTag = (tag, sha, { optional = false } = {}) => {
    const ref = api(`git/ref/tags/${tag}`, { optional });
    if (ref === null) return false;
    let object = ref.object;
    const seen = new Set();
    while (object?.type === 'tag') {
      if (!shaPattern.test(object.sha) || seen.has(object.sha) || seen.size >= 16) throw new Error('Invalid annotated tag chain.');
      seen.add(object.sha);
      object = api(`git/tags/${object.sha}`).object;
    }
    if (object?.type !== 'commit' || object.sha !== sha) throw new Error('Release tag/source mismatch: ' + tag);
    return true;
  };
  return { gh, api, invoke, assets, verifyAssetMetadata, readAsset, verifyDigest, verifyTag };
}

export function checkoutSource(root, invoke) {
  const git = (...args) => invoke('git', ['-C', root, ...args]).trim();
  if (git('rev-parse', '--is-shallow-repository') !== 'false') throw new Error('Full Git history required.');
  const sha = git('rev-parse', 'HEAD'), count = Number(git('rev-list', '--count', 'HEAD'));
  const tag = `v0.1.${count}`;
  if (!shaPattern.test(sha) || tagCount(tag) !== count) throw new Error('Invalid checkout source identity.');
  return { sha, count, version: `0.1.${count}`, tag };
}

export function verifyStable(release, { tag, sha, id = release?.id }) {
  if (!release || !Number.isSafeInteger(id) || id <= 0 || release.id !== id || release.tag_name !== tag || release.target_commitish !== sha || release.draft !== false || release.prerelease !== false || typeof release.published_at !== 'string' || !Number.isFinite(Date.parse(release.published_at))) throw new Error('Expected published stable release at exact source: ' + tag);
}
