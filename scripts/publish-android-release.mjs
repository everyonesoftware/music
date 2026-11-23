#!/usr/bin/env node
// Publish Android bytes without moving Latest. Only the index publisher may promote.
import { copyFileSync, lstatSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { checkoutSource, commands, hash, parseOptions, verifyStable } from './release-publication.mjs';

const validator = fileURLToPath(new URL('./android-release.py', import.meta.url));
const manifestLimit = 65536;
const releaseByTag = `query($owner: String!, $name: String!, $tagName: String!) {
  repository(owner: $owner, name: $name) { release(tagName: $tagName) { databaseId } }
}`;

export function main(argv = process.argv.slice(2), { root = fileURLToPath(new URL('..', import.meta.url)), run, output = console.log } = {}) {
  const args = parseOptions(argv, ['directory', 'repository']);
  const io = commands(args.repository, run);
  if (!args.directory) throw new Error('--directory is required.');
  const source = checkoutSource(root, io.invoke);
  const { tag, sha, version } = source, apk = `Music-${version}.apk`;
  const title = `Music ${version}`;
  const names = [apk, `${apk}.sha256`, 'release.json'];
  const directory = mkdtempSync(join(tmpdir(), 'music-android-publication-'));
  try {
    const inventory = new Map();
    for (const name of names) {
      const path = join(resolve(args.directory), name), info = lstatSync(path);
      if (!info.isFile() || info.size < 1 || (name === 'release.json' && info.size > manifestLimit)) throw new Error('Invalid local Android asset: ' + name);
      const staged = join(directory, name);
      copyFileSync(path, staged);
      inventory.set(name, { path: staged, size: lstatSync(staged).size });
    }
    // Call the unchanged packaging validator, never the legacy promote-on-head publisher.
    io.invoke('python3', ['-c', 'import json, pathlib, runpy, sys; runpy.run_path(sys.argv[1])["validate"](pathlib.Path(sys.argv[2]), json.loads(sys.argv[3]))', validator, directory, JSON.stringify(source)]);
    const manifest = JSON.parse(readFileSync(inventory.get('release.json').path, 'utf8'));
    for (const [name, item] of inventory) item.sha256 = name === apk ? manifest.sha256 : hash(readFileSync(item.path));
    const view = (optional = false) => {
      // Use the exact pending-tag lookup behind `gh release view`: a draft
      // can be absent from both the REST tag endpoint and the release list.
      const published = io.api(`releases/tags/${tag}`, { optional: true });
      if (published) return published;
      const [owner, name] = args.repository.split('/');
      const response = JSON.parse(io.gh('api', 'graphql', '-f', `owner=${owner}`, '-f', `name=${name}`, '-f', `tagName=${tag}`, '-f', `query=${releaseByTag}`));
      const match = response?.data?.repository?.release;
      if (response?.errors !== undefined || (match !== null && (!Number.isSafeInteger(match?.databaseId) || match.databaseId < 1))) throw new Error('Invalid exact Android release lookup: ' + tag);
      if (match === null) {
        if (optional) return null;
        throw new Error('Missing Android release: ' + tag);
      }
      const release = io.api(`releases/${match.databaseId}`);
      if (release?.id !== match.databaseId || release.tag_name !== tag) throw new Error('Android release lookup identity mismatch: ' + tag);
      return release;
    };
    const verifyDraft = (release, id = release?.id) => {
      if (!Number.isSafeInteger(id) || id < 1 || release?.id !== id || release.tag_name !== tag || release.name !== title || release.target_commitish !== sha || release.draft !== true || release.prerelease !== false) throw new Error('Expected matching unpublished Android draft.');
    };
    const check = (release, draft) => {
      const remote = io.assets(release.id);
      const missing = [];
      for (const [name, item] of inventory) {
        const matches = remote.filter(asset => asset.name === name);
        if (draft && !matches.length) { missing.push(item.path); continue; }
        if (matches.length !== 1) throw new Error(`Expected one published Android asset: ${name}`);
        const [asset] = matches;
        io.verifyAssetMetadata(asset, draft ? null : tag);
        if (asset.size !== item.size) throw new Error('Android asset size conflict: ' + name);
        io.verifyDigest(asset, item.sha256, { readBack: true });
      }
      return missing;
    };
    let release = view(true);
    if (release?.draft !== false) {
      if (release === null) {
        io.verifyTag(tag, sha, { optional: true });
        const created = io.api('releases', { fields: [
          '--method', 'POST', '-f', `tag_name=${tag}`, '-f', `target_commitish=${sha}`,
          '-f', `name=${title}`, '-f', 'body=', '-F', 'draft=true', '-F', 'prerelease=false', '-f', 'make_latest=false',
        ] });
        verifyDraft(created);
        release = io.api(`releases/${created.id}`);
        if (release?.id !== created.id) throw new Error('Android release lookup identity mismatch: ' + tag);
      }
      verifyDraft(release);
      io.verifyTag(tag, sha, { optional: true });
      const missing = check(release, true);
      if (missing.length) io.gh('release', 'upload', tag, ...missing, '--repo', args.repository);
      const ready = io.api(`releases/${release.id}`);
      verifyDraft(ready, release.id);
      io.verifyTag(tag, sha, { optional: true });
      if (check(ready, true).length) throw new Error('Uploaded Android artifact missing from draft.');
      io.gh('release', 'edit', tag, '--repo', args.repository, '--draft=false', '--latest=false');
    }
    const published = view();
    verifyStable(published, { ...source, id: release.id });
    io.verifyTag(tag, sha);
    check(published, false);
    const result = { tag, sha };
    output(JSON.stringify(result));
    return result;
  } finally { rmSync(directory, { recursive: true, force: true }); }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try { main(); } catch (error) { console.error(error.message); process.exitCode = 1; }
}
