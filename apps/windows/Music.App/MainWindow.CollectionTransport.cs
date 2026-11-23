using Microsoft.UI;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Shapes;

namespace Music.App;

public sealed partial class MainWindow
{
    private Grid CreateCollectionTransport()
    {
        var owner = connectionLease;
        var account = connection?.Id;
        var identity = detailIdentity;
        var capturedSongs = detailSongs;
        bool available() => collectionTransport.CanStart(detailLoading, detailError, offlineSettings.Offline,
            !closed && !accountChangePending && page == "detail" && client is not null && account is not null &&
            account == connection?.Id && identity == detailIdentity && connectionGate.IsCurrent(owner) &&
            ReferenceEquals(capturedSongs, detailSongs), capturedSongs.Count);

        var transport = new Grid();
        var group = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, HorizontalAlignment = HorizontalAlignment.Left };
        var download = new Button { Content = new SymbolIcon(Symbol.Download) { Foreground = Sage }, Width = 48, Height = 48,
            Background = new SolidColorBrush(Colors.Transparent), BorderThickness = new Thickness(0),
            MinWidth = 0, Padding = new Thickness(0), IsEnabled = available(), VerticalAlignment = VerticalAlignment.Center };
        download.Resources["ButtonBackground"] = new SolidColorBrush(Colors.Transparent);
        download.Resources["ButtonBackgroundPointerOver"] = new SolidColorBrush(Colors.Transparent);
        download.Resources["ButtonBackgroundPressed"] = new SolidColorBrush(Colors.Transparent);
        download.Resources["ButtonBackgroundDisabled"] = new SolidColorBrush(Colors.Transparent);
        download.Resources["ButtonBorderBrush"] = new SolidColorBrush(Colors.Transparent);
        download.Resources["ButtonBorderBrushPointerOver"] = new SolidColorBrush(Colors.Transparent);
        download.Resources["ButtonBorderBrushPressed"] = new SolidColorBrush(Colors.Transparent);
        download.Resources["ButtonBorderBrushDisabled"] = new SolidColorBrush(Colors.Transparent);
        AutomationProperties.SetName(download, "Download " + detailTitle);
        ToolTipService.SetToolTip(download, AutomationProperties.GetName(download));
        download.Click += async (_, _) => { if (available()) await DownloadSongs(capturedSongs); };
        var shuffle = new Button { Content = new SymbolIcon(Symbol.Shuffle) { Foreground = Muted }, Width = 48, Height = 48,
            CornerRadius = new CornerRadius(24), MinWidth = 0, IsEnabled = available(), Padding = new Thickness(0),
            VerticalAlignment = VerticalAlignment.Center };
        AutomationProperties.SetName(shuffle, "Play " + identity.Kind + " shuffled");
        ToolTipService.SetToolTip(shuffle, AutomationProperties.GetName(shuffle));
        var playIcon = new Polygon
        {
            Points = new PointCollection { new Windows.Foundation.Point(0, 0), new Windows.Foundation.Point(0, 20), new Windows.Foundation.Point(16, 10) },
            Fill = new SolidColorBrush(ColorHelper.FromArgb(255, 36, 52, 31)),
            Margin = new Thickness(3, 0, 0, 0)
        };
        var play = new Button { Content = playIcon, Width = 56, Height = 56, CornerRadius = new CornerRadius(28),
            Background = Sage, Foreground = new SolidColorBrush(ColorHelper.FromArgb(255, 36, 52, 31)),
            Padding = new Thickness(0), MinWidth = 0, IsEnabled = available() };
        AutomationProperties.SetName(play, "Play " + identity.Kind + " in order");
        ToolTipService.SetToolTip(play, AutomationProperties.GetName(play));
        void start(bool shuffled)
        {
            var prepared = collectionTransport.Prepare(capturedSongs, available(), shuffled);
            if (prepared is null) return;
            // The fresh queue already embodies its selected occurrence order.
            StartQueue(prepared, 0);
        }
        play.Click += (_, _) => start(false);
        shuffle.Click += (_, _) => start(true);
        group.Children.Add(play); group.Children.Add(shuffle); group.Children.Add(download);
        transport.Children.Add(group);
        return transport;
    }
}
