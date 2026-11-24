using System.Text.Json;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Controls;
using Music.Core;
using Windows.Networking.Connectivity;
using ConnectionProfile = Music.Core.ConnectionProfile;

namespace Music.App;

public sealed partial class MainWindow
{
    private OfflineSettings offlineSettings = new();
    private SongCache? cache;
    private CancellationTokenSource cacheCancellation = new();
    private Task cacheTask = Task.CompletedTask;
    private readonly BackgroundStorage storageWork = new();
    private Task cacheInitialization = Task.CompletedTask;
    private readonly Dictionary<string, List<WeakReference<Border>>> songBadges = new(StringComparer.Ordinal);
    private readonly List<(string Key, int Count, WeakReference<Border> Badge)> collectionBadges = [];
    private readonly Dictionary<string, IReadOnlyList<Song>> verifiedCollectionMembership = new(StringComparer.Ordinal);
    private bool deviceActionRunning;
    private readonly HashSet<string> changedCacheSongs = new(StringComparer.Ordinal);
    private IReadOnlyDictionary<string, CachedSong> renderedCacheSnapshot = new Dictionary<string, CachedSong>();
    private Microsoft.UI.Dispatching.DispatcherQueueTimer? cacheRefreshTimer;
    private ListViewBase? offlineSongsView;
    private readonly List<Song> offlineRows = [];
    private readonly List<Song> offlinePlaybackSongs = [];
    private TextBlock? offlineEmpty;
    private int cacheMaintenance;
    private bool playIntended, playbackInterrupted, offlineOnly, offlineInitialized;
    private double interruptedPosition;
    private bool localPlayback;
    private PlaybackOrder? playbackOrder;
    private string? activeSongId, previousSongId;
    private TextBlock? storageUsage;
    private string SettingsPath => Path.Combine(profiles!.DirectoryPath, "offline.json");

    private void InitializeOfflineSettings()
    {
        if (offlineInitialized) return;
        offlineInitialized = true;
        try { offlineSettings = JsonSerializer.Deserialize(File.ReadAllText(SettingsPath), PersistenceJsonContext.Default.OfflineSettings) ?? new(); }
        catch (Exception e) when (e is IOException or JsonException) { offlineSettings = new(); }
        offlineSettings = offlineSettings with
        {
            CacheLimitBytes = Math.Clamp(offlineSettings.CacheLimitBytes, 1024L * 1024, 1024L * 1024 * 1024 * 1024),
            NextCount = new[] { 0, 1, 3, 5, 10 }.Contains(offlineSettings.NextCount) ? offlineSettings.NextCount : 3
        };
        var section = new StackPanel { Spacing = 12 };
        section.Children.Add(Text("Storage & offline", 21));
        var offline = new ToggleSwitch { Header = "Offline mode — no server playback or browsing", IsOn = offlineSettings.Offline };
        var played = new ToggleSwitch { Header = "Cache played songs automatically", IsOn = offlineSettings.SavePlayed };
        var mobile = new ToggleSwitch { Header = "Allow upcoming songs on mobile / metered networks", IsOn = offlineSettings.MobilePrefetch };
        var limit = new ComboBox { Header = "Automatic cache limit", HorizontalAlignment = HorizontalAlignment.Stretch };
        long mb = 1024L * 1024;
        long[] limits = [500 * mb, 1024 * mb, 2048 * mb, 5120 * mb];
        foreach (var label in new[] { "500 MB", "1 GB", "2 GB", "5 GB", "Custom" }) limit.Items.Add(label);
        int selected = Array.IndexOf(limits, offlineSettings.CacheLimitBytes); limit.SelectedIndex = selected >= 0 ? selected : 4;
        var custom = new NumberBox { Header = "Custom cache limit (MB)", Minimum = 1, Maximum = 1048576, Value = offlineSettings.CacheLimitBytes / (double)mb, Visibility = limit.SelectedIndex == 4 ? Visibility.Visible : Visibility.Collapsed };
        var next = new ComboBox { Header = "Prefetch upcoming songs (unmetered Wi-Fi by default)", HorizontalAlignment = HorizontalAlignment.Stretch };
        int[] counts = [0, 1, 3, 5, 10]; foreach (int count in counts) next.Items.Add(count == 0 ? "Off" : count.ToString());
        next.SelectedIndex = Array.IndexOf(counts, offlineSettings.NextCount);
        storageUsage = Text("", 13, true); storageUsage.TextWrapping = TextWrapping.Wrap;
        var clear = new Button { Content = "Clear automatic cache" };
        var recent = new Button { Content = "Offline & recent songs" };
        var removeAccount = new Button { Content = "Remove account and all saved songs" };
        foreach (var control in new FrameworkElement[] { offline, played, limit, custom, next, mobile, storageUsage, clear, recent, removeAccount }) section.Children.Add(control);
        section.Children.Add(new TextBlock { Text = "Saved songs play offline and stay on this device until removed. Cached songs play offline too, but can be cleared automatically. Streamed songs need a connection. Saved songs are not included in the cache limit. Active and previous songs are protected; usage can temporarily exceed the limit. Incomplete transfers retry from the beginning. Current-song saving uses your playback connection; the network restriction applies to prefetch only.", TextWrapping = TextWrapping.Wrap, Foreground = Muted });
        C<StackPanel>("SettingsPanel").Children.Insert(2, section);
        offline.Toggled += async (_, _) => await SetOfflineAsync(offline.IsOn);
        played.Toggled += (_, _) => { offlineSettings = offlineSettings with { SavePlayed = played.IsOn }; SaveOfflineSettings(); ScheduleCache(); };
        mobile.Toggled += (_, _) => { offlineSettings = offlineSettings with { MobilePrefetch = mobile.IsOn }; SaveOfflineSettings(); ScheduleCache(); };
        next.SelectionChanged += (_, _) => { if (next.SelectedIndex < 0) return; offlineSettings = offlineSettings with { NextCount = counts[next.SelectedIndex] }; SaveOfflineSettings(); ScheduleCache(); };
        limit.SelectionChanged += (_, _) =>
        {
            custom.Visibility = limit.SelectedIndex == 4 ? Visibility.Visible : Visibility.Collapsed;
            if (limit.SelectedIndex is >= 0 and < 4) { offlineSettings = offlineSettings with { CacheLimitBytes = limits[limit.SelectedIndex] }; SaveOfflineSettings(); _ = TrimCacheAsync(false); }
        };
        custom.ValueChanged += (_, _) =>
        {
            if (limit.SelectedIndex != 4 || !double.IsFinite(custom.Value) || custom.Value < 1 || custom.Value > 1048576) return;
            offlineSettings = offlineSettings with { CacheLimitBytes = (long)(custom.Value * mb) }; SaveOfflineSettings(); _ = TrimCacheAsync(false);
        };
        clear.Click += async (_, _) => await TrimCacheAsync(true);
        recent.Click += async (_, _) => await NavigateAsync("offline");
        removeAccount.Click += async (_, _) => await RemoveAccountAsync();
        if (C<Button>("SongsNav").Parent is StackPanel navigation)
        {
            var button = new Button { Content = "Offline & recent", Style = C<Button>("SongsNav").Style };
            button.Click += async (_, _) => await NavigateAsync("offline"); navigation.Children.Add(button);
        }
        NetworkInformation.NetworkStatusChanged += NetworkChanged;
        C<Button>("ConnectButton").IsEnabled = !offlineSettings.Offline;
    }
    private void SaveOfflineSettings()
    {
        try
        {
            File.WriteAllText(SettingsPath + ".tmp", JsonSerializer.Serialize(offlineSettings, PersistenceJsonContext.Default.OfflineSettings));
            File.Move(SettingsPath + ".tmp", SettingsPath, true);
        }
        catch { ShowError("Could not save storage preferences. They may reset after reopening."); }
    }
    private async Task ActivateCache(ConnectionProfile profile)
    {
        cacheCancellation.Cancel();
        var old = cache; cache = null; songBadges.Clear(); collectionBadges.Clear();
        InvalidateCollectionMembership();
        changedCacheSongs.Clear(); renderedCacheSnapshot = new Dictionary<string, CachedSong>();
        var owner = connectionLease;
        if (profiles is null) return;
        // Account identity is stable across credential refresh, but case-sensitive for usernames.
        string account = new Uri(profile.ServerUrl.TrimEnd('/') + "/").AbsoluteUri + "\n" + profile.Username;
        try
        {
            string root = Path.Combine(profiles.DirectoryPath, "media");
            await cacheTask;
            var created = await storageWork.RunAsync(() => { old?.Dispose(); return new SongCache(root, account); });
            if (closed || !connectionGate.IsCurrent(owner))
            { await storageWork.RunAsync(created.Dispose); return; }
            cache = created;
        }
        catch { if (!closed && connectionGate.IsCurrent(owner)) ShowError("Song storage is unavailable. Streaming still works while online. Check free disk space and folder permissions, then reconnect to retry storage."); }
        QueueCacheRefresh("");
        RefreshStorageUsage();
    }
    private async Task PurgeAccountCacheAsync(ConnectionProfile profile)
    {
        // An account still opening may have no attached cache. Drain initialization
        // and purge by explicit account identity, never by a nullable UI attachment.
        await cacheInitialization;
        string root = Path.Combine(profiles!.DirectoryPath, "media");
        string account = new Uri(profile.ServerUrl.TrimEnd('/') + "/").AbsoluteUri + "\n" + profile.Username;
        await storageWork.RunAsync(() =>
        {
            using var storage = new SongCache(root, account);
            storage.Purge();
        });
    }
    private async Task SetOfflineAsync(bool value)
    {
        navigationHistory.Clear(); detailCollection = null;
        offlineSettings = offlineSettings with { Offline = value };
        SaveOfflineSettings();
        browseGate.Cancel(); artworkGate.Cancel(); playingArtGate.Cancel(); connectGate.Cancel(); testGate.Cancel();
        if (client is not null) client.Offline = value;
        RefreshRatingViews();
        C<Button>("ConnectButton").IsEnabled = !value;
        C<Button>("TestConnectionButton").IsEnabled = !value && client is not null;
        cacheCancellation.Cancel();
        if (queueIndex >= 0)
        {
            double position = restoredPosition > 0 ? restoredPosition : player.PlaybackSession.Position.TotalSeconds;
            bool intended = playIntended;
            SelectTrack(queueIndex, intended, position);
        }
        if (!value) { ScheduleCache(); _ = ReconnectAsync(); }
        await NavigateAsync(value ? "offline" : "library", remember: false);
    }
    private void RefreshStorageUsage()
    {
        if (storageUsage is null) return;
        var entries = cache?.Snapshot().Values.ToArray() ?? [];
        string Usage(bool pin)
        {
            var items = entries.Where(x => x.Pinned == pin).ToArray();
            return $"{items.Sum(x => x.Bytes) / (1024d * 1024):F1} MB · {items.Count(x => x.Complete)} complete · {items.Count(x => !x.Complete)} incomplete";
        }
        storageUsage.Text = $"Automatic cache: {Usage(false)}\nSaved to Device: {Usage(true)}";
    }
    private async Task TrimCacheAsync(bool clear)
    {
        var storage = cache;
        cacheMaintenance++;
        cacheCancellation.Cancel();
        try
        {
            await cacheTask;
            if (storage is null || cache != storage || closed) return;
            var protect = ProtectedTracks().ToArray();
            long limit = offlineSettings.CacheLimitBytes;
            await storageWork.RunAsync(() => { if (clear) storage.ClearAutomatic(protect); else storage.Trim(limit, protect); });
            if (closed || cache != storage) return;
            QueueCacheRefresh("");
            RefreshStorageUsage();
        }
        catch { ShowError("Could not clear storage. Active playback files have been kept."); }
        finally { cacheMaintenance--; }
        if (!clear) ScheduleCache();
    }
    private void RenderOfflineBrowse()
    {
        var panel = C<StackPanel>("BrowsePanel"); panel.Children.Clear();
        panel.Children.Add(Text("Offline & recent", 21));
        var filter = new CheckBox { Content = "Available offline only", IsChecked = offlineOnly };
        filter.Click += (_, _) => { offlineOnly = filter.IsChecked == true; RenderOfflineBrowse(); };
        panel.Children.Add(filter);
        offlineRows.Clear();
        offlineEmpty = Text("No songs match. Play or save songs while connected to make them available offline.", 14, true);
        panel.Children.Add(offlineEmpty);
        AddSongs(panel, offlineRows, "list", CancellationToken.None, offlinePlaybackSongs);
        offlineSongsView = panel.Children.OfType<ListViewBase>().Last();
        RefreshOfflineRows();
    }
    private void RefreshOfflineRows()
    {
        if (offlineSongsView is null || !C<StackPanel>("BrowsePanel").Children.Contains(offlineSongsView)) return;
        var found = (cache?.Snapshot().Values ?? []).Where(x => (!offlineOnly || x.Complete) &&
            ($"{x.Song.Title} {x.Song.Artist} {x.Song.Album}").Contains(query, StringComparison.OrdinalIgnoreCase))
            .OrderByDescending(x => x.Accessed).Select(x => x.Song).ToArray();
        var wanted = found.Select(x => x.Id).ToHashSet(StringComparer.Ordinal);
        bool removed = false;
        for (int i = offlineRows.Count - 1; i >= 0; i--)
            if (!wanted.Contains(offlineRows[i].Id)) { offlineRows.RemoveAt(i); offlineSongsView.Items.RemoveAt(i); removed = true; }
        var displayed = offlineRows.Select(x => x.Id).ToHashSet(StringComparer.Ordinal);
        int added = 0;
        foreach (var song in found)
        {
            if (displayed.Contains(song.Id)) continue;
            // Bound new control creation so large download lists yield between batches.
            if (added++ == 50) { QueueCacheRefresh(song.Id); break; }
            offlineSongsView.Items.Add(SongRow(song, offlineRows.Count, "list", CancellationToken.None, connectionLease));
            offlineRows.Add(song);
            displayed.Add(song.Id);
        }
        offlinePlaybackSongs.Clear();
        offlinePlaybackSongs.AddRange(offlineRows);
        offlinePlaybackSongs.AddRange(found.Where(song => !displayed.Contains(song.Id)));
        if (removed)
            for (int i = 0; i < offlineRows.Count; i++)
                if (offlineSongsView.Items[i] is Grid row)
                { row.Tag = i; if (row.Children[0] is TextBlock number) number.Text = (i + 1).ToString(); }
        if (offlineEmpty is not null) offlineEmpty.Visibility = found.Length == 0 ? Visibility.Visible : Visibility.Collapsed;
    }
    private Button CreateDeviceActions(string title, double size, Func<bool> current,
        Func<IReadOnlyDictionary<string, CachedSong>, bool> requested, Func<Task> save, Func<Task> remove)
    {
        var button = new Button { Content = new SymbolIcon(Symbol.More), Width = size, Height = size,
            MinWidth = 0, MinHeight = 0, Padding = new Thickness(0), BorderThickness = new Thickness(0),
            CornerRadius = new CornerRadius(size / 2), VerticalAlignment = VerticalAlignment.Center,
            Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent), IsEnabled = current() };
        string name = "Device actions for " + title;
        AutomationProperties.SetName(button, name);
        ToolTipService.SetToolTip(button, name);
        var menu = new MenuFlyout();
        var action = new MenuFlyoutItem();
        bool removing = false;
        menu.Items.Add(action);
        button.Flyout = menu;
        menu.Opening += (_, _) =>
        {
            removing = requested(CacheSnapshot());
            action.Text = removing ? "Remove from Device" : "Save to Device";
            action.IsEnabled = !deviceActionRunning && current() && cache is not null;
        };
        action.Click += async (_, _) =>
        {
            if (deviceActionRunning || !current() || cache is null || removing != requested(CacheSnapshot())) return;
            deviceActionRunning = true;
            try { if (removing) await remove(); else await save(); }
            finally { deviceActionRunning = false; }
        };
        return button;
    }

    private void AddSongDeviceActions(FrameworkElement row, Song song, RequestGate.Lease owner)
    {
        if (row is not Grid grid) return;
        var actions = CreateDeviceActions(song.Title, 32,
            () => !closed && !accountChangePending && connectionGate.IsCurrent(owner),
            snapshot => snapshot.TryGetValue(song.Id, out var entry) && entry.Pinned,
            () => SaveToDeviceAsync([song], "", owner), () => RemoveFromDeviceAsync(song.Id, null, owner));
        if (page != "offline" && !offlineSettings.Offline)
        {
            AddSongPlaylistAction(row, song, owner, (MenuFlyout)actions.Flyout);
            AutomationProperties.SetName(actions, "Actions for " + song.Title);
            ToolTipService.SetToolTip(actions, "Actions for " + song.Title);
        }
        if (grid.ColumnDefinitions.Count == 0) // Song tile: keep the whole title width below the artwork.
        {
            actions.VerticalAlignment = VerticalAlignment.Top;
            actions.Margin = new Thickness(0, 4, 4, 0);
            actions.Background = new SolidColorBrush(Microsoft.UI.ColorHelper.FromArgb(235, 36, 44, 40));
        }
        else Grid.SetColumn(actions, grid.ColumnDefinitions.Count - 1);
        actions.HorizontalAlignment = HorizontalAlignment.Right;
        grid.Children.Add(actions);
    }

    private async Task SaveToDeviceAsync(IReadOnlyList<Song> tracks, string key, RequestGate.Lease owner)
    {
        var storage = cache;
        if (storage is null || !connectionGate.IsCurrent(owner)) return;
        if (offlineSettings.Offline && tracks.Any(song => !HasSavedSong(song.Id)))
        { ShowError("Turn off Offline mode to save songs that are not cached yet."); return; }
        try { await storageWork.RunAsync(() => storage.Pin(tracks, key)); }
        catch { if (!closed && storage == cache && connectionGate.IsCurrent(owner)) ShowError("Could not save to this device. Check free disk space and retry."); return; }
        if (closed || storage != cache || !connectionGate.IsCurrent(owner)) return;
        QueueCacheRefresh(tracks[0].Id);
        ScheduleCache();
    }

    private async Task RemoveFromDeviceAsync(string? songId, string? collectionKey, RequestGate.Lease owner)
    {
        var storage = cache;
        if (storage is null || !connectionGate.IsCurrent(owner)) return;
        cacheMaintenance++;
        cacheCancellation.Cancel();
        try
        {
            await cacheTask;
            if (closed || storage != cache || !connectionGate.IsCurrent(owner)) return;
            bool removed = await storageWork.RunAsync(() => collectionKey is not null
                ? storage.RemoveCollection(collectionKey) : storage.RemoveDownload(songId!));
            if (closed || storage != cache || !connectionGate.IsCurrent(owner)) return;
            if (!removed) ShowError("The current or previous song is protected. Change songs before removing its copy from this device.");
        }
        catch { if (!closed && storage == cache && connectionGate.IsCurrent(owner)) ShowError("Could not fully remove the saved music from this device."); }
        finally
        {
            cacheMaintenance--;
            if (!closed && storage == cache && connectionGate.IsCurrent(owner))
            {
                QueueCacheRefresh(songId ?? "");
                ScheduleCache();
            }
        }
    }
    private void NetworkChanged(object sender) => DispatcherQueue.TryEnqueue(() =>
    {
        if (!closed && !offlineSettings.Offline) { ScheduleCache(); _ = ReconnectAsync(); }
    });
    private bool reconnecting;
    private async Task ReconnectAsync()
    {
        if (closed || offlineSettings.Offline || client is null || reconnecting) return;
        var target = client; var owner = connectionLease; reconnecting = true;
        try
        {
            await target.PingAsync(owner.Token);
            if (closed || offlineSettings.Offline || client != target || !connectionGate.IsCurrent(owner)) return;
            if (playbackInterrupted && playIntended && queueIndex >= 0) SelectTrack(queueIndex, true, interruptedPosition);
            long catalogRevision = playlistCatalogRevision;
            var data = await ReadLibraryAsync(target, owner.Token);
            if (closed || offlineSettings.Offline || client != target || !connectionGate.IsCurrent(owner)) return;
            albums = data.Albums;
            playlists = MergePlaylistCatalog(data.Playlists, catalogRevision);
            InvalidateCollectionMembership();
            if (page is not "offline" and not "settings" and not "detail") await LoadBrowseAsync();
        }
        catch { /* No surprise replay after a failed reconnect. */ }
        finally { reconnecting = false; }
    }
    private async Task RemoveAccountAsync()
    {
        if (connection is null || profiles is null || accountChangePending) return;
        var profile = connection;
        var dialog = new ContentDialog { Title = "Remove account?", Content = "This deletes all automatic cache, saved songs and the saved queue for this account.", PrimaryButtonText = "Remove", CloseButtonText = "Cancel", XamlRoot = root.XamlRoot, DefaultButton = ContentDialogButton.Close };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;
        if (closed || connection != profile || accountChangePending) return;
        accountChangePending = true;
        connectGate.Cancel(); browseGate.Cancel(); testGate.Cancel(); artworkGate.Cancel(); playingArtGate.Cancel(); connectionGate.Cancel();
        RefreshRatingViews();
        cacheMaintenance++;
        try
        {
            cacheCancellation.Cancel(); await cacheTask;
            if (closed || connection != profile) return;
            StopPlayback();
            var storage = cache;
            try { await PurgeAccountCacheAsync(profile); sessions?.RemovePlayback(profile.Id); profiles.Remove(profile); }
            catch { ShowError("Account removal did not finish. Retry before closing the app."); return; }
            await storageWork.RunAsync(() => storage?.Dispose());
            if (closed || connection != profile) return;
            cache = null; client?.Dispose(); client = null; connection = null;
            ResetRatings(null);
            navigationHistory.Clear(); detailCollection = null;
            albums = []; playlists = []; artists = []; artistsLoaded = false; songs.Clear(); artwork.Clear();
            playlistEdits.Clear();
            unverifiedPlaylistIds.Clear();
            InvalidateCollectionMembership();
            C<TextBlock>("ConnectionInfo").Text = "No saved connection.";
            C<TextBlock>("ConnectionStatus").Text = "Account and saved songs removed.";
            RefreshStorageUsage(); await NavigateAsync("settings", remember: false);
        }
        finally { accountChangePending = false; cacheMaintenance--; if (!closed) SetConnecting(false); }
    }

    private static readonly IReadOnlyDictionary<string, CachedSong> EmptyCache = new Dictionary<string, CachedSong>();
    private IReadOnlyDictionary<string, CachedSong> CacheSnapshot() => cache?.Snapshot() ?? EmptyCache;
    private bool HasSavedSong(string id) => DeviceAvailability.Song(CacheSnapshot(), id) != DeviceSongState.Streamed;

    private static void ConfigureSavedBadge(Border badge, double size)
    {
        badge.Width = badge.Height = size;
        badge.CornerRadius = new CornerRadius(size / 2);
        badge.Background = Sage;
        badge.VerticalAlignment = VerticalAlignment.Center;
        badge.Child = new FontIcon { Glyph = "\uE8FB", FontSize = size * .68,
            Foreground = new SolidColorBrush(Microsoft.UI.ColorHelper.FromArgb(255, 36, 52, 31)) };
        AutomationProperties.SetAccessibilityView(badge.Child, Microsoft.UI.Xaml.Automation.Peers.AccessibilityView.Raw);
        AutomationProperties.SetName(badge, "Saved to Device");
        ToolTipService.SetToolTip(badge, "Saved to Device");
    }

    private static void UpdateSongBadge(Border badge, string? id, IReadOnlyDictionary<string, CachedSong> snapshot)
    {
        var state = id is null ? DeviceSongState.Streamed : DeviceAvailability.Song(snapshot, id);
        badge.Visibility = state == DeviceSongState.Saved ? Visibility.Visible : Visibility.Collapsed;
        if (badge.Tag is TextBlock title)
            AutomationProperties.SetHelpText(title, id is null ? "" : state == DeviceSongState.Cached
                ? "Cached: available offline but can be cleared automatically." : state.ToString());
    }

    private static Grid TitleWithBadge(TextBlock title, Border badge)
    {
        var line = new Grid { HorizontalAlignment = HorizontalAlignment.Left };
        line.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        line.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        badge.Margin = new Thickness(6, 0, 0, 0);
        Grid.SetColumn(badge, 1);
        line.Children.Add(title);
        line.Children.Add(badge);
        return line;
    }

    private Grid SongTitle(Song song, double size, bool current = false)
    {
        var title = Text(song.Title, size);
        if (current) title.Foreground = Sage;
        var badge = new Border { Tag = title };
        ConfigureSavedBadge(badge, 16);
        UpdateSongBadge(badge, song.Id, CacheSnapshot());
        if (!songBadges.TryGetValue(song.Id, out var badges)) songBadges[song.Id] = badges = [];
        badges.RemoveAll(x => !x.TryGetTarget(out _));
        badges.Add(new(badge));
        return TitleWithBadge(title, badge);
    }

    private FrameworkElement CardTitle(Card card)
    {
        var title = Text(card.Title, 14);
        var (key, count) = card.Value switch
        {
            Album album => ("album:" + album.Id, album.SongCount),
            Playlist playlist => ("playlist:" + playlist.Id, playlist.SongCount),
            _ => ("", 0)
        };
        if (count <= 0) return title;
        var badge = new Border();
        ConfigureSavedBadge(badge, 16);
        UpdateCardBadge(badge, key, count, CacheSnapshot());
        collectionBadges.Add((key, count, new(badge)));
        return TitleWithBadge(title, badge);
    }

    private void RememberCollectionMembership((string Kind, string Id) identity, IReadOnlyList<Song>? tracks)
    {
        string key = identity.Kind + ":" + identity.Id;
        if (tracks is null) verifiedCollectionMembership.Remove(key);
        else verifiedCollectionMembership[key] = tracks;
    }

    private void InvalidateCollectionMembership() => verifiedCollectionMembership.Clear();

    private void UpdateCardBadge(Border badge, string key, int count, IReadOnlyDictionary<string, CachedSong> snapshot)
        => badge.Visibility = DeviceAvailability.SavedCard(snapshot, key, count, verifiedCollectionMembership.GetValueOrDefault(key))
            ? Visibility.Visible : Visibility.Collapsed;

    private void UpdateCollectionDeviceBadge()
    {
        bool saved = page == "detail" && detailIdentity.Kind is "album" or "playlist" && !detailLoading && !detailError &&
            DeviceAvailability.Collection(CacheSnapshot(), detailIdentity.Kind + ":" + detailIdentity.Id, detailSongs).Saved;
        C<Border>("CollectionDeviceBadge").Visibility = saved ? Visibility.Visible : Visibility.Collapsed;
    }

    private void UpdateTrackDeviceBadge(Song? song) => UpdateSongBadge(C<Border>("TrackDeviceBadge"), song?.Id, CacheSnapshot());

    private void QueueCacheRefresh(string id)
    {
        changedCacheSongs.Add(id);
        if (cacheRefreshTimer is null)
        {
            cacheRefreshTimer = DispatcherQueue.CreateTimer();
            cacheRefreshTimer.Interval = TimeSpan.FromMilliseconds(250);
            cacheRefreshTimer.IsRepeating = false;
            cacheRefreshTimer.Tick += (_, _) =>
            {
                cacheRefreshTimer.Stop();
                if (closed) return;
                var snapshot = CacheSnapshot();
                foreach (var entry in snapshot)
                    if (!renderedCacheSnapshot.TryGetValue(entry.Key, out var prior) || prior.Complete != entry.Value.Complete || prior.Pinned != entry.Value.Pinned)
                        changedCacheSongs.Add(entry.Key);
                foreach (var id in renderedCacheSnapshot.Keys)
                    if (!snapshot.ContainsKey(id)) changedCacheSongs.Add(id);
                renderedCacheSnapshot = snapshot;
                foreach (var changed in changedCacheSongs)
                {
                    if (!songBadges.TryGetValue(changed, out var badges)) continue;
                    badges.RemoveAll(item =>
                    {
                        if (!item.TryGetTarget(out var badge)) return true;
                        UpdateSongBadge(badge, changed, snapshot);
                        return false;
                    });
                    if (badges.Count == 0) songBadges.Remove(changed);
                }
                changedCacheSongs.Clear();
                collectionBadges.RemoveAll(item =>
                {
                    if (!item.Badge.TryGetTarget(out var badge)) return true;
                    UpdateCardBadge(badge, item.Key, item.Count, snapshot);
                    return false;
                });
                RefreshStorageUsage();
                UpdateCollectionDeviceBadge();
                UpdateTrackDeviceBadge(queueIndex >= 0 ? queue[queueIndex] : null);
                if (page == "offline" || offlineSettings.Offline) RefreshOfflineRows();
            };
        }
        if (!cacheRefreshTimer.IsRunning) cacheRefreshTimer.Start();
    }
    private void ShutdownOffline()
    {
        NetworkInformation.NetworkStatusChanged -= NetworkChanged;
        cacheRefreshTimer?.Stop();
        cacheCancellation.Cancel();
        var storage = cache; cache = null;
        _ = storageWork.RunAsync(() => storage?.Dispose());
    }

    private void EnsurePlaybackOrder()
    {
        if (queueIndex < 0) return;
        bool repeat = C<Microsoft.UI.Xaml.Controls.Primitives.ToggleButton>("RepeatButton").IsChecked == true;
        playbackOrder ??= new PlaybackOrder(queue.Count, queueIndex, false, repeat);
        playbackOrder.Configure(false, repeat);
        if (playbackOrder.Current != queueIndex) playbackOrder.Select(queueIndex);
    }
    private void HandoffCompletedSong(string songId, long version)
    {
        if (closed || playbackOrder?.Ended == true || version != playbackVersion || localPlayback || queueIndex < 0 || queue[queueIndex].Id != songId) return;
        double position = restoredPosition > 0 ? restoredPosition : playbackInterrupted ? interruptedPosition : player.PlaybackSession.Position.TotalSeconds;
        var handoff = CacheHandoff.Resolve(songId, version, queue[queueIndex].Id, playbackVersion, localPlayback, closed, position, playIntended);
        if (handoff is not null) SelectTrack(queueIndex, handoff.Play, handoff.PositionSeconds);
    }
    private static string CacheFailureMessage(Exception error) => error switch
    {
        UnauthorizedAccessException => "Song storage is not writable. Check Windows folder permissions and retry saving.",
        IOException => "Could not save this song. Free disk space or clear automatic cache, then retry. It is not available offline yet.",
        _ => "Could not finish saving this song. Check the connection and free disk space, then retry. Incomplete songs are not available offline."
    };
    private bool PrefetchAllowed()
    {
        try
        {
            var network = NetworkInformation.GetInternetConnectionProfile();
            if (network is null) return false;
            var cost = network.GetConnectionCost();
            bool unrestrictedWifi = network.IsWlanConnectionProfile && cost.NetworkCostType == NetworkCostType.Unrestricted && !cost.Roaming && !cost.OverDataLimit && !cost.BackgroundDataUsageRestricted;
            return offlineSettings.CanPrefetch(unrestrictedWifi, network.GetNetworkConnectivityLevel() != NetworkConnectivityLevel.None);
        }
        catch { return false; }
    }
    private IEnumerable<string> ProtectedTracks() => new[] { activeSongId, previousSongId }.OfType<string>();
    private void ScheduleCache()
        => ScheduleCache(allowCurrentHandoff: true);
    private void ScheduleCache(bool allowCurrentHandoff)
    {
        if (cacheMaintenance != 0) return;
        cacheCancellation.Cancel(); cacheCancellation = new();
        if (closed || cache is null || client is null || offlineSettings.Offline) return;
        var storage = cache; var target = client; var token = cacheCancellation.Token;
        EnsurePlaybackOrder();
        var upcoming = playbackOrder is null ? [] : playbackOrder.Upcoming(offlineSettings.NextCount).Select(i => queue[i]).ToArray();
        var capturedQueue = queue.ToArray();
        int capturedIndex = queueIndex;
        var settings = offlineSettings;
        bool prefetch = PrefetchAllowed();
        var protectedTracks = ProtectedTracks().ToArray();
        long limit = offlineSettings.CacheLimitBytes;
        long scheduledPlaybackVersion = playbackVersion;
        var previousTask = cacheTask;
        cacheTask = Task.Run(async () =>
        {
            try { await previousTask; } catch { /* A failed generation must not poison retries. */ }
            if (token.IsCancellationRequested) return;
            Song[] plan;
            try
            {
                var recent = storage.Recent();
                var pins = recent.Where(x => x.HasActiveSave() && !x.Complete).Select(x => x.Song);
                plan = OfflinePolicy.Plan(capturedQueue, capturedIndex, recent, settings, prefetch, upcoming)
                    .Concat(pins).DistinctBy(x => x.Id).ToArray();
            }
            catch (Exception error)
            {
                DispatcherQueue.TryEnqueue(() =>
                {
                    if (!closed && storage == cache && !token.IsCancellationRequested) ShowError(CacheFailureMessage(error));
                });
                return;
            }
            foreach (var song in plan)
            {
                if (token.IsCancellationRequested) break;
                try
                {
                    // Retention claims were recorded by Pin, never inferred from a transfer.
                    await storage.SaveMediaAsync(song, false, (output, cancel) => target.DownloadSongAsync(song.Id, output, cancel), token);
                    token.ThrowIfCancellationRequested();
                    storage.Trim(limit, protectedTracks);
                    DispatcherQueue.TryEnqueue(() =>
                    {
                        if (closed || storage != cache || token.IsCancellationRequested) return;
                        QueueCacheRefresh(song.Id);
                        // A queue-only shuffle may reprioritize prefetch, never replace
                        // the active source as a side effect of that click.
                        if (allowCurrentHandoff) HandoffCompletedSong(song.Id, scheduledPlaybackVersion);
                    });
                }
                catch (OperationCanceledException) { break; }
                catch (Exception error)
                {
                    if (!token.IsCancellationRequested)
                    {
                        try { storage.Trim(limit, protectedTracks); } catch { }
                        DispatcherQueue.TryEnqueue(() =>
                        {
                            if (closed || storage != cache || token.IsCancellationRequested) return;
                            QueueCacheRefresh(song.Id);
                            ShowError(CacheFailureMessage(error));
                        });
                    }
                }
            }
        });
    }
}
