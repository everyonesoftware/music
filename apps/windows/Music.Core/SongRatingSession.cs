namespace Music.Core;

public sealed record SongRatingChange(string SongId, int Before, int After);

/// <summary>Account-scoped rating state, used from one UI dispatcher.</summary>
public sealed class SongRatingSession(Func<string, int, CancellationToken, Task<int>> write,
    Func<string, CancellationToken, Task<int>> read)
{
    private readonly Dictionary<string, int> confirmed = new(StringComparer.Ordinal);
    private readonly HashSet<string> pending = new(StringComparer.Ordinal);
    private SongRatingChange? undo;

    public int Get(Song song) => confirmed.GetValueOrDefault(song.Id, Math.Clamp(song.UserRating, 0, 5));
    public Song Apply(Song song) => song with { UserRating = Get(song) };
    public bool IsBusy(string songId) => pending.Contains(songId);

    public Task<SongRatingChange?> SetAsync(Song song, int rating, CancellationToken token = default)
        => ChangeAsync(song.Id, rating, undoAfter: null, token);

    public Task<SongRatingChange?> UndoAsync(string songId, CancellationToken token = default)
        => undo is { } change && change.SongId == songId
            ? ChangeAsync(songId, change.Before, undoAfter: change.After, token)
            : Task.FromResult<SongRatingChange?>(null);

    private async Task<SongRatingChange?> ChangeAsync(string id, int rating, int? undoAfter, CancellationToken token)
    {
        if (rating is < 0 or > 5) throw new ArgumentOutOfRangeException(nameof(rating));
        token.ThrowIfCancellationRequested();
        if (!pending.Add(id)) return null;
        try
        {
            // A saved queue (or a lost write acknowledgment in a previous process) can
            // leave the displayed value stale. Always establish the baseline at the server.
            int before = await read(id, token);
            token.ThrowIfCancellationRequested();
            if (before is < 0 or > 5) throw new InvalidOperationException("Invalid confirmed rating.");
            confirmed[id] = before;
            if (undo?.SongId == id) undo = null;
            if (rating == before || (undoAfter is { } expected && expected != before))
                return new SongRatingChange(id, before, before);
            int actual = await write(id, rating, token);
            token.ThrowIfCancellationRequested();
            if (actual is < 0 or > 5) throw new InvalidOperationException("Invalid confirmed rating.");
            confirmed[id] = actual;
            var change = new SongRatingChange(id, before, actual);
            if (undoAfter is null && actual != before) undo = change;
            return change;
        }
        finally { pending.Remove(id); }
    }
}
