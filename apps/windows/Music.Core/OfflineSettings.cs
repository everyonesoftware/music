namespace Music.Core;

public sealed record OfflineSettings
{
    public bool SavePlayed { get; init; } = true;
    public long CacheLimitBytes { get; init; } = 2L * 1024 * 1024 * 1024;
    public int NextCount { get; init; } = 3;
    public bool MobilePrefetch { get; init; }
    public bool Offline { get; init; }
    public bool CanPrefetch(bool unmeteredWifi, bool connected) => !Offline && NextCount > 0 && (unmeteredWifi || (MobilePrefetch && connected));
}

public static class OfflinePolicy
{
    public static IReadOnlyList<Song> Plan(IReadOnlyList<Song> queue, int index, IReadOnlyList<CachedSong> recent, OfflineSettings settings, bool prefetchAllowed, IReadOnlyList<Song>? resolvedUpcoming = null)
    {
        if (settings.Offline || index < 0 || index >= queue.Count) return [];
        IEnumerable<Song> result = settings.SavePlayed ? new[] { queue[index] } : [];
        if (prefetchAllowed)
        {
            var upcoming = (resolvedUpcoming ?? queue.Skip(index + 1).ToArray()).Take(settings.NextCount).ToArray();
            result = result.Concat(upcoming.Take(1))
                .Concat(recent.Where(x => !x.Complete).Take(3).Select(x => x.Song))
                .Concat(upcoming.Skip(1));
        }
        return result.DistinctBy(x => x.Id).ToArray();
    }
}
