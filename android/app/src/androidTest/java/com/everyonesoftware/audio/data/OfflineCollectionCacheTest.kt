package com.everyonesoftware.audio.data

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class OfflineCollectionCacheTest {
    @Test fun accountSwitchInvalidatesOldSnapshotAndRejectsAnInFlightPublication() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.filesDir, "publication-account-${System.nanoTime()}")
        val a = Credentials(Connection("a", "A", "https://music.example", "alice"), "secret")
        val b = a.copy(connection = a.connection.copy(username = "bob"))
        val active = java.util.concurrent.atomic.AtomicReference(a)
        val cache = SongCache(context, dir) { active.get() }
        val collection = OfflineCollection("playlist", "shared-id", "Alice's playlist", songs = listOf(Song("s", "A", "Artist")))
        val index = requireNotNull(SongCache::class.java.getDeclaredField("index").apply { isAccessible = true }.get(cache))
        val maintenance = requireNotNull(SongCache::class.java.getDeclaredField("maintenance").apply { isAccessible = true }.get(cache))
        val publish = SongCache::class.java.getDeclaredMethod("publish").apply { isAccessible = true }
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val oldPublisher = Thread {
            try { publish.invoke(cache) } catch (error: Throwable) { failure.set(error) }
        }
        try {
            cache.setSettings(cache.state.value.settings.copy(offline = true))
            cache.downloadCollection(a, collection)
            assertEquals(listOf(collection), cache.state.value.collections)
            synchronized(maintenance) {
                synchronized(index) {
                    oldPublisher.start()
                    await("Old publication did not reach its index read") { oldPublisher.state == Thread.State.BLOCKED }
                    cache.accountChanged { active.set(b) }
                    assertTrue("Alice's snapshot remained visible after switching accounts", cache.state.value.collections.isEmpty())
                }
                // Don't let B's normal refresh repair a stale publication before we observe it.
                oldPublisher.join(5000)
                assertFalse(oldPublisher.isAlive)
                failure.get()?.let { throw AssertionError("Publication failed", it) }
                assertTrue("Late publication exposed Alice's snapshot", cache.state.value.collections.isEmpty())
            }
            val bobCollection = collection.copy(name = "Bob's playlist")
            cache.downloadCollection(b, bobCollection)
            assertEquals(listOf(bobCollection), cache.state.value.collections)
            assertEquals(b.connection, cache.state.value.owner)
        } finally { oldPublisher.join(5000); cache.close(); dir.deleteRecursively() }
    }

    @Test fun failedSongRemovalRestartsPinnedTransfers() = failedRemovalRestartsPinnedTransfers(collection = false)

    @Test fun failedCollectionRemovalRestartsPinnedTransfers() = failedRemovalRestartsPinnedTransfers(collection = true)

    private fun failedRemovalRestartsPinnedTransfers(collection: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.filesDir, "failed-removal-${System.nanoTime()}")
        val credentials = Credentials(Connection("account", "Test", "https://music.example", "alice"), "secret")
        val cache = SongCache(context, dir) { credentials }
        val pending = Song("pending", "Pending", "Artist")
        val other = pending.copy(id = "other")
        val saved = OfflineCollection("playlist", "p", "Saved", songs = listOf(other))
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val upstream = DataSource.Factory {
            val bytes = ByteArrayDataSource(ByteArray(8192))
            object : DataSource by bytes {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    entered.countDown()
                    check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    return bytes.read(buffer, offset, minOf(length, 1024))
                }
            }
        }
        val blockedWrite = File(dir, "metadata/catalog.tmp")
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val removal = Thread {
            try {
                if (collection) cache.removeCollection(credentials, saved.kind, saved.id)
                else cache.removeDownload(credentials, other.id)
            } catch (error: Throwable) { failure.set(error) }
        }
        try {
            cache.setSettings(cache.state.value.settings.copy(offline = true))
            cache.schedule(credentials, emptyList(), -1, upstream) { "https://music.example/$it" }
            cache.pin(credentials, listOf(pending))
            if (collection) cache.downloadCollection(credentials, saved) else cache.pin(credentials, listOf(other))
            cache.setSettings(cache.state.value.settings.copy(offline = false))
            assertTrue("Pinned transfer did not start", entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val interrupted = SongCache::class.java.getDeclaredField("worker").apply { isAccessible = true }
                .get(cache) as kotlinx.coroutines.Job
            // The in-flight source already remembered its metadata. Fail just the removal
            // rename/open while keeping the previously committed catalog intact.
            assertTrue(blockedWrite.mkdir())
            removal.start()
            await("Removal never cancelled the shared transfer") { interrupted.isCancelled }
            release.countDown()
            removal.join(10_000)
            assertFalse(removal.isAlive)
            assertTrue("Removal did not surface the persistence failure", failure.get() is java.io.IOException)
            assertTrue(blockedWrite.delete())
            if (collection) assertEquals(listOf(saved), cache.collections(credentials))
            else assertTrue(other.id in cache.state.value.explicitDownloads)
            await("Unrelated pinned transfer never resumed after failed removal") { pending.id in cache.state.value.downloadedSongs }
        } finally { release.countDown(); removal.join(10_000); cache.close(); dir.deleteRecursively() }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        while (!condition() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(15)
        assertTrue(message, condition())
    }

    @Test fun sharedCollectionAndExplicitOwnersRetainBytesUntilLastRemovalAfterRestart() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.filesDir, "collection-cache-${System.nanoTime()}")
        val credentials = Credentials(Connection("collection-test", "Test", "https://music.example", "alice"), "secret")
        val song = Song("one", "One", "Artist")
        var cache = SongCache(context, dir) { credentials }
        try {
            val source = cache.factory(credentials, song, DataSource.Factory { ByteArrayDataSource(ByteArray(4096)) }).createDataSource()
            source.open(DataSpec.Builder().setUri("https://music.example/song").build())
            val buffer = ByteArray(1024)
            while (source.read(buffer, 0, buffer.size) != -1) { }
            source.close()
            cache.setSettings(cache.state.value.settings.copy(offline = true))
            val album = OfflineCollection("album", "a", "Album", songs = listOf(song, song))
            val playlist = OfflineCollection("playlist", "p", "Playlist", songs = listOf(song))
            cache.downloadCollection(credentials, album)
            cache.downloadCollection(credentials, playlist)
            cache.pin(credentials, listOf(song))
            cache.close()
            cache = SongCache(context, dir) { credentials }
            assertEquals(listOf(song, song), cache.collection(credentials, "album", "a")!!.songs)
            cache.clearCache()
            assertTrue(cache.complete(credentials, song.id))
            cache.removeDownload(credentials, song.id)
            assertTrue("Individual removal broke collection pin", cache.complete(credentials, song.id))
            cache.removeCollection(credentials, "album", "a")
            assertTrue("Another collection still needs audio", cache.complete(credentials, song.id))
            cache.removeCollection(credentials, "playlist", "p")
            assertFalse("Last owner removal retained inactive audio", cache.complete(credentials, song.id))
            assertTrue(cache.collections(credentials).isEmpty())
        } finally { cache.close(); dir.deleteRecursively() }
    }

    @Test fun accountSwitchRejectsStaleCollectionWritesAndAccountRemovalPurgesSnapshots() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.filesDir, "collection-account-${System.nanoTime()}")
        val a = Credentials(Connection("a", "A", "https://music.example", "alice"), "secret")
        val b = a.copy(connection = a.connection.copy(id = "b", username = "bob"))
        var active: Credentials? = a
        val cache = SongCache(context, dir) { active }
        val collection = OfflineCollection("playlist", "p", "Playlist", songs = listOf(Song("s", "Song", "Artist")))
        try {
            cache.setSettings(cache.state.value.settings.copy(offline = true))
            cache.downloadCollection(a, collection)
            active = b
            cache.accountChanged()
            try { cache.downloadCollection(a, collection.copy(name = "Late")); fail("Stale write accepted") } catch (_: IllegalArgumentException) { }
            assertTrue(cache.collections(b).isEmpty())
            active = a
            cache.accountChanged()
            assertEquals(listOf(collection), cache.collections(a))
            cache.removeConnection("a")
            assertTrue(OfflineIndex(File(dir, "metadata")).collections(a.connection).isEmpty())
            try { cache.downloadCollection(a, collection); fail("Removed account resurrected") } catch (_: IllegalArgumentException) { }
        } finally { cache.close(); dir.deleteRecursively() }
    }
}
