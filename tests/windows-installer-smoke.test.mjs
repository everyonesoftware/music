import assert from 'node:assert/strict';
import { existsSync, readFileSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const script = fileURLToPath(new URL('../scripts/test-windows-installer.ps1', import.meta.url));
const source = () => {
  assert.ok(existsSync(script), 'Windows installer smoke gate must exist');
  return readFileSync(script, 'utf8');
};
const includes = (text, values) => values.forEach(value => assert.ok(text.includes(value), `Missing contract: ${value}`));

test('requires actual installer and evidence paths; rejects unsafe existing user state', () => {
  includes(source(), ['[Parameter(Mandatory = $true)]', '[string]$InstallerPath', '[string]$EvidenceDirectory', 'Get-FileHash', 'SHA256', '[guid]::NewGuid()', '--installto', 'Existing MusicWindows registration', 'Existing Music profile', 'Existing Start menu shortcut']);
});

test('uses pinned supported silent CLI and bounded processes, not package extraction', () => {
  includes(source(), ['velopack/1.2.161', "'--silent', '--installto'", "'--uninstall', '--silent'", "'--log'", 'WaitForExit(', 'Kill($true)', 'ExitCode', 'current\\Music.exe', 'Microsoft.UI.Xaml.dll', 'Microsoft.WindowsAppRuntime.dll', 'coreclr.dll']);
  assert.doesNotMatch(source(), /Expand-Archive|7z\s+x|--no-activate|--skip-install/);
});

test('requires a real visible Music window owned by the installed executable and responsive to WM_NULL', () => {
  includes(source(), ['EnumWindows', 'GetWindowThreadProcessId', 'IsWindowVisible', 'GetWindowText', 'GetWindowRect', 'SendMessageTimeout', 'SMTO_ABORTIFHUNG', 'OpenInputDesktop', 'SwitchDesktop', 'UserInteractive', 'SessionId', "$title -eq 'Music'", 'ExecutablePath', 'WindowTimeoutSeconds', 'stableSamples', 'window.json']);
  assert.doesNotMatch(source(), /MainWindowHandle\s*-ne\s*0\s*\)\s*\{\s*(return|exit\s+0)/);
});

test('captures a validated screenshot and structured success only after uninstall verification', () => {
  const text = source();
  includes(text, ['CopyFromScreen', 'window.png', 'GetPixel', 'screenshotSha256', 'ConvertTo-Json', 'summary.json', 'installed-files.json', 'events.json', 'Start-Transcript', 'Stop-Transcript', 'uninstallVerified', 'Get-StartMenuLinks', 'Remaining installed payload']);
  assert.ok(text.indexOf('$summary.uninstallVerified = $true') < text.indexOf("$summary.status = 'passed'"));
  assert.ok(text.indexOf('Save-WindowScreenshot') < text.indexOf("$summary.status = 'passed'"));
});

test('failure retains diagnostics and always attempts scoped cleanup, without masking uninstall failure', () => {
  includes(source(), ['finally {', 'cleanupErrors', 'failure.txt', 'cleanup-uninstall.log', 'Get-OwnedProcesses', 'Stop-OwnedProcesses', 'Remove-Item -LiteralPath $installRoot', '$summary.status -ne \'passed\'', 'exit 1', 'cleanupRemovedInstallRoot', 'uninstallRemaining.json']);
  assert.doesNotMatch(source(), /Stop-Process\s+-Name|Remove-Item[^\r\n]*\$env:LOCALAPPDATA/);
});

test('registry evidence serializes only scalar fields, never recursive provider metadata', () => {
  assert.match(source(), /Write-Json \(\$registration \| Select-Object DisplayName, DisplayVersion, InstallLocation, UninstallString\) 'registration\.json'/);
  assert.doesNotMatch(source(), /Write-Json \$registration 'registration\.json'/);
});

test('launch failures retain process exit details and opt-in startup diagnostics', () => {
  includes(source(), ['MUSIC_STARTUP_DIAGNOSTICS', 'app-exit.json', '$app.HasExited', '$app.ExitCode']);
});

const probe = spawnSync('pwsh', ['-NoLogo', '-NoProfile', '-Command', '$PSVersionTable.PSVersion.ToString()'], { encoding: 'utf8' });
test('PowerShell parser accepts the complete script when pwsh is available', { skip: probe.error?.code === 'ENOENT' ? 'pwsh is not installed; native Windows acceptance still required' : false }, () => {
  assert.equal(probe.status, 0, probe.stderr);
  const result = spawnSync('pwsh', ['-NoLogo', '-NoProfile', '-Command', '& { param($p) $tokens=$null; $errors=$null; [System.Management.Automation.Language.Parser]::ParseFile($p,[ref]$tokens,[ref]$errors) > $null; if ($errors.Count) { $errors | Format-List | Out-String | Write-Error; exit 1 } }', script], { encoding: 'utf8' });
  assert.equal(result.status, 0, result.stdout + result.stderr);
});

test('embedded Win32 interop C# compiles with PowerShell Add-Type', { skip: probe.error?.code === 'ENOENT' ? 'pwsh is not installed' : false }, () => {
  const nativeSource = source().match(/Add-Type -TypeDefinition @'\r?\n([\s\S]*?)\r?\n'@/);
  assert.ok(nativeSource, 'interop source must be available for compilation');
  const directory = mkdtempSync(join(tmpdir(), 'music-native-contract-'));
  try {
    const file = join(directory, 'Native.cs');
    writeFileSync(file, nativeSource[1]);
    const result = spawnSync('pwsh', ['-NoLogo', '-NoProfile', '-Command', '& { param($p) $ErrorActionPreference="Stop"; Add-Type -Path $p }', file], { encoding: 'utf8', timeout: 30000 });
    assert.equal(result.status, 0, result.stdout + result.stderr);
  } finally { rmSync(directory, { recursive: true, force: true }); }
});

test('non-Windows execution really fails closed and reads back retained summary and diagnostics', { skip: process.platform === 'win32' || probe.error?.code === 'ENOENT' ? 'requires non-Windows pwsh' : false }, () => {
  const directory = mkdtempSync(join(tmpdir(), 'music-smoke-rejection-'));
  try {
    const evidence = join(directory, 'evidence');
    const result = spawnSync('pwsh', ['-NoLogo', '-NoProfile', '-File', script, '-InstallerPath', join(directory, 'not-an-installer.exe'), '-EvidenceDirectory', evidence], { encoding: 'utf8', timeout: 30000 });
    assert.equal(result.status, 1, result.stdout + result.stderr);
    const summary = JSON.parse(readFileSync(join(evidence, 'summary.json'), 'utf8').replace(/^\uFEFF/, ''));
    assert.equal(summary.status, 'failed');
    assert.equal(summary.installVerified, false);
    assert.equal(summary.windowVerified, false);
    assert.equal(summary.uninstallVerified, false);
    assert.equal(summary.installRoot, null);
    assert.deepEqual(summary.cleanupErrors, []);
    assert.match(summary.failure, /Native Windows is required/);
    assert.match(readFileSync(join(evidence, 'failure.txt'), 'utf8'), /Native Windows is required/);
    assert.ok(existsSync(join(evidence, 'transcript.log')));
    const original = readFileSync(join(evidence, 'summary.json'), 'utf8');
    const second = spawnSync('pwsh', ['-NoLogo', '-NoProfile', '-File', script, '-InstallerPath', 'ignored.exe', '-EvidenceDirectory', evidence], { encoding: 'utf8', timeout: 30000 });
    assert.notEqual(second.status, 0);
    assert.match(second.stdout + second.stderr, /EvidenceDirectory must be empty/);
    assert.equal(readFileSync(join(evidence, 'summary.json'), 'utf8'), original, 'prior evidence must not be overwritten');
  } finally { rmSync(directory, { recursive: true, force: true }); }
});
