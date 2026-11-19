package com.everyonesoftware.audio.playback

import org.junit.Assert.*
import org.junit.Test

class CarMediaIdsTest {
    @Test fun albumIdsAreCanonicalConnectionScopedCollections() {
        assertEquals(CarMediaIds.Item("album", "c", "p"), CarMediaIds.parse("audio:album:Yw:cA"))
        assertNull(CarMediaIds.parse("audio:album:Yw==:cA"))
        assertNull(CarMediaIds.parse("audio:album:Yw:cA:0"))
    }

    @Test fun albumActionsAndOccurrencesRoundTripWithoutPlaylistNamespaceCollisions() {
        assertEquals(CarMediaIds.Item("albumplay", "c", "p"), CarMediaIds.parse(CarMediaIds.albumPlay("c", "p")))
        assertEquals(CarMediaIds.Item("albumshuffle", "c", "p"), CarMediaIds.parse(CarMediaIds.albumShuffle("c", "p")))
        val track = CarMediaIds.albumTrack("c", "p", 2, "song:雨")
        assertEquals(CarMediaIds.Item("albumtrack", "c", "p", 2, "song:雨"), CarMediaIds.parse(track))
        assertNotEquals(track, CarMediaIds.track("c", "p", 2, "song:雨"))
        assertNotEquals(track, CarMediaIds.albumTrack("c", "p", 0, "song:雨"))
        for (id in listOf(track.replace(":2:", ":02:"), track.replace(":2:", ":-1:"), track + ":extra",
            "audio:albumplay:Yw==:cA", "audio:albumshuffle:Yw:cA:0", "audio:albumtrack:Yw:cA:0")) assertNull(CarMediaIds.parse(id))
    }

    @Test fun opaqueIdsPreserveUnicodeAndPunctuationExactly() {
        val connection = "connection: / 雨"
        val playlist = "playlist/one:two"
        val song = "song:+/=?"
        assertEquals(CarMediaIds.Item("playlist", connection, playlist), CarMediaIds.parse(CarMediaIds.playlist(connection, playlist)))
        assertEquals(CarMediaIds.Item("shuffle", connection, playlist), CarMediaIds.parse(CarMediaIds.shuffle(connection, playlist)))
        assertEquals(CarMediaIds.Item("track", connection, playlist, 2, song), CarMediaIds.parse(CarMediaIds.track(connection, playlist, 2, song)))
        assertEquals(CarMediaIds.Item("queued", connection, "queue:1", 2), CarMediaIds.parse(CarMediaIds.queued(connection, "queue:1", 2)))
    }

    @Test fun repeatedSongsHaveDistinctOccurrenceIds() {
        assertNotEquals(CarMediaIds.queued("c", "q", 0), CarMediaIds.queued("c", "q", 2))
        assertNotEquals(CarMediaIds.track("c", "p", 0, "s"), CarMediaIds.track("c", "p", 2, "s"))
    }

    @Test fun urlsUnknownNamespacesAndNonCanonicalIdsAreRejected() {
        val valid = CarMediaIds.queued("c", "q", 0)
        for (id in listOf("https://example.invalid/song", "content://anything", "song", "", "audio:root",
            valid + ":extra", valid.dropLast(1) + "-1", valid.dropLast(1) + "00", valid.replace("queued", "unknown"),
            "audio:queued:Yw==:cQ:0", "audio:queued::cQ:0", "audio:track:Yw:cA:0:/", "x".repeat(16_385))) {
            assertNull(id.take(80), CarMediaIds.parse(id))
        }
    }
}
