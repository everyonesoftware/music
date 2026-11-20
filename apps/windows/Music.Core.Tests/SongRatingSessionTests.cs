using Music.Core;
using Xunit;

namespace Music.Core.Tests;

public sealed class SongRatingSessionTests
{
    private static readonly Song Song = new("id", "Track", "Artist", "Album", null, 60, 2);

    [Fact]
    public async Task Confirmed_rating_wins_over_stale_song_metadata_and_undo_restores_previous_value()
    {
        var changes = new List<int>();
        int server = 2;
        var session = new SongRatingSession((_, rating, _) => {
            changes.Add(rating);
            return Task.FromResult(server = rating == 5 ? 4 : rating);
        }, (_, _) => Task.FromResult(server));
        Assert.Equal(new SongRatingChange("id", 2, 4), await session.SetAsync(Song, 5));
        Assert.Equal(4, session.Get(Song with { UserRating = 1 }));
        Assert.Equal(4, session.Apply(Song).UserRating);
        Assert.Equal(new SongRatingChange("id", 4, 2), await session.UndoAsync("id"));
        Assert.Equal(new[] { 5, 2 }, changes);
        Assert.Null(await session.UndoAsync("id"));
    }

    [Fact]
    public async Task Duplicate_click_does_not_send_another_write_and_unverified_value_is_not_published()
    {
        var completion = new TaskCompletionSource<int>();
        int sends = 0;
        var session = new SongRatingSession((_, _, _) => { sends++; return completion.Task; }, (_, _) => Task.FromResult(2));
        var first = session.SetAsync(Song, 5);
        Assert.True(session.IsBusy(Song.Id));
        Assert.Null(await session.SetAsync(Song, 3));
        Assert.Equal(1, sends);
        Assert.Equal(2, session.Get(Song));
        completion.SetException(new InvalidOperationException());
        await Assert.ThrowsAsync<InvalidOperationException>(() => first);
        Assert.False(session.IsBusy(Song.Id));
        Assert.Equal(2, session.Get(Song));
    }

    [Fact]
    public async Task Undo_only_applies_to_the_most_recent_change_and_clear_is_zero()
    {
        var server = new Dictionary<string, int>();
        var session = new SongRatingSession((id, rating, _) => Task.FromResult(server[id] = rating),
            (id, _) => Task.FromResult(server.GetValueOrDefault(id, 2)));
        var other = Song with { Id = "other" };
        await session.SetAsync(Song, 5);
        await session.SetAsync(other, 0);
        Assert.Equal(0, session.Get(other));
        Assert.Null(await session.UndoAsync(Song.Id));
        Assert.Equal(5, session.Get(Song));
        Assert.NotNull(await session.UndoAsync(other.Id));
    }

    [Fact]
    public async Task Undo_does_not_overwrite_a_different_rating_changed_on_the_server()
    {
        int server = 2, writes = 0;
        var session = new SongRatingSession((_, rating, _) => { writes++; return Task.FromResult(server = rating); },
            (_, _) => Task.FromResult(server));
        await session.SetAsync(Song, 5);
        server = 3;
        Assert.Equal(new SongRatingChange("id", 3, 3), await session.UndoAsync(Song.Id));
        Assert.Equal(3, session.Get(Song));
        Assert.Equal(1, writes);
    }
}
