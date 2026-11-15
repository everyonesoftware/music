using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media.Imaging;
using Music.Core;
using Windows.Storage.Streams;

namespace Music.App;

internal sealed class ArtworkLoader
{
    private readonly SemaphoreSlim slots = new(4, 4);
    private readonly Dictionary<string, byte[]> cache = new();
    private readonly Queue<string> order = new();
    private long cachedBytes;
    private const long MaximumCacheBytes = 24 * 1024 * 1024;
    private const int MaximumImageBytes = 8 * 1024 * 1024;
    public void Clear() { cache.Clear(); order.Clear(); cachedBytes = 0; }

    // Called on the UI context; cache entries and image assignments stay there.
    // Cache contains only bytes; no authenticated URL can enter a XAML property.
    public async Task SetAsync(Image image, string? id, NavidromeClient client, CancellationToken token)
    {
        if (string.IsNullOrWhiteSpace(id)) return;
        try
        {
            await slots.WaitAsync(token);
            try
            {
                token.ThrowIfCancellationRequested();
                if (!cache.TryGetValue(id, out var bytes))
                {
                    bytes = await client.GetCoverArtAsync(id, token);
                    token.ThrowIfCancellationRequested();
                    if (bytes.Length == 0 || bytes.Length > MaximumImageBytes) return;
                    while (order.Count > 0 && (cache.Count >= 96 || cachedBytes + bytes.Length > MaximumCacheBytes))
                    {
                        var oldest = order.Dequeue();
                        if (cache.Remove(oldest, out var removed)) cachedBytes -= removed.Length;
                    }
                    // Two concurrent requests may have completed for the same id.
                    if (!cache.ContainsKey(id)) { cache[id] = bytes; order.Enqueue(id); cachedBytes += bytes.Length; }
                }
                token.ThrowIfCancellationRequested();
                using var stream = new InMemoryRandomAccessStream();
                using (var writer = new DataWriter(stream.GetOutputStreamAt(0)))
                { writer.WriteBytes(bytes); await writer.StoreAsync(); writer.DetachStream(); }
                stream.Seek(0);
                var bitmap = new BitmapImage { DecodePixelWidth = 320 };
                await bitmap.SetSourceAsync(stream);
                token.ThrowIfCancellationRequested();
                image.Source = bitmap;
            }
            finally { slots.Release(); }
        }
        catch (OperationCanceledException) { }
        catch { /* A cover is optional. Never display or log transport exceptions. */ }
    }
}
