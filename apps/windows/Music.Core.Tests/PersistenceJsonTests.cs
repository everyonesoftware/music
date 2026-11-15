using Microsoft.Data.Sqlite;
using Music.Core;
using System.Text.Json;
using System.Text.Json.Serialization.Metadata;
using Xunit;

namespace Music.Core.Tests;

public sealed class PersistenceJsonTests
{
    private const string LegacySnapshotJson = """{"ConnectionId":"home","Queue":[{"Id":"song-1","Title":"Track","Artist":"Artist","Album":"Album","CoverArtId":null,"DurationSeconds":123,"UserRating":4}],"Index":0,"PositionSeconds":12.25,"Shuffle":true,"Repeat":false}""";

    [Fact]
    public void Source_generated_profile_metadata_preserves_legacy_json_and_roundtrips()
    {
        const string json = """{"Id":"home","Name":"Home","ServerUrl":"https://music.invalid","Username":"listener"}""";
        var metadata = PersistenceJsonContext.Default.ConnectionProfile;
        Assert.Same(metadata, Assert.IsAssignableFrom<JsonTypeInfo<ConnectionProfile>>(PersistenceJsonContext.Default.GetTypeInfo(typeof(ConnectionProfile))));
        var profile = Assert.IsType<ConnectionProfile>(JsonSerializer.Deserialize(json, metadata));

        Assert.Equal(new ConnectionProfile("home", "Home", "https://music.invalid", "listener"), profile);
        Assert.Equal(json, JsonSerializer.Serialize(profile, metadata));
        Assert.Equal(profile, JsonSerializer.Deserialize(JsonSerializer.Serialize(profile, metadata), metadata));
    }

    [Fact]
    public void Source_generated_snapshot_metadata_preserves_legacy_json_and_roundtrips()
    {
        var metadata = PersistenceJsonContext.Default.PlaybackSnapshot;
        var snapshot = Assert.IsType<PlaybackSnapshot>(JsonSerializer.Deserialize(LegacySnapshotJson, metadata));
        Assert.Equal(LegacySnapshotJson, JsonSerializer.Serialize(snapshot, metadata));

        var roundtrip = Assert.IsType<PlaybackSnapshot>(JsonSerializer.Deserialize(JsonSerializer.Serialize(snapshot, metadata), metadata));
        Assert.Equal(snapshot.ConnectionId, roundtrip.ConnectionId);
        Assert.Equal(snapshot.Queue, roundtrip.Queue);
        Assert.Equal(snapshot.Index, roundtrip.Index);
        Assert.Equal(snapshot.PositionSeconds, roundtrip.PositionSeconds);
        Assert.Equal(snapshot.Shuffle, roundtrip.Shuffle);
        Assert.Equal(snapshot.Repeat, roundtrip.Repeat);
    }

    [Fact]
    public void Source_generated_snapshot_accepts_legacy_songs_without_optional_rating()
    {
        const string json = """{"ConnectionId":"home","Queue":[{"Id":"song-1","Title":"雪","Artist":"Artist","Album":"Album","CoverArtId":"cover-1","DurationSeconds":123,"UnknownProperty":"ignored"}],"Index":0,"PositionSeconds":0,"Shuffle":false,"Repeat":true}""";
        var snapshot = Assert.IsType<PlaybackSnapshot>(JsonSerializer.Deserialize(json, PersistenceJsonContext.Default.PlaybackSnapshot));
        Assert.Equal(new Song("song-1", "雪", "Artist", "Album", "cover-1", 123), Assert.Single(snapshot.Queue));

        var roundtrip = Assert.IsType<PlaybackSnapshot>(JsonSerializer.Deserialize(
            JsonSerializer.Serialize(snapshot, PersistenceJsonContext.Default.PlaybackSnapshot), PersistenceJsonContext.Default.PlaybackSnapshot));
        Assert.Equal(snapshot.Queue, roundtrip.Queue);
    }

    [Fact]
    public void Source_generated_snapshot_roundtrips_an_empty_queue()
    {
        const string json = """{"ConnectionId":"home","Queue":[],"Index":-1,"PositionSeconds":0,"Shuffle":false,"Repeat":false}""";
        var snapshot = Assert.IsType<PlaybackSnapshot>(JsonSerializer.Deserialize(json, PersistenceJsonContext.Default.PlaybackSnapshot));
        Assert.Empty(snapshot.Queue);
        Assert.Equal(-1, snapshot.Index);
        Assert.Equal(json, JsonSerializer.Serialize(snapshot, PersistenceJsonContext.Default.PlaybackSnapshot));
    }

    [Fact]
    public void Session_store_reads_legacy_json_and_preserves_default_json_shape()
    {
        var path = Path.Combine(AppContext.BaseDirectory, "test-data", Guid.NewGuid().ToString("N") + ".db");
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        try
        {
            using (var store = new SessionStore(path))
            using (var database = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = path, Pooling = false }.ToString()))
            {
                database.Open();
                using var command = database.CreateCommand();
                command.CommandText = "INSERT INTO playback (connection_id, snapshot) VALUES ('home', $snapshot)";
                command.Parameters.AddWithValue("$snapshot", LegacySnapshotJson);
                command.ExecuteNonQuery();

                var loaded = Assert.IsType<PlaybackSnapshot>(store.LoadPlayback("home"));
                Assert.Equal("home", loaded.ConnectionId);
                Assert.Equal(new Song("song-1", "Track", "Artist", "Album", null, 123, 4), Assert.Single(loaded.Queue));
                Assert.Equal(0, loaded.Index);
                Assert.Equal(12.25, loaded.PositionSeconds);
                Assert.True(loaded.Shuffle);
                Assert.False(loaded.Repeat);

                store.SavePlayback(loaded);
                command.CommandText = "SELECT snapshot FROM playback WHERE connection_id = 'home'";
                Assert.Equal(LegacySnapshotJson, command.ExecuteScalar());
            }
        }
        finally
        {
            File.Delete(path);
        }
    }
}
