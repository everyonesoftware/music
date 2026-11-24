package com.everyonesoftware.audio.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.asStateFlow

data class SongCacheState(val settings: OfflineSettings = OfflineSettings(),
    val cacheBytes: Long = 0, val downloadBytes: Long = 0,
    val cachedSongs: Set<String> = emptySet(), val downloadedSongs: Set<String> = emptySet(),
    val songs: List<Song> = emptyList(), val error: String? = null,
    val pendingDownloads: List<Song> = emptyList(), val recentSongs: List<Song> = emptyList(),
    val collections: List<OfflineCollection> = emptyList(), val explicitDownloads: Set<String> = emptySet(),
    val owner: Connection? = null) {
    internal fun forConnection(connection: Connection?): SongCacheState =
        if (connection != null && owner == connection) this else SongCacheState(settings = settings)
}

/** One persistent, private Media3 span store shared by foreground playback and whole-song fetches. */
@UnstableApi
class SongCache(context: Context, directory: File = File(context.filesDir, "offline-music"),
    private val networkPolicy: MusicNetworkPolicy = MusicNetworkPolicy(),
    private val activeCredentials: () -> Credentials?) {
    private val index = OfflineIndex(File(directory, "metadata"))
    private val cacheDirectory = File(directory, "audio").canonicalFile
    private val cache = acquire(context, cacheDirectory)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val mutable = MutableStateFlow(SongCacheState(settings = index.settings))
    val state = mutable.asStateFlow()
    private val removed = ConcurrentHashMap.newKeySet<String>()
    private val blocked = ConcurrentHashMap.newKeySet<String>()
    private val openSources = ConcurrentHashMap.newKeySet<GuardedSource>()
    private val fetching = AtomicReference<DataSource?>(null)
    private val maintenance = Any()
    private val publication = Any()
    @Volatile private var generation = 0L
    @Volatile private var closed = false
    @Volatile private var target: Target? = null
    @Volatile private var worker: Job? = null
    private var automaticEnabled = true
    private data class Target(val credentials: Credentials, val queue: List<Song>, val index: Int,
        val upstream: DataSource.Factory, val uri: (String) -> String, val queueIdentity: Any, val repeatMode: Int)
    private data class PublicationOwner(val epoch: Long, val credentials: Credentials?)

    private fun publicationOwner() = synchronized(publication) { PublicationOwner(generation, activeCredentials()) }

    // Called under publication so invalidation and the final error write cannot race.
    private fun ownsPublication(owner: PublicationOwner) = !closed && owner.epoch == generation &&
        owner.credentials == activeCredentials()

    private fun reportError(owner: PublicationOwner, message: String, stillRelevant: () -> Boolean = { true }) {
        synchronized(publication) {
            if (ownsPublication(owner) && owner.credentials?.connection?.id !in removed && stillRelevant()) {
                mutable.update { it.copy(error = message) }
            }
        }
    }

    private fun launchCacheWork(
        owner: PublicationOwner = publicationOwner(),
        stillRelevant: () -> Boolean = { true },
        work: suspend CoroutineScope.() -> Unit,
    ): Job = scope.launch {
        try { work() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            reportError(owner, "Offline storage is unavailable. Free space and retry.") { isActive && stillRelevant() }
        }
    }

    private val listener = object : Cache.Listener {
        override fun onSpanAdded(cache: Cache, span: CacheSpan) {
            // Tombstones stop in-flight writes from resurrecting a removed account after purge.
            if (span.key in blocked) cache.removeSpan(span)
            launchCacheWork { synchronized(maintenance) { trim(); publish() } }
        }
        override fun onSpanRemoved(cache: Cache, span: CacheSpan) { launchCacheWork { synchronized(maintenance) { publish() } } }
        override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) = Unit
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { restart() }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { restart() }
        override fun onLost(network: Network) { restart() }
    }
    init {
        networkPolicy.offline = index.settings.offline
        val known = index.entries().map { it.key }.toSet()
        (cache.keys - known).forEach(cache::removeResource)
        index.entries().forEach { cache.addListener(it.key, listener) }
        launchCacheWork { synchronized(maintenance) { trim(); publish() } }
        manager?.registerDefaultNetworkCallback(networkCallback)
    }
    fun complete(credentials: Credentials, songId: String): Boolean = completeKey(OfflineIndex.key(credentials.connection, songId))
    private fun completeKey(key: String): Boolean {
        if (key in blocked) return false
        val spans = cache.getCachedSpans(key)
        val damaged = spans.filter { it.file?.length() != it.length }
        if (damaged.isNotEmpty()) { damaged.forEach(cache::removeSpan); return false }
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        return length > 0 && cache.isCached(key, 0, length)
    }
    fun factory(credentials: Credentials, song: Song, upstream: DataSource.Factory, background: Boolean = false,
        writeThrough: Boolean = true): DataSource.Factory =
        sourceFactory(credentials, song, upstream, background, writeThrough) { !background || !networkPolicy.offline }

    private fun sourceFactory(credentials: Credentials, song: Song, upstream: DataSource.Factory, background: Boolean,
        writeThrough: Boolean, stillRelevant: () -> Boolean): DataSource.Factory {
        val key = OfflineIndex.key(credentials.connection, song.id)
        val epoch = generation
        return DataSource.Factory {
            checkOwner(credentials, key, epoch)
            GuardedSource(credentials, song, key, epoch, background, stillRelevant) {
                val settings = index.settings
                val pinned = index.entries().any { it.key == key && it.pinned }
                CacheDataSource.Factory().setCache(cache).setCacheKeyFactory { key }
                    .setUpstreamDataSourceFactory(if (settings.offline) null else upstream)
                    .setCacheWriteDataSinkFactory(if (writeThrough && (settings.autoSave || pinned))
                        CacheDataSink.Factory().setCache(cache).setFragmentSize(2_097_152) else null)
                    .setFlags(if (background) CacheDataSource.FLAG_BLOCK_ON_CACHE else 0)
                    .createDataSource()
            }
        }
    }
    private fun checkOwner(credentials: Credentials, key: String, epoch: Long) {
        if (closed || epoch != generation || credentials != activeCredentials() || credentials.connection.id in removed || key in blocked)
            throw IOException("The active music account changed.")
    }
    private inner class GuardedSource(val credentials: Credentials, private val song: Song,
        val key: String, val epoch: Long, val background: Boolean, private val stillRelevant: () -> Boolean,
        private val createDelegate: () -> DataSource) : DataSource {
        private val sourceLock = Any()
        private val listeners = mutableListOf<TransferListener>()
        private var delegate: DataSource? = null
        override fun addTransferListener(transferListener: TransferListener) = synchronized(sourceLock) {
            listeners.add(transferListener)
            delegate?.addTransferListener(transferListener)
            Unit
        }
        override fun open(dataSpec: DataSpec): Long {
            // Never hold the source lock while acquiring maintenance: removal takes the reverse
            // path to close live sources after tombstoning their account.
            synchronized(maintenance) {
                checkOwner(credentials, key, epoch)
                try { index.remember(key, credentials.connection.id, song) }
                catch (_: Exception) {
                    reportError(PublicationOwner(epoch, credentials), "Offline metadata could not be saved. Existing saved audio can still play.", stillRelevant)
                }
                cache.addListener(key, listener)
            }
            return synchronized(sourceLock) {
                checkOwner(credentials, key, epoch)
                if (index.settings.offline && !completeKey(key)) throw IOException("This song is not completely saved for offline playback.")
                check(delegate == null) { "Music source is already open." }
                val next = createDelegate()
                listeners.forEach(next::addTransferListener)
                delegate = next
                openSources.add(this)
                try {
                    next.open(dataSpec.buildUpon().setKey(key).setFlags(dataSpec.flags or DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION).build())
                        .also { checkOwner(credentials, key, epoch) }
                } catch (_: Exception) { close(); throw IOException("Cannot load this song. Connect and retry.") }
            }
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = synchronized(sourceLock) {
            checkOwner(credentials, key, epoch)
            val current = delegate ?: throw IOException("Music source is not open.")
            try { current.read(buffer, offset, length).also { checkOwner(credentials, key, epoch) } }
            catch (_: Exception) { throw IOException("Cannot read this song. Connect and retry.") }
        }
        override fun getUri(): Uri? = synchronized(sourceLock) { delegate?.uri }
        override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()
        override fun close() = synchronized(sourceLock) {
            // Media3 reuses a DataSource after close for seeking/replay. Only this open is
            // finished; the next open builds a fresh delegate using the current offline policy.
            val current = delegate
            delegate = null
            try { current?.close() } finally {
                openSources.remove(this)
                launchCacheWork(PublicationOwner(epoch, credentials), stillRelevant) { synchronized(maintenance) { trim(); publish() } }
            }
            Unit
        }
    }
    @Synchronized fun schedule(credentials: Credentials, queue: List<Song>, index: Int, upstream: DataSource.Factory, queueIdentity: Any = "", repeatMode: Int = 0, uri: (String) -> String) {
        if (credentials != activeCredentials() || credentials.connection.id in removed) return
        val previous = target
        if (previous?.credentials == credentials && previous.queue == queue && previous.index == index && previous.queueIdentity == queueIdentity && previous.repeatMode == repeatMode) return
        synchronized(publication) { target = Target(credentials, queue, index, upstream, uri, queueIdentity, repeatMode) }
        restart()
    }
    @Synchronized internal fun accountChanged(changeCredentials: () -> Unit = {}) {
        synchronized(publication) {
            generation++
            mutable.update { SongCacheState(settings = it.settings) }
            changeCredentials()
            target = null
            worker?.cancel()
        }
        fetching.getAndSet(null)?.let { source -> scope.launch { runCatching { source.close() } } }
        launchCacheWork { synchronized(maintenance) { publish() } }
    }
    private fun unmetered(): Boolean = manager?.isActiveNetworkMetered == false
    @Synchronized private fun restart(includeAutomatic: Boolean = true) {
        if (closed) return
        automaticEnabled = includeAutomatic
        synchronized(publication) { worker?.cancel() }
        fetching.getAndSet(null)?.let { source -> scope.launch { runCatching { source.close() } } }
        val selected = target ?: return
        val owner = PublicationOwner(generation, selected.credentials)
        val fetchRelevant = { selected === target && !networkPolicy.offline }
        worker = launchCacheWork(owner, fetchRelevant) {
            // Retry failed partials on a bounded interval, without issuing player commands.
            while (isActive && selected == target && selected.credentials == activeCredentials()) {
                val settings = index.settings
                if (!settings.offline) {
                    val allowBackground = settings.mobilePrefetch || unmetered()
                    val automatic = if (!includeAutomatic || !settings.autoSave) emptyList() else OfflinePlan.songs(selected.queue, selected.index,
                        if (allowBackground) settings.prefetch else 0,
                        index.entries().filter { allowBackground && !completeKey(it.key) && bytes(it.key) > 0 && it.key == OfflineIndex.key(selected.credentials.connection, it.song.id) }, selected.credentials.connection.id, selected.repeatMode)
                    val pinned = index.entries().filter { it.connectionId == selected.credentials.connection.id && it.pinned && it.key == OfflineIndex.key(selected.credentials.connection, it.song.id) }.map { it.song }
                    for (song in (automatic + pinned).distinctBy { it.id }) {
                        ensureActive()
                        if (complete(selected.credentials, song.id)) continue
                        val source = try {
                            sourceFactory(selected.credentials, song, selected.upstream, background = true, writeThrough = true) {
                                isActive && fetchRelevant()
                            }.createDataSource()
                        }
                        catch (_: Exception) { break }
                        fetching.set(source)
                        try {
                            ensureActive()
                            val resolved = source.open(DataSpec.Builder().setUri(selected.uri(song.id)).build())
                            val buffer = ByteArray(65_536)
                            var received = 0L
                            while (true) {
                                ensureActive()
                                if (selected != target) throw CancellationException("Music selection changed.")
                                val count = source.read(buffer, 0, buffer.size)
                                if (count == C.RESULT_END_OF_INPUT) break
                                received += count
                            }
                            ensureActive()
                            synchronized(maintenance) {
                                ensureActive()
                                if (selected != target || selected.credentials != activeCredentials() || selected.credentials.connection.id in removed)
                                    throw CancellationException("Music account changed.")
                                val key = OfflineIndex.key(selected.credentials.connection, song.id)
                                val mutations = androidx.media3.datasource.cache.ContentMetadataMutations()
                                // A cancelled zero-byte writer can remove the cache key while a
                                // BLOCK_ON_CACHE reader already holds its resolved length. Persist
                                // the length again only after a genuine whole-resource EOF.
                                if (resolved != C.LENGTH_UNSET.toLong() && received != resolved) {
                                    androidx.media3.datasource.cache.ContentMetadataMutations.setContentLength(mutations, resolved)
                                    cache.applyContentMetadataMutations(key, mutations)
                                    throw IOException("The song fetch was incomplete.")
                                }
                                androidx.media3.datasource.cache.ContentMetadataMutations.setContentLength(mutations, received)
                                cache.applyContentMetadataMutations(key, mutations)
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) {
                            reportError(owner, "Some songs could not be saved. Retrying when connected.") { isActive && fetchRelevant() }
                        }
                        finally { runCatching { source.close() }; fetching.compareAndSet(source, null) }
                    }
                }
                synchronized(maintenance) { trim(); publish() }
                delay(30_000)
            }
        }
    }
    @Synchronized fun setSettings(settings: OfflineSettings) {
        val previous = index.settings
        index.settings = settings
        synchronized(publication) { networkPolicy.offline = settings.offline }
        if (settings.offline != previous.offline) openSources.toList().forEach { runCatching { it.close() } }
        mutable.update { it.copy(settings = settings) }
        restart()
        launchCacheWork { synchronized(maintenance) { trim(); publish() } }
    }
    fun downloadCollection(credentials: Credentials, collection: OfflineCollection) {
        synchronized(maintenance) {
            require(credentials == activeCredentials() && credentials.connection.id !in removed) { "The active music account changed." }
            index.saveCollection(credentials.connection, collection)
            publish()
        }
        ensureDownloadTarget(credentials)
        restart()
    }
    fun collections(credentials: Credentials): List<OfflineCollection> = synchronized(maintenance) {
        require(credentials == activeCredentials() && credentials.connection.id !in removed)
        index.collections(credentials.connection)
    }
    fun collection(credentials: Credentials, kind: String, id: String): OfflineCollection? =
        collections(credentials).firstOrNull { it.kind == kind && it.id == id }
    fun pinnedSongs(credentials: Credentials): List<Song> = synchronized(maintenance) {
        require(credentials == activeCredentials() && credentials.connection.id !in removed)
        index.entries().filter { it.pinned && it.key == OfflineIndex.key(credentials.connection, it.song.id) }.map { it.song }
    }
    /** Keep metadata reads and synchronous artwork-owner changes ordered with removal.
     * The callback must not suspend or issue player/network operations. */
    fun <T> coordinatePins(credentials: Credentials, action: () -> T): T = synchronized(maintenance) {
        require(credentials == activeCredentials() && credentials.connection.id !in removed) { "The active music account changed." }
        action()
    }
    fun removeCollection(credentials: Credentials, kind: String, id: String) = removePins(credentials) {
        val before = index.entries().filter { it.pinned }.map { it.key }.toSet()
        index.removeCollection(credentials.connection, kind, id)
        val retained = index.entries().filter { it.pinned }.map { it.key }.toSet() + protectedKeys()
        (before - retained).forEach(cache::removeResource)
    }
    fun download(credentials: Credentials, songs: List<Song>) {
        pin(credentials, songs)
        ensureDownloadTarget(credentials)
    }
    private fun ensureDownloadTarget(credentials: Credentials) {
        synchronized(this) {
            if (target != null) return
            val client = NavidromeClient(credentials, allowLocalHttp = com.everyonesoftware.audio.BuildConfig.DEBUG)
            val http = okhttp3.OkHttpClient.Builder().cache(null).followRedirects(false).followSslRedirects(false)
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS).readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val unsigned = chain.request()
                    try {
                        if (credentials != activeCredentials() || credentials.connection.id in removed) throw IOException()
                        val response = chain.proceed(unsigned.newBuilder()
                            .url(client.authenticatedMediaUri(unsigned.url.toString())).header("Cache-Control", "no-store").build())
                        if (!response.isSuccessful) { response.close(); throw IOException() }
                        response.newBuilder().request(unsigned).build()
                    } catch (_: Exception) { throw IOException("Cannot save this song. Connect and retry.") }
                }.build()
            schedule(credentials, emptyList(), -1, androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(networkPolicy.guard(http)), uri = client::streamUri)
        }
    }
    fun pin(credentials: Credentials, songs: List<Song>) {
        require(credentials == activeCredentials())
        synchronized(maintenance) {
            require(credentials == activeCredentials() && credentials.connection.id !in removed) { "The active music account changed." }
            songs.forEach { song -> index.remember(OfflineIndex.key(credentials.connection, song.id), credentials.connection.id, song, pinned = true) }
            publish()
        }
        restart()
    }
    fun played(credentials: Credentials, song: Song) {
        val owner = publicationOwner()
        if (owner.credentials != credentials) return
        launchCacheWork(owner) {
            synchronized(maintenance) {
                if (!synchronized(publication) { ownsPublication(owner) && credentials.connection.id !in removed }) return@synchronized
                val key = OfflineIndex.key(credentials.connection, song.id)
                try { index.remember(key, credentials.connection.id, song); index.markPlayed(key) }
                catch (_: Exception) { reportError(owner, "Listening history could not be saved.") }
                publish()
            }
        }
    }
    fun removeDownload(credentials: Credentials, songId: String) = removePins(credentials) {
        val key = OfflineIndex.key(credentials.connection, songId)
        index.unpin(key)
        if (key !in protectedKeys() && index.entries().none { it.key == key && it.pinned }) cache.removeResource(key)
    }

    private fun removePins(credentials: Credentials, remove: () -> Unit) {
        var interrupted: Job? = null
        var epoch = -1L
        try {
            synchronized(maintenance) {
                require(credentials == activeCredentials() && credentials.connection.id !in removed)
                epoch = generation
                synchronized(publication) {
                    interrupted = worker
                    interrupted?.cancel()
                }
                fetching.getAndSet(null)?.let { runCatching { it.close() } }
                remove()
                publish()
            }
        } finally {
            // A failed metadata commit leaves the pins intact, but the shared worker
            // still needs to resume. Don't touch a newer worker/account or enable prefetch.
            synchronized(this) {
                if (interrupted != null && worker === interrupted && epoch == generation &&
                    credentials == activeCredentials() && target?.credentials == credentials &&
                    credentials.connection.id !in removed && !networkPolicy.offline) {
                    restart(includeAutomatic = automaticEnabled)
                }
            }
        }
    }
    fun clearCache() {
        synchronized(maintenance) {
            synchronized(publication) { worker?.cancel() }
            val protected = protectedKeys()
            val keys = index.entries().filter { !it.pinned && it.key !in protected }.map { it.key }.toSet()
            // Close every background open, including a cancelled worker superseded in fetching.
            fetching.getAndSet(null)?.let { runCatching { it.close() } }
            openSources.filter { it.background }.forEach { runCatching { it.close() } }
            keys.forEach(cache::removeResource)
            publish()
        }
        // Pinned downloads share the cancelled worker. Resume them without immediately
        // undoing the clear by prefetching the same automatic queue (including retries).
        restart(includeAutomatic = false)
    }
    fun removeConnection(id: String) = synchronized(maintenance) {
        synchronized(publication) {
            removed.add(id)
            if (target?.credentials?.connection?.id == id) { target = null; worker?.cancel() }
        }
        val keys = index.entries().filter { it.connectionId == id }.map { it.key }.toSet()
        blocked.addAll(keys)
        openSources.filter { it.credentials.connection.id == id }.forEach { runCatching { it.close() } }
        keys.forEach(cache::removeResource)
        index.removeConnection(id)
        publish()
    }
    private fun protectedKeys(): Set<String> {
        val current = target?.let { t -> t.queue.getOrNull(t.index)?.let { OfflineIndex.key(t.credentials.connection, it.id) } }
        return openSources.filter { !it.background }.map { it.key }.toSet() + listOfNotNull(current)
    }
    private fun bytes(key: String) = cache.getCachedSpans(key).sumOf { it.length }
    private fun trim() {
        val entries = index.entries()
        val protected = protectedKeys()
        var total = entries.filter { !it.pinned }.sumOf { bytes(it.key) }
        entries.filter { !it.pinned && it.key !in protected }.sortedBy { it.touched }.forEach {
            if (total > index.settings.limitBytes) { val size = bytes(it.key); cache.removeResource(it.key); total -= size }
        }
    }
    private fun publish() {
        val owner = publicationOwner()
        val credentials = owner.credentials
        val entries = index.entries().filter { it.connectionId == credentials?.connection?.id &&
            it.key == credentials?.let { c -> OfflineIndex.key(c.connection, it.song.id) } }
        val snapshot = SongCacheState(
            cacheBytes = entries.filter { !it.pinned }.sumOf { bytes(it.key) },
            downloadBytes = entries.filter { it.pinned }.sumOf { bytes(it.key) },
            cachedSongs = entries.filter { !it.pinned && completeKey(it.key) }.map { it.song.id }.toSet(),
            downloadedSongs = entries.filter { it.pinned && completeKey(it.key) }.map { it.song.id }.toSet(),
            songs = entries.filter { completeKey(it.key) }.sortedByDescending { it.touched }.map { it.song },
            pendingDownloads = entries.filter { it.pinned && !completeKey(it.key) }.map { it.song },
            recentSongs = entries.filter { it.playedAt != null }.sortedByDescending { it.playedAt }.map { it.song },
            collections = credentials?.let { index.collections(it.connection) }.orEmpty(),
            explicitDownloads = entries.filter { it.pinned && (it.owners.isEmpty() || "song:${it.song.id}" in it.owners) }.map { it.song.id }.toSet(),
            owner = credentials?.connection)
        synchronized(publication) {
            if (ownsPublication(owner)) {
                mutable.update { snapshot.copy(settings = it.settings, error = it.error) }
            }
        }
    }
    fun close() {
        synchronized(publication) {
            if (closed) return
            closed = true
            generation++
            target = null
        }
        manager?.unregisterNetworkCallback(networkCallback)
        worker?.cancel()
        openSources.toList().forEach { runCatching { it.close() } }
        scope.cancel()
        runBlocking { scope.coroutineContext[Job]?.join() }
        index.entries().forEach { cache.removeListener(it.key, listener) }
        synchronized(caches) {
            val shared = caches.getValue(cacheDirectory)
            if (shared.second == 1) { caches.remove(cacheDirectory); cache.release() }
            else caches[cacheDirectory] = cache to (shared.second - 1)
        }
    }
    companion object {
        private val caches = mutableMapOf<File, Pair<SimpleCache, Int>>()
        private fun acquire(context: Context, directory: File): SimpleCache = synchronized(caches) {
            val existing = caches[directory]
            val cache = existing?.first ?: SimpleCache(directory, NoOpCacheEvictor(), StandaloneDatabaseProvider(context))
            caches[directory] = cache to ((existing?.second ?: 0) + 1)
            cache
        }
    }
}
