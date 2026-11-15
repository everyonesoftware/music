using Microsoft.Data.Sqlite;

namespace Music.Core;

/// <summary>Safe diagnostics: never include exception messages, paths, or server metadata.</summary>
public static class SessionSaveFailure
{
    public static string Code(Exception error) => error switch
    {
        SqliteException sqlite => (sqlite.SqliteErrorCode & 0xff) switch
        {
            13 => "storage-full",
            8 => "storage-read-only",
            5 or 6 => "storage-busy",
            11 or 26 => "storage-damaged",
            3 or 23 => "storage-permission",
            10 or 14 => "storage-io",
            _ => "storage-error"
        },
        UnauthorizedAccessException => "storage-permission",
        IOException => "storage-io",
        ArgumentException argument => argument.ParamName switch
        {
            nameof(PlaybackSnapshot.ConnectionId) => "invalid-connection",
            nameof(PlaybackSnapshot.Queue) => "invalid-queue",
            nameof(PlaybackSnapshot.Index) => "invalid-queue-index",
            nameof(PlaybackSnapshot.PositionSeconds) => "invalid-playback-position",
            nameof(Song.Id) => "invalid-song-id",
            nameof(Song.CoverArtId) => "invalid-artwork-id",
            _ => "invalid-metadata"
        },
        _ => "unexpected"
    };

    public static string Message(Exception error)
    {
        string code = Code(error);
        string advice = code switch
        {
            "storage-full" => "Free some disk space and try again.",
            "storage-read-only" or "storage-permission" => "Check write access to Music's local app data.",
            "storage-busy" => "Close other Music windows and try again.",
            "storage-damaged" => "The local session database needs repair; your server library is unaffected.",
            "invalid-connection" => "The local session has no connection identifier. Report this error code.",
            "invalid-queue" => "The local session has no queue. Report this error code.",
            "invalid-queue-index" => "The selected track is outside the local session queue. Report this error code.",
            "invalid-playback-position" => "The playback position cannot be saved. Report this error code.",
            "invalid-song-id" => "A queued song has a missing identifier or one recognized as a URL. Report this error code.",
            "invalid-artwork-id" => "Queued artwork has an identifier recognized as a URL. Report this error code.",
            "invalid-metadata" => "The queue contains metadata that cannot be safely saved.",
            _ => "Try again; if it persists, report this error code."
        };
        return $"Playback continues, but the local session could not be saved ({code}). {advice}";
    }
}
