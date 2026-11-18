package com.everyonesoftware.audio.ui

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.everyonesoftware.audio.AudioApplication
import com.everyonesoftware.audio.AudioGraph
import com.everyonesoftware.audio.BuildConfig
import com.everyonesoftware.audio.data.Connection
import com.everyonesoftware.audio.data.CollectionKind
import com.everyonesoftware.audio.data.CollectionLayout
import com.everyonesoftware.audio.data.Playlist
import com.everyonesoftware.audio.data.OfflineCollection
import com.everyonesoftware.audio.data.Song
import com.everyonesoftware.audio.data.Credentials
import com.everyonesoftware.audio.data.NavidromeClient
import com.everyonesoftware.audio.data.NavidromeException
import com.everyonesoftware.audio.playback.PlaybackService
import com.everyonesoftware.audio.playback.CarLibrary
import com.everyonesoftware.audio.playback.CarMediaIds
import com.everyonesoftware.audio.playback.SessionRules
import com.everyonesoftware.audio.playback.output.AndroidMediaOutputBackend
import com.everyonesoftware.audio.playback.output.MediaOutputBackend
import com.everyonesoftware.audio.playback.output.MediaOutputController
import com.google.common.util.concurrent.ListenableFuture
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioViewModel @JvmOverloads constructor(application: Application, private val graph: AudioGraph = (application as AudioApplication).graph, private val connectController: Boolean = true,
    outputBackend: MediaOutputBackend? = if (connectController) AndroidMediaOutputBackend(application) else null) : AndroidViewModel(application), AudioUiActions {
    private val current = MutableStateFlow(AudioUiState())
    val state = current.asStateFlow()
    private val mediaOutput = outputBackend?.let { MediaOutputController(it, viewModelScope) }
    private var operation: Job? = null
    private var albumsOperation: Job? = null
    private var albumsInitialized = false
    private var searchOperation: Job? = null
    private var searchGeneration = 0L
    private var playlistDetailOperation: Job? = null
    private var playlistDetailGeneration = 0L
    private var albumDetailOperation: Job? = null
    private var albumDetailGeneration = 0L
    private val initialization: Job
    private var generation = 0L
    private var firstLibrarySetup = false
    private var installOperation: Job? = null
    private val installRequests = Channel<Intent>(Channel.RENDEZVOUS)
    val installIntents = installRequests.receiveAsFlow()
    private val controllerFuture: ListenableFuture<MediaController>? = if (connectController) {
        MediaController.Builder(application, SessionToken(application, ComponentName(application, PlaybackService::class.java))).buildAsync()
    } else null

    init {
        mediaOutput?.let { output ->
            viewModelScope.launch { output.state.collect { routes -> current.update { it.copy(mediaOutput = routes) } } }
        }
        viewModelScope.launch {
            graph.songCache.state.collect { storage -> current.update { it.copy(storage = storage) } }
        }
        viewModelScope.launch {
            graph.artworkCache.state.collect { revision -> current.update { it.copy(artworkRevision = revision) } }
        }
        viewModelScope.launch {
            graph.artworkCache.error.collect { error -> if (error != null) current.update { it.copy(error = error) } }
        }
        viewModelScope.launch {
            graph.updates.state.collect { updates -> current.update { it.copy(updates = updates) } }
        }
        // Main.immediate may run startup to completion inline. Publish the Job before any
        // startup-triggered refresh can join it (also true for an already-bootstrapped graph).
        initialization = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                graph.bootstrap()
                val playlistLayout = graph.store.collectionLayout(CollectionKind.PLAYLISTS)
                val albumLayout = graph.store.collectionLayout(CollectionKind.ALBUMS)
                current.update { it.copy(playlistCollectionLayout = playlistLayout, albumCollectionLayout = albumLayout) }
                restoreConnection()
                val session = graph.session.value
                current.update { it.copy(booting = false, session = session, positionMs = session?.positionMs ?: 0,
                    screen = when { graph.credentials.value != null -> Screen.PLAYLISTS; else -> Screen.WELCOME }) }
                if (graph.credentials.value != null) loadAlbums()
                // Startup refresh must not cancel an explicit request received while restoring.
                if (current.value.screen == Screen.PLAYLISTS && generation == 0L) loadPlaylists()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { current.update { it.copy(booting = false, error = safeMessage(error)) } }
        }
        initialization.start()
        viewModelScope.launch {
            graph.session.collect { session -> current.update { it.copy(session = session, positionMs = session?.positionMs ?: 0) } }
        }
        viewModelScope.launch {
            graph.playlists.state.collect { catalog ->
                current.update {
                    if (it.connection?.id != catalog.connectionId) it else it.copy(
                        playlists = catalog.playlists.sortedByDescending { playlist -> playlist.id == graph.session.value?.playlistId },
                        playlistsRefreshing = catalog.refreshing, stalePlaylistIds = catalog.staleIds, playlistError = catalog.error)
                }
            }
        }
        viewModelScope.launch {
            graph.playback.collect { player -> current.update { it.copy(playing = player.playing,
                positionMs = player.positionMs, durationMs = player.durationMs,
                error = player.error ?: it.error) } }
        }
    }

    private suspend fun restoreConnection() {
        ++playlistDetailGeneration
        playlistDetailOperation?.cancel()
        ++searchGeneration
        searchOperation?.cancel()
        albumsOperation?.cancel()
        albumsInitialized = false
        ++albumDetailGeneration
        albumDetailOperation?.cancel()
        val connection = graph.credentials.value?.connection
        val playlists = connection?.let { graph.store.cachedPlaylists(it.id) }.orEmpty()
        val selected = connection?.let { graph.store.selectedLibraries(it.id) }.orEmpty()
        val connections = graph.store.connections()
        current.update { it.copy(connection = connection, connections = connections, selectedOfflineCollection = null, playlists = playlists, albums = emptyList(), albumsError = null, selectedAlbum = null, albumSongs = emptyList(), albumError = null,
            selectedPlaylist = null, playlistSongs = emptyList(), playlistLoading = false, playlistDetailError = null,
            searchQuery = "", searchSongs = emptyList(), searchLoading = false, searchError = null,
            playlistCollectionQuery = "", albumCollectionQuery = "",
            settingsReturnScreen = null, playlistReturnScreen = Screen.PLAYLISTS, albumReturnScreen = Screen.PLAYLISTS,
            albumLoading = false, albumsRefreshing = false,
            libraries = emptyList(), selectedLibraries = selected,
            playlistsRefreshing = graph.playlists.state.value.refreshing,
            stalePlaylistIds = graph.playlists.state.value.staleIds, playlistError = graph.playlists.state.value.error) }
    }

    private fun work(block: suspend () -> Unit) {
        val ticket = ++generation
        operation?.cancel()
        current.update { it.copy(loading = true, error = null) }
        operation = viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (ticket == generation) current.update { it.copy(error = safeMessage(error)) } }
            finally { if (ticket == generation) current.update { it.copy(loading = false) } }
        }
    }

    private fun safeMessage(error: Exception): String = when (error) {
        is NavidromeException, is IllegalArgumentException -> error.message ?: "Check your music server and retry."
        else -> "Unable to complete that action. Check your connection and retry."
    }

    override fun connect(name: String, url: String, username: String, password: String) {
        if (current.value.loading) return
        work {
            require(username.isNotBlank() && password.isNotEmpty()) { "Enter your username and password." }
            val base = NavidromeClient.validateBaseUrl(url, allowLocalHttp = BuildConfig.DEBUG)
            val existing = graph.store.connections().firstOrNull { it.baseUrl == base && it.username == username }
            val connection = Connection(existing?.id ?: UUID.randomUUID().toString(), name.trim().ifBlank { "Navidrome" }, base, username)
            val credentials = Credentials(connection, password)
            val client = NavidromeClient(credentials, allowLocalHttp = BuildConfig.DEBUG, networkPolicy = graph.networkPolicy)
            client.ping()
            val libraries = client.libraries()
            graph.store.saveConnection(credentials)
            controllerFuture?.takeIf { it.isDone && !it.isCancelled }?.get()?.pause()
            graph.activate(connection.id)
            restoreConnection()
            firstLibrarySetup = true
            current.update { it.copy(screen = Screen.LIBRARIES, libraries = libraries, selectedLibraries = libraries.map { library -> library.id }.toSet()) }
        }
    }

    override fun showScreen(screen: Screen) {
        if (screen == Screen.OFFLINE) current.update { it.copy(selectedOfflineCollection = null) }
        if (screen == Screen.UPDATES && current.value.screen != Screen.UPDATES) {
            current.update { it.copy(updatesReturnScreen = it.screen) }
        }
        if (current.value.screen == Screen.CONNECT && screen != Screen.CONNECT) {
            ++generation; operation?.cancel()
            current.update { it.copy(loading = false) }
        }
        current.update { it.navigateTo(screen) }
        when (screen) {
            Screen.PLAYLISTS -> loadPlaylists()
            Screen.ALL_ALBUMS -> if (!albumsInitialized) loadAlbums()
            Screen.SEARCH -> {
                current.update { it.copy(screen = screen, error = null) }
                loadAlbums()
            }
            Screen.ALBUM -> current.update { it.copy(screen = screen, error = null) }
            Screen.LIBRARIES -> {
                firstLibrarySetup = false
                current.update { it.copy(screen = screen, error = null) }
                work {
                    val id = graph.credentials.value?.connection?.id ?: error("Connect first.")
                    val libraries = graph.client().libraries()
                    val selected = graph.store.selectedLibraries(id).intersect(libraries.map { it.id }.toSet())
                    current.update { it.copy(libraries = libraries, selectedLibraries = selected) }
                }
            }
            Screen.PLAYER, Screen.QUEUE -> {
                if (graph.session.value == null) loadPlaylists() else current.update { it.copy(screen = screen, error = null) }
            }
            else -> current.update { it.copy(screen = screen, error = null) }
        }
    }

    override fun setLibrary(id: String, selected: Boolean) {
        if (current.value.loading || current.value.libraries.none { it.id == id }) return
        current.update { it.copy(selectedLibraries = if (selected) it.selectedLibraries + id else it.selectedLibraries - id) }
    }

    override fun saveLibraries() {
        val id = graph.credentials.value?.connection?.id ?: return
        val selected = current.value.selectedLibraries.toSet()
        work {
            graph.store.setSelectedLibraries(id, selected)
            current.update { it.copy(screen = if (firstLibrarySetup) Screen.READY else Screen.SETTINGS) }
        }
    }

    override fun loadPlaylists() {
        current.update { it.copy(screen = Screen.PLAYLISTS) }
        loadAlbums()
        viewModelScope.launch {
            initialization.join()
            graph.playlists.refresh()
        }
    }

    override fun setCollectionQuery(kind: CollectionKind, query: String) {
        current.update { when (kind) {
            CollectionKind.PLAYLISTS -> it.copy(playlistCollectionQuery = query)
            CollectionKind.ALBUMS -> it.copy(albumCollectionQuery = query)
        } }
    }

    override fun setCollectionLayout(kind: CollectionKind, layout: CollectionLayout) {
        current.update { it.withCollectionLayout(kind, layout) }
        viewModelScope.launch {
            try { graph.store.setCollectionLayout(kind, layout) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { current.update { it.copy(error = "Unable to save collection layout. Try again.") } }
        }
    }

    override fun refreshCollection(kind: CollectionKind) {
        when (kind) {
            CollectionKind.ALBUMS -> loadAlbums()
            CollectionKind.PLAYLISTS -> {
                val credentials = graph.credentials.value ?: return
                viewModelScope.launch {
                    initialization.join()
                    if (graph.credentials.value == credentials) graph.playlists.refresh()
                }
            }
        }
    }

    /** Refresh server metadata in place; never navigate, alter playback, or schedule downloads. */
    override fun refreshCurrentPage() {
        if (graph.networkPolicy.offline) return
        val credentials = graph.credentials.value ?: return
        val page = current.value
        if (page.connection != credentials.connection) return
        when (page.screen) {
            Screen.PLAYLISTS -> {
                refreshCollection(CollectionKind.PLAYLISTS)
                loadAlbums()
            }
            Screen.ALL_PLAYLISTS -> refreshCollection(CollectionKind.PLAYLISTS)
            Screen.ALL_ALBUMS -> loadAlbums()
            Screen.PLAYLIST -> if (!page.playlistLoading) page.selectedPlaylist?.let { loadPlaylistDetail(it) }
            Screen.ALBUM -> if (!page.albumLoading) page.selectedAlbum?.let { loadAlbumDetail(it.id) }
            Screen.SEARCH -> if (page.searchQuery.isNotBlank()) loadSearch(page.searchQuery, retainResults = true)
            else -> Unit
        }
    }

    override fun loadAlbums() {
        val credentials = graph.credentials.value ?: return
        if (current.value.albumsRefreshing && current.value.connection?.id == credentials.connection.id) return
        albumsOperation?.cancel()
        current.update { it.copy(albumsRefreshing = true, albumsError = null) }
        albumsOperation = viewModelScope.launch {
            try {
                val albums = graph.clientFor(credentials).albums()
                if (graph.credentials.value == credentials) {
                    albumsInitialized = true
                    current.update { it.copy(albums = albums, albumsError = null) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (graph.credentials.value == credentials) current.update { it.copy(albumsError = safeMessage(error)) }
            } finally {
                if (graph.credentials.value == credentials) current.update { it.copy(albumsRefreshing = false) }
            }
        }
    }

    override fun openAlbum(id: String) {
        if (graph.networkPolicy.offline) { openOfflineCollection("album", id); return }
        if (graph.credentials.value == null) return
        val album = current.value.albums.firstOrNull { it.id == id } ?: return
        current.update { it.navigateTo(Screen.ALBUM).copy(selectedAlbum = album, albumSongs = emptyList(), albumLoading = true, albumError = null) }
        loadAlbumDetail(id)
    }

    private fun loadAlbumDetail(id: String) {
        val credentials = graph.credentials.value ?: return
        val ticket = ++albumDetailGeneration
        albumDetailOperation?.cancel()
        current.update { it.copy(albumLoading = true, albumError = null) }
        albumDetailOperation = viewModelScope.launch {
            try {
                val result = graph.clientFor(credentials).album(id)
                if (ticket == albumDetailGeneration && graph.credentials.value == credentials && current.value.selectedAlbum?.id == id) {
                    current.update { it.copy(selectedAlbum = result.album, albumSongs = result.songs, albumLoading = false) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (ticket == albumDetailGeneration && graph.credentials.value == credentials && current.value.selectedAlbum?.id == id) {
                    current.update { it.copy(albumLoading = false, albumError = safeMessage(error)) }
                }
            }
        }
    }

    override fun playAlbum(id: String, shuffle: Boolean) {
        val detail = current.value
        if (detail.loading || detail.selectedAlbum?.id != id || detail.albumLoading || detail.albumError != null ||
            detail.albumSongs.isEmpty() || graph.networkPolicy.offline) return
        work {
            val credentials = requireNotNull(graph.credentials.value) { "Connect first." }
            val result = graph.clientFor(credentials).album(id)
            val album = result.album
            val previous = graph.session.value
            val session = SessionRules.start(credentials.connection.id, album.id, album.name, result.songs, sourceType = "album", shuffle = shuffle)
            val controller = controller()
            graph.setSession(session, credentials, previous)
            controller.play()
            current.update { it.copy(screen = Screen.PLAYER, selectedAlbum = album, albumSongs = result.songs, ratingError = null) }
        }
    }

    fun onForeground() {
        mediaOutput?.start()
        mediaOutput?.refresh()
        if (connectController) graph.updates.onForeground()
        if (!current.value.booting && current.value.screen == Screen.PLAYLISTS) loadPlaylists()
    }

    fun onBackground() { installOperation?.cancel(); mediaOutput?.stop() }


    override fun setOfflineSettings(settings: com.everyonesoftware.audio.data.OfflineSettings) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try { graph.songCache.setSettings(settings) }
            catch (_: Exception) { current.update { it.copy(error = "Cannot save storage settings.") } }
        }
    }
    override fun clearSongCache() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try { graph.songCache.clearCache(); graph.artworkCache.clearAutomatic() }
            catch (_: Exception) { current.update { it.copy(error = "Cannot clear saved songs. Retry.") } }
        }
    }
    override fun removeDownload(songId: String) {
        val credentials = graph.credentials.value ?: return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try { graph.songCache.removeDownload(credentials, songId) }
            catch (_: Exception) { current.update { it.copy(error = "Cannot remove this download. Retry.") } }
        }
    }
    override fun downloadSongs(songs: List<Song>) {
        val credentials = graph.credentials.value ?: return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                graph.songCache.download(credentials, songs)
            }
            catch (_: Exception) { current.update { it.copy(error = "Cannot download these songs. Retry.") } }
        }
    }

    override fun downloadAlbum() {
        val album = current.value.selectedAlbum ?: return
        if (current.value.albumLoading || current.value.albumError != null) return
        saveCollectionDownload(OfflineCollection("album", album.id, album.name, album.artist, album.year,
            album.coverArtId, current.value.albumSongs))
    }
    override fun downloadPlaylist() {
        val playlist = current.value.selectedPlaylist ?: return
        if (current.value.playlistLoading || current.value.playlistDetailError != null) return
        saveCollectionDownload(OfflineCollection("playlist", playlist.id, playlist.name,
            coverArtId = playlist.coverArtId, songs = current.value.playlistSongs))
    }
    private fun saveCollectionDownload(collection: OfflineCollection) {
        val credentials = graph.credentials.value ?: return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                graph.songCache.downloadCollection(credentials, collection)
            } catch (_: Exception) {
                if (graph.credentials.value == credentials) current.update { it.copy(error = "Cannot save this collection. Previous downloads are kept. Retry.") }
            }
        }
    }
    override fun openOfflineCollection(kind: String, id: String) {
        val credentials = graph.credentials.value ?: return
        val snapshot = graph.songCache.collection(credentials, kind, id) ?: return
        current.update { it.copy(screen = Screen.OFFLINE, selectedOfflineCollection = snapshot, error = null) }
    }
    override fun playOfflineCollection(kind: String, id: String) {
        if (current.value.loading) return
        work {
            val credentials = requireNotNull(graph.credentials.value) { "Connect first." }
            val snapshot = requireNotNull(graph.songCache.collection(credentials, kind, id)) { "This download was removed. Reopen Saved music." }
            val songs = snapshot.playableSongs { graph.songCache.complete(credentials, it) }
            val previous = graph.session.value
            val session = SessionRules.start(credentials.connection.id, snapshot.id, snapshot.name, songs,
                sourceType = snapshot.kind, shuffle = false)
            val controller = controller()
            require(graph.credentials.value == credentials) { "The active music account changed." }
            graph.setSession(session, credentials, previous)
            controller.play()
            current.update { it.copy(screen = Screen.PLAYER, ratingError = null) }
        }
    }
    override fun removeOfflineCollection(kind: String, id: String) {
        val credentials = graph.credentials.value ?: return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                graph.songCache.removeCollection(credentials, kind, id)
                if (graph.credentials.value == credentials) current.update { it.copy(selectedOfflineCollection = null) }
            } catch (_: Exception) {
                if (graph.credentials.value == credentials) current.update { it.copy(error = "Cannot remove this collection. Retry.") }
            }
        }
    }

    override fun refreshMediaOutputs() { mediaOutput?.refresh() }
    override fun selectMediaOutput(id: String) { mediaOutput?.select(id) }

    override fun checkForUpdates() { graph.updates.coordinator.check() }
    override fun setAutomaticUpdates(enabled: Boolean) { graph.updates.coordinator.setAutomaticChecks(enabled) }
    override fun downloadUpdate() { graph.updates.coordinator.download() }
    override fun cancelUpdateDownload() { graph.updates.coordinator.cancelDownload() }
    override fun pauseForUpdate() = playerCommand { it.pause() }

    /** A separate, freshly confirmed action; no check or download ever sends a player command. */
    override fun installUpdate() {
        if (installOperation?.isActive == true) return
        installOperation = viewModelScope.launch {
            val updater = graph.updates.coordinator
            var handedOff = false
            try {
                if (current.value.session != null) {
                    val player = controller()
                    if (player.playWhenReady || player.isPlaying) return@launch
                }
                if (!graph.updates.canInstall()) {
                    updater.permissionRequired(true)
                    installRequests.send(graph.updates.permissionIntent())
                    return@launch
                }
                val (file, release) = updater.beginInstall() ?: return@launch
                val intent = graph.updates.verifiedInstallIntent(file, release)
                // Recheck after IO: a car/controller may have resumed or changed the queue.
                val session = graph.session.value
                if (session != null) {
                    val player = controller()
                    if (player.playWhenReady || player.isPlaying) { updater.installFailed(); return@launch }
                    if (player.mediaItemCount > 0) {
                        val index = player.currentMediaItemIndex
                        check(index in session.queue.indices && player.currentMediaItem?.mediaId ==
                            CarMediaIds.queued(session.connectionId, session.queueId, index))
                        graph.updateProgress(index, player.currentPosition, player.repeatMode,
                            session.connectionId, session.queueId, session.queue.map { it.id })
                        check(graph.session.value?.queueId == session.queueId &&
                            graph.session.value?.connectionId == session.connectionId)
                    }
                }
                installRequests.send(intent)
                handedOff = true
            } catch (cancelled: CancellationException) {
                if (!handedOff) updater.installerReturned()
                throw cancelled
            } catch (_: Exception) { updater.installFailed() }
        }
    }

    /** Main-thread check immediately before the activity hands control to Android. */
    fun allowInstallerLaunch(intent: Intent): Boolean {
        if (intent.type != "application/vnd.android.package-archive") return true
        val player = controllerFuture?.takeIf { it.isDone && !it.isCancelled }?.let {
            try { it.get() } catch (_: Exception) { null }
        }
        val allowed = !current.value.playing && (current.value.session == null ||
            (player != null && !player.playWhenReady && !player.isPlaying))
        if (!allowed) graph.updates.coordinator.installFailed()
        return allowed
    }

    fun installerLaunchFailed() { graph.updates.coordinator.installFailed() }

    override fun playSong(song: Song) {
        if (current.value.loading) return
        work {
            val credentials = requireNotNull(graph.credentials.value) { "Connect first." }
            val previous = graph.session.value
            val session = SessionRules.start(credentials.connection.id, song.id, song.title, listOf(song),
                sourceType = "song", shuffle = false)
            val controller = controller()
            graph.setSession(session, credentials, previous)
            controller.play()
            current.update { it.copy(screen = Screen.PLAYER, ratingError = null) }
        }
    }

    override fun search(query: String) = loadSearch(query, retainResults = false)

    private fun loadSearch(query: String, retainResults: Boolean) {
        if (retainResults && current.value.searchLoading) return
        val ticket = ++searchGeneration
        searchOperation?.cancel()
        val credentials = graph.credentials.value
        current.update { it.copy(searchQuery = query, searchSongs = if (retainResults) it.searchSongs else emptyList(),
            searchLoading = credentials != null && query.isNotBlank(), searchError = null) }
        if (credentials == null || query.isBlank()) return
        searchOperation = viewModelScope.launch {
            try {
                if (!retainResults) delay(300)
                val songs = graph.clientFor(credentials).searchSongs(query.trim())
                if (ticket == searchGeneration && graph.credentials.value == credentials) {
                    current.update { it.copy(searchSongs = songs, searchLoading = false) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (ticket == searchGeneration && graph.credentials.value == credentials) {
                    current.update { it.copy(searchLoading = false, searchError = safeMessage(error)) }
                }
            }
        }
    }

    override fun openPlaylist(id: String) {
        if (graph.networkPolicy.offline) { openOfflineCollection("playlist", id); return }
        if (graph.credentials.value == null) return
        val playlist = current.value.playlists.firstOrNull { it.id == id } ?: return
        current.update { it.navigateTo(Screen.PLAYLIST).copy(selectedPlaylist = playlist, playlistSongs = emptyList(),
            playlistLoading = true, playlistDetailError = null) }
        loadPlaylistDetail(playlist)
    }

    private fun loadPlaylistDetail(playlist: Playlist) {
        val credentials = graph.credentials.value ?: return
        val id = playlist.id
        val ticket = ++playlistDetailGeneration
        playlistDetailOperation?.cancel()
        current.update { it.copy(playlistLoading = true, playlistDetailError = null) }
        playlistDetailOperation = viewModelScope.launch {
            try {
                val result = graph.playlists.fetchContents(credentials, id)
                if (ticket == playlistDetailGeneration && graph.credentials.value == credentials && current.value.selectedPlaylist?.id == id) {
                    current.update { it.copy(selectedPlaylist = playlist.copy(songCount = result.songs.size),
                        playlistSongs = result.songs, playlistLoading = false) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (ticket == playlistDetailGeneration && graph.credentials.value == credentials && current.value.selectedPlaylist?.id == id) {
                    current.update { it.copy(playlistLoading = false, playlistDetailError = safeMessage(error)) }
                }
            }
        }
    }

    override fun startPlaylist(id: String) = startPlaylistQueue(id, shuffle = true)

    override fun playPlaylist(id: String, shuffle: Boolean) {
        val detail = current.value
        if (detail.selectedPlaylist?.id != id || detail.playlistLoading || detail.playlistDetailError != null ||
            detail.playlistSongs.isEmpty() || graph.networkPolicy.offline) return
        startPlaylistQueue(id, shuffle)
    }

    private fun startPlaylistQueue(id: String, shuffle: Boolean) {
        if (graph.networkPolicy.offline) { playOfflineCollection("playlist", id); return }
        if (current.value.loading) return
        work {
            val credentials = requireNotNull(graph.credentials.value) { "Connect first." }
            val connection = credentials.connection
            val previous = graph.session.value
            val playlist = current.value.playlists.firstOrNull { it.id == id } ?: error("Refresh playlists and retry.")
            val songs = graph.playlists.fetchContents(credentials, id).songs
            val session = SessionRules.start(connection.id, playlist.id, playlist.name, songs, shuffle = shuffle)
            val controller = controller()
            graph.setSession(session, credentials, previous)
            controller.play()
            current.update { it.copy(screen = Screen.PLAYER, ratingError = null) }
        }
    }

    fun openPlayer() {
        viewModelScope.launch {
            initialization.join()
            showScreen(Screen.PLAYER)
        }
    }

    fun playFromSearch(query: String) {
        work {
            initialization.join()
            graph.bootstrap()
            val selected = CarLibrary(graph).resolveSearch(query)
            val controller = controller()
            graph.setSession(selected.session, selected.credentials, selected.previous)
            controller.play()
            current.update { it.copy(screen = Screen.PLAYER, ratingError = null) }
        }
    }

    private suspend fun controller(): MediaController {
        val future = controllerFuture ?: error("The player is unavailable.")
        return suspendCancellableCoroutine { continuation ->
            future.addListener({
                if (continuation.isActive) {
                    try { continuation.resume(future.get()) }
                    catch (error: Exception) { continuation.resumeWithException(error) }
                }
            }, ContextCompat.getMainExecutor(getApplication()))
        }
    }

    private fun playerCommand(action: (MediaController) -> Unit) {
        viewModelScope.launch {
            try { action(controller()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { current.update { it.copy(error = "The player could not connect. Reopen Music and retry.") } }
        }
    }
    override fun togglePlay() = playerCommand { if (it.playWhenReady) it.pause() else { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() } }
    override fun next() = playerCommand { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }
    override fun previous() = playerCommand { it.seekToPrevious() }
    override fun seekTo(positionMs: Long) = playerCommand { it.seekTo(positionMs.coerceAtLeast(0)) }
    override fun toggleRepeat() = playerCommand { it.repeatMode = (it.repeatMode + 1) % 3 }

    override fun toggleShuffle() {
        if (current.value.loading) return
        work {
            val session = graph.session.value ?: return@work
            val next = if (session.shuffle) session.copy(shuffle = false) else {
                val remaining = session.queue.filterIndexed { index, _ -> index != session.index }.shuffled()
                session.copy(queue = listOf(session.queue[session.index]) + remaining, index = 0, shuffle = true)
            }
            graph.setSession(next)
        }
    }

    override fun rateSong(rating: Int) {
        val session = graph.session.value ?: return
        val song = session.queue.getOrNull(session.index) ?: return
        if (current.value.ratingBusy || rating !in 0..5) return
        val credentials = graph.credentials.value ?: return
        val client = graph.clientFor(credentials)
        current.update { it.copy(ratingBusy = true, ratingError = null) }
        viewModelScope.launch {
            try {
                val confirmed = client.rateSong(song.id, rating)
                graph.applyRating(session.connectionId, song.id, confirmed)
                graph.playlists.ratingChanged(credentials)
                if (isRatingTarget(session.connectionId, song.id) && confirmed != rating) {
                    current.update { it.copy(ratingError = "The server reports a different rating. Retry if you want to change it.") }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (isRatingTarget(session.connectionId, song.id)) current.update { it.copy(ratingError = safeMessage(error)) } }
            finally { current.update { it.copy(ratingBusy = false) } }
        }
    }

    private fun isRatingTarget(connectionId: String, songId: String): Boolean = graph.session.value?.let {
        it.connectionId == connectionId && it.queue.getOrNull(it.index)?.id == songId
    } == true

    fun artworkUrl(id: String?): String? {
        val credentials = graph.credentials.value ?: return null
        if (id == null) return null
        graph.artworkCache.prefetch(credentials, listOf(id))
        return graph.artworkCache.cachedUri(credentials, id)
    }
    override fun clearError() { current.update { it.copy(error = null, ratingError = null) } }

    override fun switchConnection(id: String) {
        if (id == graph.credentials.value?.connection?.id || current.value.loading) return
        work {
            if (connectController) {
                val controller = controller()
                controller.pause()
                graph.credentials.value?.connection?.id?.let {
                    if (controller.mediaItemCount > 0) graph.updateProgress(controller.currentMediaItemIndex, controller.currentPosition, controller.repeatMode, it)
                }
            }
            graph.activate(id)
            restoreConnection()
            val session = graph.session.value
            current.update { it.copy(session = session, positionMs = session?.positionMs ?: 0, screen = if (session != null) Screen.PLAYER else Screen.PLAYLISTS) }
            if (session == null) {
                loadAlbums()
                graph.playlists.refresh()
            }
        }
    }

    override fun onCleared() {
        mediaOutput?.stop()
        controllerFuture?.let(MediaController::releaseFuture)
        super.onCleared()
    }
}
