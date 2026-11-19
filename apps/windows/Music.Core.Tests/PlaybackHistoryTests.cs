using Music.Core;
using Xunit;

namespace Music.Core.Tests;

public sealed class PlaybackHistoryTests
{
    [Fact]
    public async Task Native_start_evidence_is_bound_to_source_and_visit_not_current_index()
    {
        var order = new PlaybackOrder(2, 0, false, true);
        var replacedSource = order.CaptureStartEvidence();
        var activeSource = order.CaptureStartEvidence();
        await Task.Run(replacedSource);
        order.MoveNext();
        Assert.Empty(order.Played);
        await Task.Run(activeSource); // Visit 0 already departed without starting.
        order.MoveNext(); // Returns to numeric index 0, but a different visit.
        order.MoveNext();
        Assert.Empty(order.Played);
    }

    [Fact]
    public async Task Native_start_before_departure_survives_handoff_and_finished_callbacks_cannot_restart()
    {
        var order = new PlaybackOrder(2, 0, false, false);
        var observed = order.CaptureStartEvidence();
        await Task.Run(observed);
        order.CaptureStartEvidence(); // Same visit's replacement source preserves evidence.
        order.MoveNext();
        Assert.Equal(new[] { 0 }, order.Played.Select(x => x.Index));
        var final = order.CaptureStartEvidence();
        await Task.Run(final);
        order.Finish();
        await Task.Run(final);
        order.Select(1); // Explicit paused replay is not another actual start.
        order.Finish();
        Assert.Equal(new[] { 0, 1 }, order.Played.Select(x => x.Index));
    }

    [Fact]
    public void Large_ordered_collection_retains_all_remaining_occurrences()
    {
        var order = new PlaybackOrder(602, 2, false, false);
        Assert.Equal(Enumerable.Range(3, 599), order.Upcoming(602));
        Assert.Equal(3, order.MoveNext());
    }

    [Fact]
    public void Unstarted_selection_is_not_previous_history()
    {
        var order = new PlaybackOrder(9, 2, false, false);
        order.Select(7);
        Assert.Equal(6, order.MovePrevious());
    }

    [Fact]
    public void Collection_middle_start_skips_and_failed_selection_never_invent_played_prefix()
    {
        var order = new PlaybackOrder(9, 2, false, false);
        Assert.Empty(order.Played);
        order.MarkStarted();
        Assert.Empty(order.Played); // Current is not yet a past song.
        order.Select(7);
        Assert.Equal(new[] { 2 }, order.Played.Select(x => x.Index));
        order.Select(8); // 7 never started.
        Assert.Equal(new[] { 2 }, order.Played.Select(x => x.Index));
        Assert.Equal(2, order.MovePrevious());
        Assert.Empty(order.Played);
    }

    [Fact]
    public void Paused_restoration_and_same_occurrence_handoffs_have_no_phantom_history()
    {
        var order = new PlaybackOrder(4, 2, false, false);
        order.Select(2); order.Select(2);
        Assert.Empty(order.Played);
        order.MarkStarted(); order.MarkStarted();
        order.Select(2); order.MarkStarted(); // Reconnect/local handoff.
        order.Select(3);
        Assert.Single(order.Played);
        Assert.Equal(2, order.Played[0].Index);
    }

    [Fact]
    public void Fallback_previous_does_not_manufacture_listening_evidence()
    {
        var order = new PlaybackOrder(5, 3, false, false);
        Assert.Equal(2, order.MovePrevious());
        Assert.Equal(1, order.MovePrevious());
        Assert.Empty(order.Played);
        Assert.Equal(2, order.MoveNext());
        Assert.Empty(order.Played);
    }

    [Fact]
    public void Played_selection_uses_event_identity_and_preserves_duplicate_occurrences()
    {
        var order = new PlaybackOrder(4, 0, false, true);
        order.MarkStarted(); order.Select(2); // 0 and 2 can be the same song ID.
        order.MarkStarted(); order.Select(0);
        order.MarkStarted(); order.Select(3);
        var selected = order.Played[0];
        Assert.Equal(new[] { 0, 2, 0 }, order.Played.Select(x => x.Index));
        Assert.True(order.SelectPlayed(selected.Id));
        Assert.Equal(new[] { 2, 0 }, order.Played.Select(x => x.Index));
        Assert.False(order.SelectPlayed(selected.Id)); // Stale row cannot select again.
        order.MarkStarted(); order.Select(1);
        Assert.Equal(new[] { 2, 0, 0 }, order.Played.Select(x => x.Index));
        Assert.Equal(3, order.Played.Select(x => x.Id).Distinct().Count());
    }

    [Fact]
    public void Selecting_a_past_visit_of_current_occurrence_starts_a_fresh_visit()
    {
        var order = new PlaybackOrder(1, 0, false, true);
        order.MarkStarted(); order.MoveNext(); order.MarkStarted();
        long past = order.Played[0].Id;
        Assert.True(order.SelectPlayed(past));
        Assert.Single(order.Played); // Archive the actually playing visit, not the selected past event.
        Assert.NotEqual(past, order.Played[0].Id);
        order.Finish();
        Assert.Single(order.Played); // The replacement selection has not started.
    }

    [Fact]
    public void End_records_only_a_started_final_song_once_and_restart_is_a_new_visit()
    {
        var order = new PlaybackOrder(3, 2, false, false);
        order.Finish(); Assert.Empty(order.Played);
        Assert.True(order.Ended);
        order.Select(2); order.MarkStarted(); order.Finish(); order.Finish();
        Assert.Single(order.Played);
        Assert.True(order.Ended);
        Assert.Empty(order.Upcoming(5));
        order.Select(2); Assert.False(order.Ended);
        order.MarkStarted(); order.Finish();
        Assert.Equal(new[] { 2, 2 }, order.Played.Select(x => x.Index));
    }

    [Fact]
    public void Previous_of_a_repeated_same_occurrence_consumes_the_actual_visit_identity()
    {
        var order = new PlaybackOrder(1, 0, false, true);
        order.MarkStarted(); order.MoveNext();
        long first = order.Played[0].Id;
        order.MarkStarted();
        Assert.Equal(0, order.MovePrevious());
        Assert.Single(order.Played);
        Assert.NotEqual(first, order.Played[0].Id);
        order.Finish();
        Assert.Single(order.Played); // Back selected a new source, not yet started.
    }

    [Fact]
    public void Previous_after_finish_replays_the_last_actual_visit_not_an_unheard_prefix()
    {
        var order = new PlaybackOrder(3, 2, false, false);
        order.MarkStarted(); order.Finish();
        Assert.Equal(2, order.MovePrevious());
        Assert.Empty(order.Played);
        Assert.False(order.Ended);
        order.Select(1); // Replay failed to start; do not archive it again.
        Assert.Empty(order.Played);
    }

    [Fact]
    public void Shuffle_prefetch_previous_and_forward_follow_actual_starts()
    {
        var order = new PlaybackOrder(7, 3, true, false, new Random(4));
        var planned = order.Upcoming(5).ToArray();
        Assert.NotEqual(4, planned[0]);
        order.MarkStarted(); Assert.Equal(planned[0], order.MoveNext());
        order.MarkStarted(); Assert.Equal(planned[1], order.MoveNext());
        order.MarkStarted(); Assert.Equal(planned[0], order.MovePrevious());
        Assert.Equal(3, order.MovePrevious());
        Assert.Equal(planned, order.Upcoming(5));
        order.MarkStarted(); Assert.Equal(planned[0], order.MoveNext());
        Assert.Equal(planned[1], order.MoveNext());
    }

    [Fact]
    public void Configuration_changes_keep_actual_back_and_forward_but_replace_guesses()
    {
        var order = new PlaybackOrder(9, 2, true, false, new Random(4));
        order.MarkStarted(); order.Select(7);
        order.MarkStarted(); order.Select(4);
        order.Upcoming(5);
        Assert.Equal(7, order.MovePrevious());
        Assert.Equal(2, order.MovePrevious());
        order.Configure(false, true);
        Assert.Equal(new[] { 7, 4, 5, 6 }, order.Upcoming(4));
        order.MarkStarted(); Assert.Equal(7, order.MoveNext());
        order.Configure(false, false);
        Assert.Equal(new[] { 4, 5, 6 }, order.Upcoming(3));
        order.MarkStarted(); Assert.Equal(4, order.MoveNext());
        Assert.Equal(7, order.MovePrevious());
    }

    [Fact]
    public void Initial_previous_clamps_and_repeat_wrap_preserves_forward_plan()
    {
        Assert.Equal(2, new PlaybackOrder(5, 3, true, false).MovePrevious());
        Assert.Equal(0, new PlaybackOrder(5, 0, true, false).MovePrevious());
        var repeat = new PlaybackOrder(5, 0, false, true);
        Assert.Equal(4, repeat.MovePrevious());
        repeat.MarkStarted(); Assert.Equal(0, repeat.MoveNext());
        Assert.Equal(4, repeat.MovePrevious());
        var singleton = new PlaybackOrder(1, 0, true, true);
        singleton.MarkStarted(); Assert.Equal(0, singleton.MoveNext());
        Assert.Single(singleton.Played);
        singleton.MarkStarted(); Assert.Equal(0, singleton.MoveNext());
        Assert.Equal(2, singleton.Played.Count);
    }

    [Fact]
    public void History_and_forward_storage_are_bounded_and_new_queues_start_empty()
    {
        var order = new PlaybackOrder(1000, 0, false, false);
        for (int i = 1; i <= 101; i++) { order.MarkStarted(); order.Select(i * 2); }
        Assert.Equal(100, order.Played.Count);
        for (int i = 100; i >= 1; i--) Assert.Equal(i * 2, order.MovePrevious());
        Assert.Equal(1, order.MovePrevious());
        Assert.Empty(new PlaybackOrder(10, 8, true, false).Played);
        order = new PlaybackOrder(3, 0, true, true);
        order.Upcoming(100);
        for (int i = 0; i < 500; i++) order.MovePrevious();
        var field = typeof(PlaybackOrder).GetField("upcoming", System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Instance)!;
        Assert.True(Assert.IsType<List<int>>(field.GetValue(order)).Count <= 100);
    }
}
