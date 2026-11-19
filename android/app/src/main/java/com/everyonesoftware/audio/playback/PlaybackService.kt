package com.everyonesoftware.audio.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionResult
import com.everyonesoftware.audio.AudioApplication
import com.everyonesoftware.audio.AudioGraph
import com.everyonesoftware.audio.MainActivity
import com.everyonesoftware.audio.BuildConfig
import com.everyonesoftware.audio.PlaybackSnapshot
import com.everyonesoftware.audio.R
import com.everyonesoftware.audio.data.Credentials
import com.everyonesoftware.audio.data.ListeningSession
import com.everyonesoftware.audio.data.NavidromeClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The service, never an activity or ViewModel, owns the player and its audio focus. */
@UnstableApi
class PlaybackService : MediaLibraryService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // Final progress writes may finish after the service's observers and player have stopped.
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var graph: AudioGraph
    private lateinit var player: ExoPlayer
    private var librarySession: MediaLibrarySession? = null
    private var binding: QueueBinding? = null
    private var replacingQueue = false
    private var storageError: String? = null
    private lateinit var carLibrary: CarLibrary
    private var selectionGeneration = 0L
    private var artworkJob: Job? = null
    private var artworkLoader: SessionArtwork? = null
    private var artworkCredentials: Credentials? = null
    private var artworkTarget: Pair<QueueBinding, String>? = null
    private var artworkIndex = C.INDEX_UNSET
    private var history = PlaybackHistory()
    private var repeatedVisit = false
    private var publishedVisits: List<Int>? = null

    private data class QueueKey(val connectionId: String, val playlistId: String, val ids: List<String>, val queueId: String, val occurrences: List<Int>)
    private data class QueueBinding(val key: QueueKey, val credentials: Credentials)
    private data class Progress(val key: QueueKey, val index: Int, val positionMs: Long, val repeatMode: Int)

    private fun ListeningSession.key() = QueueKey(connectionId, playlistId, queue.map { it.id }, queueId, occurrences)
    private fun trusted(controller: MediaSession.ControllerInfo) =
        (controller.packageName == packageName && controller.uid == applicationInfo.uid) || controller.isTrusted

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build().apply {
            setSmallIcon(R.drawable.ic_audio_notification)
        })
        graph = (application as AudioApplication).graph
        carLibrary = CarLibrary(graph)
        // Owner tags never cross the session wire. Sources capture queue-specific credentials,
        // including when Media3 applies the asynchronous onSetMediaItems result itself.
        val defaultSources = DefaultMediaSourceFactory(this)
        val sources = object : MediaSource.Factory by defaultSources {
            override fun createMediaSource(mediaItem: MediaItem): MediaSource {
                val owner = mediaItem.localConfiguration?.tag as? QueueBinding
                    ?: throw IllegalArgumentException("Choose music from Music.")
                val client = NavidromeClient(owner.credentials, allowLocalHttp = BuildConfig.DEBUG, networkPolicy = graph.networkPolicy)
                val song = graph.session.value?.queue?.firstOrNull { client.streamUri(it.id) == mediaItem.localConfiguration?.uri.toString() }
                    ?: throw IllegalArgumentException("Choose music from the active queue.")
                return DefaultMediaSourceFactory(graph.songCache.factory(owner.credentials, song,
                    OkHttpDataSource.Factory(streamClient(owner, client)), writeThrough = false))
                    .createMediaSource(mediaItem)
            }
        }
        player = ExoPlayer.Builder(this).setMediaSourceFactory(sources).build().apply {
            setAudioAttributes(AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
            // The stored queue is already shuffled; a second shuffle would change its order.
            shuffleModeEnabled = false
            addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    if (!replacingQueue && reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) repeatedVisit = true
                }
                override fun onEvents(player: Player, events: Player.Events) {
                    if (replacingQueue) return
                    observeHistory()
                    publishSnapshot()
                    if (player.isPlaying && events.containsAny(Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                        val owner = binding
                        val song = graph.session.value?.queue?.getOrNull(player.currentMediaItemIndex)
                        if (owner != null && song != null && owner.credentials == graph.credentials.value) graph.songCache.played(owner.credentials, song)
                    }
                    if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED)) {
                        refreshArtwork()
                    }
                    if (events.containsAny(Player.EVENT_PLAY_WHEN_READY_CHANGED,
                            Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_POSITION_DISCONTINUITY,
                            Player.EVENT_REPEAT_MODE_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED)) {
                        persistProgress()
                    }
                }
            })
        }
        val callback = object : MediaLibrarySession.Callback {
            override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> =
                Futures.immediateFuture(LibraryResult.ofItem(carLibrary.root(), params))

            override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
                libraryFuture { LibraryResult.ofItemList(carLibrary.children(parentId, page, pageSize), params) }

            override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> =
                libraryFuture { LibraryResult.ofItem(carLibrary.item(mediaId), null) }

            override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> =
                libraryFuture {
                    session.notifySearchResultChanged(browser, query, carLibrary.searchCount(query), params)
                    LibraryResult.ofVoid()
                }

            override fun onGetSearchResult(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
                libraryFuture { LibraryResult.ofItemList(carLibrary.search(query, page, pageSize), params) }

            override fun onSetMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                val generation = ++selectionGeneration
                return future {
                    require(mediaItems.size == 1 && mediaItems.single().localConfiguration == null) { "Choose music from Music." }
                    val request = mediaItems.single()
                    require(request.requestMetadata.mediaUri == null) { "Choose music from Music." }
                    val saved = selectMedia(request.mediaId, generation, request.requestMetadata.searchQuery)
                    val owner = requireNotNull(binding)
                    MediaSession.MediaItemsWithStartPosition(playbackItems(saved, owner), saved.index, saved.positionMs)
                }
            }

            override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                if (customCommand.customAction !in setOf(CarLibrary.PLAY_COLLECTION, CarLibrary.SHUFFLE_COLLECTION, CarLibrary.SHUFFLE_PLAYLIST))
                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                val generation = ++selectionGeneration
                return future {
                    try {
                        val mediaId = requireNotNull(args.getString(MediaConstants.EXTRA_KEY_MEDIA_ID))
                        selectMedia(mediaId, generation, command = customCommand.customAction)
                        player.play()
                        SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply {
                            // Android Auto's browse-action protocol. Media3 1.8 forwards result
                            // extras to legacy browsers; navigation remains the host's decision.
                            putBoolean("androidx.media.utils.extras.KEY_CUSTOM_BROWSER_ACTION_RESULT_SHOW_PLAYING_ITEM", true)
                        })
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { SessionResult(SessionError.ERROR_BAD_VALUE) }
                }
            }

            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                if (!trusted(controller)) return MediaSession.ConnectionResult.reject()
                // Set goes through our ID resolver. Add/remove/reorder and arbitrary URLs remain
                // prohibited; controllers never own the queue or choose its stream references.
                val commands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                    .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
                    .remove(Player.COMMAND_SET_SHUFFLE_MODE)
                    // Music uses track skips, not podcast-style ten-second seek buttons.
                    // Keep in-track seeking available for the phone's progress slider.
                    .remove(Player.COMMAND_SEEK_BACK)
                    .remove(Player.COMMAND_SEEK_FORWARD)
                    .build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                        .add(SessionCommand(CarLibrary.SHUFFLE_PLAYLIST, Bundle.EMPTY))
                        .add(SessionCommand(CarLibrary.PLAY_COLLECTION, Bundle.EMPTY))
                        .add(SessionCommand(CarLibrary.SHUFFLE_COLLECTION, Bundle.EMPTY)).build())
                    .setAvailablePlayerCommands(commands).build()
            }

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onPlayerCommandRequest(session: MediaSession, controller: MediaSession.ControllerInfo, playerCommand: Int): Int {
                // setSession() can emit immediately before controller.play(). Reconcile synchronously
                // rather than letting the collector prepare a paused queue after the play command.
                reconcileQueue()
                return SessionResult.RESULT_SUCCESS
            }
        }
        val builder = MediaLibrarySession.Builder(this, player, callback)
        packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
            intent.action = MainActivity.ACTION_OPEN_PLAYER
            builder.setSessionActivity(PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        librarySession = builder.build()
        serviceScope.launch {
            try {
                graph.bootstrap()
                combine(graph.credentials, graph.session) { _, _ -> Unit }.collect { reconcileQueue() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                clearQueue()
                graph.playback.value = PlaybackSnapshot(error = "Cannot restore playback. Reopen your playlist.")
            }
        }
        serviceScope.launch {
            graph.songCache.state.map { it.settings.offline }.distinctUntilChanged().drop(1).collect { offline ->
                if (offline) cancelArtwork()
                val owner = binding ?: return@collect
                if (owner.credentials != graph.credentials.value) return@collect
                val index = player.currentMediaItemIndex
                val position = player.currentPosition.coerceAtLeast(0)
                val intent = player.playWhenReady
                player.stop()
                player.seekTo(index, position)
                player.prepare()
                player.playWhenReady = intent
                refreshArtwork()
            }
        }
        serviceScope.launch {
            graph.songCache.state.map { it.cachedSongs + it.downloadedSongs }.distinctUntilChanged().collect { complete ->
                val owner = binding ?: return@collect
                val song = graph.session.value?.queue?.getOrNull(player.currentMediaItemIndex) ?: return@collect
                if (owner.credentials == graph.credentials.value && song.id in complete && player.playerError != null) {
                    // prepare retains the latest playWhenReady, including an explicit pause.
                    player.prepare()
                }
            }
        }
        serviceScope.launch {
            graph.artworkCache.state.collect {
                val owner = binding ?: return@collect
                val song = graph.session.value?.queue?.getOrNull(player.currentMediaItemIndex) ?: return@collect
                val cover = song.coverArtId ?: return@collect
                if (player.currentMediaItem?.mediaMetadata?.artworkData == null &&
                    graph.artworkCache.cachedUri(owner.credentials, cover) != null) {
                    artworkTarget = null
                    refreshArtwork()
                }
            }
        }
        serviceScope.launch {
            while (true) {
                delay(1_000)
                publishSnapshot()
                persistProgress()
            }
        }
        serviceScope.launch {
            graph.playlists.state.filter { !it.refreshing }
                .map { catalog -> catalog.connectionId to catalog.playlists.map { Triple(it.id, it.name, it.songCount) } }
                .distinctUntilChanged().drop(1)
                .collect { (_, rows) -> librarySession?.notifyChildrenChanged(CarMediaIds.PLAYLISTS, rows.size, null) }
        }
    }

    // Legacy binding starts with a synthetic unknown-UID controller. onConnect validates the
    // actual caller before library or playback access; don't reject the initial binder lookup.
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = librarySession

    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        val job = serviceScope.launch {
            try { future.set(block()) }
            catch (cancelled: CancellationException) { future.cancel(false); throw cancelled }
            catch (_: Exception) { future.setException(IllegalArgumentException("This music is unavailable. Refresh Music and retry.")) }
        }
        future.addListener({ if (future.isCancelled) job.cancel() }, MoreExecutors.directExecutor())
        return future
    }

    private fun <T : Any> libraryFuture(block: suspend () -> LibraryResult<T>): ListenableFuture<LibraryResult<T>> = future<LibraryResult<T>> {
        try { return@future block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            val code = when {
                graph.credentials.value == null -> SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED
                error is IllegalArgumentException -> SessionError.ERROR_BAD_VALUE
                else -> SessionError.ERROR_IO
            }
            val message = if (graph.credentials.value == null) "Open Music on your phone and connect to Navidrome."
                else "Cannot load this music. Refresh the list or check the connection on your phone."
            return@future LibraryResult.ofError<T>(SessionError(code, message))
        }
    }

    private fun reconcileQueue() {
        val credentials = graph.credentials.value
        val saved = graph.session.value
        if (credentials == null || saved == null || saved.queue.isEmpty() ||
            saved.connectionId != credentials.connection.id) {
            clearQueue()
            return
        }
        val next = QueueBinding(saved.key(), credentials)
        if (next == binding) return // Progress/rating emissions must never replace or seek the queue.
        val previous = binding
        val keepPlaying = player.playWhenReady && (previous == null || previous.credentials == credentials)
        replacingQueue = true
        try {
            // Each queue captures its own credentials. A late old request cannot borrow the newly
            // selected account's authentication, even when both accounts share the same server.
            val mediaItems = playbackItems(saved, next)
            cancelArtwork()
            player.pause()
            player.stop()
            binding = next
            storageError = null
            player.shuffleModeEnabled = false
            player.repeatMode = saved.repeatMode.coerceIn(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ALL)
            player.setMediaItems(mediaItems, saved.index.coerceIn(saved.queue.indices), saved.positionMs.coerceAtLeast(0))
            player.prepare()
            // Cold restore is paused; an explicit play before a new queue preserves play intent.
            player.playWhenReady = keepPlaying
        } catch (_: Exception) {
            clearQueue()
            graph.playback.value = PlaybackSnapshot(error = "Cannot prepare playback. Reopen your playlist.")
        } finally {
            replacingQueue = false
            publishSnapshot()
        }
        observeHistory()
        refreshArtwork()
        librarySession?.notifyChildrenChanged(CarMediaIds.QUEUE, 3, null)
    }

    private fun cancelArtwork() {
        artworkJob?.cancel()
        artworkJob = null
        artworkTarget = null
        artworkIndex = C.INDEX_UNSET
    }

    private fun refreshArtwork() {
        val owner = binding ?: return
        val item = player.currentMediaItem ?: return
        if (graph.credentials.value != owner.credentials || graph.session.value?.key() != owner.key) return
        val target = owner to item.mediaId
        val index = player.currentMediaItemIndex
        val saved = graph.session.value
        if (saved != null) {
            val client = NavidromeClient(owner.credentials, allowLocalHttp = BuildConfig.DEBUG, networkPolicy = graph.networkPolicy)
            graph.songCache.schedule(owner.credentials, saved.queue, index,
                OkHttpDataSource.Factory(streamClient(owner, client)), queueIdentity = owner.key, repeatMode = player.repeatMode, uri = client::streamUri)
        }
        val coverId = graph.session.value?.queue?.getOrNull(index)?.coverArtId?.takeIf { it.isNotBlank() }
        if (artworkTarget == target) {
            // A controller can reapply this occurrence after the service already loaded it.
            val cached = coverId?.let { artworkLoader?.cached(it) }
            if (cached != null && (item.mediaMetadata.artworkData == null || item.mediaMetadata.artworkUri != cached.uri)) {
                publishArtwork(index, item, cached)
            }
            return
        }
        artworkJob?.cancel()
        artworkTarget = target // Set before metadata replacements can emit another player event.
        val previousIndex = artworkIndex
        artworkIndex = index
        // Keep image bytes only on the current item; other occurrences retain browse
        // capabilities rather than evictable current-artwork-store URIs.
        if (previousIndex in 0 until player.mediaItemCount && previousIndex != index) {
            val previous = player.getMediaItemAt(previousIndex)
            if (previous.mediaMetadata.artworkData != null || previous.mediaMetadata.artworkUri != null) {
                player.replaceMediaItem(previousIndex, previous.buildUpon().setMediaMetadata(
                    previous.mediaMetadata.buildUpon().setArtworkUri(graph.browseArtwork.register(owner.credentials,
                        saved?.queue?.getOrNull(previousIndex)?.coverArtId)?.let(Uri::parse))
                        .setArtworkData(null, MediaMetadata.PICTURE_TYPE_FRONT_COVER).build()).build())
            }
        }
        if (artworkCredentials != owner.credentials) {
            artworkLoader?.close()
            artworkCredentials = owner.credentials
            artworkLoader = SessionArtwork(NavidromeClient(owner.credentials, allowLocalHttp = BuildConfig.DEBUG, networkPolicy = graph.networkPolicy), owner.credentials, graph.artworkCache)
        }
        if (coverId == null) return
        val loader = requireNotNull(artworkLoader)
        artworkJob = serviceScope.launch {
            // A transient cover failure gets one retry; music preparation never awaits artwork.
            repeat(2) { attempt ->
                if (attempt > 0) delay(1_000)
                val artwork = try { loader.load(coverId) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
                if (artworkTarget != target || binding != owner || graph.credentials.value != owner.credentials ||
                    graph.session.value?.key() != owner.key || player.currentMediaItem?.mediaId != item.mediaId) return@launch
                if (artwork != null) {
                    val current = player.currentMediaItem ?: return@launch
                    publishArtwork(index, current, artwork)
                    return@launch
                }
            }
        }
    }

    private fun publishArtwork(index: Int, item: MediaItem, artwork: SessionArtworkImage) {
        player.replaceMediaItem(index, item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon()
            .setArtworkUri(artwork.uri).setArtworkData(artwork.thumbnail, MediaMetadata.PICTURE_TYPE_FRONT_COVER).build()).build())
    }

    private fun playbackItems(saved: ListeningSession, owner: QueueBinding): List<MediaItem> {
        val client = NavidromeClient(owner.credentials, allowLocalHttp = BuildConfig.DEBUG, networkPolicy = graph.networkPolicy)
        return saved.queue.mapIndexed { index, song ->
            CarLibrary.songItem(CarMediaIds.queued(saved.connectionId, saved.queueId, saved.occurrences[index]), song,
                graph.browseArtwork.register(owner.credentials, song.coverArtId)).buildUpon()
                .setUri(client.streamUri(song.id)).setTag(owner).build()
        }
    }

    private suspend fun selectMedia(mediaId: String, generation: Long, searchQuery: String? = null, command: String? = null): ListeningSession {
        val selected = if (searchQuery != null) carLibrary.resolveSearch(searchQuery) else carLibrary.resolve(mediaId, command)
        require(generation == selectionGeneration) { "A newer selection is already active." }
        graph.setSession(selected.session, selected.credentials, selected.previous)
        require(generation == selectionGeneration) { "A newer selection is already active." }
        reconcileQueue()
        val saved = requireNotNull(graph.session.value)
        val owner = requireNotNull(binding)
        require(owner.key == saved.key() && owner.credentials == selected.credentials)
        return saved
    }

    private fun streamClient(owner: QueueBinding, client: NavidromeClient): OkHttpClient =
        graph.networkPolicy.guard(OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false).cache(null).retryOnConnectionFailure(false)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            // A song can legitimately stream longer than an API request. Reads remain bounded.
            .callTimeout(0, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val unsigned = chain.request()
                try {
                    if (graph.credentials.value != owner.credentials || graph.session.value?.key() != owner.key) {
                        throw IOException("The active music account or queue changed.")
                    }
                    val signed = unsigned.newBuilder()
                        .url(client.authenticatedMediaUri(unsigned.url.toString()))
                        .header("Cache-Control", "no-store").build()
                    val response = chain.proceed(signed)
                    if (!response.isSuccessful) {
                        response.close()
                        throw IOException("The music server could not stream this song.")
                    }
                    // OkHttpDataSource.getUri() reads response.request.url. Restore the unsigned
                    // request so signed URLs cannot escape into session metadata, errors or events.
                    response.newBuilder().request(unsigned).build()
                } catch (_: Exception) {
                    // No credential-bearing transport exception causes may enter ExoPlayer logs.
                    throw IOException("Cannot stream this song. Check your connection and retry.")
                }
            }.build())

    private fun clearQueue() {
        cancelArtwork()
        artworkLoader?.close()
        artworkLoader = null
        artworkCredentials = null
        if (binding == null && player.mediaItemCount == 0) return
        val wasReplacing = replacingQueue
        replacingQueue = true
        try {
            player.pause()
            player.stop()
            player.clearMediaItems()
            binding = null
            history = PlaybackHistory()
            publishedVisits = null
            repeatedVisit = false
            storageError = null
        } finally {
            replacingQueue = wasReplacing
        }
        graph.playback.value = PlaybackSnapshot()
        librarySession?.notifyChildrenChanged(CarMediaIds.QUEUE, 0, null)
    }

    private fun publishSnapshot() {
        val owner = binding ?: return
        if (graph.credentials.value != owner.credentials || graph.session.value?.key() != owner.key) return
        graph.playback.value = PlaybackSnapshot(
            playing = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0,
            error = if (player.playerError != null) "Cannot play this song. Check your connection and retry." else storageError,
        )
    }

    private fun observeHistory() {
        val owner = binding ?: return
        val saved = graph.session.value ?: return
        if (replacingQueue || owner.credentials != graph.credentials.value || saved.key() != owner.key) return
        val visits = history.observe(saved, player.currentMediaItemIndex, player.isPlaying,
            repeat = repeatedVisit, ended = player.playbackState == Player.STATE_ENDED)
        repeatedVisit = false
        if (publishedVisits == visits && saved.played == visits) return
        publishedVisits = visits
        persistenceScope.launch {
            try {
                graph.updatePlayed(owner.credentials, owner.key.queueId, visits)
                if (binding == owner && graph.credentials.value == owner.credentials) {
                    librarySession?.notifyChildrenChanged(CarMediaIds.QUEUE, 3, null)
                    librarySession?.notifyChildrenChanged(CarMediaIds.played(saved.connectionId, saved.queueId), visits.size, null)
                    librarySession?.notifyChildrenChanged(CarMediaIds.upcoming(saved.connectionId, saved.queueId),
                        (saved.queue.size - player.currentMediaItemIndex - 1).coerceAtLeast(0), null)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { storageError = "Playback history could not be saved." }
        }
    }

    private fun captureProgress(): Progress? {
        val owner = binding ?: return null
        if (replacingQueue || player.mediaItemCount == 0 || graph.credentials.value != owner.credentials ||
            graph.session.value?.key() != owner.key) return null
        return Progress(owner.key, player.currentMediaItemIndex, player.currentPosition.coerceAtLeast(0), player.repeatMode)
    }

    private suspend fun saveProgress(progress: Progress) {
        if (graph.session.value?.key() == progress.key &&
            graph.credentials.value?.connection?.id == progress.key.connectionId) {
            val previousIndex = graph.session.value?.index
            graph.updateProgress(progress.index, progress.positionMs, progress.repeatMode, progress.key.connectionId,
                expectedQueueId = progress.key.queueId, expectedQueueIds = progress.key.ids, expectedOccurrences = progress.key.occurrences)
            val saved = graph.session.value
            if (previousIndex != progress.index && saved?.key() == progress.key) {
                librarySession?.notifyChildrenChanged(CarMediaIds.QUEUE, 3, null)
                librarySession?.notifyChildrenChanged(CarMediaIds.upcoming(saved.connectionId, saved.queueId),
                    (saved.queue.size - saved.index - 1).coerceAtLeast(0), null)
            }
        }
    }

    private fun persistProgress(progress: Progress? = captureProgress()) {
        if (progress == null) return
        persistenceScope.launch {
            try {
                saveProgress(progress)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                storageError = "Playback progress could not be saved."
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        persistProgress()
        // Active playback belongs to the foreground service, not the activity's task.
        if (!player.playWhenReady) stopSelf()
    }

    override fun onDestroy() {
        val finalProgress = captureProgress()
        cancelArtwork()
        artworkLoader?.close()
        artworkLoader = null
        artworkCredentials = null
        serviceScope.cancel()
        val pendingWrites = persistenceScope.coroutineContext[Job]?.children?.toList().orEmpty()
        // Finish prior writes and a final snapshot without blocking Android's main thread.
        persistenceScope.launch {
            try {
                pendingWrites.joinAll()
                if (finalProgress != null) saveProgress(finalProgress)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Best effort; process death falls back to the persisted one-second ticker.
            } finally {
                persistenceScope.cancel()
            }
        }
        librarySession?.release()
        librarySession = null
        binding?.let { owner ->
            if (graph.session.value?.key() == owner.key && graph.credentials.value == owner.credentials) {
                graph.playback.value = graph.playback.value.copy(playing = false)
            }
        }
        player.release()
        super.onDestroy()
    }
}
