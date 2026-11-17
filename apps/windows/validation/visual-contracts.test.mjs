import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';

// Linux source contracts, not a substitute for native WinUI layout acceptance.
const app = new URL('../Music.App/', import.meta.url);
const source = readFileSync(new URL('MainWindow.cs', app), 'utf8').replaceAll('\r\n', '\n');
const shelfFile = new URL('MainWindow.LibraryShelves.cs', app);
const shelves = existsSync(shelfFile) ? readFileSync(shelfFile, 'utf8').replaceAll('\r\n', '\n') : '';
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

test('library shelf headings are the sage collection links, with heading semantics and no redundant View all', () => {
  const section = method('NewSection');
  assert.match(section, /target is "playlists" or "albums"/);
  assert.match(section, /new HyperlinkButton[\s\S]*?Content = title/);
  assert.match(section, /Foreground = Sage/);
  for (const state of ['PointerOver', 'Pressed']) {
    assert.match(section, new RegExp(`heading.Resources\\["HyperlinkButtonForeground${state}"\\] = Sage`));
  }
  const linkedHeadingBranch = section.slice(section.indexOf('if (target is'), section.indexOf('\n            else'));
  assert.doesNotMatch(linkedHeadingBranch, /View all/);
  assert.match(section, /SetHeadingLevel\(heading,.*Level2\)/);
  assert.match(section, /heading.Click \+= async .*NavigateAsync\(target\)/);
  assert.match(section, /CreateLibraryShelfHeader\(heading, title\)/);
  assert.match(section, /else[\s\S]*?View all/); // Songs search retains its existing header.
});

test('library renders the playlists row before albums', () => {
  const browse = method('RenderBrowse');
  const playlists = browse.indexOf('NewSection("Playlists", "playlists")');
  const albums = browse.indexOf('NewSection("Albums", "albums")');
  assert.ok(playlists >= 0 && albums >= 0 && playlists < albums);
});

test('library collections use one horizontally scrolling row without the eight-card cap', () => {
  const browse = method('RenderBrowse');
  const collectionCalls = browse.split('\n').filter(line => line.includes('AddCollection(section,'));
  assert.equal(collectionCalls.length, 2);
  for (const call of collectionCalls) {
    assert.match(call, /library \? "row" : mode, artLease.Token\)/);
  }
  const collection = method('AddCollection');
  assert.match(collection, /layout == "grid" \? new GridView\(\) : new ListView\(\)/);
  assert.match(collection, /if \(layout == "row"\)/);
  assert.match(collection, /view.ItemsPanel = \(ItemsPanelTemplate\)root.Resources\["LibraryRowPanel"\]/);
  assert.match(collection, /SetHorizontalScrollMode\(view, ScrollMode.Enabled\)/);
  assert.match(collection, /SetHorizontalScrollBarVisibility\(view, ScrollBarVisibility.Hidden\)/);
  assert.match(collection, /BindLibraryShelf\(section, view\)/);
  assert.match(collection, /SetVerticalScrollMode\(view, ScrollMode.Disabled\)/);
  assert.match(collection, /foreach \(var card in cards\)/);
  assert.match(collection, /CardElement\(card, layout == "row" \? "grid" : layout, token\)/);
  assert.doesNotMatch(collection, /cards.Take\(/);
  assert.match(shell, /<ItemsPanelTemplate x:Key="LibraryRowPanel">\s*<ItemsStackPanel Orientation="Horizontal"\s*\/>\s*<\/ItemsPanelTemplate>/);
});

test('shelf arrows track real scroll and resize boundaries; wheel interception precedes the internal ScrollViewer', () => {
  assert.match(shelves, /HorizontalAlignment = HorizontalAlignment.Right/);
  assert.match(shelves, /"Previous " \+ title.ToLowerInvariant\(\)/);
  assert.match(shelves, /"Next " \+ title.ToLowerInvariant\(\)/);
  assert.match(shelves, /LibraryShelfScrollPolicy.Boundaries\(/);
  assert.match(shelves, /Previous.IsEnabled = state.Previous/);
  assert.match(shelves, /Next.IsEnabled = state.Next/);
  assert.match(shelves, /scroller.ViewChanged \+=/);
  assert.match(shelves, /scroller.SizeChanged \+=/);
  assert.match(shelves, /scroller.LayoutUpdated \+=/);
  assert.match(shelves, /HorizontalSnapPointsType = SnapPointsType.None/);
  assert.match(shelves, /scroller.Content is (?:not )?UIElement wheelSurface/);
  assert.match(shelves, /wheelSurface.AddHandler\(UIElement.PointerWheelChangedEvent, wheelHandler, true\)/);
  assert.match(shelves, /wheelSurface.RemoveHandler\(UIElement.PointerWheelChangedEvent, wheelHandler\)/);
  assert.doesNotMatch(shelves, /view.AddHandler\(UIElement.PointerWheelChangedEvent/);
  assert.match(shelves, /LibraryShelfScrollPolicy.Wheel\(properties.MouseWheelDelta,[\s\S]*?VirtualKeyModifiers.Shift[\s\S]*?properties.IsHorizontalMouseWheel\)/);
  assert.match(shelves, /e.Handled = true/);
  assert.match(shelves, /C<ScrollViewer>\("MainScroll"\)/);
  assert.match(shelves, /horizontalTarget \?\? scroller.HorizontalOffset/);
  assert.match(shelves, /ChangeView\(horizontalTarget, null, null, true\)/);
  assert.doesNotMatch(shelves, /\.Focus\(|ManipulationMode\s*=/);
});

test('library song results are query-aware and keep the song search request', () => {
  assert.match(method('RenderBrowse'), /BrowsePolicy.ShowSection\(page, "songs", songs.Count, query\)/);
  assert.match(method('LoadBrowseAsync'), /requestedPage is "library" or "songs".*SearchSongsAsync\(requestedQuery,/);
});

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
  const row = method('SongRow');
  assert.match(songs, /view.Items.Add\(SongRow\(/);
  assert.match(row, /var grid = SongGrid\(\)/);
  assert.match(songs, /HorizontalContentAlignmentProperty, HorizontalAlignment.Stretch/);
  assert.match(songs, /view.ItemContainerStyle = itemStyle/);
  assert.match(songs, /PaddingProperty, new Thickness\(0\)/);
  assert.match(row, /ToolTipService.SetToolTip\(album, song.Album\)/);
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

test('output trigger matches the compact Android two-line control without a chevron', () => {
  const trigger = shell.match(/<Button x:Name="OutputButton"[\s\S]*?<\/Button>/)?.[0] ?? '';
  assert.match(trigger, /HorizontalAlignment="Left"/);
  assert.match(trigger, /MinHeight="48"/);
  assert.match(trigger, /Text="Media output"/);
  assert.match(trigger, /x:Name="OutputName"[^>]*TextTrimming="CharacterEllipsis"/);
  assert.match(trigger, /Foreground="\{StaticResource Sage\}"/);
  assert.doesNotMatch(trigger, /E70E|E70D|HorizontalAlignment="Stretch"/);
});

test('output chooser uses Android-style device cards instead of a dropdown and Apply', () => {
  const chooser = method('ChooseOutputAsync');
  assert.doesNotMatch(chooser, /ComboBox|PrimaryButtonText = "Apply"/);
  for (const text of ['Choose where to play your music.', 'Close media output', 'Device settings', 'ms-settings:sound', 'Refresh']) {
    assert.ok(chooser.includes(text), `Missing chooser control/copy: ${text}`);
  }
  assert.match(chooser, /new RadioButton/);
  assert.match(chooser, /OutputRouteStyle/);
  assert.match(chooser, /FindAllAsync\(MediaDevice.GetAudioRenderSelector\(\)\)/);
  assert.match(chooser, /currentDevices.FirstOrDefault/);
  assert.match(chooser, /player.AudioDevice\?\.Id != target\?\.Id/);
  assert.match(chooser, /if \(!active \|\| closed\) return/);
  assert.match(chooser, /button.Focus\(FocusState.Programmatic\)/);
  const style = shell.match(/<Style x:Key="OutputRouteStyle"[\s\S]*?<\/Style>/)?.[0] ?? '';
  assert.match(style, /CornerRadius="14"/);
  assert.match(style, /MinHeight="76"/);
  assert.match(style, /VisualState x:Name="Checked"/);
  assert.match(style, /Grid.Column="1"[^>]*Width="20"/);
});