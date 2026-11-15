using System.Net;
using System.Security.Cryptography;
using System.Text;
using Music.Core;
using Xunit;

namespace Music.Core.Tests;

public sealed class ClientTests
{
    internal static ConnectionProfile Profile(string url = "https://music.example/sub/") => new("c", "Home", url, "alice & 雪");
    internal static Dictionary<string,string> Query(Uri uri) => uri.Query.TrimStart('?').Split('&').Select(x => x.Split('=',2)).ToDictionary(x => Uri.UnescapeDataString(x[0]), x => Uri.UnescapeDataString(x[1]));
    internal sealed class Handler(Func<HttpRequestMessage, HttpResponseMessage> respond) : HttpMessageHandler
    {
        public List<Uri> Requests { get; } = [];
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Requests.Add(request.RequestUri!);
            return Task.FromResult(respond(request));
        }
    }
    internal static HttpResponseMessage Json(string members = "") => new(HttpStatusCode.OK) { Content = new StringContent("{\"subsonic-response\":{\"status\":\"ok\"" + members + "}}") };

    [Theory]
    [InlineData("")] [InlineData(" ")] [InlineData("\n")]
    public async Task Empty_identifiers_are_rejected_before_network_without_leaking_input(string id)
    {
        using var handler = new Handler(_ => Json());
        using var http = new HttpClient(handler);
        using var client = new NavidromeClient(Profile(), "secret", http);
        Assert.Throws<ArgumentException>(() => client.GetStreamUri(id));
        await Assert.ThrowsAsync<ArgumentException>(() => client.GetPlaylistSongsAsync(id));
        await Assert.ThrowsAsync<ArgumentException>(() => client.GetAlbumSongsAsync(id));
        await Assert.ThrowsAsync<ArgumentException>(() => client.GetCoverArtAsync(id));
        await Assert.ThrowsAsync<ArgumentException>(() => client.RateSongAsync(id, 3));
        Assert.Empty(handler.Requests);
    }

    [Theory]
    [InlineData("https://@music.example", false)]
    [InlineData("http://music.example", false)]
    [InlineData("http://music.example", true)]
    [InlineData("http://10.0.2.2", true)]
    [InlineData("http://127.0.0.1", false)]
    [InlineData("https://user:secret@music.example", false)]
    [InlineData("https://music.example?secret=1", false)]
    [InlineData("https://music.example#fragment", false)]
    [InlineData("https://music.example/a/../b", false)]
    [InlineData("https://music.example/a/%2E%2e/b", false)]
    [InlineData("https://music.example/a\\\\b", false)]
    [InlineData("https://music.example/\n", false)]
    [InlineData("not-a-url", false)]
    public void Unsafe_server_addresses_are_rejected_without_echoing_input(string url, bool allow)
    {
        var ex = Assert.Throws<ArgumentException>(() => new NavidromeClient(Profile(url), "password", allowLoopbackHttp: allow));
        Assert.DoesNotContain(url, ex.Message);
        Assert.Null(ex.InnerException);
    }

    [Fact]
    public async Task Playlists_map_metadata_and_empty_collection()
    {
        using var handler = new Handler(_ => Json(",\"playlists\":{\"playlist\":[{\"id\":\"p\",\"name\":\"雪 Mix\",\"coverArt\":\"art\",\"songCount\":3}]}"));
        using var http = new HttpClient(handler);
        using var client = new NavidromeClient(Profile(), "password", http);
        Assert.Equal(new Playlist("p", "雪 Mix", "art", 3), Assert.Single(await client.GetPlaylistsAsync()));
        Assert.Equal("/sub/rest/getPlaylists.view", handler.Requests.Single().AbsolutePath);
        using var emptyHandler = new Handler(_ => Json(",\"playlists\":{}"));
        using var emptyHttp = new HttpClient(emptyHandler);
        using var empty = new NavidromeClient(Profile(), "password", emptyHttp);
        Assert.Empty(await empty.GetPlaylistsAsync());
    }

    [Fact]
    public async Task Ping_uses_fresh_salted_android_compatible_authentication()
    {
        using var handler = new Handler(_ => Json());
        using var http = new HttpClient(handler);
        using var client = new NavidromeClient(Profile(), "päss&secret", http);
        await client.PingAsync();
        await client.PingAsync();
        Assert.Equal(2, handler.Requests.Count);
        Assert.NotEqual(Query(handler.Requests[0])["s"], Query(handler.Requests[1])["s"]);
        foreach (var uri in handler.Requests)
        {
            var q = Query(uri);
            Assert.Equal("/sub/rest/ping.view", uri.AbsolutePath);
            Assert.Equal("alice & 雪", q["u"]);
            Assert.Matches("^[0-9a-f]{32}$", q["s"]);
            Assert.Equal(Convert.ToHexStringLower(MD5.HashData(Encoding.UTF8.GetBytes("päss&secret" + q["s"]))), q["t"]);
            Assert.Equal("1.16.1", q["v"]);
            Assert.Equal("EveryoneMusic", q["c"]);
            Assert.Equal("json", q["f"]);
            Assert.DoesNotContain("secret", uri.AbsoluteUri);
            Assert.False(q.ContainsKey("p"));
        }
    }
}
