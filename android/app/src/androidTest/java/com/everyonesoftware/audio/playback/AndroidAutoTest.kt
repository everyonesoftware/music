package com.everyonesoftware.audio.playback

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaConstants
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.everyonesoftware.audio.AudioApplication
import com.everyonesoftware.audio.data.Connection
import com.everyonesoftware.audio.data.Credentials
import com.everyonesoftware.audio.data.Song
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Real service/controller and local protocol/audio fixtures; no personal server or car host. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class AndroidAutoTest {
    private val app get() = ApplicationProvider.getApplicationContext<AudioApplication>()
    private val graph get() = app.graph
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val server = MockWebServer()
    private lateinit var browser: MediaBrowser
    private lateinit var credentials: Credentials
    private var previousConnectionId: String? = null
    private var noSessionConnectionId: String? = null
    @Volatile private var delayedPlaylists: java.util.concurrent.CountDownLatch? = null
    private val playlistsRequestStarted = java.util.concurrent.CountDownLatch(1)
    @Volatile private var unavailable = false
    @Volatile private var failStream = false
    @Volatile private var duplicatePlaylistNames = false
    @Volatile private var otherPlaylist = false
    @Volatile private var failOtherPlaylist = false
    @Volatile private var otherPlaylistGate: java.util.concurrent.CountDownLatch? = null
    private val otherPlaylistStarted = java.util.concurrent.CountDownLatch(1)
    @Volatile private var fixtureCount = 3
    @Volatile private var fixtureAlbumCount = 1
    @Volatile private var fixtureRating = 0
    @Volatile private var delayedPlaylist: java.util.concurrent.CountDownLatch? = null
    private val playlistRequestStarted = java.util.concurrent.CountDownLatch(1)
    private val changedParents = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val fixtureSongs = listOf(Song("one", "First song", "Fixture artist", coverArtId = "one-cover"), Song("two", "Second song", "Fixture artist", coverArtId = "two-cover"), Song("one", "First song", "Fixture artist", coverArtId = "one-cover"))
    private lateinit var fixtureWav: ByteArray
    private lateinit var fixtureCover: ByteArray

    private fun ok(body: String) = MockResponse().setHeader("Content-Type", "application/json")
        .setBody("{\"subsonic-response\":{\"status\":\"ok\",$body}}")

    @Before fun connectFixtureBrowser() {
        val sampleBytes = 300 * 8000 * 2
        val wav = ByteBuffer.allocate(44 + sampleBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + sampleBytes); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(sampleBytes)
        }.array()
        fixtureWav = wav
        val bitmap = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888)
        fixtureCover = java.io.ByteArrayOutputStream().use { output ->
            bitmap.eraseColor(android.graphics.Color.rgb(38, 112, 83))
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, output))
            output.toByteArray()
        }
        bitmap.recycle()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (unavailable) return MockResponse().setResponseCode(503)
                val url = request.requestUrl ?: return MockResponse().setResponseCode(400)
                if (url.queryParameter("u") != "auto-fixture" || url.queryParameter("t").isNullOrBlank()) return MockResponse().setResponseCode(401)
                if (url.encodedPath.endsWith("/getPlaylist.view") && url.queryParameter("id") == "other") {
                    otherPlaylistStarted.countDown()
                    otherPlaylistGate?.let { check(it.await(15, TimeUnit.SECONDS)) }
                    if (failOtherPlaylist) return MockResponse().setResponseCode(503)
                }
                if (url.encodedPath.endsWith("/getPlaylist.view")) delayedPlaylist?.let {
                    playlistRequestStarted.countDown()
                    check(it.await(15, TimeUnit.SECONDS))
                }
                return when (url.encodedPath.substringAfterLast('/')) {
                    "getPlaylists.view" -> {
                        delayedPlaylists?.let {
                            playlistsRequestStarted.countDown()
                            check(it.await(15, TimeUnit.SECONDS))
                        }
                        val extra = if (duplicatePlaylistNames || otherPlaylist) ",{\"id\":\"other\",\"name\":\"${if (duplicatePlaylistNames) "Drive fixture" else "Other fixture"}\",\"songCount\":3}" else ""
                        ok("\"playlists\":{\"playlist\":[{\"id\":\"p/with:punctuation\",\"name\":\"Drive fixture\",\"songCount\":3,\"coverArt\":\"playlist-cover\"}$extra]}")
                    }
                    "getPlaylist.view" -> ok("\"playlist\":{\"entry\":[${fixtureSongs.take(fixtureCount).joinToString(",") { "{\"id\":\"${it.id}\",\"title\":\"${it.title}\",\"artist\":\"Fixture artist\",\"duration\":300,\"coverArt\":\"${it.coverArtId}\"}" }}]}")
                    "getAlbumList2.view" -> {
                        val offset = url.queryParameter("offset")?.toIntOrNull() ?: 0
                        val size = url.queryParameter("size")?.toIntOrNull() ?: 100
                        ok("\"albumList2\":{\"album\":[${(offset until fixtureAlbumCount).take(size).joinToString(",") { index ->
                            val suffix = if (index == 0) "" else "-$index"
                            "{\"id\":\"album-fixture$suffix\",\"name\":\"Album fixture$suffix\",\"artist\":\"Fixture artist\",\"songCount\":3,\"coverArt\":\"album-cover$suffix\"}"
                        }}]}")
                    }
                    "getAlbum.view" -> ok("\"album\":{\"id\":\"album-fixture\",\"name\":\"Album fixture\",\"artist\":\"Fixture artist\",\"coverArt\":\"album-cover\",\"song\":[${fixtureSongs.joinToString(",") { "{\"id\":\"${it.id}\",\"title\":\"${it.title}\",\"artist\":\"Fixture artist\",\"duration\":300,\"coverArt\":\"${it.coverArtId}\"}" }}]}")
                    "getCoverArt.view" -> MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(fixtureCover))
                    "setRating.view" -> { fixtureRating = url.queryParameter("rating")!!.toInt(); fixtureCount = 1; ok("\"fixture\":true") }
                    "getSong.view" -> ok("\"song\":{\"id\":\"${url.queryParameter("id")}\",\"userRating\":$fixtureRating}")
                    "stream.view" -> if (failStream) MockResponse().setResponseCode(503) else MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(wav))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        runBlocking {
            graph.bootstrap()
            previousConnectionId = graph.credentials.value?.connection?.id?.takeIf { id -> graph.store.connections().any { it.id == id } }
            credentials = Credentials(Connection(UUID.randomUUID().toString(), "Auto fixture", server.url("/").toString(), "auto-fixture"), "fixture-only-password")
            graph.store.saveConnection(credentials)
            graph.activate(credentials.connection.id)
            graph.setSession(SessionRules.start(credentials.connection.id, "p/with:punctuation", "Drive fixture", fixtureSongs)
                .copy(queue = fixtureSongs, positionMs = 5000, shuffle = false))
        }
        lateinit var future: ListenableFuture<MediaBrowser>
        instrumentation.runOnMainSync {
            future = MediaBrowser.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java)))
                .setMaxCommandsForMediaItems(2)
                .setListener(object : MediaBrowser.Listener {
                    override fun onChildrenChanged(browser: MediaBrowser, parentId: String, itemCount: Int, params: androidx.media3.session.MediaLibraryService.LibraryParams?) {
                        changedParents.add(parentId)
                    }
                }).buildAsync()
        }
        browser = future.get(15, TimeUnit.SECONDS)
    }

    @After fun cleanupFixture() {
        otherPlaylistGate?.countDown()
        delayedPlaylists?.countDown()
        delayedPlaylist?.countDown()
        if (::browser.isInitialized) {
            onMain { browser.pause(); browser.stop() }
            // Drain the controller requests before closing its fixture server. Do not forcibly
            // stop a still-bound Media3 service during its asynchronous foreground transition.
            onMain { browser.getLibraryRoot(null) }.get(15, TimeUnit.SECONDS)
            until { !graph.playback.value.playing }
            onMain { browser.release() }
        }
        if (::credentials.isInitialized) runBlocking {
            noSessionConnectionId?.let { graph.store.removeConnection(it) }
            graph.store.removeConnection(credentials.connection.id)
            previousConnectionId?.let { graph.activate(it) }
        }
        server.shutdown()
    }

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun root(): MediaItem {
        val result = onMain { browser.getLibraryRoot(null) }.get(15, TimeUnit.SECONDS)
        assertEquals("Auto must expose a real library root", 0, result.resultCode)
        val item = requireNotNull(result.value)
        assertEquals("Music", item.mediaMetadata.title.toString())
        assertEquals("Music", app.packageManager.getApplicationLabel(app.applicationInfo).toString())
        return item
    }
    private fun children(id: String, page: Int = 0, size: Int = 100): List<MediaItem> {
        val result = onMain { browser.getChildren(id, page, size, null) }.get(15, TimeUnit.SECONDS)
        assertEquals("Auto must serve browsable content", 0, result.resultCode)
        return requireNotNull(result.value).toList()
    }
    private fun playlist(): MediaItem {
        val folder = children(root().mediaId).single { it.mediaMetadata.title == "Playlists" }
        return children(folder.mediaId).single()
    }
    private fun until(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!condition()) { check(System.nanoTime() < deadline) { "Auto fixture state timed out" }; Thread.sleep(25) }
    }

    @Test fun actualStartedHistoryIsSharedByNativeQueueAndCarBrowseWithoutCountingSkippedSelections() {
        val tracks = children(playlist().mediaId).drop(2)
        onMain { browser.setMediaItem(tracks[2]); browser.prepare(); browser.play() }
        until { onMain { browser.mediaItemCount == 3 && browser.currentMediaItemIndex == 2 && browser.isPlaying } && graph.session.value?.index == 2 }
        val saved = graph.session.value!!
        assertTrue(saved.played.isEmpty())
        var hierarchy = children(CarMediaIds.QUEUE)
        assertEquals(listOf("Played", "First song", "Up next"), hierarchy.map { it.mediaMetadata.title.toString() })
        assertTrue(children(hierarchy[0].mediaId).isEmpty())
        assertTrue(children(hierarchy[2].mediaId).isEmpty())
        assertEquals("content", hierarchy[1].mediaMetadata.artworkUri?.scheme)
        onMain { browser.pause() }
        until { onMain { !browser.isPlaying } }
        onMain { browser.seekTo(0, 0) }
        until { graph.session.value?.index == 0 && graph.session.value?.played == listOf(2) }
        onMain { browser.seekTo(1, 0) }
        until { graph.session.value?.index == 1 }
        assertEquals(listOf(2), graph.session.value!!.played)
        onMain { browser.play() }
        until { onMain { browser.isPlaying } }
        onMain { browser.seekToNextMediaItem() }
        until { graph.session.value?.index == 2 && graph.session.value?.played == listOf(2, 1) }
        hierarchy = children(CarMediaIds.QUEUE)
        val played = children(hierarchy[0].mediaId)
        assertEquals(listOf(2, 1), played.map { CarMediaIds.parse(it.mediaId)!!.song!!.toInt() })
        assertTrue(played.all { it.mediaMetadata.artworkUri?.scheme == "content" })
        val art = requireNotNull(played[0].mediaMetadata.artworkUri)
        assertTrue(app.contentResolver.openInputStream(art)!!.use { it.readBytes().isNotEmpty() })
        onMain { browser.setMediaItem(played[0]); browser.prepare(); browser.play() }
        until { onMain { browser.mediaItemCount == 3 && browser.currentMediaItemIndex == 2 && browser.isPlaying } }
        assertEquals(saved.queueId, graph.session.value!!.queueId)
        assertEquals(saved.queue, graph.session.value!!.queue)
        until { runBlocking { graph.store.session(saved.connectionId)?.played == graph.session.value!!.played } }
    }

    @Test fun failedUnstartedSelectionAndPausedRestoreNeverInventPlayedHistory() {
        until { onMain { browser.mediaItemCount == 3 } }
        assertTrue(graph.session.value!!.played.isEmpty())
        onMain { browser.seekTo(2, 0) }
        until { graph.session.value?.index == 2 }
        assertTrue(graph.session.value!!.played.isEmpty())
        failStream = true
        // Unique uncached resources keep this failure independent of speculative caching.
        runBlocking {
            graph.setSession(SessionRules.start(credentials.connection.id, "failure", "Failure fixture",
                fixtureSongs.mapIndexed { index, song -> song.copy(id = "unstarted-$index") }, shuffle = false).copy(index = 2))
        }
        until { onMain { browser.mediaItemCount == 3 && browser.currentMediaItemIndex == 2 } }
        onMain { browser.prepare(); browser.play() }
        until { onMain { browser.playerError != null } }
        assertFalse(onMain { browser.isPlaying })
        assertTrue(graph.session.value!!.played.isEmpty())
        onMain { browser.pause(); browser.seekTo(0, 0) }
        until { graph.session.value?.index == 0 }
        assertTrue(graph.session.value!!.played.isEmpty())
        assertTrue(children(children(CarMediaIds.QUEUE)[0].mediaId).isEmpty())
    }

    @Test fun upNextShuffleRetainsActiveMediaDuplicatesHistoryAndPausedRestore() {
        val store = androidx.lifecycle.ViewModelStore()
        val model = onMain { com.everyonesoftware.audio.ui.AudioViewModel(app, graph).also { store.put("up-next", it) } }
        val transitions = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) { transitions.add(playbackState) }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { transitions.add(-100 - reason) }
        }
        try {
            until { !model.state.value.booting }
            val saved = SessionRules.start(credentials.connection.id, "up-next", "Up next fixture",
                fixtureSongs + fixtureSongs + fixtureSongs, shuffle = true).copy(index = 2, positionMs = 10000, played = listOf(0, 1, 0))
            runBlocking { graph.setSession(saved) }
            until { onMain { browser.mediaItemCount == 9 && browser.currentMediaItemIndex == 2 } }
            onMain { browser.play() }
            until { onMain { browser.isPlaying } }
            onMain { browser.addListener(listener) }
            val orders = mutableSetOf<List<Int>>()
            repeat(8) {
                val position = onMain { browser.currentPosition }
                onMain { model.shuffleUpNext() }
                until { !model.state.value.loading }
                Thread.sleep(150)
                val next = graph.session.value!!
                orders.add(next.occurrences)
                assertEquals(saved.queueId, next.queueId)
                assertEquals(saved.queue.take(3), next.queue.take(3))
                assertEquals(saved.occurrences.take(3), next.occurrences.take(3))
                assertEquals(saved.queue.zip(saved.occurrences).toSet(), next.queue.zip(next.occurrences).toSet())
                assertEquals(saved.played, next.played)
                assertEquals(2, onMain { browser.currentMediaItemIndex })
                assertTrue(onMain { browser.isPlaying && browser.currentPosition >= position && !browser.shuffleModeEnabled })
            }
            assertTrue("Repeated actions must reshuffle, never toggle a mode", orders.size > 1)
            assertTrue("Suffix reorder must not reload/transition active media: $transitions", transitions.isEmpty())
            val upcoming = graph.session.value!!
            until { onMain { List(browser.mediaItemCount) { browser.getMediaItemAt(it).mediaId } } == upcoming.occurrences.map {
                CarMediaIds.queued(upcoming.connectionId, upcoming.queueId, it) } }
            onMain { browser.seekToNextMediaItem() }
            until { graph.session.value?.index == 3 && graph.session.value?.played == saved.played + saved.occurrences[2] }
            onMain { browser.pause() }
            // The remote controller can briefly extrapolate an older position after
            // pause. Capture the service-owned checkpoint only after its durable write.
            until { !graph.playback.value.playing && graph.session.value?.positionMs == graph.playback.value.positionMs &&
                runBlocking { graph.store.session(saved.connectionId) } == graph.session.value }
            val paused = graph.playback.value.positionMs
            onMain { model.shuffleUpNext() }
            until { !model.state.value.loading }
            until { runBlocking { graph.store.session(saved.connectionId) } == graph.session.value }
            val restored = runBlocking { graph.store.session(saved.connectionId)!! }
            assertEquals(paused, restored.positionMs)
            assertEquals(paused, graph.playback.value.positionMs)
            assertEquals(3, restored.index)
            assertEquals(saved.played + saved.occurrences[2], restored.played)
            assertFalse(onMain { browser.playWhenReady })
            val cold = com.everyonesoftware.audio.AudioGraph(app)
            runBlocking { cold.activate(saved.connectionId) }
            assertEquals(restored, cold.session.value)
            assertFalse(cold.playback.value.playing)
        } finally { onMain { browser.removeListener(listener); store.clear() } }
    }

    @Test fun phoneSelectedDuplicateUsesRealServiceForPlaylistAndAlbumAndNativeHistory() {
        val store = androidx.lifecycle.ViewModelStore()
        val model = onMain { com.everyonesoftware.audio.ui.AudioViewModel(app, graph).also { store.put("phone", it) } }
        try {
            until { !model.state.value.booting && !model.state.value.albumsRefreshing && model.state.value.playlists.isNotEmpty() }
            for (kind in listOf("playlist", "album")) {
                onMain { if (kind == "playlist") model.openPlaylist("p/with:punctuation") else model.openAlbum("album-fixture") }
                until { if (kind == "playlist") !model.state.value.playlistLoading && model.state.value.playlistSongs.size == 3
                    else !model.state.value.albumLoading && model.state.value.albumSongs.size == 3 }
                val page = model.state.value
                val songs = if (kind == "playlist") page.playlistSongs else page.albumSongs
                val id = if (kind == "playlist") "p/with:punctuation" else "album-fixture"
                val before = graph.session.value!!.queueId
                onMain { model.playCollectionSong(com.everyonesoftware.audio.ui.CollectionSongSelection(page.connection!!.id, kind, id, 2, songs)) }
                until { graph.session.value?.queueId != before && onMain { browser.mediaItemCount == 3 && browser.currentMediaItemIndex == 2 && browser.isPlaying } }
                val selected = graph.session.value!!
                assertEquals(songs, selected.queue)
                assertEquals(kind, selected.sourceType)
                assertFalse(selected.shuffle)
                assertTrue(selected.played.isEmpty())
                onMain { model.shuffleUpNext() }
                until { !model.state.value.loading }
                assertEquals("No pending suffix at the last duplicate", selected.occurrences, graph.session.value!!.occurrences)
                assertEquals(selected.queueId, graph.session.value!!.queueId)
                assertEquals(selected.played, graph.session.value!!.played)
                assertEquals(2, onMain { browser.currentMediaItemIndex })
                assertTrue(onMain { browser.isPlaying })
                onMain { model.shuffleUpNext() }
                until { !model.state.value.loading }
                onMain { browser.seekTo(1, 0) }
                until { graph.session.value?.played == listOf(2) }
                assertEquals(selected.queueId, graph.session.value!!.queueId)
            }
        } finally { onMain { store.clear() } }
    }

    @Test fun browseCollectionsAndContentsWithoutReplacingTheSavedQueue() {
        val saved = graph.session.value!!
        val folders = children(root().mediaId)
        assertEquals(listOf("Playlists", "Albums", "Queue"), folders.map { it.mediaMetadata.title.toString() })
        for (title in listOf("Playlists", "Albums")) {
            val folder = folders.single { it.mediaMetadata.title == title }
            val items = children(folder.mediaId)
            assertEquals(1, items.size)
            val collection = items.single()
            assertTrue(collection.mediaMetadata.isBrowsable == true)
            assertFalse(collection.mediaMetadata.isPlayable == true)
            assertTrue(collection.mediaMetadata.supportedCommands.isEmpty())
            assertTrue(onMain { browser.getCommandButtonsForMediaItem(collection) }.isEmpty())
            val contents = children(collection.mediaId)
            assertEquals(listOf("Play", "Shuffle play"), contents.take(2).map { it.mediaMetadata.title.toString() })
            assertEquals(fixtureSongs.map { it.title }, contents.drop(2).map { it.mediaMetadata.title.toString() })
            assertEquals(5, contents.map { it.mediaId }.toSet().size)
            assertTrue(contents.all { it.mediaMetadata.isPlayable == true && it.mediaMetadata.isBrowsable != true && it.localConfiguration == null })
            assertEquals(contents.take(2), children(collection.mediaId, 0, 2))
            assertEquals(items, children(folder.mediaId, 0, 1))
            assertTrue(children(folder.mediaId, Int.MAX_VALUE, 100).isEmpty())
        }
        assertEquals(saved.queueId, graph.session.value!!.queueId)
        assertEquals(saved.queue, graph.session.value!!.queue)
        assertFalse(onMain { browser.playWhenReady })
    }

    @Test fun shuffleSelectionStartsSharedPlaybackAndPreservesDuplicateOccurrences() {
        val shuffle = MediaItem.Builder().setMediaId(CarMediaIds.shuffle(credentials.connection.id, "p/with:punctuation")).build()
        onMain { browser.setMediaItem(shuffle); browser.prepare(); browser.play() }
        until { graph.playback.value.playing }
        val saved = graph.session.value!!
        assertEquals(listOf("one", "one", "two"), saved.queue.map { it.id }.sorted())
        assertTrue(saved.shuffle)
        assertEquals(3, onMain { browser.mediaItemCount })
        onMain { browser.pause() }
        until { !graph.playback.value.playing }
        val pausedOrder = graph.session.value!!.queue.map { it.id }
        onMain { browser.play() }
        until { graph.playback.value.playing }
        assertEquals(pausedOrder, graph.session.value!!.queue.map { it.id })
    }

    @Test fun queueSelectionUsesTheExistingQueueIdentity() {
        val saved = graph.session.value!!
        val folder = children(root().mediaId).single { it.mediaMetadata.title == "Queue" }
        val queue = children(folder.mediaId)
        assertEquals(3, queue.size)
        assertNotEquals(queue[0].mediaId, queue[2].mediaId)
        val upcoming = children(queue[2].mediaId)
        onMain { browser.setMediaItem(upcoming[0]); browser.prepare(); browser.play() }
        until { graph.playback.value.playing && graph.session.value?.index == 1 }
        assertEquals(saved.queueId, graph.session.value!!.queueId)
        assertEquals(saved.queue, graph.session.value!!.queue)
        assertEquals(3, onMain { browser.mediaItemCount })
    }

    @Test fun arbitraryMediaUrisAndStaleQueueIdsCannotReplacePlayback() {
        val folder = children(root().mediaId).single { it.mediaMetadata.title == "Queue" }
        val stale = children(folder.mediaId)[1]
        runBlocking { graph.setSession(SessionRules.start(credentials.connection.id, "p/with:punctuation", "Drive fixture", fixtureSongs)) }
        val saved = graph.session.value!!
        val malicious = MediaItem.Builder().setMediaId("https://attacker.invalid/song").setUri("https://attacker.invalid/song").build()
        onMain { browser.setMediaItem(malicious) }
        instrumentation.waitForIdleSync()
        onMain { browser.setMediaItem(stale) }
        instrumentation.waitForIdleSync()
        // A service round-trip barriers both preceding controller requests.
        onMain { browser.getLibraryRoot(null) }.get(15, TimeUnit.SECONDS)
        assertEquals(saved.queueId, graph.session.value!!.queueId)
        assertEquals(saved.queue, graph.session.value!!.queue)
        assertNotEquals("https://attacker.invalid/song", onMain { browser.currentMediaItem?.localConfiguration?.uri?.toString() })
    }

    @Test fun unavailableServerReturnsAnErrorAndTheSameRequestCanRecover() {
        playlist()
        // Browse returns once summaries arrive, before count reconciliation finishes. Drain
        // that healthy job so refresh() cannot coalesce the outage probe into an older success.
        runBlocking { graph.playlists.refresh().join() }
        unavailable = true
        runBlocking { graph.playlists.refresh().join() }
        val failed = onMain { browser.getChildren(CarMediaIds.PLAYLISTS, 0, 100, null) }.get(15, TimeUnit.SECONDS)
        assertNotEquals(0, failed.resultCode)
        // The error result also precedes the refresh job's completion. Keep the fixture in
        // outage mode until that job exits, rather than racing recovery with its old failure.
        runBlocking { graph.playlists.refresh().join() }
        unavailable = false
        assertEquals(1, children(CarMediaIds.PLAYLISTS).size)
        assertFalse(onMain { browser.playWhenReady })
    }

    @Test fun playlistRowOmitsActionsButLegacyShuffleCommandRemainsCompatible() {
        val p = playlist()
        val action = "com.everyonesoftware.audio.SHUFFLE_PLAYLIST"
        assertTrue(p.mediaMetadata.supportedCommands.isEmpty())
        val args = Bundle().apply { putString(MediaConstants.EXTRA_KEY_MEDIA_ID, p.mediaId) }
        val result = onMain { browser.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args) }.get(15, TimeUnit.SECONDS)
        assertEquals(0, result.resultCode)
        until { graph.playback.value.playing }
        assertTrue(graph.session.value!!.shuffle)
        assertEquals(listOf("one", "one", "two"), graph.session.value!!.queue.map { it.id }.sorted())
        val saved = graph.session.value!!
        val invalidArgs = Bundle().apply { putString(MediaConstants.EXTRA_KEY_MEDIA_ID, "https://attacker.invalid/playlist") }
        val rejected = onMain { browser.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), invalidArgs) }.get(15, TimeUnit.SECONDS)
        assertNotEquals(0, rejected.resultCode)
        assertEquals(saved.queueId, graph.session.value!!.queueId)
    }

    @Test fun playlistAndAlbumContentsOfferOrderedPlayThenImmediateShuffleWithFreshQueues() {
        for (parent in listOf(CarMediaIds.PLAYLISTS, CarMediaIds.ALBUMS)) {
            val collection = children(parent).single()
            val actions = children(collection.mediaId).take(2)
            assertEquals(listOf("Play", "Shuffle play"), actions.map { it.mediaMetadata.title.toString() })
            for (action in listOf(actions[0], actions[1], actions[1], actions[0])) {
                val before = graph.session.value!!.queueId
                onMain { browser.setMediaItem(action); browser.prepare(); browser.play() }
                until { graph.playback.value.playing && graph.session.value?.queueId != before }
                val selected = graph.session.value!!
                assertEquals(if (parent == CarMediaIds.PLAYLISTS) "playlist" else "album", selected.sourceType)
                assertEquals(0, selected.index)
                assertEquals(action == actions[1], selected.shuffle)
                assertEquals(listOf("one", "one", "two"), selected.queue.map { it.id }.sorted())
                if (action == actions[0]) assertEquals(fixtureSongs.map { it.id }, selected.queue.map { it.id })
                onMain { assertFalse(browser.shuffleModeEnabled) }
                val queue = children(CarMediaIds.QUEUE)
                assertEquals(3, queue.map { it.mediaId }.toSet().size)
            }
        }
    }

    @Test fun selectedPlaylistAndAlbumSongKeepsWholeOrderedQueueAndDuplicateOccurrence() {
        for (parent in listOf(CarMediaIds.PLAYLISTS, CarMediaIds.ALBUMS)) {
            val collection = children(parent).single()
            val tracks = children(collection.mediaId).drop(2)
            assertNotEquals(tracks[0].mediaId, tracks[2].mediaId)
            for (index in listOf(1, 2)) {
                val previous = graph.session.value!!.queueId
                onMain { browser.setMediaItem(tracks[index]); browser.prepare(); browser.play() }
                until { graph.playback.value.playing && graph.session.value?.queueId != previous && graph.session.value?.index == index }
                val session = graph.session.value!!
                // Media3 masks setMediaItem with its single request until the asynchronous
                // service result reaches this controller; graph persistence can arrive first.
                until { onMain { browser.mediaItemCount == fixtureSongs.size && browser.currentMediaItemIndex == index &&
                    browser.currentMediaItem?.mediaId == CarMediaIds.queued(session.connectionId, session.queueId, index) } }
                assertEquals(fixtureSongs.map { it.id }, session.queue.map { it.id })
                assertEquals(if (parent == CarMediaIds.PLAYLISTS) "playlist" else "album", session.sourceType)
                assertFalse(session.shuffle)
                onMain {
                    assertEquals(3, browser.mediaItemCount)
                    assertEquals(index, browser.currentMediaItemIndex)
                    assertFalse(browser.shuffleModeEnabled)
                    browser.seekToPreviousMediaItem()
                }
                until { graph.session.value?.index == index - 1 }
                assertEquals(session.queueId, graph.session.value!!.queueId)
                assertEquals(session.queue, graph.session.value!!.queue)
            }
        }
    }

    @Test fun collectionArtworkIsReadableOfflineAndRevokedAfterAccountSwitch() {
        val saved = graph.session.value!!
        val artwork = listOf(CarMediaIds.PLAYLISTS, CarMediaIds.ALBUMS).map { parent ->
            val collection = children(parent).single()
            val uri = requireNotNull(collection.mediaMetadata.artworkUri)
            assertEquals("content", uri.scheme)
            assertEquals("${app.packageName}.artwork", uri.authority)
            assertFalse(uri.toString().contains("auto-fixture"))
            assertFalse(uri.toString().contains("fixture-only-password"))
            assertFalse(uri.toString().contains("playlist-cover"))
            assertFalse(uri.toString().contains("album-cover"))
            val bytes = requireNotNull(app.contentResolver.openInputStream(uri)).use { it.readBytes() }
            val cover = requireNotNull(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            assertTrue(cover.width > 0 && cover.height > 0)
            cover.recycle()
            uri
        }
        assertEquals(saved.queueId, graph.session.value!!.queueId)
        val settings = graph.songCache.state.value.settings
        try {
            runBlocking { graph.songCache.setSettings(settings.copy(offline = true)) }
            unavailable = true
            artwork.forEach { uri ->
                val bytes = requireNotNull(app.contentResolver.openInputStream(uri)).use { it.readBytes() }
                val cover = requireNotNull(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                cover.recycle()
            }
        } finally {
            unavailable = false
            runBlocking { graph.songCache.setSettings(settings) }
        }
        runBlocking {
            val other = credentials.copy(connection = credentials.connection.copy(id = UUID.randomUUID().toString(), name = "Other account"))
            noSessionConnectionId = other.connection.id
            graph.store.saveConnection(other)
            graph.activate(other.connection.id)
        }
        artwork.forEach { uri ->
            val bytes = runCatching { app.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            assertTrue("Old account's cover must be revoked", bytes == null || bytes.isEmpty())
        }
    }

    @Test fun largeAlbumCatalogKeepsPagedAndUnpagedArtworkCapabilitiesReadable() {
        fixtureAlbumCount = 512
        val saved = graph.session.value!!
        val firstPage = children(CarMediaIds.ALBUMS, 0, 100)
        assertEquals(100, firstPage.size)
        val tail = children(CarMediaIds.ALBUMS, 5, 100)
        assertEquals(12, tail.size)
        // Legacy subscriptions can request the entire catalogue with this page size.
        val all = children(CarMediaIds.ALBUMS, 0, Int.MAX_VALUE)
        assertEquals(512, all.size)
        for (item in firstPage + tail + all) {
            val uri = requireNotNull(item.mediaMetadata.artworkUri)
            assertEquals("Issued cover URI must survive catalogue traversal", "image/jpeg", app.contentResolver.getType(uri))
        }
        for (item in listOf(firstPage.first(), tail.last(), all.last())) {
            val bytes = requireNotNull(app.contentResolver.openInputStream(requireNotNull(item.mediaMetadata.artworkUri))).use { it.readBytes() }
            val cover = requireNotNull(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            cover.recycle()
        }
        assertEquals(saved.queueId, graph.session.value!!.queueId)
        assertEquals(saved.queue, graph.session.value!!.queue)
        assertFalse(onMain { browser.playWhenReady })
    }

    @Test fun collectionActionsRejectWrongAccountAndQueueIdsWithoutReplacingPlayback() {
        playlist()
        val saved = graph.session.value!!
        val queueId = children(CarMediaIds.QUEUE).first().mediaId
        for (mediaId in listOf(queueId, CarMediaIds.playlist("different-account", "p/with:punctuation"))) {
            for (command in listOf(CarLibrary.PLAY_COLLECTION, CarLibrary.SHUFFLE_COLLECTION)) {
                val args = Bundle().apply { putString(MediaConstants.EXTRA_KEY_MEDIA_ID, mediaId) }
                val result = onMain { browser.sendCustomCommand(SessionCommand(command, Bundle.EMPTY), args) }.get(15, TimeUnit.SECONDS)
                assertNotEquals(0, result.resultCode)
                assertEquals(saved.queueId, graph.session.value!!.queueId)
                assertEquals(saved.queue, graph.session.value!!.queue)
                assertFalse(onMain { browser.playWhenReady })
            }
        }
    }

    @Test fun offlinePlaylistAndAlbumActionsRequireCompleteSnapshotsAndUseSavedOccurrences() = runBlocking {
        val settings = graph.songCache.state.value.settings
        val active = requireNotNull(graph.credentials.value)
        val snapshots = listOf(
            com.everyonesoftware.audio.data.OfflineCollection("playlist", "p/with:punctuation", "Drive fixture", songs = fixtureSongs),
            com.everyonesoftware.audio.data.OfflineCollection("album", "album-fixture", "Album fixture", songs = fixtureSongs)
        )
        try {
            graph.songCache.setSettings(settings.copy(offline = true))
            snapshots.forEach { graph.songCache.downloadCollection(active, it) }
            unavailable = true
            val saved = graph.session.value!!
            for (parent in listOf(CarMediaIds.PLAYLISTS, CarMediaIds.ALBUMS)) {
                val collection = children(parent).single()
                val contents = children(collection.mediaId)
                assertEquals(5, contents.size)
                for (item in contents) {
                    try {
                        CarLibrary(graph).resolve(item.mediaId)
                        fail("Partial offline collection must not start")
                    } catch (_: IllegalArgumentException) { }
                    assertEquals(saved.queueId, graph.session.value!!.queueId)
                }
            }
            graph.songCache.setSettings(settings.copy(offline = false, autoSave = true, prefetch = 0))
            fixtureSongs.distinctBy { it.id }.forEach { song ->
                val source = graph.songCache.factory(active, song, androidx.media3.datasource.DataSource.Factory {
                    androidx.media3.datasource.ByteArrayDataSource(fixtureWav)
                }).createDataSource()
                source.open(androidx.media3.datasource.DataSpec.Builder().setUri("https://fixture.invalid/${song.id}").build())
                try { val buffer = ByteArray(8192); while (source.read(buffer, 0, buffer.size) != -1) { } }
                finally { source.close() }
            }
            graph.songCache.setSettings(settings.copy(offline = true))
            for (parent in listOf(CarMediaIds.PLAYLISTS, CarMediaIds.ALBUMS)) {
                val collection = children(parent).single()
                val contents = children(collection.mediaId)
                for ((index, item) in contents.take(2).withIndex()) {
                    val previous = graph.session.value!!.queueId
                    onMain { browser.setMediaItem(item); browser.prepare(); browser.play() }
                    until { graph.playback.value.playing && graph.session.value?.queueId != previous }
                    val session = graph.session.value!!
                    assertEquals(index == 1, session.shuffle)
                    assertEquals(listOf("one", "one", "two"), session.queue.map { it.id }.sorted())
                    if (index == 0) assertEquals(fixtureSongs, session.queue)
                }
                val previous = graph.session.value!!.queueId
                onMain { browser.setMediaItem(contents.last()); browser.prepare(); browser.play() }
                until { graph.playback.value.playing && graph.session.value?.queueId != previous }
                assertEquals(fixtureSongs, graph.session.value!!.queue)
                assertEquals(2, graph.session.value!!.index)
                assertFalse(graph.session.value!!.shuffle)
            }
        } finally {
            unavailable = false
            graph.songCache.setSettings(settings)
        }
    }

    @Test fun voiceSearchSelectsAMatchingPlaylistAndBlankSearchResumes() {
        fun request(query: String) = MediaItem.Builder().setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery(query).build()).build()
        onMain { browser.setMediaItem(request("Drive fixture")); browser.prepare(); browser.play() }
        until { graph.playback.value.playing && graph.session.value?.shuffle == true }
        onMain { browser.pause() }
        until { !graph.playback.value.playing }
        val saved = graph.session.value!!
        onMain { browser.setMediaItem(request("")); browser.prepare(); browser.play() }
        until { graph.playback.value.playing }
        assertEquals(saved.queueId, graph.session.value!!.queueId)
        assertEquals(saved.queue, graph.session.value!!.queue)
        until { onMain { browser.mediaItemCount == 3 && browser.isPlaying } }
        assertTrue(graph.session.value!!.played.isEmpty())
        onMain { browser.seekTo(2, 0) }
        until { graph.session.value?.index == 2 && graph.session.value?.played == listOf(0) }
        assertEquals(listOf(0), children(children(CarMediaIds.QUEUE)[0].mediaId).map { CarMediaIds.parse(it.mediaId)!!.song!!.toInt() })
    }

    @Test fun delayedCarSelectionCannotOverwriteANewerPhoneQueue() {
        val p = playlist()
        val gate = java.util.concurrent.CountDownLatch(1)
        delayedPlaylist = gate
        val args = Bundle().apply { putString(MediaConstants.EXTRA_KEY_MEDIA_ID, p.mediaId) }
        val future = onMain { browser.sendCustomCommand(SessionCommand("com.everyonesoftware.audio.SHUFFLE_PLAYLIST", Bundle.EMPTY), args) }
        assertTrue(playlistRequestStarted.await(10, TimeUnit.SECONDS))
        val newer = SessionRules.start(credentials.connection.id, "new-phone-playlist", "New phone playlist", fixtureSongs)
        runBlocking { graph.setSession(newer) }
        gate.countDown()
        val result = future.get(15, TimeUnit.SECONDS)
        assertNotEquals("A stale car request must fail, not undo the phone selection", 0, result.resultCode)
        assertEquals(newer.queueId, graph.session.value!!.queueId)
        assertEquals(newer.queue, graph.session.value!!.queue)
    }

    @Test fun phoneQueueReplacementNotifiesSubscribedCarBrowsers() {
        val folder = children(root().mediaId).single { it.mediaMetadata.title == "Queue" }
        assertEquals(0, onMain { browser.subscribe(folder.mediaId, null) }.get(15, TimeUnit.SECONDS).resultCode)
        until { changedParents.contains(folder.mediaId) }
        changedParents.clear()
        val newer = SessionRules.start(credentials.connection.id, "phone", "Phone selection", fixtureSongs.reversed())
        runBlocking { graph.setSession(newer) }
        until { changedParents.contains(folder.mediaId) }
        val queue = children(folder.mediaId)
        assertEquals(listOf("Played", newer.queue[0].title, "Up next"), queue.map { it.mediaMetadata.title })
        assertTrue(children(queue[0].mediaId).isEmpty())
        assertEquals(newer.queue.drop(1).map { it.title }, children(queue[2].mediaId).map { it.mediaMetadata.title })
    }

    @Test fun failingUnrelatedCountDoesNotBlockHealthyCarPlayback() {
        otherPlaylist = true
        failOtherPlaylist = true
        val rows = children(CarMediaIds.PLAYLISTS)
        assertEquals(2, rows.size)
        val selected = rows.single { it.mediaMetadata.title == "Drive fixture" }
        val args = Bundle().apply { putString(MediaConstants.EXTRA_KEY_MEDIA_ID, selected.mediaId) }
        val result = onMain { browser.sendCustomCommand(SessionCommand(CarLibrary.SHUFFLE_PLAYLIST, Bundle.EMPTY), args) }.get(10, TimeUnit.SECONDS)
        assertEquals(0, result.resultCode)
        until { graph.playback.value.playing }
    }

    @Test fun slowUnrelatedDetailsDoNotBlockBrowseOrVoicePlayback() {
        otherPlaylist = true
        otherPlaylistGate = java.util.concurrent.CountDownLatch(1)
        val rows = onMain { browser.getChildren(CarMediaIds.PLAYLISTS, 0, 100, null) }.get(5, TimeUnit.SECONDS)
        assertEquals(0, rows.resultCode)
        assertEquals(2, rows.value!!.size)
        assertTrue(otherPlaylistStarted.await(5, TimeUnit.SECONDS))
        val request = MediaItem.Builder().setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery("Drive fixture").build()).build()
        onMain { browser.setMediaItem(request); browser.prepare(); browser.play() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!graph.playback.value.playing && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue("Healthy voice selection must not wait for unrelated counts", graph.playback.value.playing)
    }

    @Test fun phoneRatingRefreshNotifiesSubscribedPlaylistRowsWithoutReplacingQueue() {
        runBlocking { graph.playlists.refresh().join() }
        assertEquals(0, onMain { browser.subscribe(CarMediaIds.PLAYLISTS, null) }.get(10, TimeUnit.SECONDS).resultCode)
        until { changedParents.contains(CarMediaIds.PLAYLISTS) }
        changedParents.clear()
        val saved = graph.session.value!!
        val owner = androidx.lifecycle.ViewModelStore()
        lateinit var model: com.everyonesoftware.audio.ui.AudioViewModel
        try {
            onMain { model = com.everyonesoftware.audio.ui.AudioViewModel(app, graph, false); owner.put("rating-fixture", model) }
            until { !model.state.value.booting }
            onMain { model.rateSong(4) }
            until { changedParents.contains(CarMediaIds.PLAYLISTS) }
            assertEquals(saved.queueId, graph.session.value!!.queueId)
            assertEquals(saved.queue.map { it.id }, graph.session.value!!.queue.map { it.id })
            assertEquals("1 songs", children(CarMediaIds.PLAYLISTS).single().mediaMetadata.subtitle)
        } finally { onMain { owner.clear() } }
    }

    @Test fun ambiguousVoiceRequestDoesNotPickAnArbitraryPlaylist() {
        duplicatePlaylistNames = true
        val saved = graph.session.value!!
        val result = runCatching { runBlocking { CarLibrary(graph).resolveSearch("Drive fixture") } }
        assertTrue("Duplicate exact names must require a manual choice", result.exceptionOrNull() is IllegalArgumentException)
        assertEquals(saved, graph.session.value)
    }

    @Test fun coldVoiceIntentWithSavedAccountAndNoSessionSurvivesStartupRefresh() {
        val id = UUID.randomUUID().toString()
        noSessionConnectionId = id
        runBlocking {
            graph.store.saveConnection(credentials.copy(connection = credentials.connection.copy(id = id, name = "No-session voice fixture")))
            assertNull(graph.store.session(id))
            graph.activate(id)
        }
        assertNull(graph.session.value)
        val gate = java.util.concurrent.CountDownLatch(1)
        delayedPlaylists = gate
        val intent = Intent("android.media.action.MEDIA_PLAY_FROM_SEARCH").setPackage(app.packageName)
            .putExtra(android.app.SearchManager.QUERY, "Drive fixture").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val modelRef = java.util.concurrent.atomic.AtomicReference<com.everyonesoftware.audio.ui.AudioViewModel>()
        val monitor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
        val callback = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
            if (activity is com.everyonesoftware.audio.MainActivity && stage == androidx.test.runner.lifecycle.Stage.RESUMED) {
                modelRef.set(androidx.lifecycle.ViewModelProvider(activity)[com.everyonesoftware.audio.ui.AudioViewModel::class.java])
            }
        }
        onMain { monitor.addLifecycleCallback(callback) }
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        // Release independently of ActivityScenario's idle wait: the loading indicator remains
        // animated while HTTP is gated, so launch/onActivity cannot be the response barrier.
        val releaseResponse = executor.submit {
            try {
                assertTrue("The real voice/startup playlist request must reach the fixture", playlistsRequestStarted.await(10, TimeUnit.SECONDS))
                until { modelRef.get()?.state?.value?.booting == false }
                // Barrier startup, including its automatic refresh, before releasing HTTP.
                instrumentation.runOnMainSync { }
                assertNull("Voice request must still be pending before the response", modelRef.get().state.value.error)
                assertNull("No queue may exist before the delayed response", graph.session.value)
            } finally { gate.countDown() }
        }
        try {
            androidx.test.core.app.ActivityScenario.launch<com.everyonesoftware.audio.MainActivity>(intent).use {
                releaseResponse.get(15, TimeUnit.SECONDS)
                val model = requireNotNull(modelRef.get())
                until { graph.playback.value.playing || model.state.value.error != null }
                assertNull("Voice playback must succeed after initialization", model.state.value.error)
                val session = requireNotNull(graph.session.value)
                assertEquals(id, session.connectionId)
                assertEquals("p/with:punctuation", session.playlistId)
                assertTrue(session.shuffle)
                assertEquals(listOf("one", "one", "two"), session.queue.map { it.id }.sorted())
                assertEquals(3, onMain { browser.mediaItemCount })
                assertTrue(onMain { browser.isPlaying })
                assertEquals(com.everyonesoftware.audio.ui.Screen.PLAYER, model.state.value.screen)
            }
        } finally {
            gate.countDown()
            executor.shutdownNow()
            onMain { monitor.removeLifecycleCallback(callback) }
        }
    }

    @Test fun mediaSessionActivityOpensPlayerWithoutChangingTheSession() {
        root()
        until { onMain { browser.mediaItemCount == fixtureSongs.size } }
        val saved = graph.session.value!!
        val activityRef = java.util.concurrent.atomic.AtomicReference<com.everyonesoftware.audio.MainActivity>()
        val modelRef = java.util.concurrent.atomic.AtomicReference<com.everyonesoftware.audio.ui.AudioViewModel>()
        val monitor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
        val callback = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
            if (activity is com.everyonesoftware.audio.MainActivity && stage == androidx.test.runner.lifecycle.Stage.RESUMED) {
                activityRef.set(activity)
                modelRef.set(androidx.lifecycle.ViewModelProvider(activity)[com.everyonesoftware.audio.ui.AudioViewModel::class.java])
            }
        }
        onMain { monitor.addLifecycleCallback(callback) }
        try {
            val pendingIntent = requireNotNull(onMain { browser.sessionActivity })
            pendingIntent.send()
            until { modelRef.get()?.state?.value?.let { !it.booting && it.screen == com.everyonesoftware.audio.ui.Screen.PLAYER } == true }
            assertEquals(saved, graph.session.value)
            assertFalse(graph.playback.value.playing)
            onMain { modelRef.get().showScreen(com.everyonesoftware.audio.ui.Screen.PLAYLISTS) }
            pendingIntent.send()
            until { modelRef.get().state.value.screen == com.everyonesoftware.audio.ui.Screen.PLAYER }
            assertEquals(saved, graph.session.value)
            assertFalse(graph.playback.value.playing)
        } finally {
            onMain { activityRef.get()?.finish(); monitor.removeLifecycleCallback(callback) }
        }
    }

    @Test fun voiceSearchIntentOpensTheNativePlayer() {
        val intent = Intent("android.media.action.MEDIA_PLAY_FROM_SEARCH").setPackage(app.packageName)
            .putExtra(android.app.SearchManager.QUERY, "Drive fixture").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        assertNotNull("Voice search must be discoverable", app.packageManager.resolveActivity(intent, 0))
        androidx.test.core.app.ActivityScenario.launch<com.everyonesoftware.audio.MainActivity>(intent).use { scenario ->
            until { graph.playback.value.playing && graph.session.value?.shuffle == true }
            scenario.onActivity { activity ->
                val model = androidx.lifecycle.ViewModelProvider(activity)[com.everyonesoftware.audio.ui.AudioViewModel::class.java]
                assertEquals(com.everyonesoftware.audio.ui.Screen.PLAYER, model.state.value.screen)
            }
            val queueId = graph.session.value!!.queueId
            scenario.recreate()
            assertEquals("Recreation must not replay the voice selection", queueId, graph.session.value!!.queueId)
        }
    }

    @Test fun musicControlsAdvertiseTrackSkippingInsteadOfTenSecondSeeking() {
        root()
        until { onMain { browser.mediaItemCount == fixtureSongs.size } }
        val saved = graph.session.value!!
        onMain { browser.play() }
        until { onMain { browser.isPlaying && browser.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) } }
        val notifications = app.getSystemService(android.app.NotificationManager::class.java)
        until { notifications.activeNotifications.any {
            it.notification.smallIcon?.resId == com.everyonesoftware.audio.R.drawable.ic_audio_notification
        } }
        onMain {
            assertTrue(browser.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))
            assertTrue(browser.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
            assertFalse("Music controls must not offer podcast-style rewind", browser.isCommandAvailable(Player.COMMAND_SEEK_BACK))
            assertFalse("Music controls must not offer podcast-style fast-forward", browser.isCommandAvailable(Player.COMMAND_SEEK_FORWARD))
            // The phone's progress slider must keep working.
            assertTrue(browser.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
        }
        onMain { browser.seekToNextMediaItem() }
        until { graph.session.value?.index == 1 }
        onMain { browser.seekToNextMediaItem() }
        until { graph.session.value?.index == 2 }
        onMain { browser.seekToPreviousMediaItem() }
        until { graph.session.value?.index == 1 }
        onMain { browser.pause() }
        until { !graph.playback.value.playing }
        assertEquals(saved.queueId, graph.session.value!!.queueId)
        assertEquals(saved.queue, graph.session.value!!.queue)
    }

    @Test fun legacyPlatformBrowserCanBrowseAndPlayFromMediaId() {
        val connected = java.util.concurrent.CountDownLatch(1)
        lateinit var legacy: android.media.browse.MediaBrowser
        onMain {
            legacy = android.media.browse.MediaBrowser(app, ComponentName(app, PlaybackService::class.java),
                object : android.media.browse.MediaBrowser.ConnectionCallback() {
                    override fun onConnected() { connected.countDown() }
                }, null)
            legacy.connect()
        }
        try {
            assertTrue(connected.await(15, TimeUnit.SECONDS))
            val items = java.util.concurrent.LinkedBlockingQueue<List<android.media.browse.MediaBrowser.MediaItem>>()
            fun legacyChildren(id: String): List<android.media.browse.MediaBrowser.MediaItem> {
                onMain { legacy.subscribe(id, object : android.media.browse.MediaBrowser.SubscriptionCallback() {
                    override fun onChildrenLoaded(parentId: String, children: MutableList<android.media.browse.MediaBrowser.MediaItem>) { items.offer(children) }
                }) }
                return requireNotNull(items.poll(15, TimeUnit.SECONDS))
            }
            val folders = legacyChildren(onMain { legacy.root })
            val playlists = legacyChildren(folders.single { it.description.title == "Playlists" }.mediaId!!)
            val collection = playlists.single()
            assertTrue(collection.isBrowsable)
            assertFalse(collection.isPlayable)
            val contents = legacyChildren(collection.mediaId!!)
            assertEquals(listOf("Play", "Shuffle play"), contents.take(2).map { it.description.title.toString() })
            assertEquals(5, contents.size)
            assertTrue(contents.all { it.isPlayable && !it.isBrowsable })
            val controller = onMain { android.media.session.MediaController(app, legacy.sessionToken) }
            onMain { controller.transportControls.playFromMediaId(contents.first().mediaId, null) }
            until { graph.playback.value.playing }
            assertEquals(3, graph.session.value!!.queue.size)
            assertFalse(graph.session.value!!.shuffle)
            assertEquals(fixtureSongs.map { it.id }, graph.session.value!!.queue.map { it.id })
            val saved = graph.session.value!!
            val state = onMain { requireNotNull(controller.playbackState) }
            val actions = state.actions

            assertTrue(actions and android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT != 0L)
            assertTrue(actions and android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L)
            assertEquals("No rewind action for the car to mistake for previous", 0L,
                actions and android.media.session.PlaybackState.ACTION_REWIND)
            assertEquals("No fast-forward action for the car to mistake for next", 0L,
                actions and android.media.session.PlaybackState.ACTION_FAST_FORWARD)
            onMain { controller.transportControls.skipToNext() }
            until { graph.session.value?.index == 1 && graph.session.value?.played == listOf(0) && onMain { browser.isPlaying } }
            onMain { controller.transportControls.skipToNext() }
            until { graph.session.value?.index == 2 && graph.session.value?.played == listOf(0, 1) && onMain { browser.isPlaying } }
            // Legacy previous follows normal music behavior: restart after the threshold,
            // otherwise select the previous occurrence. Start at zero to test track skipping.
            onMain { controller.transportControls.seekTo(0) }
            until { onMain { requireNotNull(controller.playbackState).position } < 1000 }
            onMain { controller.transportControls.skipToPrevious() }
            until { graph.session.value?.index == 1 && graph.session.value?.played == listOf(0, 1, 2) }
            onMain { controller.transportControls.pause() }
            until { !graph.playback.value.playing }
            onMain { controller.transportControls.play() }
            until { graph.playback.value.playing }
            assertEquals(saved.queueId, graph.session.value!!.queueId)
            assertEquals(saved.queue, graph.session.value!!.queue)
        } finally { onMain { legacy.disconnect() } }
    }
}
