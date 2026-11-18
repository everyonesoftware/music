import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const source = readFileSync(new URL('../Music.App/MainWindow.cs', import.meta.url), 'utf8').replaceAll('\r\n', '\n');
const uiPath = new URL('../Music.App/MainWindow.CollectionTransport.cs', import.meta.url);
const ui = () => readFileSync(uiPath, 'utf8').replaceAll('\r\n', '\n');
test('album and playlist detail group Play, Shuffle, icon-only Download on the left', () => {
  assert.match(source, /CreateCollectionTransport\(\)/);
  const controls = ui();
  assert.match(controls, /new Grid/);
  assert.match(controls, /var shuffle = new Button/);
  assert.match(controls, /Symbol.Shuffle/);
  assert.doesNotMatch(controls, /new ToggleButton|new Ellipse|IsChecked = selected|SetItemStatus|SetShuffled|IsShuffled/);
  assert.match(controls, /Width = 56, Height = 56, CornerRadius = new CornerRadius\(28\)/);
  assert.match(controls, /var shuffle = new Button[^;]*MinWidth = 0/s);
  assert.match(controls, /var play = new Button[^;]*MinWidth = 0/s);
  assert.match(controls, /Background = Sage/);
  assert.match(controls, /var group = new StackPanel \{ Orientation = Orientation.Horizontal, Spacing = 8, HorizontalAlignment = HorizontalAlignment.Left \}/);
  assert.match(controls, /group.Children.Add\(play\);\s*group.Children.Add\(shuffle\);\s*group.Children.Add\(download\)/);
  assert.match(controls, /transport.Children.Add\(group\)/);
  assert.doesNotMatch(controls, /Grid.SetColumn|GridUnitType.Star|var right/);
  assert.match(controls, /var download = new Button \{ Content = new SymbolIcon\(Symbol.Download\)/);
  assert.match(controls, /SetName\(download, "Download " \+ detailTitle\)/);
  assert.match(controls, /SetToolTip\(download, AutomationProperties.GetName\(download\)\)/);
  assert.doesNotMatch(controls, /Text\("Download"|Content = "Download"/);
  assert.match(controls, /SetName\(play, "Play " \+ identity.Kind \+ " in order"\)/);
  assert.match(controls, /SetName\(shuffle, "Play " \+ identity.Kind \+ " shuffled"\)/);
});
test('detail callbacks fence account, collection, loading/error/offline and stale connection before side effects', () => {
  const controls = ui();
  assert.match(controls, /collectionTransport.CanStart\(detailLoading, detailError, offlineSettings.Offline/);
  assert.match(controls, /connectionGate.IsCurrent\(owner\)/);
  assert.match(controls, /ReferenceEquals\(capturedSongs, detailSongs\)/);
  assert.match(controls, /account == connection\?\.Id/);
  assert.match(controls, /identity == detailIdentity/);
  assert.match(source, /Album album => \("album", album.Id\)/);
  assert.match(source, /Playlist playlist => \("playlist", playlist.Id\)/);
  assert.match(source, /detailError = true/);
  assert.match(controls, /play.Click \+= .*start\(false\)/);
  assert.match(controls, /shuffle.Click \+= .*start\(true\)/);
  const start = controls.slice(controls.indexOf('void start('));
  assert.match(start, /collectionTransport.Prepare\(capturedSongs, available\(\), shuffled\)/);
  assert.match(start, /if \(prepared is null\) return/);
  assert.match(start, /C<ToggleButton>\("ShuffleButton"\).IsChecked = false/);
  assert.match(start, /StartQueue\(prepared, 0\)/);
  assert.ok(start.indexOf('if (prepared is null) return') < start.indexOf('IsChecked = false'));
});
test('Play uses an explicitly dark filled triangle rather than an outlined font symbol', () => {
  const controls = ui();
  assert.match(controls, /var playIcon = new Polygon/);
  assert.match(controls, /Points = new PointCollection/);
  assert.match(controls, /Fill = new SolidColorBrush\(ColorHelper.FromArgb\(255, 36, 52, 31\)\)/);
  assert.match(controls, /var play = new Button \{ Content = playIcon/);
  assert.doesNotMatch(controls, /Symbol.Play/);
});
test('Download has no button background or border while retaining a native focusable hit target', () => {
  const controls = ui();
  assert.match(controls, /var download = new Button[^;]*Background = new SolidColorBrush\(Colors.Transparent\)/s);
  assert.match(controls, /var download = new Button[^;]*BorderThickness = new Thickness\(0\)/s);
  for (const state of ['', 'PointerOver', 'Pressed', 'Disabled']) {
    assert.ok(controls.includes(`download.Resources["ButtonBackground${state}"] = new SolidColorBrush(Colors.Transparent)`));
    assert.ok(controls.includes(`download.Resources["ButtonBorderBrush${state}"] = new SolidColorBrush(Colors.Transparent)`));
  }
});
