package com.everyonesoftware.audio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.everyonesoftware.audio.data.Connection
import com.everyonesoftware.audio.data.Credentials
import com.everyonesoftware.audio.data.OfflineCollection
import com.everyonesoftware.audio.data.Song
import com.everyonesoftware.audio.playback.SessionRules
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class PlaylistDeletionFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun listingCancelAndBackDoNotWriteThenExactEmptyPlaylistIsDeleted() = exercise(fromDetail = false)
    @Test fun immediateReadbackConfirmsAbsenceWithoutClaimingAcknowledgedDelete() = exercise(fromDetail = false, acknowledged = false)
    @Test fun detailLostAcknowledgementCanOnlyBeCheckedAndPreservesSavedPlaylist() = exercise(fromDetail = true)
    @Test fun freshOwnerChangePreventsDelete() = exercise(fromDetail = true, ownerChanged = true)

    private fun exercise(fromDetail: Boolean, ownerChanged: Boolean = false, acknowledged: Boolean = !fromDetail) {
        val graph = ApplicationProvider.getApplicationContext<AudioApplication>().graph
        val removed = AtomicBoolean()
        val allowReadback = AtomicBoolean(!fromDetail)
        val writeCommitted = CountDownLatch(1)
        val releaseWriteReply = CountDownLatch(1)
        val owner = AtomicReference("DeLeTeR")
        val writes = CopyOnWriteArrayList<String>()
        val afterWriteReads = CopyOnWriteArrayList<String>()
        val id = "exact-target"
        val name = "Delete me"
        val song = Song("s", "Still here", "Artist", "Album")
        val snapshot = OfflineCollection("playlist", id, name, songs = listOf(song, song))
        val account = "playlist-delete-${UUID.randomUUID()}"
        fun ok(fields: String = "") = MockResponse().setBody("""{"subsonic-response":{"status":"ok"$fields}}""")
        fun target(tracks: Boolean, listed: Boolean = false): String {
            val count = if (fromDetail) 2 else 0
            val occurrences = if (tracks && fromDetail) ",\"entry\":[{\"id\":\"s\",\"title\":\"Still here\"},{\"id\":\"s\",\"title\":\"Still here\"}]" else ""
            return """{"id":"$id","name":"$name","owner":"${if (listed) "DeLeTeR" else owner.get()}","songCount":$count$occurrences}"""
        }
        val shared = """{"id":"shared","name":"Shared","owner":"someone-else","songCount":0}"""
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val url = request.requestUrl ?: return MockResponse().setResponseCode(400)
                    return when (url.encodedPath.substringAfterLast('/')) {
                        "ping.view" -> ok()
                        "getArtists.view" -> ok(",\"artists\":{}")
                        "getAlbumList2.view" -> ok(",\"albumList2\":{}")
                        "getPlaylists.view" -> ok(",\"playlists\":{\"playlist\":[${if (removed.get()) "" else target(false, listed = true) + ","}$shared]}")
                        "getPlaylist.view" -> when (val requested = url.queryParameter("id")) {
                            "shared" -> ok(",\"playlist\":$shared")
                            id -> if (!removed.get()) ok(",\"playlist\":${target(true)}") else {
                                afterWriteReads.add(requested)
                                if (!allowReadback.get()) MockResponse().setResponseCode(503)
                                else MockResponse().setBody("""{"subsonic-response":{"status":"failed","error":{"code":70}}}""")
                            }
                            else -> MockResponse().setResponseCode(400)
                        }
                        "deletePlaylist.view" -> {
                            val body = request.body.readUtf8()
                            writes.add("${request.method}:$body")
                            if (request.method != "POST" || body != "id=$id" || url.queryParameter("id") != null) return MockResponse().setResponseCode(400)
                            removed.set(true)
                            if (acknowledged) return ok()
                            if (fromDetail) {
                                writeCommitted.countDown()
                                check(releaseWriteReply.await(20, TimeUnit.SECONDS)) { "Delete reply not released by fixture" }
                            }
                            MockResponse().setResponseCode(503).setHeader("Retry-After", "0")
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
            val before = runBlocking {
                graph.bootstrap()
                graph.store.saveConnection(Credentials(Connection(account, "Delete fixture", server.url("/").toString(), "deleter"), "test-password"))
                graph.activate(account)
                if (fromDetail) graph.songCache.downloadCollection(requireNotNull(graph.credentials.value), snapshot)
                SessionRules.start(account, id, name, listOf(song, song), shuffle = false).also { graph.setSession(it) }
            }
            try {
                ActivityScenario.launch(MainActivity::class.java).use {
                    await(hasTestTag("view-all-playlists"))
                    runBlocking { graph.playlists.refresh().join() }
                    compose.onNodeWithTag("view-all-playlists").performClick()
                    await(hasContentDescription("More actions for Shared"))
                    compose.onNodeWithContentDescription("More actions for Shared").performClick()
                    compose.onNodeWithText("Add to Playlist").assertExists()
                    compose.onNodeWithText("Delete playlist").assertDoesNotExist()
                    back()
                    val launcher = if (fromDetail) {
                        compose.onNodeWithTag("playlist-card-$id").performClick()
                        await(hasTestTag("playlist-song-0"))
                        compose.onNodeWithTag("playlist-more")
                    } else compose.onNodeWithContentDescription("More actions for $name")
                    fun openConfirm() {
                        launcher.performClick()
                        compose.onNodeWithText("Delete playlist").performClick()
                        await(hasText("Delete “$name”?"))
                    }
                    openConfirm()
                    compose.onNodeWithTag("playlist-delete-cancel").assertIsFocused().performClick()
                    compose.onNodeWithText("Delete “$name”?").assertDoesNotExist()
                    launcher.assertIsFocused()
                    openConfirm()
                    back()
                    compose.onNodeWithText("Delete “$name”?").assertDoesNotExist()
                    assertTrue(writes.isEmpty())
                    openConfirm()
                    if (ownerChanged) owner.set("someone-else")
                    compose.onNodeWithTag("playlist-delete-confirm").performClick()
                    if (ownerChanged) {
                        await(hasText("This playlist changed or is no longer writable. Close and refresh playlists."))
                        assertTrue(writes.isEmpty())
                        assertFalse(removed.get())
                    } else {
                        if (fromDetail) {
                            try {
                                assertTrue(writeCommitted.await(10, TimeUnit.SECONDS))
                                runBlocking { graph.playlists.refresh().join() }
                                assertEquals(listOf("shared"), graph.playlists.state.value.playlists.map { it.id })
                            } finally {
                                releaseWriteReply.countDown()
                            }
                            await(hasText("Deletion couldn’t be verified. Check again without sending another delete."))
                            await(hasTestTag("playlist-delete-check"))
                            compose.onNodeWithTag("playlist-delete-confirm").assertDoesNotExist()
                            assertEquals(listOf(id), afterWriteReads.toList())
                            compose.onNodeWithTag("playlist-delete-cancel").performClick()
                            compose.onNodeWithTag("playlist-play").assertIsNotEnabled()
                            compose.onNodeWithTag("playlist-song-0").assertDoesNotExist()
                            compose.onNodeWithContentDescription("Back").performClick()
                            await(hasTestTag("playlist-card-$id"))
                            compose.onNodeWithTag("playlist-card-$id").assertIsNotEnabled()
                            compose.onNodeWithContentDescription("More actions for $name").performClick()
                            compose.onNodeWithText("Check deletion").performClick()
                            allowReadback.set(true)
                            compose.onNodeWithTag("playlist-delete-check").performClick()
                            assertEquals(snapshot, graph.songCache.collection(requireNotNull(graph.credentials.value), "playlist", id))
                        }
                        if (!acknowledged) {
                            await(hasText("“$name” is no longer on the music server. The server did not confirm the delete request."))
                            compose.onNodeWithText("Deleted “$name”.").assertDoesNotExist()
                        } else await(hasText("Deleted “$name”."))
                        compose.onNodeWithText("Playlists").assertIsFocused()
                        compose.onNodeWithTag("playlist-card-$id").assertDoesNotExist()
                        compose.onNodeWithTag("playlist-card-shared").assertExists()
                        assertEquals(listOf("POST:id=$id"), writes.toList())
                        assertEquals(when {
                            acknowledged -> emptyList()
                            fromDetail -> listOf(id, id)
                            else -> listOf(id)
                        }, afterWriteReads.toList())
                        val cachedIds = runBlocking {
                            graph.playlists.refresh().join()
                            graph.store.cachedPlaylists(account).map { it.id }
                        }
                        assertEquals(listOf("shared"), cachedIds)
                    }
                    val after = requireNotNull(graph.session.value)
                    assertEquals(before.copy(positionMs = after.positionMs), after)
                    assertFalse(graph.playback.value.playing)
                }
            } finally { runBlocking { graph.store.removeConnection(account) } }
        }
    }

    private fun await(matcher: SemanticsMatcher) = compose.waitUntil(20_000) {
        compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
    }

    private fun back() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()
        compose.waitForIdle()
    }
}
