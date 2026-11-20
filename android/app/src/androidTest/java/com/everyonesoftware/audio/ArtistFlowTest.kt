package com.everyonesoftware.audio

import android.content.ComponentName
import android.content.Intent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.everyonesoftware.audio.data.*
import com.everyonesoftware.audio.playback.PlaybackService
import com.everyonesoftware.audio.playback.CarMediaIds
import com.everyonesoftware.audio.ui.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ArtistFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<AudioApplication>()
    private fun ok(fields: String) = MockResponse().setHeader("Content-Type", "application/json").setBody("""{"subsonic-response":{"status":"ok",$fields}}""")
    private fun song(id: String) = """{"id":"$id","title":"Track $id","artist":"Fixture artist","duration":600}"""
    private fun capture(name: String) {
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        try {
            val dir = java.io.File(app.getExternalFilesDir(null), "evidence").apply { mkdirs() }
            java.io.File(dir, "$name.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        } finally { image.recycle() }
    }
    private fun serviceQueue(expected: ListeningSession) {
        lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync() }
        val controller = future.get(20, TimeUnit.SECONDS)
        try {
            compose.waitUntil(20_000) {
                var ready = false
                instrumentation.runOnMainSync { ready = controller.mediaItemCount == expected.queue.size && controller.currentMediaItemIndex == expected.index }
                ready
            }
            instrumentation.runOnMainSync {
                assertFalse(controller.shuffleModeEnabled)
                assertEquals(expected.occurrences.map { CarMediaIds.queued(expected.connectionId, expected.queueId, it) }, List(controller.mediaItemCount) { controller.getMediaItemAt(it).mediaId })
            }
        } finally { instrumentation.runOnMainSync { controller.release() } }
    }

    @Test fun librarySearchArtistAlbumBackAndRealServiceKeepAllOccurrences() = runBlocking {
        val graph = app.graph
        graph.bootstrap()
        val previous = graph.credentials.value
        val settings = graph.songCache.state.value.settings
        val requests = CopyOnWriteArrayList<String>()
        val server = MockWebServer()
        val bytes = 600 * 8000 * 2
        val wav = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + bytes); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16); put("data".toByteArray()); putInt(bytes)
        }.array()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                val endpoint = url.encodedPath.substringAfterLast('/')
                requests.add(endpoint)
                return when (endpoint) {
                    "getArtists.view" -> ok(""""artists":{"index":[{"name":"F","artist":[{"id":"artist","name":"Fixture artist","albumCount":99}]}]}""")
                    "getArtist.view" -> ok(""""artist":{"id":"artist","name":"Fixture artist","album":[{"id":"b","name":"Second album"},{"id":"a","name":"First album"}]}""")
                    "getAlbumList2.view" -> ok(""""albumList2":{"album":[]}""")
                    "getAlbum.view" -> if (url.queryParameter("id") == "b") ok(""""album":{"id":"b","name":"Second album","artist":"Fixture artist","song":[${song("two")},${song("one")},${song("two")}]}""")
                        else ok(""""album":{"id":"a","name":"First album","artist":"Fixture artist","song":[${song("three")}]}""")
                    "getPlaylists.view" -> ok(""""playlists":{"playlist":[]}""")
                    "search3.view" -> ok(""""searchResult3":{"song":[]}""")
                    "stream.view" -> MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(wav))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val credentials = Credentials(Connection("artist-flow-${System.nanoTime()}", "Artists fixture", server.url("/").toString(), "fixture"), "test-only")
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            graph.songCache.setSettings(settings.copy(offline = false, prefetch = 0))
            graph.store.saveConnection(credentials)
            graph.activate(credentials.connection.id)
            graph.store.setSelectedLibraries(credentials.connection.id, setOf("unchanged"))
            scenario = ActivityScenario.launch(MainActivity::class.java)
            lateinit var model: AudioViewModel
            scenario.onActivity { model = ViewModelProvider(it)[AudioViewModel::class.java] }
            compose.waitUntil(25_000) { !model.state.value.booting }
            compose.onNodeWithTag("library-content").performScrollToNode(hasTestTag("view-all-artists"))
            compose.onNodeWithTag("view-all-artists").performClick()
            compose.waitUntil(20_000) { model.state.value.artists.isNotEmpty() }
            compose.onNodeWithTag("artist-card-artist").performClick()
            compose.waitUntil(20_000) { model.state.value.artistContents != null && !model.state.value.artistLoading }
            compose.onNodeWithTag("artist-summary").assertTextEquals("2 albums · 4 songs")
            capture("artist-detail-albums")
            compose.onNodeWithTag("album-card-b").performScrollTo().performClick()
            assertEquals(Screen.ARTIST, model.state.value.backDestination)
            compose.onNodeWithContentDescription("Back").performClick()
            assertEquals(Screen.ALL_ARTISTS, model.state.value.backDestination)
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithContentDescription("Back").performClick()
            scenario.onActivity { model.showScreen(Screen.SEARCH); model.search("Fixture artist") }
            compose.waitUntil(20_000) { !model.state.value.searchLoading }
            compose.onNodeWithTag("search-artist-artist").performScrollTo().performClick()
            compose.waitUntil(20_000) { !model.state.value.artistLoading }
            assertEquals(Screen.SEARCH, model.state.value.backDestination)
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            assertEquals(Screen.SEARCH, model.state.value.screen)
            compose.onNodeWithTag("search-artist-artist").performScrollTo().performClick()
            compose.waitUntil(20_000) { !model.state.value.artistLoading }
            compose.onNodeWithTag("artist-all-songs").performScrollTo().performClick()
            compose.onNodeWithTag("artist-song-2").performScrollTo()
            capture("artist-detail-songs")
            compose.onNodeWithTag("artist-song-2").performScrollTo().performClick()
            compose.waitUntil(25_000) { graph.playback.value.playing && graph.session.value?.sourceType == "artist" }
            val selected = graph.session.value!!
            assertEquals(listOf("two", "one", "two", "three"), selected.queue.map { it.id })
            assertEquals(2, selected.index)
            serviceQueue(selected)
            scenario.onActivity { model.togglePlay() }
            compose.waitUntil(15_000) { !graph.playback.value.playing }
            for (shuffle in listOf(false, false, true, true, false)) {
                scenario.onActivity { model.showScreen(Screen.SEARCH); model.openArtist("artist") }
                compose.waitUntil(20_000) { !model.state.value.artistLoading }
                val before = graph.session.value!!.queueId
                val beforeRequests = requests.count { it == "getAlbum.view" || it == "getArtist.view" }
                compose.onNodeWithTag(if (shuffle) "artist-shuffle" else "artist-play").performScrollTo().performClick()
                compose.waitUntil(25_000) { graph.playback.value.playing && graph.session.value?.queueId != before }
                val session = graph.session.value!!
                assertEquals(shuffle, session.shuffle)
                assertEquals(listOf("one", "three", "two", "two"), session.queue.map { it.id }.sorted())
                if (!shuffle) assertEquals(listOf("two", "one", "two", "three"), session.queue.map { it.id })
                assertEquals(beforeRequests, requests.count { it == "getAlbum.view" || it == "getArtist.view" })
                serviceQueue(session)
                scenario.onActivity { model.togglePlay() }
                compose.waitUntil(15_000) { !graph.playback.value.playing }
            }
            assertEquals(setOf("unchanged"), graph.store.selectedLibraries(credentials.connection.id))
            assertEquals(graph.session.value!!.queue.map { it.id }, graph.store.session(credentials.connection.id)!!.queue.map { it.id })
            assertFalse(requests.any { it.contains("createPlaylist") })
            val paused = graph.session.value!!
            scenario.close()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { model = ViewModelProvider(it)[AudioViewModel::class.java] }
            compose.waitUntil(25_000) { !model.state.value.booting && model.state.value.session?.queueId == paused.queueId }
            assertEquals(Screen.PLAYLISTS, model.state.value.screen)
            assertEquals(paused.queue, model.state.value.session!!.queue)
            assertEquals(paused.index, model.state.value.session!!.index)
            assertEquals("artist", model.state.value.session!!.sourceType)
            assertFalse(graph.playback.value.playing)
            serviceQueue(graph.session.value!!)
        } finally {
            scenario?.close()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { app.stopService(Intent(app, PlaybackService::class.java)) }
            graph.store.removeConnection(credentials.connection.id)
            if (previous != null) graph.activate(previous.connection.id)
            graph.songCache.setSettings(settings)
            server.shutdown()
        }
    }
}
