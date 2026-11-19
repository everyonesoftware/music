using Music.Core;
using Xunit;

namespace Music.Core.Tests;

public sealed class WindowsChangelogControllerTests
{
    private static readonly WindowsChangelog Changes = new("base", "head", new[] { "Faster browsing" }, Array.Empty<string>(), new[] { new WindowsChangeCommit("abc", "feat: Faster browsing") });

    [Fact]
    public async Task Notes_are_on_demand_cached_and_preserved_for_the_same_offer()
    {
        int calls = 0;
        using var notes = new WindowsChangelogController(new(0, 1, 88), (_, _, _) => { calls++; return Task.FromResult(Changes); });
        notes.SetOffer(new(0, 1, 94));
        Assert.Equal(0, calls);
        await notes.LoadAsync();
        Assert.Equal(WindowsChangelogPhase.Loaded, notes.Phase);
        notes.SetOffer(new(0, 1, 94)); // download/progress/ready re-render the same package
        await notes.LoadAsync();
        Assert.Equal(1, calls);
        Assert.Equal(Changes, notes.Changes);
    }

    [Fact]
    public async Task Missing_notes_are_retryable_without_changing_the_update_offer()
    {
        int calls = 0;
        using var notes = new WindowsChangelogController(new(0, 1, 88), (_, _, _) => ++calls == 1
            ? Task.FromException<WindowsChangelog>(new IOException("private transport detail")) : Task.FromResult(Changes));
        notes.SetOffer(new(0, 1, 94));
        await notes.LoadAsync();
        Assert.Equal(WindowsChangelogPhase.Unavailable, notes.Phase);
        Assert.Null(notes.Changes);
        await notes.LoadAsync();
        Assert.Equal(WindowsChangelogPhase.Loaded, notes.Phase);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task Replaced_or_disposed_offer_rejects_late_notes(bool dispose)
    {
        var pending = new TaskCompletionSource<WindowsChangelog>(TaskCreationOptions.RunContinuationsAsynchronously);
        CancellationToken captured = default;
        using var notes = new WindowsChangelogController(new(0, 1, 88), (_, _, token) => { captured = token; return pending.Task; });
        notes.SetOffer(new(0, 1, 94));
        var loading = notes.LoadAsync();
        await notes.LoadAsync(); // duplicate expand/retry must not launch another request
        if (dispose) notes.Dispose(); else notes.SetOffer(new(0, 1, 95));
        Assert.True(captured.IsCancellationRequested);
        pending.SetResult(Changes);
        await loading;
        Assert.Null(notes.Changes);
        Assert.NotEqual(WindowsChangelogPhase.Loaded, notes.Phase);
    }

    [Fact]
    public async Task Clearing_the_offer_clears_notes_and_prevents_network_work()
    {
        int calls = 0;
        using var notes = new WindowsChangelogController(new(0, 1, 88), (_, _, _) => { calls++; return Task.FromResult(Changes); });
        notes.SetOffer(null);
        await notes.LoadAsync();
        Assert.Equal(0, calls);
        Assert.Null(notes.Changes);
        Assert.Equal(WindowsChangelogPhase.Idle, notes.Phase);
    }
}
