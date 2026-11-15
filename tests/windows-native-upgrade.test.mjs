import assert from 'node:assert/strict';
import { existsSync, readFileSync, mkdtempSync, rmSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const root = new URL('../', import.meta.url);
const read = path => {
  const url = new URL(path, root);
  assert.ok(existsSync(url), `missing native validation file: ${path}`);
  return readFileSync(url, 'utf8');
};

test('native trimming workflow is isolated from release publication', () => {
  const workflow = read('.github/workflows/windows-native-validation.yml');
  assert.match(workflow, /branches: \[validation\/windows-trimming\]/);
  assert.match(workflow, /contents: read/);
  assert.doesNotMatch(workflow, /contents: write|gh release|windows-release.py publish|attach-windows-installer/);
  assert.match(workflow, /windows-2025/);
  assert.match(workflow, /test-windows-trimmed-native.ps1/);
  assert.match(workflow, /if: always\(\)/);
  assert.match(workflow, /timeout-minutes: 15/);
  assert.match(workflow, /name: Exercise the exact trimmed installer and installed upgrade\s+timeout-minutes: 25/);
});

test('native orchestration checks the real shipping installer and an installed untrimmed-to-trimmed upgrade', () => {
  const source = read('scripts/test-windows-trimmed-native.ps1');
  assert.match(source, /test-windows-installer.ps1/);
  assert.match(source, /MusicNativeAcceptance=false/);
  assert.match(source, /MusicNativeAcceptance=true/);
  assert.match(source, /Existing Music profile/);
  assert.match(source, /Existing MusicWindows registration/);
  assert.match(source, /'apply', '--package',[^\n]*'--norestart', '--silent'/);
  assert.match(source, /MUSIC_NATIVE_ACCEPTANCE_STAGE/);
  assert.match(source, /'seed'/);
  assert.match(source, /'restore'/);
  assert.match(source, /seed-result.json/);
  assert.match(source, /restore-result.json/);
  assert.match(source, /WaitForExit\(/);
  assert.match(source, /0\.1\.32000/);
  assert.match(source, /0\.1\.32001/);
  assert.match(source, /uninstallVerified/);
  assert.match(source, /function Get-OwnedLinks/);
  assert.match(source, /function Remove-OwnedDirectory/);
  assert.match(source, /\$remainingLinks = @\(Get-OwnedLinks\)/);
  assert.match(source, /\$remainingLinks.Count/);
  assert.doesNotMatch(source, /Remove-Item -LiteralPath \$profile[^\n]*SilentlyContinue/);
  assert.match(source, /Get-FileHash/);
  assert.doesNotMatch(source, /gh release|attach-windows-installer/);
});

const hasPowerShell = spawnSync('pwsh', ['-NoProfile', '-Command', '$PSVersionTable.PSVersion.ToString()'], { encoding: 'utf8' }).status === 0;
test('native upgrade script parses with the real PowerShell parser', { skip: !hasPowerShell && 'pwsh is not installed' }, () => {
  const script = fileURLToPath(new URL('scripts/test-windows-trimmed-native.ps1', root));
  const result = spawnSync('pwsh', ['-NoProfile', '-Command',
    '$tokens=$null; $errors=$null; [Management.Automation.Language.Parser]::ParseFile($env:MUSIC_SCRIPT_UNDER_TEST,[ref]$tokens,[ref]$errors) | Out-Null; if ($errors.Count) { $errors | Out-String | Write-Error; exit 1 }'],
  { encoding: 'utf8', timeout: 30000, env: { ...process.env, MUSIC_SCRIPT_UNDER_TEST: script } });
  assert.ifError(result.error);
  assert.equal(result.status, 0, result.stdout + result.stderr);
});

test('native upgrade really fails closed on Linux and retains honest evidence', {
  skip: (!hasPowerShell || process.platform === 'win32') && 'requires non-Windows pwsh',
}, () => {
  const directory = mkdtempSync(join(tmpdir(), 'music-native-validation-'));
  try {
    const result = spawnSync('pwsh', ['-NoProfile', '-File', fileURLToPath(new URL('scripts/test-windows-trimmed-native.ps1', root)),
      '-EvidenceDirectory', directory], { encoding: 'utf8', timeout: 30000 });
    assert.ifError(result.error);
    assert.notEqual(result.status, 0);
    const summary = JSON.parse(readFileSync(join(directory, 'native-summary.json'), 'utf8').replace(/^\uFEFF/, ''));
    assert.equal(summary.status, 'failed');
    assert.match(summary.failure, /Native Windows is required/);
    for (const key of ['shippingInstallerVerified', 'seedVerified', 'upgradeVerified', 'restorationVerified', 'uninstallVerified', 'mediaVerified', 'audibleOutputVerified', 'publicRelease']) {
      assert.equal(summary[key], false, key);
    }
    assert.deepEqual(summary.cleanupErrors, []);
  } finally { rmSync(directory, { recursive: true, force: true }); }
});

test('owned-directory cleanup records both deletion errors and silent leftovers', { skip: !hasPowerShell && 'pwsh is not installed' }, () => {
  const directory = mkdtempSync(join(tmpdir(), 'music-native-cleanup-'));
  try {
    const command = `
$ErrorActionPreference='Stop'
$tokens=$null; $errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile($env:MUSIC_SCRIPT_UNDER_TEST,[ref]$tokens,[ref]$errors)
$definition=$ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Remove-OwnedDirectory'},$true)
if (-not $definition) { throw 'Missing cleanup helper' }
. ([scriptblock]::Create($definition.Extent.Text))
$observed=@()
foreach ($mode in @('throw','noop')) {
  $script:removalMode=$mode
  function Remove-Item { param($LiteralPath,[switch]$Recurse,[switch]$Force,$ErrorAction) if ($script:removalMode -eq 'throw') { throw 'fixture sharing violation' } }
  $summary=@{cleanupErrors=@()}
  Remove-OwnedDirectory $env:MUSIC_CLEANUP_FIXTURE 'Fixture'
  $observed+=@{mode=$mode; errors=$summary.cleanupErrors.Count; remains=(Test-Path -LiteralPath $env:MUSIC_CLEANUP_FIXTURE)}
}
Microsoft.PowerShell.Management\\Remove-Item Function:Remove-Item
$summary=@{cleanupErrors=@()}
Remove-OwnedDirectory $env:MUSIC_CLEANUP_FIXTURE 'Fixture'
@{failures=$observed; successErrors=$summary.cleanupErrors.Count; successRemains=(Test-Path -LiteralPath $env:MUSIC_CLEANUP_FIXTURE)} | ConvertTo-Json -Compress -Depth 4
`;
    const result = spawnSync('pwsh', ['-NoProfile', '-Command', command], {
      encoding: 'utf8', timeout: 30000,
      env: { ...process.env, MUSIC_SCRIPT_UNDER_TEST: fileURLToPath(new URL('scripts/test-windows-trimmed-native.ps1', root)), MUSIC_CLEANUP_FIXTURE: directory },
    });
    assert.ifError(result.error);
    assert.equal(result.status, 0, result.stdout + result.stderr);
    const evidence = JSON.parse(result.stdout);
    assert.deepEqual(evidence.failures.map(row => [row.mode, row.errors, row.remains]), [['throw', 1, true], ['noop', 1, true]]);
    assert.equal(evidence.successErrors, 0);
    assert.equal(evidence.successRemains, false);
  } finally { rmSync(directory, { recursive: true, force: true }); }
});
