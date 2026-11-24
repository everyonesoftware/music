using System.Collections.Frozen;
using System.Collections.ObjectModel;
using System.Runtime.ExceptionServices;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace Music.Core;

public sealed record CachedSong(Song Song, bool Complete, bool Pinned, long Bytes, DateTimeOffset Accessed)
{
    public string ContentType { get; init; } = "application/octet-stream";
    // Older indexes had no collection claims; their pins remain independent saves.
    public IReadOnlyDictionary<string, int>? DeviceOwners { get; init; }
    // A committed removal may still need to delete files. It is not a transfer.
    public bool PendingDeletion { get; init; }
    internal const string Individual = "";
    internal IEnumerable<string> Owners => DeviceOwners?.Keys ?? (Pinned ? [Individual] : []);
    public bool OwnedBy(string owner) => DeviceOwners is null ? Pinned && owner == Individual : DeviceOwners.GetValueOrDefault(owner) > 0;
    public bool HasActiveSave() => DeviceOwners is null ? Pinned : DeviceOwners.Values.Any(count => count > 0);
}

/// <summary>Private account/representation scoped media. Only atomically completed files are playable.</summary>
public sealed class SongCache : IDisposable
{
    private readonly string directory, metadata;
    private readonly object gate = new();
    private readonly object protectionGate = new();
    private readonly SemaphoreSlim writer = new(1);
    private List<CachedSong> entries;
    private IReadOnlyDictionary<string, CachedSong> snapshot = FrozenDictionary<string, CachedSong>.Empty;
    private HashSet<string> protectedSongs = new(StringComparer.Ordinal);
    private bool disposed;
    public SongCache(string root, string account)
    {
        directory = Path.Combine(root, Hash(account));
        Directory.CreateDirectory(directory);
        metadata = Path.Combine(directory, "index.json");
        try { entries = JsonSerializer.Deserialize(File.ReadAllText(metadata), PersistenceJsonContext.Default.ListCachedSong) ?? []; }
        catch (Exception e) when (e is IOException or JsonException) { entries = []; }
        entries = entries.Where(x => x?.Song is not null && !string.IsNullOrWhiteSpace(x.Song.Id) && !Uri.TryCreate(x.Song.Id, UriKind.Absolute, out _)).DistinctBy(x => x.Song.Id).ToList();
        for (int i = entries.Count - 1; i >= 0; i--)
        {
            var entry = entries[i];
            if (entry.PendingDeletion)
            {
                try { DeleteFiles(entry); entries.RemoveAt(i); }
                catch (Exception e) when (e is IOException or UnauthorizedAccessException)
                {
                    // Retain cleanup for a later attempt, applying the same metadata rules.
                    entries[i] = PendingRemoval(entry with { Song = SafeSong(entry.Song) });
                }
                continue;
            }
            bool complete = Available(entry);
            var path = PathFor(entry.Song.Id, complete);
            entries[i] = entry with { Song = SafeSong(entry.Song), Complete = complete, Bytes = File.Exists(path) ? new FileInfo(path).Length : 0,
                DeviceOwners = entry.DeviceOwners is null ? null : Freeze(new(entry.DeviceOwners, StringComparer.Ordinal)) };
        }
        var known = entries.Select(x => PathFor(x.Song.Id, x.Complete)).ToHashSet(StringComparer.OrdinalIgnoreCase);
        foreach (var entry in entries.Where(x => x.PendingDeletion))
        {
            known.Add(PathFor(entry.Song.Id));
            known.Add(PathFor(entry.Song.Id, false));
        }
        foreach (var file in Directory.EnumerateFiles(directory).Where(x => x.EndsWith(".media", StringComparison.Ordinal) || x.EndsWith(".partial", StringComparison.Ordinal)))
            if (!known.Contains(file)) File.Delete(file);
        try { Persist(); } catch (Exception e) when (e is IOException or UnauthorizedAccessException) { /* Existing complete media remains readable on a full/read-only disk. */ }
    }
    private static string Hash(string value) => Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(value)));
    private string PathFor(string id, bool complete = true) => Path.Combine(directory, Hash(id + "\noriginal-v1") + (complete ? ".media" : ".partial"));
    private void Persist()
    {
        PublishSnapshot();
        WriteIndex();
    }
    private void WriteIndex()
    {
        File.WriteAllText(metadata + ".tmp", JsonSerializer.Serialize(entries, PersistenceJsonContext.Default.ListCachedSong));
        File.Move(metadata + ".tmp", metadata, true);
    }
    private void PublishSnapshot() => Volatile.Write(ref snapshot,
        disposed ? FrozenDictionary<string, CachedSong>.Empty : entries.Where(x => !x.PendingDeletion).ToFrozenDictionary(x => x.Song.Id, StringComparer.Ordinal));

    /// <summary>Immutable metadata only; does not acquire the storage lock or inspect files.
    /// Complete reflects the last worker reconciliation, not a fresh filesystem check.</summary>
    public IReadOnlyDictionary<string, CachedSong> Snapshot() => Volatile.Read(ref snapshot);

    public string? Find(string id)
    {
        lock (gate)
        {
            var entry = entries.Find(x => x.Song.Id == id);
            var path = PathFor(id);
            if (disposed || entry is not { Complete: true, PendingDeletion: false } || !File.Exists(path) || new FileInfo(path).Length != entry.Bytes) return null;
            entries[entries.IndexOf(entry)] = entry with { Accessed = DateTimeOffset.UtcNow };
            try { Persist(); } catch (Exception e) when (e is IOException or UnauthorizedAccessException) { /* Recency is best-effort; never deny readable completed media. */ }
            return path;
        }
    }
    private bool Available(CachedSong entry) => entry.Complete && File.Exists(PathFor(entry.Song.Id)) && new FileInfo(PathFor(entry.Song.Id)).Length == entry.Bytes && entry.Bytes > 0;
    public IReadOnlyList<CachedSong> Recent()
    {
        lock (gate)
        {
            if (disposed) return [];
            entries = entries.Select(x => x.PendingDeletion ? x : x with { Complete = Available(x) }).ToList();
            PublishSnapshot();
            return entries.Where(x => !x.PendingDeletion).OrderByDescending(x => x.Accessed).ToArray();
        }
    }
    private static ReadOnlyDictionary<string, int> Freeze(Dictionary<string, int> owners) => new(owners);

    private static Song SafeSong(Song song)
    {
        if (string.IsNullOrWhiteSpace(song.Id) || Uri.TryCreate(song.Id, UriKind.Absolute, out _)) throw new ArgumentException("Unsigned song identifier required.");
        return song with { CoverArtId = song.CoverArtId is {} art && Uri.TryCreate(art, UriKind.Absolute, out _) ? null : song.CoverArtId };
    }
    public void Pin(IEnumerable<Song> songs, string owner = CachedSong.Individual)
    {
        var safe = songs.Select(SafeSong).ToArray();
        lock (gate)
        {
            ObjectDisposedException.ThrowIf(disposed, this);
            var prior = entries;
            var updated = entries.ToDictionary(x => x.Song.Id, StringComparer.Ordinal);
            var groups = safe.ToLookup(song => song.Id, StringComparer.Ordinal);
            if (owner != CachedSong.Individual)
            {
                // Remember older copies until explicit removal, but do not count them
                // as part of the latest explicitly saved collection membership.
                foreach (var entry in prior.Where(entry => entry.OwnedBy(owner) && !groups.Contains(entry.Song.Id)))
                {
                    if (entry.Complete) updated[entry.Song.Id] = WithOwner(entry, owner, 0);
                    else
                    {
                        var remaining = new Dictionary<string, int>(entry.DeviceOwners!, StringComparer.Ordinal);
                        remaining.Remove(owner);
                        updated[entry.Song.Id] = entry with { Pinned = remaining.Count != 0, DeviceOwners = Freeze(remaining) };
                    }
                }
            }
            foreach (var members in groups)
            {
                var song = members.First();
                var entry = updated.GetValueOrDefault(song.Id) ?? new(song, false, false, 0, DateTimeOffset.UtcNow);
                if (entry.PendingDeletion)
                {
                    // Removal is already durable. Do not replace its cleanup record
                    // with transfer intent while the old files are still blocked.
                    DeleteFiles(entry);
                    entry = new(song, false, false, 0, DateTimeOffset.UtcNow);
                }
                if (owner == CachedSong.Individual && entry.OwnedBy(owner)) continue;
                updated[song.Id] = owner == CachedSong.Individual && entry.DeviceOwners is null
                    ? entry with { Pinned = true } : WithOwner(entry, owner, members.Count());
            }
            entries = updated.Values.ToList();
            try { WriteIndex(); }
            catch { entries = prior; throw; }
            PublishSnapshot();
        }
    }
    private static CachedSong WithOwner(CachedSong entry, string owner, int occurrences)
    {
        var owners = entry.Owners.ToDictionary(key => key, key => entry.DeviceOwners?.GetValueOrDefault(key) ?? 1, StringComparer.Ordinal);
        owners[owner] = occurrences;
        return entry with { Pinned = true, DeviceOwners = Freeze(owners) };
    }

    public Task SaveAsync(Song song, bool pinned, Func<Stream, CancellationToken, Task> download, CancellationToken token = default)
        => SaveMediaAsync(song, pinned, async (output, cancel) => { await download(output, cancel).ConfigureAwait(false); return "application/octet-stream"; }, token);
    public async Task SaveMediaAsync(Song song, bool pinned, Func<Stream, CancellationToken, Task<string>> download, CancellationToken token = default)
    {
        song = SafeSong(song);
        await writer.WaitAsync(token).ConfigureAwait(false);
        try
        {
            lock (gate)
            {
                ObjectDisposedException.ThrowIf(disposed, this);
                int index = entries.FindIndex(x => x.Song.Id == song.Id);
                var old = index >= 0 ? entries[index] : null;
                if (old is { PendingDeletion: true }) DeleteFiles(old);
                IReadOnlyDictionary<string, int>? owners = old?.DeviceOwners;
                if (pinned && old is not null && !old.OwnedBy(CachedSong.Individual))
                    owners = WithOwner(old, CachedSong.Individual, 1).DeviceOwners;
                bool alreadyAvailable = old is not null && Available(old);
                bool retain = pinned || old?.Pinned == true;
                var updated = alreadyAvailable
                    ? old! with { Pinned = retain, DeviceOwners = owners }
                    : new CachedSong(song, false, retain, 0, DateTimeOffset.UtcNow) { DeviceOwners = owners };
                if (index >= 0) entries[index] = updated;
                else entries.Add(updated);
                // Commit admission before exposing retry intent or replacing a removal marker.
                try { WriteIndex(); }
                catch
                {
                    if (index >= 0) entries[index] = old!;
                    else entries.RemoveAt(entries.Count - 1);
                    throw;
                }
                PublishSnapshot();
                if (alreadyAvailable) return;
            }
            try
            {
                string contentType;
                await using (var output = new FileStream(PathFor(song.Id, false), FileMode.Create, FileAccess.Write, FileShare.Read, 65536, true))
                { contentType = await download(output, token).ConfigureAwait(false); await output.FlushAsync(token).ConfigureAwait(false); if (output.Length == 0) throw new IOException("Empty media."); }
                token.ThrowIfCancellationRequested();
                lock (gate)
                {
                    ObjectDisposedException.ThrowIf(disposed, this);
                    File.Move(PathFor(song.Id, false), PathFor(song.Id), true);
                    var i = entries.FindIndex(x => x.Song.Id == song.Id);
                    entries[i] = entries[i] with { Complete = true, Bytes = new FileInfo(PathFor(song.Id)).Length, ContentType = contentType };
                    Persist();
                }
            }
            catch
            {
                lock (gate)
                {
                    var i = entries.FindIndex(x => x.Song.Id == song.Id);
                    if (!disposed && i >= 0 && File.Exists(PathFor(song.Id, false)))
                    { entries[i] = entries[i] with { Bytes = new FileInfo(PathFor(song.Id, false)).Length }; Persist(); }
                }
                throw;
            }
        }
        finally { writer.Release(); }
    }
    private void Delete(CachedSong entry)
    {
        DeleteFiles(entry); entries.Remove(entry);
    }
    private void DeleteFiles(CachedSong entry)
    {
        File.Delete(PathFor(entry.Song.Id)); File.Delete(PathFor(entry.Song.Id, false));
    }
    /// <summary>Publish playback protection without waiting for storage IO.</summary>
    public void SetProtectedSongs(IEnumerable<string> ids)
    {
        var next = ids.ToHashSet(StringComparer.Ordinal);
        lock (protectionGate) protectedSongs = next;
    }
    // Caller holds gate. Logical eviction linearizes against selection here;
    // never hold protectionGate during disk IO, or acquire gate while holding it.
    private bool DeleteIfUnprotected(CachedSong entry, ref ExceptionDispatchInfo? cleanupFailure)
    {
        lock (protectionGate)
        {
            if (protectedSongs.Contains(entry.Song.Id)) return false;
            if (!entries.Remove(entry)) return false;
        }
        try { DeleteFiles(entry); }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            // Keep failed deletions retryable; earlier deletions in this batch still succeeded.
            entries.Add(entry);
            // An already committed removal must not block eviction of unrelated media.
            if (entry.PendingDeletion)
            {
                cleanupFailure ??= ExceptionDispatchInfo.Capture(e);
                return false;
            }
            PublishSnapshot();
            throw;
        }
        return true;
    }
    public void Trim(long limit, IEnumerable<string> protectedIds)
    {
        lock (gate)
        {
            var keep = protectedIds.ToHashSet(StringComparer.Ordinal);
            ExceptionDispatchInfo? cleanupFailure = null;
            long size = entries.Where(x => !x.Pinned).Sum(x => x.Bytes);
            foreach (var entry in entries.Where(x => !x.Pinned && !keep.Contains(x.Song.Id)).OrderBy(x => x.Accessed).ToArray())
            {
                if (size <= limit) break;
                if (!DeleteIfUnprotected(entry, ref cleanupFailure)) continue;
                size -= entry.Bytes;
            }
            Persist();
            cleanupFailure?.Throw();
        }
    }
    public void ClearAutomatic(IEnumerable<string> protectedIds)
    {
        lock (gate)
        {
            var keep = protectedIds.ToHashSet(StringComparer.Ordinal);
            ExceptionDispatchInfo? cleanupFailure = null;
            foreach (var entry in entries.Where(x => !x.Pinned && !keep.Contains(x.Song.Id)).ToArray())
                DeleteIfUnprotected(entry, ref cleanupFailure);
            Persist();
            cleanupFailure?.Throw();
        }
    }
    /// <summary>Returns false when live playback protection refuses removal; absent downloads succeed.</summary>
    public bool RemoveDownload(string id)
    {
        lock (gate)
        {
            ObjectDisposedException.ThrowIf(disposed, this);
            var entry = entries.Find(x => x.Song.Id == id);
            if (entry is null) return true;
            var updated = entries.Select(x => x == entry ? PendingRemoval(x) : x).ToList();
            return CommitDeviceRemoval(updated, [entry]);
        }
    }
    public bool RemoveCollection(string owner)
    {
        if (string.IsNullOrEmpty(owner)) throw new ArgumentException("A collection owner is required.", nameof(owner));
        lock (gate)
        {
            ObjectDisposedException.ThrowIf(disposed, this);
            var owned = entries.Where(x => x.DeviceOwners?.ContainsKey(owner) == true).ToArray();
            var remaining = owned.ToDictionary(x => x.Song.Id,
                x => x.DeviceOwners!.Where(pair => pair.Key != owner).ToDictionary(StringComparer.Ordinal), StringComparer.Ordinal);
            if (owned.Length == 0) return true;
            var deletions = owned.Where(x => remaining[x.Song.Id].Count == 0).ToArray();
            var updated = entries.Select(x => !remaining.TryGetValue(x.Song.Id, out var keys) ? x
                : keys.Count == 0 ? PendingRemoval(x) : x with { Pinned = true, DeviceOwners = Freeze(keys) }).ToList();
            return CommitDeviceRemoval(updated, deletions);
        }
    }
    private static CachedSong PendingRemoval(CachedSong entry) =>
        entry with { Complete = false, Pinned = false, DeviceOwners = null, PendingDeletion = true };

    // Caller holds gate. Commit logical removal before touching bytes, and keep
    // failed cleanup separate from both playable media and interrupted transfers.
    private bool CommitDeviceRemoval(List<CachedSong> updated, CachedSong[] deletions)
    {
        var prior = entries;
        lock (protectionGate)
        {
            if (deletions.Any(x => protectedSongs.Contains(x.Song.Id))) return false;
            entries = updated;
        }
        try { WriteIndex(); }
        catch { entries = prior; throw; }
        PublishSnapshot();

        var cleaned = new HashSet<string>(StringComparer.Ordinal);
        try
        {
            foreach (var entry in deletions)
            {
                DeleteFiles(entry);
                cleaned.Add(entry.Song.Id);
            }
        }
        finally
        {
            // Cleared markers can be compacted on the next index write or reopen.
            entries.RemoveAll(x => cleaned.Contains(x.Song.Id));
        }
        return true;
    }
    public void Purge()
    { lock (gate) { foreach (var entry in entries.ToArray()) Delete(entry); Persist(); } }
    public void Dispose() { lock (gate) { disposed = true; PublishSnapshot(); } }
}
