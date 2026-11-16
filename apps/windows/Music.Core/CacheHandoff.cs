namespace Music.Core;

public sealed record CacheHandoff(double PositionSeconds, bool Play)
{
    public static CacheHandoff? Resolve(string completedSong, long completedVersion, string currentSong, long currentVersion,
        bool alreadyLocal, bool closed, double position, bool playIntended)
        => closed || alreadyLocal || completedVersion != currentVersion || completedSong != currentSong
            ? null : new(double.IsFinite(position) ? Math.Max(0, position) : 0, playIntended);
}
