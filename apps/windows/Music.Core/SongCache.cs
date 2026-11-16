using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace Music.Core;

public sealed record CachedSong(Song Song, bool Complete, bool Pinned, long Bytes, DateTimeOffset Accessed)
{
    public string ContentType { get; init; } = "application/octet-stream";
}

/// <summary>Private account/representation scoped media. Only atomically completed files are playable.</summary>
public sealed class SongCache : IDisposable
{
    private readonly string directory, metadata;
    private readonly object gate = new();
    private readonly SemaphoreSlim writer = new(1);
    private List<CachedSong> entries;
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
        for (int i = 0; i < entries.Count; i++)
        {
            var entry = entries[i];
            bool complete = Available(entry);
            var path = PathFor(entry.Song.Id, complete);
            entries[i] = entry with { Song = SafeSong(entry.Song), Complete = complete, Bytes = File.Exists(path) ? new FileInfo(path).Length : 0 };
        }
        var known = entries.Select(x => PathFor(x.Song.Id, x.Complete)).ToHashSet(StringComparer.OrdinalIgnoreCase);
        foreach (var file in Directory.EnumerateFiles(directory).Where(x => x.EndsWith(".media", StringComparison.Ordinal) || x.EndsWith(".partial", StringComparison.Ordinal)))
            if (!known.Contains(file)) File.Delete(file);
        try { Persist(); } catch (Exception e) when (e is IOException or UnauthorizedAccessException) { /* Existing complete media remains readable on a full/read-only disk. */ }
    }
    private static string Hash(string value) => Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(value)));
    private string PathFor(string id, bool complete = true) => Path.Combine(directory, Hash(id + "\noriginal-v1") + (complete ? ".media" : ".partial"));
    private void Persist()
    {
        File.WriteAllText(metadata + ".tmp", JsonSerializer.Serialize(entries, PersistenceJsonContext.Default.ListCachedSong));
        File.Move(metadata + ".tmp", metadata, true);
    }
    public string? Find(string id)
    {
        lock (gate)
        {
            var entry = entries.Find(x => x.Song.Id == id);
            var path = PathFor(id);
            if (disposed || entry is not { Complete: true } || !File.Exists(path) || new FileInfo(path).Length != entry.Bytes) return null;
            entries[entries.IndexOf(entry)] = entry with { Accessed = DateTimeOffset.UtcNow };
            try { Persist(); } catch (Exception e) when (e is IOException or UnauthorizedAccessException) { /* Recency is best-effort; never deny readable completed media. */ }
            return path;
        }
    }
    private bool Available(CachedSong entry) => entry.Complete && File.Exists(PathFor(entry.Song.Id)) && new FileInfo(PathFor(entry.Song.Id)).Length == entry.Bytes && entry.Bytes > 0;
    public IReadOnlyList<CachedSong> Recent()
    { lock (gate) return disposed ? [] : entries.Select(x => x with { Complete = Available(x) }).OrderByDescending(x => x.Accessed).ToArray(); }
    private static Song SafeSong(Song song)
    {
        if (string.IsNullOrWhiteSpace(song.Id) || Uri.TryCreate(song.Id, UriKind.Absolute, out _)) throw new ArgumentException("Unsigned song identifier required.");
        return song with { CoverArtId = song.CoverArtId is {} art && Uri.TryCreate(art, UriKind.Absolute, out _) ? null : song.CoverArtId };
    }
    public void Pin(IEnumerable<Song> songs)
    {
        var safe = songs.Select(SafeSong).ToArray();
        lock (gate)
        {
            ObjectDisposedException.ThrowIf(disposed, this);
            foreach (var song in safe)
            {
                int index = entries.FindIndex(x => x.Song.Id == song.Id);
                if (index >= 0) entries[index] = entries[index] with { Pinned = true };
                else entries.Add(new(song, false, true, 0, DateTimeOffset.UtcNow));
            }
            Persist();
        }
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
                var old = entries.Find(x => x.Song.Id == song.Id);
                if (old is not null && Available(old))
                { entries[entries.IndexOf(old)] = old with { Pinned = pinned || old.Pinned }; Persist(); return; }
                entries.RemoveAll(x => x.Song.Id == song.Id);
                entries.Add(new(song, false, pinned || old?.Pinned == true, 0, DateTimeOffset.UtcNow)); Persist();
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
        File.Delete(PathFor(entry.Song.Id)); File.Delete(PathFor(entry.Song.Id, false)); entries.Remove(entry);
    }
    /// <summary>Publish live playback protection under the same lock as eviction and deletion.</summary>
    public void SetProtectedSongs(IEnumerable<string> ids)
    {
        lock (gate) protectedSongs = ids.ToHashSet(StringComparer.Ordinal);
    }
    public void Trim(long limit, IEnumerable<string> protectedIds)
    {
        lock (gate)
        {
            var keep = protectedIds.ToHashSet(StringComparer.Ordinal);
            keep.UnionWith(protectedSongs);
            long size = entries.Where(x => !x.Pinned).Sum(x => x.Bytes);
            foreach (var entry in entries.Where(x => !x.Pinned && !keep.Contains(x.Song.Id)).OrderBy(x => x.Accessed).ToArray())
            { if (size <= limit) break; Delete(entry); size -= entry.Bytes; }
            Persist();
        }
    }
    public void ClearAutomatic(IEnumerable<string> protectedIds)
    {
        lock (gate)
        {
            var keep = protectedIds.ToHashSet(StringComparer.Ordinal);
            keep.UnionWith(protectedSongs);
            foreach (var entry in entries.Where(x => !x.Pinned && !keep.Contains(x.Song.Id)).ToArray()) Delete(entry);
            Persist();
        }
    }
    public void RemoveDownload(string id)
    { lock (gate) { var entry = entries.Find(x => x.Song.Id == id); if (entry is not null) Delete(entry); Persist(); } }
    public void Purge()
    { lock (gate) { foreach (var entry in entries.ToArray()) Delete(entry); Persist(); } }
    public void Dispose() { lock (gate) disposed = true; }
}
