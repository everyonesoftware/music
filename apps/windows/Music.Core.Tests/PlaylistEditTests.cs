using System.Net;
using Music.Core;
using Xunit;
using static Music.Core.Tests.ClientTests;

namespace Music.Core.Tests;

public sealed class PlaylistEditTests
{
    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task Create_uses_only_name_and_ordered_occurrences_then_reads_the_returned_identity(bool seeded)
    {
        string[] ids = seeded ? ["s & 雪", "t", "s & 雪"] : [];
        var received = new List<string>();
        using var handler = new Handler(request =>
        {
            if (request.Method == HttpMethod.Post)
            {
                Assert.Equal("/sub/rest/createPlaylist.view", request.RequestUri!.AbsolutePath);
                var form = ReadForm(request);
                Assert.Equal("Snow & 夜", form["name"].Single());
                Assert.False(form.Contains("playlistId"));
                Assert.Equal(seeded, form.Contains("songId"));
                Assert.Equal(ids, form["songId"]);
                received.Add("create");
                return Json(",\"playlist\":{\"id\":\"created\"}");
            }
            Assert.Equal("created", Query(request.RequestUri!)["id"]);
            received.Add("read");
            return Contents("created", ids);
        });
        using var http = new HttpClient(handler);
        using var client = new NavidromeClient(Profile(), "fixture", http);
        var edit = new PlaylistEdit(client, ids, name: " Snow & 夜 ");
        var confirmed = await edit.ConfirmAsync(() => true, default);
        Assert.Equal("created", confirmed.Playlist.Id);
        Assert.Equal(ids, confirmed.Songs.Select(song => song.Id));
        Assert.Equal(["create", "read"], received);
    }

    [Fact]
    public async Task Append_rechecks_permissions_preserves_duplicates_and_rechecks_without_reposting_after_failed_readback()
    {
        int writes = 0, reads = 0;
        using var handler = new Handler(request =>
        {
            if (request.Method != HttpMethod.Post)
            {
                Assert.Equal("chosen", Query(request.RequestUri!)["id"]);
                return ++reads switch
                {
                    1 => Contents("chosen", ["s", "old"]),
                    2 => Contents("other", ["s", "old", "s", "t", "s"]),
                    3 => Contents("chosen", ["s", "old", "s", "s", "t"]),
                    _ => Contents("chosen", ["s", "old", "s", "t", "s", "concurrent-append"])
                };
            }
            writes++;
            Assert.Equal("/sub/rest/updatePlaylist.view", request.RequestUri!.AbsolutePath);
            var form = ReadForm(request);
            Assert.Equal("chosen", form["playlistId"].Single());
            Assert.Equal(["s", "t", "s"], form["songIdToAdd"]);
            Assert.False(form.Contains("name") || form.Contains("songId") || form.Contains("songIndexToRemove"));
            return Json();
        });
        using var http = new HttpClient(handler);
        using var client = new NavidromeClient(Profile(), "fixture", http);
        var edit = new PlaylistEdit(client, ["s", "t", "s"], playlistId: "chosen");
        await Assert.ThrowsAsync<NavidromeException>(() => edit.ConfirmAsync(() => true, default));
        Assert.True(edit.Acknowledged);
        await Assert.ThrowsAsync<NavidromeException>(() => edit.ConfirmAsync(() => true, default));
        var confirmed = await edit.ConfirmAsync(() => true, default);
        Assert.Equal(["s", "old", "s", "t", "s", "concurrent-append"], confirmed.Songs.Select(s => s.Id));
        Assert.Equal(1, writes);
        Assert.Equal(4, reads);
    }

    [Theory]
    [InlineData("\"readonly\":true", true)]
    [InlineData("\"readOnly\":true", true)]
    [InlineData("\"readonly\":false,\"readOnly\":true", true)]
    [InlineData("\"owner\":\"another user\"", true)]
    [InlineData("\"owner\":\"ALICE & 雪\",\"readonly\":false", false)]
    [InlineData("\"readonly\":\"false\"", true)]
    [InlineData("\"readonly\":false", false)]
    public async Task Picker_excludes_known_readonly_or_foreign_destinations(string metadata, bool excluded)
    {
        using var handler = new Handler(_ => Json($",\"playlists\":{{\"playlist\":[{{\"id\":\"p\",\"name\":\"Same\",{metadata}}}]}}"));
        using var http = new HttpClient(handler);
        using var client = new NavidromeClient(Profile(), "fixture", http);
        var playlist = Assert.Single(await client.GetPlaylistsAsync());
        Assert.Equal(!excluded, playlist.CanAppend);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task Append_confirms_after_concurrent_additions_but_does_not_count_old_or_missing_occurrences(bool missing)
    {
        int reads = 0;
        using var handler = new Handler(request => request.Method == HttpMethod.Post ? Json() : ++reads switch
        {
            1 => Contents("chosen", ["s", "old"]),
            2 when missing => Contents("chosen", ["s", "old", "theirs", "s", "t"]),
            _ => Contents("chosen", ["s", "old", "theirs", "s", "t", "s", "more-theirs"])
        });
        using var http = new HttpClient(handler);
        using var client = new NavidromeClient(Profile(), "fixture", http);
        var edit = new PlaylistEdit(client, ["s", "t", "s"], playlistId: "chosen");
        if (missing)
        {
            await Assert.ThrowsAsync<NavidromeException>(() => edit.ConfirmAsync(() => true, default));
            Assert.True(edit.Acknowledged);
        }
        var confirmed = await edit.ConfirmAsync(() => true, default);
        Assert.Equal(["s", "old", "theirs", "s", "t", "s", "more-theirs"], confirmed.Songs.Select(song => song.Id));
        Assert.Equal(missing ? 4 : 3, handler.Requests.Count);
    }

    [Theory]
    [InlineData("stale")]
    [InlineData("readonly")]
    [InlineData("incomplete")]
    [InlineData("ambiguous")]
    public async Task Append_refuses_stale_or_unverified_destinations_without_writing(string fault)
    {
        bool current = true;
        using var handler = new Handler(request =>
        {
            Assert.Equal(HttpMethod.Get, request.Method);
            if (fault == "stale") current = false;
            if (fault == "ambiguous")
                return Json(",\"playlist\":{\"id\":\"p\",\"name\":\"Same\",\"songCount\":1,\"entry\":[{\"id\":\"changed\",\"id\":\"old\",\"title\":\"Track\"}]}");
            return Contents("p", [], fault == "incomplete" ? 1 : null, fault == "readonly" ? ",\"readonly\":true" : "");
        });
        using var http = new HttpClient(handler);
        using var client = new NavidromeClient(Profile(), "fixture", http);
        var edit = new PlaylistEdit(client, ["song"], playlistId: "p");
        if (fault == "stale") await Assert.ThrowsAsync<OperationCanceledException>(() => edit.ConfirmAsync(() => current, default));
        else await Assert.ThrowsAsync<NavidromeException>(() => edit.ConfirmAsync(() => current, default));
        Assert.False(edit.Acknowledged);
        Assert.Single(handler.Requests);
    }

    [Theory]
    [InlineData("http")]
    [InlineData("malformed")]
    [InlineData("missing-id")]
    [InlineData("invalid-status")]
    public async Task Uncertain_create_is_never_replayed_or_inferred_from_the_catalog(string fault)
    {
        using var handler = new Handler(request =>
        {
            Assert.Equal(HttpMethod.Post, request.Method);
            return fault switch
            {
                "http" => new HttpResponseMessage(HttpStatusCode.ServiceUnavailable),
                "malformed" => new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent("not JSON") },
                "invalid-status" => new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent("{\"subsonic-response\":{\"status\":\"unexpected\",\"error\":{\"code\":50}}}") },
                _ => Json(",\"playlist\":{\"name\":\"Same\"}")
            };
        });
        using var http = new HttpClient(handler);
        using var client = new NavidromeClient(Profile(), "fixture", http);
        var edit = new PlaylistEdit(client, [], name: "Same");
        await Assert.ThrowsAsync<PlaylistWriteUncertainException>(() => edit.ConfirmAsync(() => true, default));
        await Assert.ThrowsAsync<PlaylistWriteUncertainException>(() => edit.ConfirmAsync(() => true, default));
        Assert.Single(handler.Requests);
    }

    [Fact]
    public async Task Acknowledged_create_recovers_exact_id_and_never_creates_again()
    {
        int reads = 0;
        using var handler = new Handler(request =>
        {
            if (request.Method == HttpMethod.Post) return Json(",\"playlist\":{\"id\":\"confirmed-id\"}");
            Assert.Equal("confirmed-id", Query(request.RequestUri!)["id"]);
            return ++reads == 1 ? new(HttpStatusCode.ServiceUnavailable) : Contents("confirmed-id", ["seed"]);
        });
        using var http = new HttpClient(handler);
        using var client = new NavidromeClient(Profile(), "fixture", http);
        var edit = new PlaylistEdit(client, ["seed"], name: "Same");
        await Assert.ThrowsAsync<NavidromeException>(() => edit.ConfirmAsync(() => true, default));
        Assert.True(edit.Acknowledged);
        Assert.Equal("confirmed-id", (await edit.ConfirmAsync(() => true, default)).Playlist.Id);
        Assert.Equal(3, handler.Requests.Count);
    }

    [Theory]
    [InlineData("album", "changed")]
    [InlineData("album", "truncated")]
    [InlineData("artist", "changed")]
    [InlineData("artist", "truncated")]
    [InlineData("playlist", "changed")]
    [InlineData("playlist", "truncated")]
    [InlineData("song", "wrong-id")]
    [InlineData("song", "missing")]
    [InlineData("playlist", "same")]
    [InlineData("playlist", "stale-lease")]
    public async Task Rechecks_the_exact_source_before_post_and_only_reads_destination_after_ack(string kind, string outcome)
    {
        string[] ids = kind == "song" ? ["a"] : ["a", "b", "a"];
        object source = kind switch
        {
            "album" => new Album("album", "Album", "Artist", null, 3, 0),
            "artist" => new Artist("artist", "Artist", null, 1),
            "playlist" => new Playlist("playlist", "Source", null, 3),
            _ => new Song("a", "A", "Artist", "Album", null, 0)
        };
        int sourceReads = 0, writes = 0, confirmations = 0;
        bool current = true;
        using var handler = new Handler(request =>
        {
            if (request.Method == HttpMethod.Post)
            {
                writes++;
                Assert.Equal(ids, ReadForm(request)["songId"]);
                return Json(",\"playlist\":{\"id\":\"created\"}");
            }
            string id = Query(request.RequestUri!)["id"];
            if (id == "created")
                return ++confirmations == 1 ? new(HttpStatusCode.ServiceUnavailable) : Contents(id, ids);
            bool recheck = kind == "artist" ? sourceReads >= 2 : sourceReads >= 1;
            sourceReads++;
            string[] songs = recheck && outcome == "changed" ? ["a", "a", "b"] : ids;
            int count = recheck && outcome == "truncated" ? 4 : songs.Length;
            string entries = string.Join(',', songs.Select(song => $"{{\"id\":\"{song}\",\"title\":\"Track\"}}"));
            if (recheck && outcome == "stale-lease") current = false;
            return request.RequestUri!.AbsolutePath switch
            {
                "/sub/rest/getArtist.view" when id == "artist" => Json(",\"artist\":{\"id\":\"artist\",\"name\":\"Artist\",\"albumCount\":1,\"album\":[{\"id\":\"album\",\"name\":\"Album\",\"songCount\":3}]}"),
                "/sub/rest/getAlbum.view" when id == "album" => Json($",\"album\":{{\"id\":\"album\",\"name\":\"Album\",\"songCount\":{count},\"song\":[{entries}]}}"),
                "/sub/rest/getPlaylist.view" when id == "playlist" => Contents(id, songs, count),
                "/sub/rest/getSong.view" when id == "a" && (!recheck || outcome != "missing") =>
                    Json($",\"song\":{{\"id\":\"{(recheck ? "other" : id)}\",\"title\":\"Track\"}}"),
                "/sub/rest/getSong.view" when id == "a" => new(HttpStatusCode.NotFound),
                _ => throw new InvalidOperationException("Unexpected source request")
            };
        });
        using var http = new HttpClient(handler);
        using var client = new NavidromeClient(Profile(), "fixture", http);
        var preview = await PlaylistEdit.ReadSourceAsync(client, source, default);
        Assert.Equal(ids, preview.Select(song => song.Id));
        var edit = new PlaylistEdit(client, preview.Select(song => song.Id).ToArray(), name: "New", source: source);
        if (outcome == "same")
        {
            await Assert.ThrowsAsync<NavidromeException>(() => edit.ConfirmAsync(() => current, default));
            Assert.True(edit.Acknowledged);
            Assert.Equal(ids, (await edit.ConfirmAsync(() => current, default)).Songs.Select(song => song.Id));
            Assert.Equal(1, writes);
            Assert.Equal(2, confirmations);
        }
        else
        {
            if (outcome == "stale-lease") await Assert.ThrowsAsync<OperationCanceledException>(() => edit.ConfirmAsync(() => current, default));
            else await Assert.ThrowsAsync<PlaylistSourceChangedException>(() => edit.ConfirmAsync(() => current, default));
            Assert.Equal(0, writes);
            Assert.Equal(0, confirmations);
        }
        Assert.Equal(kind == "artist" ? 4 : 2, sourceReads);
    }

    [Theory]
    [InlineData("playlist", "id")]
    [InlineData("playlist", "title")]
    [InlineData("playlist", "malformed-name")]
    [InlineData("album", "id")]
    [InlineData("artist", "id")]
    [InlineData("song", "id")]
    public async Task Ambiguous_source_track_is_rejected_before_any_post(string kind, string field)
    {
        const string track = "{\"id\":\"a\",\"title\":\"Track\"}";
        var ambiguous = (field switch
        {
            "id" => "{\"id\":\"changed\",",
            "title" => "{\"title\":\"Changed\",",
            _ => "{\"\\uD800\":null,"
        }) + track[1..];
        object source = kind switch
        {
            "playlist" => new Playlist("p", "Source", null, 2),
            "album" => new Album("al", "Album", "Artist", null, 2, 0),
            "artist" => new Artist("ar", "Artist", null, 1),
            _ => new Song("a", "Track", "Artist", "Album", null, 0)
        };
        var requests = new List<(HttpMethod Method, string Id)>();
        bool recheck = false;
        using var handler = new Handler(request =>
        {
            string id = request.Method == HttpMethod.Get ? Query(request.RequestUri!)["id"] : "";
            requests.Add((request.Method, id));
            var candidate = recheck ? ambiguous : track;
            return id switch
            {
                "p" => Json($",\"playlist\":{{\"id\":\"p\",\"name\":\"Source\",\"songCount\":2,\"entry\":[{track},{candidate}]}}"),
                "al" => Json($",\"album\":{{\"id\":\"al\",\"name\":\"Album\",\"songCount\":2,\"song\":[{track},{candidate}]}}"),
                "ar" => Json(",\"artist\":{\"id\":\"ar\",\"name\":\"Artist\",\"albumCount\":1,\"album\":[{\"id\":\"al\",\"name\":\"Album\",\"songCount\":2}]}"),
                "a" => Json($",\"song\":{candidate}"),
                "created" => Contents(id, kind == "song" ? ["a"] : ["a", "a"]),
                _ => Json(",\"playlist\":{\"id\":\"created\"}")
            };
        });
        using var http = new HttpClient(handler);
        using var client = new NavidromeClient(Profile(), "fixture", http);
        var preview = await PlaylistEdit.ReadSourceAsync(client, source, default);
        Assert.Equal(kind == "song" ? ["a"] : new[] { "a", "a" }, preview.Select(song => song.Id));
        recheck = true;
        var edit = new PlaylistEdit(client, preview.Select(song => song.Id).ToArray(), name: "New", source: source);

        var failure = await Record.ExceptionAsync(() => edit.ConfirmAsync(() => true, default));

        string[] sourceReads = kind switch { "playlist" => ["p"], "album" => ["al"], "artist" => ["ar", "al"], _ => ["a"] };
        Assert.Equal(sourceReads.Concat(sourceReads).Select(id => (HttpMethod.Get, id)), requests);
        Assert.IsType<PlaylistSourceChangedException>(failure);
        Assert.False(edit.Acknowledged);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task Ambiguous_readback_does_not_confirm_and_retry_reads_the_exact_destination_without_posting(bool append)
    {
        string id = append ? "chosen" : "created";
        const string track = "{\"id\":\"a\",\"title\":\"Track\"}";
        const string ambiguous = "{\"id\":\"changed\",\"\\u0069d\":\"a\",\"title\":\"Track\"}";
        var requests = new List<(HttpMethod Method, string Target)>();
        bool posted = false;
        int readbacks = 0;
        using var handler = new Handler(request =>
        {
            if (request.Method == HttpMethod.Post)
            {
                posted = true;
                requests.Add((request.Method, request.RequestUri!.AbsolutePath));
                return append ? Json() : Json(",\"playlist\":{\"id\":\"created\"}");
            }
            var target = Query(request.RequestUri!)["id"];
            requests.Add((request.Method, target));
            if (!posted) return Contents(id, ["old"]);
            string entries = (append ? "{\"id\":\"old\",\"title\":\"Old\"}," : "") +
                track + "," + (++readbacks == 1 ? ambiguous : track);
            return Json($",\"playlist\":{{\"id\":\"{id}\",\"name\":\"Same\",\"songCount\":{(append ? 3 : 2)},\"entry\":[{entries}]}}");
        });
        using var http = new HttpClient(handler);
        using var client = new NavidromeClient(Profile(), "fixture", http);
        var edit = new PlaylistEdit(client, ["a", "a"], name: append ? null : "New", playlistId: append ? id : null);

        var failure = await Record.ExceptionAsync(() => edit.ConfirmAsync(() => true, default));
        Assert.IsType<NavidromeException>(failure);
        Assert.True(edit.Acknowledged);
        Assert.Equal(id, edit.DestinationId);
        var confirmed = await edit.ConfirmAsync(() => true, default);

        Assert.Equal(append ? ["old", "a", "a"] : new[] { "a", "a" }, confirmed.Songs.Select(song => song.Id));
        var expected = new List<(HttpMethod, string)>();
        if (append) expected.Add((HttpMethod.Get, id));
        expected.Add((HttpMethod.Post, append ? "/sub/rest/updatePlaylist.view" : "/sub/rest/createPlaylist.view"));
        expected.AddRange([(HttpMethod.Get, id), (HttpMethod.Get, id)]);
        Assert.Equal(expected, requests);
    }

    [Fact]
    public async Task Offline_and_invalid_edits_never_send_a_request()
    {
        using var handler = new Handler(_ => Json());
        using var http = new HttpClient(handler);
        using var client = new NavidromeClient(Profile(), "fixture", http) { Offline = true };
        Assert.Throws<ArgumentException>(() => new PlaylistEdit(client, [], name: "  "));
        Assert.Throws<ArgumentException>(() => new PlaylistEdit(client, [], playlistId: "p"));
        Assert.Throws<ArgumentException>(() => new PlaylistEdit(client, ["\n"], name: "A"));
        var edit = new PlaylistEdit(client, [], name: "A");
        await Assert.ThrowsAsync<NavidromeException>(() => edit.ConfirmAsync(() => true, default));
        Assert.False(edit.Acknowledged);
        Assert.Empty(handler.Requests);
    }

    private static ILookup<string, string> ReadForm(HttpRequestMessage request)
    {
        Assert.Empty(request.RequestUri!.Query);
        Assert.Equal("application/x-www-form-urlencoded", request.Content!.Headers.ContentType!.MediaType);
        var form = request.Content.ReadAsStringAsync().GetAwaiter().GetResult().Split('&').Select(pair => pair.Split('=', 2))
            .ToLookup(p => WebUtility.UrlDecode(p[0]), p => WebUtility.UrlDecode(p[1]));
        Assert.Equal(Profile().Username, form["u"].Single());
        Assert.False(form.Contains("p"));
        return form;
    }

    private static HttpResponseMessage Contents(string id, string[] songs, int? count = null, string metadata = "") =>
        Json($",\"playlist\":{{\"id\":\"{id}\",\"name\":\"Same\",\"songCount\":{count ?? songs.Length}{metadata},\"entry\":[" +
            string.Join(',', songs.Select(song => "{\"title\":\"Track\",\"id\":" + System.Text.Json.JsonSerializer.Serialize(song) + "}")) + "]}");
}
