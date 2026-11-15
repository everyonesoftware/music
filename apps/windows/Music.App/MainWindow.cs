using System.Reflection;
using Microsoft.UI;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Markup;
using Microsoft.UI.Xaml.Media;
using Music.Core;
using Windows.Devices.Enumeration;
using Windows.Media;
using Windows.Media.Core;
using Windows.Media.Devices;
using Windows.Media.Playback;

namespace Music.App;

public sealed partial class MainWindow : Window
{
    private readonly Grid root;
    private readonly WindowsUpdatesPanel updatesPanel;
    private readonly RequestGate browseGate = new(), connectGate = new(), testGate = new(), artworkGate = new(), playingArtGate = new(), connectionGate = new();
    private RequestGate.Lease connectionLease;
    private readonly ArtworkLoader artwork = new();
    private readonly MediaPlayer player = new() { AutoPlay = false, Volume = .7, AudioCategory = MediaPlayerAudioCategory.Media };
    private readonly Microsoft.UI.Dispatching.DispatcherQueueTimer timer;
    private ProfileStore? profiles;
    private SessionStore? sessions;
    private ConnectionProfile? connection;
    private NavidromeClient? client;
    private IReadOnlyList<Album> albums = Array.Empty<Album>();
    private IReadOnlyList<Playlist> playlists = Array.Empty<Playlist>();
    private List<Song> songs = new(), queue = new();
    private string page = "library", mode = "grid", query = "";
    private int queueIndex = -1, songOffset, tickCount;
    private bool moreSongs, closed, updatingSeek, seekDragging, initialized, browsePending;
    private long browseResultsVersion;
    private long playbackVersion;
    private double restoredPosition;
    private string? sessionSaveWarning;
    private MediaSource? mediaSource;
    private Windows.Foundation.TypedEventHandler<MediaPlayer, object>? openedHandler;
    private T C<T>(string name) where T : class => (T)root.FindName(name);
    private static readonly SolidColorBrush Sage = new(ColorHelper.FromArgb(255, 192, 215, 174));
    private static readonly SolidColorBrush Muted = new(ColorHelper.FromArgb(255, 172, 184, 174));

    public MainWindow()
    {
        using var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream("Music.App.Shell.xaml")!;
        using var reader = new StreamReader(stream);
        root = (Grid)XamlReader.Load(reader.ReadToEnd());
        Content = root;
        Title = "Music";
        AppWindow.SetIcon(Path.Combine(AppContext.BaseDirectory, "Assets", "Music.ico"));
        C<Image>("AppIcon").Source = new Microsoft.UI.Xaml.Media.Imaging.BitmapImage(
            new Uri(Path.Combine(AppContext.BaseDirectory, "Assets", "Music.png")));
        updatesPanel = new WindowsUpdatesPanel(root, DispatcherQueue, () =>
            TrySavePlayback()
                ? Task.CompletedTask
                : Task.FromException(new IOException("Session could not be saved.")));
        ExtendsContentIntoTitleBar = true;
        SetTitleBar(C<Border>("TitleBar"));
        AppWindow.Resize(new Windows.Graphics.SizeInt32(1240, 850));
        AppWindow.TitleBar.ButtonBackgroundColor = ColorHelper.FromArgb(255, 36, 44, 40);
        AppWindow.TitleBar.ButtonForegroundColor = ColorHelper.FromArgb(255, 237, 240, 233);
        foreach (var entry in new[] { ("LibraryNav", "library"), ("PlaylistsNav", "playlists"), ("AlbumsNav", "albums"), ("SongsNav", "songs"), ("SettingsNav", "settings") })
        { string target = entry.Item2; C<Button>(entry.Item1).Click += async (_, _) => await NavigateAsync(target); }
        C<Button>("WelcomeConnect").Click += async (_, _) => await NavigateAsync("settings");
        C<Button>("ConnectButton").Click += async (_, _) => await ConnectAsync();
        C<Button>("CancelConnectButton").Click += (_, _) => { connectGate.Cancel(); SetConnecting(false); C<TextBlock>("ConnectionStatus").Text = "Connection cancelled. Previous connection kept."; };
        C<Button>("TestConnectionButton").Click += async (_, _) => await TestConnectionAsync();
        C<TextBox>("SearchBox").TextChanged += async (_, _) =>
        {
            if (page is "settings" or "detail" || client is null) return;
            query = C<TextBox>("SearchBox").Text.Trim();
            await LoadBrowseAsync(true);
        };
        var layoutMenu = (MenuFlyout)C<Button>("LayoutButton").Flyout;
        ((MenuFlyoutItem)layoutMenu.Items[0]).Click += (_, _) => SetMode("grid");
        ((MenuFlyoutItem)layoutMenu.Items[1]).Click += (_, _) => SetMode("list");
        C<Button>("PlayButton").Click += (_, _) => TogglePlayback();
        C<Button>("PreviousButton").Click += (_, _) => Previous();
        C<Button>("NextButton").Click += (_, _) => Step(1);
        C<ToggleButton>("ShuffleButton").Click += (_, _) => SavePlayback();
        C<ToggleButton>("RepeatButton").Click += (_, _) => SavePlayback();
        C<Slider>("VolumeSlider").ValueChanged += (_, e) => player.Volume = e.NewValue / 100;
        C<Slider>("SeekSlider").ValueChanged += (_, e) =>
        {
            if (!updatingSeek && queueIndex >= 0)
                try { restoredPosition = 0; player.PlaybackSession.Position = TimeSpan.FromSeconds(e.NewValue); SavePlayback(); } catch { ShowError("This stream cannot seek right now."); }
        };
        C<Slider>("SeekSlider").AddHandler(UIElement.PointerPressedEvent, new Microsoft.UI.Xaml.Input.PointerEventHandler((_, _) => seekDragging = true), true);
        C<Slider>("SeekSlider").AddHandler(UIElement.PointerReleasedEvent, new Microsoft.UI.Xaml.Input.PointerEventHandler((_, _) => seekDragging = false), true);
        C<Slider>("SeekSlider").PointerCaptureLost += (_, _) => seekDragging = false;
        C<Button>("OutputButton").Click += async (_, _) => await ChooseOutputAsync();
        C<Button>("QueueButton").Click += (_, _) => C<Border>("QueuePane").Visibility = C<Border>("QueuePane").Visibility == Visibility.Visible ? Visibility.Collapsed : Visibility.Visible;
        C<Button>("CloseQueueButton").Click += (_, _) => { C<Border>("QueuePane").Visibility = Visibility.Collapsed; C<Button>("QueueButton").Focus(FocusState.Programmatic); };
        C<ListView>("QueueList").ItemClick += (_, e) => { if (e.ClickedItem is FrameworkElement { Tag: int index }) SelectTrack(index, true); };
        player.PlaybackSession.PlaybackStateChanged += (_, _) => DispatcherQueue.TryEnqueue(UpdateTransport);
        player.MediaEnded += (_, _) =>
        {
            long endedVersion = Interlocked.Read(ref playbackVersion);
            DispatcherQueue.TryEnqueue(() => { if (!closed && endedVersion == playbackVersion) Step(1); });
        };
        player.MediaFailed += (_, _) => DispatcherQueue.TryEnqueue(() => { if (!closed) ShowError("Playback failed. Check your connection, stream format, and output device."); });
        // MediaPlayer's command manager handles play/pause and publishes item metadata.
        player.CommandManager.NextReceived += (_, e) => { e.Handled = true; DispatcherQueue.TryEnqueue(() => Step(1)); };
        player.CommandManager.PreviousReceived += (_, e) => { e.Handled = true; DispatcherQueue.TryEnqueue(Previous); };
        player.CommandManager.NextBehavior.EnablingRule = MediaCommandEnablingRule.Always;
        player.CommandManager.PreviousBehavior.EnablingRule = MediaCommandEnablingRule.Always;
        timer = DispatcherQueue.CreateTimer();
        timer.Interval = TimeSpan.FromSeconds(1);
        timer.Tick += (_, _) => Tick();
        root.Loaded += async (_, _) => { if (!initialized) { initialized = true; await InitializeAsync(); } };
        Closed += (_, _) => Shutdown();
        C<TextBlock>("VersionText").Text = $"Music for Windows · {Assembly.GetExecutingAssembly().GetName().Version?.ToString(3)}";
        SetNavigation();
        UpdateTransport();
    }

    private async Task InitializeAsync()
    {
#if MUSIC_NATIVE_ACCEPTANCE
        if (NativeAcceptance.IsRequested) return;
#endif
        var lease = connectGate.Begin();
        NavidromeClient? candidate = null;
        try
        {
            profiles = new ProfileStore();
            sessions = new SessionStore(Path.Combine(profiles.DirectoryPath, "session.sqlite3"));
            var profile = profiles.ReadProfile();
            if (profile is null) return;
            C<TextBlock>("ConnectionInfo").Text = $"Saved connection: {profile.Name}\n{profile.ServerUrl}\nUsername: {profile.Username}\nNot connected yet.";
            C<TextBlock>("ConnectionStatus").Text = "Connecting to saved server…";
            candidate = new NavidromeClient(profile, profiles.ReadPassword(profile));
            await candidate.PingAsync(lease.Token);
            var data = await ReadLibraryAsync(candidate, lease.Token);
            if (!connectGate.IsCurrent(lease) || closed) return;
            ActivateConnection(profile, candidate, data.Albums, data.Playlists);
            candidate = null;
            RestorePlayback();
            await NavigateAsync("library");
        }
        catch (OperationCanceledException) { }
        catch { if (connectGate.IsCurrent(lease) && !closed) { C<TextBlock>("ConnectionStatus").Text = "Saved connection could not be opened. Reconnect below; your saved connection has not been removed."; ShowError("Could not open the saved connection or local session store. Check the server and Windows credentials."); } }
        finally { candidate?.Dispose(); }
    }

    private static async Task<(IReadOnlyList<Album> Albums, IReadOnlyList<Playlist> Playlists)> ReadLibraryAsync(NavidromeClient target, CancellationToken token)
    {
        var list = new List<Album>();
        var seen = new HashSet<string>();
        for (int offset = 0; ; offset += 100)
        {
            var batch = await target.GetAlbumsAsync(offset, 100, token);
            token.ThrowIfCancellationRequested();
            int added = 0;
            foreach (var album in batch) if (seen.Add(album.Id)) { list.Add(album); added++; }
            if (batch.Count < 100 || added == 0) break;
        }
        return (list, await target.GetPlaylistsAsync(token));
    }

    private async Task ConnectAsync()
    {
        var lease = connectGate.Begin();
        NavidromeClient? candidate = null;
        SetConnecting(true);
        try
        {
            if (profiles is null || sessions is null) throw new InvalidOperationException();
            string server = C<TextBox>("ServerAddress").Text.Trim();
            string username = C<TextBox>("Username").Text.Trim();
            string password = C<PasswordBox>("Password").Password;
            if (!Uri.TryCreate(server, UriKind.Absolute, out var uri) || uri.Scheme != "https" || !string.IsNullOrEmpty(uri.UserInfo) || !string.IsNullOrEmpty(uri.Query) || !string.IsNullOrEmpty(uri.Fragment) || string.IsNullOrWhiteSpace(uri.Host) || string.IsNullOrWhiteSpace(username) || password.Length == 0)
            { C<TextBlock>("ConnectionStatus").Text = "Enter an HTTPS server address without credentials, query, or fragment, plus username and password."; return; }
            string name = C<TextBox>("ConnectionName").Text.Trim();
            var profile = new ConnectionProfile(Guid.NewGuid().ToString("N"), string.IsNullOrEmpty(name) ? uri.Host : name, server, username);
            candidate = new NavidromeClient(profile, password);
            C<TextBlock>("ConnectionStatus").Text = "Testing connection and loading your library…";
            await candidate.PingAsync(lease.Token);
            var data = await ReadLibraryAsync(candidate, lease.Token);
            if (!connectGate.IsCurrent(lease) || closed) return;
            SavePlayback();
            var previous = connection ?? profiles.ReadProfile();
            if (previous is not null && ConnectionIdentity.SameAccount(previous.ServerUrl, previous.Username, profile.ServerUrl, profile.Username))
            {
                var snapshot = sessions.LoadPlayback(previous.Id);
                if (snapshot is not null)
                    sessions.SavePlayback(snapshot with { ConnectionId = profile.Id });
            }
            // Stage the migrated session before replacing the profile or vault entry.
            // A failure leaves the previous profile, password and session untouched.
            profiles.Commit(profile, password);
            ActivateConnection(profile, candidate, data.Albums, data.Playlists);
            candidate = null;
            RestorePlayback();
            C<PasswordBox>("Password").Password = "";
            await NavigateAsync("library");
        }
        catch (OperationCanceledException) { }
        catch { if (connectGate.IsCurrent(lease) && !closed) C<TextBlock>("ConnectionStatus").Text = "Could not connect or securely save credentials. Check server, username, password, and Windows Credential Locker. Previous connection kept."; }
        finally { candidate?.Dispose(); if (connectGate.IsCurrent(lease) && !closed) SetConnecting(false); }
    }

    private void SetConnecting(bool value)
    {
        C<Button>("ConnectButton").IsEnabled = !value;
        C<Button>("CancelConnectButton").Visibility = value ? Visibility.Visible : Visibility.Collapsed;
    }

    private void ActivateConnection(ConnectionProfile profile, NavidromeClient target, IReadOnlyList<Album> newAlbums, IReadOnlyList<Playlist> newPlaylists)
    {
        browseGate.Cancel(); testGate.Cancel(); artworkGate.Cancel(); playingArtGate.Cancel();
        connectionLease = connectionGate.Begin();
        C<StackPanel>("BrowsePanel").Children.Clear();
        detailSongs.Clear(); detailTitle = "";
        StopPlayback();
        client?.Dispose();
        client = target; connection = profile; albums = newAlbums; playlists = newPlaylists;
        songs.Clear(); artwork.Clear();
        C<TextBlock>("ConnectionStatus").Text = "Connected. Password protected by Windows Credential Locker.";
        C<TextBlock>("ConnectionInfo").Text = $"{profile.Name}\n{profile.ServerUrl}\nUsername: {profile.Username}\nNavidrome · Connected";
        C<Button>("TestConnectionButton").IsEnabled = true;
        timer.Start();
    }

    private async Task TestConnectionAsync()
    {
        if (client is null) return;
        var lease = testGate.Begin();
        var target = client;
        C<Button>("TestConnectionButton").IsEnabled = false;
        try { await target.PingAsync(lease.Token); if (testGate.IsCurrent(lease) && target == client && !closed) C<TextBlock>("ConnectionStatus").Text = "Connection verified just now."; }
        catch (OperationCanceledException) { }
        catch { if (testGate.IsCurrent(lease) && !closed) C<TextBlock>("ConnectionStatus").Text = "Connection test failed. Your saved connection is unchanged."; }
        finally { if (testGate.IsCurrent(lease) && !closed) C<Button>("TestConnectionButton").IsEnabled = true; }
    }

    private async Task NavigateAsync(string target)
    {
        browseGate.Cancel(); artworkGate.Cancel();
        page = target; query = "";
        C<TextBox>("SearchBox").Text = "";
        C<TextBlock>("PageTitle").Text = char.ToUpperInvariant(target[0]) + target[1..];
        C<TextBox>("SearchBox").Visibility = target == "settings" ? Visibility.Collapsed : Visibility.Visible;
        C<Button>("LayoutButton").Visibility = target is "library" or "settings" ? Visibility.Collapsed : Visibility.Visible;
        C<StackPanel>("SettingsPanel").Visibility = target == "settings" ? Visibility.Visible : Visibility.Collapsed;
        C<StackPanel>("WelcomePanel").Visibility = target != "settings" && client is null ? Visibility.Visible : Visibility.Collapsed;
        C<StackPanel>("BrowsePanel").Visibility = target != "settings" && client is not null ? Visibility.Visible : Visibility.Collapsed;
        C<ProgressBar>("BusyBar").Visibility = Visibility.Collapsed;
        C<TextBox>("SearchBox").PlaceholderText = target == "library" ? "Search your library" : "Search " + target;
        C<ScrollViewer>("MainScroll").ChangeView(null, 0, null);
        if (target == "settings") C<Border>("QueuePane").Visibility = Visibility.Collapsed;
        mode = ReadMode(target);
        C<SymbolIcon>("LayoutIcon").Symbol = mode == "grid" ? Symbol.ViewAll : Symbol.List;
        SetNavigation();
        if (target != "settings" && client is not null) await LoadBrowseAsync();
    }

    private void SetNavigation()
    {
        foreach (var entry in new[] { ("LibraryNav", "library"), ("PlaylistsNav", "playlists"), ("AlbumsNav", "albums"), ("SongsNav", "songs"), ("SettingsNav", "settings") })
        {
            var button = C<Button>(entry.Item1);
            button.Background = entry.Item2 == page ? new SolidColorBrush(ColorHelper.FromArgb(255, 53, 66, 57)) : new SolidColorBrush(Colors.Transparent);
            AutomationProperties.SetHelpText(button, entry.Item2 == page ? "Current page" : "");
        }
    }

    private string ReadMode(string collection)
    {
        try { var value = sessions?.GetView(collection, BrowsePolicy.DefaultMode(collection)); return value is "grid" or "list" ? value : BrowsePolicy.DefaultMode(collection); }
        catch { return BrowsePolicy.DefaultMode(collection); }
    }
    private void SetMode(string value)
    {
        mode = value;
        try { sessions?.SaveView(page, value); } catch { ShowError("Could not save this view preference."); }
        C<SymbolIcon>("LayoutIcon").Symbol = mode == "grid" ? Symbol.ViewAll : Symbol.List;
        if (page != "detail") RenderBrowse();
        else RenderDetail();
    }

    private async Task LoadBrowseAsync(bool debounce = false)
    {
        if (client is null) return;
        browsePending = true;
        browseResultsVersion++;
        moreSongs = false;
        C<StackPanel>("BrowsePanel").Children.Clear();
        var lease = browseGate.Begin();
        var target = client;
        string requestedPage = page, requestedQuery = query;
        C<ProgressBar>("BusyBar").Visibility = Visibility.Visible;
        artworkGate.Cancel();
        try
        {
            if (debounce) await Task.Delay(280, lease.Token);
            IReadOnlyList<Song> result = Array.Empty<Song>();
            if (requestedPage is "library" or "songs") result = await target.SearchSongsAsync(requestedQuery, 0, 100, lease.Token);
            if (!browseGate.IsCurrent(lease) || closed || target != client) return;
            songs = result.ToList(); songOffset = result.Count; moreSongs = result.Count == 100;
            RenderBrowse();
        }
        catch (OperationCanceledException) { }
        catch { if (browseGate.IsCurrent(lease) && !closed) { songs.Clear(); moreSongs = false; RenderBrowse(); ShowError("Could not load songs. Check the connection and try searching again."); } }
        finally
        {
            if (browseGate.IsCurrent(lease) && !closed)
            {
                browsePending = false;
                C<ProgressBar>("BusyBar").Visibility = Visibility.Collapsed;
            }
        }
    }

    private void RenderBrowse()
    {
        if (client is null || page is "settings" or "detail") return;
        var artLease = artworkGate.Begin();
        var panel = C<StackPanel>("BrowsePanel"); panel.Children.Clear(); panel.Spacing = 24;
        bool Match(string text) => text.Contains(query, StringComparison.OrdinalIgnoreCase);
        var foundAlbums = albums.Where(a => Match(a.Name + " " + a.Artist)).ToList();
        var foundPlaylists = playlists.Where(p => Match(p.Name)).ToList();
        bool library = page == "library";
        if (BrowsePolicy.ShowSection(page, "albums", foundAlbums.Count))
        {
            var section = NewSection("Albums", "albums");
            AddCollection(section, foundAlbums.Select(a => new Card(a.Name, $"{a.Artist} · {a.Year}", a.CoverArtId, a)).ToList(), library ? "grid" : mode, artLease.Token, library ? 8 : int.MaxValue);
            panel.Children.Add(section);
        }
        if (BrowsePolicy.ShowSection(page, "playlists", foundPlaylists.Count))
        {
            var section = NewSection("Playlists", "playlists");
            AddCollection(section, foundPlaylists.Select(p => new Card(p.Name, $"{p.SongCount} songs", p.CoverArtId, p)).ToList(), library ? "grid" : mode, artLease.Token, library ? 8 : int.MaxValue);
            panel.Children.Add(section);
        }
        if (BrowsePolicy.ShowSection(page, "songs", songs.Count))
        {
            var section = NewSection("Songs", "songs");
            AddSongs(section, library ? songs.Take(8).ToList() : songs, library ? "list" : mode, artLease.Token);
            if (!library && moreSongs) AddLoadMore(section);
            panel.Children.Add(section);
        }
        if (panel.Children.Count == 0) panel.Children.Add(Text(string.IsNullOrEmpty(query) ? "No music found in this collection." : "No matching albums, playlists, or songs.", 16, true));
    }

    private StackPanel NewSection(string title, string target)
    {
        var panel = new StackPanel { Spacing = 12 };
        if (page == "library")
        {
            var header = new Grid();
            header.Children.Add(Text(title, 21));
            var all = new HyperlinkButton { Content = "View all " + title.ToLowerInvariant() + "  ›", HorizontalAlignment = HorizontalAlignment.Right, Foreground = Sage };
            all.Click += async (_, _) => await NavigateAsync(target);
            header.Children.Add(all); panel.Children.Add(header);
        }
        return panel;
    }
    private sealed record Card(string Title, string Subtitle, string? ArtId, object Value);
    private void AddCollection(StackPanel section, IReadOnlyList<Card> cards, string layout, CancellationToken token, int limit)
    {
        var owner = connectionLease;
        ListViewBase view = layout == "grid" ? new GridView() : new ListView();
        view.SelectionMode = ListViewSelectionMode.None; view.IsItemClickEnabled = true;
        view.HorizontalAlignment = HorizontalAlignment.Stretch;
        ScrollViewer.SetVerticalScrollMode(view, ScrollMode.Disabled);
        ScrollViewer.SetVerticalScrollBarVisibility(view, ScrollBarVisibility.Disabled);
        foreach (var card in cards.Take(limit)) view.Items.Add(CardElement(card, layout, token));
        view.ItemClick += async (_, e) => { if (!closed && connectionGate.IsCurrent(owner) && e.ClickedItem is FrameworkElement item) await OpenCollectionAsync(item.Tag); };
        section.Children.Add(view);
    }
    private FrameworkElement CardElement(Card card, string layout, CancellationToken token)
    {
        var outer = new Grid { Tag = card.Value, Margin = new Thickness(0, 0, 12, 12), Padding = new Thickness(4), MinHeight = 60 };
        var art = new Image { Stretch = Stretch.UniformToFill, Width = layout == "grid" ? 156 : 48, Height = layout == "grid" ? 156 : 48 };
        var cover = new Border { Background = new SolidColorBrush(ColorHelper.FromArgb(255, 53, 66, 57)), CornerRadius = new CornerRadius(5), Child = art };
        var copy = new StackPanel { Spacing = 4 };
        copy.Children.Add(Text(card.Title, 14)); copy.Children.Add(Text(card.Subtitle, 12, true));
        if (layout == "grid")
        {
            outer.Width = 164;
            outer.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); outer.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            Grid.SetRow(copy, 1); copy.Margin = new Thickness(0, 10, 0, 0);
        }
        else
        {
            outer.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(60) }); outer.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            Grid.SetColumn(copy, 1); copy.VerticalAlignment = VerticalAlignment.Center;
        }
        outer.Children.Add(cover); outer.Children.Add(copy);
        AutomationProperties.SetName(outer, card.Title + ", " + card.Subtitle);
        _ = artwork.SetAsync(art, card.ArtId, client!, token);
        return outer;
    }

    private void AddSongs(StackPanel section, IReadOnlyList<Song> tracks, string layout, CancellationToken token)
    {
        var owner = connectionLease;
        ListViewBase view = layout == "grid" ? new GridView() : new ListView();
        view.SelectionMode = ListViewSelectionMode.None; view.IsItemClickEnabled = true;
        view.HorizontalAlignment = HorizontalAlignment.Stretch;
        view.Padding = new Thickness(0);
        if (layout == "list")
        {
            // Stretch the container itself so star columns receive the viewport.
            var itemStyle = new Style(typeof(ListViewItem));
            itemStyle.Setters.Add(new Setter(Control.HorizontalContentAlignmentProperty, HorizontalAlignment.Stretch));
            itemStyle.Setters.Add(new Setter(Control.PaddingProperty, new Thickness(0)));
            itemStyle.Setters.Add(new Setter(FrameworkElement.MinHeightProperty, 0d));
            view.ItemContainerStyle = itemStyle;
            var header = SongGrid(); header.MinHeight = 32;
            string[] headings = { "#", "Title / artist", "Album", "Time" };
            for (int column = 0; column < headings.Length; column++)
            {
                var heading = Text(headings[column], 12, true);
                if (column == 3) heading.HorizontalAlignment = HorizontalAlignment.Right;
                Grid.SetColumn(heading, column); header.Children.Add(heading);
            }
            section.Children.Add(header);
        }
        ScrollViewer.SetVerticalScrollMode(view, ScrollMode.Disabled);
        ScrollViewer.SetVerticalScrollBarVisibility(view, ScrollBarVisibility.Disabled);
        var playQueue = tracks.ToList();
        for (int index = 0; index < tracks.Count; index++)
        {
            var song = tracks[index];
            FrameworkElement row;
            if (layout == "grid") row = CardElement(new Card(song.Title, song.Artist, song.CoverArtId, index), layout, token);
            else
            {
                var grid = SongGrid(); grid.Tag = index;
                grid.Children.Add(Text((index + 1).ToString(), 12, true));
                var copy = new StackPanel { Spacing = 3, VerticalAlignment = VerticalAlignment.Center };
                copy.Children.Add(Text(song.Title, 14)); copy.Children.Add(Text(song.Artist, 12, true));
                ToolTipService.SetToolTip(copy, song.Title + "\n" + song.Artist);
                Grid.SetColumn(copy, 1); grid.Children.Add(copy);
                var album = Text(song.Album, 12, true); ToolTipService.SetToolTip(album, song.Album); Grid.SetColumn(album, 2); grid.Children.Add(album);
                var duration = Text(Clock(song.DurationSeconds), 12, true); duration.HorizontalAlignment = HorizontalAlignment.Right; Grid.SetColumn(duration, 3); grid.Children.Add(duration);
                AutomationProperties.SetName(grid, song.Title + ", " + song.Artist + ", " + Clock(song.DurationSeconds)); row = grid;
            }
            view.Items.Add(row);
        }
        view.ItemClick += (_, e) => { if (!closed && connectionGate.IsCurrent(owner) && e.ClickedItem is FrameworkElement { Tag: int index }) StartQueue(playQueue, index); };
        section.Children.Add(view);
    }

    private static Grid SongGrid()
    {
        var grid = new Grid
        {
            MinHeight = 56, Padding = new Thickness(6, 8, 6, 8), ColumnSpacing = 12,
            HorizontalAlignment = HorizontalAlignment.Stretch,
            BorderBrush = new SolidColorBrush(ColorHelper.FromArgb(255, 57, 69, 61)), BorderThickness = new Thickness(0, 0, 0, 1)
        };
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(28) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(0.8, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(44) });
        return grid;
    }

    private void AddLoadMore(StackPanel section)
    {
        string renderedQuery = query;
        int renderedOffset = songOffset;
        long renderedVersion = browseResultsVersion;
        var owner = connectionLease;
        var button = new Button { Content = "Load more songs" };
        button.Click += async (_, _) =>
        {
            if (client is null || closed || browsePending || renderedVersion != browseResultsVersion || renderedQuery != query || renderedOffset != songOffset || !connectionGate.IsCurrent(owner)) return;
            var lease = browseGate.Begin(); var target = client; button.IsEnabled = false;
            try
            {
                var batch = await target.SearchSongsAsync(renderedQuery, renderedOffset, 100, lease.Token);
                if (!browseGate.IsCurrent(lease) || target != client || closed) return;
                songOffset += batch.Count;
                var ids = songs.Select(s => s.Id).ToHashSet(); int added = 0;
                foreach (var song in batch) if (ids.Add(song.Id)) { songs.Add(song); added++; }
                moreSongs = batch.Count == 100 && added > 0; RenderBrowse();
            }
            catch (OperationCanceledException) { }
            catch { if (browseGate.IsCurrent(lease) && !closed) { ShowError("Could not load the next page of songs."); button.IsEnabled = true; } }
        };
        section.Children.Add(button);
    }

    private string detailTitle = "";
    private List<Song> detailSongs = new();
    private async Task OpenCollectionAsync(object? value)
    {
        if (client is null) return;
        var lease = browseGate.Begin(); var target = client;
        C<ProgressBar>("BusyBar").Visibility = Visibility.Visible;
        try
        {
            IReadOnlyList<Song> tracks;
            string title;
            if (value is Album album) { title = album.Name; tracks = await target.GetAlbumSongsAsync(album.Id, lease.Token); }
            else if (value is Playlist playlist) { title = playlist.Name; tracks = await target.GetPlaylistSongsAsync(playlist.Id, lease.Token); }
            else return;
            if (!browseGate.IsCurrent(lease) || target != client || closed) return;
            page = "detail"; detailTitle = title; detailSongs = tracks.ToList();
            C<TextBlock>("PageTitle").Text = title;
            C<TextBox>("SearchBox").Visibility = Visibility.Collapsed;
            C<Button>("LayoutButton").Visibility = Visibility.Collapsed;
            RenderDetail();
        }
        catch (OperationCanceledException) { }
        catch { if (browseGate.IsCurrent(lease) && !closed) ShowError("Could not open this collection. Check the connection and try again."); }
        finally { if (browseGate.IsCurrent(lease) && !closed) C<ProgressBar>("BusyBar").Visibility = Visibility.Collapsed; }
    }
    private void RenderDetail()
    {
        var lease = artworkGate.Begin();
        var panel = C<StackPanel>("BrowsePanel"); panel.Children.Clear(); panel.Spacing = 12;
        var actions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 16, Margin = new Thickness(0, 0, 0, 8) };
        var back = new Button { Content = "←  Library", Background = new SolidColorBrush(Colors.Transparent), BorderThickness = new Thickness(0), Padding = new Thickness(0, 8, 12, 8) };
        back.Click += async (_, _) => await NavigateAsync("library"); actions.Children.Add(back);
        var play = new Button { Content = "Play", IsEnabled = detailSongs.Count > 0, Background = Sage, Foreground = new SolidColorBrush(ColorHelper.FromArgb(255, 36, 52, 31)), Padding = new Thickness(20, 8, 20, 8) };
        AutomationProperties.SetName(play, "Play " + detailTitle);
        play.Click += (_, _) => StartQueue(detailSongs, 0); actions.Children.Add(play);
        actions.Children.Add(Text($"{detailSongs.Count} songs", 13, true));
        panel.Children.Add(actions);
        AddSongs(panel, detailSongs, "list", lease.Token);
        C<ScrollViewer>("MainScroll").ChangeView(null, 0, null);
    }

    private static TextBlock Text(string text, double size, bool muted = false) => new() { Text = text, FontSize = size, Foreground = muted ? Muted : new SolidColorBrush(ColorHelper.FromArgb(255, 237, 240, 233)), TextTrimming = TextTrimming.CharacterEllipsis, VerticalAlignment = VerticalAlignment.Center, FontFamily = new FontFamily("Segoe UI") };
    private static string Clock(double seconds) => TimeSpan.FromSeconds(Math.Max(0, double.IsFinite(seconds) ? seconds : 0)).ToString(@"m\:ss");
    private void ShowError(string message)
    {
        if (closed) return;
        var notice = C<InfoBar>("Notice"); notice.Message = message; notice.IsOpen = true;
    }

    private void StartQueue(IReadOnlyList<Song> tracks, int index)
    {
        if (tracks.Count == 0 || client is null) return;
        queue = tracks.ToList(); SelectTrack(index, true);
    }
    private void SelectTrack(int index, bool play, double position = 0)
    {
        if (closed || client is null || index < 0 || index >= queue.Count) return;
        long version = ++playbackVersion;
        try
        {
            player.Pause();
            if (openedHandler is not null) player.MediaOpened -= openedHandler;
            player.Source = null; mediaSource?.Dispose(); mediaSource = null;
            queueIndex = index; restoredPosition = Math.Max(0, position);
            Song song = queue[index];
#if MUSIC_NATIVE_ACCEPTANCE
            mediaSource = MediaSource.CreateFromUri(NativeAcceptance.IsRequested ? NativeAcceptance.MediaUri : client.GetStreamUri(song.Id));
#else
            mediaSource = MediaSource.CreateFromUri(client.GetStreamUri(song.Id));
#endif
            var item = new MediaPlaybackItem(mediaSource);
            var metadata = item.GetDisplayProperties(); metadata.Type = MediaPlaybackType.Music;
            metadata.MusicProperties.Title = song.Title; metadata.MusicProperties.Artist = song.Artist; metadata.MusicProperties.AlbumTitle = song.Album;
            item.ApplyDisplayProperties(metadata);
            openedHandler = (_, _) => DispatcherQueue.TryEnqueue(() =>
            {
                if (closed || version != playbackVersion) return;
                try
                {
                    var duration = player.PlaybackSession.NaturalDuration.TotalSeconds;
                    if (restoredPosition > 0 && player.PlaybackSession.CanSeek)
                        player.PlaybackSession.Position = TimeSpan.FromSeconds(Math.Min(restoredPosition, duration > 0 ? duration : restoredPosition));
                    restoredPosition = 0;
                    // AutoPlay is false. Do not pause here: an explicit Play
                    // click while a restored source opens must still win.
                }
                catch { restoredPosition = 0; ShowError("The saved playback position could not be restored."); }
            });
            player.MediaOpened += openedHandler;
            player.Source = item;
            if (play) player.Play();
            C<TextBlock>("TrackTitle").Text = song.Title; C<TextBlock>("TrackArtist").Text = song.Artist;
            C<Image>("CurrentArt").Source = null;
            var lease = playingArtGate.Begin(); _ = artwork.SetAsync(C<Image>("CurrentArt"), song.CoverArtId, client, lease.Token);
            RenderQueue(); Tick(); SavePlayback();
        }
        catch { ShowError("Could not open this stream. Check server connectivity and the song format."); }
    }
    private void TogglePlayback()
    {
        if (queueIndex < 0) return;
        try { if (player.PlaybackSession.PlaybackState == MediaPlaybackState.Playing) { player.Pause(); SavePlayback(); } else player.Play(); }
        catch { ShowError("Playback is unavailable right now."); }
    }
    private void Previous()
    {
        if (closed || queueIndex < 0) return;
        try { if (player.PlaybackSession.Position.TotalSeconds > 3 && player.PlaybackSession.CanSeek) { player.PlaybackSession.Position = TimeSpan.Zero; SavePlayback(); return; } } catch { }
        Step(-1);
    }
    private void Step(int direction)
    {
        if (closed || queue.Count == 0 || queueIndex < 0) return;
        int next = queueIndex + direction;
        bool repeat = C<ToggleButton>("RepeatButton").IsChecked == true;
        if (C<ToggleButton>("ShuffleButton").IsChecked == true && queue.Count > 1 && direction > 0)
        { next = Random.Shared.Next(queue.Count - 1); if (next >= queueIndex) next++; }
        if (next >= queue.Count)
        {
            if (!repeat) { player.Pause(); SavePlayback(); UpdateTransport(); return; }
            next = 0;
        }
        if (next < 0) next = repeat ? queue.Count - 1 : 0;
        SelectTrack(next, true);
    }
    private void RenderQueue()
    {
        var list = C<ListView>("QueueList"); list.Items.Clear();
        for (int i = 0; i < queue.Count; i++)
        {
            var song = queue[i];
            var row = new StackPanel { Tag = i, Spacing = 4, Margin = new Thickness(0, 6, 0, 6) };
            var title = Text(song.Title, 14); if (i == queueIndex) title.Foreground = Sage;
            row.Children.Add(title); row.Children.Add(Text(song.Artist, 12, true)); list.Items.Add(row);
        }
        list.SelectedIndex = queueIndex;
    }
    private void UpdateTransport()
    {
        if (closed) return;
        bool playing = player.PlaybackSession.PlaybackState == MediaPlaybackState.Playing;
        C<Button>("PlayButton").Content = playing ? "\uE769" : "\uE768";
        AutomationProperties.SetName(C<Button>("PlayButton"), playing ? "Pause" : "Play");
        bool enabled = queueIndex >= 0;
        foreach (var name in new[] { "PlayButton", "PreviousButton", "NextButton" }) C<Button>(name).IsEnabled = enabled;
    }
    private void Tick()
    {
        if (closed) return;
        try
        {
            UpdateTransport();
            var session = player.PlaybackSession;
            double duration = session.NaturalDuration.TotalSeconds;
            if (duration <= 0 && queueIndex >= 0) duration = queue[queueIndex].DurationSeconds;
            double position = restoredPosition > 0 ? restoredPosition : session.Position.TotalSeconds;
            C<TextBlock>("ElapsedText").Text = Clock(position); C<TextBlock>("DurationText").Text = Clock(duration);
            updatingSeek = true;
            try
            {
                var slider = C<Slider>("SeekSlider"); slider.Maximum = Math.Max(1, duration);
                slider.IsEnabled = queueIndex >= 0 && session.CanSeek;
                if (!seekDragging) slider.Value = Math.Clamp(position, 0, slider.Maximum);
            }
            finally { updatingSeek = false; }
            if (++tickCount % 5 == 0) SavePlayback();
        }
        catch { /* Media sessions can transition while a timer tick is queued. */ }
    }
    private void SavePlayback()
        => TrySavePlayback();

    private bool TrySavePlayback()
    {
        if (connection is null || queueIndex < 0) return true;
        if (sessions is null) return false;
        try
        {
            sessions.SavePlayback(new PlaybackSnapshot(connection.Id, queue.ToArray(), queueIndex, restoredPosition > 0 ? restoredPosition : player.PlaybackSession.Position.TotalSeconds, C<ToggleButton>("ShuffleButton").IsChecked == true, C<ToggleButton>("RepeatButton").IsChecked == true));
            if (sessionSaveWarning is not null)
            {
                var notice = C<InfoBar>("Notice");
                if (notice.Message == sessionSaveWarning) notice.IsOpen = false;
                sessionSaveWarning = null;
            }
            return true;
        }
        catch (Exception error)
        {
            string message = SessionSaveFailure.Message(error);
            // A dismissed warning stays dismissed while periodic retries continue.
            if (sessionSaveWarning != message && !closed) ShowError(message);
            sessionSaveWarning = message;
            return false;
        }
    }
    private void RestorePlayback()
    {
        if (connection is null || sessions is null) return;
        try
        {
            var saved = sessions.LoadPlayback(connection.Id);
            if (saved is null || saved.Queue.Count == 0 || saved.Index < 0 || saved.Index >= saved.Queue.Count) return;
            queue = saved.Queue.ToList();
            C<ToggleButton>("ShuffleButton").IsChecked = saved.Shuffle; C<ToggleButton>("RepeatButton").IsChecked = saved.Repeat;
            SelectTrack(saved.Index, false, double.IsFinite(saved.PositionSeconds) ? saved.PositionSeconds : 0);
        }
        catch { ShowError("The saved queue could not be restored. Your server connection is still available."); }
    }
    private void StopPlayback()
    {
        playbackVersion++;
        if (openedHandler is not null) { player.MediaOpened -= openedHandler; openedHandler = null; }
        player.Pause(); player.Source = null; mediaSource?.Dispose(); mediaSource = null;
        queue.Clear(); queueIndex = -1; restoredPosition = 0;
        C<TextBlock>("TrackTitle").Text = "Nothing playing"; C<TextBlock>("TrackArtist").Text = ""; C<Image>("CurrentArt").Source = null;
        RenderQueue(); UpdateTransport();
    }

    private async Task ChooseOutputAsync()
    {
        var button = C<Button>("OutputButton"); button.IsEnabled = false;
        try
        {
            var devices = await DeviceInformation.FindAllAsync(MediaDevice.GetAudioRenderSelector());
            if (closed) return;
            var choices = new ComboBox { Header = "Audio output", HorizontalAlignment = HorizontalAlignment.Stretch };
            choices.Items.Add(new ComboBoxItem { Content = "System default output", Tag = null });
            foreach (var device in devices.Where(d => d.IsEnabled)) choices.Items.Add(new ComboBoxItem { Content = device.Name, Tag = device });
            var selectedId = player.AudioDevice?.Id;
            choices.SelectedIndex = 0;
            for (int i = 1; i < choices.Items.Count; i++) if (((ComboBoxItem)choices.Items[i]).Tag is DeviceInformation device && device.Id == selectedId) choices.SelectedIndex = i;
            var content = new StackPanel { Spacing = 12 }; content.Children.Add(choices);
            if (devices.Count == 0) content.Children.Add(new TextBlock { Text = "Windows returned no audio output devices. Connect an output and try again.", TextWrapping = TextWrapping.Wrap });
            var dialog = new ContentDialog { Title = "Media output", Content = content, PrimaryButtonText = "Apply", CloseButtonText = "Cancel", XamlRoot = root.XamlRoot, RequestedTheme = ElementTheme.Dark };
            if (await dialog.ShowAsync() != ContentDialogResult.Primary || closed) return;
            var selected = (ComboBoxItem)choices.SelectedItem;
            var target = selected.Tag as DeviceInformation;
            // Re-query before assigning: a device may disappear while the dialog is open.
            if (target is not null)
            {
                var currentDevices = await DeviceInformation.FindAllAsync(MediaDevice.GetAudioRenderSelector());
                if (closed) return;
                target = currentDevices.FirstOrDefault(d => d.Id == target.Id && d.IsEnabled);
                if (target is null) { ShowError("That output is no longer available. The current output was kept."); return; }
            }
            player.AudioDevice = target;
            if (player.AudioDevice?.Id != target?.Id) { ShowError("Windows did not confirm the selected output."); return; }
            C<TextBlock>("OutputName").Text = target?.Name ?? "System default output";
        }
        catch { if (!closed) ShowError("Audio output selection is unavailable. Windows system output remains in control; no switch was reported."); }
        finally { if (!closed) button.IsEnabled = true; }
    }

    private void Shutdown()
    {
        if (closed) return;
        updatesPanel.Dispose();
        SavePlayback(); closed = true;
        timer.Stop(); browseGate.Dispose(); connectGate.Dispose(); testGate.Dispose(); artworkGate.Dispose(); playingArtGate.Dispose(); connectionGate.Dispose();
        if (openedHandler is not null) player.MediaOpened -= openedHandler;
        player.Pause(); player.Source = null; mediaSource?.Dispose(); player.Dispose(); client?.Dispose(); sessions?.Dispose();
    }
}
