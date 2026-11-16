using Music.Core;
using Xunit;
namespace Music.Core.Tests;
public sealed class OfflinePolicyTests
{
    [Fact]
    public void Defaults_are_private_bounded_and_unmetered_next_three()
    {
        var settings = new OfflineSettings();
        Assert.True(settings.SavePlayed);
        Assert.Equal(2L * 1024 * 1024 * 1024, settings.CacheLimitBytes);
        Assert.Equal(3, settings.NextCount);
        Assert.False(settings.MobilePrefetch);
        Assert.False(settings.Offline);
        Assert.False(settings.CanPrefetch(false, false));
        Assert.True(settings.CanPrefetch(true, false));
        Assert.False((settings with { Offline = true }).CanPrefetch(true, false));
        Assert.True((settings with { MobilePrefetch = true }).CanPrefetch(false, true));
    }
    [Fact]
    public void Playback_order_is_shared_with_prefetch_for_shuffle_and_repeat_wrap()
    {
        var sequential = new PlaybackOrder(3, 2, false, true, new Random(1));
        Assert.Equal(new[] { 0, 1, 2, 0 }, sequential.Upcoming(4));
        Assert.Equal(0, sequential.MoveNext());
        Assert.Equal(new[] { 1, 2, 0 }, sequential.Upcoming(3));
        var shuffled = new PlaybackOrder(7, 3, true, false, new Random(4));
        var prefetched = shuffled.Upcoming(5).ToArray();
        Assert.NotEqual(3, prefetched[0]);
        Assert.Equal(prefetched[0], shuffled.MoveNext());
        Assert.Equal(prefetched.Skip(1), shuffled.Upcoming(4));
        var end = new PlaybackOrder(3, 2, false, false);
        Assert.Empty(end.Upcoming(3)); Assert.Null(end.MoveNext());
    }
    [Fact]
    public void Handoff_preserves_latest_pause_and_position_and_rejects_stale_completions()
    {
        Assert.Equal(new CacheHandoff(47, false), CacheHandoff.Resolve("song", 4, "song", 4, false, false, 47, false));
        Assert.Equal(new CacheHandoff(48, true), CacheHandoff.Resolve("song", 4, "song", 4, false, false, 48, true));
        Assert.Null(CacheHandoff.Resolve("song", 3, "song", 4, false, false, 48, true));
        Assert.Null(CacheHandoff.Resolve("song", 4, "other", 4, false, false, 48, true));
        Assert.Null(CacheHandoff.Resolve("song", 4, "song", 4, true, false, 48, true));
        Assert.Null(CacheHandoff.Resolve("song", 4, "song", 4, false, true, 48, true));
    }
    [Fact]
    public void Prefetch_uses_resolved_upcoming_instead_of_sequential_guessing()
    {
        Song Song(string id) => new(id, id, "", "", null, 1);
        var queue = new[] { Song("a"), Song("b"), Song("c") };
        Assert.Equal(new[] { "c", "a", "b" }, OfflinePolicy.Plan(queue, 2, [], new OfflineSettings(), true, new[] { queue[0], queue[1] }).Select(x => x.Id));
    }
    [Fact]
    public void Recent_partial_finishes_after_immediate_next_before_remaining_upcoming()
    {
        Song Song(string id) => new(id, id, "", "", null, 1);
        var queue = new[] { Song("b"), Song("c"), Song("d"), Song("e") };
        var partial = new CachedSong(Song("old"), false, false, 7, DateTimeOffset.UtcNow);
        Assert.Equal(new[] { "b", "c", "old", "d", "e" }, OfflinePolicy.Plan(queue, 0, new[] { partial }, new OfflineSettings(), true).Select(x => x.Id));
    }
    [Fact]
    public void Priority_is_current_next_then_recent_partial_with_no_duplicates()
    {
        Song Song(string id) => new(id, id, "", "", null, 1);
        var plan = OfflinePolicy.Plan(new[] { Song("a"), Song("b"), Song("c"), Song("d") }, 1,
            new[] { new CachedSong(Song("old"), false, false, 1, DateTimeOffset.UtcNow), new CachedSong(Song("c"), false, false, 1, DateTimeOffset.UtcNow) }, new OfflineSettings { NextCount = 1 }, true);
        Assert.Equal(new[] { "b", "c", "old" }, plan.Select(x => x.Id));
        Assert.Equal(new[] { "b" }, OfflinePolicy.Plan(new[] { Song("a"), Song("b") }, 1, [], new OfflineSettings(), false).Select(x => x.Id));
    }
}
