using Microsoft.UI;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Music.Core;

namespace Music.App;

public sealed partial class MainWindow
{
    private Grid SongCell(Song song, StackPanel copy, CancellationToken token, RequestGate.Lease owner, double spacing = 12)
    {
        var cell = new Grid { ColumnSpacing = spacing, VerticalAlignment = VerticalAlignment.Center };
        cell.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(40) });
        cell.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        cell.Children.Add(SongArtwork(song.CoverArtId, token, owner));
        Grid.SetColumn(copy, 1); cell.Children.Add(copy);
        return cell;
    }

    private Border SongArtwork(string? id, CancellationToken token, RequestGate.Lease owner) =>
        CoverArtwork(id, token, owner, 40);

    private Border CoverArtwork(string? id, CancellationToken token, RequestGate.Lease owner, double size)
    {
        var image = new Image { Width = size, Height = size, Stretch = Stretch.UniformToFill };
        var fallback = new FontIcon { Glyph = "\uE8D6", FontSize = size == 40 ? 20 : 36, Foreground = Muted };
        AutomationProperties.SetName(fallback, "No artwork available");
        var content = new Grid(); content.Children.Add(fallback); content.Children.Add(image);
        var cover = new Border
        {
            Width = size, Height = size, CornerRadius = new CornerRadius(size == 40 ? 4 : 6),
            Background = new SolidColorBrush(ColorHelper.FromArgb(255, 53, 66, 57)), Child = content
        };
        image.ImageOpened += (_, _) => fallback.Visibility = Visibility.Collapsed;
        image.ImageFailed += (_, _) => fallback.Visibility = Visibility.Visible;
        var target = client;
        CancellationTokenSource? pending = null;
        // Load only while attached, for both detail headers and realized song rows.
        cover.Loaded += async (_, _) =>
        {
            pending?.Cancel();
            if (closed || offlineSettings.Offline || target is null || target != client ||
                !connectionGate.IsCurrent(owner) || token.IsCancellationRequested || string.IsNullOrWhiteSpace(id)) return;
            var load = CancellationTokenSource.CreateLinkedTokenSource(token, owner.Token);
            pending = load;
            try { await artwork.SetAsync(image, id, target, load.Token); }
            finally
            {
                if (ReferenceEquals(pending, load)) pending = null;
                load.Dispose();
            }
        };
        // Retained queue items must not hold decoded images after virtualization.
        // Loaded restores the cover from the bounded byte cache when realized again.
        cover.Unloaded += (_, _) => { pending?.Cancel(); pending = null; image.Source = null; fallback.Visibility = Visibility.Visible; };
        return cover;
    }
}
