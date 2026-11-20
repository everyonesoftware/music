namespace Music.Core;

public sealed record ConnectionProfile(string Id, string Name, string ServerUrl, string Username);
public sealed record Song(string Id, string Title, string Artist, string Album, string? CoverArtId, int DurationSeconds, int UserRating = 0);
public sealed record Album(string Id, string Name, string Artist, string? CoverArtId, int SongCount, int Year);
public sealed record PlaybackSnapshot(string ConnectionId, IReadOnlyList<Song> Queue, int Index, double PositionSeconds, bool Shuffle, bool Repeat)
{
    /// <summary>Null denotes a legacy ordered suffix. Shuffle is retained only for file compatibility.</summary>
    [System.Text.Json.Serialization.JsonIgnore(Condition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull)]
    public IReadOnlyList<int>? RemainingOrder { get; init; }
    /// <summary>Omit optional URI-valued artwork from the saved copy, never the live queue.</summary>
    public PlaybackSnapshot WithoutTransientArtwork()
    {
        if (Queue is null) return this; // Let SessionStore reject missing queues as before.
        return this with
        {
            Queue = Queue.Select(song => song?.CoverArtId is string artwork && Uri.TryCreate(artwork, UriKind.Absolute, out _)
                ? song with { CoverArtId = null }
                : song!).ToArray()
        };
    }
}
public sealed record Playlist(string Id, string Name, string? CoverArtId, int SongCount);
