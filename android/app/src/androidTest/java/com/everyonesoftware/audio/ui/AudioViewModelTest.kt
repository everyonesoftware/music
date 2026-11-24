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
    @Test fun deviceActionPersistenceFailuresStayOnTheOriginatingPageAndAllowRetry() = runBlocking {
        withAlbumFixture { model, graph, _ ->
            val app = ApplicationProvider.getApplicationContext<Application>()
            val blockedWrite = java.io.File(app.filesDir, "offline-music/metadata/catalog.tmp")
            val account = requireNotNull(graph.credentials.value)
            onMain { model.openAlbum("a") }
            until { !model.state.value.albumLoading && model.state.value.albumSongs.isNotEmpty() }
            val session = graph.session.value
            assertTrue(blockedWrite.mkdir())
            try {
                onMain { model.downloadAlbum() }
                until { model.state.value.deviceActionFailure != null }
                val failed = requireNotNull(model.state.value.deviceActionFailure)
                assertEquals(DeviceActionPage(account.connection, Screen.ALBUM, "a"), failed.page)
                assertEquals("Cannot save this collection. Previously saved music is kept. Open Device actions and try again.", failed.message)
                assertNull(model.state.value.error)
                assertNull(graph.songCache.collection(account, "album", "a"))
                onMain { model.showScreen(Screen.SEARCH) }
                assertNull(model.state.value.deviceActionFailure)
            } finally { assertTrue(blockedWrite.delete()) }
            onMain { model.openAlbum("a") }
            until { !model.state.value.albumLoading && model.state.value.albumSongs.isNotEmpty() }
            onMain { model.downloadAlbum() }
            until { model.state.value.storage.collections.any { it.kind == "album" && it.id == "a" } }
            assertTrue(blockedWrite.mkdir())
            try {
                onMain { model.removeOfflineCollection("album", "a") }
                until { model.state.value.deviceActionFailure != null }
                assertEquals("Cannot remove this collection from the device. Open Device actions and try again.",
                    model.state.value.deviceActionFailure?.message)
                assertNotNull(graph.songCache.collection(account, "album", "a"))
            } finally { assertTrue(blockedWrite.delete()) }
            onMain { model.removeOfflineCollection("album", "a") }
            until { model.state.value.storage.collections.none { it.kind == "album" && it.id == "a" } }
            assertNull(model.state.value.deviceActionFailure)
            assertEquals(session, graph.session.value)
        }
    }

    @Test fun foregroundRecoversFailedSearchAlbumCatalogWithBlankQuery() = runBlocking {
        assertSearchAlbumRecovery(network = false, query = "   ")
    }

    @Test fun foregroundRecoversFailedSearchAlbumCatalogWithSongQuery() = runBlocking {
        assertSearchAlbumRecovery(network = false, query = "  artist  ")
    }

    @Test fun networkRecoversFailedSearchAlbumCatalogWithBlankQuery() = runBlocking {
        assertSearchAlbumRecovery(network = true, query = "   ")
    }

    @Test fun networkRecoversFailedSearchAlbumCatalogWithSongQuery() = runBlocking {
        assertSearchAlbumRecovery(network = true, query = "  artist  ")
    }

    private suspend fun assertSearchAlbumRecovery(network: Boolean, query: String) {
        withAlbumFixture { model, graph, server ->
            val failAlbums = java.util.concurrent.atomic.AtomicBoolean(false)
            val albumRequests = java.util.concurrent.atomic.AtomicInteger()
            val songRequests = java.util.concurrent.atomic.AtomicInteger()
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val recovering = java.util.concurrent.atomic.AtomicBoolean(false)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when {
                    request.requestUrl!!.encodedPath.endsWith("getAlbumList2.view") -> {
                        albumRequests.incrementAndGet()
                        if (failAlbums.get()) MockResponse().setResponseCode(503)
                        else {
                            if (recovering.get()) {
                                entered.countDown()
                                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                            }
                            ok(""", "albumList2":{"album":[{"id":"recovered","name":"Recovered album"}]}""")
                        }
                    }
                    request.requestUrl!!.encodedPath.endsWith("search3.view") -> {
                        songRequests.incrementAndGet()
                        assertEquals("artist", request.requestUrl!!.queryParameter("query"))
                        ok(""", "searchResult3":{"song":[{"id":"result","title":"Result"}]}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            try {
                failAlbums.set(true)
                onMain { model.showScreen(Screen.SEARCH); model.search(query) }
                until { !model.state.value.albumsRefreshing && model.state.value.albumsError != null && !model.state.value.searchLoading }
                val before = model.state.value
                assertEquals("a", before.albums.single().id)
                assertNull(before.searchError)
                val session = graph.session.value
                val storage = graph.songCache.state.value
                albumRequests.set(0)
                songRequests.set(0)
                failAlbums.set(false)
                recovering.set(true)
                onMain { model.onBackground(); model.onNetworkRecovered() }
                Thread.sleep(100)
                assertEquals(0, albumRequests.get())
                graph.networkPolicy.offline = true
                try {
                    onMain { model.onForeground(); model.onNetworkRecovered() }
                    Thread.sleep(100)
                    assertEquals(0, albumRequests.get())
                    assertEquals(0, songRequests.get())
                } finally { graph.networkPolicy.offline = false }
                onMain {
                    if (network) { model.onNetworkRecovered(); model.onNetworkRecovered() }
                    else { model.onBackground(); model.onForeground(); model.onForeground() }
                }
                assertTrue("Search must retry its failed album catalog for query '$query'", entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                assertTrue(model.state.value.albumsRefreshing)
                assertEquals(before.albums, model.state.value.albums)
                assertEquals(before.searchSongs, model.state.value.searchSongs)
                release.countDown()
                until { !model.state.value.albumsRefreshing && !model.state.value.searchLoading }
                assertNull(model.state.value.albumsError)
                assertEquals("recovered", model.state.value.albums.single().id)
                assertEquals(1, albumRequests.get())
                assertEquals(if (query.isBlank()) 0 else 1, songRequests.get())
                assertEquals(query, model.state.value.searchQuery)
                assertEquals(before.searchSongs, model.state.value.searchSongs)
                assertEquals(before.screen, model.state.value.screen)
                assertEquals(before.backDestination, model.state.value.backDestination)
                assertEquals(session, graph.session.value)
                assertEquals(session, graph.store.session("vm-browse-fixture"))
                assertEquals(storage, graph.songCache.state.value)
                assertFalse(model.state.value.playing)
                onMain { model.onBackground(); model.onForeground(); model.onNetworkRecovered() }
                Thread.sleep(100)
                assertEquals("Healthy Search catalog must not refetch on resume", 1, albumRequests.get())
                assertEquals(if (query.isBlank()) 0 else 1, songRequests.get())
                onMain { model.refreshCurrentPage(); model.refreshCurrentPage() }
                until { !model.state.value.searchLoading }
                assertEquals("Song-only explicit refresh must not refetch a healthy catalog", 1, albumRequests.get())
                assertEquals(if (query.isBlank()) 0 else 2, songRequests.get())
            } finally { release.countDown() }
        }
    }

    @Test fun foregroundRecoversFailedDetailsWithoutChangingNavigationPlaybackOrDownloads() = runBlocking {
        for (album in listOf(false, true)) withAlbumFixture { model, graph, server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = if (album)
                    ok(""", "album":{"id":"a","name":"Album","song":[{"id":"remote","title":"Track"}]}""")
                else ok(""", "playlist":{"entry":[{"id":"remote","title":"Track"}]}""")
            }
            onMain { if (album) model.openAlbum("a") else model.openPlaylist("p") }
            fun songs() = if (album) model.state.value.albumSongs else model.state.value.playlistSongs
            fun loading() = if (album) model.state.value.albumLoading else model.state.value.playlistLoading
            fun error() = if (album) model.state.value.albumError else model.state.value.playlistDetailError
            until { !loading() && songs().isNotEmpty() }
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = MockResponse().setResponseCode(503)
            }
            onMain { model.refreshCurrentPage() }
            until { !loading() && error() != null }
            val before = model.state.value
            val session = graph.session.value
            val storage = graph.songCache.state.value
            val requests = java.util.concurrent.atomic.AtomicInteger()
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    requests.incrementAndGet()
                    return if (album) ok(""", "album":{"id":"a","name":"Recovered","song":[{"id":"recovered","title":"Recovered"}]}""")
                    else ok(""", "playlist":{"entry":[{"id":"recovered","title":"Recovered"}]}""")
                }
            }
            onMain { model.onBackground(); model.onForeground(); model.onForeground() }
            until { !loading() && error() == null && songs().singleOrNull()?.id == "recovered" }
            assertEquals(1, requests.get())
            assertEquals(before.screen, model.state.value.screen)
            assertEquals(before.backDestination, model.state.value.backDestination)
            assertEquals(session, graph.session.value)
            assertEquals(session, graph.store.session("vm-browse-fixture"))
            assertEquals(storage, graph.songCache.state.value)
            assertFalse(model.state.value.playing)
            onMain { model.onBackground(); model.onForeground() }
            Thread.sleep(100)
            assertEquals("Healthy details must not refetch on every resume", 1, requests.get())
        }
    }

    @Test fun foregroundInvalidatesFailedArtworkCooldownSoVisibleArtworkCanLoadAgain() = runBlocking {
        withBrowseFixture { model, graph, server ->
            until { !model.state.value.albumsRefreshing }
            onMain { model.showScreen(Screen.PLAYER) }
            val credentials = graph.credentials.value!!
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = MockResponse().setResponseCode(503)
            }
            assertNull(graph.artworkCache.load(credentials, "resume-cover"))
            val revision = graph.artworkCache.state.value
            val bitmap = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
            val image = java.io.ByteArrayOutputStream().use { output ->
                try { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output); output.toByteArray() }
                finally { bitmap.recycle() }
            }
            val requests = java.util.concurrent.atomic.AtomicInteger()
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    requests.incrementAndGet()
                    return MockResponse().setBody(okio.Buffer().write(image))
                }
            }
            val session = graph.session.value
            onMain { model.onBackground(); model.onForeground() }
            until { model.state.value.artworkRevision > revision }
            onMain { repeat(10) { model.artworkUrl("resume-cover") } }
            until { graph.artworkCache.cachedUri(credentials, "resume-cover") != null }
            assertEquals(1, requests.get())
            assertEquals(session, graph.session.value)
        }
    }

    @Test fun networkRecoveryRetriesVisibleFailureOnlyWhileForegroundAndOnline() = runBlocking {
        withBrowseFixture { model, graph, server ->
            until { !model.state.value.albumsRefreshing }
            onMain { model.onForeground() }
            until { !model.state.value.albumsRefreshing && !model.state.value.playlistsRefreshing }
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = MockResponse().setResponseCode(503)
            }
            onMain { model.openPlaylist("p") }
            until { !model.state.value.playlistLoading && model.state.value.playlistDetailError != null }
            val session = graph.session.value
            val requests = java.util.concurrent.atomic.AtomicInteger()
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    requests.incrementAndGet()
                    return ok(""", "playlist":{"entry":[{"id":"recovered","title":"Recovered"}]}""")
                }
            }
            onMain { model.onBackground(); model.onNetworkRecovered() }
            Thread.sleep(100)
            assertEquals(0, requests.get())
            graph.networkPolicy.offline = true
            try {
                onMain { model.onForeground(); model.onNetworkRecovered() }
                Thread.sleep(100)
                assertEquals(0, requests.get())
            } finally { graph.networkPolicy.offline = false }
            onMain { model.onNetworkRecovered(); model.onNetworkRecovered() }
            until { !model.state.value.playlistLoading && model.state.value.playlistSongs.singleOrNull()?.id == "recovered" }
            assertEquals(1, requests.get())
            assertNull(model.state.value.playlistDetailError)
            assertEquals(session, graph.session.value)
        }
    }

    @Test fun currentPageDetailRefreshRetainsSongsCoalescesRequestsAndPreservesPlaybackAndDownloads() = runBlocking {
        for (album in listOf(false, true)) withBrowseFixture { model, graph, server ->
            until { !model.state.value.albumsRefreshing }
            val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
            val revision = java.util.concurrent.atomic.AtomicInteger(0)
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    requests.add(path)
                    if (path.endsWith("getAlbumList2.view")) return ok(""", "albumList2":{"album":[{"id":"a","name":"Album"}]}""")
                    if (!path.endsWith(if (album) "getAlbum.view" else "getPlaylist.view")) return MockResponse().setResponseCode(404)
                    val version = revision.get()
                    if (version == 1) {
                        entered.countDown()
                        check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    }
                    if (version == 2) return MockResponse().setResponseCode(503)
                    return if (album) ok(""", "album":{"id":"a","name":"Album $version","song":[{"id":"song-$version","title":"Track $version"}]}""")
                    else ok(""", "playlist":{"entry":[{"id":"song-$version","title":"Track $version"}]}""")
                }
            }
            fun songs() = if (album) model.state.value.albumSongs else model.state.value.playlistSongs
            fun loading() = if (album) model.state.value.albumLoading else model.state.value.playlistLoading
            fun error() = if (album) model.state.value.albumError else model.state.value.playlistDetailError
            try {
                if (album) {
                    onMain { model.refreshCollection(CollectionKind.ALBUMS) }
                    until { !model.state.value.albumsRefreshing && model.state.value.albums.isNotEmpty() }
                }
                onMain {
                    model.showScreen(if (album) Screen.ALL_ALBUMS else Screen.ALL_PLAYLISTS)
                    if (album) model.openAlbum("a") else model.openPlaylist("p")
                }
                until { !loading() && songs().singleOrNull()?.id == "song-0" }
                val before = model.state.value
                val session = graph.session.value
                val storage = graph.songCache.state.value
                requests.clear()
                revision.set(1)
                onMain { model.refreshCurrentPage(); model.refreshCurrentPage() }
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                assertTrue(loading())
                assertEquals(before.screen, model.state.value.screen)
                assertEquals(before.backDestination, model.state.value.backDestination)
                assertEquals("song-0", songs().single().id)
                release.countDown()
                until { !loading() && songs().singleOrNull()?.id == "song-1" }
                revision.set(2)
                onMain { model.refreshCurrentPage() }
                until { !loading() && error() != null }
                assertEquals("song-1", songs().single().id)
                assertEquals(before.screen, model.state.value.screen)
                assertEquals(before.backDestination, model.state.value.backDestination)
                assertEquals(session, graph.session.value)
                assertEquals(session, graph.store.session("vm-browse-fixture"))
                assertEquals(storage, graph.songCache.state.value)
                assertFalse(model.state.value.playing)
                val detailPath = if (album) "/rest/getAlbum.view" else "/rest/getPlaylist.view"
                assertEquals(listOf(detailPath, detailPath), requests.toList())
            } finally { release.countDown() }
        }
    }

    @Test fun currentPageRefreshDispatchesOnlyMetadataForItsCollectionAndIgnoresOtherPages() = runBlocking {
        withBrowseFixture { model, graph, server ->
            until { !model.state.value.albumsRefreshing }
            val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    requests.add(path)
                    return when {
                        path.endsWith("getAlbumList2.view") -> ok(""", "albumList2":{"album":[{"id":"new","name":"New album"}]}""")
                        path.endsWith("getArtists.view") -> ok(""", "artists":{}""")
                        path.endsWith("getPlaylists.view") -> ok(""", "playlists":{"playlist":[{"id":"p","name":"Updated playlist"}]}""")
                        path.endsWith("getPlaylist.view") -> ok(""", "playlist":{"entry":[{"id":"updated","title":"Updated"}]}""")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            val session = graph.session.value
            val storage = graph.songCache.state.value
            for (screen in listOf(Screen.ALL_PLAYLISTS, Screen.ALL_ALBUMS, Screen.PLAYLISTS)) {
                onMain { model.showScreen(screen) }
                until { !model.state.value.albumsRefreshing && !model.state.value.playlistsRefreshing && !model.state.value.artistsRefreshing }
                requests.clear()
                onMain { model.refreshCurrentPage() }
                until { requests.isNotEmpty() && !model.state.value.albumsRefreshing && !model.state.value.playlistsRefreshing && !model.state.value.artistsRefreshing }
                val expected = when (screen) {
                    Screen.ALL_PLAYLISTS -> setOf("/rest/getPlaylists.view", "/rest/getPlaylist.view")
                    Screen.ALL_ALBUMS -> setOf("/rest/getAlbumList2.view")
                    else -> setOf("/rest/getPlaylists.view", "/rest/getPlaylist.view", "/rest/getAlbumList2.view", "/rest/getArtists.view")
                }
                assertEquals(expected, requests.toSet())
                assertEquals(expected.size, requests.size)
                assertEquals(screen, model.state.value.screen)
                assertEquals("Updated playlist", model.state.value.playlists.single().name)
                if (screen != Screen.ALL_PLAYLISTS) assertEquals("new", model.state.value.albums.single().id)
            }
            for (screen in listOf(Screen.OFFLINE, Screen.SETTINGS, Screen.PLAYER, Screen.QUEUE)) {
                onMain { model.showScreen(screen) }
                requests.clear()
                val before = model.state.value
                onMain { model.refreshCurrentPage() }
                Thread.sleep(100)
                assertTrue(requests.isEmpty())
                assertEquals(before, model.state.value)
            }
            assertEquals(session, graph.session.value)
            assertEquals(storage, graph.songCache.state.value)
            assertFalse(model.state.value.playing)
        }
    }

    @Test fun currentPageSearchRefreshRetainsResultsAndQueryOnFailureWithoutDuplicateRequests() = runBlocking {
        withBrowseFixture { model, graph, server ->
            until { !model.state.value.albumsRefreshing }
            onMain { model.showScreen(Screen.SEARCH) }
            until { !model.state.value.albumsRefreshing }
            val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
            val revision = java.util.concurrent.atomic.AtomicInteger(0)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    requests.add(request.requestUrl!!.queryParameter("query") ?: "unexpected")
                    val version = revision.get()
                    return if (version == 2) MockResponse().setResponseCode(503)
                    else ok(""", "searchResult3":{"song":[{"id":"result-$version","title":"Result $version"}]}""")
                        .setBodyDelay(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                }
            }
            val session = graph.session.value
            val storage = graph.songCache.state.value
            onMain { model.search("  artist  ") }
            until { !model.state.value.searchLoading && model.state.value.searchSongs.isNotEmpty() }
            requests.clear()
            revision.set(1)
            onMain { model.refreshCurrentPage(); model.refreshCurrentPage() }
            assertEquals("result-0", model.state.value.searchSongs.single().id)
            until { !model.state.value.searchLoading && model.state.value.searchSongs.singleOrNull()?.id == "result-1" }
            revision.set(2)
            onMain { model.refreshCurrentPage() }
            until { !model.state.value.searchLoading && model.state.value.searchError != null }
            assertEquals("result-1", model.state.value.searchSongs.single().id)
            assertEquals("  artist  ", model.state.value.searchQuery)
            assertEquals(Screen.SEARCH, model.state.value.screen)
            assertEquals(listOf("artist", "artist"), requests.toList())
            assertEquals(session, graph.session.value)
            assertEquals(storage, graph.songCache.state.value)
            graph.networkPolicy.offline = true
            try {
                val before = model.state.value
                onMain { model.refreshCurrentPage() }
                Thread.sleep(100)
                assertEquals(before, model.state.value)
                assertEquals(listOf("artist", "artist"), requests.toList())
            } finally { graph.networkPolicy.offline = false }
            onMain { model.search("   ") }
            val blank = model.state.value
            onMain { model.refreshCurrentPage() }
            Thread.sleep(100)
            assertEquals(blank, model.state.value)
            assertEquals(listOf("artist", "artist"), requests.toList())
        }
    }

    @Test fun pendingDetailRefreshCannotPublishAfterAccountSwitch() = runBlocking {
        for (album in listOf(false, true)) withBrowseFixture { model, graph, server ->
            until { !model.state.value.albumsRefreshing }
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val refreshing = java.util.concurrent.atomic.AtomicBoolean(false)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    if (path.endsWith("getAlbumList2.view")) return ok(""", "albumList2":{"album":[{"id":"a","name":"Album"}]}""")
                    if (path.endsWith("getPlaylists.view")) return ok(""", "playlists":{}""")
                    if (refreshing.get()) {
                        entered.countDown()
                        check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    }
                    return if (album) ok(""", "album":{"id":"a","name":"Old account","song":[{"id":"old-account","title":"Old"}]}""")
                    else ok(""", "playlist":{"entry":[{"id":"old-account","title":"Old"}]}""")
                }
            }
            val second = "vm-refresh-second"
            try {
                if (album) {
                    onMain { model.loadAlbums() }
                    until { !model.state.value.albumsRefreshing && model.state.value.albums.isNotEmpty() }
                }
                onMain { if (album) model.openAlbum("a") else model.openPlaylist("p") }
                until { if (album) !model.state.value.albumLoading else !model.state.value.playlistLoading }
                refreshing.set(true)
                onMain { model.refreshCurrentPage() }
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                graph.store.saveConnection(Credentials(Connection(second, "Second", server.url("/").toString(), "vm-refresh-second-user"), "test-only-password"))
                val session = ListeningSession(second, "second", "Second", listOf(Song("second", "Second", "Fixture")))
                graph.store.saveSession(session)
                onMain { model.switchConnection(second) }
                until { model.state.value.connection?.id == second && !model.state.value.loading }
                release.countDown()
                Thread.sleep(300)
                assertNull(model.state.value.selectedAlbum)
                assertNull(model.state.value.selectedPlaylist)
                assertTrue(model.state.value.albumSongs.isEmpty())
                assertTrue(model.state.value.playlistSongs.isEmpty())
                assertFalse(model.state.value.albumLoading)
                assertFalse(model.state.value.playlistLoading)
                assertEquals(session, graph.session.value)
            } finally { release.countDown(); graph.store.removeConnection(second) }
        }
    }

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

    @Test fun scopedCollectionSongRequiresControllerBeforeReplacingSavedQueue() = runBlocking {
        for (kind in listOf("playlist", "album")) withAlbumFixture { model, graph, server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = if (kind == "album")
                    ok(""", "album":{"id":"a","name":"Album","song":[{"id":"one","title":"One"},{"id":"two","title":"Two"},{"id":"one","title":"One"}]}""")
                else ok(""", "playlist":{"entry":[{"id":"one","title":"One"},{"id":"two","title":"Two"},{"id":"one","title":"One"}]}""")
            }
            onMain { if (kind == "album") model.openAlbum("a") else model.openPlaylist("p") }
            until { if (kind == "album") !model.state.value.albumLoading && model.state.value.albumSongs.size == 3
                else !model.state.value.playlistLoading && model.state.value.playlistSongs.size == 3 }
            val previous = graph.session.value
            val page = model.state.value
            val request = CollectionSongSelection(page.connection!!.id, kind, if (kind == "album") "a" else "p", 2,
                if (kind == "album") page.albumSongs else page.playlistSongs)
            val requests = server.requestCount
            onMain { model.playCollectionSong(request) }
            until { model.state.value.error != null && !model.state.value.loading }
            assertEquals(previous, graph.session.value)
            assertEquals(previous, graph.store.session(previous!!.connectionId))
            assertEquals(requests, server.requestCount)
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

    @Test fun detailPlaylistPlayRejectsMissingAndFailedDetailsWithoutRequestsOrSessionMutation() = runBlocking {
        withBrowseFixture { model, graph, server ->
            until { !model.state.value.albumsRefreshing }
            val previous = graph.session.value
            val before = server.requestCount
            onMain { model.playPlaylist("p", false) }
            until { !model.state.value.loading }
            assertEquals("No detail selection must not fetch or start playback", before, server.requestCount)
            assertNull(model.state.value.error)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = MockResponse().setResponseCode(503)
            }
            onMain { model.openPlaylist("p") }
            until { model.state.value.playlistDetailError != null }
            val failedRequests = server.requestCount
            onMain { model.playPlaylist("p", true) }
            until { !model.state.value.loading }
            assertEquals(failedRequests, server.requestCount)
            assertNull(model.state.value.error)
            assertEquals(previous, graph.session.value)
        }
    }

    @Test fun detailPlaylistPlayRejectsLoadingEmptyAndOfflineDetails() = runBlocking {
        withBrowseFixture { model, graph, server ->
            until { !model.state.value.albumsRefreshing }
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    entered.countDown()
                    check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    return ok(""", "playlist":{"entry":[]}""")
                }
            }
            val previous = graph.session.value
            try {
                onMain { model.openPlaylist("p") }
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                val requests = server.requestCount
                onMain { model.playPlaylist("p", true) }
                assertFalse(model.state.value.loading)
                assertEquals(requests, server.requestCount)
                release.countDown()
                until { !model.state.value.playlistLoading }
                onMain { model.playPlaylist("p") }
                assertFalse(model.state.value.loading)
                assertEquals(requests, server.requestCount)
                server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                    override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                        ok(""", "playlist":{"entry":[{"id":"remote","title":"Remote song"}]}""")
                }
                onMain { model.openPlaylist("p") }
                until { !model.state.value.playlistLoading && model.state.value.playlistSongs.isNotEmpty() }
                val readyRequests = server.requestCount
                val settings = graph.songCache.state.value.settings
                graph.songCache.setSettings(settings.copy(offline = true))
                try {
                    onMain { model.playPlaylist("p", true) }
                    assertFalse(model.state.value.loading)
                    assertEquals(readyRequests, server.requestCount)
                    assertEquals(previous, graph.session.value)
                    assertNull(model.state.value.error)
                } finally { graph.songCache.setSettings(settings) }
            } finally { release.countDown() }
        }
    }

    @Test fun loadedCollectionPlayDoesNotRefetchMetadataBeforeConnectingToThePlayer() = runBlocking {
        for (album in listOf(false, true)) withAlbumFixture { model, graph, server ->
            if (!album) {
                server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                    override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                        ok(""", "playlist":{"entry":[{"id":"remote","title":"Remote song"}]}""")
                }
            }
            onMain { if (album) model.openAlbum("a") else model.openPlaylist("p") }
            until { if (album) !model.state.value.albumLoading else !model.state.value.playlistLoading }
            val before = server.requestCount
            val previous = graph.session.value
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = MockResponse().setResponseCode(503)
            }
            onMain { if (album) model.playAlbum("a", false) else model.playPlaylist("p", false) }
            until { !model.state.value.loading && model.state.value.error != null }
            assertEquals("Play must use the displayed songs, not wait for a second metadata request", before, server.requestCount)
            assertEquals(previous, graph.session.value)
        }
    }

    @Test fun downloadedCollectionOpensAndStartsLocallyEvenWhenOnline() = runBlocking {
        for (album in listOf(false, true)) withAlbumFixture { model, graph, server ->
            val credentials = graph.credentials.value!!
            val kind = if (album) "album" else "playlist"
            val id = if (album) "a" else "p"
            val songs = listOf(Song("saved", "Saved song", "Artist"), Song("saved", "Saved song", "Artist"))
            cacheSong(graph, credentials, songs.first())
            graph.songCache.downloadCollection(credentials, OfflineCollection(kind, id, "Saved collection", songs = songs))
            val before = server.requestCount
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = MockResponse().setResponseCode(503)
            }
            onMain { if (album) model.openAlbum(id) else model.openPlaylist(id) }
            until { if (album) !model.state.value.albumLoading else !model.state.value.playlistLoading }
            assertEquals("Downloaded details must not wait for the server", before, server.requestCount)
            assertEquals(songs, if (album) model.state.value.albumSongs else model.state.value.playlistSongs)
            val previous = graph.session.value
            onMain { if (album) model.playAlbum(id, true) else model.playPlaylist(id, true) }
            until { !model.state.value.loading && model.state.value.error != null }
            assertEquals(before, server.requestCount)
            assertEquals(previous, graph.session.value)
        }
    }

    @Test fun downloadedCollectionExplicitRefreshLoadsServerDetailsWithoutReplacingTheSavedSnapshot() = runBlocking {
        for (album in listOf(false, true)) withAlbumFixture { model, graph, server ->
            val credentials = graph.credentials.value!!
            val kind = if (album) "album" else "playlist"
            val id = if (album) "a" else "p"
            val song = Song("saved", "Saved", "Artist")
            cacheSong(graph, credentials, song)
            val saved = OfflineCollection(kind, id, "Saved", songs = listOf(song))
            graph.songCache.downloadCollection(credentials, saved)
            onMain { if (album) model.openAlbum(id) else model.openPlaylist(id) }
            until { if (album) !model.state.value.albumLoading else !model.state.value.playlistLoading }
            assertEquals(listOf(song), if (album) model.state.value.albumSongs else model.state.value.playlistSongs)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = if (album)
                    ok(""", "album":{"id":"a","name":"Updated","song":[{"id":"new","title":"New"}]}""")
                else ok(""", "playlist":{"entry":[{"id":"new","title":"New"}]}""")
            }
            val before = server.requestCount
            onMain { model.refreshCurrentPage() }
            until { if (album) !model.state.value.albumLoading else !model.state.value.playlistLoading }
            assertEquals(before + 1, server.requestCount)
            assertEquals("new", (if (album) model.state.value.albumSongs else model.state.value.playlistSongs).single().id)
            assertEquals(saved, graph.songCache.collection(credentials, kind, id))
        }
    }

    @Test fun downloadedPlaylistShortcutDoesNotRefetchBeforeConnectingToThePlayer() = runBlocking {
        withBrowseFixture { model, graph, server ->
            until { !model.state.value.albumsRefreshing }
            val credentials = graph.credentials.value!!
            val song = Song("saved", "Saved", "Artist")
            cacheSong(graph, credentials, song)
            graph.songCache.downloadCollection(credentials, OfflineCollection("playlist", "p", "Saved", songs = listOf(song)))
            val before = server.requestCount
            val previous = graph.session.value
            onMain { model.startPlaylist("p") }
            until { !model.state.value.loading && model.state.value.error != null }
            assertEquals("Downloaded shortcut must not request playlist contents", before, server.requestCount)
            assertEquals(previous, graph.session.value)
        }
    }

    @Test fun detailPlaylistPlayRequiresAControllerBeforeReplacingTheSession() = runBlocking {
        withBrowseFixture { model, graph, _ ->
            onMain { model.openPlaylist("p") }
            until { !model.state.value.playlistLoading && model.state.value.playlistSongs.isNotEmpty() }
            val previous = graph.session.value
            onMain { model.playPlaylist("p", false) }
            until { !model.state.value.loading && model.state.value.error != null }
            assertEquals(previous, graph.session.value)
            assertEquals(previous, graph.store.session("vm-browse-fixture"))
        }
    }

    @Test fun detailAlbumPlayRejectsMissingAndFailedDetailsWithoutRequestsOrSessionMutation() = runBlocking {
        withAlbumFixture { model, graph, server ->
            until { !model.state.value.albumsRefreshing }
            val previous = graph.session.value
            val before = server.requestCount
            onMain { model.playAlbum("a") }
            until { !model.state.value.loading }
            assertEquals("No detail selection must not fetch or start playback", before, server.requestCount)
            assertNull(model.state.value.error)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = MockResponse().setResponseCode(503)
            }
            onMain { model.openAlbum("a") }
            until { model.state.value.albumError != null }
            val failedRequests = server.requestCount
            onMain { model.playAlbum("a") }
            until { !model.state.value.loading }
            assertEquals(failedRequests, server.requestCount)
            assertNull(model.state.value.error)
            assertEquals(previous, graph.session.value)
        }
    }

    @Test fun detailAlbumPlayRejectsLoadingEmptyAndOfflineDetails() = runBlocking {
        withAlbumFixture { model, graph, server ->
            until { !model.state.value.albumsRefreshing }
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    entered.countDown()
                    check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    return ok(""", "album":{"id":"a","name":"Album","song":[]}""")
                }
            }
            val previous = graph.session.value
            try {
                onMain { model.openAlbum("a") }
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                val requests = server.requestCount
                onMain { model.playAlbum("a") }
                assertFalse(model.state.value.loading)
                assertEquals(requests, server.requestCount)
                release.countDown()
                until { !model.state.value.albumLoading }
                onMain { model.playAlbum("a") }
                assertFalse(model.state.value.loading)
                assertEquals(requests, server.requestCount)
                server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                    override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                        ok(""", "album":{"id":"a","name":"Album","song":[{"id":"remote","title":"Remote song"}]}""")
                }
                onMain { model.openAlbum("a") }
                until { !model.state.value.albumLoading && model.state.value.albumSongs.isNotEmpty() }
                val readyRequests = server.requestCount
                val settings = graph.songCache.state.value.settings
                graph.songCache.setSettings(settings.copy(offline = true))
                try {
                    onMain { model.playAlbum("a") }
                    assertFalse(model.state.value.loading)
                    assertEquals(readyRequests, server.requestCount)
                    assertEquals(previous, graph.session.value)
                    assertNull(model.state.value.error)
                } finally { graph.songCache.setSettings(settings) }
            } finally { release.countDown() }
        }
    }

    @Test fun detailAlbumPlayRequiresAControllerBeforeReplacingTheSession() = runBlocking {
        withAlbumFixture { model, graph, _ ->
            onMain { model.openAlbum("a") }
            until { !model.state.value.albumLoading && model.state.value.albumSongs.isNotEmpty() }
            val previous = graph.session.value
            onMain { model.playAlbum("a") }
            until { !model.state.value.loading && model.state.value.error != null }
            assertEquals(previous, graph.session.value)
            assertEquals(previous, graph.store.session("vm-browse-fixture"))
        }
    }

    private fun cacheSong(graph: AudioGraph, credentials: Credentials, song: Song) {
        val source = graph.songCache.factory(credentials, song, androidx.media3.datasource.DataSource.Factory {
            androidx.media3.datasource.ByteArrayDataSource(ByteArray(1024))
        }).createDataSource()
        source.open(androidx.media3.datasource.DataSpec.Builder().setUri("https://fixture.invalid/audio").build())
        try { val buffer = ByteArray(1024); while (source.read(buffer, 0, buffer.size) != -1) { } }
        finally { source.close() }
        assertTrue(graph.songCache.complete(credentials, song.id))
    }

    private suspend fun withAlbumFixture(block: suspend (AudioViewModel, AudioGraph, MockWebServer) -> Unit) {
        withBrowseFixture { model, graph, server ->
            until { !model.state.value.albumsRefreshing }
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when {
                    request.path!!.contains("getAlbumList2.view") -> ok(""", "albumList2":{"album":[{"id":"a","name":"Album"}]}""")
                    request.path!!.contains("getAlbum.view") -> ok(""", "album":{"id":"a","name":"Album","song":[{"id":"remote","title":"Remote song"}]}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            onMain { model.refreshCollection(CollectionKind.ALBUMS) }
            until { !model.state.value.albumsRefreshing && model.state.value.albums.isNotEmpty() }
            block(model, graph, server)
        }
    }

    private suspend fun withBrowseFixture(block: suspend (AudioViewModel, AudioGraph, MockWebServer) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val graph = AudioGraph(app)
        val server = MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when {
                request.path!!.contains("getArtists.view") -> ok(""", "artists":{}""")
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
            until { !model.state.value.booting && model.state.value.playlists.isNotEmpty() && !model.state.value.playlistsRefreshing && !model.state.value.artistsRefreshing }
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
                request.path!!.contains("getArtists.view") -> ok(""", "artists":{}""")
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
            until { !model.state.value.artistsRefreshing }
            assertEquals(4, server.requestCount)
            val paths = List(4) { server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)!!.requestUrl!!.encodedPath }.toSet()
            assertTrue(paths.contains("/rest/getArtists.view"))
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
