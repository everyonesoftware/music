using Music.Core;
using Xunit;

namespace Music.Core.Tests;

public sealed class PlaybackHistoryTests
{
    private static int Previous(PlaybackOrder order)
    {
        return order.MovePrevious();
    }

    [Fact]
    public void Direct_selection_records_departure_once_and_same_track_handoff_or_reconnect_adds_nothing()
    {
        var order = new PlaybackOrder(9, 2, true, false, new Random(4));
        order.Upcoming(3);
        order.Select(7);
        order.Select(7); // Local handoff.
        order.Select(7); // Reconnect/restart.
        Assert.Equal(2, Previous(order));
        Assert.Equal(1, Previous(order)); // No duplicate 7 or 2 history entry.
        Assert.Equal(new[] { 2, 7 }, order.Upcoming(2));
        order.Select(5); // Branch away from the replay path.
        Assert.Equal(1, Previous(order));
        Assert.Equal(5, order.MoveNext());
    }

    [Fact]
    public void Configuration_changes_keep_actual_back_and_forward_history_but_replace_unplayed_plan()
    {
        var order = new PlaybackOrder(9, 2, true, false, new Random(4));
        order.Select(7);
        order.Select(4);
        order.Upcoming(5);
        Assert.Equal(7, Previous(order));
        Assert.Equal(2, Previous(order));
        order.Configure(false, true);
        Assert.Equal(new[] { 7, 4, 5, 6 }, order.Upcoming(4));
        Assert.Equal(7, order.MoveNext());
        order.Select(7);
        order.Configure(false, false);
        Assert.Equal(new[] { 4, 5, 6 }, order.Upcoming(3));
        Assert.Equal(4, order.MoveNext());
        Assert.Equal(7, Previous(order));
    }

    [Fact]
    public void Initial_previous_preserves_sequential_clamp_and_repeat_wrap()
    {
        Assert.Equal(2, Previous(new PlaybackOrder(5, 3, true, false)));
        Assert.Equal(0, Previous(new PlaybackOrder(5, 0, true, false)));
        var repeat = new PlaybackOrder(5, 0, false, true);
        Assert.Equal(4, Previous(repeat));
        Assert.Equal(0, repeat.MoveNext());
        Assert.Equal(4, Previous(repeat));
        var singleton = new PlaybackOrder(1, 0, true, true);
        Assert.Equal(0, singleton.MoveNext());
        Assert.Equal(0, Previous(singleton));
        Assert.Equal(0, singleton.MoveNext());
    }

    [Fact]
    public void History_is_bounded_and_a_replacement_model_has_no_old_history()
    {
        var order = new PlaybackOrder(1000, 0, false, false);
        for (int i = 1; i <= 101; i++) order.Select(i * 2);
        for (int i = 100; i >= 1; i--) Assert.Equal(i * 2, Previous(order));
        Assert.Equal(1, Previous(order)); // Oldest departure 0 was discarded.
        Assert.Equal(7, Previous(new PlaybackOrder(10, 8, true, false)));
    }

    [Fact]
    public void Repeated_previous_keeps_forward_storage_bounded()
    {
        var order = new PlaybackOrder(3, 0, true, true);
        order.Upcoming(100);
        for (int i = 0; i < 500; i++) Previous(order);
        var field = typeof(PlaybackOrder).GetField("upcoming", System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Instance)!;
        Assert.True(Assert.IsType<List<int>>(field.GetValue(order)).Count <= 100);
    }

    [Fact]
    public void Shuffled_previous_replays_actual_track_and_next_reuses_prefetched_forward_path()
    {
        var order = new PlaybackOrder(7, 3, true, false, new Random(4));
        var planned = order.Upcoming(5).ToArray();
        Assert.NotEqual(4, planned[0]); // A numeric Previous would not return 3.
        Assert.Equal(planned[0], order.MoveNext());
        order.Select(planned[0]); // The window synchronizes after navigation.
        Assert.Equal(3, Previous(order));
        order.Select(3);
        Assert.Equal(planned, order.Upcoming(5));
        Assert.Equal(planned[0], order.MoveNext());
        Assert.Equal(planned[1], order.MoveNext());
        Assert.Equal(planned[0], Previous(order));
        Assert.Equal(3, Previous(order));
        Assert.Equal(planned, order.Upcoming(5));
    }
}
