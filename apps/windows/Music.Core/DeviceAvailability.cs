using System.Runtime.CompilerServices;

namespace Music.Core;

public enum DeviceSongState { Streamed, Cached, Saved }

public static class DeviceAvailability
{
    public static bool HasCollectionClaims(IReadOnlyDictionary<string, CachedSong> snapshot, string key) =>
        CollectionCounts.GetValue(snapshot, CountOccurrences).ContainsKey(key);

    public static DeviceSongState Song(IReadOnlyDictionary<string, CachedSong> snapshot, string id) =>
        snapshot.TryGetValue(id, out var entry) && entry.Complete
            ? entry.Pinned ? DeviceSongState.Saved : DeviceSongState.Cached
            : DeviceSongState.Streamed;

    public static (bool Requested, bool Saved) Collection(IReadOnlyDictionary<string, CachedSong> snapshot,
        string key, IReadOnlyList<Song> tracks)
    {
        if (tracks.Count == 0) return (false, false);
        bool complete = true;
        foreach (var track in tracks)
        {
            if (!snapshot.TryGetValue(track.Id, out var entry) || !entry.OwnedBy(key)) return (false, false);
            complete &= entry.Complete;
        }
        bool exact = complete && CollectionCounts.GetValue(snapshot, CountOccurrences).TryGetValue(key, out long count) &&
            count == tracks.Count && tracks.GroupBy(track => track.Id, StringComparer.Ordinal)
                .All(group => snapshot[group.Key].DeviceOwners?.GetValueOrDefault(key) == group.Count());
        return (true, exact);
    }
    // The cache publishes immutable snapshots. Index all collection summaries only
    // once per snapshot instead of scanning the whole cache for each realized card.
    private static readonly ConditionalWeakTable<IReadOnlyDictionary<string, CachedSong>, Dictionary<string, long>> CollectionCounts = new();

    public static bool SavedCard(IReadOnlyDictionary<string, CachedSong> snapshot, string key, int expectedCount,
        IReadOnlyList<Song>? verifiedMembership) =>
        verifiedMembership is not null && verifiedMembership.Count == expectedCount && Collection(snapshot, key, verifiedMembership).Saved;

    private static Dictionary<string, long> CountOccurrences(IReadOnlyDictionary<string, CachedSong> snapshot)
    {
        var states = new Dictionary<string, long>(StringComparer.Ordinal);
        foreach (var entry in snapshot.Values)
        {
            if (entry.DeviceOwners is null) continue;
            foreach (var (key, occurrences) in entry.DeviceOwners)
            {
                if (key.Length == 0) continue;
                // A retained zero-count claim is still removable, but not current membership.
                states[key] = states.GetValueOrDefault(key) + Math.Max(occurrences, 0);
            }
        }
        return states;
    }
}
