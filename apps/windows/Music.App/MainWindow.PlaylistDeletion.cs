using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Music.Core;

namespace Music.App;

public sealed partial class MainWindow
{
    // IDs are scoped to this connection. Keep late catalog responses and Back
    // entries from reviving a playlist deleted during this session.
    private readonly HashSet<string> deletedPlaylistIds = new(StringComparer.Ordinal);
    private readonly Dictionary<string, Playlist> pendingPlaylistDeletions = new(StringComparer.Ordinal);
    private bool playlistDeletionActive;

    private static void StretchPlaylistRows(ListViewBase view)
    {
        var style = new Style(typeof(ListViewItem));
        style.Setters.Add(new Setter(Control.HorizontalContentAlignmentProperty, HorizontalAlignment.Stretch));
        view.ItemContainerStyle = style;
    }

    private void AddPlaylistRowOverflow(Grid anchor, Playlist playlist, string layout)
    {
        var owner = connectionLease;
        long revision = navigationRevision, results = browseResultsVersion;
        bool current() => page == "playlists" && navigationRevision == revision && browseResultsVersion == results &&
            anchor.IsLoaded && anchor.XamlRoot == root.XamlRoot && CanEditPlaylists(owner) &&
            playlists.Any(item => item.Id == playlist.Id && item.Name == playlist.Name && item.CanDelete == playlist.CanDelete);
        string key = "playlist:" + playlist.Id;
        bool hasClaims(IReadOnlyDictionary<string, CachedSong> snapshot) => DeviceAvailability.HasCollectionClaims(snapshot, key);
        bool deviceAvailable() => current() && playlistDialog is null && !unverifiedPlaylistIds.Contains(playlist.Id) &&
            (playlist.SongCount > 0 || hasClaims(CacheSnapshot()));
        async Task save()
        {
            if (client is not { } target || !deviceAvailable()) return;
            try
            {
                var contents = await target.GetPlaylistContentsAsync(playlist.Id, owner.Token);
                if (!deviceAvailable() || !ReferenceEquals(target, client)) return;
                if (contents.Songs.Count == 0)
                {
                    ShowError("This playlist has no songs to save.");
                    return;
                }
                await SaveToDeviceAsync(contents.Songs, key, owner);
            }
            catch (OperationCanceledException) { }
            catch (NavidromeException ex) { if (deviceAvailable()) ShowError(ex.Message); }
        }
        var more = CreateDeviceActions(playlist.Name, 32, deviceAvailable, hasClaims, save,
            () => RemoveFromDeviceAsync(null, key, owner), showAction: () => playlist.SongCount > 0 || hasClaims(CacheSnapshot()));
        // The row still opens on primary click; the native More button is a
        // sibling of the artwork/copy and shares the container context menu.
        more.IsEnabled = true;
        string label = "More options for " + playlist.Name;
        AutomationProperties.SetName(more, label);
        ToolTipService.SetToolTip(more, label);
        var menu = (MenuFlyout)more.Flyout;
        AddPlaylistAction(anchor, playlist, owner, menu, current);
        AddPlaylistDeleteAction(more, playlist, owner, menu, current);
        anchor.ContextFlyout = menu;
        more.HorizontalAlignment = HorizontalAlignment.Right;
        if (layout == "grid")
        {
            more.VerticalAlignment = VerticalAlignment.Top;
            more.Margin = new Thickness(0, 4, 4, 0);
        }
        else
        {
            anchor.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            Grid.SetColumn(more, 2);
            more.Margin = new Thickness(8, 0, 0, 0);
        }
        anchor.Children.Add(more);
    }

    private void AddPlaylistDeleteAction(Button more, Playlist playlist, RequestGate.Lease owner, MenuFlyout menu, Func<bool> surfaceCurrent)
    {
        if (!playlist.CanDelete) return;
        bool current() => surfaceCurrent() && CanEditPlaylists(owner) &&
            !unverifiedPlaylistIds.Contains(playlist.Id) && !deletedPlaylistIds.Contains(playlist.Id);
        bool eligible() => !PlaylistActionBusy && current();
        Control launcher = more;
        var action = new MenuFlyoutItem { Text = "Delete playlist", Icon = new SymbolIcon(Symbol.Delete) };
        menu.Items.Add(new MenuFlyoutSeparator());
        menu.Items.Add(action);
        menu.Opening += (_, _) =>
        {
            launcher = menu.Target as Control ?? more;
            action.IsEnabled = eligible();
        };
        action.Click += async (_, _) =>
        {
            if (eligible()) await ConfirmPlaylistDeletionAsync(playlist, launcher, owner, current);
        };
    }

    private async Task ConfirmPlaylistDeletionAsync(Playlist playlist, Control launcher, RequestGate.Lease owner, Func<bool> surfaceCurrent)
    {
        if (PlaylistActionBusy || client is not { } target || root.XamlRoot is not { } xamlRoot || !surfaceCurrent()) return;
        var dialog = new ContentDialog
        {
            Title = $"Delete “{playlist.Name}”?",
            Content = new TextBlock { Text = "This permanently removes the playlist and its song list from your music server. The songs themselves stay in your library.\n\nThis can’t be undone.", TextWrapping = TextWrapping.Wrap },
            PrimaryButtonText = "Delete playlist",
            CloseButtonText = "Cancel", DefaultButton = ContentDialogButton.Close, XamlRoot = xamlRoot
        };
        long revision = navigationRevision;
        bool sameAccount() => !closed && target == client && connectionGate.IsCurrent(owner);
        bool sameRoute() => sameAccount() && revision == navigationRevision && root.XamlRoot == xamlRoot;
        bool current() => sameRoute() && ReferenceEquals(playlistDialog, dialog) && surfaceCurrent();
        bool restoreFocus = true;
        // Hold the same gate used by New and Add to Playlist through the final
        // result; the dialog closing is not the end of this mutation.
        playlistDialog = dialog;
        playlistDeletionActive = true;
        RefreshPlaylistActionAvailability();
        try
        {
            ContentDialogResult result;
            try { result = await dialog.ShowAsync(); }
            catch (InvalidOperationException) { return; } // Another window-modal operation opened first.
            if (result != ContentDialogResult.Primary || !current()) return;
            var (outcome, present) = await target.DeletePlaylistAsync(playlist, current, owner.Token);
            if (!sameAccount()) return;
            if (outcome is PlaylistDeletionResult.Acknowledged or PlaylistDeletionResult.AbsentAfterUncertain)
            {
                restoreFocus = false;
                await PublishPlaylistDeletionAsync(playlist, target, owner, sameRoute(), outcome);
            }
            else if (outcome == PlaylistDeletionResult.Unverified)
            {
                pendingPlaylistDeletions[playlist.Id] = playlist;
                InvalidatePlaylistDestination(playlist.Id);
                RenderPlaylistDeletionChecks();
            }
            else if (outcome == PlaylistDeletionResult.StillPresent && present is not null)
                PublishPlaylistStillPresent(present);
        }
        catch (OperationCanceledException) { }
        catch (NavidromeException ex) { if (sameRoute()) ShowError(ex.Message); }
        finally
        {
            playlistDialog = null;
            playlistDeletionActive = false;
            if (!closed) RefreshPlaylistActionAvailability();
            if (restoreFocus && sameRoute() && launcher.IsLoaded && launcher.IsEnabled && launcher.XamlRoot == xamlRoot)
                launcher.Focus(FocusState.Programmatic);
        }
    }

    private void RenderPlaylistDeletionChecks()
    {
        var host = C<StackPanel>("PlaylistDeletionChecks");
        host.Children.Clear();
        var owner = connectionLease;
        foreach (var playlist in pendingPlaylistDeletions.Values)
        {
            var retry = new Button { Content = "Check deletion again" };
            AutomationProperties.SetName(retry, $"Check deletion of {playlist.Name} again");
            var notice = new InfoBar
            {
                Message = $"The server did not confirm deletion of “{playlist.Name}”. Check its status without sending another delete request.",
                IsOpen = true, IsClosable = false, Severity = InfoBarSeverity.Warning,
                Margin = new Thickness(0, 0, 0, 12), ActionButton = retry
            };
            retry.Click += async (_, _) => await CheckPlaylistDeletionAsync(playlist, owner, notice);
            host.Children.Add(notice);
        }
        RefreshPlaylistActionAvailability();
    }

    private async Task CheckPlaylistDeletionAsync(Playlist playlist, RequestGate.Lease owner, InfoBar checkNotice)
    {
        if (PlaylistActionBusy || !CanEditPlaylists(owner) || client is not { } target ||
            !ReferenceEquals(pendingPlaylistDeletions.GetValueOrDefault(playlist.Id), playlist)) return;
        long revision = navigationRevision;
        playlistDeletionActive = true;
        RefreshPlaylistActionAvailability();
        try
        {
            var (outcome, present) = await target.CheckPlaylistDeletionAsync(playlist.Id, owner.Token);
            if (!CanEditPlaylists(owner) || target != client) return;
            if (outcome == PlaylistDeletionResult.Unverified)
            {
                checkNotice.Message = $"Could not check deletion of “{playlist.Name}”. Try checking again.";
                return;
            }
            pendingPlaylistDeletions.Remove(playlist.Id);
            RenderPlaylistDeletionChecks();
            if (outcome == PlaylistDeletionResult.AbsentAfterUncertain)
            {
                await PublishPlaylistDeletionAsync(playlist, target, owner, page == "playlists" && revision == navigationRevision, outcome);
                return;
            }
            if (outcome == PlaylistDeletionResult.StillPresent && present is not null)
                PublishPlaylistStillPresent(present);
        }
        catch (OperationCanceledException) { }
        finally
        {
            playlistDeletionActive = false;
            if (!closed) RefreshPlaylistActionAvailability();
        }
    }

    private void PublishPlaylistStillPresent(Playlist present)
    {
        // The catalog might have omitted the target during the outage.
        // Restore its verified metadata, but not its stale songs.
        long beforeCheck = playlistCatalogRevision++;
        playlistEdits[present.Id] = (playlistCatalogRevision, present);
        playlists = MergePlaylistCatalog(playlists, beforeCheck);
        if (page == "detail" && detailIdentity == ("playlist", present.Id))
        {
            detailCollection = present;
            detailTitle = present.Name;
        }
        InvalidatePlaylistDestination(present.Id);
        if (!browsePending && page is "library" or "playlists") RenderBrowse();
        ShowPlaylistDeletionNotice(InfoBarSeverity.Informational,
            $"“{present.Name}” is still on the music server. Open or reload the playlist to review it before trying to delete it again.");
    }

    private async Task PublishPlaylistDeletionAsync(Playlist playlist, NavidromeClient target, RequestGate.Lease owner,
        bool returnToPlaylists, PlaylistDeletionResult outcome)
    {
        deletedPlaylistIds.Add(playlist.Id);
        playlistEdits.Remove(playlist.Id);
        var revision = ++playlistCatalogRevision;
        playlists = playlists.Where(item => item.Id != playlist.Id).ToArray();
        unverifiedPlaylistIds.Remove(playlist.Id);
        RememberCollectionMembership(("playlist", playlist.Id), null);
        navigationHistory.RemoveCollection(item => item is Playlist p && p.Id == playlist.Id);

        // Don't navigate a route the user deliberately moved to during the
        // write. But an open copy of this exact detail must still be retired.
        bool showingDeleted = page == "detail" && detailIdentity == ("playlist", playlist.Id);
        string message = outcome == PlaylistDeletionResult.Acknowledged ? $"Deleted “{playlist.Name}”." :
            $"“{playlist.Name}” is no longer on the music server. The server did not confirm the delete request.";
        if ((returnToPlaylists || showingDeleted) && !offlineSettings.Offline)
        {
            long expectedRoute = navigationRevision + 1;
            await NavigateAsync("playlists", remember: false);
            if (!closed && target == client && connectionGate.IsCurrent(owner) && page == "playlists" && navigationRevision == expectedRoute)
            {
                C<ContentControl>("PlaylistHeading").Focus(FocusState.Programmatic);
                ShowPlaylistDeletionNotice(outcome == PlaylistDeletionResult.Acknowledged ? InfoBarSeverity.Success : InfoBarSeverity.Informational, message);
            }
        }
        else
        {
            if (!browsePending && page is "library" or "playlists") RenderBrowse();
            if (outcome == PlaylistDeletionResult.AbsentAfterUncertain && !offlineSettings.Offline)
                ShowPlaylistDeletionNotice(InfoBarSeverity.Informational, message);
        }

        if (!CanEditPlaylists(owner)) return;
        try
        {
            var refreshed = await target.GetPlaylistsAsync(owner.Token);
            if (!CanEditPlaylists(owner) || revision != playlistCatalogRevision) return;
            playlists = MergePlaylistCatalog(refreshed, revision);
            if (!browsePending && page is "library" or "playlists") RenderBrowse();
        }
        catch (OperationCanceledException) { }
        catch (NavidromeException) { /* Keep the known deletion and surviving catalog. */ }
    }

    private void ShowPlaylistDeletionNotice(InfoBarSeverity severity, string message)
    {
        var notice = C<InfoBar>("PlaylistNotice");
        notice.Severity = severity;
        notice.Message = message;
        notice.IsOpen = true;
    }
}
