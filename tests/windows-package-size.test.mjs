import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { readWorkflow, workflowJob } from './helpers/workflow.mjs';

const project = readFileSync(new URL('../apps/windows/Music.App/Music.App.csproj', import.meta.url), 'utf8');

test('self-contained Music references only the Windows App SDK components required by WinUI', () => {
  const packages = [...project.matchAll(/<PackageReference Include="([^"]+)" Version="([^"]+)"/g)];
  const sdk = packages.filter(([, name]) => name.startsWith('Microsoft.WindowsAppSDK'));
  assert.deepEqual(sdk.map(([, name, version]) => [name, version]), [
    ['Microsoft.WindowsAppSDK.WinUI', '1.8.260803003'],
  ], 'the umbrella/runtime packages bundle unused AI, ML, widgets and DirectWrite components');
  assert.match(project, /<WindowsAppSDKSelfContained>true<\/WindowsAppSDKSelfContained>/);
  assert.match(project, /<SelfContained>true<\/SelfContained>/);
  assert.match(project, /<PublishTrimmed>false<\/PublishTrimmed>/,
    'runtime-loaded XAML must not be trimmed without native compatibility testing');
  assert.match(project, /<Copy SourceFiles="\$\(ProjectPriFullPath\)" DestinationFiles="\$\(PublishDir\)resources\.pri"/);
});

test('resolved self-contained SDK payload keeps native WinUI resources without optional components', () => {
  const root = fileURLToPath(new URL('../', import.meta.url));
  const app = 'apps/windows/Music.App/Music.App.csproj';
  const run = (args) => {
    const result = spawnSync('dotnet', args, { cwd: root, encoding: 'utf8', timeout: 120000, maxBuffer: 8 * 1024 * 1024 });
    assert.ifError(result.error);
    assert.equal(result.status, 0, result.stdout + result.stderr);
    return result.stdout;
  };
  run(['restore', app]);
  // Evaluate the SDK's real copy inventory without running Windows-only mt/makepri.
  const inventory = JSON.parse(run(['msbuild', app, '-nologo', '-p:Configuration=Release',
    '-t:AddMicrosoftWindowsAppSDKPayloadFilesFromComponents',
    '-getItem:MicrosoftWindowsAppSDKFiles,MicrosoftWindowsAppSDKFilesRes'])).Items;
  const files = [...inventory.MicrosoftWindowsAppSDKFiles, ...inventory.MicrosoftWindowsAppSDKFilesRes];
  const names = files.map(file => `${file.Filename}${file.Extension}`.toLowerCase());
  for (const name of ['microsoft.ui.xaml.dll', 'microsoft.windowsappruntime.dll',
    'microsoft.ui.xaml.controls.pri', 'microsoft.ui.pri', 'microsoft.windowsappruntime.pri']) {
    assert.ok(names.includes(name), `missing native runtime/resource: ${name}`);
  }
  for (const file of files) {
    assert.doesNotMatch(file.FullPath.replaceAll('\\', '/').toLowerCase(),
      /microsoft\.windowsappsdk\.(ai|ml|widgets|dwrite|runtime)\//);
  }
  const assets = JSON.parse(readFileSync(new URL('../apps/windows/Music.App/obj/project.assets.json', import.meta.url), 'utf8'));
  const packages = Object.keys(assets.libraries);
  assert.doesNotMatch(packages.join('\n'), /Microsoft\.WindowsAppSDK(?:\/|\.(?:AI|ML|Widgets|DWrite|Runtime)\/)/);
});

test('validation and native Windows packaging run the package footprint regression checks', () => {
  const ci = readWorkflow();
  for (const name of ['validate', 'windows']) {
    assert.match(workflowJob(ci, name), /node --test[^\n]*tests\/windows-package-size\.test\.mjs/);
  }
});
