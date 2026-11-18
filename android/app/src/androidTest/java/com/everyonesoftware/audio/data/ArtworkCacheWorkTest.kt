package com.everyonesoftware.audio.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Deterministic work-count regressions: no network, image decoding or wall-clock timing. */
@RunWith(AndroidJUnit4::class)
class ArtworkCacheWorkTest {
    private val credentials = Credentials(Connection("art-work", "Test", "https://music.example", "alice"), "secret")
    private class QueueDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        var dispatched = 0
            private set
        override fun dispatch(context: CoroutineContext, block: Runnable) { dispatched++; queue.addLast(block) }
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }
    private fun directory(): File = File(ApplicationProvider.getApplicationContext<Context>().filesDir,
        "artwork-work-${System.nanoTime()}")

    companion object {
        private val originalDigestProvider = java.security.MessageDigest.getInstance("SHA-256").provider
        private val digests = java.util.concurrent.atomic.AtomicInteger()
    }
    class CountingDigest : java.security.MessageDigestSpi() {
        private val delegate = java.security.MessageDigest.getInstance("SHA-256", originalDigestProvider)
        override fun engineUpdate(input: Byte) { delegate.update(input) }
        override fun engineUpdate(input: ByteArray, offset: Int, len: Int) { delegate.update(input, offset, len) }
        override fun engineDigest(): ByteArray { digests.incrementAndGet(); return delegate.digest() }
        override fun engineReset() { delegate.reset() }
    }

    @Suppress("DEPRECATION")
    @Test fun recompositionDoesNotHashTheSameLookupKeyAgain() {
        val root = directory()
        val io = QueueDispatcher()
        ArtworkStore(root).put(credentials.connection, "cover", byteArrayOf(1, 2, 3))
        val cache = ArtworkCache(ApplicationProvider.getApplicationContext(), MusicNetworkPolicy(), { credentials }, root, io) { _, _ ->
            error("Cached artwork must not fetch")
        }
        val provider = object : java.security.Provider("ArtworkWorkCounting", 1.0, "Artwork work regression") {
            init { put("MessageDigest.SHA-256", CountingDigest::class.java.name) }
        }
        try {
            assertEquals(1, java.security.Security.insertProviderAt(provider, 1))
            cache.prefetch(credentials, listOf("cover"))
            io.drain()
            assertNotNull(cache.cachedUri(credentials, "cover"))
            val before = digests.get()
            repeat(100) {
                cache.prefetch(credentials, listOf("cover"))
                assertNotNull(cache.cachedUri(credentials, "cover"))
            }
            assertEquals("Cached lookup keys must be reused on the UI thread", before, digests.get())
        } finally {
            java.security.Security.removeProvider(provider.name)
            cache.close(); io.drain(); root.deleteRecursively()
        }
    }

    @Test fun resolvedBrowsingPrefetchDoesNotScheduleOrReadArtworkAgain() {
        val root = directory()
        val io = QueueDispatcher()
        ArtworkStore(root).put(credentials.connection, "cover", byteArrayOf(1, 2, 3))
        val cache = ArtworkCache(ApplicationProvider.getApplicationContext(), MusicNetworkPolicy(), { credentials }, root, io) { _, _ ->
            error("Cached artwork must not fetch")
        }
        try {
            cache.prefetch(credentials, listOf("cover"))
            io.drain()
            assertNotNull(cache.cachedUri(credentials, "cover"))
            val before = io.dispatched
            repeat(100) { cache.prefetch(credentials, listOf("cover")) }
            assertEquals("Recomposition must be memory-only for resolved artwork", before, io.dispatched)
        } finally { cache.close(); io.drain(); root.deleteRecursively() }
    }

    @Test fun unresolvedBrowsingPrefetchIsAdmittedOnlyOnceBeforeDispatch() {
        val root = directory()
        val io = QueueDispatcher()
        val cache = ArtworkCache(ApplicationProvider.getApplicationContext(), MusicNetworkPolicy(), { credentials }, root, io) { _, _ ->
            error("Queued work must not have started")
        }
        try {
            repeat(100) { cache.prefetch(credentials, listOf("cover")) }
            assertEquals("Duplicate lookups must coalesce before creating IO work", 1, io.dispatched)
        } finally { cache.close(); io.drain(); root.deleteRecursively() }
    }

    @Test fun clearingAutomaticCancelsQueuedBrowsingBeforeItCanRefill() {
        val root = directory()
        val io = QueueDispatcher()
        var requests = 0
        val cache = ArtworkCache(ApplicationProvider.getApplicationContext(), MusicNetworkPolicy(), { credentials }, root, io) { _, _ ->
            requests++
            throw java.io.IOException("Fixture")
        }
        try {
            cache.prefetch(credentials, listOf("cover"))
            cache.clearAutomatic()
            io.drain()
            assertEquals("Clear must cancel admitted work, not just active fetches", 0, requests)
        } finally { cache.close(); io.drain(); root.deleteRecursively() }
    }

    @Test fun accountChangeCancelsQueuedBrowsingEvenWhenTheSameAccountReturns() {
        val root = directory()
        val io = QueueDispatcher()
        var requests = 0
        val cache = ArtworkCache(ApplicationProvider.getApplicationContext(), MusicNetworkPolicy(), { credentials }, root, io) { _, _ ->
            requests++
            throw java.io.IOException("Fixture")
        }
        try {
            cache.prefetch(credentials, listOf("cover"))
            cache.accountChanged()
            io.drain()
            assertEquals("Old account work must not resume after switch-back", 0, requests)
        } finally { cache.close(); io.drain(); root.deleteRecursively() }
    }

    @Test fun lateFailureFromCancelledAccountDoesNotPoisonReturningAccount() {
        val root = directory()
        val io = QueueDispatcher()
        val finish = CompletableDeferred<Unit>()
        var requests = 0
        val cache = ArtworkCache(ApplicationProvider.getApplicationContext(), MusicNetworkPolicy(), { credentials }, root, io) { _, _ ->
            requests++
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                finish.await()
                throw java.io.IOException("Late fixture failure")
            }
        }
        try {
            cache.prefetch(credentials, listOf("cover"))
            io.drain()
            assertEquals(1, requests)
            cache.accountChanged()
            finish.complete(Unit)
            io.drain()
            assertNull("A cancelled account must not publish errors", cache.error.value)
            cache.prefetch(credentials, listOf("cover"))
            io.drain()
            assertEquals("Stale failures must not install a new account's cooldown", 2, requests)
        } finally { cache.close(); io.drain(); root.deleteRecursively() }
    }

    @Test fun unresolvedBrowsingAdmissionDoesNotWaitForTheStorageMonitor() {
        val root = directory()
        val io = QueueDispatcher()
        val cache = ArtworkCache(ApplicationProvider.getApplicationContext(), MusicNetworkPolicy(), { credentials }, root, io) { _, _ ->
            error("Queued work must not run")
        }
        val gate = requireNotNull(ArtworkCache::class.java.getDeclaredField("gate").apply { isAccessible = true }.get(cache))
        val held = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val admitted = java.util.concurrent.CountDownLatch(1)
        val storage = Thread { synchronized(gate) { held.countDown(); release.await() } }
        val caller = Thread { cache.prefetch(credentials, listOf("unresolved")); admitted.countDown() }
        try {
            storage.start()
            assertTrue(held.await(2, java.util.concurrent.TimeUnit.SECONDS))
            caller.start()
            assertTrue("UI admission must not wait behind artwork hashing or fsync", admitted.await(1, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            release.countDown(); storage.join(); caller.join()
            cache.close(); io.drain(); root.deleteRecursively()
        }
    }

    @Test fun clearingAutomaticPreservesQueuedPinnedArtwork() {
        val root = directory()
        val io = QueueDispatcher()
        val started = mutableListOf<String>()
        val finish = CompletableDeferred<Unit>()
        val cache = ArtworkCache(ApplicationProvider.getApplicationContext(), MusicNetworkPolicy(), { credentials }, root, io) { _, id ->
            started.add(id)
            finish.await()
            throw java.io.IOException("Fixture")
        }
        try {
            cache.prefetch(credentials, listOf("pinned"), "song:pinned")
            cache.prefetch(credentials, listOf("automatic"))
            cache.clearAutomatic()
            io.drain()
            assertEquals(listOf("pinned"), started)
            assertEquals(1, ArtworkStore(root).pinnedKeys().size)
        } finally { cache.close(); io.drain(); root.deleteRecursively() }
    }

    @Test fun visibleArtworkBypassesItsOwnPositionInTheDownloadBacklog() {
        val root = directory()
        val io = QueueDispatcher()
        val started = mutableListOf<String>()
        val finish = CompletableDeferred<Unit>()
        val cache = ArtworkCache(ApplicationProvider.getApplicationContext(), MusicNetworkPolicy(), { credentials }, root, io) { _, id ->
            started.add(id)
            finish.await()
            throw java.io.IOException("Fixture")
        }
        try {
            cache.syncOwners(credentials, mapOf("downloaded-songs" to listOf("first", "second", "visible")))
            io.drain()
            cache.prefetch(credentials, listOf("visible"))
            io.drain()
            assertEquals(listOf("first", "visible"), started)
            repeat(100) { cache.prefetch(credentials, listOf("visible")) }
            io.drain()
            assertEquals("Visible and downloaded requests must still share active fetches", listOf("first", "visible"), started)
        } finally { cache.close(); io.drain(); root.deleteRecursively() }
    }

    @Test fun memoizedLookupKeysRemainAccountScopedAcrossSwitches() {
        val root = directory()
        val io = QueueDispatcher()
        val other = credentials.copy(connection = credentials.connection.copy(username = "bob"))
        var active = credentials
        ArtworkStore(root).apply {
            put(credentials.connection, "cover", byteArrayOf(1))
            put(other.connection, "cover", byteArrayOf(2))
        }
        val policy = MusicNetworkPolicy().apply { offline = true }
        val cache = ArtworkCache(ApplicationProvider.getApplicationContext(), policy, { active }, root, io) { _, _ ->
            error("Offline artwork must not fetch")
        }
        try {
            cache.prefetch(credentials, listOf("cover")); io.drain()
            val first = cache.cachedUri(credentials, "cover")
            assertNotNull(first)
            active = other; cache.accountChanged()
            assertNull(cache.cachedUri(credentials, "cover"))
            cache.prefetch(other, listOf("cover")); io.drain()
            val second = cache.cachedUri(other, "cover")
            assertNotNull(second)
            assertNotEquals(first, second)
            active = credentials; cache.accountChanged()
            cache.prefetch(credentials, listOf("cover")); io.drain()
            assertEquals(first, cache.cachedUri(credentials, "cover"))
        } finally { cache.close(); io.drain(); root.deleteRecursively() }
    }

    @Test fun downloadedArtworkLeavesCapacityForVisibleArtwork() {
        val root = directory()
        val io = QueueDispatcher()
        val started = mutableListOf<String>()
        val finish = CompletableDeferred<Unit>()
        val cache = ArtworkCache(ApplicationProvider.getApplicationContext(), MusicNetworkPolicy(), { credentials }, root, io) { _, id ->
            started.add(id)
            finish.await()
            throw java.io.IOException("Fixture finished")
        }
        try {
            cache.syncOwners(credentials, mapOf("downloaded-songs" to listOf("download-1", "download-2", "download-3")))
            io.drain()
            assertEquals("Bulk prefetch must not reserve both network slots", 1, started.size)
            cache.prefetch(credentials, listOf("visible"))
            io.drain()
            assertTrue("Visible cover must start while downloads remain blocked", "visible" in started)
        } finally { cache.close(); io.drain(); root.deleteRecursively() }
    }
}
