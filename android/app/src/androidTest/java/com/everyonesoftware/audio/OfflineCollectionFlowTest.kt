package com.everyonesoftware.audio

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.everyonesoftware.audio.data.*
import com.everyonesoftware.audio.playback.PlaybackService
import com.everyonesoftware.audio.ui.AudioViewModel
import com.everyonesoftware.audio.ui.Screen
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class OfflineCollectionFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun savedPlaylistArtworkAndOrderedPlaybackSurviveActivityRestartWithoutServerRequests() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<AudioApplication>()
        val graph = app.graph
        graph.bootstrap()
        val previous = graph.credentials.value
        val originalSettings = graph.songCache.state.value.settings
        val server = MockWebServer()
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val png = try { ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() } }
            finally { bitmap.recycle() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.requestUrl?.encodedPath?.endsWith("/getCoverArt.view") == true)
                    MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(png))
                else MockResponse().setResponseCode(503)
        }
        server.start()
        val credentials = Credentials(Connection("collection-flow-${System.nanoTime()}", "Collection fixture", server.url("/").toString(), "fixture"), "test-only-password")
        val first = Song("first", "First fixture", "Artist", coverArtId = "cover", durationSeconds = 30)
        val second = Song("second", "Second fixture", "Artist", coverArtId = "cover", durationSeconds = 30)
        val collection = OfflineCollection("playlist", "driving", "Driving offline", coverArtId = "cover", songs = listOf(second, first, second))
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            graph.songCache.setSettings(originalSettings.copy(offline = false, autoSave = true, prefetch = 0))
            graph.store.saveConnection(credentials)
            graph.activate(credentials.connection.id)
            val dataBytes = 30 * 8000 * 2
            val wav = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + dataBytes); put("WAVEfmt ".toByteArray()); putInt(16)
                putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(dataBytes)
            }.array()
            listOf(first, second).forEach { song ->
                val source = graph.songCache.factory(credentials, song, DataSource.Factory { ByteArrayDataSource(wav) }).createDataSource()
                source.open(DataSpec.Builder().setUri(server.url("/stream-${song.id}").toString()).build())
                try { val buffer = ByteArray(8192); while (source.read(buffer, 0, buffer.size) != -1) { } }
                finally { source.close() }
            }
            graph.songCache.downloadCollection(credentials, collection)
            assertNotNull(graph.artworkCache.load(credentials, "cover"))
            graph.songCache.setSettings(originalSettings.copy(offline = true))
            val requestsBeforeOffline = server.requestCount
            scenario = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(20_000) { compose.onAllNodesWithText("Search playlists, albums, and songs").fetchSemanticsNodes().isNotEmpty() }
            scenario.onActivity { ViewModelProvider(it)[AudioViewModel::class.java].showScreen(Screen.OFFLINE) }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("offline-playlist-driving").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("offline-playlist-driving").performClick()
            compose.onNodeWithText("1. Second fixture").assertIsDisplayed()
            compose.onNodeWithText("3. Second fixture").performScrollTo().assertIsDisplayed()
            scenario.close()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(20_000) { compose.onAllNodesWithText("Search playlists, albums, and songs").fetchSemanticsNodes().isNotEmpty() }
            scenario.onActivity { ViewModelProvider(it)[AudioViewModel::class.java].showScreen(Screen.OFFLINE) }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("offline-playlist-driving").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("offline-playlist-driving").performClick()
            compose.onNodeWithTag("offline-collection-play").assertIsEnabled().performClick()
            compose.waitUntil(20_000) { graph.playback.value.playing }
            assertEquals(collection.songs.map { it.id }, graph.session.value!!.queue.map { it.id })
            assertFalse(graph.session.value!!.shuffle)
            assertEquals("Driving offline", graph.session.value!!.playlistName)
            compose.waitUntil(15_000) {
                val pixels = compose.onRoot().captureToImage().toPixelMap()
                var green = 0
                for (y in 0 until pixels.height step 4) for (x in 0 until pixels.width step 4) {
                    val color = pixels[x, y]
                    if (color.green > 0.7f && color.red < 0.2f && color.blue < 0.2f) green++
                }
                green > 100
            }
            val evidence = File(app.getExternalFilesDir(null), "evidence").apply { mkdirs() }
            compose.onRoot().captureToImage().asAndroidBitmap().let { image ->
                try { File(evidence, "offline-collection-artwork-player.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                finally { image.recycle() }
            }
            compose.onNodeWithTag("player-toggle").performClick()
            compose.waitUntil(10_000) { !graph.playback.value.playing }
            assertEquals("Offline collection/artwork contacted the server", requestsBeforeOffline, server.requestCount)
        } finally {
            scenario?.close()
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync { app.stopService(Intent(app, PlaybackService::class.java)) }
            graph.store.removeConnection(credentials.connection.id)
            if (previous != null) graph.activate(previous.connection.id)
            graph.songCache.setSettings(originalSettings)
            server.shutdown()
        }
    }
}
