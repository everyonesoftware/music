package com.everyonesoftware.audio.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.everyonesoftware.audio.AudioGraph
import com.everyonesoftware.audio.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioViewModelTest {
    @Test fun collectionNavigationPreservesQueriesLayoutsAndSessionAndRestoresLayoutsInNewModel() = runBlocking {
        withBrowseFixture { model, graph, _ ->
            val originalLayout = graph.store.collectionLayout(CollectionKind.PLAYLISTS)
            val originalAlbumLayout = graph.store.collectionLayout(CollectionKind.ALBUMS)
            val owner = ViewModelStore()
            val session = graph.session.value
            try {
                onMain {
                    model.showScreen(Screen.ALL_PLAYLISTS)
                    model.setCollectionQuery(CollectionKind.PLAYLISTS, "Browse")
                    model.setCollectionQuery(CollectionKind.ALBUMS, "Artist")
                    model.setCollectionLayout(CollectionKind.PLAYLISTS, CollectionLayout.LIST)
                    model.setCollectionLayout(CollectionKind.ALBUMS, CollectionLayout.GRID)
                    model.openPlaylist("p")
                }
                until { !model.state.value.playlistLoading }
                assertEquals(Screen.ALL_PLAYLISTS, model.state.value.backDestination)
                onMain { model.showScreen(Screen.SETTINGS) }
                assertEquals(Screen.PLAYLIST, model.state.value.backDestination)
                onMain { model.showScreen(model.state.value.backDestination) }
                assertEquals(Screen.ALL_PLAYLISTS, model.state.value.backDestination)
                onMain { model.showScreen(model.state.value.backDestination); model.refreshCollection(CollectionKind.PLAYLISTS) }
                until { !model.state.value.playlistsRefreshing }
                assertEquals(Screen.ALL_PLAYLISTS, model.state.value.screen)
                assertEquals("Browse", model.state.value.playlistCollectionQuery)
                assertEquals("Artist", model.state.value.albumCollectionQuery)
                assertEquals(CollectionLayout.LIST, model.state.value.playlistCollectionLayout)
                assertEquals(CollectionLayout.GRID, model.state.value.albumCollectionLayout)
                // Read the actual DataStore before constructing a new process-like owner.
                until { runBlocking { graph.store.collectionLayout(CollectionKind.PLAYLISTS) == CollectionLayout.LIST &&
                    graph.store.collectionLayout(CollectionKind.ALBUMS) == CollectionLayout.GRID } }
                lateinit var restored: AudioViewModel
                onMain { restored = AudioViewModel(model.getApplication(), graph, false); owner.put("restored", restored) }
                until { !restored.state.value.booting }
                assertEquals(Screen.PLAYLISTS, restored.state.value.screen)
                assertEquals(CollectionLayout.LIST, restored.state.value.playlistCollectionLayout)
                assertEquals(CollectionLayout.GRID, restored.state.value.albumCollectionLayout)
                assertEquals("", restored.state.value.playlistCollectionQuery)
                assertEquals(session, graph.session.value)
                assertEquals(session, graph.store.session("vm-browse-fixture"))
                assertFalse(model.state.value.playing)
            } finally {
                onMain { owner.clear() }
                graph.store.setCollectionLayout(CollectionKind.PLAYLISTS, originalLayout)
                graph.store.setCollectionLayout(CollectionKind.ALBUMS, originalAlbumLayout)
            }
        }
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun until(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(12)
        while (!predicate()) { check(System.nanoTime() < deadline) { "Timed out waiting for view-model state" }; Thread.sleep(20) }
    }
    private fun ok(content: String = "") = MockResponse().setHeader("Content-Type", "application/json").setBody("{\"subsonic-response\":{\"status\":\"ok\",\"version\":\"1.16.1\"$content}}")

    @Test fun connectionChecksServerThenSavesCredentialsAndSelectedLibraries() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val graph = AudioGraph(app)
        val server = MockWebServer()
        server.enqueue(ok())
        server.enqueue(ok(",\"musicFolders\":{\"musicFolder\":[{\"id\":\"1\",\"name\":\"Music\"},{\"id\":\"2\",\"name\":\"Archive\"}]}"))
        server.start()
        val baseUrl = server.url("/").toString()
        val owner = ViewModelStore()
        lateinit var model: AudioViewModel
        try {
            onMain { model = AudioViewModel(app, graph, false); owner.put("fixture", model) }
            until { !model.state.value.booting }
            onMain { model.connect("Fixture", baseUrl, "vm-fixture-user", "test-only-password") }
            until { model.state.value.screen == Screen.LIBRARIES || model.state.value.error != null }
            assertNull(model.state.value.error)
            assertEquals(setOf("1", "2"), model.state.value.selectedLibraries)
            assertEquals("vm-fixture-user", graph.credentials.value?.connection?.username)
            onMain { model.setLibrary("2", false); model.saveLibraries() }
            until { model.state.value.screen == Screen.READY || model.state.value.error != null }
            runBlocking { assertEquals(setOf("1"), graph.store.selectedLibraries(graph.credentials.value!!.connection.id)) }
        } finally {
            onMain { owner.clear() }
            runBlocking { graph.store.connections().filter { it.username == "vm-fixture-user" }.forEach { graph.store.removeConnection(it.id) } }
            server.shutdown()
        }
    }

    @Test fun rejectedCredentialsNeverBecomeASavedConnection() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val graph = AudioGraph(app)
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401))
        server.start()
        val baseUrl = server.url("/").toString()
        val owner = ViewModelStore()
        lateinit var model: AudioViewModel
        try {
            onMain { model = AudioViewModel(app, graph, false); owner.put("fixture", model) }
            until { !model.state.value.booting }
            onMain { model.connect("Rejected", baseUrl, "vm-rejected-user", "test-only-password") }
            until { model.state.value.error != null }
            assertFalse(model.state.value.loading)
            assertEquals(1, server.requestCount)
            runBlocking { assertFalse(graph.store.connections().any { it.username == "vm-rejected-user" }) }
            assertFalse(model.state.value.error!!.contains("test-only-password"))
        } finally { onMain { owner.clear() }; server.shutdown() }
    }

    @Test fun coldStartWithSavedSessionLandsInLibraryAndRestoresQueue() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val graph = AudioGraph(app)
        val id = "vm-returning-fixture"
        val owner = ViewModelStore()
        try {
            graph.store.saveConnection(Credentials(Connection(id, "Fixture", "https://example.invalid/", "vm-returning-user"), "test-only-password"))
            graph.store.selectConnection(id)
            val session = ListeningSession(id, "p1", "Routine", listOf(Song("second", "Second", "Fixture"), Song("first", "First", "Fixture")), 1, 34567, true, 2)
            graph.store.saveSession(session)
            lateinit var model: AudioViewModel
            onMain { model = AudioViewModel(app, graph, false); owner.put("fixture", model) }
            until { !model.state.value.booting }
            assertEquals(Screen.PLAYLISTS, model.state.value.screen)
            assertEquals(session, model.state.value.session)
            assertEquals(34567L, model.state.value.positionMs)
            assertFalse(model.state.value.playing)
        } finally { onMain { owner.clear() }; graph.store.removeConnection(id) }
    }

    @Test fun playlistRefreshUsesContentsNotStaleSummaryAndPreservesQueue() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val graph = AudioGraph(app)
        val server = MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when {
                request.path!!.contains("getPlaylists.view") -> ok(",\"playlists\":{\"playlist\":[{\"id\":\"smart\",\"name\":\"Rated\",\"songCount\":0}]}")
                request.path!!.contains("getPlaylist.view") -> ok(",\"playlist\":{\"id\":\"smart\",\"name\":\"Rated\",\"entry\":[{\"id\":\"s1\",\"title\":\"One\"},{\"id\":\"s1\",\"title\":\"One again\"}]}")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val id = "vm-playlist-count-fixture"
        val owner = ViewModelStore()
        try {
            graph.store.saveConnection(Credentials(Connection(id, "Fixture", server.url("/").toString(), "vm-count-user"), "test-only-password"))
            graph.store.selectConnection(id)
            val session = ListeningSession(id, "smart", "Rated", listOf(Song("old", "In progress", "Fixture")), positionMs = 1234L)
            graph.store.saveSession(session)
            lateinit var model: AudioViewModel
            onMain { model = AudioViewModel(app, graph, false); owner.put("fixture", model) }
            until { !model.state.value.booting }
            onMain { model.loadPlaylists() }
            until { model.state.value.playlists.singleOrNull()?.songCount == 2 && !model.state.value.playlistsRefreshing }
            assertEquals(session, graph.session.value)
            assertEquals(2, graph.store.cachedPlaylists(id).single().songCount)
        } finally {
            onMain { owner.clear() }
            graph.store.removeConnection(id)
            server.shutdown()
        }
    }

    @Test fun openingPlaylistLoadsContentsWithoutChangingListeningSession() = runBlocking {
        withBrowseFixture { model, graph, _ ->
            val session = graph.session.value
            onMain { model.openPlaylist("p") }
            until { !model.state.value.playlistLoading && model.state.value.playlistSongs.isNotEmpty() }
            assertEquals(Screen.PLAYLIST, model.state.value.screen)
            assertEquals("Browse", model.state.value.selectedPlaylist?.name)
            assertEquals("remote", model.state.value.playlistSongs.single().id)
            assertNull(model.state.value.playlistDetailError)
            assertEquals(session, graph.session.value)
            assertEquals(session, graph.store.session("vm-browse-fixture"))
        }
    }

    @Test fun searchDebouncesAndDiscardsStaleServerSongs() = runBlocking {
        withBrowseFixture { model, graph, server ->
            val queries = java.util.concurrent.CopyOnWriteArrayList<String>()
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val query = request.requestUrl!!.queryParameter("query") ?: return MockResponse().setResponseCode(404)
                    queries.add(query)
                    return ok(""", "searchResult3":{"song":[{"id":"$query","title":"$query"}]}""")
                        .setBodyDelay(if (query == "old") 800 else 0, java.util.concurrent.TimeUnit.MILLISECONDS)
                }
            }
            val session = graph.session.value
            onMain { model.search("discarded"); model.search("old") }
            until { queries.contains("old") }
            onMain { model.search("new") }
            until { !model.state.value.searchLoading && model.state.value.searchSongs.singleOrNull()?.id == "new" }
            Thread.sleep(900)
            assertEquals(listOf("old", "new"), queries.toList())
            assertEquals("new", model.state.value.searchQuery)
            assertEquals("new", model.state.value.searchSongs.single().id)
            assertNull(model.state.value.searchError)
            assertEquals(session, graph.session.value)
            onMain { model.search("   ") }
            assertFalse(model.state.value.searchLoading)
            assertTrue(model.state.value.searchSongs.isEmpty())
        }
    }

    @Test fun browseErrorsDoNotChangeQueueAndAccountSwitchResetsBrowseState() = runBlocking {
        withBrowseFixture { model, graph, server ->
            val session = graph.session.value
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = MockResponse().setResponseCode(503)
            }
            onMain {
                model.setCollectionQuery(CollectionKind.PLAYLISTS, "private playlist")
                model.setCollectionQuery(CollectionKind.ALBUMS, "private artist")
                model.openPlaylist("p"); model.search("failure")
            }
            until { model.state.value.playlistDetailError != null && model.state.value.searchError != null }
            assertFalse(model.state.value.playlistLoading)
            assertFalse(model.state.value.searchLoading)
            assertTrue(model.state.value.playlistSongs.isEmpty())
            assertEquals(session, graph.session.value)
            val second = "vm-browse-second"
            try {
                graph.store.saveConnection(Credentials(Connection(second, "Second", server.url("/").toString(), "vm-second-user"), "test-only-password"))
                onMain { model.switchConnection(second) }
                until { model.state.value.connection?.id == second && !model.state.value.loading }
                assertNull(model.state.value.selectedPlaylist)
                assertNull(model.state.value.playlistDetailError)
                assertFalse(model.state.value.playlistLoading)
                assertEquals("", model.state.value.searchQuery)
                assertEquals("", model.state.value.playlistCollectionQuery)
                assertEquals("", model.state.value.albumCollectionQuery)
                assertTrue(model.state.value.albums.isEmpty())
                assertTrue(model.state.value.searchSongs.isEmpty())
                assertNull(model.state.value.searchError)
            } finally { graph.store.removeConnection(second) }
        }
    }

    @Test fun switchingToAccountWithoutSessionLoadsLibraryAlbumsAndImmediateViewAllSharesTheRequest() = runBlocking {
        withBrowseFixture { model, graph, server ->
            until { !model.state.value.albumsRefreshing }
            val previous = graph.session.value
            val albumRequests = java.util.concurrent.atomic.AtomicInteger()
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when {
                    request.path!!.contains("getAlbumList2.view") -> {
                        albumRequests.incrementAndGet()
                        ok(""", "albumList2":{"album":[{"id":"second-album","name":"Second account"}]}""")
                            .setBodyDelay(400, java.util.concurrent.TimeUnit.MILLISECONDS)
                    }
                    request.path!!.contains("getPlaylists.view") -> ok(""", "playlists":{}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val second = "vm-browse-no-session"
            try {
                graph.store.saveConnection(Credentials(Connection(second, "Second", server.url("/").toString(), "vm-second-user"), "test-only-password"))
                onMain { model.switchConnection(second) }
                until { model.state.value.connection?.id == second && !model.state.value.loading }
                assertEquals(Screen.PLAYLISTS, model.state.value.screen)
                // Library must initialize its sibling album section without opening Search or View all.
                assertTrue(model.state.value.albumsRefreshing || model.state.value.albums.isNotEmpty())
                onMain { model.showScreen(Screen.ALL_ALBUMS) }
                until { !model.state.value.albumsRefreshing && model.state.value.albums.singleOrNull()?.id == "second-album" }
                assertEquals(Screen.ALL_ALBUMS, model.state.value.screen)
                assertEquals(1, albumRequests.get())
                assertNull(graph.session.value)
                assertEquals(previous, graph.store.session("vm-browse-fixture"))
                onMain { model.showScreen(Screen.SETTINGS); model.showScreen(model.state.value.backDestination) }
                assertEquals(Screen.ALL_ALBUMS, model.state.value.screen)
                assertFalse(model.state.value.albumsRefreshing)
                Thread.sleep(200)
                assertEquals(1, albumRequests.get())
            } finally { graph.store.removeConnection(second) }
        }
    }

    @Test fun switchingToAccountWithSessionInitializesAlbumsOnViewAllAndKeepsLoadedEmptyCatalog() = runBlocking {
        withBrowseFixture { model, graph, server ->
            until { !model.state.value.albumsRefreshing }
            val albumRequests = java.util.concurrent.atomic.AtomicInteger()
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when {
                    request.path!!.contains("getAlbumList2.view") -> {
                        albumRequests.incrementAndGet()
                        ok(""", "albumList2":{}""")
                    }
                    request.path!!.contains("getPlaylists.view") -> ok(""", "playlists":{}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val second = "vm-browse-empty-session"
            val session = ListeningSession(second, "second", "Second", listOf(Song("second-song", "Second song", "Fixture")), positionMs = 1234L)
            try {
                graph.store.saveConnection(Credentials(Connection(second, "Second", server.url("/").toString(), "vm-second-user"), "test-only-password"))
                graph.store.saveSession(session)
                onMain { model.switchConnection(second) }
                until { model.state.value.connection?.id == second && !model.state.value.loading }
                assertEquals(Screen.PLAYER, model.state.value.screen)
                assertEquals(0, albumRequests.get())
                onMain { model.showScreen(Screen.ALL_ALBUMS) }
                until { albumRequests.get() == 1 && !model.state.value.albumsRefreshing }
                assertTrue(model.state.value.albums.isEmpty())
                assertNull(model.state.value.albumsError)
                onMain { model.showScreen(Screen.SETTINGS); model.showScreen(model.state.value.backDestination) }
                assertEquals(Screen.ALL_ALBUMS, model.state.value.screen)
                assertFalse(model.state.value.albumsRefreshing)
                Thread.sleep(200)
                assertEquals(1, albumRequests.get())
                onMain { model.refreshCollection(CollectionKind.ALBUMS) }
                until { albumRequests.get() == 2 && !model.state.value.albumsRefreshing }
                assertEquals(session, graph.session.value)
                assertEquals(session, graph.store.session(second))
                assertFalse(model.state.value.playing)
            } finally { graph.store.removeConnection(second) }
        }
    }

    @Test fun failedRefreshDoesNotInvalidateSuccessfullyLoadedEmptyAlbumCatalog() = runBlocking {
        withBrowseFixture { model, _, server ->
            until { !model.state.value.albumsRefreshing }
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = MockResponse().setResponseCode(503)
            }
            // A failed explicit refresh must not invalidate an already initialized empty catalog.
            onMain { model.refreshCollection(CollectionKind.ALBUMS) }
            until { !model.state.value.albumsRefreshing && model.state.value.albumsError != null }
            val requests = server.requestCount
            onMain { model.showScreen(Screen.ALL_ALBUMS) }
            assertFalse(model.state.value.albumsRefreshing)
            Thread.sleep(200)
            assertEquals(requests, server.requestCount)
            assertEquals(Screen.ALL_ALBUMS, model.state.value.screen)
        }
    }

    @Test fun singleSongPlaybackRequiresControllerBeforeMutatingSession() = runBlocking {
        withBrowseFixture { model, graph, _ ->
            val previous = graph.session.value
            onMain { model.playSong(Song("chosen", "Chosen", "Artist")) }
            until { model.state.value.error != null && !model.state.value.loading }
            assertEquals(previous, graph.session.value)
        }
    }

    private suspend fun withBrowseFixture(block: suspend (AudioViewModel, AudioGraph, MockWebServer) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val graph = AudioGraph(app)
        val server = MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when {
                request.path!!.contains("getPlaylists.view") -> ok(""", "playlists":{"playlist":[{"id":"p","name":"Browse","songCount":1}]}""")
                request.path!!.contains("getPlaylist.view") -> ok(""", "playlist":{"entry":[{"id":"remote","title":"Remote song"}]}""")
                request.path!!.contains("getAlbumList2.view") -> ok(""", "albumList2":{}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val id = "vm-browse-fixture"
        val owner = ViewModelStore()
        try {
            graph.store.saveConnection(Credentials(Connection(id, "Fixture", server.url("/").toString(), "vm-browse-user"), "test-only-password"))
            graph.store.selectConnection(id)
            graph.store.saveSession(ListeningSession(id, "old", "Playing", listOf(Song("old", "Old song", "Fixture")), positionMs = 4321L))
            lateinit var model: AudioViewModel
            onMain { model = AudioViewModel(app, graph, false); owner.put("fixture", model) }
            until { !model.state.value.booting && model.state.value.playlists.isNotEmpty() && !model.state.value.playlistsRefreshing }
            block(model, graph, server)
        } finally {
            onMain { owner.clear() }
            graph.store.removeConnection(id)
            server.shutdown()
        }
    }

    @Test fun albumCatalogLoadsAndAlbumDetailIsScopedToTheActiveConnection() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val graph = AudioGraph(app)
        val server = MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when {
                request.path!!.contains("getAlbumList2.view") -> ok(",\"albumList2\":{\"album\":[{\"id\":\"album-1\",\"name\":\"Fixture Album\",\"artist\":\"Fixture Artist\",\"coverArt\":\"cover-1\",\"songCount\":1,\"year\":2024}]}")
                request.path!!.contains("getAlbum.view") -> ok(",\"album\":{\"id\":\"album-1\",\"name\":\"Fixture Album\",\"artist\":\"Fixture Artist\",\"coverArt\":\"cover-1\",\"songCount\":1,\"year\":2024,\"song\":[{\"id\":\"track-1\",\"title\":\"Fixture Track\",\"artist\":\"Fixture Artist\",\"duration\":190}]}")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val id = "vm-album-fixture"
        val owner = ViewModelStore()
        try {
            graph.store.saveConnection(Credentials(Connection(id, "Fixture", server.url("/").toString(), "vm-album-user"), "test-only-password"))
            graph.store.selectConnection(id)
            lateinit var model: AudioViewModel
            onMain { model = AudioViewModel(app, graph, false); owner.put("fixture", model) }
            until { !model.state.value.booting && model.state.value.albums.singleOrNull()?.id == "album-1" }
            onMain {
                model.showScreen(Screen.ALL_ALBUMS)
                model.setCollectionQuery(CollectionKind.ALBUMS, "Fixture Artist")
                model.openAlbum("album-1")
            }
            until { !model.state.value.albumLoading && model.state.value.albumSongs.isNotEmpty() }
            assertEquals("Fixture Album", model.state.value.selectedAlbum?.name)
            assertEquals("Fixture Track", model.state.value.albumSongs.single().title)
            assertEquals(Screen.ALL_ALBUMS, model.state.value.backDestination)
            onMain { model.showScreen(Screen.SETTINGS); model.showScreen(model.state.value.backDestination) }
            assertEquals(Screen.ALL_ALBUMS, model.state.value.backDestination)
            onMain { model.showScreen(model.state.value.backDestination) }
            assertEquals(Screen.ALL_ALBUMS, model.state.value.screen)
            assertEquals("Fixture Artist", model.state.value.albumCollectionQuery)
            assertEquals(3, server.requestCount)
            val paths = List(3) { server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)!!.requestUrl!!.encodedPath }.toSet()
            assertTrue(paths.contains("/rest/getPlaylists.view"))
            assertTrue(paths.contains("/rest/getAlbumList2.view"))
            assertTrue(paths.contains("/rest/getAlbum.view"))
        } finally {
            onMain { owner.clear() }
            graph.store.removeConnection(id)
            server.shutdown()
        }
    }
}
