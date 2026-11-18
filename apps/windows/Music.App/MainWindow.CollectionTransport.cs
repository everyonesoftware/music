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
        bool selected = account is not null && collectionTransport.IsShuffled(account, identity.Kind, identity.Id);
        var transport = new Grid { Margin = new Thickness(0, 0, 0, 8) };
        var group = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, HorizontalAlignment = HorizontalAlignment.Left };
        var download = new Button { Content = new SymbolIcon(Symbol.Download) { Foreground = Sage }, Width = 48, Height = 48,
            MinWidth = 0, Padding = new Thickness(0), IsEnabled = available(), VerticalAlignment = VerticalAlignment.Center };
        AutomationProperties.SetName(download, "Download " + detailTitle);
        ToolTipService.SetToolTip(download, AutomationProperties.GetName(download));
        download.Click += async (_, _) => { if (available()) await DownloadSongs(capturedSongs); };
        var shuffleIcon = new SymbolIcon(Symbol.Shuffle);
        var dot = new Ellipse { Width = 4, Height = 4, Fill = Sage, HorizontalAlignment = HorizontalAlignment.Center };
        var shuffleContent = new StackPanel { Spacing = 3, VerticalAlignment = VerticalAlignment.Center };
        shuffleContent.Children.Add(shuffleIcon); shuffleContent.Children.Add(dot);
        var shuffle = new ToggleButton { Content = shuffleContent, Width = 48, Height = 48,
            CornerRadius = new CornerRadius(24), MinWidth = 0, IsChecked = selected, IsEnabled = available(), Padding = new Thickness(0),
            VerticalAlignment = VerticalAlignment.Center };
        AutomationProperties.SetName(shuffle, "Shuffle " + identity.Kind);
        var play = new Button { Content = new SymbolIcon(Symbol.Play), Width = 56, Height = 56, CornerRadius = new CornerRadius(28),
            Background = Sage, Foreground = new SolidColorBrush(ColorHelper.FromArgb(255, 36, 52, 31)),
            Padding = new Thickness(0), MinWidth = 0, IsEnabled = available() };
        void updateMode()
        {
            shuffleIcon.Foreground = selected ? Sage : Muted;
            shuffle.Background = new SolidColorBrush(selected ? ColorHelper.FromArgb(255, 47, 61, 46) : Colors.Transparent);
            dot.Opacity = selected ? 1 : 0;
            AutomationProperties.SetItemStatus(shuffle, selected ? "On" : "Off");
            AutomationProperties.SetName(play, "Play " + identity.Kind + (selected ? " shuffled" : " in order"));
            ToolTipService.SetToolTip(shuffle, "Shuffle " + identity.Kind + (selected ? ": On" : ": Off"));
            ToolTipService.SetToolTip(play, AutomationProperties.GetName(play));
        }
        updateMode();
        shuffle.Click += (_, _) =>
        {
            if (!available()) { shuffle.IsChecked = selected; return; }
            selected = shuffle.IsChecked == true;
            collectionTransport.SetShuffled(account!, identity.Kind, identity.Id, selected);
            updateMode();
        };
        play.Click += (_, _) =>
        {
            var prepared = collectionTransport.Prepare(capturedSongs, available(), selected);
            if (prepared is null) return;
            // This fresh queue already embodies its selected order. Do not randomize it again
            // through the independent session shuffle control on Next/prefetch.
            C<ToggleButton>("ShuffleButton").IsChecked = false;
            StartQueue(prepared, 0);
        };
        group.Children.Add(play); group.Children.Add(shuffle); group.Children.Add(download);
        transport.Children.Add(group);
        return transport;
    }
}
