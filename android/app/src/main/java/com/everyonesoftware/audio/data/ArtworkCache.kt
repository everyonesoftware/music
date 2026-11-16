package com.everyonesoftware.audio.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Local-first image bytes shared by browsing, downloads and media-session artwork. */
class ArtworkCache(
    context: Context,
    private val networkPolicy: MusicNetworkPolicy,
    private val activeCredentials: () -> Credentials?,
    private val directory: File = File(context.filesDir, "offline-artwork"),
    private val fetch: suspend (Credentials, String) -> ByteArray = { credentials, id ->
        NavidromeClient(credentials, allowLocalHttp = com.everyonesoftware.audio.BuildConfig.DEBUG,
            networkPolicy = networkPolicy).coverArt(id)
    },
) {
    private val store = ArtworkStore(directory)
    private val gate = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val slots = Semaphore(2)
    private val pending = mutableMapOf<String, Deferred<ByteArray?>>()
    private val resolved = ConcurrentHashMap<String, String>()
    private val failedUntil = ConcurrentHashMap<String, Long>()
    private val removed = mutableSetOf<String>()
    private var generation = 0L
    private val mutable = MutableStateFlow(0L)
    val state = mutable.asStateFlow()
    private val errors = MutableStateFlow<String?>(null)
    val error = errors.asStateFlow()
    private fun changed() { mutable.value = mutable.value + 1 }
    private fun allowed(credentials: Credentials, epoch: Long = generation) =
        epoch == generation && credentials == activeCredentials() && credentials.connection.id !in removed
    private fun safeId(id: String) = id.isNotBlank() && id.length <= 4096 &&
        !Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(id)

    fun cachedUri(credentials: Credentials, id: String): String? {
        if (credentials != activeCredentials() || !safeId(id)) return null
        return resolved[OfflineIndex.key(credentials.connection, id, "artwork-v1")]
    }
    private fun resolve(key: String) {
        val uri = Uri.fromFile(File(directory, "$key.image")).toString()
        if (resolved.put(key, uri) != uri) changed()
    }

    suspend fun load(credentials: Credentials, id: String, retry: Boolean = false): ByteArray? = withContext(Dispatchers.IO) {
        val work = synchronized(gate) {
            if (!allowed(credentials) || !safeId(id)) return@withContext null
            val key = OfflineIndex.key(credentials.connection, id, "artwork-v1")
            store.read(credentials.connection, id)?.let { resolve(key); return@withContext it }
            if (resolved.remove(key) != null) changed()
            if (networkPolicy.offline) return@withContext null
            pending[key]?.let { return@synchronized it }
            if (!retry && (failedUntil[key] ?: 0) > System.currentTimeMillis()) return@withContext null
            val epoch = generation
            scope.async {
                try {
                    slots.withPermit {
                        if (synchronized(gate) { !allowed(credentials, epoch) } || networkPolicy.offline) return@withPermit null
                        val bytes = normalize(fetch(credentials, id)) ?: throw IOException("Invalid artwork.")
                        val caller = currentCoroutineContext()
                        synchronized(gate) {
                            caller.ensureActive()
                            if (!allowed(credentials, epoch) || networkPolicy.offline) return@synchronized null
                            store.put(credentials.connection, id, bytes)
                            resolved.keys.retainAll(store.keys())
                            resolve(key)
                            errors.value = null
                            changed()
                            bytes
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    failedUntil[key] = System.currentTimeMillis() + 30_000
                    errors.value = "Some artwork could not be saved. Reconnect or check available storage, then retry."
                    null
                }
            }.also { deferred ->
                pending[key] = deferred
                deferred.invokeOnCompletion { synchronized(gate) { if (pending[key] === deferred) pending.remove(key) } }
            }
        }
        work.await()
    }

    /** Pin intentions are durable before fetching; repeated UI lookups share in-flight work. */
    fun prefetch(credentials: Credentials, ids: List<String>, pinOwner: String? = null) {
        val safe = ids.filter(::safeId).distinct()
        if (credentials != activeCredentials()) return
        if (pinOwner != null) synchronized(gate) {
            if (!allowed(credentials)) return
            try { store.replaceOwner(credentials.connection, pinOwner, safe); changed() }
            catch (_: Exception) { errors.value = "Artwork download preferences could not be saved. Check available storage." }
        }
        safe.forEach { id -> scope.launch { load(credentials, id) } }
    }

    /** Replace every owner from the authoritative download snapshot, dropping obsolete pins. */
    fun syncOwners(credentials: Credentials, owners: Map<String, List<String>>) {
        val safe = owners.mapValues { it.value.filter(::safeId).distinct() }
        synchronized(gate) {
            if (!allowed(credentials)) return
            try { store.syncOwners(credentials.connection, safe) }
            catch (_: Exception) { errors.value = "Artwork download preferences could not be saved. Check available storage." }
        }
        prefetch(credentials, safe.values.flatten().distinct())
    }

    fun removeOwner(credentials: Credentials, owner: String) = synchronized(gate) {
        if (allowed(credentials)) { store.removeOwner(credentials.connection, owner); changed() }
    }
    fun removeConnection(connectionId: String) = synchronized(gate) {
        removed.add(connectionId)
        generation++
        pending.values.toList().forEach { it.cancel() }; pending.clear()
        store.removeConnection(connectionId)
        resolved.clear()
        changed()
    }
    fun accountChanged() = synchronized(gate) {
        generation++
        pending.values.toList().forEach { it.cancel() }; pending.clear()
        failedUntil.clear()
        resolved.clear()
        changed()
    }
    fun clearAutomatic() = synchronized(gate) {
        val pinned = store.pinnedKeys()
        pending.filterKeys { it !in pinned }.values.toList().forEach { it.cancel() }
        pending.keys.retainAll(pinned)
        store.clearAutomatic()
        resolved.keys.retainAll(store.keys())
        changed()
    }
    fun close() { scope.cancel() }

    private suspend fun normalize(bytes: ByteArray): ByteArray? = withContext(Dispatchers.Default) {
        if (bytes.isEmpty() || bytes.size > 2 * 1024 * 1024) return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..16384 || bounds.outHeight !in 1..16384 ||
            bounds.outWidth.toLong() * bounds.outHeight > 64_000_000) return@withContext null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1024) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@withContext null
        try {
            val ratio = minOf(1f, 1024f / maxOf(bitmap.width, bitmap.height))
            val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1),
                (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
            try {
                ByteArrayOutputStream().use { output ->
                    if (!scaled.compress(Bitmap.CompressFormat.JPEG, 90, output)) return@withContext null
                    output.toByteArray().takeIf { it.size <= 2 * 1024 * 1024 }
                }
            } finally { if (scaled !== bitmap) scaled.recycle() }
        } finally { bitmap.recycle() }
    }
}
