using Music.Core;
using Xunit;
using static Music.Core.Tests.ClientTests;

namespace Music.Core.Tests;

public sealed class AlbumTests
{
    [Fact]
    public async Task Album_page_maps_metadata_and_requests_stable_order()
    {
        using var handler = new Handler(_ => Json(",\"albumList2\":{\"album\":[{\"id\":\"a\",\"name\":\"Album\",\"artist\":\"Artist\",\"coverArt\":\"cover\",\"songCount\":4,\"year\":2024}]}"));
        using var http = new HttpClient(handler);
        using var client = new NavidromeClient(Profile(), "password", http);
        Assert.Equal(new Album("a", "Album", "Artist", "cover", 4, 2024), Assert.Single(await client.GetAlbumsAsync(200, 50)));
        var uri = handler.Requests.Single();
        Assert.Equal("/sub/rest/getAlbumList2.view", uri.AbsolutePath);
        var q = Query(uri);
        Assert.Equal("200", q["offset"]);
        Assert.Equal("50", q["size"]);
        Assert.Equal("alphabeticalByName", q["type"]);
    }

    [Theory]
    [InlineData(-1, 100)] [InlineData(0, 0)] [InlineData(0, 501)]
    public async Task Invalid_pages_are_rejected_before_network(int offset, int size)
    {
        using var handler = new Handler(_ => Json());
        using var http = new HttpClient(handler);
        using var client = new NavidromeClient(Profile(), "password", http);
        await Assert.ThrowsAsync<ArgumentOutOfRangeException>(() => client.GetAlbumsAsync(offset, size));
        Assert.Empty(handler.Requests);
    }
}
