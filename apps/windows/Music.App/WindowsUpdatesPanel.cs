using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Music.Core;

namespace Music.App;

/// <summary>Bind once after Shell.xaml is loaded; dispose when its window closes. No startup network or apply.</summary>
public sealed partial class WindowsUpdatesPanel : IDisposable
{
    private readonly WindowsUpdateController controller;
    private readonly DispatcherQueue dispatcher;
    private readonly Button check, download, restart;
    private readonly TextBlock title, status;
    private readonly StackPanel actions;
    private readonly ProgressBar progress;
    private bool disposed;

    public WindowsUpdatesPanel(FrameworkElement root, DispatcherQueue dispatcher, Func<Task> preserveSession)
    {
        this.dispatcher = dispatcher;
        check = (Button)root.FindName("CheckUpdatesButton");
        download = (Button)root.FindName("DownloadUpdateButton");
        restart = (Button)root.FindName("RestartUpdateButton");
        title = (TextBlock)root.FindName("UpdateTitle");
        status = (TextBlock)root.FindName("UpdateStatus");
        actions = (StackPanel)root.FindName("UpdateActions");
        progress = (ProgressBar)root.FindName("UpdateProgress");
        controller = new WindowsUpdateController(new WindowsUpdateBackend(), preserveSession);
        check.Click += CheckClicked;
        download.Click += DownloadClicked;
        restart.Click += RestartClicked;
        controller.Changed += OnChanged;
        Render(controller.Status);
    }

    private async void CheckClicked(object sender, RoutedEventArgs e) => await controller.CheckAsync();
    private async void DownloadClicked(object sender, RoutedEventArgs e) => await controller.DownloadAsync();
    private async void RestartClicked(object sender, RoutedEventArgs e) => await controller.RestartAsync();

    private void OnChanged(WindowsUpdateStatus _) => dispatcher.TryEnqueue(() =>
    {
        // Read the current state rather than a queued progress snapshot: late callbacks cannot rewind Ready.
        if (!disposed) Render(controller.Status);
    });

    private void Render(WindowsUpdateStatus state)
    {
        title.Text = state.Phase switch
        {
            WindowsUpdatePhase.Idle => "Not checked yet",
            WindowsUpdatePhase.Checking => "Checking for updates",
            WindowsUpdatePhase.Current => "Up to date",
            WindowsUpdatePhase.Available => "Update available",
            WindowsUpdatePhase.Downloading => "Downloading update",
            WindowsUpdatePhase.Ready => "Ready to install",
            WindowsUpdatePhase.Restarting => "Restarting",
            WindowsUpdatePhase.Error => "Update failed",
            WindowsUpdatePhase.NotInstalled => "Updates unavailable",
            _ => "Updates"
        };
        status.Text = state.Phase == WindowsUpdatePhase.Downloading ? $"{state.Message} {state.Progress}%" : state.Message;
        bool busy = state.Phase is WindowsUpdatePhase.Checking or WindowsUpdatePhase.Downloading or WindowsUpdatePhase.Restarting;
        check.Content = state.Phase == WindowsUpdatePhase.Checking ? "Checking…" : "Check for updates";
        check.IsEnabled = !busy && state.Phase is not (WindowsUpdatePhase.Ready or WindowsUpdatePhase.NotInstalled);
        actions.Visibility = state.Phase is WindowsUpdatePhase.Available or WindowsUpdatePhase.Ready ? Visibility.Visible : Visibility.Collapsed;
        download.Visibility = state.Phase == WindowsUpdatePhase.Available ? Visibility.Visible : Visibility.Collapsed;
        download.IsEnabled = !busy;
        restart.Visibility = state.Phase == WindowsUpdatePhase.Ready ? Visibility.Visible : Visibility.Collapsed;
        restart.IsEnabled = !busy;
        progress.Visibility = busy || state.Phase == WindowsUpdatePhase.Ready ? Visibility.Visible : Visibility.Collapsed;
        progress.IsIndeterminate = state.Phase is WindowsUpdatePhase.Checking or WindowsUpdatePhase.Restarting;
        progress.Value = state.Progress;
    }

    public void Dispose()
    {
        if (disposed) return;
        disposed = true;
        check.Click -= CheckClicked;
        download.Click -= DownloadClicked;
        restart.Click -= RestartClicked;
        controller.Changed -= OnChanged;
        controller.Dispose();
    }
}
