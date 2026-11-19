package com.everyonesoftware.audio

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.everyonesoftware.audio.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioGraphTest {
    private fun connection(id: String) = Connection(id, "Test $id", "https://example.invalid/$id", "test-user")
    private val songs = listOf(Song("s1", "One", "Fixture"), Song("s2", "Two", "Fixture"))

    @Test fun playedEvidenceMergesWithProgressPersistsAndRejectsOldQueueOrAccount() = runBlocking {
        val graph = AudioGraph(ApplicationProvider.getApplicationContext())
        val a = "graph-history-a"
        val b = "graph-history-b"
        try {
            for (id in listOf(a, b)) graph.store.saveConnection(Credentials(connection(id), "test-only-password"))
            graph.activate(a)
            val credentials = graph.credentials.value!!
            val saved = ListeningSession(a, "p", "Playlist", songs + songs[0], index = 2, queueId = "history")
            graph.setSession(saved)
            graph.updatePlayed(credentials, saved.queueId, listOf(2))
            graph.updateProgress(0, 100, 2)
            graph.applyRating(a, "s1", 5)
            assertEquals(listOf(2), graph.session.value!!.played)
            assertEquals(graph.session.value, graph.store.session(a))
            val restored = AudioGraph(ApplicationProvider.getApplicationContext())
            restored.activate(a)
            assertEquals(listOf(2), restored.session.value!!.played)
            assertFalse(restored.playback.value.playing)
            val shuffled = graph.session.value!!.copy(occurrenceIds = listOf(2, 1, 0), index = 0)
            graph.updatePlayed(credentials, saved.queueId, listOf(2, 0))
            graph.setSession(shuffled)
            assertEquals(listOf(2, 0), graph.session.value!!.played)
            graph.updateProgress(2, 999, 0, a, saved.queueId, saved.queue.map { it.id }, expectedOccurrences = listOf(0, 1, 2))
            assertEquals(shuffled.copy(played = listOf(2, 0)), graph.session.value)
            graph.setSession(saved.copy(queueId = "new"))
            graph.updatePlayed(credentials, saved.queueId, listOf(1, 2))
            assertTrue(graph.session.value!!.played.isEmpty())
            graph.activate(b)
            graph.setSession(saved.copy(connectionId = b, queueId = "history"))
            graph.updatePlayed(credentials, "history", listOf(2))
            assertTrue(graph.session.value!!.played.isEmpty())
        } finally { graph.store.removeConnection(a); graph.store.removeConnection(b) }
    }

    @Test fun lateProgressCannotOverwriteAnExplicitRestartOfTheSamePlaylist() = runBlocking {
        val graph = AudioGraph(ApplicationProvider.getApplicationContext())
        val id = "graph-late-progress-fixture"
        try {
            graph.store.saveConnection(Credentials(connection(id), "test-only-password"))
            graph.activate(id)
            val replacement = ListeningSession(id, "p1", "Fixture", songs, queueId = "new-run")
            graph.setSession(replacement)
            graph.updateProgress(1, 25000, 2, id, expectedQueueId = "old-run", expectedQueueIds = songs.map { it.id })
            assertEquals(replacement, graph.session.value)
            assertEquals(replacement, graph.store.session(id))
        } finally { graph.store.removeConnection(id) }
    }

    @Test fun coldBootstrapRestoresExactQueueAndPositionWithoutAutoplay() = runBlocking {
        val graph = AudioGraph(ApplicationProvider.getApplicationContext())
        val id = "graph-restore-fixture"
        try {
            graph.store.saveConnection(Credentials(connection(id), "test-only-password"))
            graph.store.selectConnection(id)
            val session = ListeningSession(id, "p1", "Fixture playlist", songs.reversed(), 1, 42001, true, 2)
            graph.store.saveSession(session)
            graph.bootstrap()
            assertEquals(session, graph.session.value)
            assertEquals(id, graph.credentials.value?.connection?.id)
            assertFalse(graph.playback.value.playing)
        } finally { graph.store.removeConnection(id) }
    }

    @Test fun ratingResponseForPreviousConnectionCannotMutateActiveConnection() = runBlocking {
        val graph = AudioGraph(ApplicationProvider.getApplicationContext())
        val a = "graph-rating-fixture-a"
        val b = "graph-rating-fixture-b"
        try {
            for (id in listOf(a, b)) {
                graph.store.saveConnection(Credentials(connection(id), "test-only-password"))
                graph.store.saveSession(ListeningSession(id, "p1", "Fixture", songs))
            }
            graph.activate(a)
            graph.activate(b)
            graph.applyRating(a, "s1", 4)
            assertEquals(4, graph.store.session(a)!!.queue.first().userRating)
            assertEquals(0, graph.session.value!!.queue.first().userRating)
            assertEquals(b, graph.credentials.value!!.connection.id)
        } finally { graph.store.removeConnection(a); graph.store.removeConnection(b) }
    }

    @Test fun progressRetainsConfirmedRatingAndQueueMetadata() = runBlocking {
        val graph = AudioGraph(ApplicationProvider.getApplicationContext())
        val id = "graph-progress-fixture"
        try {
            graph.store.saveConnection(Credentials(connection(id), "test-only-password"))
            graph.activate(id)
            graph.setSession(ListeningSession(id, "p1", "Fixture", songs))
            graph.applyRating(id, "s1", 5)
            graph.updateProgress(1, 1234, 1)
            val session = graph.store.session(id)!!
            assertEquals(5, session.queue.first().userRating)
            assertEquals(1, session.index)
            assertEquals(1234L, session.positionMs)
            assertEquals(listOf("s1", "s2"), session.queue.map { it.id })
        } finally { graph.store.removeConnection(id) }
    }
}
