using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Music.Core;

namespace Music.App;

public sealed partial class MainWindow
{
    private SongRatingSession? ratingSession;
    private Song? ratingUndoSong;
    private int ratingUndoValue;
    private bool ratingWritePending;
    private Flyout? activeRatingFlyout;
    private Button undoRatingButton = null!;
    private event Action? RatingViewsChanged;
    private (Song? Song, int Value, bool Available)? playerRatingDisplay;

    private void InitializeRatings()
    {
        var button = C<Button>("PlayerRatingButton");
        button.Click += (_, _) =>
        {
            if (queueIndex >= 0 && queueIndex < queue.Count)
                ShowRatingPicker(button, queue[queueIndex], connectionLease);
        };
        undoRatingButton = new Button { Content = "Undo", Visibility = Visibility.Collapsed };
        C<InfoBar>("RatingNotice").ActionButton = undoRatingButton;
        undoRatingButton.Click += async (_, _) =>
        {
            if (ratingUndoSong is { } song) await ChangeRatingAsync(song, 0, connectionLease, undo: true);
        };
    }

    private void ResetRatings(NavidromeClient? target)
    {
        activeRatingFlyout?.Hide();
        ratingSession = null;
        if (target is not null) ratingSession = new SongRatingSession(target.RateSongAsync, target.GetSongRatingAsync);
        ratingWritePending = false;
        ratingUndoSong = null;
        C<InfoBar>("RatingNotice").IsOpen = false;
        RefreshRatingViews();
    }

    private bool RatingAvailable(RequestGate.Lease owner) => !closed && !accountChangePending &&
        !offlineSettings.Offline && !ratingWritePending && ratingSession is not null && connectionGate.IsCurrent(owner);

    private int GetRating(Song song, RequestGate.Lease owner) => connectionGate.IsCurrent(owner)
        ? ratingSession?.Get(song) ?? Math.Clamp(song.UserRating, 0, 5) : Math.Clamp(song.UserRating, 0, 5);

    private Song ApplyConfirmedRating(Song song) => ratingSession?.Apply(song) ?? song;

    private void BindRatingView(FrameworkElement element, Song song, RequestGate.Lease owner, Action<int, bool> paint)
    {
        void Refresh() => paint(GetRating(song, owner), RatingAvailable(owner));
        element.Loaded += (_, _) => { RatingViewsChanged -= Refresh; RatingViewsChanged += Refresh; Refresh(); };
        element.Unloaded += (_, _) => RatingViewsChanged -= Refresh;
        Refresh();
    }

    private FrameworkElement CreateInlineRating(Song song, RequestGate.Lease owner)
    {
        var control = SongRatingView.CreateInline(rating => _ = ChangeRatingAsync(song, rating, owner), out var paint);
        BindRatingView(control, song, owner, paint);
        return control;
    }

    private Button CreateQueueRating(Song song, RequestGate.Lease owner)
    {
        var button = new Button { Width = 40, MinWidth = 0, Height = 32, Padding = new(2, 0, 2, 0),
            BorderThickness = new(0), Background = new Microsoft.UI.Xaml.Media.SolidColorBrush(Microsoft.UI.Colors.Transparent),
            HorizontalAlignment = HorizontalAlignment.Right };
        button.Click += (_, _) => ShowRatingPicker(button, song, owner);
        button.Unloaded += (_, _) => { if (ReferenceEquals(activeRatingFlyout?.Target, button)) activeRatingFlyout.Hide(); };
        BindRatingView(button, song, owner, (rating, enabled) => SongRatingView.UpdateTrigger(button, song, rating, enabled));
        return button;
    }

    private void ShowRatingPicker(Button button, Song song, RequestGate.Lease owner)
    {
        if (!RatingAvailable(owner)) return;
        activeRatingFlyout?.Hide();
        var picker = SongRatingView.CreatePicker(song, GetRating(song, owner),
            rating => { if (button.IsLoaded) _ = ChangeRatingAsync(song, rating, owner); });
        picker.Placement = ReferenceEquals(button, C<Button>("PlayerRatingButton")) ? FlyoutPlacementMode.Top : FlyoutPlacementMode.Left;
        activeRatingFlyout = picker;
        picker.Closed += (_, _) => { if (ReferenceEquals(activeRatingFlyout, picker)) activeRatingFlyout = null; };
        picker.ShowAt(button);
    }

    private void RefreshRatingViews()
    {
        if (!RatingAvailable(connectionLease)) activeRatingFlyout?.Hide();
        RatingViewsChanged?.Invoke();
        UpdatePlayerRating();
        undoRatingButton.IsEnabled = ratingUndoSong is not null && !ratingWritePending && RatingAvailable(connectionLease);
    }

    private void UpdatePlayerRating()
    {
        var button = C<Button>("PlayerRatingButton");
        Song? song = queueIndex >= 0 && queueIndex < queue.Count ? queue[queueIndex] : null;
        var display = (song, song is null ? 0 : GetRating(song, connectionLease), RatingAvailable(connectionLease));
        if (playerRatingDisplay == display) return;
        if (playerRatingDisplay?.Song?.Id != song?.Id && ReferenceEquals(activeRatingFlyout?.Target, button)) activeRatingFlyout?.Hide();
        playerRatingDisplay = display;
        button.Visibility = song is null ? Visibility.Collapsed : Visibility.Visible;
        if (song is not null) SongRatingView.UpdateTrigger(button, song, display.Item2, display.Item3);
    }

    private async Task ChangeRatingAsync(Song song, int rating, RequestGate.Lease owner, bool undo = false)
    {
        var state = ratingSession;
        if (state is null || !RatingAvailable(owner)) return;
        int requested = undo ? ratingUndoValue : rating;
        ratingWritePending = true;
        RefreshRatingViews();
        try
        {
            var result = undo ? await state.UndoAsync(song.Id, owner.Token) : await state.SetAsync(song, rating, owner.Token);
            if (!OwnsRatingResult(state, owner) || result is null) return;
            ratingUndoSong = !undo && result.Before != result.After ? song : null;
            ratingUndoValue = result.Before;
            SavePlayback();
            string message = result.After == 0 ? $"Rating cleared for {song.Title}." : $"{song.Title} rated {result.After} out of 5.";
            if (result.After != requested)
                message = $"Server confirmed: {message}";
            ShowRatingNotice(message, InfoBarSeverity.Success);
        }
        catch (Exception) when (!OwnsRatingResult(state, owner)) { }
        catch
        {
            ratingUndoSong = null;
            ShowRatingNotice("Could not confirm the rating with the server. Try again when connected.", InfoBarSeverity.Error);
        }
        finally
        {
            if (ReferenceEquals(state, ratingSession) && !closed)
            {
                ratingWritePending = false;
                RefreshRatingViews();
            }
        }
    }

    private bool OwnsRatingResult(SongRatingSession session, RequestGate.Lease owner) =>
        !closed && ReferenceEquals(session, ratingSession) && connectionGate.IsCurrent(owner);

    private void ShowRatingNotice(string message, InfoBarSeverity severity)
    {
        var notice = C<InfoBar>("RatingNotice");
        notice.Message = message;
        notice.Severity = severity;
        undoRatingButton.Visibility = ratingUndoSong is null ? Visibility.Collapsed : Visibility.Visible;
        notice.IsOpen = true;
    }
}
