import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, mkdtempSync, writeFileSync, rmSync, existsSync } from 'node:fs';
import { join, delimiter } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

// Compile the actual installUpdate and mutex-protected updateProgress bodies.
// Substitute only Android/controller/updater/store boundaries; real session rules,
// occurrence IDs, coroutines and mutex scheduling are used. No emulator required.
test('installer captured progress and handoff are occurrence/controller fenced', () => {
  const main = new URL('../app/src/main/java/com/everyonesoftware/audio/', import.meta.url);
  const read = name => readFileSync(new URL(name, main), 'utf8').replaceAll('\r\n', '\n');
  const method = (text, start, end) => {
    const at = text.indexOf(start); assert.notEqual(at, -1);
    const until = text.indexOf(end, at); assert.notEqual(until, -1);
    return text.slice(at, until);
  };
  const install = method(read('ui/AudioViewModel.kt'), '    override fun installUpdate()', '\n    /** Main-thread check').replace('override fun', 'fun');
  const progress = method(read('AudioGraph.kt'), '    suspend fun updateProgress(', '    suspend fun applyRating(');
  const cache = process.env.GRADLE_USER_HOME || join(process.env.HOME, '.gradle');
  const modules = join(cache, 'caches/modules-2/files-2.1');
  function jar(group, name, version) {
    const root = join(modules, group, name, version);
    const result = readdirSync(root).flatMap(hash => readdirSync(join(root, hash)).filter(n => n.endsWith('.jar')).map(n => join(root, hash, n)));
    assert.equal(result.length, 1, root); return result[0];
  }
  const cp = [jar('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.2.21'),
    jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.2.21'),
    jar('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.2.21'),
    jar('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
    jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.10.2'),
    jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-test-jvm', '1.10.2'),
    jar('org.jetbrains.kotlinx', 'kotlinx-serialization-core-jvm', '1.9.0'),
    jar('org.jetbrains', 'annotations', '23.0.0')].join(delimiter);
  const dir = mkdtempSync(join(process.env.TMPDIR || '/tmp', 'music-install-'));
  const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin/java') : 'java';
  assert.ok(!process.env.JAVA_HOME || existsSync(java), java);
  try {
    const template = readFileSync(new URL('./InstallUpdateHarness.kt.txt', import.meta.url), 'utf8');
    writeFileSync(join(dir, 'Harness.kt'), template.replace('/* INSTALL_METHOD */', install).replace('/* PROGRESS_METHOD */', progress));
    const sources = ['data/Models.kt', 'playback/SessionRules.kt', 'playback/PlaybackHistory.kt', 'playback/CarMediaIds.kt'].map(name => fileURLToPath(new URL(name, main)));
    const compile = spawnSync(java, ['-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', cp, '-d', join(dir, 'classes'), ...sources, join(dir, 'Harness.kt')], { encoding: 'utf8', timeout: 120000 });
    assert.equal(compile.status, 0, compile.stdout + compile.stderr);
    const failures = [];
    for (const selector of ['progress', 'handoff', 'controller', 'resume', 'success']) {
      const result = spawnSync(java, ['-cp', join(dir, 'classes') + delimiter + cp, 'HarnessKt', selector], { encoding: 'utf8', timeout: 30000 });
      if (result.status !== 0) failures.push(`${selector}: ${result.stdout}${result.stderr}`);
      else console.log(result.stdout.trim());
    }
    assert.deepEqual(failures, []);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});
