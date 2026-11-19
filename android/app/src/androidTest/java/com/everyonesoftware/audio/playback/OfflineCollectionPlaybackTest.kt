package com.everyonesoftware.audio.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.everyonesoftware.audio.AudioApplication
import com.everyonesoftware.audio.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class OfflineCollectionPlaybackTest {
    @Test fun offlineCarListingSearchAndResolutionUseSnapshotWithoutServerAndPreserveDuplicates() = runBlocking {
        val graph = ApplicationProvider.getApplicationContext<AudioApplication>().graph
        graph.bootstrap()
        val previous = graph.credentials.value
        val settings = graph.songCache.state.value.settings
        val suffix = System.nanoTime().toString()
        var credentials = Credentials(Connection("offline-$suffix", "Offline", "https://offline.invalid", "fixture"), "secret")
        val one = Song("one-$suffix", "One", "Artist")
        val two = Song("two-$suffix", "Two", "Artist")
        val snapshot = OfflineCollection("playlist", "mix-$suffix", "Offline mix", songs = listOf(two, one, two))
        try {
            graph.songCache.setSettings(settings.copy(offline = true))
            graph.store.saveConnection(credentials)
            graph.activate(credentials.connection.id)
            // AudioStore canonicalizes server URLs. Exercise the activated identity,
            // as production callers do, rather than the pre-save fixture value.
            credentials = requireNotNull(graph.credentials.value)
            graph.songCache.downloadCollection(credentials, snapshot)
            val library = CarLibrary(graph)
            assertEquals(listOf(CarMediaIds.playlist(credentials.connection.id, snapshot.id)), library.children(CarMediaIds.PLAYLISTS).map { it.mediaId })
            assertEquals(1, library.search("Offline mix").size)
            try { library.resolveSearch("Offline mix"); fail("Partial collection was playable") } catch (_: IllegalArgumentException) { }
            // Release the partial pin before filling local fixture bytes. Otherwise switching
            // online starts a real pinned downloader that can reserve the cache hole first,
            // causing this foreground fixture source to bypass the cache rather than fill it.
            graph.songCache.removeCollection(credentials, snapshot.kind, snapshot.id)
            // Fill actual Media3 spans locally, then disable all server transport again.
            graph.songCache.setSettings(settings.copy(offline = false, autoSave = true, prefetch = 0))
            listOf(one, two).forEach { song ->
                val source = graph.songCache.factory(credentials, song, DataSource.Factory { ByteArrayDataSource(ByteArray(4096)) }).createDataSource()
                source.open(DataSpec.Builder().setUri("https://offline.invalid/${song.id}").build())
                val bytes = ByteArray(1024)
                while (source.read(bytes, 0, bytes.size) != -1) { }
                source.close()
                assertTrue("Fixture bytes must be complete before offline playback", graph.songCache.complete(credentials, song.id))
            }
            graph.songCache.setSettings(settings.copy(offline = true))
            graph.songCache.downloadCollection(credentials, snapshot)
            val fromVoice = library.resolveSearch("Offline mix").session
            assertEquals(snapshot.songs, fromVoice.queue)
            assertFalse(fromVoice.shuffle)
            assertEquals("playlist", fromVoice.sourceType)
            val fromAction = library.resolve(CarMediaIds.shuffle(credentials.connection.id, snapshot.id)).session
            assertEquals(snapshot.songs.map { it.id }.sorted(), fromAction.queue.map { it.id }.sorted())
            assertTrue(fromAction.shuffle)
            val fromTrack = library.resolve(CarMediaIds.track(credentials.connection.id, snapshot.id, 2, two.id)).session
            assertEquals(snapshot.songs, fromTrack.queue)
            assertEquals(2, fromTrack.index)
            val contents = library.children(CarMediaIds.playlist(credentials.connection.id, snapshot.id))
            assertEquals(listOf("Play", "Shuffle play"), contents.take(2).map { it.mediaMetadata.title.toString() })
            assertEquals(snapshot.songs.map { it.title }, contents.drop(2).map { it.mediaMetadata.title.toString() })
            assertEquals(5, contents.map { it.mediaId }.toSet().size)
        } finally {
            graph.store.removeConnection(credentials.connection.id)
            if (previous != null) graph.activate(previous.connection.id)
            graph.songCache.setSettings(settings)
        }
    }
}
