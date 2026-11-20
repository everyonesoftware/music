package com.everyonesoftware.audio.ui

import com.everyonesoftware.audio.data.*
import org.junit.Assert.*
import org.junit.Test

class ArtistBrowsingTest {
    private val songs = listOf(Song("two", "Two", "Artist"), Song("one", "One", "Artist"), Song("two", "Two", "Artist"))
    private val artist = Artist("a", "Artist")
    private fun page() = AudioUiState(booting = false, connection = Connection("c", "Music", "https://example.com", "u"), screen = Screen.ARTIST,
        selectedArtist = artist, artistContents = ArtistContents(artist, listOf(ArtistAlbum(Album("album", "Album"), songs))))

    @Test fun albumBackRetainsArtistAndOriginalOrigin() {
        for (origin in listOf(Screen.PLAYLISTS, Screen.SEARCH, Screen.ALL_ARTISTS)) {
            val artistPage = page().copy(screen = origin).navigateTo(Screen.ARTIST)
            val albumPage = artistPage.navigateTo(Screen.ALBUM)
            assertEquals(Screen.ARTIST, albumPage.backDestination)
            val back = albumPage.navigateTo(albumPage.backDestination)
            assertEquals(origin, back.backDestination)
            assertEquals(artistPage.artistContents, back.artistContents)
        }
    }

    @Test fun selectingDuplicateOccurrenceStartsTheCompleteOrderedArtistQueue() {
        val request = CollectionSongSelection("c", "artist", "a", 2, songs)
        val session = page().collectionSongSession(request)
        assertNotNull(session)
        assertEquals("artist", session!!.sourceType)
        assertEquals(songs, session.queue)
        assertEquals(2, session.index)
        assertEquals(listOf(0, 1, 2), session.occurrences)
        assertNotEquals(session.queueId, page().collectionSongSession(request)!!.queueId)
    }

    @Test fun staleWrongAccountLoadingFailedEmptyAndOfflineSelectionsCannotReplacePlayback() {
        val request = CollectionSongSelection("c", "artist", "a", 2, songs)
        val page = page()
        for (invalid in listOf(page.copy(booting = true), page.copy(loading = true), page.copy(artistLoading = true),
            page.copy(artistError = "Failed"), page.copy(artistContents = null), page.copy(selectedArtist = artist.copy(id = "other")),
            page.copy(connection = page.connection!!.copy(id = "other-account")), page.copy(screen = Screen.SEARCH),
            page.copy(storage = page.storage.copy(settings = page.storage.settings.copy(offline = true))),
            page.copy(artistContents = ArtistContents(artist, emptyList())),
            page.copy(artistContents = page.artistContents!!.copy(complete = false)))) assertNull(invalid.collectionSongSession(request))
        assertNull(page.collectionSongSession(request.copy(index = 3)))
        assertNull(page.collectionSongSession(request.copy(songs = songs.reversed().dropLast(1))))
    }

    @Test fun artistShuffleIsFreshAndRestorationPreservesOrderedDuplicateOccurrences() {
        val ordered = com.everyonesoftware.audio.playback.SessionRules.start("c", "a", "Artist", songs, sourceType = "artist", shuffle = false)
        repeat(3) { seed ->
            val shuffled = com.everyonesoftware.audio.playback.SessionRules.start("c", "a", "Artist", songs, kotlin.random.Random(seed), "artist", true)
            assertEquals(songs.groupingBy { it.id }.eachCount(), shuffled.queue.groupingBy { it.id }.eachCount())
            assertNotEquals(ordered.queueId, shuffled.queueId)
            assertEquals(shuffled.queue.size, shuffled.occurrences.distinct().size)
        }
        val restored = com.everyonesoftware.audio.playback.SessionRules.restore(ordered.copy(index = 2, positionMs = 1234L))
        assertEquals("artist", restored.sourceType)
        assertEquals(ordered.queueId, restored.queueId)
        assertEquals(songs, restored.queue)
        assertEquals(2, restored.index)
        assertEquals(1234L, restored.positionMs)
    }
}
