package com.everyonesoftware.audio.playback

import com.everyonesoftware.audio.data.Song
import org.junit.Assert.*
import org.junit.Test

class PlaybackHistoryTest {
    private val songs = listOf(Song("a", "A", "Artist"), Song("b", "B", "Artist"), Song("a", "A", "Artist"), Song("c", "C", "Artist"))
    private fun session() = SessionRules.start("account", "collection", "Collection", songs, shuffle = false)

    @Test fun repeatPreviousDuplicateVisitsAndPausedRestoreAreEvidenceBased() {
        val saved = session().copy(played = listOf(2))
        val history = PlaybackHistory()
        assertEquals(listOf(2), history.observe(saved, 0, false))
        assertEquals(listOf(2), history.observe(saved, 1, false))
        history.observe(saved, 1, true)
        assertEquals(listOf(2, 1), history.observe(saved, 1, true, repeat = true))
        assertEquals(listOf(2, 1, 1), history.observe(saved, 0, true))
        assertEquals(listOf(2, 1, 1, 0), history.observe(saved, 2, true))
        assertEquals(listOf(2, 1, 1, 0, 2), history.observe(saved, 2, false, ended = true))
        assertEquals(listOf(2, 1, 1, 0, 2), history.observe(saved, 2, false, ended = true))
        assertEquals(emptyList<Int>(), history.observe(session(), 0, false))
        assertEquals(emptyList<Int>(), history.observe(saved.copy(connectionId = "other", played = emptyList()), 0, false))
    }

    @Test fun shufflePreservesOccurrenceEvidenceEvenForIdenticalSongs() {
        val saved = session().copy(index = 2, played = listOf(0, 1))
        val history = PlaybackHistory()
        history.observe(saved, 2, true)
        val shuffled = SessionRules.shuffleUpNext(saved)
        assertEquals(saved.queueId, shuffled.queueId)
        assertEquals(2, shuffled.occurrences[2])
        assertEquals(saved.played, history.observe(shuffled, 2, true))
        val next = shuffled.queue.lastIndex
        val visits = history.observe(shuffled, next, false)
        assertEquals(listOf(0, 1, 2), visits)
        assertEquals(saved.queue[0], shuffled.queue[shuffled.copy(played = visits).playedIndices[0]])
        assertEquals(saved.shuffle, SessionRules.shuffleUpNext(shuffled).shuffle)
    }

    @Test fun restoreSanitizesHistoryAndLegacyJsonDoesNotInventIt() {
        val legacy = """{"connectionId":"account","playlistId":"p","playlistName":"P","queue":[{"id":"a","title":"A","artist":"Artist"}],"index":0}"""
        val decoded = kotlinx.serialization.json.Json.decodeFromString<com.everyonesoftware.audio.data.ListeningSession>(legacy)
        assertTrue(SessionRules.restore(decoded).played.isEmpty())
        assertEquals(listOf(0), SessionRules.restore(decoded.copy(played = listOf(-1, 0, 45))).played)
    }

    @Test fun onlyStartedOccurrenceLeftEntersPlayedNotSelectedPrefixOrSkippedSongs() {
        val saved = session().copy(index = 2)
        val history = PlaybackHistory()
        assertEquals(emptyList<Int>(), history.observe(saved, 2, false))
        assertEquals(emptyList<Int>(), history.observe(saved, 2, true))
        assertEquals(listOf(2), history.observe(saved, 0, false))
        assertEquals(listOf(2), history.observe(saved, 1, false))
        assertEquals(listOf(2), history.observe(saved, 3, true))
        assertEquals(listOf(2, 3), history.observe(saved, 0, true))
        assertEquals(listOf(2, 3, 0), history.observe(saved, 2, true))
        assertEquals(saved.queueId, saved.copy(played = history.observe(saved, 2, false)).queueId)
    }
}
