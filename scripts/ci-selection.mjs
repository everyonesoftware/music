#!/usr/bin/env node
// Separate publication affectedness from manually requested CI jobs.
import { appendFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { main as selectiveMain } from './selective-builds.mjs';

export function main(argv = process.argv.slice(2), { select = selectiveMain, root, run, output = text => console.log(text) } = {}) {
  const options = {};
  for (let i = 0; i < argv.length; i += 2) {
    const key = argv[i], value = argv[i + 1];
    if (!['--repository', '--target', '--trimmed', '--github-output'].includes(key) || Object.hasOwn(options, key) || !value || value.startsWith('-')) throw new Error('Invalid CI selection options.');
    options[key] = value;
  }
  const repository = options['--repository'];
  if (!/^[A-Za-z0-9_][A-Za-z0-9_.-]*\/[A-Za-z0-9_][A-Za-z0-9_.-]*$/.test(repository) || repository.split('/').some(part => part === '.' || part === '..')) throw new Error('Invalid repository.');
  const target = options['--target'] ?? 'auto';
  const trimmed = options['--trimmed'] ?? 'false';
  if (!['auto', 'all', 'android', 'windows', 'maintenance'].includes(target) || !['true', 'false'].includes(trimmed)) throw new Error('Invalid target/trimmed selection.');
  if (trimmed === 'true' && !['windows', 'all'].includes(target)) throw new Error('Trimmed builds require target windows or all.');
  const native = select(['--repository', repository, '--component', 'windows', '--force', 'none'], { root, run, output: () => {} });
  const result = {
    android: trimmed !== 'true' && (['all', 'android'].includes(target) || (target === 'auto' && native.android.build)),
    windows: ['all', 'windows'].includes(target) || (target === 'auto' && native.windows.build),
    android_changed: native.android.build,
    windows_changed: native.windows.build,
    web: false,
  };
  if (trimmed !== 'true' && ['auto', 'all'].includes(target)) result.web = select(['--repository', repository, '--component', 'web', '--force', 'none'], { root, run, output: () => {} }).web.build;
  if (options['--github-output']) appendFileSync(options['--github-output'], Object.entries(result).map(([key, value]) => `${key}=${value}\n`).join(''));
  output(JSON.stringify(result, null, 2));
  return result;
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try { main(); } catch (error) { console.error(error.message); process.exitCode = 1; }
}
