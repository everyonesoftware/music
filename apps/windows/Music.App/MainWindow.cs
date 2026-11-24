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
    private readonly TaskbarPlayback? taskbarPlayback;
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
    private IReadOnlyList<Artist> artists = Array.Empty<Artist>();
    private bool artistsLoaded;
    private List<Song> songs = new(), queue = new();
    private List<Song>? renderedQueue;
    private int renderedQueueCount = -1, renderedSelection = -1;
    private string renderedQueueState = "";
    private bool playedExpanded;
    private Windows.Foundation.TypedEventHandler<MediaPlaybackSession, object>? startedHandler;
    private string page = "library", mode = "grid", query = "";
    private int queueIndex = -1, songOffset, tickCount;
    private bool moreSongs, closed, updatingSeek, seekDragging, initialized, browsePending;
    private bool accountChangePending;
    private long browseResultsVersion;
    private long playbackVersion;
    private double restoredPosition;
    private string? sessionSaveWarning;
    private MediaSource? mediaSource;
    private Windows.Storage.Streams.IRandomAccessStream? localStream;
    private Windows.Foundation.TypedEventHandler<MediaPlayer, object>? openedHandler;
    private T C<T>(string name) where T : class => (T)root.FindName(name);
    private static readonly SolidColorBrush Sage = new(ColorHelper.FromArgb(255, 192, 215, 174));
    private static readonly SolidColorBrush Muted = new(ColorHelper.FromArgb(255, 172, 184, 174));

    public MainWindow()
    {
        using var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream("Music.App.Shell.xaml")!;
        using var reader = new StreamReader(stream);
        root = (Grid)XamlReader.Load(reader.ReadToEnd());
        ConfigureSavedBadge(C<Border>("CollectionDeviceBadge"), 23);
        var trackBadge = C<Border>("TrackDeviceBadge");
        ConfigureSavedBadge(trackBadge, 16);
        trackBadge.Tag = C<TextBlock>("TrackTitle");
        Content = root;
        InitializeRatings();
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
        foreach (var entry in new[] { ("LibraryNav", "library"), ("PlaylistsNav", "playlists"), ("AlbumsNav", "albums"), ("ArtistsNav", "artists"), ("SongsNav", "songs"), ("SettingsNav", "settings") })
        { string target = entry.Item2; C<Button>(entry.Item1).Click += async (_, _) => await NavigateAsync(target); }
        C<Button>("WelcomeConnect").Click += async (_, _) => await NavigateAsync("settings");
        C<Button>("ConnectButton").Click += async (_, _) => await ConnectAsync();
        C<Button>("CancelConnectButton").Click += (_, _) => { if (accountChangePending) return; connectGate.Cancel(); SetConnecting(false); C<TextBlock>("ConnectionStatus").Text = "Connection cancelled. Previous connection kept."; };
        C<Button>("TestConnectionButton").Click += async (_, _) => await TestConnectionAsync();
        C<TextBox>("SearchBox").TextChanged += async (_, _) =>
        {
            if (updatingNavigation || page is "settings" or "detail" || client is null) return;
            query = C<TextBox>("SearchBox").Text.Trim();
            await LoadBrowseAsync(true);
        };
        var layoutMenu = (MenuFlyout)C<Button>("LayoutButton").Flyout;
        ((MenuFlyoutItem)layoutMenu.Items[0]).Click += (_, _) => SetMode("grid");
        ((MenuFlyoutItem)layoutMenu.Items[1]).Click += (_, _) => SetMode("list");
        InitializePlayerControls();
        C<Button>("PlayButton").Click += (_, _) => TogglePlayback();
        C<Button>("PreviousButton").Click += (_, _) => Previous();
        C<Button>("NextButton").Click += (_, _) => Step(1);
        C<ToggleButton>("RepeatButton").Click += (_, _) => { SavePlayback(); ScheduleCache(); RenderQueue(); };
        C<Slider>("SeekSlider").ValueChanged += (_, e) =>
        {
            if (!updatingSeek && queueIndex >= 0)
                try
                {
                    if (!localPlayback && HasSavedSong(queue[queueIndex].Id)) SelectTrack(queueIndex, playIntended, e.NewValue);
                    else { restoredPosition = 0; player.PlaybackSession.Position = TimeSpan.FromSeconds(e.NewValue); SavePlayback(); }
                }
                catch { ShowError("This stream cannot seek right now."); }
        };
        C<Slider>("SeekSlider").AddHandler(UIElement.PointerPressedEvent, new Microsoft.UI.Xaml.Input.PointerEventHandler((_, _) => seekDragging = true), true);
        C<Slider>("SeekSlider").AddHandler(UIElement.PointerReleasedEvent, new Microsoft.UI.Xaml.Input.PointerEventHandler((_, _) => seekDragging = false), true);
        C<Slider>("SeekSlider").PointerCaptureLost += (_, _) => seekDragging = false;
        C<Button>("OutputButton").Click += async (_, _) => await ChooseOutputAsync();
        C<Button>("QueueButton").Click += (_, _) => C<Border>("QueuePane").Visibility = C<Border>("QueuePane").Visibility == Visibility.Visible ? Visibility.Collapsed : Visibility.Visible;
        C<Button>("CloseQueueButton").Click += (_, _) => { C<Border>("QueuePane").Visibility = Visibility.Collapsed; C<Button>("QueueButton").Focus(FocusState.Programmatic); };
        InitializeQueueList(C<ListView>("QueueList"));
        InitializeDetailList();
        InitializeArtistAlbums();
        InitializeNavigation();
        InitializePlaylistActions();
        player.PlaybackSession.PlaybackStateChanged += (_, _) => DispatcherQueue.TryEnqueue(UpdateTransport);
        player.MediaEnded += (_, _) =>
        {
            long endedVersion = Interlocked.Read(ref playbackVersion);
            DispatcherQueue.TryEnqueue(() => { if (!closed && endedVersion == playbackVersion) Step(1); });
        };
        player.MediaFailed += (_, _) =>
        {
            long failedVersion = Interlocked.Read(ref playbackVersion);
            DispatcherQueue.TryEnqueue(() =>
            {
                if (closed || failedVersion != playbackVersion) return;
                playbackInterrupted = true;
                if (!localPlayback && queueIndex >= 0 && HasSavedSong(queue[queueIndex].Id))
                { SelectTrack(queueIndex, playIntended, interruptedPosition); return; }
                ShowError("Playback interrupted. Cached and Saved songs work offline; reconnect to resume this song. Check the format and output device if it still fails.");
            });
        };
        player.CommandManager.PlayReceived += (_, _) => DispatcherQueue.TryEnqueue(() => { playIntended = true; UpdateTaskbarPlayback(); });
        player.CommandManager.PauseReceived += (_, _) => DispatcherQueue.TryEnqueue(() => { playIntended = false; UpdateTaskbarPlayback(); });
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
        C<TextBlock>("VersionText").Text = $"Current Version: {Assembly.GetExecutingAssembly().GetName().Version?.ToString(3)}";
        SetNavigation();
        RenderQueue();
        taskbarPlayback = TaskbarPlayback.Attach(
            WinRT.Interop.WindowNative.GetWindowHandle(this), DispatcherQueue, Previous, TogglePlayback, () => Step(1));
        UpdateTransport();
    }

    internal Task CheckForUpdatesOnStartupAsync() => updatesPanel.CheckOnStartupAsync();

    private async Task InitializeAsync()
    {
#if MUSIC_NATIVE_ACCEPTANCE
        if (NativeAcceptance.IsRequested) return;
#endif
        // Cover cache initialization too; browsing must not cancel useful catalog work.
        long startupRevision = navigationRevision;
        var lease = connectGate.Begin();
        NavidromeClient? candidate = null;
        try
        {
            profiles = new ProfileStore();
            sessions = new SessionStore(Path.Combine(profiles.DirectoryPath, "session.sqlite3"));
            InitializeOfflineSettings();
            var profile = profiles.ReadProfile();
            if (profile is null) return;
            C<TextBlock>("ConnectionInfo").Text = $"Saved connection: {profile.Name}\n{profile.ServerUrl}\nUsername: {profile.Username}\nNot connected yet.";
            C<TextBlock>("ConnectionStatus").Text = "Connecting to saved server…";
            candidate = new NavidromeClient(profile, profiles.ReadPassword(profile));
            ActivateConnection(profile, candidate, [], []); candidate = null;
            await cacheInitialization;
            if (closed || !connectGate.IsCurrent(lease)) return;
            RestorePlayback();
            await NavigateAsync("offline", remember: false, startupRevision: startupRevision);
            if (offlineSettings.Offline)
            {
                return;
            }
            var startupClient = client!;
            if (client is null) return;
            await client.PingAsync(lease.Token);
            long catalogRevision = playlistCatalogRevision;
            var data = await ReadLibraryAsync(startupClient, lease.Token);
            if (!connectGate.IsCurrent(lease) || closed) return;
            albums = data.Albums;
            playlists = MergePlaylistCatalog(data.Playlists, catalogRevision);
            InvalidateCollectionMembership();
            // A user-owned browse route still needs the newly published catalog.
            // Pending searches render it on completion; Songs pagination is untouched.
            if (navigationRevision != startupRevision && !browsePending && !offlineSettings.Offline &&
                page is "library" or "albums" or "playlists") RenderBrowse();
            await NavigateAsync("library", remember: false, startupRevision: startupRevision);
        }
        catch (OperationCanceledException) { }
        catch
        {
            if (connectGate.IsCurrent(lease) && !closed)
            {
                if (client is not null && connection is not null)
                {
                    await NavigateAsync("offline", remember: false, startupRevision: startupRevision);
                    ShowError("Server unavailable. Cached and Saved songs are available offline; incomplete songs need a connection.");
                }
                else ShowError("Could not open the saved connection or local session store. Check Windows credentials.");
            }
        }
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
        if (accountChangePending) return;
        if (offlineSettings.Offline) { ShowError("Turn off Offline mode before connecting to a server."); return; }
        var lease = connectGate.Begin();
        NavidromeClient? candidate = null;
        bool ownsAccountChange = false, committed = false;
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
            await cacheInitialization;
            cacheCancellation.Cancel(); await cacheTask;
            if (!connectGate.IsCurrent(lease) || closed || accountChangePending) return;
            // Credentials cannot be rolled back by a late cancellation: complete
            // activation once commit begins, while the dispatcher remains responsive.
            accountChangePending = true; ownsAccountChange = true; cacheMaintenance++;
            RefreshRatingViews();
            SetConnecting(true);
            RenderQueue();
            profiles.Commit(profile, password);
            committed = true;
            if (previous is not null && previous.Id != profile.Id)
            {
                if (!ConnectionIdentity.SameAccount(previous.ServerUrl, previous.Username, profile.ServerUrl, profile.Username))
                {
                    StopPlayback();
                    try { await PurgeAccountCacheAsync(previous); } catch { ShowError("The replaced account's saved media could not be fully removed. Check storage permissions."); }
                    if (closed) return;
                }
                try { sessions.RemovePlayback(previous.Id); } catch { ShowError("The previous account's saved queue could not be removed. Check storage permissions."); }
            }
            ActivateConnection(profile, candidate, data.Albums, data.Playlists);
            candidate = null;
            await cacheInitialization;
            if (closed) return;
            RestorePlayback();
            C<PasswordBox>("Password").Password = "";
            await NavigateAsync(offlineSettings.Offline ? "offline" : "library", remember: false);
        }
        catch (OperationCanceledException) { }
        catch
        {
            if (!closed && (ownsAccountChange || connectGate.IsCurrent(lease)))
                C<TextBlock>("ConnectionStatus").Text = committed
                    ? "The connection was saved, but setup did not finish. Reopen the app to retry the saved connection."
                    : "Could not connect or securely save credentials. Check server, username, password, and Windows Credential Locker. Previous connection kept.";
        }
        finally
        {
            candidate?.Dispose();
            if (ownsAccountChange) { accountChangePending = false; cacheMaintenance--; }
            if (!closed && (ownsAccountChange || connectGate.IsCurrent(lease))) SetConnecting(false);
            if (ownsAccountChange) { ScheduleCache(); if (!closed) { RenderQueue(); RefreshRatingViews(); } }
        }
    }

    private void SetConnecting(bool value)
    {
        RefreshPlaylistActionAvailability();
        C<Button>("ConnectButton").IsEnabled = !value && !offlineSettings.Offline && !accountChangePending;
        C<Button>("CancelConnectButton").Visibility = value ? Visibility.Visible : Visibility.Collapsed;
        C<Button>("CancelConnectButton").IsEnabled = !accountChangePending;
    }

    private void ActivateConnection(ConnectionProfile profile, NavidromeClient target, IReadOnlyList<Album> newAlbums, IReadOnlyList<Playlist> newPlaylists)
    {
        navigationHistory.Clear(); detailCollection = null;
        page = "library"; query = ""; mode = ReadMode("library");
        browseGate.Cancel(); testGate.Cancel(); artworkGate.Cancel(); playingArtGate.Cancel();
        connectionLease = connectionGate.Begin();
        C<StackPanel>("BrowsePanel").Children.Clear();
        ClearDetail();
        detailSongs.Clear(); detailTitle = "";
        StopPlayback();
        client?.Dispose();
        client = target; connection = profile; albums = newAlbums; playlists = newPlaylists;
        playlistEdits.Clear();
        unverifiedPlaylistIds.Clear();
        ResetRatings(target);
        artists = []; artistsLoaded = false;
        client.Offline = offlineSettings.Offline;
        cacheInitialization = ActivateCache(profile);
        songs.Clear(); artwork.Clear();
        C<TextBlock>("ConnectionStatus").Text = "Connected. Password protected by Windows Credential Locker.";
        C<TextBlock>("ConnectionInfo").Text = $"{profile.Name}\n{profile.ServerUrl}\nUsername: {profile.Username}\nNavidrome · Connected";
        C<Button>("TestConnectionButton").IsEnabled = !offlineSettings.Offline;
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

    private async Task NavigateAsync(string target, bool remember = true, NavigationView? restore = null, long? startupRevision = null)
    {
        // Automatic startup routes share one revision; user navigation (including Back
        // and repeated sidebar clicks) invalidates them without touching connectGate.
        if (startupRevision is { } expected)
        {
            if (navigationRevision != expected) return;
        }
        else navigationRevision++;
        var destination = restore ?? new NavigationView(target, "", ReadMode(target));
        if (remember && !navigationHistory.Record(CaptureNavigation(), destination)) return;
        browseGate.Cancel(); artworkGate.Cancel();
        ClearDetail();
        detailCollection = null;
        page = target; query = destination.Query;
        updatingNavigation = true;
        try { C<TextBox>("SearchBox").Text = query; }
        finally { updatingNavigation = false; }
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
        mode = destination.Mode;
        C<SymbolIcon>("LayoutIcon").Symbol = mode == "grid" ? Symbol.ViewAll : Symbol.List;
        SetNavigation();
        if (target != "settings" && client is not null) await LoadBrowseAsync();
    }

    private void SetNavigation()
    {
        UpdatePlaylistActions();
        foreach (var entry in new[] { ("LibraryNav", "library"), ("PlaylistsNav", "playlists"), ("AlbumsNav", "albums"), ("ArtistsNav", "artists"), ("SongsNav", "songs"), ("SettingsNav", "settings") })
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
        if (offlineSettings.Offline || page == "offline") { RenderOfflineBrowse(); return; }
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
            if (!artistsLoaded && requestedPage is "library" or "artists")
            {
                var index = await target.GetArtistsAsync(lease.Token);
                if (!browseGate.IsCurrent(lease) || closed || target != client) return;
                artists = index; artistsLoaded = true;
            }
            if (requestedPage is "library" or "songs") result = await target.SearchSongsAsync(requestedQuery, 0, 100, lease.Token);
            if (!browseGate.IsCurrent(lease) || closed || target != client) return;
            songs = result.ToList(); songOffset = result.Count; moreSongs = result.Count == 100;
            RenderBrowse();
        }
        catch (OperationCanceledException) { }
        catch
        {
            if (browseGate.IsCurrent(lease) && target == client && !closed)
            {
                songs.Clear(); moreSongs = false; RenderBrowse();
                ShowError("Could not load the library. Check the connection and retry.");
                var retry = new Button { Content = "Retry loading library" };
                retry.Click += async (_, _) => { if (browseGate.IsCurrent(lease) && target == client && !closed) await LoadBrowseAsync(); };
                C<StackPanel>("BrowsePanel").Children.Add(retry);
            }
        }
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
        if (offlineSettings.Offline || page == "offline") { RenderOfflineBrowse(); return; }
        var artLease = artworkGate.Begin();
        var panel = C<StackPanel>("BrowsePanel"); panel.Children.Clear(); panel.Spacing = 24;
        bool Match(string text) => text.Contains(query, StringComparison.OrdinalIgnoreCase);
        var foundAlbums = albums.Where(a => Match(a.Name + " " + a.Artist)).ToList();
        var foundPlaylists = playlists.Where(p => Match(p.Name)).ToList();
        var foundArtists = artists.Where(a => Match(a.Name)).ToList();
        bool library = page == "library";
        if (BrowsePolicy.ShowSection(page, "playlists", foundPlaylists.Count))
        {
            var section = NewSection("Playlists", "playlists");
            AddCollection(section, foundPlaylists.Select(p => new Card(p.Name, $"{p.SongCount} songs", p.CoverArtId, p)).ToList(), library ? "row" : mode, artLease.Token);
            panel.Children.Add(section);
        }
        if (BrowsePolicy.ShowSection(page, "albums", foundAlbums.Count))
        {
            var section = NewSection("Albums", "albums");
            AddCollection(section, foundAlbums.Select(a => new Card(a.Name, $"{a.Artist} · {a.Year}", a.CoverArtId, a)).ToList(), library ? "row" : mode, artLease.Token);
            panel.Children.Add(section);
        }
        if (BrowsePolicy.ShowSection(page, "artists", foundArtists.Count))
        {
            var section = NewSection("Artists", "artists");
            AddCollection(section, foundArtists.Select(a => new Card(a.Name, $"{a.AlbumCount} albums", a.CoverArtId, a)).ToList(), library ? "row" : mode, artLease.Token);
            panel.Children.Add(section);
        }
        if (BrowsePolicy.ShowSection(page, "songs", songs.Count, query))
        {
            var section = NewSection("Songs", "songs");
            AddSongs(section, library ? songs.Take(8).ToList() : songs, library ? "list" : mode, artLease.Token, showRatings: page == "songs");
            if (!library && moreSongs) AddLoadMore(section);
            panel.Children.Add(section);
        }
        if (panel.Children.Count == 0) panel.Children.Add(Text(string.IsNullOrEmpty(query) ? "No music found in this collection." : "No matching albums, playlists, artists, or songs.", 16, true));
    }

    private StackPanel NewSection(string title, string target)
    {
        var panel = new StackPanel { Spacing = 12 };
        if (page == "library")
        {
            if (target is "playlists" or "albums" or "artists")
            {
                var heading = new HyperlinkButton
                {
                    Content = title, FontSize = 21, Foreground = Sage,
                    FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
                    HorizontalAlignment = HorizontalAlignment.Left, Padding = new Thickness(0), MinHeight = 32
                };
                // The native template replaces Foreground in these visual states.
                heading.Resources["HyperlinkButtonForegroundPointerOver"] = Sage;
                heading.Resources["HyperlinkButtonForegroundPressed"] = Sage;
                AutomationProperties.SetName(heading, "Open all " + title.ToLowerInvariant());
                AutomationProperties.SetHeadingLevel(heading, Microsoft.UI.Xaml.Automation.Peers.AutomationHeadingLevel.Level2);
                heading.Click += async (_, _) => await NavigateAsync(target);
                panel.Children.Add(CreateLibraryShelfHeader(heading, title));
            }
            else
            {
                var header = new Grid();
                header.Children.Add(Text(title, 21));
                var all = new HyperlinkButton { Content = "View all " + title.ToLowerInvariant() + "  ›", HorizontalAlignment = HorizontalAlignment.Right, Foreground = Sage };
                all.Click += async (_, _) => await NavigateAsync(target);
                header.Children.Add(all); panel.Children.Add(header);
            }
        }
        return panel;
    }
    private sealed record Card(string Title, string Subtitle, string? ArtId, object Value);
    private void AddCollection(StackPanel section, IReadOnlyList<Card> cards, string layout, CancellationToken token)
    {
        var owner = connectionLease;
        long renderedVersion = browseResultsVersion;
        string renderedPage = page;
        ListViewBase view = layout == "grid" ? new GridView() : new ListView();
        view.SelectionMode = ListViewSelectionMode.None; view.IsItemClickEnabled = true;
        view.HorizontalAlignment = HorizontalAlignment.Stretch;
        ScrollViewer.SetVerticalScrollMode(view, ScrollMode.Disabled);
        ScrollViewer.SetVerticalScrollBarVisibility(view, ScrollBarVisibility.Disabled);
        if (layout == "row")
        {
            view.ItemsPanel = (ItemsPanelTemplate)root.Resources["LibraryRowPanel"];
            ScrollViewer.SetHorizontalScrollMode(view, ScrollMode.Enabled);
            ScrollViewer.SetHorizontalScrollBarVisibility(view, ScrollBarVisibility.Hidden);
            BindLibraryShelf(section, view);
        }
        BindPlaylistContext(view);
        foreach (var card in cards) view.Items.Add(CardElement(card, layout == "row" ? "grid" : layout, token));
        view.ItemClick += async (_, e) =>
        {
            if (closed || !connectionGate.IsCurrent(owner) || e.ClickedItem is not FrameworkElement item) return;
            if (item.Tag is Artist && (renderedVersion != browseResultsVersion || renderedPage != page)) return;
            await OpenCollectionAsync(item.Tag);
        };
        section.Children.Add(view);
    }
    private FrameworkElement CardElement(Card card, string layout, CancellationToken token, Song? song = null)
    {
        if (card.Value is Artist artist) return ArtistCard(artist, layout, token);
        var outer = new Grid { Tag = card.Value, Margin = new Thickness(0, 0, 12, 12), Padding = new Thickness(4), MinHeight = 60 };
        var art = new Image { Stretch = Stretch.UniformToFill, Width = layout == "grid" ? 156 : 48, Height = layout == "grid" ? 156 : 48 };
        var cover = new Border { Background = new SolidColorBrush(ColorHelper.FromArgb(255, 53, 66, 57)), CornerRadius = new CornerRadius(5), Child = art };
        var copy = new StackPanel { Spacing = 4 };
        copy.Children.Add(song is null ? CardTitle(card) : SongTitle(song, 14)); copy.Children.Add(Text(card.Subtitle, 12, true));
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
        AddCollectionPlaylistAction(outer, card.Value);
        AutomationProperties.SetName(outer, card.Title + ", " + card.Subtitle);
        if (!offlineSettings.Offline) _ = artwork.SetAsync(art, card.ArtId, client!, token);
        return outer;
    }

    private void AddSongs(StackPanel section, IReadOnlyList<Song> tracks, string layout, CancellationToken token, IReadOnlyList<Song>? playbackTracks = null, bool showRatings = false)
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
            var header = SongGrid(showRatings); header.MinHeight = 32;
            string[] headings = showRatings ? ["#", "Title / artist", "Album", "Rating", "Time"] : ["#", "Title / artist", "Album", "Time"];
            for (int column = 0; column < headings.Length; column++)
            {
                var heading = Text(headings[column], 12, true);
                if (column == 1) heading.Margin = new Thickness(52, 0, 0, 0);
                if (column == headings.Length - 1) heading.HorizontalAlignment = HorizontalAlignment.Right;
                Grid.SetColumn(heading, column); header.Children.Add(heading);
            }
            section.Children.Add(header);
        }
        ScrollViewer.SetVerticalScrollMode(view, ScrollMode.Disabled);
        ScrollViewer.SetVerticalScrollBarVisibility(view, ScrollBarVisibility.Disabled);
        var playQueue = playbackTracks ?? tracks.ToList();
        BindPlaylistContext(view);
        for (int index = 0; index < tracks.Count; index++)
        {
            view.Items.Add(SongRow(tracks[index], index, layout, token, owner, showRatings));
        }
        view.ItemClick += (_, e) => { if (!closed && connectionGate.IsCurrent(owner) && e.ClickedItem is FrameworkElement { Tag: int index }) StartQueue(playQueue, index); };
        section.Children.Add(view);
    }
    private FrameworkElement SongRow(Song song, int index, string layout, CancellationToken token, RequestGate.Lease owner, bool showRatings = false)
    {
        FrameworkElement row;
        if (layout == "grid") row = CardElement(new Card(song.Title, song.Artist, song.CoverArtId, index), layout, token, song);
        else
        {
            var grid = SongGrid(showRatings, showRatings ? inline => inline ? CreateInlineRating(song, owner) : CreateCompactRating(song, owner) : null);
            grid.Tag = index;
            grid.Children.Add(Text((index + 1).ToString(), 12, true));
            var copy = new StackPanel { Spacing = 3, VerticalAlignment = VerticalAlignment.Center };
            copy.Children.Add(SongTitle(song, 14)); copy.Children.Add(Text(song.Artist, 12, true));
            ToolTipService.SetToolTip(copy, song.Title + "\n" + song.Artist);
            var cell = SongCell(song, copy, token, owner);
            Grid.SetColumn(cell, 1); grid.Children.Add(cell);
            var album = Text(song.Album, 12, true); ToolTipService.SetToolTip(album, song.Album); Grid.SetColumn(album, 2); grid.Children.Add(album);

            var duration = Text(Clock(song.DurationSeconds), 12, true); duration.HorizontalAlignment = HorizontalAlignment.Right; Grid.SetColumn(duration, showRatings ? 4 : 3); grid.Children.Add(duration);
            AutomationProperties.SetName(grid, song.Title + ", " + song.Artist + ", " + Clock(song.DurationSeconds)); row = grid;
        }
        AddSongDeviceActions(row, song, owner);
        return row;
    }

    private static Grid SongGrid(bool showRatings, Func<bool, FrameworkElement>? createRating = null)
    {
        var grid = new Grid
        {
            MinHeight = 56, Padding = new Thickness(6, 8, 6, 8), ColumnSpacing = 12,
            HorizontalAlignment = HorizontalAlignment.Stretch,
            BorderBrush = new SolidColorBrush(ColorHelper.FromArgb(255, 57, 69, 61)), BorderThickness = new Thickness(0, 0, 0, 1)
        };
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(28) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(showRatings ? 2 : 1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(0.8, GridUnitType.Star) });
        if (showRatings)
        {
            const double compactRatingWidth = 40;
            const double inlineRatingWidth = 145;
            var ratingColumn = new ColumnDefinition { Width = new GridLength(compactRatingWidth) };
            grid.ColumnDefinitions.Add(ratingColumn);
            FrameworkElement? rating = null;
            bool? wasInline = null;
            // The sidebar, Queue and DPI can all change the actual room for titles.
            grid.SizeChanged += (_, args) =>
            {
                bool inline = args.NewSize.Width >= 600;
                if (inline == wasInline) return;
                wasInline = inline;
                ratingColumn.Width = new GridLength(inline ? inlineRatingWidth : compactRatingWidth);
                if (createRating is null) return; // Heading uses the same column without an action.
                if (rating is not null) grid.Children.Remove(rating);
                rating = createRating(inline);
                Grid.SetColumn(rating, 3); grid.Children.Add(rating);
            };
        }
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(44) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(32) });
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
    private bool detailLoading, detailError;
    private (string Kind, string Id) detailIdentity;
    private object? renderedCollectionCover;
    private readonly CollectionTransport collectionTransport = new();
    private sealed record DetailSong(Song Song, int Index)
    {
        public RequestGate.Lease Owner { get; init; }
    }
    private void InitializeDetailList()
    {
        var view = C<ListView>("DetailList");
        var itemStyle = new Style(typeof(ListViewItem));
        itemStyle.Setters.Add(new Setter(Control.HorizontalContentAlignmentProperty, HorizontalAlignment.Stretch));
        itemStyle.Setters.Add(new Setter(Control.PaddingProperty, new Thickness(0)));
        itemStyle.Setters.Add(new Setter(FrameworkElement.MinHeightProperty, 0d));
        view.ItemContainerStyle = itemStyle;
        view.ContainerContentChanging += (_, args) =>
        {
            if (args.ItemContainer.ContentTemplateRoot is not Grid host) return;
            host.Children.Clear();
            args.ItemContainer.ContextFlyout = null;
            AutomationProperties.SetName(args.ItemContainer, "");
            if (args.InRecycleQueue || closed || page != "detail" || args.Item is not DetailSong item) return;
            var row = SongRow(item.Song, item.Index, "list", CancellationToken.None, item.Owner, showRatings: detailIdentity.Kind == "playlist");
            args.ItemContainer.ContextFlyout = row.ContextFlyout;
            AutomationProperties.SetName(args.ItemContainer, AutomationProperties.GetName(row));
            host.Children.Add(row);
            args.Handled = true;
        };
        view.ItemClick += (_, args) =>
        {
            if (args.ClickedItem is not DetailSong item) return;
            var owner = item.Owner;
            if (closed || accountChangePending || client is null || !connectionGate.IsCurrent(owner) ||
                detailLoading || detailError || page != "detail" || (detailIdentity.Kind == "artist" && artistSongsIncomplete) ||
                item.Index < 0 || item.Index >= detailSongs.Count || !ReferenceEquals(detailSongs[item.Index], item.Song)) return;
            int index = item.Index;
            StartQueue(detailSongs, index);
        };
    }
    private void ClearDetail()
    {
        C<GridView>("ArtistAlbumList").ItemsSource = null;
        C<GridView>("ArtistAlbumList").Visibility = Visibility.Collapsed;
        artistAlbums = [];
        C<ListView>("DetailList").ItemsSource = null;
        C<StackPanel>("DetailHeader").Children.Clear();
        ClearCollectionHeader();
        C<Grid>("PageHeader").Visibility = Visibility.Visible;
        C<Grid>("DetailHost").Visibility = Visibility.Collapsed;
        C<ScrollViewer>("MainScroll").Visibility = Visibility.Visible;
        detailLoading = false;
    }
    private async Task OpenCollectionAsync(object? value, bool remember = true, IReadOnlyList<Song>? confirmedSongs = null)
    {
        if (value is ArtistNavigation origin) { await OpenArtistAsync(origin.Artist, remember, origin.View); return; }
        if (value is Artist artist) { await OpenArtistAsync(artist, remember); return; }
        if (client is null) return;
        if (offlineSettings.Offline) { RenderOfflineBrowse(); return; }
        var artistAlbum = value as ArtistAlbumNavigation;
        if (artistAlbum is not null) value = artistAlbum.Album;
        string title = value switch { Album album => album.Name, Playlist playlist => playlist.Name, _ => "" };
        if (value is not Album and not Playlist) return;
        navigationRevision++;
        var destination = new NavigationView("detail", query, mode, artistAlbum ?? value);
        if (remember && !navigationHistory.Record(CaptureNavigation(), destination)) return;
        detailCollection = destination.Collection;
        var lease = browseGate.Begin(); var target = client;
        browsePending = false; browseResultsVersion++;
        C<ProgressBar>("BusyBar").Visibility = Visibility.Collapsed;
        page = "detail"; detailTitle = title; detailSongs = new(); detailLoading = true; detailError = false;
        detailIdentity = value switch { Album album => ("album", album.Id), Playlist playlist => ("playlist", playlist.Id), _ => default };
        RememberCollectionMembership(detailIdentity, null);
        C<TextBox>("SearchBox").Visibility = Visibility.Collapsed;
        C<Button>("LayoutButton").Visibility = Visibility.Collapsed;
        SetNavigation();
        RenderDetail();
        try
        {
            IReadOnlyList<Song> tracks;
            PlaylistContents? refreshed = null;
            if (confirmedSongs is not null && value is Playlist) tracks = confirmedSongs;
            else if (value is Album album) tracks = await target.GetAlbumSongsAsync(album.Id, lease.Token, validateCount: artistAlbum is not null);
            else if (value is Playlist playlist)
            {
                if (unverifiedPlaylistIds.Contains(playlist.Id))
                {
                    refreshed = await target.GetPlaylistContentsAsync(playlist.Id, lease.Token);
                    tracks = refreshed.Songs;
                }
                else tracks = await target.GetPlaylistSongsAsync(playlist.Id, lease.Token);
            }
            else return;
            if (!browseGate.IsCurrent(lease) || target != client || closed) return;
            if (refreshed is not null)
            {
                unverifiedPlaylistIds.Remove(refreshed.Playlist.Id);
                detailCollection = refreshed.Playlist;
                detailTitle = refreshed.Playlist.Name;
            }
            detailLoading = false; detailSongs = tracks.ToList();
            RememberCollectionMembership(detailIdentity, detailSongs);
            RenderDetail();
        }
        catch (OperationCanceledException) { }
        catch
        {
            if (browseGate.IsCurrent(lease) && target == client && !closed)
            {
                detailLoading = false; detailError = true; RenderDetail();
                if (value is not Playlist failedPlaylist || !unverifiedPlaylistIds.Contains(failedPlaylist.Id))
                    ShowError("Could not open this collection. Return to Library and try again.");
            }
        }
    }
    private void RenderDetail()
    {
        artworkGate.Cancel();
        C<StackPanel>("BrowsePanel").Children.Clear();
        C<ScrollViewer>("MainScroll").Visibility = Visibility.Collapsed;
        C<Grid>("DetailHost").Visibility = Visibility.Visible;
        var panel = C<StackPanel>("DetailHeader"); panel.Children.Clear(); panel.Spacing = 12;
        bool isCollection = detailIdentity.Kind is "album" or "playlist";
        C<Grid>("PageHeader").Visibility = isCollection ? Visibility.Collapsed : Visibility.Visible;
        bool partialArtist = detailIdentity.Kind == "artist" && artistSongsIncomplete;
        bool unverifiedPlaylist = detailError && detailCollection is Playlist unverified && unverifiedPlaylistIds.Contains(unverified.Id);
        string count = detailLoading ? "Loading songs…" : unverifiedPlaylist ? "Songs unverified" : $"{detailSongs.Count} songs{(partialArtist ? " loaded" : "")}";
        if (!isCollection)
        {
            ClearCollectionHeader();
            RenderArtistHeader(panel);
            var actions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 16, Margin = new Thickness(0, 0, 0, 8) };
            actions.Children.Add(Text(count, 13, true));
            panel.Children.Add(actions);
            panel.Children.Add(CreateArtistTransport());
        }
        else
        {
            if (!ReferenceEquals(renderedCollectionCover, detailCollection))
            {
                string? coverArtId = detailCollection switch
                {
                    Album album => album.CoverArtId,
                    Playlist playlist => playlist.CoverArtId,
                    ArtistAlbumNavigation origin => origin.Album.CoverArtId,
                    _ => null
                };
                C<Border>("CollectionCover").Child = CoverArtwork(coverArtId, CancellationToken.None, connectionLease, 120);
                renderedCollectionCover = detailCollection;
            }
            C<TextBlock>("CollectionTitle").Text = detailTitle;
            UpdateCollectionDeviceBadge();
            ToolTipService.SetToolTip(C<TextBlock>("CollectionTitle"), detailTitle);
            var albumDetails = detailCollection as Album ?? (detailCollection as ArtistAlbumNavigation)?.Album;
            var metadata = new[] { albumDetails?.Artist, albumDetails is { Year: > 0 } ? albumDetails.Year.ToString() : null, count };
            C<TextBlock>("CollectionSummary").Text = string.Join(" · ", metadata.Where(value => !string.IsNullOrWhiteSpace(value)));
            C<Border>("CollectionActions").Child = CreateCollectionTransport();
            C<Grid>("CollectionHeader").Visibility = Visibility.Visible;
        }
        bool showRatings = detailIdentity.Kind == "playlist";
        var header = SongGrid(showRatings); header.MinHeight = 32;
        string[] headings = showRatings ? ["#", "Title / artist", "Album", "Rating", "Time"] : ["#", "Title / artist", "Album", "Time"];
        for (int column = 0; column < headings.Length; column++)
        {
            var heading = Text(headings[column], 12, true);
            if (column == 1) heading.Margin = new Thickness(52, 0, 0, 0);
            if (column == headings.Length - 1) heading.HorizontalAlignment = HorizontalAlignment.Right;
            Grid.SetColumn(heading, column); header.Children.Add(heading);
        }
        panel.Children.Add(header);
        var view = C<ListView>("DetailList");
        view.ItemContainerTransitions = new Microsoft.UI.Xaml.Media.Animation.TransitionCollection();
        view.IsItemClickEnabled = !partialArtist;
        view.ItemsSource = detailSongs.Select((song, index) => new DetailSong(song, index) { Owner = connectionLease }).ToArray();
        bool showAlbums = detailIdentity.Kind == "artist" && artistView == "albums";
        view.Visibility = showAlbums ? Visibility.Collapsed : Visibility.Visible;
        header.Visibility = showAlbums ? Visibility.Collapsed : Visibility.Visible;
        if (detailIdentity.Kind == "playlist" && !detailLoading && !detailError && detailSongs.Count == 0)
        {
            header.Visibility = Visibility.Collapsed;
            RenderEmptyPlaylistActions(panel);
        }
        if (unverifiedPlaylist && detailCollection is Playlist playlistToRetry)
        {
            header.Visibility = Visibility.Collapsed;
            RenderUnverifiedPlaylistAction(panel, playlistToRetry);
        }
        var albumView = C<GridView>("ArtistAlbumList");
        albumView.Visibility = showAlbums ? Visibility.Visible : Visibility.Collapsed;
        albumView.ItemsSource = showAlbums ? artistAlbums : null;
    }

    private void ClearCollectionHeader()
    {
        renderedCollectionCover = null;
        C<Grid>("CollectionHeader").Visibility = Visibility.Collapsed;
        C<Border>("CollectionCover").Child = null;
        C<Border>("CollectionActions").Child = null;
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
        if (tracks.Count == 0 || client is null || index < 0 || index >= tracks.Count) return;
        playbackOrder = null;
        playedExpanded = false;
        queue = tracks.ToList(); SelectTrack(index, true);
    }
    private async void SelectTrack(int index, bool play, double position = 0)
    {
        if (closed || client is null || index < 0 || index >= queue.Count) return;
        long version = ++playbackVersion;
        // Invalidate old transfers before any asynchronous local-file open. A worker
        // already past cancellation is also fenced by the cache's live protection.
        cacheCancellation.Cancel();
        try
        {
            if (startedHandler is not null) { player.PlaybackSession.PlaybackStateChanged -= startedHandler; startedHandler = null; }
            player.Pause();
            if (openedHandler is not null) player.MediaOpened -= openedHandler;
            player.Source = null; mediaSource?.Dispose(); mediaSource = null; localStream?.Dispose(); localStream = null;
            queueIndex = index; restoredPosition = Math.Max(0, position);
            EnsurePlaybackOrder();
            playbackOrder!.Select(index);
            Song song = queue[index];
            // Publish protection immediately, before exposing selection or yielding.
            // This short memory-only operation never waits behind storage IO.
            string? previous = activeSongId != song.Id ? activeSongId : previousSongId;
            var storage = cache;
            var protectedSongs = new[] { song.Id, previous }.OfType<string>().ToArray();
            storage?.SetProtectedSongs(protectedSongs);
            previousSongId = previous; activeSongId = song.Id;
            interruptedPosition = Math.Max(0, position);
            playIntended = play; playbackInterrupted = false;
            UpdateTaskbarPlayback();
            C<TextBlock>("TrackTitle").Text = song.Title;
            UpdateTrackDeviceBadge(song);
            C<TextBlock>("TrackArtist").Text = song.Artist;
            UpdatePlayerRating();
            RenderQueue(); SavePlayback();
            var local = await storageWork.RunAsync(() => storage?.Find(song.Id));
            if (closed || version != playbackVersion || storage != cache) return;
            localPlayback = local is not null;
#if MUSIC_NATIVE_ACCEPTANCE
            if (NativeAcceptance.IsRequested) mediaSource = MediaSource.CreateFromUri(NativeAcceptance.MediaUri);
            else
#endif
            if (local is not null)
            {
                var file = await Windows.Storage.StorageFile.GetFileFromPathAsync(local);
                if (closed || version != playbackVersion) return;
                var opened = await file.OpenReadAsync();
                if (closed || version != playbackVersion) { opened.Dispose(); return; }
                localStream = opened;
                string contentType = storage?.Snapshot().GetValueOrDefault(song.Id)?.ContentType ?? "application/octet-stream";
                mediaSource = MediaSource.CreateFromStream(localStream, contentType);
            }
            else
            {
                if (offlineSettings.Offline)
                {
                    ShowError("This song has no playable local copy. Reconnect to play it.");
                    UpdateTransport(); return;
                }
                mediaSource = MediaSource.CreateFromUri(client.GetStreamUri(song.Id));
            }
            ScheduleCache();
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
            var startEvidence = playbackOrder!.CaptureStartEvidence();
            startedHandler = (session, _) =>
            {
                // Record source/visit-bound evidence before dispatch. Only the tiny
                // evidence cell is shared; queue state and UI stay dispatcher-owned.
                if (session.PlaybackState != MediaPlaybackState.Playing || (player.Source as MediaPlaybackItem) != item) return;
                startEvidence();
                DispatcherQueue.TryEnqueue(() =>
                {
                    if (closed || version != playbackVersion || (player.Source as MediaPlaybackItem) != item) return;
                    RenderQueue(); UpdateTransport();
                });
            };
            player.PlaybackSession.PlaybackStateChanged += startedHandler;
            if (playIntended) player.Play();
            C<Image>("CurrentArt").Source = null;
            var lease = playingArtGate.Begin(); if (!offlineSettings.Offline) _ = artwork.SetAsync(C<Image>("CurrentArt"), song.CoverArtId, client, lease.Token);
            RenderQueue(); Tick(); SavePlayback();
        }
        catch { ShowError("Could not open this stream. Check server connectivity and the song format."); }
    }
    private void TogglePlayback()
    {
        if (closed || queueIndex < 0) return;
        try
        {
            if (playIntended) { playIntended = false; player.Pause(); SavePlayback(); }
            else
            {
                playIntended = true;
                if (playbackOrder?.Ended == true) SelectTrack(queueIndex, true);
                else if (playbackInterrupted || player.Source is null) SelectTrack(queueIndex, true, interruptedPosition > 0 ? interruptedPosition : restoredPosition);
                else player.Play();
            }
        }
        catch { ShowError("Playback is unavailable right now."); }
        finally { UpdateTaskbarPlayback(); }
    }
    private void Previous()
    {
        if (closed || queueIndex < 0) return;
        try
        {
            if (playbackOrder?.Ended != true && player.PlaybackSession.Position.TotalSeconds > 3 && player.PlaybackSession.CanSeek)
            {
                if (!localPlayback && HasSavedSong(queue[queueIndex].Id)) SelectTrack(queueIndex, playIntended);
                else { player.PlaybackSession.Position = TimeSpan.Zero; SavePlayback(); }
                return;
            }
        }
        catch { }
        Step(-1);
    }
    private void Step(int direction)
    {
        if (closed || queue.Count == 0 || queueIndex < 0) return;
        EnsurePlaybackOrder();
        if (direction > 0)
        {
            var plannedNext = playbackOrder!.MoveNext();
            if (plannedNext is null) { playbackOrder!.Finish(); playIntended = false; player.Pause(); RenderQueue(); SavePlayback(); UpdateTransport(); return; }
            SelectTrack(plannedNext.Value, true); return;
        }
        SelectTrack(playbackOrder!.MovePrevious(), true);
    }
    private sealed record QueueEntry(List<Song> Queue, int Index = -1, string? Heading = null, long VisitId = 0, bool Current = false, int PendingOffset = -1);
    private void InitializeQueueList(ListView list)
    {
        // Inset the items, not the scrollbar, so the native overlay has its own gutter.
        list.Padding = new Thickness(0, 0, 16, 0);
        var itemStyle = new Style(typeof(ListViewItem));
        itemStyle.Setters.Add(new Setter(Control.HorizontalContentAlignmentProperty, HorizontalAlignment.Stretch));
        itemStyle.Setters.Add(new Setter(Control.PaddingProperty, new Thickness(0)));
        list.ItemContainerStyle = itemStyle;
        list.ContainerContentChanging += (_, args) =>
        {
            if (args.ItemContainer.ContentTemplateRoot is not Grid host) return;
            host.Children.Clear();
            args.ItemContainer.ContextFlyout = null;
            AutomationProperties.SetName(args.ItemContainer, "");
            if (args.InRecycleQueue || closed || args.Item is not QueueEntry entry || !ReferenceEquals(entry.Queue, queue)) return;
            if (entry.Heading == "Played") host.Children.Add(CreatePlayedExpander());
            else if (entry.Heading == "Up next") host.Children.Add(CreateUpNextHeading());
            else if (entry.Heading is not null)
            {
                var heading = Text(entry.Heading, 12, true); heading.Margin = new Thickness(8, 16, 8, 8);
                AutomationProperties.SetHeadingLevel(heading, Microsoft.UI.Xaml.Automation.Peers.AutomationHeadingLevel.Level3);
                host.Children.Add(heading);
            }
            else
            {
                var row = CreateQueueSong(entry);
                args.ItemContainer.ContextFlyout = row.ContextFlyout;
                AutomationProperties.SetName(args.ItemContainer, AutomationProperties.GetName(row));
                host.Children.Add(row);
            }
            args.Handled = true;
        };
        list.ItemClick += (_, args) =>
        {
            if (closed || accountChangePending || args.ClickedItem is not QueueEntry entry ||
                entry.Index < 0 || !ReferenceEquals(entry.Queue, queue)) return;
            if (entry.VisitId != 0 && playbackOrder?.SelectPlayed(entry.VisitId) != true) return;
            if (entry.PendingOffset >= 0 && (playbackOrder is null ||
                entry.PendingOffset >= playbackOrder.RemainingOrder.Count || playbackOrder.RemainingOrder[entry.PendingOffset] != entry.Index ||
                !playbackOrder.SelectPending(entry.PendingOffset))) return;
            SelectTrack(entry.Index, true);
        };
    }
    private Grid CreateQueueSong(QueueEntry entry)
    {
        var song = entry.Queue[entry.Index];
        var title = SongTitle(song, 14, current: entry.Current);
        var copy = new StackPanel { Spacing = 3, VerticalAlignment = VerticalAlignment.Center };
        copy.Children.Add(title); copy.Children.Add(Text(song.Artist, 12, true));
        var row = new Grid { Tag = entry.Index, MinHeight = 60, Padding = new Thickness(8, 10, 8, 10) };
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        row.ColumnSpacing = 8;
        row.Children.Add(SongCell(song, copy, CancellationToken.None, connectionLease, 10));
        var duration = Text(Clock(song.DurationSeconds), 11, true);
        duration.HorizontalAlignment = HorizontalAlignment.Right;
        var tail = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        tail.Children.Add(duration);
        tail.Children.Add(CreateCompactRating(song, connectionLease, HorizontalAlignment.Right));
        Grid.SetColumn(tail, 1); row.Children.Add(tail);
        if (entry.Current) row.Background = new SolidColorBrush(ColorHelper.FromArgb(255, 53, 66, 57));
        AutomationProperties.SetName(row, (entry.Current ? "Current song: " : "") + song.Title + ", " + song.Artist);
        ToolTipService.SetToolTip(row, song.Title + "\n" + song.Artist);
        AddSongPlaylistAction(row, song, connectionLease, valid: () => ReferenceEquals(entry.Queue, queue) &&
            entry.Index < queue.Count && ReferenceEquals(queue[entry.Index], song) && C<Border>("QueuePane").Visibility == Visibility.Visible);
        return row;
    }
    private Grid CreateUpNextHeading()
    {
        var capturedQueue = queue;
        var order = playbackOrder;
        var owner = connectionLease;
        var heading = new Grid { MinHeight = 54, Margin = new Thickness(8, 8, 0, 0) };
        heading.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        heading.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var title = Text("Up next", 14); title.VerticalAlignment = VerticalAlignment.Center;
        AutomationProperties.SetHeadingLevel(title, Microsoft.UI.Xaml.Automation.Peers.AutomationHeadingLevel.Level3);
        heading.Children.Add(title);
        var shuffle = new Button { Content = new SymbolIcon(Symbol.Shuffle), Width = 48, Height = 48,
            MinWidth = 48, Padding = new Thickness(0), Background = new SolidColorBrush(Colors.Transparent),
            BorderThickness = new Thickness(0), HorizontalAlignment = HorizontalAlignment.Right,
            IsEnabled = !closed && !accountChangePending && order?.CanShufflePending == true };
        AutomationProperties.SetName(shuffle, "Shuffle Up next");
        ToolTipService.SetToolTip(shuffle, "Shuffle Up next");
        shuffle.Click += (_, _) =>
        {
            if (closed || accountChangePending || !connectionGate.IsCurrent(owner) ||
                !ReferenceEquals(capturedQueue, queue) || !ReferenceEquals(order, playbackOrder) ||
                order is null || !order.ShufflePending()) return;
            SavePlayback(); ScheduleCache(allowCurrentHandoff: false); RenderQueue();
        };
        Grid.SetColumn(shuffle, 1); heading.Children.Add(shuffle);
        return heading;
    }
    private Expander CreatePlayedExpander()
    {
        var header = new Grid();
        header.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        header.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        header.Children.Add(Text("Played", 14));
        int count = playbackOrder?.Played.Count ?? 0;
        var countLabel = Text($"{count} {(count == 1 ? "song" : "songs")}", 12, true);
        countLabel.HorizontalAlignment = HorizontalAlignment.Right;
        Grid.SetColumn(countLabel, 1); header.Children.Add(countLabel);
        var expander = new Expander { Header = header, IsExpanded = playedExpanded,
            Style = (Style)root.Resources["PlayedDisclosureStyle"],
            HorizontalAlignment = HorizontalAlignment.Stretch, HorizontalContentAlignment = HorizontalAlignment.Stretch };
        AutomationProperties.SetName(expander, "Played songs");
        expander.Expanding += (_, _) => playedExpanded = true;
        expander.Collapsed += (_, _) => playedExpanded = false;
        if (playbackOrder?.Played.Count > 0)
        {
            var playedList = new ListView { MaxHeight = 240, SelectionMode = ListViewSelectionMode.Single, IsItemClickEnabled = true,
                ItemTemplate = (DataTemplate)root.Resources["DetailRowTemplate"] };
            InitializeQueueList(playedList);
            playedList.ItemsSource = playbackOrder.Played.Select(x => new QueueEntry(queue, x.Index, VisitId: x.Id)).ToArray();
            AutomationProperties.SetName(playedList, "Played songs");
            expander.Content = playedList;
        }
        else expander.Content = Text("No songs played yet.", 12, true);
        return expander;
    }
    private void RenderQueue()
    {
        var list = C<ListView>("QueueList");
        EnsurePlaybackOrder();
        string state = $"{accountChangePending}:{queueIndex}:{playbackOrder?.Ended}:{C<ToggleButton>("RepeatButton").IsChecked}:" +
            string.Join(',', playbackOrder?.Played.Select(x => x.Id) ?? []) + ":" + string.Join(',', playbackOrder?.RemainingOrder ?? []);
        if (ReferenceEquals(renderedQueue, queue) && renderedQueueCount == queue.Count && renderedQueueState == state) return;
        var entries = new List<QueueEntry>();
        if (queueIndex >= 0 && playbackOrder is not null)
        {
            entries.Add(new(queue, Heading: "Played"));
            entries.Add(new(queue, Heading: playbackOrder.Ended ? "Playback finished" : "Now playing"));
            if (!playbackOrder.Ended) entries.Add(new(queue, queueIndex, Current: true));
            entries.Add(new(queue, Heading: "Up next"));
            entries.AddRange(playbackOrder.RemainingOrder.Select((index, offset) => new QueueEntry(queue, index, PendingOffset: offset)));
            entries.Add(new(queue, Heading: "End of queue"));
        }
        else entries.Add(new(queue, Heading: "Up next"));
        list.ItemsSource = entries;
        renderedQueue = queue; renderedQueueCount = queue.Count; renderedQueueState = state;
        renderedSelection = entries.FindIndex(x => x.Current);
        list.SelectedIndex = renderedSelection;
    }
    private void UpdateTransport()
    {
        if (closed) return;
        bool playing = player.PlaybackSession.PlaybackState == MediaPlaybackState.Playing;
        C<PathIcon>("PlayerPlayIcon").Visibility = playing ? Visibility.Collapsed : Visibility.Visible;
        C<PathIcon>("PlayerPauseIcon").Visibility = playing ? Visibility.Visible : Visibility.Collapsed;
        AutomationProperties.SetName(C<Button>("PlayButton"), playing ? "Pause" : "Play");
        bool enabled = queueIndex >= 0;
        foreach (var name in new[] { "PlayButton", "PreviousButton", "NextButton" }) C<Button>(name).IsEnabled = enabled;
        UpdateTaskbarPlayback();
    }
    private void UpdateTaskbarPlayback()
    {
        if (closed) return;
        bool enabled = queueIndex >= 0;
        taskbarPlayback?.Update(enabled, playIntended);
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
            if (!playbackInterrupted) interruptedPosition = position;
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
            if (tickCount % 15 == 0 && cacheTask.IsCompleted && !offlineSettings.Offline) { ScheduleCache(); if (playbackInterrupted) _ = ReconnectAsync(); }
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
            EnsurePlaybackOrder();
            var snapshot = new PlaybackSnapshot(connection.Id, queue.Select(ApplyConfirmedRating).ToArray(), queueIndex, restoredPosition > 0 ? restoredPosition : playbackInterrupted ? interruptedPosition : player.PlaybackSession.Position.TotalSeconds, false, C<ToggleButton>("RepeatButton").IsChecked == true)
            { RemainingOrder = playbackOrder?.RemainingOrder };
            sessions.SavePlayback(snapshot.WithoutTransientArtwork());
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
            playbackOrder = null;
            playbackOrder = PlaybackOrder.Restore(saved);
            C<ToggleButton>("RepeatButton").IsChecked = saved.Repeat;
            SelectTrack(saved.Index, false, double.IsFinite(saved.PositionSeconds) ? saved.PositionSeconds : 0);
        }
        catch { ShowError("The saved queue could not be restored. Your server connection is still available."); }
    }
    private void StopPlayback()
    {
        playIntended = false; playbackInterrupted = false; interruptedPosition = 0;
        activeSongId = previousSongId = null;
        playbackOrder = null; playedExpanded = false;
        playbackVersion++;
        if (startedHandler is not null) { player.PlaybackSession.PlaybackStateChanged -= startedHandler; startedHandler = null; }
        if (openedHandler is not null) { player.MediaOpened -= openedHandler; openedHandler = null; }
        player.Pause(); player.Source = null; mediaSource?.Dispose(); mediaSource = null; localStream?.Dispose(); localStream = null;
        var storage = cache;
        storage?.SetProtectedSongs([]);
        queue.Clear(); queueIndex = -1; restoredPosition = 0;
        UpdateTrackDeviceBadge(null);
        C<TextBlock>("TrackTitle").Text = "Nothing playing"; C<TextBlock>("TrackArtist").Text = ""; C<Image>("CurrentArt").Source = null;
        UpdatePlayerRating();
        RenderQueue(); UpdateTransport();
    }

    private async Task ChooseOutputAsync()
    {
        var button = C<Button>("OutputButton"); button.IsEnabled = false;
        bool active = true, busy = false, syncing = false;
        var routes = new List<RadioButton>();
        var devicesPanel = new StackPanel { Spacing = 8 };
        var status = new TextBlock { TextWrapping = TextWrapping.Wrap, Foreground = Muted };
        AutomationProperties.SetLiveSetting(status, Microsoft.UI.Xaml.Automation.Peers.AutomationLiveSetting.Polite);
        var refresh = new Button { Content = "Refresh", Foreground = Sage, Background = new SolidColorBrush(Colors.Transparent), BorderThickness = new Thickness(0), MinHeight = 48 };
        var settings = new Button { Content = "Device settings", Foreground = Sage, Background = new SolidColorBrush(Colors.Transparent), BorderThickness = new Thickness(0), MinHeight = 48 };
        var dialog = new ContentDialog { XamlRoot = root.XamlRoot, RequestedTheme = ElementTheme.Dark, Background = new SolidColorBrush(ColorHelper.FromArgb(255, 36, 47, 40)), CornerRadius = new CornerRadius(24) };
        AutomationProperties.SetName(dialog, "Media output");
        dialog.Closing += (_, _) => active = false;
        void SetBusy(bool value)
        {
            busy = value;
            refresh.IsEnabled = settings.IsEnabled = !value;
            foreach (var route in routes) route.IsEnabled = !value;
        }
        void ConfirmSelection()
        {
            syncing = true;
            try { foreach (var route in routes) route.IsChecked = (route.Tag as string) == player.AudioDevice?.Id; }
            finally { syncing = false; }
        }
        async Task SelectAsync(DeviceInformation? target)
        {
            if (!active || closed || busy || syncing) return;
            SetBusy(true);
            // Never display a requested route as selected before MediaPlayer confirms it.
            ConfirmSelection();
            status.Text = "Switching audio output…";
            try
            {
                // Re-query before assigning: a device may disappear while the chooser is open.
                if (target is not null)
                {
                    var currentDevices = await DeviceInformation.FindAllAsync(MediaDevice.GetAudioRenderSelector());
                    if (!active || closed) return;
                    target = currentDevices.FirstOrDefault(d => d.Id == target.Id && d.IsEnabled);
                    if (target is null) { status.Text = "That output is no longer available. The current output was kept."; return; }
                }
                if (!active || closed) return;
                player.AudioDevice = target;
                if (player.AudioDevice?.Id != target?.Id) { status.Text = "Windows did not confirm the selected output."; return; }
                C<TextBlock>("OutputName").Text = target?.Name ?? "System default output";
                AutomationProperties.SetName(button, "Choose audio output, " + C<TextBlock>("OutputName").Text);
                ConfirmSelection();
                dialog.Hide();
            }
            catch { if (active && !closed) status.Text = "Audio output selection is unavailable. Refresh or try device settings."; }
            finally { if (active && !closed) { ConfirmSelection(); SetBusy(false); } }
        }
        void AddRoute(DeviceInformation? device)
        {
            string name = device?.Name ?? "System default output";
            var labels = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
            labels.Children.Add(new TextBlock { Text = name, FontSize = 14, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, TextWrapping = TextWrapping.Wrap, MaxLines = 2, TextTrimming = TextTrimming.CharacterEllipsis });
            labels.Children.Add(new TextBlock { Text = device is null ? "Follow Windows sound settings" : "Audio device", FontSize = 13, Foreground = Muted });
            var row = new Grid { ColumnSpacing = 12 };
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            row.Children.Add(new FontIcon { Glyph = "\uE7F4", FontSize = 22, Foreground = Sage, VerticalAlignment = VerticalAlignment.Center });
            Grid.SetColumn(labels, 1); row.Children.Add(labels);
            var route = new RadioButton { Content = row, Tag = device?.Id, GroupName = "MediaOutput", Style = (Style)root.Resources["OutputRouteStyle"], IsChecked = device?.Id == player.AudioDevice?.Id };
            AutomationProperties.SetName(route, name);
            ToolTipService.SetToolTip(route, name);
            routes.Add(route); devicesPanel.Children.Add(route);
            route.Checked += async (_, _) => await SelectAsync(device);
            route.Click += (_, _) => { if (active && !closed && !busy && device?.Id == player.AudioDevice?.Id) dialog.Hide(); };
        }
        async Task RefreshAsync()
        {
            if (!active || closed || busy) return;
            SetBusy(true); status.Text = "Looking for audio outputs…";
            try
            {
                var devices = await DeviceInformation.FindAllAsync(MediaDevice.GetAudioRenderSelector());
                if (!active || closed) return;
                routes.Clear(); devicesPanel.Children.Clear();
                AddRoute(null);
                foreach (var device in devices.Where(d => d.IsEnabled)) AddRoute(device);
                status.Text = devices.Any(d => d.IsEnabled) ? "" : "Windows returned no audio output devices. Connect an output and refresh.";
            }
            catch { if (active && !closed) status.Text = "Couldn't read audio outputs. Try Refresh or device settings."; }
            finally { if (active && !closed) SetBusy(false); }
        }
        try
        {
            var content = new StackPanel { Spacing = 12, Width = Math.Min(380, Math.Max(0, root.XamlRoot.Size.Width - 96)) };
            var header = new Grid();
            header.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            header.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            header.Children.Add(new TextBlock { Text = "Media output", FontSize = 24, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, VerticalAlignment = VerticalAlignment.Center });
            var close = new Button { Content = "\uE8BB", Style = (Style)root.Resources["TransportButtonStyle"], MinWidth = 48, MinHeight = 48, Width = 48, Height = 48 };
            AutomationProperties.SetName(close, "Close media output");
            close.Click += (_, _) => dialog.Hide();
            Grid.SetColumn(close, 1); header.Children.Add(close); content.Children.Add(header);
            content.Children.Add(new TextBlock { Text = "Choose where to play your music.", Foreground = Muted, TextWrapping = TextWrapping.Wrap });
            content.Children.Add(new ScrollViewer { Content = devicesPanel, MaxHeight = Math.Min(420, root.XamlRoot.Size.Height * .5), HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled, VerticalScrollBarVisibility = ScrollBarVisibility.Auto });
            content.Children.Add(status);
            var footer = new Grid();
            footer.Children.Add(settings); settings.HorizontalAlignment = HorizontalAlignment.Left;
            footer.Children.Add(refresh); refresh.HorizontalAlignment = HorizontalAlignment.Right;
            content.Children.Add(footer); dialog.Content = content;
            refresh.Click += async (_, _) => await RefreshAsync();
            settings.Click += async (_, _) =>
            {
                try { if (!await Windows.System.Launcher.LaunchUriAsync(new Uri("ms-settings:sound")) && active && !closed) status.Text = "Windows sound settings are unavailable."; }
                catch { if (active && !closed) status.Text = "Windows sound settings are unavailable."; }
            };
            dialog.Opened += async (_, _) => await RefreshAsync();
            await dialog.ShowAsync();
        }
        catch { if (!closed) ShowError("Audio output selection is unavailable. Windows system output remains in control; no switch was reported."); }
        finally
        {
            active = false;
            if (!closed) { button.IsEnabled = true; button.Focus(FocusState.Programmatic); }
        }
    }

    private void Shutdown()
    {
        if (closed) return;
        updatesPanel.Dispose();
        taskbarPlayback?.Dispose();
        SavePlayback(); closed = true;
        ShutdownOffline();
        timer.Stop(); browseGate.Dispose(); connectGate.Dispose(); testGate.Dispose(); artworkGate.Dispose(); playingArtGate.Dispose(); connectionGate.Dispose();
        if (openedHandler is not null) player.MediaOpened -= openedHandler;
        player.Pause(); player.Source = null; mediaSource?.Dispose(); localStream?.Dispose(); player.Dispose(); client?.Dispose(); sessions?.Dispose();
    }
}
