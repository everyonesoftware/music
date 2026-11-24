using System.Text.Json;

namespace Music.Core;

public enum PlaylistDeletionResult { Acknowledged, AbsentAfterUncertain, StillPresent, Unverified }

public sealed partial class NavidromeClient
{
    // Navidrome normalizes ASCII username casing. Do not broaden destructive
    // ownership to Unicode case-folded aliases or missing owner metadata.
    private static bool SameAsciiUser(string owner, string username)
    {
        if (owner.Length != username.Length) return false;
        for (var i = 0; i < owner.Length; i++)
            if (Fold(owner[i]) != Fold(username[i])) return false;
        return true;

        static char Fold(char value) => value is >= 'A' and <= 'Z' ? (char)(value + ('a' - 'A')) : value;
    }

    public async Task<(PlaylistDeletionResult Outcome, Playlist? Present)> DeletePlaylistAsync(Playlist playlist, Func<bool> current,
        CancellationToken cancellationToken = default)
    {
        CheckCurrent();
        if (!playlist.CanDelete || string.IsNullOrWhiteSpace(playlist.Id))
            throw new NavidromeException("Only the owner can delete a writable playlist.");

        var latest = await ReadDeletionTargetAsync(playlist.Id, cancellationToken);
        CheckCurrent();
        if (latest is not { CanDelete: true } || latest.Name != playlist.Name)
            throw new NavidromeException("This playlist changed or is no longer writable. Refresh before trying again. Nothing was deleted by Music.");

        // Preflight is not an atomic server-side precondition; the server still
        // has to enforce authority on the write itself.
        using var request = new HttpRequestMessage(HttpMethod.Post, ApiUri("deletePlaylist"))
        {
            Content = new FormUrlEncodedContent(Authentication().Prepend(("id", playlist.Id))
                .Select(pair => KeyValuePair.Create(pair.Item1, pair.Item2)))
        };
        JsonElement? response = null;
        try
        {
            response = ParseResponse(await Download(request, "deletePlaylist", 8 * 1024 * 1024, cancellationToken), uniqueThroughout: true);
        }
        catch (NavidromeException) { /* A lost or invalid reply does not prove the write failed. */ }

        if (response is { } root)
        {
            if (Text(root, "status") == "ok" && !root.TryGetProperty("error", out _) && !root.TryGetProperty("playlist", out _))
                return (PlaylistDeletionResult.Acknowledged, null);
            if (FailureCode(root) is (10 or 20 or 30 or >= 40 and <= 44 or 50) and var rejected)
                throw ResponseError(rejected);
        }

        // Never replay the write. Only a valid response for this exact ID can
        // establish absence; an HTTP error or invalid response cannot.
        return await CheckPlaylistDeletionAsync(playlist.Id, cancellationToken);

        void CheckCurrent()
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (!current()) throw new OperationCanceledException("The playlist action is no longer current.");
            RequireOnline();
        }
    }

    public async Task<(PlaylistDeletionResult Outcome, Playlist? Present)> CheckPlaylistDeletionAsync(string id,
        CancellationToken cancellationToken = default)
    {
        try
        {
            var present = await ReadDeletionTargetAsync(id, cancellationToken).ConfigureAwait(false);
            return (present is null ? PlaylistDeletionResult.AbsentAfterUncertain : PlaylistDeletionResult.StillPresent, present);
        }
        catch (NavidromeException) { return (PlaylistDeletionResult.Unverified, null); }
    }

    private async Task<Playlist?> ReadDeletionTargetAsync(string id, CancellationToken token)
    {
        var root = ParseResponse(await Download("getPlaylist", 8 * 1024 * 1024, token, ("id", id)).ConfigureAwait(false), uniqueThroughout: true);
        var failure = FailureCode(root);
        if (failure == 70) return null;
        if (Text(root, "status") != "ok" || root.TryGetProperty("error", out _)) throw ResponseError(failure ?? 0);
        var playlist = ParsePlaylist(Object(root, "playlist"));
        return playlist.Id == id ? playlist : throw InvalidResponse();
    }

    private static int? FailureCode(JsonElement root) =>
        Text(root, "status") == "failed" && !root.TryGetProperty("playlist", out _) &&
        root.TryGetProperty("error", out var error) && error.ValueKind == JsonValueKind.Object ? ReadCount(error, "code") : null;
}