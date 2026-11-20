package com.everyonesoftware.audio.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.everyonesoftware.audio.AudioGraph
import com.everyonesoftware.audio.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ArtistViewModelTest {
    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun until(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!condition()) { check(System.nanoTime() < deadline) { "Artist state timed out" }; Thread.sleep(20) }
    }
    private fun ok(fields: String) = MockResponse().setBody("""{"subsonic-response":{"status":"ok",$fields}}""")
    private class Fixture(val server: MockWebServer, val graph: AudioGraph, val model: AudioViewModel, val account: String)
    private fun fixture(block: suspend (Fixture) -> Unit) = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when (request.requestUrl!!.encodedPath.substringAfterLast('/')) {
                "getArtists.view" -> ok(""""artists":{"index":[{"artist":[{"id":"artist","name":"Artist"}]}]}""")
                "getPlaylists.view" -> ok(""""playlists":{}""")
                "getAlbumList2.view" -> ok(""""albumList2":{}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val graph = AudioGraph(app)
        val account = "artists-vm-${System.nanoTime()}"
        val owner = ViewModelStore()
        try {
            graph.store.saveConnection(Credentials(Connection(account, "Artist fixture", server.url("/").toString(), "fixture"), "test-only"))
            graph.store.selectConnection(account)
            graph.store.saveSession(ListeningSession(account, "old", "Old queue", listOf(Song("old", "Old", "Artist")), queueId = "old-queue"))
            lateinit var model: AudioViewModel
            main { model = AudioViewModel(app, graph, false); owner.put("artist", model) }
            until { !model.state.value.booting && !model.state.value.artistsRefreshing && model.state.value.artists.isNotEmpty() }
            block(Fixture(server, graph, model, account))
        } finally {
            main { owner.clear() }
            graph.store.removeConnection(account)
            server.shutdown()
        }
    }

    @Test fun failedLaterAlbumPublishesNoPartialSongsThenExplicitRetryAndUnavailableControllerKeepQueue() = fixture { f ->
        val fail = AtomicBoolean(true)
        val gate = CountDownLatch(1)
        val release = CountDownLatch(1)
        val detailRequests = AtomicInteger()
        f.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath.substringAfterLast('/')) {
                "getArtist.view" -> { detailRequests.incrementAndGet(); ok(""""artist":{"id":"artist","name":"Artist","album":[{"id":"b","name":"B"},{"id":"a","name":"A"}]}""") }
                "getAlbum.view" -> if (request.requestUrl!!.queryParameter("id") == "b") ok(""""album":{"id":"b","name":"B","song":[{"id":"two","title":"Two"},{"id":"two","title":"Two"}]}""")
                    else { gate.countDown(); check(release.await(15, TimeUnit.SECONDS)); if (fail.get()) MockResponse().setResponseCode(503) else ok(""""album":{"id":"a","name":"A","song":[{"id":"one","title":"One"}]}""") }
                else -> MockResponse().setResponseCode(404)
            }
        }
        val previous = f.graph.session.value
        try {
            main { f.model.showScreen(Screen.ALL_ARTISTS); f.model.openArtist("artist") }
            assertTrue(gate.await(10, TimeUnit.SECONDS))
            assertTrue(f.model.state.value.artistLoading)
            assertNull(f.model.state.value.artistContents)
            main { f.model.playArtist("artist"); f.model.playArtist("artist", true) }
            assertEquals(previous, f.graph.session.value)
            assertEquals(1, detailRequests.get())
            release.countDown()
            until { !f.model.state.value.artistLoading && f.model.state.value.artistError != null }
            assertNull(f.model.state.value.artistContents)
            main { f.model.playArtist("artist") }
            assertEquals(previous, f.graph.store.session(f.account))
            fail.set(false)
            main { f.model.openArtist("artist") }
            until { !f.model.state.value.artistLoading && f.model.state.value.artistContents != null }
            assertEquals(listOf("two", "two", "one"), f.model.state.value.artistContents!!.songs.map { it.id })
            assertEquals(Screen.ALL_ARTISTS, f.model.state.value.backDestination)
            main { f.model.playArtist("artist") }
            until { !f.model.state.value.loading && f.model.state.value.error != null }
            assertEquals("Controller must be acquired before replacing saved queue", previous, f.graph.store.session(f.account))
            val count = f.server.requestCount
            val settings = f.graph.songCache.state.value.settings
            f.graph.songCache.setSettings(settings.copy(offline = true))
            try {
                main { f.model.playArtist("artist"); f.model.openArtist("artist"); f.model.loadArtists() }
                assertFalse(f.model.state.value.artistLoading)
                assertNull(f.model.state.value.artistContents)
                assertNotNull(f.model.state.value.artistError)
                assertEquals(count, f.server.requestCount)
                assertEquals(previous, f.graph.session.value)
            } finally { f.graph.songCache.setSettings(settings) }
        } finally { release.countDown() }
    }

    @Test fun delayedOldAccountDetailCannotPopulateNewAccountWithSameArtistId() = fixture { f ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val newAccount = "artists-new-${System.nanoTime()}"
        f.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val user = request.requestUrl!!.queryParameter("u")
                return when (request.requestUrl!!.encodedPath.substringAfterLast('/')) {
                    "getArtist.view" -> {
                        if (user == "fixture") { entered.countDown(); check(release.await(15, TimeUnit.SECONDS)) }
                        ok(""""artist":{"id":"artist","name":"$user","album":[]}""")
                    }
                    "getArtists.view" -> ok(""""artists":{"index":[{"artist":[{"id":"artist","name":"$user"}]}]}""")
                    "getPlaylists.view" -> ok(""""playlists":{}""")
                    "getAlbumList2.view" -> ok(""""albumList2":{}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        try {
            f.graph.store.saveConnection(Credentials(Connection(newAccount, "New account", f.server.url("/").toString(), "new-user"), "test-only"))
            main { f.model.showScreen(Screen.SEARCH); f.model.setArtistQuery("old query"); f.model.openArtist("artist") }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            main { f.model.switchConnection(newAccount) }
            until { f.model.state.value.connection?.id == newAccount && !f.model.state.value.artistsRefreshing && f.model.state.value.artists.isNotEmpty() }
            assertNull(f.model.state.value.selectedArtist)
            assertNull(f.model.state.value.artistContents)
            assertFalse(f.model.state.value.artistLoading)
            assertEquals("", f.model.state.value.artistCollectionQuery)
            assertEquals("", f.model.state.value.searchQuery)
            main { f.model.showScreen(Screen.ALL_ARTISTS); f.model.openArtist("artist") }
            until { f.model.state.value.artistContents?.artist?.name == "new-user" }
            release.countDown()
            Thread.sleep(100)
            assertEquals("new-user", f.model.state.value.selectedArtist?.name)
            assertEquals(Screen.ALL_ARTISTS, f.model.state.value.backDestination)
            assertNull(f.graph.session.value)
            assertNull(f.graph.store.session(newAccount))
        } finally { release.countDown(); f.graph.store.removeConnection(newAccount) }
    }

    @Test fun emptyCatalogReentryDoesNotReloadAndFailedRefreshCanRetryWithoutNavigation() = fixture { f ->
        val requests = AtomicInteger()
        val fail = AtomicBoolean(false)
        f.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.requestUrl!!.encodedPath.endsWith("getArtists.view")) {
                    requests.incrementAndGet()
                    return if (fail.get()) MockResponse().setResponseCode(503) else ok(""""artists":{}""")
                }
                return MockResponse().setResponseCode(404)
            }
        }
        main { f.model.loadArtists() }
        until { !f.model.state.value.artistsRefreshing && f.model.state.value.artists.isEmpty() }
        main { f.model.showScreen(Screen.ALL_ARTISTS); f.model.showScreen(Screen.SEARCH); f.model.showScreen(Screen.ALL_ARTISTS) }
        assertEquals(1, requests.get())
        fail.set(true)
        main { f.model.refreshCurrentPage() }
        until { !f.model.state.value.artistsRefreshing && f.model.state.value.artistsError != null }
        assertEquals(Screen.ALL_ARTISTS, f.model.state.value.screen)
        fail.set(false)
        main { f.model.loadArtists() }
        until { !f.model.state.value.artistsRefreshing && f.model.state.value.artistsError == null }
        assertEquals(3, requests.get())
        assertEquals("old-queue", f.graph.session.value!!.queueId)
    }
}
