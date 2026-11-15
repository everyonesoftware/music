using Music.Core;
using Xunit;
using static Music.Core.Tests.SongTests;

namespace Music.Core.Tests;

public sealed class SessionTests : IDisposable
{
    private readonly string directory = Path.Combine(AppContext.BaseDirectory, "test-data", Guid.NewGuid().ToString("N"));
    private string DatabasePath => Path.Combine(directory, "session.db");

    public SessionTests() => Directory.CreateDirectory(directory);
    public void Dispose() => Directory.Delete(directory, true);

    [Theory]
    [InlineData(-1, 0)] [InlineData(1, 0)] [InlineData(0, -1)] [InlineData(0, double.NaN)] [InlineData(0, double.PositiveInfinity)]
    public void Invalid_snapshot_is_rejected_without_replacing_previous_session(int index, double position)
    {
        using var store = new SessionStore(DatabasePath);
        store.SavePlayback(new PlaybackSnapshot("home", new[] { Expected }, 0, 1, false, false));
        Assert.Throws<ArgumentException>(() => store.SavePlayback(new PlaybackSnapshot("home", new[] { Expected }, index, position, false, false)));
        Assert.Equal(1, store.LoadPlayback("home")!.PositionSeconds);
    }

    [Theory]
    [InlineData(true)] [InlineData(false)]
    public void Signed_media_urls_are_rejected_before_disk_write(bool songId)
    {
        const string signed = "https://server/rest/stream.view?id=s&t=private-token&s=private-salt";
        var song = songId ? Expected with { Id = signed } : Expected with { CoverArtId = signed };
        using var store = new SessionStore(DatabasePath);
        Assert.Throws<ArgumentException>(() => store.SavePlayback(new PlaybackSnapshot("home", new[] { song }, 0, 0, false, false)));
        Assert.Null(store.LoadPlayback("home"));
        Assert.DoesNotContain("private-token", System.Text.Encoding.UTF8.GetString(File.ReadAllBytes(DatabasePath)));
    }

    [Fact]
    public void Queue_replacement_is_atomic_scoped_and_allows_explicit_empty_queue()
    {
        using var store = new SessionStore(DatabasePath);
        store.SavePlayback(new PlaybackSnapshot("home'; DROP TABLE playback;--", new[] { Expected, Expected }, 1, 8, false, true));
        store.SavePlayback(new PlaybackSnapshot("work", new[] { Expected }, 0, 9, true, false));
        store.SavePlayback(new PlaybackSnapshot("home'; DROP TABLE playback;--", Array.Empty<Song>(), -1, 0, false, false));
        Assert.Empty(store.LoadPlayback("home'; DROP TABLE playback;--")!.Queue);
        Assert.Equal(-1, store.LoadPlayback("home'; DROP TABLE playback;--")!.Index);
        Assert.Equal(9, store.LoadPlayback("work")!.PositionSeconds);
    }

    [Fact]
    public void View_modes_survive_reopen_are_collection_scoped_and_use_fallback()
    {
        using (var store = new SessionStore(DatabasePath))
        {
            Assert.Equal("grid", store.GetView("albums", "grid"));
            store.SaveView("albums", "list");
            store.SaveView("playlists", "grid");
            store.SaveView("albums", "grid");
            store.SaveView("albums", "list");
        }
        using var reopened = new SessionStore(DatabasePath);
        Assert.Equal("list", reopened.GetView("albums", "grid"));
        Assert.Equal("grid", reopened.GetView("playlists", "list"));
        Assert.Equal("fallback", reopened.GetView("unseen'; DROP TABLE playback;--", "fallback"));
    }

    [Theory]
    [InlineData("not-json")]
    [InlineData("null")]
    [InlineData("{\"ConnectionId\":\"other-server\",\"Queue\":[],\"Index\":-1,\"PositionSeconds\":0,\"Shuffle\":false,\"Repeat\":false}")]
    [InlineData("{\"ConnectionId\":\"home\",\"Queue\":null,\"Index\":99,\"PositionSeconds\":0,\"Shuffle\":false,\"Repeat\":false}")]
    public void Corrupt_or_cross_connection_snapshot_is_not_restored(string payload)
    {
        using var store = new SessionStore(DatabasePath);
        using (var database = new Microsoft.Data.Sqlite.SqliteConnection(new Microsoft.Data.Sqlite.SqliteConnectionStringBuilder { DataSource = DatabasePath, Pooling = false }.ToString()))
        {
            database.Open();
            using var command = database.CreateCommand();
            command.CommandText = "INSERT INTO playback (connection_id, snapshot) VALUES ('home', $payload)";
            command.Parameters.AddWithValue("$payload", payload);
            command.ExecuteNonQuery();
        }
        Assert.Null(store.LoadPlayback("home"));
    }

    [Fact]
    public void Queue_survives_reopen_with_duplicate_occurrences_exact_index_and_connection_scope()
    {
        var queue = new[] { Expected, Expected with { Id = "second" }, Expected };
        var snapshot = new PlaybackSnapshot("home", queue, 2, 12.25, true, false);
        using (var store = new SessionStore(DatabasePath)) store.SavePlayback(snapshot);
        using var reopened = new SessionStore(DatabasePath);
        var loaded = Assert.IsType<PlaybackSnapshot>(reopened.LoadPlayback("home"));
        Assert.Equal("home", loaded.ConnectionId);
        Assert.Equal(queue, loaded.Queue);
        Assert.Equal(2, loaded.Index);
        Assert.Equal(12.25, loaded.PositionSeconds);
        Assert.True(loaded.Shuffle);
        Assert.False(loaded.Repeat);
        Assert.Null(reopened.LoadPlayback("different-server"));
    }
}
