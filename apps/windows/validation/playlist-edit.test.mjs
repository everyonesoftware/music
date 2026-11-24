import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const read = name => readFileSync(new URL(`../Music.App/${name}`, import.meta.url), 'utf8');

test('Playlists has a native create button and empty details point back to Library', () => {
  assert.match(read('Shell.xaml'), /<Button[^>]*x:Name="NewPlaylistButton"/);
  const ui = read('MainWindow.Playlists.cs');
  assert.match(ui, /page == "playlists"/);
  assert.match(ui, /Content = "Browse Library"/);
  assert.match(ui, /NavigateAsync\("library"\)/);
});

test('playlist actions are wired to collection cards, song rows and every realized Queue song', () => {
  const main = read('MainWindow.cs');
  assert.match(main, /AddCollectionPlaylistAction\(outer, card.Value\)/);
  assert.match(read('MainWindow.Artists.cs'), /AddCollectionPlaylistAction\(panel, artist\)/);
  assert.match(read('MainWindow.Offline.cs'), /AddSongPlaylistAction\(row, song, owner, \(MenuFlyout\)actions.Flyout/);
  assert.match(main, /AddSongPlaylistAction\(row, song, connectionLease, valid: \(\) => ReferenceEquals\(entry.Queue, queue\)/);
  assert.match(main, /args.ItemContainer.ContextFlyout = row.ContextFlyout/);
  assert.match(read('MainWindow.Playlists.cs'), /new MenuFlyoutItem \{ Text = "Add to Playlist"/);
});

test('only creation opens a new detail; append refreshes without touching transport', () => {
  const ui = read('MainWindow.Playlists.cs');
  assert.match(ui, /if \(created\)[\s\S]*?OpenCollectionAsync\(confirmed.Playlist/);
  assert.match(ui, /detailIdentity == \("playlist", confirmed.Playlist.Id\)/);
  assert.doesNotMatch(ui, /StartQueue|SelectTrack|SavePlayback/);
});

test('the submitted draft owns the previewed source and can reload after failed revalidation', () => {
  const ui = read('MainWindow.Playlists.cs');
  assert.match(ui, /PlaylistEdit.ReadSourceAsync\(target, source, token\)/);
  assert.match(ui, /new PlaylistEdit\(target, ids,[^\n]*source\)/);
  assert.match(ui, /catch \(PlaylistSourceChangedException[\s\S]*?ids = null;[\s\S]*?retry.Visibility = Visibility.Visible/);
});

test('unconfirmed append removes stale playable detail and requires an exact read on retry', () => {
  const ui = read('MainWindow.Playlists.cs');
  assert.match(ui, /catch \(PlaylistWriteUncertainException[\s\S]*?InvalidatePlaylistDestination\(edit\?\.DestinationId\)/);
  assert.match(ui, /if \(edit\?\.Acknowledged == true\) InvalidatePlaylistDestination\(edit.DestinationId\)/);
  assert.match(ui, /void InvalidatePlaylistDestination[\s\S]*?RememberCollectionMembership\(identity, null\)[\s\S]*?browseGate.Cancel\(\)[\s\S]*?detailSongs = \[\];[\s\S]*?detailError = true;[\s\S]*?RenderDetail\(\)/);
  const main = read('MainWindow.cs');
  assert.match(main, /unverifiedPlaylistIds.Contains\(playlist.Id\)[\s\S]*?GetPlaylistContentsAsync\(playlist.Id, lease.Token\)[\s\S]*?browseGate.IsCurrent\(lease\)[\s\S]*?unverifiedPlaylistIds.Remove\(/);
  assert.match(ui, /Retry playlist[\s\S]*?OpenCollectionAsync\(playlist, remember: false\)/);
});
