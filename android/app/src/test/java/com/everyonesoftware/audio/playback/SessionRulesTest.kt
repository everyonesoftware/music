package com.everyonesoftware.audio.playback

import com.everyonesoftware.audio.data.ListeningSession
import com.everyonesoftware.audio.data.Song
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class SessionRulesTest {
    private val songs = (1..8).map { Song("song-$it", "Track $it", "Test artist") }

    @Test fun `up next shuffle preserves prefix current position identity and duplicate occurrences on every tap`() {
        val saved = ListeningSession("server-a", "playlist-a", "Test", songs + songs.first(),
            index = 2, positionMs = 42137, shuffle = true, repeatMode = 2,
            occurrenceIds = (10..18).toList(), played = listOf(10, 11, 10))
        val first = SessionRules.shuffleUpNext(saved)
        assertEquals(saved.queue.take(3), first.queue.take(3))
        assertEquals(saved.occurrences.take(3), first.occurrences.take(3))
        assertEquals(saved.index, first.index)
        assertEquals(saved.positionMs, first.positionMs)
        assertEquals(saved.played, first.played)
        assertEquals(saved.queueId, first.queueId)
        assertEquals(saved.shuffle, first.shuffle)
        assertEquals(saved.occurrences.sorted(), first.occurrences.sorted())
        assertEquals(saved.queue.zip(saved.occurrences).toSet(), first.queue.zip(first.occurrences).toSet())
        assertTrue((1..20).map { SessionRules.shuffleUpNext(first).occurrences }.toSet().size > 1)
    }

    @Test fun `up next shuffle is inert for missing current or fewer than two remaining`() {
        for (index in listOf(-1, songs.lastIndex - 1, songs.lastIndex, songs.size)) {
            val session = ListeningSession("a", "p", "P", songs, index = index)
            assertSame(session, SessionRules.shuffleUpNext(session))
        }
    }

    @Test fun `up next action ignores either legacy mode and never restores ordered queue`() {
        for (legacy in listOf(false, true)) {
            val saved = ListeningSession("a", "p", "P", songs, index = 1, shuffle = legacy)
            val first = SessionRules.shuffleUpNext(saved, Random(7))
            val next = SessionRules.shuffleUpNext(first, Random(9))
            assertNotEquals(saved.queue, first.queue)
            assertNotEquals(first.queue, next.queue)
            assertEquals(saved.queue.take(2), next.queue.take(2))
            assertEquals(legacy, next.shuffle)
        }
    }

    @Test fun `every eligible tap changes pending occurrence order even when random chooses original order`() {
        val saved = ListeningSession("a", "p", "P", listOf(songs[0], songs[0], songs[0]), index = 0)
        for (seed in 0..20) {
            val next = SessionRules.shuffleUpNext(saved, Random(seed))
            assertNotEquals("Seed $seed must still act on the two pending duplicates", saved.occurrences, next.occurrences)
            assertEquals(saved.occurrences.take(1), next.occurrences.take(1))
        }
    }

    @Test fun `explicit restart has a new queue identity even for a single song`() {
        val first = SessionRules.start("server-a", "playlist-a", "One song", songs.take(1))
        val restarted = SessionRules.start("server-a", "playlist-a", "One song", songs.take(1))
        assertNotEquals(first.queueId, restarted.queueId)
        assertEquals(first.queue, restarted.queue)
        assertEquals(first.queueId, SessionRules.restore(first).queueId)
    }

    @Test fun `shuffle preserves every playlist occurrence including repeated songs`() {
        val original = songs + songs.first()
        val session = SessionRules.start("server-a", "playlist-a", "Test playlist", original, Random(7))
        assertEquals(original.groupingBy { it.id }.eachCount(), session.queue.groupingBy { it.id }.eachCount())
        assertNotEquals(original, session.queue)
        assertEquals("server-a", session.connectionId)
        assertEquals(0, session.index)
        assertEquals(0L, session.positionMs)
    }

    @Test fun `album playback records album origin without changing queue semantics`() {
        val session = SessionRules.start("server-a", "album-a", "Album A", songs, Random(7), sourceType = "album", shuffle = false)
        assertEquals("album", session.sourceType)
        assertFalse(session.shuffle)
        assertEquals(songs, session.queue)
    }

    @Test fun `song search playback records song origin and restores without shuffle`() {
        val song = songs.first()
        val session = SessionRules.start("server-a", song.id, song.title, listOf(song), sourceType = "song", shuffle = false)
        assertEquals("song", session.sourceType)
        assertFalse(session.shuffle)
        assertEquals(listOf(song), session.queue)
        assertEquals(session, SessionRules.restore(session))
    }

    @Test fun `restoring a paused session preserves exact shuffle order and position`() {
        val original = ListeningSession("server-a", "playlist-a", "Test playlist", songs.reversed(), 3, 42137L, true, 2)
        assertEquals(original, SessionRules.restore(original))
    }

    @Test fun `empty playlist cannot replace a listening session`() {
        assertThrows(IllegalArgumentException::class.java) {
            SessionRules.start("server-a", "empty", "Empty playlist", emptyList(), Random(1))
        }
    }

    @Test fun `progress updates do not change queue or connection`() {
        val original = ListeningSession("server-a", "playlist-a", "Test playlist", songs, 0, 0L)
        val next = SessionRules.progress(original, 4, 12345L, 1)
        assertEquals(original.queue, next.queue)
        assertEquals(original.connectionId, next.connectionId)
        assertEquals(4, next.index)
        assertEquals(12345L, next.positionMs)
        assertEquals(1, next.repeatMode)
    }

    @Test fun `invalid saved index is clamped and negative position cannot persist`() {
        val original = ListeningSession("server-a", "playlist-a", "Test playlist", songs, 500, -5L)
        val restored = SessionRules.restore(original)
        assertEquals(songs.lastIndex, restored.index)
        assertEquals(0L, restored.positionMs)
    }
}
