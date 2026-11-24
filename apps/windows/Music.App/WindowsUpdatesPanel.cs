using EveryoneSoftware.WindowsCommon;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Music.Core;
using SharedUpdatesPanel = EveryoneSoftware.WindowsCommon.WindowsUpdatesPanel;

namespace Music.App;

/// <summary>Bind once after Shell.xaml is loaded; dispose when its window closes.</summary>
internal sealed partial class WindowsUpdatesPanel : IDisposable
{
    private readonly WindowsUpdateController controller;
    private readonly DispatcherQueue dispatcher;
    private readonly SharedUpdatesPanel panel;
    private readonly TextBlock notesStatus;
    private readonly Expander changelog;
    private readonly StackPanel notesContent;
    private readonly Button retryNotes;
    private readonly WindowsChangelogController notes;
    private Version? shownOffer;
    private bool disposed;

    public WindowsUpdatesPanel(FrameworkElement root, DispatcherQueue dispatcher, string installedVersion, Func<Task> preserveSession)
    {
        this.dispatcher = dispatcher;
        var backend = new WindowsUpdateBackend();
        controller = new WindowsUpdateController(backend, preserveSession);
        panel = new SharedUpdatesPanel("Music", installedVersion,
            cardBackground: (Brush)root.Resources["SettingsCardBackground"],
            cardBorder: (Brush)root.Resources["SettingsCardBorder"]);
        changelog = (Expander)root.FindName("UpdateChangelog");
        notesStatus = (TextBlock)root.FindName("ChangelogStatus");
        notesContent = (StackPanel)root.FindName("ChangelogContent");
        retryNotes = (Button)root.FindName("RetryChangelogButton");
        // Resolve names before moving the notes subtree into the shared panel's details slot.
        ((ContentControl)root.FindName("ChangelogParkingHost")).Content = null;
        ((ContentControl)root.FindName("UpdatesPanelHost")).Content = panel.View;
        notes = new WindowsChangelogController();
        retryNotes.Click += RetryNotesClicked;
        notes.Changed += NotesChanged;
        panel.CheckRequested += CheckRequested;
        panel.DownloadRequested += DownloadRequested;
        panel.RestartRequested += RestartRequested;
        controller.Changed += OnChanged;
        Render(controller.Status);
    }

    internal Task CheckOnStartupAsync() => controller.CheckOnStartupAsync();

    private async void CheckRequested(object? sender, EventArgs e) => await controller.CheckAsync();
    private async void DownloadRequested(object? sender, EventArgs e) => await controller.DownloadAsync();
    private async void RestartRequested(object? sender, EventArgs e) => await controller.RestartAsync();
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
        if (shownOffer != state.Version) changelog.IsExpanded = false;
        shownOffer = state.Version;
        notes.SetOffer(state.Version, state.Changelog);
        retryNotes.IsEnabled = state.Phase == WindowsUpdatePhase.Available;
        // Music's controller queues checks behind an in-flight download and can recheck a staged offer.
        panel.Render(new UpdateSnapshot(ToSharedPhase(state.Phase), state.Message, state.Version, state.Progress)
        {
            AllowRecheckDuringDownloadOrReady = true
        }, restartAllowed: true, changelog: changelog);
    }

    private static UpdatePhase ToSharedPhase(WindowsUpdatePhase phase) => phase switch
    {
        WindowsUpdatePhase.Idle => UpdatePhase.Idle,
        WindowsUpdatePhase.Checking => UpdatePhase.Checking,
        WindowsUpdatePhase.Current => UpdatePhase.Current,
        WindowsUpdatePhase.Available => UpdatePhase.Available,
        WindowsUpdatePhase.Downloading => UpdatePhase.Downloading,
        WindowsUpdatePhase.Ready => UpdatePhase.Ready,
        WindowsUpdatePhase.Restarting => UpdatePhase.Restarting,
        WindowsUpdatePhase.Error => UpdatePhase.Error,
        WindowsUpdatePhase.NotInstalled => UpdatePhase.NotInstalled,
        WindowsUpdatePhase.StartupDeferred => UpdatePhase.StartupDeferred,
        _ => throw new ArgumentOutOfRangeException(nameof(phase), phase, null)
    };

    public void Dispose()
    {
        if (disposed) return;
        disposed = true;
        panel.CheckRequested -= CheckRequested;
        panel.DownloadRequested -= DownloadRequested;
        panel.RestartRequested -= RestartRequested;
        panel.Dispose();
        retryNotes.Click -= RetryNotesClicked;
        notes.Changed -= NotesChanged;
        notes.Dispose();
        controller.Changed -= OnChanged;
        controller.Dispose();
    }
}
