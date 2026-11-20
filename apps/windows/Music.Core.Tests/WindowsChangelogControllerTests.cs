using Music.Core;
using Xunit;

namespace Music.Core.Tests;

public sealed class WindowsChangelogControllerTests
{
    private static readonly WindowsChangelog Changes = new(new string('a', 40), new string('b', 40), ["Faster browsing"], [], [new(new string('b', 40), "feat: Faster browsing")]);

    [Fact]
    public void Notes_are_ready_before_disclosure_and_preserved_for_same_snapshot()
    {
        using var notes = new WindowsChangelogController();
        int notifications = 0;
        notes.Changed += () => notifications++;
        notes.SetOffer(new(0, 1, 94), Changes);
        Assert.Equal(WindowsChangelogPhase.Loaded, notes.Phase);
        Assert.Same(Changes, notes.Changes);
        notes.SetOffer(new(0, 1, 94), Changes);
        Assert.Equal(1, notifications);
    }

    [Fact]
    public void Same_version_replacement_cannot_retain_previous_sources_notes()
    {
        using var notes = new WindowsChangelogController();
        notes.SetOffer(new(0, 1, 94), Changes);
        notes.SetOffer(new(0, 1, 94), null);
        Assert.Equal(WindowsChangelogPhase.Unavailable, notes.Phase);
        Assert.Null(notes.Changes);
        notes.SetOffer(new(0, 1, 94), Changes);
        Assert.Equal(WindowsChangelogPhase.Loaded, notes.Phase);
        notes.SetOffer(null, Changes);
        Assert.Equal(WindowsChangelogPhase.Idle, notes.Phase);
        Assert.Null(notes.Changes);
    }

    [Fact]
    public void Disposal_clears_snapshot_and_ignores_later_completion()
    {
        var notes = new WindowsChangelogController();
        notes.SetOffer(new(0, 1, 94), Changes);
        notes.Dispose();
        notes.SetOffer(new(0, 1, 95), Changes);
        Assert.Equal(WindowsChangelogPhase.Idle, notes.Phase);
        Assert.Null(notes.Changes);
    }
}
