using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Music.Core;

namespace Music.App;

public sealed partial class MainWindow
{
    private ContentDialog? playlistDialog;
    private long playlistCatalogRevision;
    private readonly Dictionary<string, (long Revision, Playlist Playlist)> playlistEdits = new(StringComparer.Ordinal);
    private readonly HashSet<string> unverifiedPlaylistIds = new(StringComparer.Ordinal);

    private bool CanEditPlaylists(RequestGate.Lease owner) => !closed && !accountChangePending &&
        client is { Offline: false } && !offlineSettings.Offline && connectionGate.IsCurrent(owner);

    private void InitializePlaylistActions()
    {
        C<Button>("NewPlaylistButton").Click += async (_, _) =>
        {
            if (page == "playlists") await EditPlaylistAsync(null, connectionLease);
        };
    }

    private void UpdatePlaylistActions()
    {
        RefreshPlaylistActionAvailability();
        C<InfoBar>("PlaylistNotice").IsOpen = false;
    }

    private void RefreshPlaylistActionAvailability()
    {
        var create = C<Button>("NewPlaylistButton");
        create.Visibility = page == "playlists" && client is not null ? Visibility.Visible : Visibility.Collapsed;
        create.IsEnabled = CanEditPlaylists(connectionLease) && playlistDialog is null;
    }

    private void AddCollectionPlaylistAction(FrameworkElement anchor, object source)
    {
        if (source is Album or Artist or Playlist) AddPlaylistAction(anchor, source, connectionLease);
    }

    private void AddSongPlaylistAction(FrameworkElement row, Song song, RequestGate.Lease owner, MenuFlyout? menu = null,
        Func<bool>? valid = null) => AddPlaylistAction(row, song, owner, menu, valid);

    private void AddPlaylistAction(FrameworkElement anchor, object source, RequestGate.Lease owner, MenuFlyout? menu = null,
        Func<bool>? valid = null)
    {
        var renderedPage = page;
        var renderedDetail = detailIdentity;
        var renderedResults = browseResultsVersion;
        bool current() => anchor.IsLoaded && anchor.XamlRoot == root.XamlRoot && CanEditPlaylists(owner) && playlistDialog is null &&
            (valid?.Invoke() ?? (page == renderedPage && (page == "detail" ? detailIdentity == renderedDetail : browseResultsVersion == renderedResults)));
        menu ??= new MenuFlyout();
        var action = new MenuFlyoutItem { Text = "Add to Playlist", Icon = new SymbolIcon(Symbol.Add) };
        menu.Items.Insert(0, action);
        menu.Opening += (_, _) => action.IsEnabled = current();
        action.Click += async (_, _) =>
        {
            if (current()) await EditPlaylistAsync(source, owner);
        };
        anchor.ContextFlyout = menu;
    }

    private void BindPlaylistContext(ListViewBase view) => view.ContainerContentChanging += (_, args) =>
        args.ItemContainer.ContextFlyout = args.InRecycleQueue ? null : (args.Item as FrameworkElement)?.ContextFlyout;

    private void RenderEmptyPlaylistActions(StackPanel panel)
    {
        if (detailIdentity.Kind != "playlist" || detailLoading || detailError || detailSongs.Count != 0) return;
        var owner = connectionLease;
        var identity = detailIdentity;
        var revision = navigationRevision;
        panel.Children.Add(Text("No songs yet. Use Add to Playlist on an item in your library.", 14, true));
        var browse = new Button { Content = "Browse Library" };
        browse.Click += async (_, _) =>
        {
            if (!closed && !accountChangePending && page == "detail" && detailIdentity == identity &&
                navigationRevision == revision && connectionGate.IsCurrent(owner)) await NavigateAsync("library");
        };
        panel.Children.Add(browse);
    }

    private static string PlaylistSourceTitle(object source) => source switch
    {
        Song song => song.Title, Album album => album.Name, Artist artist => artist.Name, Playlist playlist => playlist.Name,
        _ => throw new ArgumentException("Unsupported playlist source.")
    };


    private async Task EditPlaylistAsync(object? source, RequestGate.Lease owner)
    {
        if (client is not { } target || root.XamlRoot is not { } xamlRoot || playlistDialog is not null || !CanEditPlaylists(owner)) return;
        long revision = navigationRevision;
        bool current() => CanEditPlaylists(owner) && target == client && navigationRevision == revision && root.XamlRoot == xamlRoot;
        using var lifetime = CancellationTokenSource.CreateLinkedTokenSource(owner.Token);
        var token = lifetime.Token;
        var name = new TextBox { Header = "Playlist name", PlaceholderText = "e.g. Sunday drive", MaxLength = PlaylistEdit.MaxNameLength };
        var picker = new ComboBox { Header = "Playlist", PlaceholderText = "Choose a playlist", HorizontalAlignment = HorizontalAlignment.Stretch,
            Visibility = source is null ? Visibility.Collapsed : Visibility.Visible };
        var newPlaylist = new ComboBoxItem { Content = "New playlist" };
        var summary = Text(source is null ? "Starts empty. Add music from your library later." : "Loading songs and playlists…", 14, true);
        summary.TextTrimming = TextTrimming.None; summary.TextWrapping = TextWrapping.Wrap;
        var status = new InfoBar { Severity = InfoBarSeverity.Error, IsClosable = false };
        var retry = new Button { Content = "Retry loading", Visibility = Visibility.Collapsed };
        var content = new StackPanel { Spacing = 12 };
        content.Children.Add(summary); content.Children.Add(picker); content.Children.Add(name); content.Children.Add(status); content.Children.Add(retry);
        var dialog = new ContentDialog { XamlRoot = xamlRoot, RequestedTheme = ElementTheme.Dark,
            Title = source is null ? "New playlist" : "Add to Playlist", Content = content,
            PrimaryButtonText = source is null ? "Create" : "Add", CloseButtonText = "Cancel", DefaultButton = ContentDialogButton.Primary };
        bool loading = false, writing = false, uncertain = false;
        string[]? ids = source is null ? [] : null;
        PlaylistEdit? edit = null;
        PlaylistContents? confirmed = null;
        bool created = false;
        bool create() => source is null || ReferenceEquals(picker.SelectedItem, newPlaylist);
        bool writable() => picker.SelectedItem is ComboBoxItem { Tag: Playlist };
        void sync()
        {
            bool checkOnly = edit?.Acknowledged == true;
            name.Visibility = create() ? Visibility.Visible : Visibility.Collapsed;
            name.IsEnabled = picker.IsEnabled = !loading && !writing && !checkOnly && !uncertain;
            dialog.PrimaryButtonText = checkOnly ? "Check again" : create() ? "Create" : "Add";
            dialog.IsPrimaryButtonEnabled = current() && !loading && !writing && !uncertain && ids is not null &&
                (checkOnly || (create() ? PlaylistEdit.IsValidName(name.Text.Trim()) : writable()));
            dialog.CloseButtonText = checkOnly || uncertain ? "Close" : "Cancel";
        }

        async Task load()
        {
            if (source is null || loading || !current()) return;
            loading = true; retry.Visibility = Visibility.Collapsed; status.IsOpen = false; ids = null;
            summary.Text = "Loading songs and playlists…";
            sync();
            try
            {
                var tracks = await PlaylistEdit.ReadSourceAsync(target, source, token);
                if (tracks.Count == 0) throw new NavidromeException("This item has no songs to add.");
                var destinations = await target.GetPlaylistsAsync(token);
                if (!current() || token.IsCancellationRequested) return;
                ids = tracks.Select(song => song.Id).ToArray();
                summary.Text = $"{PlaylistSourceTitle(source)} · {tracks.Count} {(tracks.Count == 1 ? "song" : "songs")}";
                picker.Items.Clear(); picker.Items.Add(newPlaylist);
                foreach (var group in destinations.GroupBy(p => p.Id, StringComparer.Ordinal).Where(g => g.Count() == 1)
                    .Select(g => g.Single()).Where(p => p.CanAppend).GroupBy(p => p.Name, StringComparer.CurrentCultureIgnoreCase))
                {
                    int number = 0;
                    foreach (var playlist in group)
                    {
                        string label = group.Count() == 1 ? playlist.Name : $"{playlist.Name} ({++number}) · {playlist.SongCount} songs";
                        picker.Items.Add(new ComboBoxItem { Content = label, Tag = playlist });
                    }
                }

            }
            catch (OperationCanceledException) { }
            catch (NavidromeException ex)
            {
                if (!current() || token.IsCancellationRequested) return;
                summary.Text = PlaylistSourceTitle(source);
                status.Message = ex.Message; status.IsOpen = true; retry.Visibility = Visibility.Visible;
            }
            finally
            {
                loading = false;
                if (!token.IsCancellationRequested)
                {
                    sync();
                    if (current())
                    {
                        if (ids is not null) picker.Focus(FocusState.Programmatic);
                        else retry.Focus(FocusState.Programmatic);
                    }
                }
            }
        }

        name.TextChanged += (_, _) => sync();
        picker.SelectionChanged += (_, _) => { if (create() && !writing) { sync(); name.Focus(FocusState.Programmatic); } else sync(); };
        retry.Click += async (_, _) => await load();
        dialog.Opened += async (_, _) =>
        {
            if (source is null) name.Focus(FocusState.Programmatic);
            else await load();
        };
        dialog.Closing += (_, args) => { if (writing && !closed) args.Cancel = true; };
        dialog.PrimaryButtonClick += async (_, args) =>
        {
            args.Cancel = true;
            if (!dialog.IsPrimaryButtonEnabled || !current() || writing || ids is null) return;
            var deferral = args.GetDeferral();
            writing = true; status.IsOpen = false; sync();
            try
            {
                if (edit?.Acknowledged != true)
                {
                    created = create();
                    var destination = (picker.SelectedItem as ComboBoxItem)?.Tag as Playlist;
                    edit = new PlaylistEdit(target, ids, created ? name.Text : null, created ? null : destination?.Id, source);
                }
                confirmed = await edit.ConfirmAsync(current, token);
                if (current()) args.Cancel = false;
            }
            catch (OperationCanceledException) { }
            catch (PlaylistSourceChangedException ex)
            {
                if (current())
                {
                    ids = null;
                    edit = null;
                    summary.Text = PlaylistSourceTitle(source!);
                    status.Message = ex.Message; status.IsOpen = true;
                    retry.Visibility = Visibility.Visible;
                    retry.Focus(FocusState.Programmatic);
                }
            }
            catch (PlaylistWriteUncertainException ex)
            {
                uncertain = true;
                if (current())
                {
                    InvalidatePlaylistDestination(edit?.DestinationId);
                    status.Message = ex.Message; status.IsOpen = true;
                }
            }
            catch (NavidromeException ex)
            {
                if (current())
                {
                    if (edit?.Acknowledged == true) InvalidatePlaylistDestination(edit.DestinationId);
                    status.Message = edit?.Acknowledged == true
                        ? "The server accepted the edit, but Music could not verify it. Check again without resubmitting."
                        : ex.Message;
                    status.IsOpen = true;
                }
            }
            finally { writing = false; if (!token.IsCancellationRequested) sync(); deferral.Complete(); }
        };
        playlistDialog = dialog;
        var focused = Microsoft.UI.Xaml.Input.FocusManager.GetFocusedElement(xamlRoot) as Control;
        sync();
        RefreshPlaylistActionAvailability();
        ContentDialogResult result;
        try { result = await dialog.ShowAsync(); }
        catch (InvalidOperationException) { return; } // Another window-modal operation may have opened first.
        finally
        {
            lifetime.Cancel(); playlistDialog = null;
            if (!closed) RefreshPlaylistActionAvailability();
        }
        if (!current()) return;
        if (result == ContentDialogResult.Primary && confirmed is not null)
            await PublishPlaylistEditAsync(confirmed, created);
        if (!created || result != ContentDialogResult.Primary)
        {
            if (focused is { IsLoaded: true, IsEnabled: true } && focused.XamlRoot == root.XamlRoot)
                focused.Focus(FocusState.Programmatic);
        }
    }

    private void InvalidatePlaylistDestination(string? id)
    {
        if (id is null) return; // A lost create response doesn't identify a destination.
        unverifiedPlaylistIds.Add(id);
        var identity = ("playlist", id);
        RememberCollectionMembership(identity, null);
        foreach (var item in collectionBadges)
            if (item.Key == "playlist:" + id && item.Badge.TryGetTarget(out var badge)) badge.Visibility = Visibility.Collapsed;
        if (page != "detail" || detailIdentity != identity) return;
        browseGate.Cancel();
        detailSongs = [];
        detailLoading = false;
        detailError = true;
        RenderDetail();
    }

    private void RenderUnverifiedPlaylistAction(StackPanel panel, Playlist playlist)
    {
        var owner = connectionLease;
        var revision = navigationRevision;
        panel.Children.Add(Text("Playlist contents could not be verified. Reload to see the server's current songs.", 14, true));
        var retry = new Button { Content = "Retry playlist" };
        retry.Click += async (_, _) =>
        {
            if (CanEditPlaylists(owner) && playlistDialog is null && page == "detail" && navigationRevision == revision &&
                detailIdentity == ("playlist", playlist.Id) && detailError && unverifiedPlaylistIds.Contains(playlist.Id))
                await OpenCollectionAsync(playlist, remember: false);
        };
        panel.Children.Add(retry);
    }

    private async Task PublishPlaylistEditAsync(PlaylistContents confirmed, bool created)
    {
        unverifiedPlaylistIds.Remove(confirmed.Playlist.Id);
        long beforeEdit = playlistCatalogRevision++;
        playlistEdits[confirmed.Playlist.Id] = (playlistCatalogRevision, confirmed.Playlist);
        playlists = MergePlaylistCatalog(playlists, beforeEdit);
        RememberCollectionMembership(("playlist", confirmed.Playlist.Id), confirmed.Songs);
        if (created)
        {
            // Use the verified contents; a second fetch must not undo successful creation.
            await OpenCollectionAsync(confirmed.Playlist, confirmedSongs: confirmed.Songs);
            return;
        }
        if (page == "detail" && detailIdentity == ("playlist", confirmed.Playlist.Id))
        {
            browseGate.Cancel();
            detailCollection = confirmed.Playlist; detailTitle = confirmed.Playlist.Name;
            detailSongs = confirmed.Songs.ToList(); detailLoading = detailError = false;
            RenderDetail();
        }
        else if (!browsePending && page is "library" or "playlists") RenderBrowse();
        var notice = C<InfoBar>("PlaylistNotice");
        notice.Message = $"Added to {confirmed.Playlist.Name}.";
        notice.IsOpen = true;
    }

    // A startup/reconnect request predating an edit can still supply the rest of the catalog.
    private IReadOnlyList<Playlist> MergePlaylistCatalog(IReadOnlyList<Playlist> fetched, long requestedRevision)
    {
        if (requestedRevision == playlistCatalogRevision) return fetched;
        var latest = playlistEdits.Where(pair => pair.Value.Revision > requestedRevision)
            .ToDictionary(pair => pair.Key, pair => pair.Value.Playlist, StringComparer.Ordinal);
        return fetched.Where(item => !latest.ContainsKey(item.Id)).Concat(latest.Values)
            .OrderBy(item => item.Name, StringComparer.CurrentCultureIgnoreCase).ToArray();
    }
}
