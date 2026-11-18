package com.everyonesoftware.audio

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.everyonesoftware.audio.playback.PlaybackService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.everyonesoftware.audio.data.ListeningSession
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.*
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/** All network, account and audio data are local fixtures. This exercises the production activity and service. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NativeFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val requests = ConcurrentLinkedQueue<RecordedRequest>()
    private val ratings = ConcurrentHashMap<String, Int>()
    private val server = MockWebServer()
    private val app get() = ApplicationProvider.getApplicationContext<AudioApplication>()
    private val graph get() = app.graph
    private val coldProbe get() = InstrumentationRegistry.getArguments().getString("coldRestoreOnly") == "true"
    private val preserveFixture get() = InstrumentationRegistry.getArguments().getString("preserveSession") == "true"
    // Native software-emulator UI/capture steps can exceed 30 seconds while audio keeps playing.
    private val fixtureDurationSeconds = 180
    private fun capture(name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(400, 5000)
        // Capture the actual tested Compose root. UiAutomation can return an old display buffer
        // immediately after fast fixture-driven routing, despite up-to-date semantics.
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val directory = java.io.File(app.getExternalFilesDir(null), "evidence").apply { mkdirs() }
        java.io.File(directory, "$name.png").outputStream().use { output ->
            check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
        }
        image.recycle()
    }
    private fun ok(content: String = "") = MockResponse().setHeader("Content-Type", "application/json").setBody("{\"subsonic-response\":{\"status\":\"ok\",\"version\":\"1.16.1\"$content}}")
    private fun song(id: String) = "{\"id\":\"$id\",\"title\":\"Fixture $id\",\"artist\":\"Audio test\",\"album\":\"Local fixture\",\"duration\":$fixtureDurationSeconds,\"userRating\":${ratings[id] ?: 0}}"
    private fun until(condition: () -> Boolean) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20)
        while (!condition()) { check(System.nanoTime() < deadline) { "Native fixture state timed out" }; Thread.sleep(25) }
    }

    private fun assertServiceOccurrenceQueue(saved: ListeningSession) {
        lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
        }
        val controller = future.get(15, java.util.concurrent.TimeUnit.SECONDS)
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                Assert.assertEquals(saved.queue.size, controller.mediaItemCount)
                Assert.assertFalse("Queue order is materialized, not Media3-shuffled", controller.shuffleModeEnabled)
                val ids = List(controller.mediaItemCount) { controller.getMediaItemAt(it).mediaId }
                Assert.assertEquals(saved.queue.indices.map {
                    com.everyonesoftware.audio.playback.CarMediaIds.queued(saved.connectionId, saved.queueId, it)
                }, ids)
                Assert.assertEquals("Duplicate songs retain distinct occurrence media IDs", ids.size, ids.toSet().size)
            }
        } finally { InstrumentationRegistry.getInstrumentation().runOnMainSync { controller.release() } }
    }

    @Before fun serveIsolatedMusicFixture() {
        if (coldProbe) return
        val samples = fixtureDurationSeconds * 8000
        val bytes = samples * 2
        val wav = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + bytes); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(bytes)
        }.array()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                val url = request.requestUrl ?: return MockResponse().setResponseCode(400)
                if (url.queryParameter("u") != "native-fixture-user" || url.queryParameter("t").isNullOrBlank() || url.queryParameter("p") != null) return MockResponse().setResponseCode(401)
                return when (url.encodedPath.substringAfterLast('/')) {
                    "ping.view" -> ok()
                    "getAlbumList2.view" -> ok(",\"albumList2\":{\"album\":[]}")
                    "search3.view" -> ok(",\"searchResult3\":{\"song\":[${song("s1")}]}")
                    "getMusicFolders.view" -> ok(",\"musicFolders\":{\"musicFolder\":[{\"id\":\"main\",\"name\":\"Fixture library\"}]}")
                    "getPlaylists.view" -> ok(",\"playlists\":{\"playlist\":[{\"id\":\"routine\",\"name\":\"Fixture routine\",\"songCount\":3}]}")
                    "getPlaylist.view" -> ok(",\"playlist\":{\"id\":\"routine\",\"name\":\"Fixture routine\",\"entry\":[${listOf("s1", "s2", "s1", "s3").joinToString(",") { song(it) }}]}")
                    "setRating.view" -> { ratings[url.queryParameter("id")!!] = url.queryParameter("rating")!!.toInt(); ok() }
                    "getSong.view" -> ok(",\"song\":${song(url.queryParameter("id")!!)}")
                    "stream.view" -> MockResponse().setHeader("Content-Type", "audio/wav").setHeader("Cache-Control", "no-store").setBody(Buffer().write(wav))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After fun removeOnlyFixtureAccount() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            app.stopService(Intent(app, PlaybackService::class.java))
        }
        if (!preserveFixture) runBlocking {
            graph.store.connections().filter { it.username == "native-fixture-user" }.forEach { graph.store.removeConnection(it.id) }
            Assert.assertTrue("Removed account retained active credentials", graph.credentials.value?.connection?.username != "native-fixture-user")
        }
        if (!coldProbe) server.shutdown()
    }

    @Test fun realConnectionShuffleRatingBackgroundPlaybackAndActivityReturn() {
        if (coldProbe) {
            val expected = Json.decodeFromString<ListeningSession>(java.io.File(app.getExternalFilesDir(null), "evidence/expected-session.json").readText())
            compose.waitUntil(15_000) { graph.session.value != null }
            // Graph bootstrap restores the session before the activity finishes loading its
            // connection/UI state. Wait for that boundary, not only the earlier graph write.
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("mini-player").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("bottom-navigation").assertDoesNotExist()
            compose.onNodeWithText("Search playlists, albums, and songs").assertIsDisplayed()
            compose.onNodeWithTag("player-toggle").assertDoesNotExist()
            compose.onNodeWithTag("mini-player").assertIsDisplayed()
            Assert.assertEquals(expected, graph.session.value)
            Assert.assertFalse(graph.playback.value.playing)
            capture("native-cold-restored")
            compose.onNodeWithText(expected.queue[expected.index].title).performClick()
            compose.onNodeWithTag("player-toggle").assertIsDisplayed()
            Assert.assertEquals(expected, graph.session.value)
            Assert.assertFalse(graph.playback.value.playing)
            return
        }
        val baseUrl = server.url("/").toString()
        // A standalone cold run can still be restoring when the activity first becomes idle.
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Connect to Navidrome").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Connect to Navidrome").assertIsDisplayed()
        capture("native-welcome")
        compose.onNodeWithText("Connect to Navidrome").performClick()
        compose.onNodeWithTag("connect-name").performTextInput("Local fixture")
        compose.onNodeWithTag("connect-server-url").performTextInput(baseUrl)
        compose.onNodeWithTag("connect-username").performTextInput("native-fixture-user")
        compose.onNodeWithTag("connect-password").performTextInput("test-only-password")
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val activity = compose.activity
            (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
        compose.onNodeWithTag("connect-submit").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("library-continue").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("library-continue").assertIsDisplayed()
        capture("native-libraries")
        compose.onNodeWithTag("library-continue").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Choose a playlist").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Choose a playlist").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("playlist-card-routine").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Search playlists, albums, and songs").performClick()
        compose.onNodeWithTag("library-search").performTextInput("s1")
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Fixture s1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Fixture s1").performClick()
        compose.waitUntil(20_000) { graph.playback.value.playing }
        Assert.assertEquals("song", graph.session.value!!.sourceType)
        Assert.assertEquals(listOf("s1"), graph.session.value!!.queue.map { it.id })
        Assert.assertFalse(graph.session.value!!.shuffle)
        compose.onNodeWithTag("player-toggle").performClick()
        compose.waitUntil(10_000) { !graph.playback.value.playing }
        compose.onNodeWithContentDescription("Back").performClick()
        val beforeBrowsing = graph.session.value
        compose.onNodeWithTag("playlist-card-routine").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Fixture s2").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Fixture s2").performScrollTo().assertIsDisplayed()
        // The service may persist its final pause position asynchronously; browsing must
        // preserve the queue identity, order, source and paused state.
        Assert.assertEquals(beforeBrowsing?.copy(positionMs = graph.session.value!!.positionMs), graph.session.value)
        Assert.assertFalse(graph.playback.value.playing)
        capture("native-playlist-details")
        compose.onNodeWithTag("playlist-play").performScrollTo().performClick()
        compose.waitUntil(20_000) { graph.playback.value.playing }
        val ordered = graph.session.value!!
        Assert.assertEquals("playlist", ordered.sourceType)
        Assert.assertEquals(listOf("s1", "s2", "s1", "s3"), ordered.queue.map { it.id })
        Assert.assertFalse(ordered.shuffle)
        assertServiceOccurrenceQueue(ordered)
        Assert.assertNotEquals(beforeBrowsing!!.queueId, ordered.queueId)
        // Mini-player transport and the detail mode selector must not rebuild a paused queue.
        compose.onNodeWithTag("player-toggle").performClick()
        compose.waitUntil(10_000) { !graph.playback.value.playing }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("playlist-card-routine").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Fixture s2").fetchSemanticsNodes().isNotEmpty() }
        // Repeated ordered Play must restart even when its contents are identical.
        compose.onNodeWithTag("playlist-play").performScrollTo().performClick()
        compose.waitUntil(20_000) { graph.playback.value.playing && graph.session.value?.queueId != ordered.queueId }
        val restarted = graph.session.value!!
        Assert.assertEquals(ordered.queue, restarted.queue)
        Assert.assertFalse(restarted.shuffle)
        Assert.assertEquals(0, restarted.index)
        compose.onNodeWithTag("player-toggle").performClick()
        compose.waitUntil(10_000) { !graph.playback.value.playing }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("playlist-card-routine").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Fixture s2").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("playlist-shuffle").performScrollTo().performClick()
        Assert.assertEquals(restarted.queueId, graph.session.value!!.queueId)
        Assert.assertEquals(restarted.queue, graph.session.value!!.queue)
        Assert.assertFalse(graph.session.value!!.shuffle)
        Assert.assertFalse(graph.playback.value.playing)
        compose.onNodeWithTag("mini-player-toggle").performClick()
        compose.waitUntil(10_000) { graph.playback.value.playing }
        compose.onNodeWithTag("mini-player-toggle").performClick()
        compose.waitUntil(10_000) { !graph.playback.value.playing }
        Assert.assertEquals(restarted.queueId, graph.session.value!!.queueId)
        Assert.assertEquals(restarted.queue, graph.session.value!!.queue)
        compose.onNodeWithTag("playlist-play").performClick()
        compose.waitUntil(20_000) { graph.playback.value.playing && graph.session.value?.queueId != restarted.queueId }
        val original = graph.session.value!!
        Assert.assertTrue(original.shuffle)
        Assert.assertEquals(listOf("s1", "s1", "s2", "s3"), original.queue.map { it.id }.sorted())
        assertServiceOccurrenceQueue(original)
        Assert.assertEquals(setOf("s1", "s2", "s3"), original.queue.map { it.id }.toSet())
        Assert.assertTrue(requests.any { it.requestUrl?.encodedPath?.endsWith("/stream.view") == true })
        compose.onNodeWithTag("player-rating").performClick()
        compose.onNodeWithTag("rating-4").performClick()
        compose.waitUntil(15_000) { graph.session.value?.queue?.getOrNull(graph.session.value!!.index)?.userRating == 4 }
        compose.onNodeWithTag("rating-4").assertIsSelected()
        compose.onNodeWithText("Done").performClick()
        capture("native-player")
        Assert.assertEquals(4, ratings[original.queue[original.index].id])

        val positionBeforeBackground = graph.playback.value.positionMs
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        until { graph.playback.value.playing && graph.playback.value.positionMs > positionBeforeBackground + 1200 }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("player-toggle").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { !graph.playback.value.playing }
        compose.onNodeWithTag("player-seek").performSemanticsAction(SemanticsActions.SetProgress) { it(5000f) }
        compose.waitUntil(10_000) { graph.session.value?.positionMs in 4900L..5100L }
        val paused = graph.session.value!!
        Assert.assertEquals("Fixture must not finish a track before the recreation check", original.index, paused.index)
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("player-toggle").assertIsDisplayed()
        compose.waitUntil(10_000) { graph.session.value?.positionMs == paused.positionMs }
        Assert.assertFalse(graph.playback.value.playing)
        Assert.assertEquals(original.queue.map { it.id }, graph.session.value!!.queue.map { it.id })
        Assert.assertEquals(original.index, graph.session.value!!.index)
        runBlocking {
            val persisted = graph.store.session(original.connectionId)!!
            Assert.assertEquals(paused.positionMs, persisted.positionMs)
            Assert.assertEquals(original.queue.map { it.id }, persisted.queue.map { it.id })
            Assert.assertEquals(4, persisted.queue[original.index].userRating)
        }
        Assert.assertTrue(requests.any { it.requestUrl?.encodedPath?.endsWith("/setRating.view") == true })
        Assert.assertTrue(requests.any { it.requestUrl?.encodedPath?.endsWith("/getSong.view") == true })
        // Exercise the production edge-to-edge activity, including its real system insets.
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("mini-player-toggle").assertIsDisplayed()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("bottom-navigation").assertDoesNotExist()
        val mini = compose.onNodeWithTag("mini-player").fetchSemanticsNode().boundsInRoot
        Assert.assertEquals(root.left, mini.left, 1f)
        Assert.assertEquals(root.right, mini.right, 1f)
        Assert.assertTrue(mini.bottom <= root.bottom)
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        val edgeX = (root.width - 2).toInt()
        Assert.assertEquals(Color(0xFF354239), pixels[edgeX, mini.center.y.toInt()])

        capture("native-tonal-library")
        compose.onNodeWithTag("mini-player-toggle").performClick()
        compose.waitUntil(10_000) { graph.playback.value.playing }
        compose.onNodeWithTag("mini-player-toggle").performClick()
        compose.waitUntil(10_000) { !graph.playback.value.playing }
        compose.onNodeWithText("Fixture ${original.queue[original.index].id}").performClick()
        compose.onNodeWithTag("player-toggle").assertIsDisplayed()
        capture("native-tonal-player")
        // The real service must replay and seek saved audio without another stream request.
        // Emulator images differ in whether their default network is reported as metered.
        val settings = graph.songCache.state.value.settings
        graph.songCache.setSettings(settings.copy(mobilePrefetch = true))
        compose.waitUntil(15_000) { original.queue.all { graph.songCache.complete(graph.credentials.value!!, it.id) } }
        graph.songCache.setSettings(settings.copy(offline = true))
        compose.waitForIdle()
        Assert.assertFalse("Enabling offline resumed a paused session", graph.playback.value.playing)
        val streamsBeforeOfflineReplay = requests.count { it.requestUrl?.encodedPath?.endsWith("/stream.view") == true }
        compose.onNodeWithTag("player-seek").performSemanticsAction(SemanticsActions.SetProgress) { it(12_000f) }
        compose.onNodeWithTag("player-toggle").performClick()
        compose.waitUntil(15_000) { graph.playback.value.playing && graph.playback.value.positionMs >= 11_900 }
        compose.onNodeWithTag("player-toggle").performClick()
        compose.waitUntil(10_000) { !graph.playback.value.playing }
        Assert.assertEquals(streamsBeforeOfflineReplay, requests.count { it.requestUrl?.encodedPath?.endsWith("/stream.view") == true })
        Assert.assertEquals(original.queueId, graph.session.value!!.queueId)
        graph.songCache.setSettings(settings)
        compose.waitForIdle()
        Assert.assertFalse("Leaving offline resumed a paused session", graph.playback.value.playing)
        if (preserveFixture) {
            java.io.File(app.getExternalFilesDir(null), "evidence/expected-session.json")
                .writeText(Json.encodeToString(graph.session.value!!))
        }
    }
}
