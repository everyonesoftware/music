package com.everyonesoftware.audio.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Counts describe browsing, never the persisted listening queue. No credentials enter observable state. */
data class PlaylistCatalogState(
    val connectionId: String? = null,
    val playlists: List<Playlist> = emptyList(),
    val refreshing: Boolean = false,
    val staleIds: Set<String> = emptySet(),
    val error: String? = null,
    val listError: String? = null,
    internal val pendingDeletionTargets: Map<String, Playlist> = emptyMap(),
    val deletedIds: Set<String> = emptySet(),
) {
    val pendingDeletionIds: Set<String> get() = pendingDeletionTargets.keys

    // A refresh may omit the target before the write reply arrives. Keep the confirmed
    // target available for phone recovery without treating it as a live server listing.
    internal val playlistsForPhone: List<Playlist> get() = if (pendingDeletionTargets.isEmpty()) playlists else
        playlists.filterNot { it.id in pendingDeletionTargets } + pendingDeletionTargets.values
}

class PlaylistCatalog(
    private val scope: CoroutineScope,
    private val summaries: suspend (Credentials) -> List<Playlist>,
    private val contents: suspend (Credentials, String) -> PlaylistContents,
    private val cached: suspend (String) -> List<Playlist>,
    private val save: suspend (String, List<Playlist>) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val ratingDelayMs: Long = 6_000L,
) {
    private val lock = Mutex()
    private val current = MutableStateFlow(PlaylistCatalogState())
    val state = current.asStateFlow()
    private var active: Credentials? = null
    private var revision = 0L
    private var ratingRevision = 0L
    private var refreshJob: Job? = null
    private var ratingJob: Job? = null
    private var saveJob: Job? = null
    private var summaryReady: CompletableDeferred<Boolean>? = null
    private var lastSummariesAt: Long? = null
    private val ratingDeadlines = mutableMapOf<String, Long>()
    private val versions = mutableMapOf<String, Long>()

    suspend fun activate(credentials: Credentials?) = lock.withLock {
        revision++
        ratingRevision++
        refreshJob?.cancel()
        ratingJob?.cancel()
        saveJob?.cancel()
        refreshJob = null
        ratingJob = null
        saveJob = null
        lastSummariesAt = null
        active = credentials
        ratingDeadlines.clear()
        versions.clear()
        val playlists = credentials?.let { cached(it.connection.id) }.orEmpty()
        current.value = PlaylistCatalogState(credentials?.connection?.id, playlists, staleIds = playlists.map { it.id }.toSet())
    }

    /** Coalesce phone/car refreshes. Network work is independent of playback/UI operation jobs. */
    suspend fun refresh(): Job = lock.withLock { startRefreshLocked() }

    /** A write must not coalesce into a summary/count read started before the server changed. */
    suspend fun playlistWritten(credentials: Credentials, id: String? = null) = lock.withLock {
        if (active != credentials) return@withLock
        refreshJob?.cancel()
        refreshJob = null
        saveJob?.cancel()
        lastSummariesAt = null
        if (id != null) versions[id] = (versions[id] ?: 0L) + 1
        startRefreshLocked()
        Unit
    }

    internal suspend fun deletionPending(credentials: Credentials, target: Playlist) = lock.withLock {
        val id = target.id
        if (active == credentials && id !in current.value.deletedIds) {
            versions[id] = (versions[id] ?: 0L) + 1
            current.value = current.value.copy(pendingDeletionTargets = current.value.pendingDeletionTargets + (id to target))
        }
    }

    /** Publish the exact-ID result before starting a best-effort catalog refresh. */
    internal suspend fun deletionResolved(credentials: Credentials, id: String, present: Playlist?, online: Boolean) = lock.withLock {
        if (active != credentials) return@withLock
        require(present == null || present.id == id)
        val absent = present == null
        ++revision
        refreshJob?.cancel()
        refreshJob = null
        saveJob?.cancel()
        saveJob = null
        lastSummariesAt = null
        versions[id] = (versions[id] ?: 0L) + 1
        val state = current.value
        val playlists = when {
            present == null -> state.playlists.filterNot { it.id == id }
            state.playlists.any { it.id == id } -> state.playlists.map { if (it.id == id) present else it }
            else -> state.playlists + present
        }
        current.value = state.copy(refreshing = false, pendingDeletionTargets = state.pendingDeletionTargets - id,
            playlists = playlists, staleIds = if (absent) state.staleIds - id else state.staleIds + id,
            deletedIds = if (absent) state.deletedIds + id else state.deletedIds)
        if (absent) ratingDeadlines.remove(id)
        saveSnapshotLocked(credentials)
        if (online) startRefreshLocked()
    }

    private fun PlaylistCatalogState.forBrowse() = copy(playlists = playlists.filterNot { it.id in pendingDeletionIds })

    private fun requireLivePlaylist(id: String) {
        if (id in current.value.pendingDeletionIds || id in current.value.deletedIds) {
            throw NavidromeException("This playlist is no longer available online. Refresh playlists.")
        }
    }

    /** Car browse/search awaits only the summaries, never unrelated detail requests. */
    suspend fun listing(credentials: Credentials, reconcileCounts: Boolean = true): PlaylistCatalogState {
        while (true) {
            val ready = lock.withLock {
                require(active == credentials) { "The music account changed. Refresh playlists." }
                if (current.value.listError == null && lastSummariesAt?.let { now() - it < 30_000L } == true) {
                    if (reconcileCounts && current.value.staleIds.isNotEmpty()) startRefreshLocked()
                    return current.value.forBrowse()
                }
                startRefreshLocked(if (reconcileCounts) null else emptySet())
                requireNotNull(summaryReady)
            }
            val available = ready.await()
            lock.withLock {
                require(active == credentials) { "The music account changed. Refresh playlists." }
                if (available || current.value.listError != null) return current.value.forBrowse()
                // A rating invalidated a pending summary request. Retry for this same account.
            }
        }
    }

    private fun startRefreshLocked(onlyIds: Set<String>? = null): Job {
        refreshJob?.takeIf { it.isActive }?.let { return it }
        val credentials = active
        val ticket = ++revision
        val ready = CompletableDeferred<Boolean>().also { summaryReady = it }
        current.value = current.value.copy(refreshing = credentials != null, error = null, listError = null)
        return scope.launch(start = CoroutineStart.LAZY) {
            if (credentials != null) refresh(credentials, ticket, onlyIds, ready)
        }.also { job ->
            refreshJob = job
            job.invokeOnCompletion { ready.complete(false) }
            job.start()
        }
    }

    /** Debounce rapid ratings; an early browse must not consume the deferred post-rating refresh. */
    suspend fun ratingChanged(credentials: Credentials) = lock.withLock {
        if (active != credentials) return@withLock
        revision++
        refreshJob?.cancel()
        refreshJob = null
        ratingJob?.cancel()
        val fallbackDeadline = now() + ratingDelayMs
        ratingDeadlines.clear()
        current.value.playlists.forEach { playlist ->
            ratingDeadlines[playlist.id] = maxOf(fallbackDeadline, (playlist.validUntilMs ?: 0L) + 100L)
        }
        current.value = current.value.copy(refreshing = false, staleIds = current.value.playlists.map { it.id }.toSet())
        val ratingTicket = ++ratingRevision
        ratingJob = scope.launch {
            if (lock.withLock { ratingDeadlines.isEmpty() }) {
                delay(ratingDelayMs)
                lock.withLock {
                    if (active == credentials && ratingTicket == ratingRevision) startRefreshLocked()
                }
                return@launch
            }
            // Finite, per-playlist retries at the pre-mutation validity deadlines, not a polling loop.
            while (true) {
                val deadline = lock.withLock {
                    if (active != credentials || ratingTicket != ratingRevision) return@launch
                    ratingDeadlines.values.minOrNull()
                } ?: break
                delay((deadline - now()).coerceAtLeast(0L))
                val due = lock.withLock { ratingDeadlines.filterValues { it <= now() }.keys.toSet() }
                val job = lock.withLock {
                    if (active != credentials || ratingTicket != ratingRevision) return@launch
                    // A pre-deadline response must not consume the post-rating refresh.
                    revision++
                    refreshJob?.cancel()
                    refreshJob = null
                    startRefreshLocked(due)
                }
                job.join()
                lock.withLock {
                    if (ratingTicket == ratingRevision) due.forEach { ratingDeadlines.remove(it) }
                }
            }
        }
    }

    /** Capture ownership BEFORE network work; older inspection/playback responses cannot repair newer counts. */
    suspend fun fetchContents(credentials: Credentials, id: String): PlaylistContents {
        val token = lock.withLock {
            require(active == credentials) { "The music account changed. Refresh playlists." }
            requireLivePlaylist(id)
            val version = (versions[id] ?: 0L) + 1
            versions[id] = version
            ratingRevision to version
        }
        val result = contents(credentials, id)
        lock.withLock {
            require(active == credentials) { "The music account changed. Refresh playlists." }
            requireLivePlaylist(id)
            if (active == credentials && token.first == ratingRevision && token.second == versions[id]) {
                publishCount(id, result.songs.size, result.validUntilMs)
                if (!current.value.refreshing) scheduleSaveLocked(credentials)
            }
        }
        return result
    }

    private fun publishCount(id: String, count: Int, validUntilMs: Long? = null) {
        val previous = current.value
        val playlists = previous.playlists.map { if (it.id == id) it.copy(songCount = count, validUntilMs = validUntilMs ?: it.validUntilMs) else it }
        current.value = previous.copy(playlists = playlists,
            staleIds = if (now() >= (ratingDeadlines[id] ?: 0L)) previous.staleIds - id else previous.staleIds + id)
    }

    private fun scheduleSaveLocked(credentials: Credentials) {
        saveJob?.cancel()
        val epoch = ratingRevision
        saveJob = scope.launch {
            delay(250L)
            lock.withLock {
                if (active == credentials && epoch == ratingRevision) saveSnapshotLocked(credentials)
            }
        }
    }

    private suspend fun saveSnapshotLocked(credentials: Credentials) {
        try { save(credentials.connection.id, current.value.playlists) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { current.value = current.value.copy(error = "Could not save playlist counts. Refresh to retry.") }
    }

    private suspend fun refresh(credentials: Credentials, ticket: Long, onlyIds: Set<String>?, ready: CompletableDeferred<Boolean>) {
        try {
            val listed = summaries(credentials)
            val playlists = lock.withLock {
                if (active != credentials || ticket != revision) return
                val old = current.value.playlists.associateBy { it.id }
                // A stale summary must not overwrite a previously reconciled count, even on partial failure.
                val listedLive = listed.filterNot { it.id in current.value.deletedIds }
                val merged = listedLive.map { it.copy(songCount = old[it.id]?.songCount ?: it.songCount,
                    validUntilMs = it.validUntilMs ?: old[it.id]?.validUntilMs) }
                val ids = merged.map { it.id }.toSet()
                current.value = current.value.copy(playlists = merged,
                    staleIds = if (onlyIds == null) ids else (current.value.staleIds + onlyIds + (ids - old.keys)).intersect(ids))
                lastSummariesAt = now()
                ready.complete(true)
                merged.filter { it.id !in current.value.pendingDeletionIds && (onlyIds == null || it.id in onlyIds) }
            }
            // A fixed worker pool bounds both requests and coroutine count for large catalogs.
            val pending = playlists.iterator()
            val pendingLock = Mutex()
            coroutineScope {
                List(3) {
                    launch {
                        while (true) {
                            val playlist = pendingLock.withLock { if (pending.hasNext()) pending.next() else null } ?: break
                            try {
                                fetchContents(credentials, playlist.id)
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) {
                                lock.withLock {
                                    if (active == credentials && ticket == revision) current.value = current.value.copy(
                                        error = "Some playlist counts could not be refreshed. Showing last known counts.")
                                }
                            }
                        }
                    }
                }.joinAll()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            lock.withLock {
                if (active == credentials && ticket == revision) current.value = current.value.copy(
                    staleIds = current.value.playlists.map { it.id }.toSet(),
                    error = "Could not refresh playlists. Showing cached playlists. Retry when connected.",
                    listError = "Could not refresh playlists. Retry when connected.")
            }
        } finally {
            ready.complete(false)
            lock.withLock {
                if (active == credentials && ticket == revision) {
                    saveJob?.cancel()
                    saveJob = null
                    saveSnapshotLocked(credentials)
                    current.value = current.value.copy(refreshing = false)
                }
            }
        }
    }
}
