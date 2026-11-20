using Microsoft.UI;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Shapes;
using Music.Core;

namespace Music.App;

internal sealed record ArtistNavigation(Artist Artist, string View);

public sealed partial class MainWindow
{
    private IReadOnlyList<Album> artistAlbums = [];
    private string artistView = "albums";

    private async Task OpenArtistAsync(Artist artist, bool remember = true, string view = "albums")
    {
        if (closed || accountChangePending || client is null || offlineSettings.Offline) return;
        var destination = new NavigationView("detail", query, mode, new ArtistNavigation(artist, view));
        if (remember && !navigationHistory.Record(CaptureNavigation(), destination)) return;
        navigationRevision++;
        var lease = browseGate.Begin(); var target = client; var owner = connectionLease;
        artworkGate.Cancel();
        browsePending = false; browseResultsVersion++;
        C<ProgressBar>("BusyBar").Visibility = Visibility.Collapsed;
        detailCollection = destination.Collection;
        page = "detail"; detailTitle = artist.Name; detailIdentity = ("artist", artist.Id);
        artistView = view == "songs" ? "songs" : "albums";
        detailSongs = []; artistAlbums = []; detailLoading = true; detailError = false;
        C<TextBlock>("PageTitle").Text = artist.Name;
        C<TextBox>("SearchBox").Visibility = Visibility.Collapsed;
        C<Button>("LayoutButton").Visibility = Visibility.Collapsed;
        SetNavigation(); RenderDetail();
        bool current() => !closed && !accountChangePending && !offlineSettings.Offline && target == client &&
            connectionGate.IsCurrent(owner) && browseGate.IsCurrent(lease) && page == "detail" && detailIdentity == ("artist", artist.Id);
        try
        {
            var library = await target.GetArtistAsync(artist.Id, lease.Token);
            if (!current()) return;
            detailCollection = new ArtistNavigation(library.Artist, artistView);
            detailTitle = library.Artist.Name;
            C<TextBlock>("PageTitle").Text = detailTitle;
            artistAlbums = library.Albums; detailSongs = library.Songs.ToList();
            detailLoading = false; RenderDetail();
        }
        catch (OperationCanceledException) { }
        catch
        {
            if (!current()) return;
            detailLoading = false; detailError = true; RenderDetail();
        }
    }

    private void RenderArtistHeader(StackPanel panel)
    {
        if (detailCollection is not ArtistNavigation origin) return;
        var owner = connectionLease; var identity = detailIdentity; long revision = navigationRevision;
        bool current() => !closed && !accountChangePending && connectionGate.IsCurrent(owner) && navigationRevision == revision &&
            ReferenceEquals(detailCollection, origin) && page == "detail" && detailIdentity == identity && !offlineSettings.Offline;
        var summary = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 20 };
        summary.Children.Add(ArtistPortrait(origin.Artist, 112, CancellationToken.None));
        var copy = new StackPanel { Spacing = 6, VerticalAlignment = VerticalAlignment.Center };
        copy.Children.Add(Text("Artist", 12, true));
        copy.Children.Add(Text(origin.Artist.Name, 28));
        copy.Children.Add(Text(detailLoading ? "Loading library…" : detailError ? "Could not load this artist." : $"{artistAlbums.Count} albums", 13, true));
        summary.Children.Add(copy); panel.Children.Add(summary);
        if (detailError)
        {
            var retry = new Button { Content = "Retry loading artist" };
            retry.Click += async (_, _) => { if (current()) await OpenArtistAsync(origin.Artist, false, artistView); };
            panel.Children.Add(retry);
        }
        else if (!detailLoading && detailSongs.Count == 0) panel.Children.Add(Text("No songs available in your library for this artist.", 14, true));
        var tabs = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 12 };
        foreach (var tab in new[] { ("Albums", "albums"), ("All songs", "songs") })
        {
            var button = new Button { Content = tab.Item1, Foreground = artistView == tab.Item2 ? Sage : Muted };
            AutomationProperties.SetName(button, tab.Item1);
            AutomationProperties.SetItemStatus(button, artistView == tab.Item2 ? "Selected" : "");
            button.Click += (_, _) =>
            {
                if (!current()) return;
                artistView = tab.Item2; detailCollection = new ArtistNavigation(origin.Artist, artistView); RenderDetail();
            };
            tabs.Children.Add(button);
        }
        panel.Children.Add(tabs);
    }

    private void InitializeArtistAlbums()
    {
        var view = C<GridView>("ArtistAlbumList");
        view.ContainerContentChanging += (_, args) =>
        {
            if (args.ItemContainer.ContentTemplateRoot is not Grid host) return;
            host.Children.Clear(); AutomationProperties.SetName(args.ItemContainer, "");
            if (args.InRecycleQueue || closed || page != "detail" || detailIdentity.Kind != "artist" || args.Item is not Album album) return;
            var card = CardElement(new Card(album.Name, album.Artist, album.CoverArtId, album), "grid", connectionLease.Token);
            host.Children.Add(card); AutomationProperties.SetName(args.ItemContainer, album.Name); args.Handled = true;
        };
        view.ItemClick += async (_, args) =>
        {
            if (closed || accountChangePending || offlineSettings.Offline || page != "detail" || detailIdentity.Kind != "artist" ||
                detailLoading || detailError || args.ClickedItem is not Album album || !artistAlbums.Any(a => ReferenceEquals(a, album))) return;
            await OpenCollectionAsync(album);
        };
    }

    private FrameworkElement ArtistCard(Artist artist, string layout, CancellationToken token)
    {
        var panel = new StackPanel { Tag = artist, Spacing = 10, Margin = new Thickness(4, 4, 16, 12),
            Orientation = layout == "grid" ? Orientation.Vertical : Orientation.Horizontal };
        if (layout == "grid") panel.Width = 164;
        panel.Children.Add(ArtistPortrait(artist, layout == "grid" ? 156 : 48, token));
        var copy = new StackPanel { Spacing = 4, VerticalAlignment = VerticalAlignment.Center };
        copy.Children.Add(Text(artist.Name, 14)); copy.Children.Add(Text($"{artist.AlbumCount} albums", 12, true));
        panel.Children.Add(copy); AutomationProperties.SetName(panel, artist.Name + ", artist");
        return panel;
    }

    private FrameworkElement ArtistPortrait(Artist artist, double size, CancellationToken token)
    {
        var panel = new Grid { Width = size, Height = size };
        panel.Children.Add(new Ellipse { Fill = new SolidColorBrush(ColorHelper.FromArgb(255, 53, 66, 57)) });
        var placeholder = new SymbolIcon(Symbol.Contact) { Foreground = Muted, Width = size * .4, Height = size * .4 };
        AutomationProperties.SetName(placeholder, "Artist artwork unavailable");
        panel.Children.Add(placeholder);
        var portrait = new Ellipse(); panel.Children.Add(portrait);
        CancellationTokenSource? cancellation = null;
        panel.Loaded += async (_, _) =>
        {
            cancellation?.Cancel(); cancellation?.Dispose();
            var target = client; var owner = connectionLease;
            cancellation = CancellationTokenSource.CreateLinkedTokenSource(token, owner.Token);
            var active = cancellation;
            if (target is null || offlineSettings.Offline) return;
            var image = new Image();
            await artwork.SetAsync(image, artist.CoverArtId, target, active.Token);
            if (!active.IsCancellationRequested && target == client && connectionGate.IsCurrent(owner) && image.Source is not null)
                portrait.Fill = new ImageBrush { ImageSource = image.Source, Stretch = Stretch.UniformToFill };
        };
        panel.Unloaded += (_, _) => { cancellation?.Cancel(); cancellation?.Dispose(); cancellation = null; portrait.Fill = null; };
        return panel;
    }

    private Grid CreateArtistTransport()
    {
        var owner = connectionLease; var identity = detailIdentity; var capturedSongs = detailSongs;
        bool available() => collectionTransport.CanStart(detailLoading, detailError, offlineSettings.Offline,
            !closed && !accountChangePending && client is not null && page == "detail" &&
            connectionGate.IsCurrent(owner) && identity == detailIdentity && ReferenceEquals(capturedSongs, detailSongs), capturedSongs.Count);
        var group = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, HorizontalAlignment = HorizontalAlignment.Left };
        var playIcon = new Polygon { Points = new PointCollection { new Windows.Foundation.Point(0, 0), new Windows.Foundation.Point(0, 20), new Windows.Foundation.Point(16, 10) },
            Fill = new SolidColorBrush(ColorHelper.FromArgb(255, 36, 52, 31)), Margin = new Thickness(3, 0, 0, 0) };
        var play = new Button { Content = playIcon, Width = 56, Height = 56, CornerRadius = new CornerRadius(28),
            Background = Sage, MinWidth = 0, Padding = new Thickness(0), IsEnabled = available() };
        var shuffle = new Button { Content = new SymbolIcon(Symbol.Shuffle) { Foreground = Muted }, Width = 48, Height = 48,
            CornerRadius = new CornerRadius(24), MinWidth = 0, Padding = new Thickness(0), IsEnabled = available(), VerticalAlignment = VerticalAlignment.Center };
        AutomationProperties.SetName(play, "Play artist in order"); ToolTipService.SetToolTip(play, "Play artist in order");
        AutomationProperties.SetName(shuffle, "Play artist shuffled"); ToolTipService.SetToolTip(shuffle, "Play artist shuffled");
        void start(bool shuffled)
        {
            var prepared = collectionTransport.Prepare(capturedSongs, available(), shuffled);
            if (prepared is not null) StartQueue(prepared, 0);
        }
        play.Click += (_, _) => start(false); shuffle.Click += (_, _) => start(true);
        group.Children.Add(play); group.Children.Add(shuffle);
        var transport = new Grid(); transport.Children.Add(group); return transport;
    }
}
