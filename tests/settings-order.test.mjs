import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

test('Windows Settings puts Updates before connection sections', () => {
  const shell = readFileSync(new URL('../apps/windows/Music.App/Shell.xaml', import.meta.url), 'utf8');
  const settings = shell.split('x:Name="SettingsPanel"')[1].split('</ScrollViewer>')[0];
  const headings = [...settings.matchAll(/<TextBlock Text="([^"]+)" FontSize="21"/g)].map(match => match[1]);
  assert.deepEqual(headings, ['Updates', 'Connection', 'Connection info']);
});

test('Android Settings puts Updates before connection and storage controls', () => {
  const source = readFileSync(new URL('../android/app/src/main/java/com/everyonesoftware/audio/ui/AudioScreens.kt', import.meta.url), 'utf8');
  const settings = source.split('private fun Settings(')[1].split('private fun Queue(')[0];
  const updates = settings.indexOf('testTag("settings-updates")');
  assert.ok(updates >= 0);
  assert.equal(settings.match(/testTag\("settings-updates"\)/g).length, 1);
  assert.ok(updates < settings.indexOf('Hint("Connections on this device.'));
  assert.ok(updates < settings.indexOf('StorageSettings(state, actions)'));
});
