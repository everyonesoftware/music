#!/usr/bin/env node
import { spawnSync } from 'node:child_process';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { deriveSource } from './attach-windows-installer.mjs';

export async function main(argv = process.argv.slice(2), { root = resolve(dirname(fileURLToPath(import.meta.url)), '..'), run = spawnSync, output = text => process.stdout.write(text) } = {}) {
  const options = {};
  for (let i = 0; i < argv.length; i += 2) {
    const key = argv[i];
    const value = argv[i + 1];
    if (!['--repository', '--android-changed'].includes(key) || Object.hasOwn(options, key) ||
        typeof value !== 'string' || !value || value.startsWith('-')) {
      throw new Error('Usage: node scripts/ensure-windows-main-release.mjs --repository OWNER/REPO --android-changed true|false');
    }
    options[key] = value;
  }
  const repository = options['--repository'];
  if (typeof repository !== 'string' || !/^[A-Za-z0-9_][A-Za-z0-9_.-]*\/[A-Za-z0-9_][A-Za-z0-9_.-]*$/.test(repository) || /\s/.test(repository)) throw new Error('Invalid GitHub repository.');
  if (!['true', 'false'].includes(options['--android-changed'])) throw new Error('--android-changed true|false is required.');
  let result = { skipped: true, reason: 'Android changed' };
  if (options['--android-changed'] !== 'true') {
    // deriveSource shares the publisher's full-history version and exact SHA rules.
    const boundedRun = (command, args, extra) => run(command, args, { ...extra, shell: false, timeout: 120_000 });
    const source = deriveSource(root, { run: boundedRun });
    const tag = `v${source.version}`;

    const api = (path, optional = false) => {
      const response = boundedRun('gh', ['api', `repos/${repository}/${path}`], { encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 });
      if (response.error || response.status !== 0) {
        if (!response.error && optional && /\bHTTP 404\b/i.test(String(response.stderr))) return null;
        throw new Error('GitHub command failed: ' + (response.error?.message || response.stderr || `exit ${response.status}`));
      }
      const value = JSON.parse(String(response.stdout));
      if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Invalid GitHub API object.');
      return value;
    };
    const verify = release => {
      if (!release || !Number.isSafeInteger(release.id) || release.id <= 0 || release.tag_name !== tag ||
          release.target_commitish !== source.sha || release.draft !== false || release.prerelease !== false ||
          typeof release.published_at !== 'string' || !Number.isFinite(Date.parse(release.published_at))) {
        throw new Error('Main release source/identity/state mismatch; expected published stable release at exact SHA.');
      }
      verifyTag();
    };
    const verifyTag = (optional = false) => {
      const ref = api(`git/ref/tags/${tag}`, optional);
      if (ref === null) return;
      let object = ref.object;
      const seen = new Set();
      while (object?.type === 'tag') {
        if (!/^(?:[a-f0-9]{40}|[a-f0-9]{64})$/.test(object.sha) || seen.has(object.sha) || seen.size >= 16) throw new Error('Invalid annotated tag chain/SHA.');
        seen.add(object.sha);
        object = api(`git/tags/${object.sha}`)?.object;
      }
      if (object?.type !== 'commit' || object.sha !== source.sha) throw new Error('Main release tag commit mismatch.');
    };
    const initial = api(`releases/tags/${tag}`, true);
    if (initial !== null) verify(initial);
    if (initial === null) {
      // --target does not retarget an existing tag. Reject a conflict before writing.
      verifyTag(true);
      const response = boundedRun('gh', ['release', 'create', tag, '--repo', repository, '--target', source.sha,
        '--title', `Music ${source.version}`, '--notes', 'Windows build (unsigned installer). Android unchanged; see Current downloads for the current Android and Windows installers.',
        '--latest=false', '--draft=false', '--prerelease=false'], { encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 });
      if (response.error || response.status !== 0) throw new Error('GitHub release creation failed: ' + (response.error?.message || response.stderr || `exit ${response.status}`));
      verify(api(`releases/tags/${tag}`));
    }
    result = { tag, sha: source.sha, created: initial === null };
  }
  output(JSON.stringify(result, null, 2) + '\n');
  return result;
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main().catch(error => { console.error(error.message); process.exitCode = 1; });
}
