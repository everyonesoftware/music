package com.everyonesoftware.audio

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.everyonesoftware.audio.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioGraphTest {
    private fun connection(id: String) = Connection(id, "Test $id", "https://example.invalid/$id", "test-user")
    private val songs = listOf(Song("s1", "One", "Fixture"), Song("s2", "Two", "Fixture"))

    @Test fun shuffleRetryFailureRepairsDurableOrderAndLatestProgress() = shufflePersistenceFailure("retry-failure")
    @Test fun shuffleRetryCancellationRepairsDurableOrderAndLatestProgress() = shufflePersistenceFailure("retry-cancel")
    @Test fun shuffleCancellationAfterCommitRepairsDurableOrderAndLatestProgress() = shufflePersistenceFailure("after-commit")

    private fun shufflePersistenceFailure(mode: String) = runBlocking {
        val graph = AudioGraph(ApplicationProvider.getApplicationContext())
        val id = "up-next-$mode"
        val field = graph.store.javaClass.getDeclaredField("dao").apply { isAccessible = true }
        val real = field.get(graph.store) as AudioDao
        try {
            graph.store.saveConnection(Credentials(connection(id), "test-only-password"))
            graph.activate(id)
            val saved = ListeningSession(id, "p", "P", songs + songs + songs, index = 1,
                positionMs = 123, played = listOf(0, 1, 0), sourceType = "playlist")
            graph.setSession(saved)
            var live = Triple(1, 123L, 0)
            var writes = 0
            val failure = java.io.IOException("Controlled retry failure")
            field.set(graph.store, object : AudioDao by real {
                override suspend fun saveSession(session: StoredSession) {
                    writes++
                    if (writes == 2 && mode != "after-commit") {
                        live = Triple(3, 7654L, 1)
                        if (mode == "retry-failure") throw failure
                        kotlinx.coroutines.currentCoroutineContext().let {
                            it[kotlinx.coroutines.Job]!!.cancel()
                            it.ensureActive()
                        }
                    }
                    real.saveSession(session)
                    if (writes == 1) {
                        live = Triple(2, 4567L, 2)
                        if (mode == "after-commit") {
                            kotlinx.coroutines.currentCoroutineContext().let {
                                it[kotlinx.coroutines.Job]!!.cancel()
                                it.ensureActive()
                            }
                        }
                    }
                }
            })
            var caught: Throwable? = null
            val pending = async {
                try { graph.shuffleUpNext(graph.credentials.value!!, saved) { live } }
                catch (error: Throwable) { caught = error }
            }
            pending.join()
            if (mode == "retry-failure") assertSame(failure, caught)
            else assertTrue(caught is kotlinx.coroutines.CancellationException)
            val expected = saved.copy(index = live.first, positionMs = live.second, repeatMode = live.third)
            assertEquals("Durable state must match the untouched order and latest player", expected, graph.store.session(id))
            assertEquals("Published state must retain latest progress too", expected, graph.session.value)
        } finally {
            field.set(graph.store, real)
            graph.store.removeConnection(id)
        }
    }

    @Test fun shuffleWaitingForStoreCannotPersistAnOrderPastTheNewCurrent() = runBlocking {
        val graph = AudioGraph(ApplicationProvider.getApplicationContext())
        val id = "up-next-store-race"
        try {
            graph.store.saveConnection(Credentials(connection(id), "test-only-password"))
            graph.activate(id)
            val saved = ListeningSession(id, "p", "P", songs + songs + songs, index = 1, positionMs = 123, played = listOf(0))
            graph.setSession(saved)
            val mutex = graph.store.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(graph.store) as kotlinx.coroutines.sync.Mutex
            var live = Triple(1, 123L, 0)
            mutex.lock()
            val pending = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                graph.shuffleUpNext(graph.credentials.value!!, saved) { live }
            }
            assertFalse(pending.isCompleted)
            live = Triple(saved.queue.lastIndex, 9876L, 2)
            mutex.unlock()
            assertFalse(pending.await())
            val expected = saved.copy(index = saved.queue.lastIndex, positionMs = 9876L, repeatMode = 2)
            assertEquals(expected, graph.session.value)
            assertEquals(expected, graph.store.session(id))
        } finally { graph.store.removeConnection(id) }
    }

    @Test fun shuffleUsesLatestLockedProgressHistoryAndRejectsStaleAccountQueueOrderOrController() = runBlocking {
        val graph = AudioGraph(ApplicationProvider.getApplicationContext())
        val id = "up-next-guards"
        try {
            graph.store.saveConnection(Credentials(connection(id), "test-only-password"))
            graph.activate(id)
            val owner = graph.credentials.value!!
            val saved = ListeningSession(id, "p", "P", songs + songs + songs, index = 0, queueId = "up-next-guards")
            graph.setSession(saved)
            graph.updateProgress(1, 23456, 2)
            graph.updatePlayed(owner, saved.queueId, listOf(0, 1, 0))
            graph.applyRating(id, "s1", 4)
            val latest = graph.session.value!!
            assertTrue(graph.shuffleUpNext(owner, saved) { Triple(latest.index, latest.positionMs, latest.repeatMode) })
            val next = graph.session.value!!
            assertEquals(latest.queue.take(2), next.queue.take(2))
            assertEquals(latest.played, next.played)
            assertEquals(latest.positionMs, next.positionMs)
            assertEquals(latest.repeatMode, next.repeatMode)
            assertEquals(latest.queue.zip(latest.occurrences).toSet(), next.queue.zip(next.occurrences).toSet())
            assertEquals(next, graph.store.session(id))
            for (stale in listOf(next.copy(connectionId = "other"), next.copy(queueId = "other"),
                next.copy(occurrenceIds = next.occurrences.reversed()), next.copy(queue = next.queue.reversed()))) {
                assertFalse(graph.shuffleUpNext(owner, stale) { error("Stale session must fail before controller sampling") })
                assertEquals(next, graph.session.value)
            }
            assertFalse(graph.shuffleUpNext(owner.copy(password = "changed"), next) { error("Stale account") })
            assertFalse(graph.shuffleUpNext(owner, next) { null })
            var checks = 0
            assertFalse(graph.shuffleUpNext(owner, next) { if (++checks == 1) Triple(1, 23456L, 2) else null })
            assertEquals(next, graph.session.value)
            assertEquals(next, graph.store.session(id))
            graph.updateProgress(5, 999, 0, id, next.queueId, next.queue.map { it.id }, saved.occurrences)
            if (next.occurrences != saved.occurrences) assertEquals(next, graph.session.value)
        } finally { graph.store.removeConnection(id) }
    }

    @Test fun pausedSeekWhileShuffleWaitsForStoragePersistsTheNewPosition() = runBlocking {
        val graph = AudioGraph(ApplicationProvider.getApplicationContext())
        val id = "up-next-paused-seek"
        try {
            graph.store.saveConnection(Credentials(connection(id), "test-only-password"))
            graph.activate(id)
            val saved = ListeningSession(id, "p", "P", songs + songs + songs, index = 1, positionMs = 123)
            graph.setSession(saved)
            val mutex = graph.store.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(graph.store) as kotlinx.coroutines.sync.Mutex
            var live = Triple(1, 123L, 0)
            mutex.lock()
            val pending = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                graph.shuffleUpNext(graph.credentials.value!!, saved) { live }
            }
            assertFalse(pending.isCompleted)
            live = Triple(1, 54321L, 0)
            mutex.unlock()
            assertTrue(pending.await())
            assertEquals(54321L, graph.session.value!!.positionMs)
            assertEquals(graph.session.value, graph.store.session(id))
        } finally { graph.store.removeConnection(id) }
    }

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
