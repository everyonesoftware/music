package com.everyonesoftware.audio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.everyonesoftware.audio.data.Connection
import com.everyonesoftware.audio.data.Credentials
import com.everyonesoftware.audio.data.OfflineCollection
import com.everyonesoftware.audio.data.Song
import com.everyonesoftware.audio.playback.SessionRules
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class PlaylistEditingFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun createAndAppendUseExactServerIdentityAndPreservePlayback() = exercise()
    @Test fun retryCreatedPlaylistStillChecksExpectedOccurrences() = exercise(createMismatch = true)
    @Test fun lostAppendAcknowledgementInvalidatesOpenDetail() = exercise(loseAppend = true)
    @Test fun searchShuffleDoesNotUseObsoletePin() = exercise(shuffleFromSearch = true)
    @Test fun searchShuffleReadsLiveMembershipForSameCountPinWithoutLocalEdit() = exercise(sameCountPin = true)

    private fun exercise(createMismatch: Boolean = false, loseAppend: Boolean = false, shuffleFromSearch: Boolean = false, sameCountPin: Boolean = false) {
        val app = ApplicationProvider.getApplicationContext<AudioApplication>()
        val graph = app.graph
        val writes = CopyOnWriteArrayList<Pair<String, List<String>>>()
        val playlists = ConcurrentHashMap(mapOf("destination" to listOf("s0"), "shared" to emptyList<String>()))
        if (sameCountPin) playlists["destination"] = listOf("s0", "s1", "s1")
        val pinnedIds = if (sameCountPin) listOf("s0", "s0", "s1") else listOf("s0")
        val pinned = OfflineCollection("playlist", "destination", "Destination",
            songs = pinnedIds.map { Song(it, "Track $it", "Audio test", "") })
        val names = ConcurrentHashMap(mapOf("destination" to "Destination", "shared" to "Shared"))
        val failCreatedRead = AtomicBoolean(true)
        val failDestinationRead = AtomicBoolean(false)
        val loseNextAppend = AtomicBoolean(false)
        val audioBytes = 180 * 8_000 * 2
        val wav = ByteBuffer.allocate(44 + audioBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + audioBytes); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(8_000); putInt(16_000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(audioBytes)
        }.array()
        fun song(id: String) = """{"id":"$id","title":"Track $id","duration":180}"""
        fun playlist(id: String, includeTracks: Boolean): String {
            val ids = playlists.getValue(id)
            val owner = if (id == "shared") "another-user" else "playlist-fixture"
            val entries = if (includeTracks) ",\"entry\":[${ids.joinToString(",", transform = ::song)}]" else ""
            return """{"id":"$id","name":"${names.getValue(id)}","owner":"$owner","songCount":${ids.size}$entries}"""
        }
        fun ok(fields: String = "") = MockResponse().setBody("""{"subsonic-response":{"status":"ok"$fields}}""")
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val url = request.requestUrl ?: return MockResponse().setResponseCode(400)
                    if (url.queryParameter("u") != "playlist-fixture") return MockResponse().setResponseCode(401)
                    return when (url.encodedPath.substringAfterLast('/')) {
                        "ping.view" -> ok()
                        "getArtists.view" -> ok(",\"artists\":{}")
                        "getAlbumList2.view" -> ok(",\"albumList2\":{\"album\":[{\"id\":\"album\",\"name\":\"Source album\",\"songCount\":3}]}")
                        "getAlbum.view" -> ok(",\"album\":{\"id\":\"album\",\"name\":\"Source album\",\"songCount\":3,\"song\":[${listOf("s1", "s2", "s1").joinToString(",", transform = ::song)}]}")
                        "getSong.view" -> ok(",\"song\":${song(url.queryParameter("id")!!)}")
                        "search3.view" -> ok(",\"searchResult3\":{}")
                        "stream.view" -> MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(wav))
                        "getPlaylists.view" -> ok(",\"playlists\":{\"playlist\":[${playlists.keys.sorted().joinToString(",") { playlist(it, false) }}]}")
                        "getPlaylist.view" -> {
                            val id = url.queryParameter("id")!!
                            if ((id == "created" && failCreatedRead.get()) || (id == "destination" && failDestinationRead.get())) MockResponse().setResponseCode(503)
                            else ok(",\"playlist\":${playlist(id, true)}")
                        }
                        "createPlaylist.view" -> {
                            if (request.method != "POST") return MockResponse().setResponseCode(405)
                            val form = ("http://localhost/?" + request.body.readUtf8()).toHttpUrl()
                            val name = form.queryParameter("name")!!
                            val seeds = form.queryParameterValues("songId").filterNotNull()
                            writes.add("create:$name" to seeds)
                            // A concurrently created playlist has the same name, so neither list diff nor name can select the created entry.
                            names["concurrent"] = name
                            playlists["concurrent"] = listOf("s0")
                            names["created"] = name
                            playlists["created"] = seeds
                            ok(",\"playlist\":${playlist("created", true)}")
                        }
                        "updatePlaylist.view" -> {
                            if (request.method != "POST") return MockResponse().setResponseCode(405)
                            val form = ("http://localhost/?" + request.body.readUtf8()).toHttpUrl()
                            val id = form.queryParameter("playlistId")!!
                            val seeds = form.queryParameterValues("songIdToAdd").filterNotNull()
                            writes.add("append:$id" to seeds)
                            playlists.compute(id) { _, old -> old.orEmpty() + seeds }
                            if (loseNextAppend.getAndSet(false)) {
                                failDestinationRead.set(true)
                                MockResponse().setResponseCode(503)
                            } else ok()
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
            val account = "playlist-edit-${UUID.randomUUID()}"
            val session = runBlocking {
                graph.bootstrap()
                graph.store.saveConnection(Credentials(Connection(account, "Playlist editing", server.url("/").toString(), "playlist-fixture"), "fixture-password"))
                graph.activate(account)
                if (shuffleFromSearch || sameCountPin) graph.songCache.downloadCollection(requireNotNull(graph.credentials.value), pinned)
                SessionRules.start(account, "fixture", "Fixture queue", listOf(Song("s2", "Track s2", "Audio test", "Source album")), shuffle = false)
                    .also { graph.setSession(it) }
            }
            try {
                ActivityScenario.launch(MainActivity::class.java).use {
                    awaitTag("new-playlist")
                    if (sameCountPin) {
                        compose.onNodeWithText("Search playlists, albums, artists, and songs").performClick()
                        compose.onNodeWithTag("library-search").performTextInput("Destination")
                        val shuffle = compose.onNodeWithContentDescription("Shuffle and play Destination")
                        compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription("Shuffle and play Destination").fetchSemanticsNodes().isNotEmpty() }
                        shuffle.performClick()
                        compose.waitUntil(20_000) { graph.session.value?.let { it.queueId != session.queueId && it.playlistId == "destination" } == true }
                        assertEquals(listOf("s0", "s1", "s1"), graph.session.value?.queue?.map { it.id }?.sorted())
                        assertEquals(pinned, graph.songCache.collection(requireNotNull(graph.credentials.value), "playlist", "destination"))
                        assertTrue(writes.isEmpty())
                        return@use
                    }
                    compose.onNodeWithTag("new-playlist").performClick()
                    compose.onNodeWithText("Create").assertIsNotEnabled()
                    compose.onNodeWithTag("playlist-name").performTextInput("Empty fixture")
                    compose.onNodeWithText("Create").performClick()
                    awaitText("Playlist created, but songs couldn’t be loaded. Retry to open the same playlist.")
                    assertEquals(listOf("create:Empty fixture" to emptyList<String>()), writes.toList())
                    if (createMismatch) playlists["created"] = listOf("s0", "s0")
                    failCreatedRead.set(false)
                    compose.onNodeWithText("Retry").performClick()
                    if (createMismatch) {
                        awaitText("Playlist created, but the server returned different songs. Showing the current playlist.")
                        assertEquals(1, writes.size)
                        return@use
                    }
                    awaitText("This playlist has no songs.")
                    compose.onNodeWithText("Browse Library").performClick()
                    awaitTag("view-all-albums")
                    compose.onNodeWithTag("library-content").performScrollToNode(hasTestTag("view-all-albums"))
                    compose.onNodeWithTag("view-all-albums").performClick()
                    awaitTag("album-card-album")
                    compose.onNodeWithContentDescription("More actions for Source album").performClick()
                    compose.onNodeWithText("Add to Playlist").performClick()
                    awaitTag("playlist-destination-destination")
                    compose.onNodeWithTag("playlist-destination-shared").assertDoesNotExist()
                    compose.onNodeWithTag("playlist-seed-summary").assertTextEquals("3 songs")
                    compose.onNodeWithTag("playlist-destination-destination").performClick()
                    awaitText("Added to Destination.")
                    compose.onNodeWithTag("album-card-album").assertExists()
                    assertEquals(listOf("s0", "s1", "s2", "s1"), playlists["destination"])
                    assertEquals(listOf("create:Empty fixture" to emptyList(), "append:destination" to listOf("s1", "s2", "s1")), writes.toList())
                    val after = requireNotNull(graph.session.value)
                    assertEquals(session.copy(positionMs = after.positionMs), after)
                    assertFalse(graph.playback.value.playing)
                    if (loseAppend || shuffleFromSearch) {
                        compose.onNodeWithContentDescription("Back").performClick()
                        compose.onNodeWithText("Search playlists, albums, artists, and songs").performClick()
                        compose.onNodeWithTag("library-search").performTextInput("Destination")
                        awaitText("Destination")
                    }
                    if (loseAppend) {
                        compose.onNode(hasText("Destination") and hasClickAction() and hasAnyAncestor(hasTestTag("search-results"))).performClick()
                        awaitTag("playlist-song-0")
                        compose.onNodeWithTag("playlist-more").performClick()
                        compose.onNodeWithText("Add to Playlist").performClick()
                        awaitTag("playlist-destination-destination")
                        loseNextAppend.set(true)
                        compose.onNodeWithTag("playlist-destination-destination").performClick()
                        awaitText("Close")
                        compose.onNodeWithText("Close").performClick()
                        awaitText("Retry")
                        compose.onNodeWithTag("playlist-play").assertIsNotEnabled()
                        compose.onNodeWithTag("playlist-song-0").assertDoesNotExist()
                        failDestinationRead.set(false)
                        compose.onNodeWithText("Retry").performClick()
                        awaitText("8 songs")
                        compose.onNodeWithTag("playlist-play").assertIsEnabled()
                        assertEquals(3, writes.size)
                        assertEquals(after, graph.session.value)
                    }
                    if (shuffleFromSearch) {
                        compose.onNodeWithContentDescription("Shuffle and play Destination").performClick()
                        compose.waitUntil(20_000) { graph.session.value?.let { it.queueId != session.queueId && it.playlistId == "destination" } == true }
                        assertEquals(listOf("s0", "s1", "s1", "s2"), graph.session.value?.queue?.map { it.id }?.sorted())
                        assertEquals(listOf("s0"), graph.songCache.collection(requireNotNull(graph.credentials.value), "playlist", "destination")?.songs?.map { it.id })
                    }
                }
            } finally { runBlocking { graph.store.removeConnection(account) } }
        }
    }

    private fun awaitTag(tag: String) = compose.waitUntil(20_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitText(text: String) = compose.waitUntil(20_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
}
