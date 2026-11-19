package com.everyonesoftware.audio.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import androidx.media3.session.MediaConstants
import android.net.Uri
import android.os.Bundle

import com.everyonesoftware.audio.AudioGraph
import com.everyonesoftware.audio.data.Credentials
import com.everyonesoftware.audio.data.Album
import com.everyonesoftware.audio.data.ListeningSession
import com.everyonesoftware.audio.data.Playlist
import com.everyonesoftware.audio.data.Song

/** Browsing is read-only. Only resolve() proposes a replacement of the shared listening session. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class CarLibrary(private val source: Source) {
    constructor(graph: AudioGraph) : this(GraphSource(graph))

    /** Read seam: production and JVM tests use the same browse and selection resolver. */
    internal interface Source {
        suspend fun bootstrap()
        fun artworkUri(credentials: Credentials, coverArtId: String?): String? = null
        val credentials: Credentials?
        val session: ListeningSession?
        val offline: Boolean
        suspend fun playlists(credentials: Credentials, reconcileCounts: Boolean): List<Playlist>
        suspend fun albums(credentials: Credentials): List<Album> = emptyList()
        suspend fun songs(credentials: Credentials, id: String, kind: String = "playlist"): List<Song>
        suspend fun playbackSongs(credentials: Credentials, id: String, kind: String = "playlist"): List<Song> = songs(credentials, id, kind)
    }

    data class Selection(val credentials: Credentials, val previous: ListeningSession?, val session: ListeningSession)
    data class BrowseAction(val command: String, val title: String, val icon: Int, val iconResource: Int)

    fun root(): MediaItem = folder(CarMediaIds.ROOT, "Music")

    suspend fun children(parentId: String, page: Int = 0, pageSize: Int = Int.MAX_VALUE): List<MediaItem> {
        require(page >= 0 && pageSize > 0) { "Invalid library page." }
        source.bootstrap()
        if (parentId == CarMediaIds.ROOT) return page(listOf(folder(CarMediaIds.PLAYLISTS, "Playlists"), folder(CarMediaIds.ALBUMS, "Albums"), folder(CarMediaIds.QUEUE, "Queue")), page, pageSize)
        val credentials = credentials()
        if (parentId == CarMediaIds.PLAYLISTS) {
            val playlists = playlists(credentials, reconcileCounts = true)
            val recent = source.session?.takeIf { it.sourceType == "playlist" }?.playlistId
            return page(playlists.sortedByDescending { it.id == recent }, page, pageSize).map { playlist ->
                playlistItem(credentials, playlist)
            }
        }
        if (parentId == CarMediaIds.QUEUE) {
            val session = source.session ?: return emptyList()
            require(session.connectionId == credentials.connection.id)
            return page(session.queue.mapIndexed { index, song ->
                songItem(CarMediaIds.queued(session.connectionId, session.queueId, index), song)
            }, page, pageSize)
        }
        if (parentId == CarMediaIds.ALBUMS) return page(albums(credentials), page, pageSize).map { albumItem(credentials, it) }
        val id = requireNotNull(CarMediaIds.parse(parentId)) { "Refresh playlists and retry." }
        require(id.kind in setOf("playlist", "album") && id.connection == credentials.connection.id) { "The music account changed. Refresh collections." }
        if (id.kind == "album") require(albums(credentials).any { it.id == id.parent }) { "This album is unavailable. Refresh albums." }
        else require(playlists(credentials).any { it.id == id.parent }) { "This playlist is unavailable. Refresh playlists." }
        val songs = source.songs(credentials, id.parent, id.kind)
        checkCurrent(credentials)
        val album = id.kind == "album"
        return page(listOf(playable(if (album) CarMediaIds.albumPlay(id.connection, id.parent) else CarMediaIds.play(id.connection, id.parent), "Play"),
            playable(if (album) CarMediaIds.albumShuffle(id.connection, id.parent) else CarMediaIds.shuffle(id.connection, id.parent), "Shuffle play")) +
            songs.mapIndexed { index, song -> songItem(if (album) CarMediaIds.albumTrack(id.connection, id.parent, index, song.id)
                else CarMediaIds.track(id.connection, id.parent, index, song.id), song) }, page, pageSize)
    }

    suspend fun item(mediaId: String): MediaItem {
        source.bootstrap()
        when (mediaId) {
            CarMediaIds.ROOT -> return root()
            CarMediaIds.PLAYLISTS -> return folder(mediaId, "Playlists")
            CarMediaIds.ALBUMS -> return folder(mediaId, "Albums")
            CarMediaIds.QUEUE -> return folder(mediaId, "Queue")
        }
        val id = requireNotNull(CarMediaIds.parse(mediaId)) { "Refresh playlists and retry." }
        val credentials = credentials()
        require(id.connection == credentials.connection.id) { "The music account changed. Refresh playlists." }
        return when (id.kind) {
            "album" -> {
                val album = requireNotNull(albums(credentials).firstOrNull { it.id == id.parent }) { "This album is unavailable. Refresh albums." }
                albumItem(credentials, album)
            }
            "playlist" -> {
                val playlist = playlists(credentials).firstOrNull { it.id == id.parent }
                requireNotNull(playlist) { "This playlist is unavailable. Refresh playlists." }
                playlistItem(credentials, playlist)
            }
            "queued" -> {
                val session = requireNotNull(source.session) { "Choose a playlist first." }
                require(session.connectionId == id.connection && session.queueId == id.parent) { "The queue changed. Reopen Queue." }
                songItem(mediaId, requireNotNull(session.queue.getOrNull(id.index!!)) { "The queue changed. Reopen Queue." })
            }
            "play", "shuffle", "track", "albumplay", "albumshuffle", "albumtrack" -> {
                val kind = if (id.kind.startsWith("album")) "album" else "playlist"
                if (kind == "album") require(albums(credentials).any { it.id == id.parent }) { "This album is unavailable. Refresh albums." }
                else require(playlists(credentials).any { it.id == id.parent }) { "This playlist is unavailable. Refresh playlists." }
                if (id.kind.endsWith("shuffle")) playable(mediaId, "Shuffle play")
                else if (id.kind.endsWith("play")) playable(mediaId, "Play")
                else {
                    val songs = source.songs(credentials, id.parent, kind)
                    checkCurrent(credentials)
                    val song = requireNotNull(songs.getOrNull(id.index!!)) { "The playlist changed. Refresh playlists." }
                    require(song.id == id.song) { "The playlist changed. Refresh playlists." }
                    songItem(mediaId, song)
                }
            }
            else -> throw IllegalArgumentException("This music is unavailable. Refresh Music.")
        }
    }

    suspend fun resolve(mediaId: String, command: String? = null): Selection {
        source.bootstrap()
        val id = requireNotNull(CarMediaIds.parse(mediaId)) { "Choose a song or shuffle action from Music." }
        val credentials = credentials()
        val previous = source.session
        require(id.connection == credentials.connection.id) { "The music account changed. Refresh playlists." }
        if (command != null) {
            require(command in setOf(PLAY_COLLECTION, SHUFFLE_COLLECTION, SHUFFLE_PLAYLIST)) { "Unsupported collection command." }
            require(id.kind in setOf("playlist", "album") && (command != SHUFFLE_PLAYLIST || id.kind == "playlist")) { "Choose a collection from Music." }
        }
        if (id.kind == "queued") {
            val saved = requireNotNull(previous) { "Choose a playlist first." }
            require(saved.connectionId == id.connection && saved.queueId == id.parent && id.index in saved.queue.indices) {
                "The queue changed. Reopen Queue."
            }
            return Selection(credentials, previous, saved.copy(index = id.index!!, positionMs = 0))
        }
        require(id.kind in setOf("playlist", "album", "play", "shuffle", "track", "albumplay", "albumshuffle", "albumtrack")) { "Choose a collection or song from Music." }
        val kind = if (id.kind.startsWith("album")) "album" else "playlist"
        val name = if (kind == "album") {
            requireNotNull(albums(credentials).firstOrNull { it.id == id.parent }) { "This album is unavailable. Refresh albums." }.name
        } else {
            requireNotNull(playlists(credentials).firstOrNull { it.id == id.parent }) { "This playlist is unavailable. Refresh playlists." }.name
        }
        val songs = source.playbackSongs(credentials, id.parent, kind)
        checkCurrent(credentials)
        val started = SessionRules.start(id.connection, id.parent, name, songs, sourceType = kind,
            shuffle = id.kind.endsWith("shuffle") || command == SHUFFLE_COLLECTION || command == SHUFFLE_PLAYLIST)
        val selected = if (!id.kind.endsWith("track")) started else {
            require(id.index in songs.indices && songs[id.index!!].id == id.song) { "The playlist changed. Reopen it and choose the song again." }
            started.copy(queue = songs, index = id.index, shuffle = false)
        }
        return Selection(credentials, previous, selected)
    }

    suspend fun search(query: String, page: Int = 0, pageSize: Int = Int.MAX_VALUE): List<MediaItem> {
        require(page >= 0 && pageSize > 0) { "Invalid library page." }
        val (credentials, matches) = searchMatches(query)
        return page(matches, page, pageSize).map { playlistItem(credentials, it) }
    }

    suspend fun searchCount(query: String): Int = searchMatches(query).second.size

    private suspend fun searchMatches(query: String): Pair<Credentials, List<Playlist>> {
        require(query.length <= 256) { "Use a shorter playlist name." }
        source.bootstrap()
        val credentials = credentials()
        return credentials to playlists(credentials).filter { it.name.contains(query.trim(), ignoreCase = true) }
    }

    suspend fun resolveSearch(query: String): Selection {
        require(query.length <= 256) { "Use a shorter playlist name." }
        source.bootstrap()
        val credentials = credentials()
        val previous = source.session
        if (query.isBlank()) return Selection(credentials, previous, requireNotNull(previous) { "Choose a playlist in Music first." })
        val matches = playlists(credentials).filter { it.name.contains(query.trim(), ignoreCase = true) }
        val exact = matches.filter { it.name.equals(query.trim(), ignoreCase = true) }
        val playlist = if (exact.isNotEmpty()) exact.singleOrNull() else matches.singleOrNull()
        requireNotNull(playlist) { "Say an exact playlist name or choose a playlist in Music." }
        val songs = source.playbackSongs(credentials, playlist.id)
        checkCurrent(credentials)
        return Selection(credentials, previous, SessionRules.start(credentials.connection.id, playlist.id, playlist.name, songs, shuffle = !source.offline))
    }

    private fun credentials() = requireNotNull(source.credentials) { "Open Music on your phone and connect to Navidrome." }

    private fun checkCurrent(credentials: Credentials) {
        require(source.credentials == credentials) { "The music account changed. Refresh playlists." }
    }

    private suspend fun playlists(credentials: Credentials, reconcileCounts: Boolean = false): List<Playlist> {
        val result = source.playlists(credentials, reconcileCounts)
        checkCurrent(credentials)
        return result
    }


    private suspend fun albums(credentials: Credentials): List<Album> {
        val result = source.albums(credentials)
        checkCurrent(credentials)
        return result
    }

    private class GraphSource(private val graph: AudioGraph) : Source {
        override suspend fun bootstrap() = graph.bootstrap()
        override fun artworkUri(credentials: Credentials, coverArtId: String?): String? = graph.browseArtwork.register(credentials, coverArtId)
        override val credentials get() = graph.credentials.value
        override val session get() = graph.session.value
        override val offline get() = graph.networkPolicy.offline

        override suspend fun playlists(credentials: Credentials, reconcileCounts: Boolean): List<Playlist> {
            if (offline) return graph.songCache.collections(credentials).filter { it.kind == "playlist" }.map {
                Playlist(it.id, it.name, it.songs.size, it.coverArtId)
            }
            val catalog = graph.playlists.listing(credentials, reconcileCounts)
            require(graph.credentials.value == credentials && catalog.connectionId == credentials.connection.id) { "The music account changed. Refresh playlists." }
            if (catalog.listError != null) throw com.everyonesoftware.audio.data.NavidromeException(catalog.listError)
            return catalog.playlists
        }

        override suspend fun albums(credentials: Credentials): List<Album> {
            if (offline) return graph.songCache.collections(credentials).filter { it.kind == "album" }.map {
                Album(it.id, it.name, it.artist, it.coverArtId, it.songs.size, it.year)
            }
            return graph.clientFor(credentials).albums()
        }

        override suspend fun songs(credentials: Credentials, id: String, kind: String): List<Song> {
            if (offline) {
                val snapshot = requireNotNull(graph.songCache.collection(credentials, kind, id)) { "This collection is not downloaded." }
                return snapshot.songs
            }
            return if (kind == "album") graph.clientFor(credentials).album(id).songs else graph.playlists.fetchContents(credentials, id).songs
        }
        override suspend fun playbackSongs(credentials: Credentials, id: String, kind: String): List<Song> {
            if (offline) {
                val snapshot = requireNotNull(graph.songCache.collection(credentials, kind, id)) { "This collection is not downloaded." }
                return snapshot.playableSongs { graph.songCache.complete(credentials, it) }
            }
            return songs(credentials, id, kind)
        }
    }

    private fun albumItem(credentials: Credentials, album: Album): MediaItem = MediaItem.Builder()
        .setMediaId(CarMediaIds.album(credentials.connection.id, album.id))
        .setMediaMetadata(MediaMetadata.Builder().setTitle(album.name).setSubtitle("${album.artist} · ${album.songCount} songs")
            .setExtras(collectionStyle())
            .setArtworkUri(source.artworkUri(credentials, album.coverArtId)?.let(Uri::parse))
            .setIsBrowsable(true).setIsPlayable(false).build()).build()

    private fun playlistItem(credentials: Credentials, playlist: Playlist): MediaItem = MediaItem.Builder()
        .setMediaId(CarMediaIds.playlist(credentials.connection.id, playlist.id))
        .setMediaMetadata(MediaMetadata.Builder().setTitle(playlist.name).setSubtitle("${playlist.songCount} songs")
            .setExtras(collectionStyle())
            .setArtworkUri(source.artworkUri(credentials, playlist.coverArtId)?.let(Uri::parse))
            .setIsBrowsable(true).setIsPlayable(false).build()).build()

    companion object {
        const val SHUFFLE_PLAYLIST = "com.everyonesoftware.audio.SHUFFLE_PLAYLIST"
        const val PLAY_COLLECTION = "com.everyonesoftware.audio.PLAY_COLLECTION"
        const val SHUFFLE_COLLECTION = "com.everyonesoftware.audio.SHUFFLE_COLLECTION"
        fun browseActions(): List<BrowseAction> = emptyList()
        fun commandButtons(packageName: String): List<CommandButton> = browseActions().map { action ->
            CommandButton.Builder(action.icon).setDisplayName(action.title)
                .setIconUri(Uri.parse("android.resource://$packageName/${action.iconResource}"))
                .setSessionCommand(SessionCommand(action.command, Bundle.EMPTY)).build()
        }

        private fun folder(id: String, title: String, subtitle: String? = null) = media(id, title, subtitle, browsable = true)
        private fun playable(id: String, title: String, subtitle: String? = null) = MediaItem.Builder().setMediaId(id)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setSubtitle(subtitle).setIsBrowsable(false).setIsPlayable(true)
                .setExtras(Bundle().apply { putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM) }).build()).build()
        private fun collectionStyle() = Bundle().apply {
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
        }
        fun songItem(id: String, song: Song): MediaItem = MediaItem.Builder().setMediaId(id)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist).setAlbumTitle(song.album)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).setIsBrowsable(false).setIsPlayable(true).build()).build()
        private fun media(id: String, title: String, subtitle: String?, browsable: Boolean): MediaItem =
            MediaItem.Builder().setMediaId(id).setMediaMetadata(MediaMetadata.Builder().setTitle(title).setSubtitle(subtitle)
                .setIsBrowsable(browsable).setIsPlayable(!browsable).build()).build()

        fun <T> page(items: List<T>, page: Int, pageSize: Int): List<T> {
            require(page >= 0 && pageSize > 0) { "Invalid library page." }
            val from = page.toLong() * pageSize
            if (from >= items.size) return emptyList()
            return items.subList(from.toInt(), (from + pageSize).coerceAtMost(items.size.toLong()).toInt())
        }
    }
}
