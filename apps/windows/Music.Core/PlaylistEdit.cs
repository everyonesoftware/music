namespace Music.Core;

public sealed class PlaylistWriteUncertainException() : IOException(
    "The server did not confirm this edit. Check the playlist before submitting it again; retrying could create duplicates.");

public sealed class PlaylistSourceChangedException() : IOException(
    "Music could not verify the same songs. Reload the selection and review it before trying again. Nothing was submitted.");

/// <summary>One immutable edit. Once acknowledged, retry only the read of that exact destination.</summary>
public sealed class PlaylistEdit
{
    public const int MaxNameLength = 80;
    private readonly NavidromeClient client;
    private readonly string? name, playlistId;
    private readonly string[] songIds;
    private readonly object? source;
    private string? acknowledgedId;
    private string[] baselineIds = [];
    private bool uncertain;
    private int running;
    public bool Acknowledged => acknowledgedId is not null;
    public string? DestinationId => acknowledgedId ?? playlistId;

    public static bool IsValidName(string? value) => value is { Length: > 0 and <= MaxNameLength } &&
        !string.IsNullOrWhiteSpace(value) && !value.Any(char.IsControl);

    public PlaylistEdit(NavidromeClient client, IReadOnlyList<string> songIds, string? name = null, string? playlistId = null, object? source = null)
    {
        name = name?.Trim();
        if ((playlistId is null ? !IsValidName(name) : name is not null || string.IsNullOrWhiteSpace(playlistId) || songIds.Count == 0) ||
            songIds.Any(string.IsNullOrWhiteSpace)) throw new ArgumentException("Enter a valid playlist name or choose a destination and songs.");
        this.client = client;
        this.name = name;
        this.playlistId = playlistId;
        this.songIds = songIds.ToArray();
        this.source = source;
    }

    public static async Task<IReadOnlyList<Song>> ReadSourceAsync(NavidromeClient client, object source, CancellationToken token)
    {
        switch (source)
        {
            case Song song: return [await client.GetPlaylistSourceSongAsync(song.Id, token).ConfigureAwait(false)];
            case Album album: return await client.GetPlaylistSourceAlbumSongsAsync(album.Id, token).ConfigureAwait(false);
            case Playlist playlist: return (await client.GetPlaylistContentsAsync(playlist.Id, token).ConfigureAwait(false)).Songs;
            case Artist artist:
                var library = await client.GetArtistAsync(artist.Id, token, requireCounts: true).ConfigureAwait(false);
                if (library.IsComplete) return library.Songs;
                throw new NavidromeException("Could not verify all songs by this artist. Nothing was added.");
            default: throw new ArgumentException("Unsupported playlist source.", nameof(source));
        }
    }

    public async Task<PlaylistContents> ConfirmAsync(Func<bool> current, CancellationToken cancellationToken)
    {
        if (Interlocked.Exchange(ref running, 1) != 0) throw new InvalidOperationException("A playlist edit is already in progress.");
        try
        {
            if (uncertain) throw new PlaylistWriteUncertainException();
            CheckCurrent();
            if (client.Offline) throw new NavidromeException("Turn off Offline mode to edit playlists.");
            if (acknowledgedId is null)
            {
                if (playlistId is not null)
                {
                    var destination = await client.GetPlaylistContentsAsync(playlistId, cancellationToken);
                    if (!destination.Playlist.CanAppend) throw new NavidromeException("This playlist is read-only. Choose another playlist.");
                    baselineIds = destination.Songs.Select(song => song.Id).ToArray();
                }
                CheckCurrent();
                if (source is not null)
                {
                    IReadOnlyList<Song> latest;
                    try { latest = await ReadSourceAsync(client, source, cancellationToken); }
                    catch (NavidromeException) { throw new PlaylistSourceChangedException(); }
                    if (latest.Count == 0 || !latest.Select(song => song.Id).SequenceEqual(songIds, StringComparer.Ordinal))
                        throw new PlaylistSourceChangedException();
                }
                CheckCurrent();
                try
                {
                    acknowledgedId = playlistId is null
                        ? await client.CreatePlaylistAsync(name!, songIds, cancellationToken)
                        : await client.AppendPlaylistAsync(playlistId, songIds, cancellationToken);
                }
                catch (Exception ex) when (ex is PlaylistWriteUncertainException or OperationCanceledException)
                {
                    // Cancellation while awaiting a non-idempotent request does not roll it back.
                    uncertain = true;
                    throw;
                }
            }
            CheckCurrent();
            var confirmed = await client.GetPlaylistContentsAsync(acknowledgedId, cancellationToken);
            CheckCurrent();
            if (!MatchesReadback(confirmed))
                throw new NavidromeException("The server acknowledged the edit, but the ordered songs could not be confirmed.");
            return confirmed;
        }
        finally { Volatile.Write(ref running, 0); }

        void CheckCurrent()
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (!current()) throw new OperationCanceledException("The playlist action is no longer current.");
        }
    }

    private bool MatchesReadback(PlaylistContents confirmed)
    {
        var actual = confirmed.Songs.Select(song => song.Id).ToArray().AsSpan();
        if (playlistId is null)
            return actual.StartsWith(songIds);
        // A concurrent append can land on either side of our batch. Never count
        // occurrences already present before the write, or accept a partial batch.
        return actual.StartsWith(baselineIds) && actual[baselineIds.Length..].IndexOf(songIds) >= 0;
    }
}
