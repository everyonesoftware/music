import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import test from 'node:test';

test('downloads use a permanent README link with no automated table writer', () => {
  const readme = readFileSync(new URL('../README.md', import.meta.url), 'utf8');
  assert.match(readme, /\[Downloads\]\(https:\/\/github\.com\/everyonesoftware\/music\/releases\/latest\)/);
  assert.doesNotMatch(readme, /current-downloads:start/);
  const workflows = new URL('../.github/workflows/', import.meta.url);
  assert.equal(existsSync(new URL('current-downloads.yml', workflows)), false);
  for (const file of readdirSync(workflows).filter(name => /\.ya?ml$/.test(name))) {
    assert.doesNotMatch(readFileSync(new URL(file, workflows), 'utf8'), /current-downloads\.mjs[^\n]*--publish/, file);
  }
});