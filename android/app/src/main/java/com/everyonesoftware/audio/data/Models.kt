package com.everyonesoftware.audio.data

import kotlinx.serialization.Serializable

@Serializable
data class Connection(val id: String, val name: String, val baseUrl: String, val username: String)
data class Credentials(val connection: Connection, val password: String)
@Serializable
data class MusicLibrary(val id: String, val name: String)
@Serializable
data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val coverArtId: String? = null,
    val durationSeconds: Int = 0,
    val userRating: Int = 0,
)
@Serializable
data class Playlist(val id: String, val name: String, val songCount: Int = 0, val coverArtId: String? = null,
    val validUntilMs: Long? = null, @kotlinx.serialization.Transient val canDelete: Boolean = false)
data class PlaylistContents(val songs: List<Song>, val validUntilMs: Long? = null)
@Serializable
data class Album(val id: String, val name: String, val artist: String = "Unknown artist", val coverArtId: String? = null, val songCount: Int = 0, val year: Int = 0)
data class AlbumContents(val album: Album, val songs: List<Song>)
data class Artist(val id: String, val name: String, val coverArtId: String? = null, val albumCount: Int = 0)
/** A listed album can be browsed only after its complete track list is verified. */
data class ArtistAlbum(val album: Album, val songs: List<Song>?)
data class ArtistContents(val artist: Artist, val albums: List<ArtistAlbum>, val complete: Boolean = true) {
    val songs: List<Song> = albums.flatMap { it.songs.orEmpty() }
}
@Serializable
data class ListeningSession(
    val connectionId: String,
    val playlistId: String,
    val playlistName: String,
    val queue: List<Song>,
    val index: Int = 0,
    val positionMs: Long = 0,
    val shuffle: Boolean = true,
    val repeatMode: Int = 0,
    val queueId: String = "",
    val sourceType: String = "playlist",
    /** Stable occurrence tokens survive in-session shuffle; legacy sessions use queue indices. */
    val occurrenceIds: List<Int> = emptyList(),
    /** Completed/left visits proven by the service's isPlaying evidence, not a queue prefix. */
    val played: List<Int> = emptyList(),
) {
    val occurrences: List<Int> get() = occurrenceIds.takeIf { it.size == queue.size && it.distinct().size == it.size && it.all { token -> token >= 0 } }
        ?: queue.indices.toList()
    val playedIndices: List<Int> get() = played.mapNotNull { token -> occurrences.indexOf(token).takeIf { it >= 0 } }
}
