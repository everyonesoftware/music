using System.Text.Json.Serialization;

namespace Music.Core;

[JsonSerializable(typeof(ConnectionProfile))]
[JsonSerializable(typeof(PlaybackSnapshot))]
public partial class PersistenceJsonContext : JsonSerializerContext
{
}
