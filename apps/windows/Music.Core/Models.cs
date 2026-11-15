namespace Music.Core;

public sealed record ConnectionProfile(string Id, string Name, string ServerUrl, string Username);
public sealed record Song(string Id, string Title, string Artist, string Album, string? CoverArtId, int DurationSeconds, int UserRating = 0);
public sealed record Album(string Id, string Name, string Artist, string? CoverArtId, int SongCount, int Year);
public sealed record PlaybackSnapshot(string ConnectionId, IReadOnlyList<Song> Queue, int Index, double PositionSeconds, bool Shuffle, bool Repeat);
public sealed record Playlist(string Id, string Name, string? CoverArtId, int SongCount);
