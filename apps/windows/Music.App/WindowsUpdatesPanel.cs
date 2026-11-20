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
    private readonly TextBlock offeredVersion, notesStatus;
    private readonly Expander changelog;
    private readonly StackPanel notesContent;
    private readonly Button retryNotes;
    private readonly WindowsChangelogController notes;
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
        var backend = new WindowsUpdateBackend();
        controller = new WindowsUpdateController(backend, preserveSession);
        offeredVersion = (TextBlock)root.FindName("UpdateOfferedVersion");
        changelog = (Expander)root.FindName("UpdateChangelog");
        notesStatus = (TextBlock)root.FindName("ChangelogStatus");
        notesContent = (StackPanel)root.FindName("ChangelogContent");
        retryNotes = (Button)root.FindName("RetryChangelogButton");
        notes = new WindowsChangelogController();
        retryNotes.Click += RetryNotesClicked;
        notes.Changed += NotesChanged;
        check.Click += CheckClicked;
        download.Click += DownloadClicked;
        restart.Click += RestartClicked;
        controller.Changed += OnChanged;
        Render(controller.Status);
    }

    private async void CheckClicked(object sender, RoutedEventArgs e) => await controller.CheckAsync();
    private async void DownloadClicked(object sender, RoutedEventArgs e) => await controller.DownloadAsync();
    private async void RestartClicked(object sender, RoutedEventArgs e) => await controller.RestartAsync();
    private async void RetryNotesClicked(object sender, RoutedEventArgs e) => await controller.CheckAsync();

    private void NotesChanged()
    {
        if (!disposed) RenderNotes();
    }

    private void RenderNotes()
    {
        notesContent.Children.Clear();
        retryNotes.Visibility = notes.Phase == WindowsChangelogPhase.Unavailable ? Visibility.Visible : Visibility.Collapsed;
        notesStatus.Visibility = notes.Phase == WindowsChangelogPhase.Loaded ? Visibility.Collapsed : Visibility.Visible;
        notesStatus.Text = notes.Phase switch
        {
            WindowsChangelogPhase.Unavailable => "Couldn’t load the changelog. You can still download or install this update.",
            _ => "Check for updates to load changes."
        };
        changelog.Header = "What’s new";
        if (notes.Changes is not { } changes) return;
        int count = changes.Improvements.Count + changes.Fixes.Count;
        changelog.Header = $"What’s new · {count} {(count == 1 ? "change" : "changes")}";
        AddGroup("Improvements", changes.Improvements);
        AddGroup("Fixes", changes.Fixes);
        if (count == 0) notesContent.Children.Add(NoteText("No user-facing Windows changes in this source range."));
        var details = new StackPanel { Spacing = 8 };
        details.Children.Add(CommitLink($"{changes.BaseRevision[..7]}…{changes.HeadRevision[..7]}",
            $"https://github.com/everyonesoftware/music/compare/{changes.BaseRevision}...{changes.HeadRevision}",
            $"Compare source range {changes.BaseRevision[..7]} to {changes.HeadRevision[..7]} on GitHub (opens in your browser)"));
        foreach (var commit in changes.Commits)
        {
            var row = new Grid { ColumnSpacing = 9 };
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            row.Children.Add(CommitLink(commit.Sha[..7],
                $"https://github.com/everyonesoftware/music/commit/{commit.Sha}",
                $"View commit {commit.Sha[..7]} on GitHub (opens in your browser)"));
            var subject = NoteText(commit.Subject);
            subject.VerticalAlignment = VerticalAlignment.Center;
            Grid.SetColumn(subject, 1);
            row.Children.Add(subject);
            details.Children.Add(row);
        }
        notesContent.Children.Add(new Expander { Header = "Commit details", Content = details, HorizontalAlignment = HorizontalAlignment.Stretch, HorizontalContentAlignment = HorizontalAlignment.Stretch });
    }

    private void AddGroup(string heading, IReadOnlyList<string> entries)
    {
        if (entries.Count == 0) return;
        notesContent.Children.Add(new TextBlock { Text = heading, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold });
        foreach (string entry in entries) notesContent.Children.Add(NoteText($"• {entry}"));
    }

    private HyperlinkButton CommitLink(string text, string uri, string accessibleName)
    {
        // NavigateUri is inert until native hyperlink activation; disclosures only render cached notes.
        var link = new HyperlinkButton
        {
            Content = text,
            NavigateUri = new Uri(uri),
            Style = (Style)notesContent.Resources["CommitLinkStyle"]
        };
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(link, accessibleName);
        ToolTipService.SetToolTip(link, accessibleName);
        return link;
    }

    private static TextBlock NoteText(string text) => new() { Text = text, TextWrapping = TextWrapping.Wrap, IsTextSelectionEnabled = true };

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
        bool changedOffer = offeredVersion.Text != (state.Version is { } version ? $"Music {version}" : "");
        offeredVersion.Text = state.Version is { } offer ? $"Music {offer}" : "";
        offeredVersion.Visibility = state.Version is null ? Visibility.Collapsed : Visibility.Visible;
        notes.SetOffer(state.Version, state.Changelog);
        retryNotes.IsEnabled = state.Phase == WindowsUpdatePhase.Available;
        changelog.Visibility = state.Version is null ? Visibility.Collapsed : Visibility.Visible;
        if (changedOffer) changelog.IsExpanded = false;
        // The product/version already lives in the offer header, not in a duplicate status row.
        if (state.Phase == WindowsUpdatePhase.Available) status.Visibility = Visibility.Collapsed;
        else status.Visibility = Visibility.Visible;
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
        retryNotes.Click -= RetryNotesClicked;
        notes.Changed -= NotesChanged;
        notes.Dispose();
        controller.Changed -= OnChanged;
        controller.Dispose();
    }
}
