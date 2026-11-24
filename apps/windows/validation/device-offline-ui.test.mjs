import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const app = file => readFileSync(new URL(`../Music.App/${file}`, import.meta.url), 'utf8').replaceAll('\r\n', '\n');

test('collection transport retains Play and Shuffle and ends with the device flyout', () => {
  const source = app('MainWindow.CollectionTransport.cs');
  assert.match(source, /group.Children.Add\(play\); group.Children.Add\(shuffle\); group.Children.Add\(device\)/);
  assert.match(source, /CreateDeviceActions\(detailTitle/);
  assert.doesNotMatch(source, /Symbol.Download/);
});

test('native row actions are visible and have one state-dependent native menu item', () => {
  const main = app('MainWindow.cs');
  assert.match(main, /AddSongDeviceActions\(row, song, owner\)/);
  const source = app('MainWindow.Offline.cs');
  assert.match(source, /Content = new SymbolIcon\(Symbol.More\)/);
  assert.match(source, /button.Flyout = menu/);
  assert.match(source, /menu.Opening \+=/);
  assert.match(source, /"Remove from Device" : "Save to Device"/);
  assert.equal([...source.matchAll(/menu.Items.Add\(/g)].length, 1);
});

test('collection removal remains available when only some claims remain', () => {
  const source = app('MainWindow.CollectionTransport.cs');
  assert.match(source, /bool hasClaims\([^\n]*=> DeviceAvailability\.HasCollectionClaims\(snapshot, key\);/);
  assert.match(source, /CreateDeviceActions\(detailTitle, 48, deviceAvailable, hasClaims,/);
  assert.match(source, /RemoveFromDeviceAsync\(null, key, owner\)/);
});

test('browse cards use fetched membership and discard it when the catalog refreshes', () => {
  const main = app('MainWindow.cs');
  const offline = app('MainWindow.Offline.cs');
  assert.match(main, /detailSongs = tracks.ToList\(\);\s*RememberCollectionMembership\(/);
  for (const source of [main, offline]) {
    assert.match(source, /albums = data.Albums;\s*playlists = MergePlaylistCatalog\(data.Playlists, catalogRevision\);\s*InvalidateCollectionMembership\(\)/);
  }
  assert.match(offline, /DeviceAvailability\.SavedCard\(snapshot, key, count, verifiedCollectionMembership.GetValueOrDefault\(key\)\)/);
});

test('only Saved gets a check beside titles; Now Playing and header badges start collapsed', () => {
  const main = app('MainWindow.cs');
  const source = app('MainWindow.Offline.cs');
  const xaml = app('Shell.xaml');
  assert.match(source, /DeviceSongState.Saved/);
  assert.match(source, /FontIcon \{ Glyph = "\\uE8FB"/);
  assert.match(source, /SetName\(badge, "Saved to Device"\)/);
  assert.match(main, /SongTitle\(song, 14\)/);
  assert.match(main, /UpdateCollectionDeviceBadge\(/);
  for (const name of ['TrackDeviceBadge', 'CollectionDeviceBadge'])
    assert.match(xaml, new RegExp(`x:Name="${name}"[^>]*Visibility="Collapsed"`));
  assert.doesNotMatch(main + source, /SongAvailability\(| · Offline| · Downloaded/);
});
