package com.everyonesoftware.audio.ui

import com.everyonesoftware.audio.data.Album
import com.everyonesoftware.audio.data.Playlist
import com.everyonesoftware.audio.data.CollectionKind
import com.everyonesoftware.audio.data.CollectionLayout
import org.junit.Assert.*
import org.junit.Test

class CollectionBrowsingTest {
    @Test fun systemBackRoutesPreservePlayerQueueUpdatesAndDisconnectedHome() {
        assertEquals(Screen.PLAYLISTS, AudioUiState(screen = Screen.PLAYER).backDestination)
        assertEquals(Screen.PLAYER, AudioUiState(screen = Screen.QUEUE).backDestination)
        assertEquals(Screen.ALL_ALBUMS, AudioUiState(screen = Screen.UPDATES, updatesReturnScreen = Screen.ALL_ALBUMS).backDestination)
        assertEquals(Screen.WELCOME, AudioUiState(screen = Screen.SETTINGS).backDestination)
        assertEquals(Screen.WELCOME, AudioUiState(screen = Screen.SEARCH).backDestination)
    }

    @Test fun returningFromUpdatesDoesNotReplaceTheSettingsOriginWithUpdates() {
        val settings = AudioUiState(screen = Screen.ALL_ALBUMS).navigateTo(Screen.SETTINGS)
        val updates = settings.navigateTo(Screen.UPDATES)
        val returned = updates.navigateTo(updates.backDestination)
        assertEquals(Screen.SETTINGS, returned.screen)
        assertEquals(Screen.ALL_ALBUMS, returned.backDestination)
    }

    @Test fun layoutDefaultsToGridAndEachCollectionChangesIndependently() {
        val state = AudioUiState()
        assertEquals(CollectionLayout.GRID, state.playlistCollectionLayout)
        assertEquals(CollectionLayout.GRID, state.albumCollectionLayout)
        val changed = state.withCollectionLayout(CollectionKind.PLAYLISTS, CollectionLayout.LIST)
        assertEquals(CollectionLayout.LIST, changed.playlistCollectionLayout)
        assertEquals(CollectionLayout.GRID, changed.albumCollectionLayout)
        assertEquals(CollectionLayout.LIST, changed.navigateTo(Screen.SETTINGS).playlistCollectionLayout)
        assertEquals(CollectionLayout.LIST, changed.withCollectionLayout(CollectionKind.ALBUMS, CollectionLayout.LIST).albumCollectionLayout)
    }

    @Test fun detailsAndSettingsReturnToTheirOriginWithoutClearingQueriesOrPlayback() {
        for ((collection, detail) in listOf(Screen.ALL_PLAYLISTS to Screen.PLAYLIST, Screen.ALL_ALBUMS to Screen.ALBUM, Screen.SEARCH to Screen.ALBUM)) {
            val browsing = AudioUiState(screen = collection, playlistCollectionQuery = "night", albumCollectionQuery = "lena")
            val opened = browsing.navigateTo(detail)
            assertEquals(collection, opened.backDestination)
            val settings = opened.navigateTo(Screen.SETTINGS)
            assertEquals(detail, settings.backDestination)
            val restored = settings.navigateTo(settings.backDestination)
            assertEquals(collection, restored.backDestination)
            assertEquals("night", restored.playlistCollectionQuery)
            assertEquals("lena", restored.albumCollectionQuery)
            assertEquals(browsing.session, restored.session)
        }
        assertEquals(Screen.PLAYLISTS, AudioUiState(screen = Screen.ALL_ALBUMS).backDestination)
        assertEquals(Screen.PLAYER, AudioUiState(screen = Screen.QUEUE).backDestination)
        assertEquals(Screen.PLAYLISTS, AudioUiState(screen = Screen.PLAYER).backDestination)
    }

    @Test fun collectionSearchIsScopedAndIncludesTheEntireCatalog() {
        val state = AudioUiState(
            playlists = (1..150).map { Playlist("p$it", "Playlist $it") },
            albums = listOf(Album("a", "Journey", "Austin Wintory"), Album("b", "Celeste", "Lena Raine")),
        )
        assertEquals(150, state.visiblePlaylists.size)
        val filtered = state.copy(playlistCollectionQuery = "  PLAYLIST 150 ", albumCollectionQuery = "wintory")
        assertEquals(listOf("p150"), filtered.visiblePlaylists.map { it.id })
        assertEquals(listOf("a"), filtered.visibleAlbums.map { it.id })
        assertTrue(state.copy(playlistCollectionQuery = "Wintory").visiblePlaylists.isEmpty())
        assertEquals(listOf("b"), state.copy(albumCollectionQuery = "CELESTE").visibleAlbums.map { it.id })
        assertEquals("", filtered.searchQuery)
    }
}
