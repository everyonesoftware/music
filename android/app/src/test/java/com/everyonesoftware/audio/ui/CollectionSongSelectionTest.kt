package com.everyonesoftware.audio.ui

import com.everyonesoftware.audio.data.*
import org.junit.Assert.*
import org.junit.Test

class CollectionSongSelectionTest {
    private val songs = listOf(Song("a", "A", "Artist"), Song("b", "B", "Artist"), Song("a", "A", "Artist"))
    private fun page(kind: String) = AudioUiState(booting = false, connection = Connection("c", "C", "https://example.invalid", "u"),
        screen = if (kind == "album") Screen.ALBUM else Screen.PLAYLIST,
        selectedAlbum = Album("a", "Album"), albumSongs = songs, selectedPlaylist = Playlist("p", "Playlist"), playlistSongs = songs)
    @Test fun selectedDuplicateStartsWholeOrderedCollectionAtExactOccurrenceWithFreshIdentity() {
        for (kind in listOf("playlist", "album")) {
            val page = page(kind)
            val request = CollectionSongSelection("c", kind, if (kind == "album") "a" else "p", 2, songs)
            val selected = requireNotNull(page.collectionSongSession(request))
            assertEquals(songs, selected.queue)
            assertEquals(2, selected.index)
            assertEquals(kind, selected.sourceType)
            assertFalse(selected.shuffle)
            assertTrue(selected.played.isEmpty())
            assertNotEquals(selected.queueId, page.collectionSongSession(request)!!.queueId)
        }
    }
    @Test fun staleAccountCollectionOccurrenceMetadataAndUnavailableDetailsCannotStartPlayback() {
        for (kind in listOf("playlist", "album")) {
            val page = page(kind)
            val request = CollectionSongSelection("c", kind, if (kind == "album") "a" else "p", 2, songs)
            for (invalid in listOf(request.copy(connectionId = "other"), request.copy(id = "other"), request.copy(index = 3),
                request.copy(index = -1), request.copy(songs = songs.reversed().dropLast(1)), request.copy(kind = "song"))) {
                assertNull(page.collectionSongSession(invalid))
            }
            for (invalid in listOf(page.copy(loading = true), page.copy(booting = true), page.copy(screen = Screen.PLAYER),
                page.copy(playlistLoading = true, albumLoading = true), page.copy(playlistDetailError = "failed", albumError = "failed"),
                page.copy(storage = page.storage.copy(settings = page.storage.settings.copy(offline = true))))) {
                assertNull(invalid.collectionSongSession(request))
            }
        }
    }
}
