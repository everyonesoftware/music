package com.everyonesoftware.audio.data

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class SongCacheTest {
    @Test fun clearCacheResumesPendingExplicitDownloadWithoutRefillingAutomaticAudio() {
        clearCacheResumesPendingDownload(collection = false)
    }

    @Test fun clearCacheResumesPendingCollectionDownloadWithoutRefillingAutomaticAudio() {
        clearCacheResumesPendingDownload(collection = true)
    }

    private fun clearCacheResumesPendingDownload(collection: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.filesDir, "clear-pending-${System.nanoTime()}")
        val credentials = Credentials(Connection("test", "Test", "https://music.example", "alice"), "secret")
        val cache = SongCache(context, dir) { credentials }
        val current = Song("current", "Current", "Artist")
        val automatic = Song("automatic", "Automatic", "Artist")
        val pinned = Song("pinned", "Pinned", "Artist")
        val entered = java.util.concurrent.CountDownLatch(1)
        val finish = java.util.concurrent.CountDownLatch(1)
        val automaticOpens = java.util.concurrent.atomic.AtomicInteger()
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        var clearing: Thread? = null
        val upstream = DataSource.Factory {
            val bytes = androidx.media3.datasource.ByteArrayDataSource(ByteArray(8192) { 42 })
            object : DataSource by bytes {
                private var gated = false
                override fun open(spec: DataSpec): Long {
                    gated = spec.uri.lastPathSegment == pinned.id
                    if (spec.uri.lastPathSegment == automatic.id) automaticOpens.incrementAndGet()
                    return bytes.open(spec)
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (gated) {
                        entered.countDown()
                        check(finish.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "Pinned transfer gate timed out" }
                    }
                    // Cancellation after the gated read must leave an actual partial,
                    // not a complete one-read resource that passes without any restart.
                    return bytes.read(buffer, offset, if (gated) minOf(length, 1024) else length)
                }
            }
        }
        fun await(message: String, condition: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 10_000
            while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertTrue(message, condition())
        }
        try {
            cache.setSettings(cache.state.value.settings.copy(mobilePrefetch = true))
            cache.schedule(credentials, listOf(current, automatic), 0, upstream) { "https://music.example/$it" }
            await("Automatic fixture did not finish") { cache.complete(credentials, automatic.id) }
            if (collection) cache.downloadCollection(credentials, OfflineCollection("playlist", "saved", "Saved", songs = listOf(pinned)))
            else cache.download(credentials, listOf(pinned))
            assertTrue("Pinned transfer never started", entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(cache.complete(credentials, pinned.id))
            val pendingWorker = SongCache::class.java.getDeclaredField("worker").apply { isAccessible = true }
                .get(cache) as kotlinx.coroutines.Job
            val opensBeforeClear = automaticOpens.get()
            clearing = Thread {
                try { cache.clearCache() } catch (error: Throwable) { failure.set(error) }
            }.also { it.start() }
            // Observe cancellation before releasing the IO gate, not a sleep/thread-state
            // guess that might let the old worker finish before clear actually cancels it.
            await("Clear did not cancel the pending worker") { pendingWorker.isCancelled }
            finish.countDown()
            clearing.join(10_000)
            assertFalse("Clear did not finish", clearing.isAlive)
            failure.get()?.let { throw AssertionError("Clear failed", it) }
            assertFalse("Clear retained automatic audio", cache.complete(credentials, automatic.id))
            // No schedule/settings/network event is issued after clear.
            await("Clear stranded the pinned download: ${cache.state.value}") { cache.complete(credentials, pinned.id) }
            await("Pinned completion was not published") { pinned.id in cache.state.value.downloadedSongs }
            assertFalse("Clear immediately refilled automatic audio", cache.complete(credentials, automatic.id))
            assertEquals("Clear fetched automatic audio again", opensBeforeClear, automaticOpens.get())
            assertTrue("Current playback audio was removed", cache.complete(credentials, current.id))
            if (collection) assertEquals(listOf(pinned), cache.collection(credentials, "playlist", "saved")!!.songs)
        } finally {
            finish.countDown()
            clearing?.join(10_000)
            cache.close()
            dir.deleteRecursively()
        }
    }

    @Test fun wholeFetchRejectsEarlyEofAgainstItsKnownLength() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.filesDir, "early-eof-${System.nanoTime()}")
        val credentials = Credentials(Connection("test", "Test", "https://music.example", "alice"), "secret")
        val cache = SongCache(context, dir) { credentials }
        val song = Song("song", "Title", "Artist")
        val upstream = DataSource.Factory {
            val bytes = androidx.media3.datasource.ByteArrayDataSource(ByteArray(4096))
            object : DataSource by bytes { override fun open(spec: DataSpec): Long { bytes.open(spec); return 8192 } }
        }
        try {
            cache.schedule(credentials, listOf(song), 0, upstream) { "https://music.example/song" }
            val deadline = System.currentTimeMillis() + 10_000
            while (cache.state.value.error == null && System.currentTimeMillis() < deadline) Thread.sleep(25)
            assertNotNull("Early EOF was silently accepted", cache.state.value.error)
            assertFalse("Truncated whole fetch was marked complete", cache.complete(credentials, song.id))
        } finally { cache.close(); dir.deleteRecursively() }
    }

    @Test fun currentWholeFetchFinishesWhileReadOnlyPlaybackSourceRemainsOpen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.filesDir, "whole-ahead-${System.nanoTime()}")
        val credentials = Credentials(Connection("test", "Test", "https://music.example", "alice"), "secret")
        val cache = SongCache(context, dir) { credentials }
        val song = Song("song", "Title", "Artist")
        val upstream = DataSource.Factory { androidx.media3.datasource.ByteArrayDataSource(ByteArray(600_000)) }
        val playback = cache.factory(credentials, song, upstream, writeThrough = false).createDataSource()
        try {
            playback.open(DataSpec.Builder().setUri("https://music.example/song").build())
            playback.read(ByteArray(100), 0, 100)
            cache.setSettings(cache.state.value.settings.copy(prefetch = 0))
            cache.schedule(credentials, listOf(song), 0, upstream) { "https://music.example/song" }
            val deadline = System.currentTimeMillis() + 10_000
            while (!cache.complete(credentials, song.id) && System.currentTimeMillis() < deadline) Thread.sleep(25)
            assertTrue("Full fetch did not finish: ${cache.state.value}", cache.complete(credentials, song.id))
            assertEquals(100, playback.read(ByteArray(100), 0, 100))
        } finally { playback.close(); cache.close(); dir.deleteRecursively() }
    }

    @Test fun truncatedPhysicalSpanIsNotCompleteAfterRestart() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.filesDir, "truncated-${System.nanoTime()}")
        val credentials = Credentials(Connection("test", "Test", "https://music.example", "alice"), "secret")
        val song = Song("song", "Title", "Artist")
        var cache = SongCache(context, dir) { credentials }
        try {
            val source = cache.factory(credentials, song, DataSource.Factory { androidx.media3.datasource.ByteArrayDataSource(ByteArray(4096)) }).createDataSource()
            source.open(DataSpec.Builder().setUri("https://music.example/song").build())
            val buffer = ByteArray(1024)
            while (source.read(buffer, 0, buffer.size) != -1) { }
            source.close()
            assertTrue(cache.complete(credentials, song.id))
            cache.close()
            val span = File(dir, "audio").walkTopDown().first { it.isFile && it.name.endsWith(".exo") }
            java.io.RandomAccessFile(span, "rw").use { it.setLength(100) }
            cache = SongCache(context, dir) { credentials }
            assertFalse("Truncated file was advertised as complete", cache.complete(credentials, song.id))
        } finally { cache.close(); dir.deleteRecursively() }
    }

    @Test fun intactOfflineAudioPlaysWhenMetadataDirectoryIsUnwritable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.filesDir, "metadata-full-${System.nanoTime()}")
        val credentials = Credentials(Connection("test", "Test", "https://music.example", "alice"), "secret")
        val song = Song("song", "Title", "Artist")
        var cache = SongCache(context, dir) { credentials }
        val metadata = File(dir, "metadata")
        try {
            val upstream = DataSource.Factory { androidx.media3.datasource.ByteArrayDataSource(ByteArray(4096)) }
            val source = cache.factory(credentials, song, upstream).createDataSource()
            val spec = DataSpec.Builder().setUri("https://music.example/song").build()
            source.open(spec)
            val buffer = ByteArray(1024)
            while (source.read(buffer, 0, buffer.size) != -1) { }
            source.close()
            cache.setSettings(cache.state.value.settings.copy(offline = true))
            cache.close()
            assertTrue(metadata.setWritable(false, false))
            cache = SongCache(context, dir) { credentials }
            val offline = cache.factory(credentials, song, DataSource.Factory { error("Offline used network") }).createDataSource()
            offline.open(spec)
            assertEquals(1024, offline.read(buffer, 0, buffer.size))
            offline.close()
            assertTrue(cache.state.value.error?.contains("metadata") == true)
        } finally { metadata.setWritable(true, true); cache.close(); dir.deleteRecursively() }
    }

    @Test fun sameDataSourceCanReopenAfterNormalCloseForSeekAndReplay() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.filesDir, "reopen-${System.nanoTime()}")
        val credentials = Credentials(Connection("test", "Test", "https://music.example", "alice"), "secret")
        val cache = SongCache(context, dir) { credentials }
        val song = Song("song", "Title", "Artist")
        val source = cache.factory(credentials, song, DataSource.Factory { androidx.media3.datasource.ByteArrayDataSource(ByteArray(4096) { 42 }) }).createDataSource()
        val spec = DataSpec.Builder().setUri("https://music.example/song").build()
        try {
            source.open(spec)
            assertEquals(100, source.read(ByteArray(100), 0, 100))
            source.close()
            source.open(spec.buildUpon().setPosition(2048).build())
            val bytes = ByteArray(100)
            assertEquals(100, source.read(bytes, 0, bytes.size))
            assertEquals(42.toByte(), bytes[0])
        } finally { source.close(); cache.close(); dir.deleteRecursively() }
    }

    @Test fun leastRecentlyUsedAutomaticSongsEvictButPinnedDownloadsSurvive() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.filesDir, "eviction-${System.nanoTime()}")
        val credentials = Credentials(Connection("test", "Test", "https://music.example", "alice"), "secret")
        val cache = SongCache(context, dir) { credentials }
        val songs = (0..2).map { Song("$it", "Title $it", "Artist") }
        val upstream = DataSource.Factory { androidx.media3.datasource.ByteArrayDataSource(ByteArray(600_000)) }
        try {
            cache.setSettings(cache.state.value.settings.copy(limitBytes = 1_000_000))
            cache.pin(credentials, listOf(songs[0]))
            songs.forEach { song ->
                val source = cache.factory(credentials, song, upstream).createDataSource()
                source.open(DataSpec.Builder().setUri("https://music.example/${song.id}").build())
                val buffer = ByteArray(65_536)
                while (source.read(buffer, 0, buffer.size) != -1) { }
                source.close()
                Thread.sleep(30)
            }
            val deadline = System.currentTimeMillis() + 5_000
            while (cache.complete(credentials, "1") && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertTrue("Pinned bytes were evicted", cache.complete(credentials, "0"))
            assertFalse("Oldest automatic song was retained over the limit", cache.complete(credentials, "1"))
            assertTrue("Most recent automatic song was not retained", cache.complete(credentials, "2"))
            cache.clearCache()
            assertTrue(cache.complete(credentials, "0"))
            assertFalse(cache.complete(credentials, "2"))
        } finally { cache.close(); dir.deleteRecursively() }
    }

    @Test fun accountRemovalRejectsLateInFlightWrites() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.filesDir, "late-write-${System.nanoTime()}")
        val credentials = Credentials(Connection("test", "Test", "https://music.example", "alice"), "secret")
        val cache = SongCache(context, dir) { credentials }
        val entered = java.util.concurrent.CountDownLatch(1)
        val finish = java.util.concurrent.CountDownLatch(1)
        val upstream = DataSource.Factory { object : DataSource {
            override fun addTransferListener(listener: androidx.media3.datasource.TransferListener) = Unit
            override fun open(spec: DataSpec) = 4096L
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int { entered.countDown(); finish.await(5, java.util.concurrent.TimeUnit.SECONDS); buffer.fill(1, offset, offset + length); return length }
            override fun getUri(): android.net.Uri? = null
            override fun close() = Unit
        } }
        val source = cache.factory(credentials, Song("song", "Title", "Artist"), upstream).createDataSource()
        try {
            source.open(DataSpec.Builder().setUri("https://music.example/song").build())
            val thread = Thread { try { source.read(ByteArray(1024), 0, 1024) } catch (_: IOException) { } }
            thread.start()
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            cache.removeConnection("test")
            finish.countDown()
            thread.join(5_000)
            assertFalse(thread.isAlive)
            assertFalse(cache.complete(credentials, "song"))
            assertTrue(cache.state.value.songs.isEmpty())
        } finally { finish.countDown(); source.close(); cache.close(); dir.deleteRecursively() }
    }

    @Test fun enablingOfflineClosesExistingAndNotYetOpenedOnlineSources() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.filesDir, "toggle-offline-${System.nanoTime()}")
        val credentials = Credentials(Connection("test", "Test", "https://music.example", "alice"), "secret")
        val cache = SongCache(context, dir) { credentials }
        val song = Song("song", "Title", "Artist")
        val factory = DataSource.Factory { androidx.media3.datasource.ByteArrayDataSource(ByteArray(4096)) }
        val active = cache.factory(credentials, song, factory).createDataSource()
        val unopened = cache.factory(credentials, song, factory).createDataSource()
        val spec = DataSpec.Builder().setUri("https://music.example/song").build()
        try {
            active.open(spec)
            active.read(ByteArray(100), 0, 100)
            cache.setSettings(cache.state.value.settings.copy(offline = true))
            try { active.read(ByteArray(100), 0, 100); fail("Old source continued reading") } catch (_: IOException) { }
            try { unopened.open(spec); fail("Old source opened after offline switch") } catch (_: IOException) { }
            assertFalse(cache.complete(credentials, song.id))
        } finally { active.close(); unopened.close(); cache.close(); dir.deleteRecursively() }
    }

    @Test fun sameSongsWithNewQueueIdentityReplaceTheQueueBoundTransport() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.filesDir, "queue-rebind-${System.nanoTime()}")
        val credentials = Credentials(Connection("test", "Test", "https://music.example", "alice"), "secret")
        val cache = SongCache(context, dir) { credentials }
        val song = Song("song", "Title", "Artist")
        try {
            val failing = DataSource.Factory { object : DataSource {
                override fun addTransferListener(listener: androidx.media3.datasource.TransferListener) = Unit
                override fun open(spec: DataSpec): Long = throw IOException("Old queue")
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
                override fun getUri(): android.net.Uri? = null
                override fun close() = Unit
            } }
            cache.schedule(credentials, listOf(song), 0, failing, queueIdentity = "old") { "https://music.example/song" }
            cache.schedule(credentials, listOf(song), 0, DataSource.Factory { androidx.media3.datasource.ByteArrayDataSource(ByteArray(1024)) }, queueIdentity = "new") { "https://music.example/song" }
            val deadline = System.currentTimeMillis() + 10_000
            while (!cache.complete(credentials, song.id) && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertTrue("New queue did not finish: ${cache.state.value}", cache.complete(credentials, song.id))
        } finally { cache.close(); dir.deleteRecursively() }
    }

    @Test fun explicitDownloadWithoutPlaybackUsesPrivateAuthenticatedTransport() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.filesDir, "download-test-${System.nanoTime()}")
        val server = MockWebServer()
        server.start()
        val credentials = Credentials(Connection("test", "Test", server.url("/").toString(), "alice"), "secret")
        val cache = SongCache(context, dir) { credentials }
        val song = Song("song", "Title", "Artist")
        try {
            // Registering the network callback may replace an initial transfer. Like a
            // real server, serve every authorized retry rather than consuming one response
            // and leaving the replacement request blocked on MockWebServer's empty queue.
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse =
                    if (request.requestUrl?.encodedPath == "/rest/stream.view" &&
                        request.requestUrl?.queryParameter("u") == "alice" &&
                        !request.requestUrl?.queryParameter("t").isNullOrBlank()) MockResponse().setBody("audio bytes")
                    else MockResponse().setResponseCode(401)
            }
            cache.download(credentials, listOf(song))
            val deadline = System.currentTimeMillis() + 15_000
            while (!cache.complete(credentials, song.id) && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertTrue("Pinned download incomplete after ${server.requestCount} requests; ${cache.state.value}", cache.complete(credentials, song.id))
            val request = server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS)!!
            assertEquals("alice", request.requestUrl!!.queryParameter("u"))
            assertNotNull(request.requestUrl!!.queryParameter("t"))
            assertFalse(dir.walkTopDown().filter { it.isFile }.any { it.readBytes().toString(Charsets.ISO_8859_1).contains("t=") })
            cache.clearCache()
            assertTrue(cache.complete(credentials, song.id))
        } finally { cache.close(); server.shutdown(); dir.deleteRecursively() }
    }

    @Test fun backgroundFetchCompletesNextTrackAndPersistsAcrossRestart() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.filesDir, "background-test-${System.nanoTime()}")
        val credentials = Credentials(Connection("test", "Test", "https://music.example", "alice"), "secret")
        val songs = (0..2).map { Song("$it", "Title $it", "Artist") }
        val data = ByteArray(300_000) { 42 }
        var cache = SongCache(context, dir) { credentials }
        try {
            cache.setSettings(cache.state.value.settings.copy(mobilePrefetch = true))
            cache.schedule(credentials, songs, 0, DataSource.Factory { androidx.media3.datasource.ByteArrayDataSource(data) }) { "https://music.example/$it" }
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline && !songs.all { cache.complete(credentials, it.id) }) Thread.sleep(50)
            assertTrue("Whole current and next tracks were not fetched", songs.all { cache.complete(credentials, it.id) })
            cache.close()
            cache = SongCache(context, dir) { credentials }
            assertTrue(songs.all { cache.complete(credentials, it.id) })
            cache.setSettings(cache.state.value.settings.copy(offline = true))
            val source = cache.factory(credentials, songs[0], DataSource.Factory { error("Offline used upstream") }).createDataSource()
            source.open(DataSpec.Builder().setUri("https://music.example/0").setPosition(150_000).build())
            val buffer = ByteArray(1024)
            assertEquals(1024, source.read(buffer, 0, buffer.size))
            assertEquals(42.toByte(), buffer[0])
            source.close()
        } finally { cache.close(); dir.deleteRecursively() }
    }

    @Test fun partialTrackIsNeverAdvertisedOrPlayedOffline() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.filesDir, "partial-test-${System.nanoTime()}")
        val credentials = Credentials(Connection("test", "Test", "https://music.example", "alice"), "secret")
        val cache = SongCache(context, dir) { credentials }
        val song = Song("partial", "Partial", "Artist")
        try {
            val source = cache.factory(credentials, song, DataSource.Factory { androidx.media3.datasource.ByteArrayDataSource(ByteArray(4096)) }).createDataSource()
            val spec = DataSpec.Builder().setUri("https://music.example/partial").build()
            source.open(spec)
            source.read(ByteArray(1024), 0, 1024)
            source.close()
            assertFalse(cache.complete(credentials, song.id))
            cache.setSettings(cache.state.value.settings.copy(offline = true))
            val offline = cache.factory(credentials, song, DataSource.Factory { error("Network used") }).createDataSource()
            try { offline.open(spec); fail("Played partial audio offline") } catch (_: IOException) { } finally { offline.close() }
        } finally { cache.close(); dir.deleteRecursively() }
    }

    @Test fun fullFetchReopensOfflineAndChecksOwnershipBeforeCacheHits() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.filesDir, "cache-test-${System.nanoTime()}")
        val server = MockWebServer()
        server.start()
        val credentials = Credentials(Connection("test", "Test", server.url("/").toString(), "alice"), "secret")
        var active: Credentials? = credentials
        val cache = SongCache(context, dir) { active }
        val song = Song("song", "Title", "Artist")
        val bytes = ByteArray(8192) { (it % 127).toByte() }
        val upstream = OkHttpDataSource.Factory(OkHttpClient())
        fun read(source: DataSource): ByteArray {
            source.open(DataSpec.Builder().setUri(server.url("/stream").toString()).build())
            return try {
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                while (true) { val count = source.read(buffer, 0, buffer.size); if (count == -1) break; output.write(buffer, 0, count) }
                output.toByteArray()
            } finally { source.close() }
        }
        try {
            server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
            assertArrayEquals(bytes, read(cache.factory(credentials, song, upstream).createDataSource()))
            assertTrue(cache.complete(credentials, song.id))
            cache.setSettings(cache.state.value.settings.copy(offline = true))
            assertArrayEquals(bytes, read(cache.factory(credentials, song, upstream).createDataSource()))
            assertEquals(1, server.requestCount)
            active = credentials.copy(connection = credentials.connection.copy(username = "bob"))
            try { read(cache.factory(credentials, song, upstream).createDataSource()); fail("Old owner read cached audio") }
            catch (_: IOException) { }
            active = credentials
            cache.pin(credentials, listOf(song))
            cache.clearCache()
            assertTrue(cache.complete(credentials, song.id))
            cache.removeDownload(credentials, song.id)
            assertFalse("Unpinned inactive download retained bytes", cache.complete(credentials, song.id))
            cache.removeConnection("test")
            assertFalse(cache.complete(credentials, song.id))
            try { cache.pin(credentials, listOf(song)); fail("Removed account pinned new metadata") } catch (_: IllegalArgumentException) { }
        } finally { cache.close(); server.shutdown(); dir.deleteRecursively() }
    }
}
