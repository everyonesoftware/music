using System.Text.Json;
using System.Globalization;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;

namespace Music.Core;

public sealed class NavidromeClient : IDisposable
{
    private readonly ConnectionProfile connection;
    private readonly string password;
    private readonly Uri server;
    private readonly HttpClient http;
    private readonly bool ownsHttp;

    public NavidromeClient(ConnectionProfile connection, string password, HttpClient? httpClient = null, bool allowLoopbackHttp = false)
    {
        this.connection = connection;
        this.password = password;
        server = ValidateServer(connection.ServerUrl, allowLoopbackHttp);
        ownsHttp = httpClient is null;
        http = httpClient ?? new HttpClient(new HttpClientHandler { AllowAutoRedirect = false }) { Timeout = TimeSpan.FromSeconds(45) };
    }

    private static Uri ValidateServer(string raw, bool allowLoopbackHttp)
    {
        const string message = "Use an HTTPS server address without credentials, query parameters or a fragment.";
        if (string.IsNullOrWhiteSpace(raw) || raw.Contains('\\') || raw.Any(char.IsControl) ||
            !Uri.TryCreate(raw, UriKind.Absolute, out var uri) || string.IsNullOrEmpty(uri.Host) ||
            !string.IsNullOrEmpty(uri.UserInfo) || raw.Contains('?') || raw.Contains('#') ||
            !(uri.Scheme == "https" || (allowLoopbackHttp && uri.Scheme == "http" &&
                uri.Host is "localhost" or "127.0.0.1" or "[::1]")))
            throw new ArgumentException(message);
        // Inspect original segments before System.Uri removes dot-segments.
        if (raw[(raw.IndexOf("://", StringComparison.Ordinal) + 3)..].Split('/')[0].Contains('@') ||
            raw.Split('/').Any(segment => Uri.UnescapeDataString(segment) is "." or ".."))
            throw new ArgumentException(message);
        return new Uri(raw.TrimEnd('/') + "/");
    }

    private Uri Endpoint(string name, params (string Key, string Value)[] parameters)
    {
        if (parameters.Any(parameter => parameter.Key == "id" && string.IsNullOrWhiteSpace(parameter.Value)))
            throw new ArgumentException("A music item identifier is required.");
        var salt = Convert.ToHexStringLower(RandomNumberGenerator.GetBytes(16));
        var token = Convert.ToHexStringLower(MD5.HashData(Encoding.UTF8.GetBytes(password + salt)));
        var query = parameters.Concat(new[] { ("u", connection.Username), ("t", token), ("s", salt), ("v", "1.16.1"), ("c", "EveryoneMusic"), ("f", "json") });
        return new Uri(new Uri(server, "rest/" + name + ".view"), "?" + string.Join('&', query.Select(x => Uri.EscapeDataString(x.Item1) + "=" + Uri.EscapeDataString(x.Item2))));
    }

    public async Task PingAsync(CancellationToken cancellationToken = default) =>
        _ = await Api("ping", cancellationToken).ConfigureAwait(false);

    public async Task<IReadOnlyList<Playlist>> GetPlaylistsAsync(CancellationToken cancellationToken = default)
    {
        var root = await Api("getPlaylists", cancellationToken).ConfigureAwait(false);
        return Items(Object(root, "playlists"), "playlist").Select(x => new Playlist(Required(x, "id"), Required(x, "name"), Text(x, "coverArt"), Number(x, "songCount"))).ToArray();
    }

    public async Task<IReadOnlyList<Album>> GetAlbumsAsync(int offset = 0, int size = 100, CancellationToken cancellationToken = default)
    {
        if (offset < 0) throw new ArgumentOutOfRangeException(nameof(offset));
        if (size is < 1 or > 500) throw new ArgumentOutOfRangeException(nameof(size));
        var root = await Api("getAlbumList2", cancellationToken, ("type", "alphabeticalByName"), ("offset", offset.ToString(CultureInfo.InvariantCulture)), ("size", size.ToString(CultureInfo.InvariantCulture))).ConfigureAwait(false);
        return Items(Object(root, "albumList2"), "album").Select(x => new Album(Required(x, "id"), Required(x, "name"), Text(x, "artist") ?? "Unknown artist", Text(x, "coverArt"), Number(x, "songCount"), Number(x, "year"))).ToArray();
    }

    public async Task<IReadOnlyList<Song>> GetPlaylistSongsAsync(string id, CancellationToken cancellationToken = default)
    {
        var root = await Api("getPlaylist", cancellationToken, ("id", id)).ConfigureAwait(false);
        return Items(Object(root, "playlist"), "entry").Select(x => ParseSong(x)).ToArray();
    }

    public async Task<IReadOnlyList<Song>> GetAlbumSongsAsync(string id, CancellationToken cancellationToken = default)
    {
        var root = Object(await Api("getAlbum", cancellationToken, ("id", id)).ConfigureAwait(false), "album");
        if (Required(root, "id") != id) throw InvalidResponse();
        return Items(root, "song").Select(x => ParseSong(x, Text(root, "artist"), Required(root, "name"), Text(root, "coverArt"))).ToArray();
    }

    public async Task<IReadOnlyList<Song>> SearchSongsAsync(string query, int offset = 0, int count = 100, CancellationToken cancellationToken = default)
    {
        if (offset < 0) throw new ArgumentOutOfRangeException(nameof(offset));
        if (count is < 1 or > 500) throw new ArgumentOutOfRangeException(nameof(count));
        var root = await Api("search3", cancellationToken, ("query", query), ("artistCount", "0"), ("albumCount", "0"), ("songOffset", offset.ToString(CultureInfo.InvariantCulture)), ("songCount", count.ToString(CultureInfo.InvariantCulture))).ConfigureAwait(false);
        return Items(Object(root, "searchResult3"), "song").Select(x => ParseSong(x)).ToArray();
    }

    public async Task<int> RateSongAsync(string id, int rating, CancellationToken cancellationToken = default)
    {
        if (rating is < 0 or > 5) throw new ArgumentOutOfRangeException(nameof(rating), "Rating must be between 0 and 5.");
        await Api("setRating", cancellationToken, ("id", id), ("rating", rating.ToString(CultureInfo.InvariantCulture))).ConfigureAwait(false);
        var song = Object(await Api("getSong", cancellationToken, ("id", id)).ConfigureAwait(false), "song");
        var actual = Number(song, "userRating");
        if (Required(song, "id") != id || actual is < 0 or > 5) throw InvalidResponse();
        return actual;
    }

    /// <summary>Ephemeral signed URI. Never persist, log, or cache this value.</summary>
    public Uri GetStreamUri(string songId) => Endpoint("stream", ("id", songId));

    public Task<byte[]> GetCoverArtAsync(string id, CancellationToken cancellationToken = default) =>
        Download("getCoverArt", 2 * 1024 * 1024, cancellationToken, ("id", id), ("size", "1024"));

    private static Song ParseSong(JsonElement x, string? artist = null, string? album = null, string? coverArt = null) =>
        new(Required(x, "id"), Required(x, "title"), Text(x, "artist") ?? artist ?? "Unknown artist", Text(x, "album") ?? album ?? "", Text(x, "coverArt") ?? coverArt, Number(x, "duration"), Number(x, "userRating"));

    private static JsonElement Object(JsonElement root, string key) =>
        root.ValueKind == JsonValueKind.Object && root.TryGetProperty(key, out var item) && item.ValueKind == JsonValueKind.Object ? item : throw InvalidResponse();

    private static JsonElement[] Items(JsonElement root, string key)
    {
        if (!root.TryGetProperty(key, out var value) || value.ValueKind == JsonValueKind.Null) return [];
        if (value.ValueKind != JsonValueKind.Array) throw InvalidResponse();
        var items = value.EnumerateArray().ToArray();
        if (items.Any(x => x.ValueKind != JsonValueKind.Object)) throw InvalidResponse();
        return items;
    }

    private static string? Text(JsonElement root, string key)
    {
        if (!root.TryGetProperty(key, out var value) || value.ValueKind == JsonValueKind.Null) return null;
        return value.ValueKind == JsonValueKind.String ? value.GetString() : throw InvalidResponse();
    }
    private static string Required(JsonElement root, string key) => Text(root, key) is { } text && !string.IsNullOrWhiteSpace(text) ? text : throw InvalidResponse();
    private static int Number(JsonElement root, string key)
    {
        if (!root.TryGetProperty(key, out var value) || value.ValueKind == JsonValueKind.Null) return 0;
        return value.ValueKind == JsonValueKind.Number && value.TryGetInt32(out var number) ? number : throw InvalidResponse();
    }

    private static NavidromeException InvalidResponse() => new("The music server returned an invalid response. Check compatibility and retry.");

    private async Task<byte[]> Download(string name, int limit, CancellationToken cancellationToken, params (string Key, string Value)[] parameters)
    {
        var callerCancellation = cancellationToken;
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        var maximum = TimeSpan.FromSeconds(name == "getCoverArt" ? 10 : 45);
        deadline.CancelAfter(http.Timeout != Timeout.InfiniteTimeSpan && http.Timeout < maximum ? http.Timeout : maximum);
        cancellationToken = deadline.Token;
        try
        {
            using var request = new HttpRequestMessage(HttpMethod.Get, Endpoint(name, parameters));
            request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue(name == "getCoverArt" ? "image/*" : "application/json"));
            request.Headers.CacheControl = new CacheControlHeaderValue { NoStore = true };
            using var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken).ConfigureAwait(false);
            if (!response.IsSuccessStatusCode)
                throw new NavidromeException((int)response.StatusCode switch
                {
                    401 or 403 => "Authentication failed. Check your username and password.",
                    >= 300 and < 400 => "The server redirected this request. Check its configured HTTPS address.",
                    _ => "The music server is unavailable. Retry shortly."
                });
            if (response.Content.Headers.ContentLength > limit) throw InvalidResponse();
            await using var stream = await response.Content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false);
            using var output = new MemoryStream();
            var buffer = new byte[16384];
            while (true)
            {
                var read = await stream.ReadAsync(buffer.AsMemory(0, Math.Min(buffer.Length, limit + 1 - (int)output.Length)), cancellationToken).ConfigureAwait(false);
                if (read == 0) break;
                output.Write(buffer, 0, read);
                if (output.Length > limit) throw InvalidResponse();
            }
            return output.ToArray();
        }
        catch (OperationCanceledException) when (callerCancellation.IsCancellationRequested)
        { throw new OperationCanceledException("The music request was canceled.", callerCancellation); }
        catch (NavidromeException) { throw; }
        catch (Exception ex) when (ex is HttpRequestException or IOException or OperationCanceledException or InvalidOperationException)
        { throw new NavidromeException("Cannot reach the music server. Check your connection and retry."); }
    }

    private async Task<JsonElement> Api(string name, CancellationToken cancellationToken, params (string Key, string Value)[] parameters)
    {
        var bytes = await Download(name, 8 * 1024 * 1024, cancellationToken, parameters).ConfigureAwait(false);
        try
        {
            using var document = JsonDocument.Parse(bytes);
            var root = document.RootElement.GetProperty("subsonic-response");
            if (root.GetProperty("status").GetString() != "ok" || root.TryGetProperty("error", out _))
            {
                var code = root.TryGetProperty("error", out var error) && error.TryGetProperty("code", out var value) && value.TryGetInt32(out var number) ? number : 0;
                throw new NavidromeException(code switch
                {
                    >= 40 and <= 44 => "Authentication failed. Check your username and password.",
                    50 => "Your account cannot access this music. Check server permissions.",
                    70 => "This item is no longer available. Refresh the playlist.",
                    _ => "The server rejected this request. Check server compatibility and retry."
                });
            }
            return root.Clone();
        }
        catch (NavidromeException) { throw; }
        catch (Exception ex) when (ex is JsonException or InvalidOperationException or KeyNotFoundException or FormatException)
        { throw InvalidResponse(); }
    }

    public void Dispose() { if (ownsHttp) http.Dispose(); }
}
