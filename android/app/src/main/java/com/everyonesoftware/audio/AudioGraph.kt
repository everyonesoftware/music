package com.everyonesoftware.audio

import android.content.Context
import com.everyonesoftware.audio.data.AudioStore
import com.everyonesoftware.audio.data.Credentials
import com.everyonesoftware.audio.data.ListeningSession
import com.everyonesoftware.audio.data.NavidromeClient
import com.everyonesoftware.audio.data.PlaylistCatalog
import com.everyonesoftware.audio.playback.SessionRules
import com.everyonesoftware.audio.updates.UpdateManager
import com.everyonesoftware.audio.updates.UpdateClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

data class PlaybackSnapshot(val playing: Boolean = false, val positionMs: Long = 0, val durationMs: Long = 0, val error: String? = null)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioGraph(context: Context, updateClient: UpdateClient = UpdateClient()) {
    private val currentCredentials = MutableStateFlow<Credentials?>(null)
    internal val browseArtwork = com.everyonesoftware.audio.playback.BrowseArtworkRegistry("${BuildConfig.APPLICATION_ID}.artwork") { currentCredentials.value }
    val networkPolicy = com.everyonesoftware.audio.data.MusicNetworkPolicy()
    val artworkCache = com.everyonesoftware.audio.data.ArtworkCache(context, networkPolicy, { currentCredentials.value })
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    val songCache = com.everyonesoftware.audio.data.SongCache(context, networkPolicy = networkPolicy) { currentCredentials.value }
    val store = AudioStore(context) { id ->
        if (currentCredentials.value?.connection?.id == id) {
            songCache.accountChanged { currentCredentials.value = null }
            browseArtwork.clear()
            currentSession.value = null
            playback.value = PlaybackSnapshot()
            artworkCache.accountChanged()
        }
        songCache.removeConnection(id)
        artworkCache.removeConnection(id)
    }
    private val libraryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val updates = UpdateManager(context, libraryScope, updateClient)
    private val clientLock = Any()
    private var cachedClient: Pair<Credentials, NavidromeClient>? = null
    val playlists = PlaylistCatalog(libraryScope,
        summaries = { clientFor(it).playlists() },
        contents = { credentials, id -> clientFor(credentials).playlistContents(id) },
        cached = store::cachedPlaylists, save = store::cachePlaylists)
    private val currentSession = MutableStateFlow<ListeningSession?>(null)
    val credentials = currentCredentials.asStateFlow()
    val session = currentSession.asStateFlow()
    val playback = MutableStateFlow(PlaybackSnapshot())
    private val lock = Mutex()
    private var initialized = false
    init {
        libraryScope.launch(Dispatchers.IO) {
            combine(credentials, songCache.state) { account, _ ->
                Triple(account, account?.let { runCatching { songCache.pinnedSongs(it).mapNotNull { song -> song.coverArtId } }.getOrDefault(emptyList()) }.orEmpty(),
                    account?.let { runCatching { songCache.collections(it) }.getOrDefault(emptyList()) }.orEmpty())
            }.distinctUntilChanged().collect { (account, covers, collections) ->
                if (account != null) {
                    val owners = mutableMapOf("downloaded-songs" to covers)
                    collections.forEach { collection ->
                        owners[collection.owner] = (listOfNotNull(collection.coverArtId) + collection.songs.mapNotNull { it.coverArtId }).distinct()
                    }
                    artworkCache.syncOwners(account, owners)
                }
            }
        }
    }
    suspend fun bootstrap() = lock.withLock {
        if (initialized) return@withLock
        val connections = store.connections()
        val selected = store.selectedConnectionId()?.takeIf { id -> connections.any { it.id == id } }
            ?: connections.firstOrNull()?.id
        if (selected != null) activateLocked(selected)
        initialized = true
    }
    suspend fun activate(id: String) = lock.withLock {
        activateLocked(id)
        initialized = true
    }
    private suspend fun activateLocked(id: String) {
        val credentials = store.credentials(id) ?: error("Reconnect this Navidrome account to continue.")
        val restored = store.session(id)?.takeIf { it.queue.isNotEmpty() }?.let(SessionRules::restore)
        store.selectConnection(id)
        playlists.activate(credentials)
        if (currentCredentials.value != credentials) {
            browseArtwork.clear()
            artworkCache.accountChanged()
            songCache.accountChanged { currentCredentials.value = credentials }
        }
        currentSession.value = restored
        songCache.download(credentials, emptyList())
        playback.value = PlaybackSnapshot(positionMs = restored?.positionMs ?: 0)
    }
    fun client(): NavidromeClient = clientFor(requireNotNull(credentials.value) { "Connect to Navidrome first." })
    internal fun clientFor(credentials: Credentials): NavidromeClient = synchronized(clientLock) {
        cachedClient?.takeIf { it.first == credentials }?.second
            ?: NavidromeClient(credentials, allowLocalHttp = BuildConfig.DEBUG, networkPolicy = networkPolicy).also { cachedClient = credentials to it }
    }
    suspend fun setSession(value: ListeningSession, expectedCredentials: Credentials? = null, expectedSession: ListeningSession? = null) = lock.withLock {
        require(value.connectionId == credentials.value?.connection?.id) { "The active connection changed. Choose the playlist again." }
        if (expectedCredentials != null) {
            require(currentCredentials.value == expectedCredentials && currentSession.value?.queueId == expectedSession?.queueId &&
                currentSession.value?.occurrences == expectedSession?.occurrences &&
                currentSession.value?.queue?.map { it.id } == expectedSession?.queue?.map { it.id }) {
                "The listening session changed. Choose the music again."
            }
        }
        // A queue jump changes position only, not a rating/repeat update committed during resolution.
        val current = currentSession.value
        val merged = if (expectedCredentials != null && current != null && current.queueId == value.queueId)
            current.copy(index = value.index, positionMs = value.positionMs)
        else if (current != null && current.queueId == value.queueId)
            value.copy(played = current.played) // In-session shuffle cannot erase a newer service visit.
        else value
        val safe = SessionRules.restore(merged)
        store.saveSession(safe)
        currentSession.value = safe
    }
    /** Resolve against the latest locked session and service position, never a captured UI copy. */
    internal suspend fun shuffleUpNext(expectedCredentials: Credentials, expected: ListeningSession,
        isPlaying: () -> Boolean = { false }, checkpoint: () -> Triple<Int, Long, Int>?,
    ): Boolean = lock.withLock {
        val current = currentSession.value ?: return@withLock false
        if (currentCredentials.value != expectedCredentials || current.connectionId != expected.connectionId ||
            current.queueId != expected.queueId || current.occurrences != expected.occurrences ||
            current.queue.map { it.id } != expected.queue.map { it.id }) return@withLock false
        var attemptedWrite = false
        var lastLive = Triple(current.index, current.positionMs, current.repeatMode)
        fun sample(): Triple<Int, Long, Int>? = checkpoint()?.takeIf { it.first in current.queue.indices }
            ?.also { lastLive = it }
        suspend fun repair() = withContext(NonCancellable) {
            // Keep the graph mutex until the untouched order AND latest live progress are
            // durable. Room may commit even when its cancelled continuation never resumes.
            var live = sample() ?: lastLive
            while (true) {
                val restored = SessionRules.progress(current, live.first, live.second, live.third)
                store.saveSession(restored)
                val after = sample()
                if (after != null && (after.first != live.first || after.third != live.third ||
                        (!isPlaying() && after.second != live.second))) {
                    live = after
                    continue
                }
                currentSession.value = restored
                break
            }
        }
        try {
            while (true) {
                val live = sample()
                if (live == null) {
                    if (attemptedWrite) repair()
                    return@withLock false
                }
                val latest = SessionRules.progress(current, live.first, live.second, live.third)
                val shuffled = SessionRules.shuffleUpNext(latest)
                if (shuffled == latest) {
                    if (attemptedWrite || latest != current) {
                        attemptedWrite = true
                        repair()
                    }
                    return@withLock false
                }
                // Mark before suspension: a failed/cancelled save may already have committed.
                attemptedWrite = true
                store.saveSession(shuffled)
                currentCoroutineContext().ensureActive()
                // Retry from the untouched original order if Next/seek moved during storage.
                val after = sample()
                if (after == null) {
                    repair()
                    return@withLock false
                }
                if (after.first != live.first || after.third != live.third || (!isPlaying() && after.second != live.second)) continue
                currentSession.value = shuffled.copy(positionMs = after.second, repeatMode = after.third)
                return@withLock true
            }
            @Suppress("UNREACHABLE_CODE") false
        } catch (failure: Throwable) {
            if (attemptedWrite) {
                try { repair() }
                catch (cleanupFailure: Throwable) {
                    // Do not claim a successful rollback or hide the original failure.
                    failure.addSuppressed(cleanupFailure)
                }
            }
            throw failure
        }
    }

    suspend fun updateProgress(index: Int, positionMs: Long, repeatMode: Int, expectedConnectionId: String? = null, expectedQueueId: String? = null, expectedQueueIds: List<String>? = null, expectedOccurrences: List<Int>? = null) = lock.withLock {
        val current = currentSession.value ?: return@withLock
        if (expectedConnectionId != null && current.connectionId != expectedConnectionId) return@withLock
        if (expectedQueueId != null && current.queueId != expectedQueueId) return@withLock
        if (expectedQueueIds != null && current.queue.map { it.id } != expectedQueueIds) return@withLock
        if (expectedOccurrences != null && current.occurrences != expectedOccurrences) return@withLock
        val next = SessionRules.progress(current, index, positionMs, repeatMode)
        store.saveSession(next)
        currentSession.value = next
    }
    suspend fun applyRating(connectionId: String, songId: String, rating: Int) = lock.withLock {
        require(rating in 0..5)
        val current = if (currentSession.value?.connectionId == connectionId) currentSession.value else store.session(connectionId)
        if (current == null) return@withLock
        val next = current.copy(queue = current.queue.map { if (it.id == songId) it.copy(userRating = rating) else it })
        store.saveSession(next)
        if (currentSession.value?.connectionId == connectionId) currentSession.value = next
    }

    /** Only PlaybackService supplies started-visit evidence; merge with latest progress/rating. */
    internal suspend fun updatePlayed(expectedCredentials: Credentials, queueId: String, played: List<Int>) = lock.withLock {
        val current = currentSession.value ?: return@withLock
        if (currentCredentials.value != expectedCredentials || current.connectionId != expectedCredentials.connection.id || current.queueId != queueId) return@withLock
        val next = SessionRules.restore(current.copy(played = played))
        if (next == current) return@withLock
        store.saveSession(next)
        currentSession.value = next
    }
}
