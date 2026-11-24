using System.Net;
using System.Text.Json;
using Music.Core;
using Xunit;
using static Music.Core.Tests.ClientTests;

namespace Music.Core.Tests;

public sealed class PlaylistDeletionTests
{
    private const string Id = "p & 雪";
    private const string Owner = "\"owner\":\"ALICE & 雪\"";

    [Fact]
    public async Task Only_explicit_writable_ownership_enables_deletion_in_catalog()
    {
        using var handler = new Handler(_ => Json(",\"playlists\":{\"playlist\":[" + string.Join(',', new[]
        {
            Record(Owner), Record(Owner + ",\"readOnly\":true"), Record("\"owner\":\"another\""),
            Record("\"owner\":null"), Record("\"owner\":\"\""), Record("\"readonly\":false"),
            Record(Owner + ",\"readonly\":null"), Record("\"owner\":\"other\"," + Owner)
        }) + "]}"));
        using var http = new HttpClient(handler);
        using var client = new NavidromeClient(Profile(), "fixture", http);
        Assert.Equal([true, false, false, false, false, false, false, false],
            (await client.GetPlaylistsAsync()).Select(p => p.CanDelete));
    }

    [Fact]
    public async Task Deletes_exact_owned_empty_playlist_with_one_authenticated_post()
    {
        int reads = 0, writes = 0;
        using var handler = new Handler(request =>
        {
            if (request.Method == HttpMethod.Get)
            {
                reads++;
                Assert.Equal("/sub/rest/getPlaylist.view", request.RequestUri!.AbsolutePath);
                Assert.Equal(Id, Query(request.RequestUri)["id"]);
                return Target();
            }
            writes++;
            Assert.Equal(HttpMethod.Post, request.Method);
            Assert.Equal("/sub/rest/deletePlaylist.view", request.RequestUri!.AbsolutePath);
            Assert.Empty(request.RequestUri.Query);
            Assert.Equal("application/x-www-form-urlencoded", request.Content!.Headers.ContentType!.MediaType);
            var form = request.Content.ReadAsStringAsync().GetAwaiter().GetResult().Split('&').Select(p => p.Split('=', 2))
                .ToDictionary(p => WebUtility.UrlDecode(p[0]), p => WebUtility.UrlDecode(p[1]));
            Assert.Equal(new[] { "c", "f", "id", "s", "t", "u", "v" }, form.Keys.Order(StringComparer.Ordinal));
            Assert.Equal(Id, form["id"]);
            Assert.Equal(Profile().Username, form["u"]);
            return Json();
        });
        using var http = new HttpClient(handler);
        using var client = new NavidromeClient(Profile(), "fixture", http);
        Assert.Equal((PlaylistDeletionResult.Acknowledged, null), await client.DeletePlaylistAsync(Selected(), () => true));
        Assert.Equal(1, reads);
        Assert.Equal(1, writes);
    }

    [Theory]
    [InlineData("owner")]
    [InlineData("readonly")]
    [InlineData("rename")]
    [InlineData("identity")]
    [InlineData("duplicate")]
    [InlineData("nested-duplicate")]
    [InlineData("envelope")]
    [InlineData("session")]
    [InlineData("offline")]
    [InlineData("ineligible")]
    public async Task Invalid_or_stale_preflight_never_posts(string fault)
    {
        bool current = true;
        using var handler = new Handler(request =>
        {
            Assert.Equal(HttpMethod.Get, request.Method);
            if (fault == "session") current = false;
            return fault switch
            {
                "owner" => Json(",\"playlist\":" + Record("\"owner\":\"another\"")),
                "readonly" => Json(",\"playlist\":" + Record(Owner + ",\"readonly\":true")),
                "rename" => Json(",\"playlist\":" + Record(Owner, name: "Renamed")),
                "identity" => Json(",\"playlist\":" + Record(Owner, id: "other")),
                "duplicate" => Json(",\"playlist\":" + Record("\"owner\":\"other\"," + Owner)),
                "nested-duplicate" => Json(",\"playlist\":" + Record(Owner + ",\"entry\":[{\"id\":\"other\",\"id\":\"track\"}]")),
                "envelope" => Json(",\"status\":\"ok\",\"playlist\":" + Record(Owner)),
                _ => Target()
            };
        });
        using var http = new HttpClient(handler);
        using var client = new NavidromeClient(Profile(), "fixture", http) { Offline = fault == "offline" };
        var selected = Selected() with { CanDelete = fault != "ineligible" };
        if (fault == "session") await Assert.ThrowsAnyAsync<OperationCanceledException>(() => client.DeletePlaylistAsync(selected, () => current));
        else await Assert.ThrowsAsync<NavidromeException>(() => client.DeletePlaylistAsync(selected, () => current));
        Assert.Equal(fault is "offline" or "ineligible" ? 0 : 1, handler.Requests.Count);
    }

    [Theory]
    [InlineData("missing", PlaylistDeletionResult.AbsentAfterUncertain)]
    [InlineData("present", PlaylistDeletionResult.StillPresent)]
    [InlineData("unavailable", PlaylistDeletionResult.Unverified)]
    [InlineData("wrong-id", PlaylistDeletionResult.Unverified)]
    [InlineData("duplicate-error", PlaylistDeletionResult.Unverified)]
    [InlineData("transport", PlaylistDeletionResult.AbsentAfterUncertain)]
    [InlineData("redirect", PlaylistDeletionResult.StillPresent)]
    public async Task Uncertain_post_checks_only_exact_id_without_replaying(string readback, PlaylistDeletionResult expected)
    {
        int reads = 0, writes = 0;
        using var handler = new Handler(request =>
        {
            if (request.Method == HttpMethod.Post)
            {
                writes++;
                if (readback == "transport") throw new HttpRequestException("lost acknowledgement");
                if (readback == "redirect") return new(HttpStatusCode.TemporaryRedirect) { Headers = { Location = new Uri("https://other.example/") } };
                return Json(",\"status\":\"ok\""); // Ambiguous duplicate acknowledgement.
            }
            Assert.Equal(Id, Query(request.RequestUri!)["id"]);
            if (++reads == 1) return Target();
            return readback switch
            {
                "missing" or "transport" => Failed("\"code\":70"),
                "present" or "redirect" => Json(",\"playlist\":" + Record(Owner, name: "Renamed on server")),
                "duplicate-error" => Failed("\"code\":50,\"code\":70"),
                "wrong-id" => Json(",\"playlist\":" + Record(Owner, id: "other")),
                _ => new(HttpStatusCode.ServiceUnavailable)
            };
        });
        using var http = new HttpClient(handler);
        using var client = new NavidromeClient(Profile(), "fixture", http);
        var (outcome, present) = await client.DeletePlaylistAsync(Selected(), () => true);
        Assert.Equal(expected, outcome);
        if (expected == PlaylistDeletionResult.StillPresent)
        {
            Assert.NotNull(present);
            Assert.Equal(Id, present.Id);
            Assert.Equal("Renamed on server", present.Name);
        }
        else Assert.Null(present);
        Assert.Equal(1, writes);
        Assert.Equal(2, reads);
    }

    [Theory]
    [InlineData("absent", PlaylistDeletionResult.AbsentAfterUncertain)]
    [InlineData("present", PlaylistDeletionResult.StillPresent)]
    [InlineData("http-404", PlaylistDeletionResult.Unverified)]
    [InlineData("wrong-id", PlaylistDeletionResult.Unverified)]
    [InlineData("duplicate-code", PlaylistDeletionResult.Unverified)]
    public async Task Explicit_check_after_lost_acknowledgement_and_failed_readback_only_reads_same_id(
        string retryReply, PlaylistDeletionResult expected)
    {
        var requests = new List<HttpMethod>();
        using var handler = new Handler(request =>
        {
            requests.Add(request.Method);
            if (request.Method == HttpMethod.Post)
            {
                Assert.Equal("/sub/rest/deletePlaylist.view", request.RequestUri!.AbsolutePath);
                throw new HttpRequestException("lost acknowledgement");
            }
            Assert.Equal("/sub/rest/getPlaylist.view", request.RequestUri!.AbsolutePath);
            Assert.Equal(Id, Query(request.RequestUri)["id"]);
            return requests.Count switch
            {
                1 => Target(),
                3 => new(HttpStatusCode.ServiceUnavailable),
                4 => retryReply switch
                {
                    "absent" => Failed("\"code\":70"),
                    "present" => Json(",\"playlist\":" + Record(Owner, name: "Renamed on server")),
                    "http-404" => new(HttpStatusCode.NotFound),
                    "wrong-id" => Json(",\"playlist\":" + Record(Owner, id: "other")),
                    _ => Failed("\"code\":50,\"code\":70")
                },
                _ => throw new InvalidOperationException("Unexpected request")
            };
        });
        using var http = new HttpClient(handler);
        using var client = new NavidromeClient(Profile(), "fixture", http);

        Assert.Equal((PlaylistDeletionResult.Unverified, null), await client.DeletePlaylistAsync(Selected(), () => true));
        Assert.Equal([HttpMethod.Get, HttpMethod.Post, HttpMethod.Get], requests);

        var (outcome, present) = await client.CheckPlaylistDeletionAsync(Id);
        Assert.Equal(expected, outcome);
        Assert.Equal([HttpMethod.Get, HttpMethod.Post, HttpMethod.Get, HttpMethod.Get], requests);
        if (expected == PlaylistDeletionResult.StillPresent)
        {
            Assert.NotNull(present);
            Assert.Equal(Id, present.Id);
            Assert.Equal("Renamed on server", present.Name);
        }
        else Assert.Null(present);
    }

    [Fact]
    public async Task Explicit_rejection_is_not_success_or_ambiguous()
    {
        using var handler = new Handler(request => request.Method == HttpMethod.Post ? Failed("\"code\":50") : Target());
        using var http = new HttpClient(handler);
        using var client = new NavidromeClient(Profile(), "fixture", http);
        var error = await Assert.ThrowsAsync<NavidromeException>(() => client.DeletePlaylistAsync(Selected(), () => true));
        Assert.Contains("permissions", error.Message);
        Assert.Equal(2, handler.Requests.Count);
    }

    private static Playlist Selected() => new(Id, "Same", null, 0) { CanDelete = true };
    private static string Record(string metadata, string? id = null, string name = "Same") =>
        $"{{\"id\":{JsonSerializer.Serialize(id ?? Id)},\"name\":\"{name}\",\"songCount\":0,{metadata}}}";
    private static HttpResponseMessage Target() => Json(",\"playlist\":" + Record(Owner));
    private static HttpResponseMessage Failed(string metadata) => new(HttpStatusCode.OK)
    { Content = new StringContent("{\"subsonic-response\":{\"status\":\"failed\",\"error\":{" + metadata + "}}}") };
}
