using System.Text.Json.Serialization;

namespace Music.Core;

[JsonSerializable(typeof(ConnectionProfile))]
[JsonSerializable(typeof(PlaybackSnapshot))]
[JsonSerializable(typeof(List<CachedSong>))]
[JsonSerializable(typeof(OfflineSettings))]
public partial class PersistenceJsonContext : JsonSerializerContext
{
}
