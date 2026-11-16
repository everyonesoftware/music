using System.Net;
using Music.Core;
using Xunit;
using static Music.Core.Tests.ClientTests;

namespace Music.Core.Tests;

public sealed class MediaTests
{
    [Theory]
    [InlineData(4, false)] [InlineData(2097153, true)]
    public async Task Cover_art_returns_bounded_bytes_without_exposing_signed_uri(int size, bool reject)
    {
        using var handler = new Handler(request =>
        {
            Assert.True(request.Headers.CacheControl!.NoStore);
            return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StreamContent(new NonSeekableStream(new byte[size])) };
        });
        using var http = new HttpClient(handler);
        using var client = new NavidromeClient(Profile(), "password", http);
        if (reject) await Assert.ThrowsAsync<NavidromeException>(() => client.GetCoverArtAsync("a&=雪"));
        else Assert.Equal(new byte[size], await client.GetCoverArtAsync("a&=雪"));
        var uri = handler.Requests.Single();
        Assert.Equal("/sub/rest/getCoverArt.view", uri.AbsolutePath);
        Assert.Equal("a&=雪", Query(uri)["id"]);
        Assert.Equal("1024", Query(uri)["size"]);
    }

    [Fact]
    public async Task Offline_mode_blocks_all_server_requests_and_media_transfer()
    {
        using var handler = new Handler(_ => throw new InvalidOperationException("Network must not be reached"));
        using var http = new HttpClient(handler);
        using var client = new NavidromeClient(Profile(), "password", http) { Offline = true };
        await Assert.ThrowsAsync<NavidromeException>(() => client.PingAsync());
        await Assert.ThrowsAsync<NavidromeException>(() => client.SearchSongsAsync(""));
        await Assert.ThrowsAsync<NavidromeException>(() => client.GetCoverArtAsync("art"));
        await Assert.ThrowsAsync<NavidromeException>(() => client.DownloadSongAsync("song", new MemoryStream()));
        Assert.Empty(handler.Requests);
    }
    [Theory]
    [InlineData("audio/mpeg", 3, true)]
    [InlineData("audio/mpeg", 4, false)]
    [InlineData("application/json", 3, false)]
    public async Task Song_transfer_checks_type_and_length_before_committing(string type, int length, bool succeeds)
    {
        using var handler = new Handler(_ =>
        {
            var content = new ByteArrayContent(new byte[] { 1, 2, 3 });
            content.Headers.ContentType = new System.Net.Http.Headers.MediaTypeHeaderValue(type);
            content.Headers.ContentLength = length;
            return new HttpResponseMessage(HttpStatusCode.OK) { Content = content };
        });
        using var http = new HttpClient(handler);
        using var client = new NavidromeClient(Profile(), "password", http);
        using var output = new MemoryStream();
        if (succeeds) { Assert.Equal(type, await client.DownloadSongAsync("song", output)); Assert.Equal(3, output.Length); }
        else await Assert.ThrowsAsync<NavidromeException>(() => client.DownloadSongAsync("song", output));
    }
    [Fact]
    public void Stream_uri_is_ephemeral_authenticated_and_id_is_not_query_injection()
    {
        using var client = new NavidromeClient(Profile(), "password");
        var uri = client.GetStreamUri("song & t=evil/雪");
        Assert.Equal("/sub/rest/stream.view", uri.AbsolutePath);
        Assert.Equal("song & t=evil/雪", Query(uri)["id"]);
        Assert.Matches("^[0-9a-f]{32}$", Query(uri)["t"]);
        Assert.NotEqual(Query(uri)["s"], Query(client.GetStreamUri("song & t=evil/雪"))["s"]);
    }
}
