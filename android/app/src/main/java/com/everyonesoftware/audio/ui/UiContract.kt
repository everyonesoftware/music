package com.everyonesoftware.audio.ui

import com.everyonesoftware.audio.data.Connection
import com.everyonesoftware.audio.data.ListeningSession
import com.everyonesoftware.audio.data.Album
import com.everyonesoftware.audio.data.MusicLibrary
import com.everyonesoftware.audio.data.Playlist
import com.everyonesoftware.audio.data.Song
import com.everyonesoftware.audio.data.CollectionKind
import com.everyonesoftware.audio.data.CollectionLayout
import com.everyonesoftware.audio.playback.output.MediaOutputState

import com.everyonesoftware.audio.updates.UpdateUiState

enum class Screen { WELCOME, CONNECT, LIBRARIES, READY, PLAYLISTS, ALL_PLAYLISTS, ALL_ALBUMS, ALL_ARTISTS, ARTIST, PLAYLIST, SEARCH, ALBUM, PLAYER, SETTINGS, QUEUE, UPDATES, OFFLINE }

data class DeviceActionPage(val connection: Connection?, val screen: Screen, val item: String?)
data class DeviceActionFailure(val page: DeviceActionPage, val message: String)

enum class PlaylistSourceKind { SONG, PLAYLIST, ALBUM, ARTIST }
data class PlaylistDeletion(val ticket: Long, val page: DeviceActionPage, val id: String, val name: String,
    val busy: Boolean = false, val unconfirmed: Boolean = false, val error: String? = null)
data class PlaylistSource(val kind: PlaylistSourceKind, val id: String, val title: String)

data class PlaylistEditor(
    val ticket: Long,
    val page: DeviceActionPage,
    val source: PlaylistSource? = null,
    val creating: Boolean = false,
    val songs: List<Song>? = null,
    val destinations: List<Playlist> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val unconfirmed: Boolean = false,
)

data class AudioUiState(
    val booting: Boolean = true,
    val screen: Screen = Screen.WELCOME,
    val connections: List<Connection> = emptyList(),
    val connection: Connection? = null,
    val libraries: List<MusicLibrary> = emptyList(),
    val selectedLibraries: Set<String> = emptySet(),
    val playlists: List<Playlist> = emptyList(),
    val selectedPlaylist: Playlist? = null,
    val playlistSongs: List<Song> = emptyList(),
    val playlistLoading: Boolean = false,
    val playlistDetailError: String? = null,
    val searchQuery: String = "",
    val playlistCollectionQuery: String = "",
    val albumCollectionQuery: String = "",
    val playlistCollectionLayout: CollectionLayout = CollectionLayout.GRID,
    val albumCollectionLayout: CollectionLayout = CollectionLayout.GRID,
    val searchSongs: List<Song> = emptyList(),
    val searchLoading: Boolean = false,
    val searchError: String? = null,
    val albums: List<Album> = emptyList(),
    val artists: List<com.everyonesoftware.audio.data.Artist> = emptyList(),
    val artistsRefreshing: Boolean = false,
    val artistsError: String? = null,
    val artistCollectionQuery: String = "",
    val selectedArtist: com.everyonesoftware.audio.data.Artist? = null,
    val artistContents: com.everyonesoftware.audio.data.ArtistContents? = null,
    val artistLoading: Boolean = false,
    val artistError: String? = null,
    val artistReturnScreen: Screen = Screen.PLAYLISTS,
    val albumsRefreshing: Boolean = false,
    val albumsError: String? = null,
    val selectedAlbum: Album? = null,
    val albumSongs: List<Song> = emptyList(),
    val albumLoading: Boolean = false,
    val albumError: String? = null,
    val playlistsRefreshing: Boolean = false,
    val stalePlaylistIds: Set<String> = emptySet(),
    val playlistError: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val deviceActionFailure: DeviceActionFailure? = null,
    val playlistEditor: PlaylistEditor? = null,
    val playlistDeletion: PlaylistDeletion? = null,
    val playlistMutationBusy: Boolean = false,
    val pendingPlaylistDeletionIds: Set<String> = emptySet(),
    val playlistDeletionFocus: Long? = null,
    val playlistNotice: String? = null,
    val session: ListeningSession? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val mediaOutput: MediaOutputState = MediaOutputState(),
    val ratingBusy: Boolean = false,
    val ratingError: String? = null,
    val storage: com.everyonesoftware.audio.data.SongCacheState = com.everyonesoftware.audio.data.SongCacheState(),
    val selectedOfflineCollection: com.everyonesoftware.audio.data.OfflineCollection? = null,
    val artworkRevision: Long = 0,
    val updates: UpdateUiState = UpdateUiState(),
    val updatesReturnScreen: Screen = Screen.SETTINGS,
    val settingsReturnScreen: Screen? = null,
    val playlistReturnScreen: Screen = Screen.PLAYLISTS,
    val albumReturnScreen: Screen = Screen.PLAYLISTS,
)

internal val AudioUiState.deviceActionPage: DeviceActionPage
    get() = DeviceActionPage(connection, screen, when (screen) {
        Screen.ALBUM -> selectedAlbum?.id
        Screen.PLAYLIST -> selectedPlaylist?.id
        Screen.ARTIST -> selectedArtist?.id
        Screen.OFFLINE -> selectedOfflineCollection?.owner
        Screen.SEARCH -> searchQuery
        Screen.PLAYER -> session?.let { it.queue.getOrNull(it.index)?.id }
        Screen.QUEUE -> session?.queueId
        else -> null
    })

internal val AudioUiState.canEditPlaylists: Boolean
    get() = connection != null && !booting && !loading && !storage.settings.offline

internal fun AudioUiState.canOfferPlaylistDeletion(id: String) = canEditPlaylists && !playlistMutationBusy &&
    (screen == Screen.ALL_PLAYLISTS || (screen == Screen.PLAYLIST && selectedPlaylist?.id == id)) &&
    playlists.any { it.id == id && (it.canDelete || id in pendingPlaylistDeletionIds) }

internal fun AudioUiState.containsPlaylistSource(source: PlaylistSource): Boolean {
    val id = source.id
    return when (source.kind) {
        PlaylistSourceKind.PLAYLIST -> id !in pendingPlaylistDeletionIds && when (screen) {
            Screen.PLAYLISTS, Screen.ALL_PLAYLISTS, Screen.SEARCH -> playlists.any { it.id == id }
            Screen.PLAYLIST -> selectedPlaylist?.id == id
            else -> false
        }
        PlaylistSourceKind.ALBUM -> when (screen) {
            Screen.PLAYLISTS, Screen.ALL_ALBUMS, Screen.SEARCH -> albums.any { it.id == id }
            Screen.ARTIST -> artistContents?.albums?.any { it.album.id == id } == true
            Screen.ALBUM -> selectedAlbum?.id == id
            else -> false
        }
        PlaylistSourceKind.ARTIST -> when (screen) {
            Screen.PLAYLISTS, Screen.ALL_ARTISTS, Screen.SEARCH -> artists.any { it.id == id }
            Screen.ARTIST -> selectedArtist?.id == id
            else -> false
        }
        PlaylistSourceKind.SONG -> when (screen) {
            Screen.SEARCH -> searchSongs.any { it.id == id }
            Screen.PLAYLIST -> !playlistLoading && playlistDetailError == null && playlistSongs.any { it.id == id }
            Screen.ALBUM -> !albumLoading && albumError == null && albumSongs.any { it.id == id }
            Screen.ARTIST -> !artistLoading && artistError == null && artistContents?.songs?.any { it.id == id } == true
            Screen.PLAYER, Screen.QUEUE -> session?.takeIf { it.connectionId == connection?.id }?.let {
                if (screen == Screen.PLAYER) it.queue.getOrNull(it.index)?.id == id
                else it.queue.drop(it.index).any { song -> song.id == id } || it.playedIndices.any { index -> it.queue[index].id == id }
            } == true
            Screen.OFFLINE -> selectedOfflineCollection?.songs?.any { it.id == id } == true ||
                (selectedOfflineCollection == null && (id in storage.downloadedSongs || id in storage.cachedSongs))
            else -> false
        }
    }
}

fun AudioUiState.withCollectionLayout(kind: CollectionKind, layout: CollectionLayout): AudioUiState = when (kind) {
    CollectionKind.PLAYLISTS -> copy(playlistCollectionLayout = layout)
    CollectionKind.ALBUMS -> copy(albumCollectionLayout = layout)
}

/** Browsing navigation never issues playback commands or clears collection state. */
fun AudioUiState.navigateTo(destination: Screen): AudioUiState {
    val origins = setOf(Screen.PLAYLISTS, Screen.ALL_PLAYLISTS, Screen.ALL_ALBUMS, Screen.ALL_ARTISTS, Screen.SEARCH)
    val returningToSettings = screen == Screen.LIBRARIES || screen == Screen.OFFLINE ||
        (screen == Screen.UPDATES && updatesReturnScreen == Screen.SETTINGS)
    return copy(
        screen = destination,
        deviceActionFailure = deviceActionFailure.takeIf { destination == screen },
        playlistEditor = playlistEditor.takeIf { destination == screen },
        playlistDeletion = playlistDeletion.takeIf { destination == screen },
        playlistDeletionFocus = playlistDeletionFocus.takeIf { destination == screen },
        playlistNotice = playlistNotice.takeIf { destination == screen },
        settingsReturnScreen = if (destination == Screen.SETTINGS && screen != Screen.SETTINGS && !returningToSettings) screen else settingsReturnScreen,
        updatesReturnScreen = if (destination == Screen.UPDATES && screen != Screen.UPDATES) screen else updatesReturnScreen,
        playlistReturnScreen = if (destination == Screen.PLAYLIST && screen in origins) screen else playlistReturnScreen,
        albumReturnScreen = if (destination == Screen.ALBUM && (screen in origins || screen == Screen.ARTIST)) screen else albumReturnScreen,
        artistReturnScreen = if (destination == Screen.ARTIST && screen in origins) screen else artistReturnScreen,
    )
}

val AudioUiState.backDestination: Screen
    get() = when (screen) {
        Screen.PLAYLIST -> playlistReturnScreen
        Screen.ALBUM -> albumReturnScreen
        Screen.ARTIST -> artistReturnScreen
        Screen.SETTINGS -> settingsReturnScreen ?: if (connection != null) Screen.PLAYLISTS else Screen.WELCOME
        Screen.UPDATES -> updatesReturnScreen
        Screen.QUEUE -> Screen.PLAYER
        Screen.OFFLINE -> if (selectedOfflineCollection != null) Screen.OFFLINE else Screen.SETTINGS
        Screen.ALL_PLAYLISTS, Screen.ALL_ALBUMS, Screen.ALL_ARTISTS, Screen.PLAYER -> Screen.PLAYLISTS
        else -> if (connection != null) Screen.PLAYLISTS else Screen.WELCOME
    }

val AudioUiState.visiblePlaylists: List<Playlist>
    get() = playlists.filter { it.name.contains(playlistCollectionQuery.trim(), ignoreCase = true) }

val AudioUiState.visibleAlbums: List<Album>
    get() = albums.filter {
        it.name.contains(albumCollectionQuery.trim(), ignoreCase = true) ||
            it.artist.contains(albumCollectionQuery.trim(), ignoreCase = true)
    }

/** Captures the displayed ordered occurrences, not merely a song ID which may repeat. */
data class CollectionSongSelection(val connectionId: String, val kind: String, val id: String, val index: Int, val songs: List<Song>)

fun AudioUiState.collectionSongSession(request: CollectionSongSelection): ListeningSession? {
    if (booting || loading || storage.settings.offline || connection?.id != request.connectionId) return null
    val name = when (request.kind) {
        "playlist" -> if (screen == Screen.PLAYLIST && selectedPlaylist?.id == request.id && !playlistLoading && playlistDetailError == null && playlistSongs == request.songs) selectedPlaylist.name else null
        "album" -> if (screen == Screen.ALBUM && selectedAlbum?.id == request.id && !albumLoading && albumError == null && albumSongs == request.songs) selectedAlbum.name else null
        "artist" -> if (screen == Screen.ARTIST && selectedArtist?.id == request.id && !artistLoading && artistError == null &&
            artistContents?.let { it.complete && it.artist.id == request.id && it.songs == request.songs } == true) selectedArtist.name else null
        else -> null
    } ?: return null
    if (request.index !in request.songs.indices) return null
    return com.everyonesoftware.audio.playback.SessionRules.start(request.connectionId, request.id, name, request.songs,
        sourceType = request.kind, shuffle = false).copy(index = request.index)
}

interface AudioUiActions {
    fun newPlaylist() = Unit
    fun choosePlaylist(page: DeviceActionPage, source: PlaylistSource) = Unit
    fun dismissPlaylistEditor() = Unit
    fun createPlaylist(name: String) = Unit
    fun appendToPlaylist(id: String) = Unit
    fun requestPlaylistDeletion(page: DeviceActionPage, id: String) = Unit
    fun dismissPlaylistDeletion() = Unit
    fun confirmPlaylistDeletion() = Unit
    fun clearPlaylistNotice() = Unit
    fun retryPlaylistDetail() = Unit
    fun loadArtists() = Unit
    fun openArtist(id: String) = Unit
    fun playArtist(id: String, shuffle: Boolean = false) = Unit
    fun setArtistQuery(query: String) = Unit
    fun playCollectionSong(selection: CollectionSongSelection) = Unit
    fun playQueueSong(connectionId: String, queueId: String, occurrence: Int) = Unit
    fun setCollectionQuery(kind: CollectionKind, query: String) = Unit
    fun setCollectionLayout(kind: CollectionKind, layout: CollectionLayout) = Unit
    fun refreshCollection(kind: CollectionKind) = Unit
    fun refreshCurrentPage() = Unit
    fun downloadAlbum() = Unit
    fun downloadPlaylist() = Unit
    fun openOfflineCollection(kind: String, id: String) = Unit
    fun playOfflineCollection(kind: String, id: String) = Unit
    fun removeOfflineCollection(kind: String, id: String) = Unit
    fun setOfflineSettings(settings: com.everyonesoftware.audio.data.OfflineSettings) = Unit
    fun clearSongCache() = Unit
    fun removeDownload(songId: String) = Unit
    fun downloadSongs(songs: List<Song>) = Unit
    fun refreshMediaOutputs() = Unit
    fun selectMediaOutput(id: String) = Unit
    fun checkForUpdates() = Unit
    fun retryUpdateChangelog() = Unit
    fun downloadUpdate() = Unit
    fun cancelUpdateDownload() = Unit
    fun installUpdate() = Unit
    fun pauseForUpdate() = Unit
    fun connect(name: String, url: String, username: String, password: String)
    fun showScreen(screen: Screen)
    fun setLibrary(id: String, selected: Boolean)
    fun saveLibraries()
    fun loadPlaylists()
    fun loadAlbums()
    fun openAlbum(id: String)
    fun playAlbum(id: String, shuffle: Boolean = false)
    fun playSong(song: Song) = Unit
    fun search(query: String) = Unit
    fun openPlaylist(id: String) = Unit
    fun startPlaylist(id: String, requireLiveContents: Boolean = false)
    /** Fresh playlist queue; independent of the current session's transport. */
    fun playPlaylist(id: String, shuffle: Boolean = false)
    fun togglePlay()
    fun next()
    fun previous()
    fun seekTo(positionMs: Long)
    fun shuffleUpNext()
    fun toggleRepeat()
    fun rateSong(rating: Int)
    fun clearError()
    fun switchConnection(id: String)
}
