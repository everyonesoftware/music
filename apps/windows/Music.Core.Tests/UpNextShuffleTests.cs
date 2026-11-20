using Music.Core;
using Xunit;

namespace Music.Core.Tests;

public sealed class UpNextShuffleTests
{
    private static bool Shuffle(PlaybackOrder order)
    {
        return order.ShufflePending();
    }

    [Fact]
    public void One_shot_shuffle_keeps_occurrences_current_start_evidence_and_played_visits()
    {
        var order = new PlaybackOrder(9, 0, false, false, new Random(4));
        order.MarkStarted(); order.MoveNext();
        var evidence = order.CaptureStartEvidence(); evidence();
        var played = order.Played.ToArray();
        var before = order.Upcoming(9).ToArray();
        Assert.True(Shuffle(order));
        var after = order.Upcoming(9).ToArray();
        Assert.Equal(1, order.Current);
        Assert.Equal(played, order.Played);
        Assert.Equal(before.Order(), after.Order());
        Assert.False(before.SequenceEqual(after));
        Assert.Equal(after, order.Upcoming(9)); // Prefetch never rerandomizes.
        Assert.Equal(after[0], order.MoveNext());
        Assert.Equal(new[] { 0, 1 }, order.Played.Select(x => x.Index));
        order.MarkStarted(); Assert.Equal(1, order.MovePrevious());
        Assert.True(Shuffle(order));
        var replay = order.Upcoming(9).ToArray();
        Assert.Equal(after.Order(), replay.Order());
        foreach (int index in replay) Assert.Equal(index, order.MoveNext());
        Assert.Null(order.MoveNext());
    }

    [Fact]
    public void Shuffle_after_manual_selection_and_previous_does_not_truncate_a_large_queue()
    {
        var order = new PlaybackOrder(602, 0, false, false, new Random(2));
        order.MarkStarted(); order.MoveNext(); order.MarkStarted(); order.MovePrevious();
        Assert.Equal(601, order.Upcoming(602).Count);
        Assert.True(Shuffle(order));
        var pending = order.Upcoming(602).ToArray();
        order.Select(pending[3]);
        Assert.Equal(pending.Skip(4), order.Upcoming(602));
        Assert.True(Shuffle(order));
        var rest = order.Upcoming(602).ToArray();
        Assert.Equal(rest.Length, rest.Distinct().Count());
        foreach (int index in rest) Assert.Equal(index, order.MoveNext());
        Assert.Null(order.MoveNext());
    }

    [Fact]
    public void Shuffle_is_disabled_for_zero_or_one_pending_and_repeat_is_not_toggled()
    {
        Assert.False(Shuffle(new PlaybackOrder(2, 0, false, false)));
        var order = new PlaybackOrder(4, 0, false, true, new Random(3));
        Assert.True(Shuffle(order));
        var cycle = order.Upcoming(3).ToArray();
        foreach (int index in cycle) Assert.Equal(index, order.MoveNext());
        Assert.Equal(0, order.MoveNext());
        order.Configure(false, false);
        foreach (int index in new[] { 1, 2, 3 }) Assert.Equal(index, order.MoveNext());
        Assert.Null(order.MoveNext());
        order.Finish(); Assert.False(Shuffle(order));
    }

    [Fact]
    public void Cold_restore_retains_exact_materialized_occurrences_and_ignores_legacy_shuffle_mode()
    {
        var song = new Song("duplicate", "A", "Artist", "Album", null, 100);
        var snapshot = new PlaybackSnapshot("account", [song, song, song, song], 1, 37.5, true, false)
            { RemainingOrder = new[] { 3, 0, 2 } };
        string directory = Path.Combine(Path.GetTempPath(), Guid.NewGuid().ToString());
        Directory.CreateDirectory(directory);
        try
        {
            using (var store = new SessionStore(Path.Combine(directory, "session.db"))) store.SavePlayback(snapshot);
            using var reopened = new SessionStore(Path.Combine(directory, "session.db"));
            var saved = reopened.LoadPlayback("account")!;
            Assert.Equal(37.5, saved.PositionSeconds);
            var order = PlaybackOrder.Restore(saved);
            Assert.Empty(order.Played);
            Assert.Equal(new[] { 3, 0, 2 }, order.Upcoming(4));
            foreach (int i in new[] { 3, 0, 2 }) Assert.Equal(i, order.MoveNext());
            Assert.Null(order.MoveNext());
            snapshot = snapshot with { RemainingOrder = null };
            var legacy = PlaybackOrder.Restore(snapshot);
            Assert.Equal(new[] { 2, 3 }, legacy.Upcoming(4));
            legacy.MoveNext(); legacy.MoveNext(); Assert.Null(legacy.MoveNext());
            snapshot = snapshot with { RemainingOrder = new[] { 4 } };
            Assert.Throws<ArgumentException>(() => reopened.SavePlayback(snapshot));
            Assert.NotNull(reopened.LoadPlayback("account"));
        }
        finally { Directory.Delete(directory, true); }
    }

    [Fact]
    public void Previous_across_repeat_boundary_retains_next_cycle_occurrences_before_shuffle()
    {
        var order = new PlaybackOrder(3, 0, false, true, new Random(7));
        for (int i = 0; i < 3; i++) { order.MarkStarted(); order.MoveNext(); }
        order.MarkStarted();
        Assert.Equal(2, order.MovePrevious());
        Assert.Equal(new[] { 0, 1, 2 }, order.RemainingOrder);
        Assert.Equal(1, order.MovePrevious());
        Assert.Equal(new[] { 2, 0, 1, 2 }, order.RemainingOrder);
        Assert.True(Shuffle(order));
        Assert.Equal(new[] { 0, 1, 2, 2 }, order.RemainingOrder.Order());
        order.Configure(false, false);
        Assert.Equal(new[] { 0, 2 }, order.RemainingOrder.Order());
        while (order.MoveNext() is not null) { }
        Assert.Null(order.MoveNext());
    }

    [Theory]
    [InlineData(3, 2)]
    [InlineData(2, 1)]
    public void Pending_selection_consumes_the_exact_repeat_occurrence_and_starts_a_new_visit(int offset, int selected)
    {
        var order = new PlaybackOrder(3, 0, false, true);
        for (int i = 0; i < 3; i++) { order.MarkStarted(); order.MoveNext(); }
        order.MarkStarted(); order.MovePrevious(); order.MovePrevious();
        Assert.Equal(1, order.Current);
        Assert.Equal(new[] { 2, 0, 1, 2 }, order.RemainingOrder);
        var played = order.Played.ToArray();
        var oldSource = order.CaptureStartEvidence(); oldSource();
        Assert.True(order.SelectPending(offset));
        Assert.Equal(selected, order.Current);
        Assert.Equal(new[] { 2, 0, 1, 2 }.Skip(offset + 1), order.RemainingOrder);
        Assert.Equal(played.Select(x => x.Index).Append(1), order.Played.Select(x => x.Index));
        oldSource(); // A late callback cannot mark the new same-index visit.
        order.MoveNext();
        Assert.Equal(played.Length + 1, order.Played.Count);
    }

    [Fact]
    public void Legacy_shuffled_planning_is_finite_without_repeat()
    {
        var order = new PlaybackOrder(7, 3, true, false, new Random(4));
        var pending = order.Upcoming(7).ToArray();
        Assert.Equal(6, pending.Length);
        Assert.Equal(Enumerable.Range(0, 7).Where(i => i != 3), pending.Order());
        foreach (int index in pending) Assert.Equal(index, order.MoveNext());
        Assert.Null(order.MoveNext());
    }
}
