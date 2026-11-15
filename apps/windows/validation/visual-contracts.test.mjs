import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

// Linux source contracts, not a substitute for native WinUI layout acceptance.
const app = new URL('../Music.App/', import.meta.url);
const source = readFileSync(new URL('MainWindow.cs', app), 'utf8');
const shell = readFileSync(new URL('Shell.xaml', app), 'utf8');
function method(name) {
  const start = source.search(new RegExp(`private [^\\n]*\\b${name}\\(`));
  assert.notEqual(start, -1, `Missing rendering method ${name}`);
  const open = source.indexOf('{', start);
  let depth = 1, end = open + 1;
  for (; end < source.length && depth; end++) {
    if (source[end] === '{') depth++;
    if (source[end] === '}') depth--;
  }
  return source.slice(open + 1, end - 1);
}

test('collection detail keeps back, play, and count in one compact action row', () => {
  const detail = method('RenderDetail');
  assert.match(detail, /Orientation = Orientation.Horizontal/);
  assert.match(detail, /panel.Spacing = 12/);
  assert.match(detail, /actions.Children.Add\(back\)/);
  assert.match(detail, /actions.Children.Add\(play\)/);
  assert.match(detail, /actions.Children.Add\(Text\(/);
  assert.doesNotMatch(detail, /panel.Children.Add\((back|play)\)/);
  assert.match(detail, /StartQueue\(detailSongs, 0\)/);
  assert.match(detail, /NavigateAsync\("library"\)/);
  assert.match(detail, /AutomationProperties.SetName\(play, "Play " \+ detailTitle\)/);
});

test('track list shares responsive columns with its headings and stretches item containers', () => {
  const songs = method('AddSongs');
  assert.match(songs, /var header = SongGrid\(\)/);
  for (const label of ['#', 'Title / artist', 'Album', 'Time']) assert.ok(songs.includes(`"${label}"`));
  assert.match(songs, /var grid = SongGrid\(\)/);
  assert.match(songs, /HorizontalContentAlignmentProperty, HorizontalAlignment.Stretch/);
  assert.match(songs, /view.ItemContainerStyle = itemStyle/);
  assert.match(songs, /PaddingProperty, new Thickness\(0\)/);
  assert.match(songs, /ToolTipService.SetToolTip\(album, song.Album\)/);
  assert.match(songs, /StartQueue\(playQueue, index\)/);
  const grid = method('SongGrid');
  assert.match(grid, /new GridLength\(1, GridUnitType.Star\)/);
  assert.match(grid, /new GridLength\(0.8, GridUnitType.Star\)/);
  assert.match(grid, /ColumnSpacing = 12/);
  assert.match(grid, /MinHeight = 56/);
  assert.doesNotMatch(grid, /new GridLength\(160\)/);
});

test('sidebar uses borderless navigation rather than default outlined buttons', () => {
  const style = shell.match(/<Style x:Key="NavigationButtonStyle"[\s\S]*?<\/Style>/)?.[0] ?? '';
  assert.match(style, /Property="BorderThickness" Value="0"/);
  assert.match(style, /Property="Background" Value="Transparent"/);
  assert.match(style, /Property="MinHeight" Value="42"/);
  for (const name of ['LibraryNav', 'PlaylistsNav', 'AlbumsNav', 'SongsNav', 'SettingsNav']) {
    assert.match(shell, new RegExp(`<Button[^>]*x:Name="${name}"[^>]*Style="\\{StaticResource NavigationButtonStyle\\}"`));
  }
});

test('slider template brushes stay sage across pointer states, including disabled playback', () => {
  for (const part of ['SliderThumbBackground', 'SliderTrackValueFill']) {
    for (const state of ['', 'PointerOver', 'Pressed', 'Disabled']) {
      const color = state === 'Disabled' ? '#65765e' : '#c0d7ae';
      assert.match(shell, new RegExp(`<SolidColorBrush x:Key="${part}${state}" Color="${color}"`));
    }
  }
  for (const state of ['', 'PointerOver', 'Pressed', 'Disabled']) {
    assert.match(shell, new RegExp(`<SolidColorBrush x:Key="SliderTrackFill${state}" Color="#526052"`));
  }
});

test('player uses one icon vocabulary, balanced columns, and independent output label', () => {
  assert.match(shell, /RowDefinition Height="112"/);
  assert.match(shell, /x:Name="PlayerComposition"[\s\S]*?Width="\*"\/><ColumnDefinition Width="1.2\*"\/><ColumnDefinition Width="\*"/);
  for (const [name, glyph] of [['ShuffleButton', 'E8B1'], ['RepeatButton', 'E8EE']]) {
    assert.match(shell, new RegExp(`x:Name="${name}"[^>]*Content="&#x${glyph};"[^>]*Style="\\{StaticResource TransportToggleStyle\\}"`));
  }
  for (const name of ['PreviousButton', 'NextButton']) {
    assert.match(shell, new RegExp(`x:Name="${name}"[^>]*Style="\\{StaticResource TransportButtonStyle\\}"`));
  }
  assert.match(shell, /x:Name="PlayButton"[^>]*Width="44"[^>]*Height="44"[^>]*CornerRadius="22"/);
  assert.match(shell, /x:Name="OutputName"[^>]*TextTrimming="CharacterEllipsis"/);
  assert.match(shell, /x:Name="QueueButton"[\s\S]*?<FontIcon Glyph="&#xE8FD;"/);
  assert.match(source, /C<TextBlock>\("OutputName"\).Text = target\?\.Name \?\? "System default output"/);
  assert.doesNotMatch(shell, /Content="[⤨↻☷◖]/);
});

test('view picker updates a native icon without replacing its chevron or menu', () => {
  assert.match(shell, /<SymbolIcon x:Name="LayoutIcon" Symbol="ViewAll"/);
  assert.match(shell, /x:Name="CloseQueueButton" Content="&#xE8BB;"/);
  assert.match(source, /C<SymbolIcon>\("LayoutIcon"\).Symbol = mode == "grid" \? Symbol.ViewAll : Symbol.List/);
  assert.doesNotMatch(source, /C<Button>\("LayoutButton"\).Content =/);
  assert.doesNotMatch(shell, /Content="▦/);
});

test('collection title reclaims the hidden search width instead of leaving a header-sized gap', () => {
  const header = shell.slice(shell.indexOf('<Grid Margin="0,0,0,16">'), shell.indexOf('<InfoBar'));
  assert.doesNotMatch(header, /ColumnDefinition Width="260"/);
  assert.match(header, /x:Name="SearchBox"[^>]*Width="260"/);
  assert.match(header, /x:Name="PageTitle"[^>]*TextTrimming="CharacterEllipsis"/);
});