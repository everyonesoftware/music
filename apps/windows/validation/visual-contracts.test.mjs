import './playing-callback.test.mjs';
import './artists-flow.test.mjs';
import './artists-visual.test.mjs';
import './up-next-shuffle.test.mjs';
import './player-controls.test.mjs';
import './taskbar-playback.test.mjs';
import './played-queue.test.mjs';
import './updates-card.test.mjs';
import './update-changelog.test.mjs';
import './collection-transport.test.mjs';
import './device-offline-ui.test.mjs';
import './navigation-contracts.test.mjs';
import './song-rating-wiring.test.mjs';
import './song-rating-view.test.mjs';
import './collection-artwork.test.mjs';
import './playlist-edit.test.mjs';
import './playlist-deletion.test.mjs';
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
  assert.equal(collectionCalls.length, 3);
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

test('collection detail virtualizes data rows in a bounded viewport without entrance animations', () => {
  const mainScrollEnd = shell.indexOf('</ScrollViewer>', shell.indexOf('x:Name="MainScroll"'));
  const detailHost = shell.indexOf('x:Name="DetailHost"');
  assert.ok(detailHost > mainScrollEnd, 'detail list must be outside the unbounded browse ScrollViewer');
  assert.match(shell.slice(detailHost), /RowDefinition Height="Auto"\/><RowDefinition Height="\*"/);
  assert.match(shell, /<ListView[^>]*Grid.Row="1"[^>]*x:Name="DetailList"/);
  assert.match(shell, /<DataTemplate x:Key="DetailRowTemplate">\s*<Grid\/>\s*<\/DataTemplate>/);
  const detail = method('RenderDetail');
  assert.doesNotMatch(detail, /AddSongs\(|SongRow\(|\.Items.Add\(/);
  assert.match(detail, /view.ItemsSource = detailSongs/);
  assert.match(detail, /Select\(\(song, index\) => new DetailSong\(song, index\) \{ Owner = connectionLease \}\)\.ToArray\(\)/);
  assert.doesNotMatch(detail, /\.Take\(|Distinct/);
  assert.match(detail, /view.ItemContainerTransitions = new .*TransitionCollection\(\)/);
  assert.match(source, /ContainerContentChanging \+=/);
  assert.match(source, /args.InRecycleQueue/);
  assert.match(source, /ContentTemplateRoot is not Grid/);
  assert.match(source, /SongRow\(item.Song, item.Index, "list"/);
  assert.match(source, /StartQueue\(detailSongs, index\)/);
  assert.match(method('InitializeDetailList'), /AutomationProperties.SetName\(args.ItemContainer,/);
});

test('collection opens its header before awaiting the server and uses static loading feedback', () => {
  const open = method('OpenCollectionAsync');
  assert.ok(open.indexOf('RenderDetail();') < open.indexOf('await target.GetAlbumSongsAsync'), 'header must render before collection retrieval');
  assert.match(open, /detailLoading = true/);
  assert.doesNotMatch(open, /BusyBar.*Visibility.Visible/);
  assert.match(open, /browseGate.IsCurrent\(lease\).*target != client.*closed/);
  assert.match(open, /detailLoading = false/);
  assert.match(open, /Could not open this collection/);
  const detail = method('RenderDetail');
  assert.match(detail, /Loading songs…/);
  assert.match(detail, /CreateCollectionTransport\(\)/);
  const transport = readFileSync(new URL('MainWindow.CollectionTransport.cs', app), 'utf8').replaceAll('\r\n', '\n');
  assert.match(transport, /collectionTransport.CanStart\(detailLoading, detailError/);
  assert.match(transport, /IsEnabled = available\(\)/);
  assert.match(method('NavigateAsync'), /ClearDetail\(\)/);
  assert.match(method('ActivateConnection'), /ClearDetail\(\)/);
});

test('collection detail keeps count above shared left transport without a Library back link', () => {
  const detail = method('RenderDetail');
  assert.doesNotMatch(detail, /←|NavigateAsync/);
  assert.match(detail, /C<TextBlock>\("CollectionSummary"\).Text = [^;]+;\s*C<Border>\("CollectionActions"\).Child = CreateCollectionTransport\(\)/);
  assert.match(shell, /x:Name="CollectionSummary"[\s\S]*?<Border Grid.Row="1" x:Name="CollectionActions"/);
});

test('track list shares responsive columns with its headings and stretches item containers', () => {
  const songs = method('AddSongs');
  assert.match(songs, /var header = SongGrid\(showRatings\)/);
  for (const label of ['#', 'Title / artist', 'Album', 'Time']) assert.ok(songs.includes(`"${label}"`));
  const row = method('SongRow');
  assert.match(songs, /view.Items.Add\(SongRow\(/);
  assert.match(row, /var grid = SongGrid\(showRatings,/);
  assert.match(songs, /HorizontalContentAlignmentProperty, HorizontalAlignment.Stretch/);
  assert.match(songs, /view.ItemContainerStyle = itemStyle/);
  assert.match(songs, /PaddingProperty, new Thickness\(0\)/);
  assert.match(row, /ToolTipService.SetToolTip\(album, song.Album\)/);
  assert.match(songs, /StartQueue\(playQueue, index\)/);
  const grid = method('SongGrid');
  assert.match(grid, /new GridLength\(showRatings \? 2 : 1, GridUnitType.Star\)/);
  assert.match(grid, /new GridLength\(0.8, GridUnitType.Star\)/);
  assert.match(grid, /ColumnSpacing = 12/);
  assert.match(grid, /MinHeight = 56/);
  assert.doesNotMatch(grid, /new GridLength\(160\)/);
});

test('rated track lists prioritize titles and reclaim inline star space at the reported narrow width', () => {
  const grid = method('SongGrid');
  const baseColumns = [...grid.matchAll(/new GridLength\(([\d.]+)\)/g)].map(match => Number(match[1]));
  const [number, time] = baseColumns;
  const compact = grid.match(/const double compactRatingWidth = (\d+);/);
  assert.ok(compact, 'a narrow rated list must not reserve the full five-star width');
  const compactWidth = Number(compact[1]);
  const fullRating = Number(grid.match(/const double inlineRatingWidth = (\d+);/)?.[1]);
  const starColumns = [...grid.matchAll(/new GridLength\((?:showRatings \? ([\d.]+) : )?([\d.]+), GridUnitType.Star\)/g)]
    .map(match => Number(match[1] ?? match[2]));
  const [title, album] = starColumns;
  const spacing = Number(grid.match(/ColumnSpacing = (\d+)/)?.[1]);
  const padding = Number(grid.match(/Padding = new Thickness\((\d+),/)?.[1]);
  const titleWidth = width => (width - number - time - compactWidth - 4 * spacing - 2 * padding) * title / (title + album) - 52;
  assert.ok(titleWidth(444) >= 135, 'at the reported scale the title needs room beyond the artwork');
  assert.ok(compactWidth >= 40 && compactWidth < fullRating);
  assert.match(grid, /SizeChanged \+=/);
  assert.match(grid, /args\.NewSize\.Width >= (\d+)/);
  const threshold = Number(grid.match(/args\.NewSize\.Width >= (\d+)/)[1]);
  assert.ok(444 < threshold && threshold <= 800);
  assert.match(grid, /ratingColumn\.Width = new GridLength\(inline \? inlineRatingWidth : compactRatingWidth\)/);
  assert.match(method('RenderDetail'), /var header = SongGrid\(showRatings\)/);
  assert.match(method('SongRow'), /showRatings \? inline => inline \? CreateInlineRating\(song, owner\) : CreateCompactRating\(song, owner\) : null/);
});

test('list songs include 40px artwork beside the title while headings retain text alignment', () => {
  const artworkFile = new URL('MainWindow.SongArtwork.cs', app);
  const artwork = existsSync(artworkFile) ? readFileSync(artworkFile, 'utf8').replaceAll('\r\n', '\n') : '';
  assert.match(method('SongRow'), /SongCell\(song, copy, token, owner\)/);
  assert.match(artwork, /new GridLength\(40\)/);
  assert.match(artwork, /CoverArtwork\(id, token, owner, 40\)/);
  assert.match(artwork, /Width = size, Height = size/);
  assert.match(artwork, /ColumnSpacing = spacing/);
  assert.match(artwork, /SongArtwork\(song.CoverArtId, token, owner\)/);
  assert.match(artwork, /Stretch = Stretch.UniformToFill/);
  assert.match(artwork, /CornerRadius = new CornerRadius\(size == 40 \? 4 : 6\)/);
  assert.match(artwork, /Glyph = "\\uE8D6"/);
  assert.match(artwork, /No artwork available/);
  assert.match(artwork, /cover.Loaded \+= async/);
  assert.match(artwork, /closed \|\| offlineSettings.Offline \|\| target is null/);
  assert.match(artwork, /connectionGate.IsCurrent\(owner\)/);
  assert.match(artwork, /CreateLinkedTokenSource\(token, owner.Token\)/);
  assert.match(artwork, /artwork.SetAsync\(image, id, target, load.Token\)/);
  assert.match(artwork, /cover.Unloaded \+= .*pending\?\.Cancel\(\)/);
  assert.match(artwork, /load.Dispose\(\)/);
  for (const name of ['AddSongs', 'RenderDetail']) {
    assert.match(method(name), /column == 1.*heading.Margin = new Thickness\(52, 0, 0, 0\)/);
  }
});

test('unrealized song covers release decoded images and restore their placeholder', () => {
  const artwork = readFileSync(new URL('MainWindow.SongArtwork.cs', app), 'utf8').replaceAll('\r\n', '\n');
  const unloaded = artwork.slice(artwork.indexOf('cover.Unloaded +='), artwork.indexOf('return cover;'));
  assert.match(unloaded, /pending\?\.Cancel\(\)/);
  assert.match(unloaded, /image.Source = null/);
  assert.match(unloaded, /fallback.Visibility = Visibility.Visible/);
});

test('both queue lists reserve a right gutter for the overlay scrollbar', () => {
  assert.match(source, /InitializeQueueList\(C<ListView>\("QueueList"\)\)/);
  assert.match(method('CreatePlayedExpander'), /InitializeQueueList\(playedList\)/);
  assert.match(method('InitializeQueueList'), /list\.Padding = new Thickness\(0, 0, 16, 0\)/);
});

test('queue lazily realizes occurrence-identified artwork rows and reuses unchanged queue state', () => {
  const row = method('CreateQueueSong');
  assert.match(row, /SongCell\(song, copy, CancellationToken.None, connectionLease, 10\)/);
  assert.match(row, /Tag = entry.Index/);
  assert.match(row, /MinHeight = 60/);
  assert.match(row, /Padding = new Thickness\(8, 10, 8, 10\)/);
  assert.match(row, /SongTitle\(song, 14, current: entry.Current\)/);
  assert.match(row, /AutomationProperties.SetName\(row,/);
  assert.doesNotMatch(row, /Text\(.*entry.Index|entry.Index \+ 1/); // No queue track numbers.
  const initialize = method('InitializeQueueList');
  assert.match(initialize, /HorizontalContentAlignmentProperty, HorizontalAlignment.Stretch/);
  assert.match(initialize, /PaddingProperty, new Thickness\(0\)/);
  assert.match(initialize, /host.Children.Clear\(\)/);
  assert.match(initialize, /args.InRecycleQueue/);
  assert.match(initialize, /SelectTrack\(entry.Index, true\)/);
  assert.match(initialize, /ReferenceEquals\(entry.Queue, queue\)/);
  const queue = method('RenderQueue');
  assert.match(queue, /ReferenceEquals\(renderedQueue, queue\)/);
  assert.match(queue, /renderedQueueState == state\) return/);
  assert.match(queue, /FindIndex\(x => x.Current\)/);
  assert.match(queue, /list.SelectedIndex = renderedSelection/);
  assert.doesNotMatch(queue, /SongCell\(|CreateQueueSong\(/);
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
  assert.match(shell, /x:Name="PlayerSeekRow"[^>]*>\s*<Grid\.ColumnDefinitions>\s*<ColumnDefinition Width="\*"\/><ColumnDefinition Width="1\.2\*" MinWidth="328"\/><ColumnDefinition Width="\*"\/>/);
  for (const [name, glyph] of [['RepeatButton', 'E8EE']]) {
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
  const header = shell.slice(shell.indexOf('<Grid x:Name="PageHeader"'), shell.indexOf('<InfoBar Grid.Row="1"'));
  assert.doesNotMatch(header, /ColumnDefinition Width="260"/);
  assert.match(header, /x:Name="SearchBox"[^>]*Width="260"/);
  assert.match(header, /x:Name="PageTitle"[^>]*TextTrimming="CharacterEllipsis"/);
  assert.match(method('RenderDetail'), /C<Grid>\("PageHeader"\).Visibility = isCollection \? Visibility.Collapsed : Visibility.Visible/);
  const collection = shell.slice(shell.indexOf('<Grid x:Name="CollectionHeader"'), shell.indexOf('<StackPanel x:Name="DetailHeader"'));
  assert.match(collection, /<ColumnDefinition Width="120"\/><ColumnDefinition Width="\*"\/>/);
  assert.match(collection, /x:Name="CollectionTitle"[^>]*TextTrimming="CharacterEllipsis"/);
  assert.doesNotMatch(collection, /SearchBox|LayoutButton/);
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