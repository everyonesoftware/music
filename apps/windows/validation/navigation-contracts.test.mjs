import './startup-navigation.test.mjs';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const app = new URL('../Music.App/', import.meta.url);
const read = name => readFileSync(new URL(name, app), 'utf8').replaceAll('\r\n', '\n');
const main = read('MainWindow.cs');
const offline = read('MainWindow.Offline.cs');
const between = (text, start, end) => text.slice(text.indexOf(start), text.indexOf(end, text.indexOf(start) + start.length));
test('both detail kinds omit hardcoded Library link and retain shared transport/virtualization', () => {
  const detail = between(main, 'private void RenderDetail()', 'private static TextBlock Text(');
  assert.doesNotMatch(detail, /←|NavigateAsync|actions.Children.Add\(back\)/);
  assert.match(detail, /CreateCollectionTransport\(\)/);
  assert.match(detail, /view.ItemsSource = detailSongs.Select/);
});
test('normal navigation snapshots current view; restoration does not re-push or reset search/layout', () => {
  const nav = between(main, 'private async Task NavigateAsync(', 'private void SetNavigation()');
  assert.match(nav, /remember && !navigationHistory.Record\(CaptureNavigation\(\), destination\)/);
  assert.match(nav, /query = destination.Query/);
  assert.match(nav, /mode = destination.Mode/);
  assert.match(nav, /updatingNavigation = true/);
  assert.match(main, /updatingNavigation \|\| page is/);
  assert.match(nav, /browseGate.Cancel\(\); artworkGate.Cancel\(\)/);
  assert.match(nav, /ClearDetail\(\)/);
  assert.doesNotMatch(nav, /StartQueue|SelectTrack|StopPlayback|SavePlayback|player\.|queueIndex\s*=|queue\s*=/);
});
test('collection entry records metadata before async load; late request guards remain', () => {
  const open = between(main, 'private async Task OpenCollectionAsync(', 'private void RenderDetail()');
  assert.match(open, /remember && !navigationHistory.Record\(CaptureNavigation\(\), destination\)/);
  assert.ok(open.indexOf('navigationHistory.Record') < open.indexOf('await target.GetAlbumSongsAsync'));
  assert.match(open, /detailCollection = destination\.Collection/);
  assert.match(open, /browseGate.IsCurrent\(lease\).*target != client.*closed/);
  assert.doesNotMatch(open, /StartQueue|SelectTrack|StopPlayback|SavePlayback|player\.|queueIndex\s*=|queue\s*=/);
});
test('mouse Back is routed once, consumed, guarded for popups/editors and does not touch playback', () => {
  const navigation = read('MainWindow.Navigation.cs');
  assert.match(main, /InitializeNavigation\(\)/);
  assert.match(navigation, /AddHandler\(UIElement.PointerPressedEvent,[\s\S]*?true\)/);
  assert.match(navigation, /PointerUpdateKind.XButton1Pressed/);
  assert.match(navigation, /args.Handled = true/);
  assert.match(navigation, /GetOpenPopupsForXamlRoot/);
  assert.match(navigation, /GetFocusedElement\(root.XamlRoot\)/);
  for (const editor of ['TextBox', 'PasswordBox', 'RichEditBox', 'NumberBox', 'AutoSuggestBox', 'ContentDialog']) assert.ok(navigation.includes(editor));
  assert.match(navigation, /TryBack\(out var previous\)/);
  assert.match(navigation, /OpenCollectionAsync\(previous.Collection, remember: false\)/);
  assert.match(navigation, /NavigateAsync\(previous.Page, remember: false, restore: previous\)/);
  assert.doesNotMatch(navigation, /StartQueue|SelectTrack|StopPlayback|SavePlayback|player\.|queueIndex\s*=|queue\s*=/);
});
test('activation, account removal and offline transitions clear scoped history; forced routes do not push', () => {
  const activation = between(main, 'private void ActivateConnection(', 'private async Task TestConnectionAsync()');
  assert.match(activation, /navigationHistory.Clear\(\)/);
  assert.match(activation, /detailCollection = null/);
  assert.match(activation, /page = "library"; query = ""/);
  const toggle = between(offline, 'private async Task SetOfflineAsync(', 'private void RefreshStorageUsage()');
  assert.match(toggle, /navigationHistory.Clear\(\)/);
  assert.match(toggle, /NavigateAsync\(value \? "offline" : "library", remember: false\)/);
  assert.match(offline, /navigationHistory.Clear\(\);[\s\S]*?NavigateAsync\("settings", remember: false\)/);
});
