using System.Text.Json;
using Microsoft.Data.Sqlite;

namespace Music.Core;

/// <summary>Stores only unsigned song metadata and playback position. UI must restore paused.</summary>
public sealed class SessionStore : IDisposable
{
    private readonly SqliteConnection database;

    public SessionStore(string databasePath)
    {
        database = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = databasePath, Pooling = false }.ToString());
        database.Open();
        using var command = database.CreateCommand();
        command.CommandText = "CREATE TABLE IF NOT EXISTS playback (connection_id TEXT PRIMARY KEY NOT NULL, snapshot TEXT NOT NULL); CREATE TABLE IF NOT EXISTS views (collection TEXT PRIMARY KEY NOT NULL, mode TEXT NOT NULL);";
        command.ExecuteNonQuery();
    }

    public void SavePlayback(PlaybackSnapshot snapshot)
    {
        Validate(snapshot);
        using var command = database.CreateCommand();
        command.CommandText = "INSERT INTO playback (connection_id, snapshot) VALUES ($id, $snapshot) ON CONFLICT(connection_id) DO UPDATE SET snapshot = excluded.snapshot;";
        command.Parameters.AddWithValue("$id", snapshot.ConnectionId);
        command.Parameters.AddWithValue("$snapshot", JsonSerializer.Serialize(snapshot, PersistenceJsonContext.Default.PlaybackSnapshot));
        command.ExecuteNonQuery();
    }

    public PlaybackSnapshot? LoadPlayback(string connectionId)
    {
        using var command = database.CreateCommand();
        command.CommandText = "SELECT snapshot FROM playback WHERE connection_id = $id;";
        command.Parameters.AddWithValue("$id", connectionId);
        if (command.ExecuteScalar() is not string json) return null;
        try
        {
            var snapshot = JsonSerializer.Deserialize(json, PersistenceJsonContext.Default.PlaybackSnapshot);
            if (snapshot is null || snapshot.ConnectionId != connectionId) return null;
            Validate(snapshot);
            return snapshot;
        }
        catch (Exception ex) when (ex is JsonException or ArgumentException) { return null; }
    }

    public void SaveView(string collection, string mode)
    {
        using var command = database.CreateCommand();
        command.CommandText = "INSERT INTO views (collection, mode) VALUES ($collection, $mode) ON CONFLICT(collection) DO UPDATE SET mode = excluded.mode;";
        command.Parameters.AddWithValue("$collection", collection);
        command.Parameters.AddWithValue("$mode", mode);
        command.ExecuteNonQuery();
    }

    public string GetView(string collection, string fallback)
    {
        using var command = database.CreateCommand();
        command.CommandText = "SELECT mode FROM views WHERE collection = $collection;";
        command.Parameters.AddWithValue("$collection", collection);
        return command.ExecuteScalar() as string ?? fallback;
    }

    private static void Validate(PlaybackSnapshot snapshot)
    {
        if (string.IsNullOrWhiteSpace(snapshot.ConnectionId) || snapshot.Queue is null ||
            !double.IsFinite(snapshot.PositionSeconds) || snapshot.PositionSeconds < 0 ||
            (snapshot.Queue.Count == 0 ? snapshot.Index != -1 || snapshot.PositionSeconds != 0 : snapshot.Index < 0 || snapshot.Index >= snapshot.Queue.Count) ||
            snapshot.Queue.Any(song => song is null || string.IsNullOrWhiteSpace(song.Id) ||
                Uri.TryCreate(song.Id, UriKind.Absolute, out _) ||
                (song.CoverArtId is not null && Uri.TryCreate(song.CoverArtId, UriKind.Absolute, out _))))
            throw new ArgumentException("Invalid playback snapshot. Store unsigned song identifiers and a valid queue position.");
    }

    public void Dispose() => database.Dispose();
}
