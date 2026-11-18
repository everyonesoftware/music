package com.everyonesoftware.audio.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.everyonesoftware.audio.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineCollectionScreensTest {
    @get:Rule val compose = createComposeRule()
    private val song = Song("s", "Song", "Artist")
    private val collection = OfflineCollection("playlist", "p", "Saved playlist", songs = listOf(song, song))
    private val connection = Connection("test", "Test", "https://music.example", "alice")

    @Test fun browseDetailPlaysInSavedOrderAndReturnsToCollectionList() {
        val state = mutableStateOf(AudioUiState(booting = false, screen = Screen.OFFLINE, connection = connection,
            storage = SongCacheState(collections = listOf(collection), downloadedSongs = setOf(song.id))))
        val actions = object : Actions() {
            var played = false
            override fun openOfflineCollection(kind: String, id: String) { state.value = state.value.copy(selectedOfflineCollection = collection) }
            override fun showScreen(screen: Screen) { state.value = state.value.copy(screen = screen, selectedOfflineCollection = null) }
            override fun playOfflineCollection(kind: String, id: String) { assertEquals("playlist", kind); assertEquals("p", id); played = true }
        }
        compose.setContent { AudioTheme { AudioApp(state.value, actions) { null } } }
        compose.onNodeWithTag("offline-playlist-p").performClick()
        compose.onNodeWithText("1. Song").assertIsDisplayed()
        compose.onNodeWithText("2. Song").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("offline-collection-play").assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(actions.played) }
        compose.onNodeWithText("Back to saved music").performClick()
        compose.onNodeWithTag("offline-playlist-p").assertIsDisplayed()
    }

    @Test fun partialCollectionShowsEveryTrackAndDisablesFullPlayback() {
        val partial = collection.copy(songs = listOf(song, song.copy(id = "missing", title = "Missing"), song))
        val state = AudioUiState(booting = false, screen = Screen.OFFLINE, connection = connection,
            selectedOfflineCollection = partial, storage = SongCacheState(downloadedSongs = setOf(song.id)))
        compose.setContent { AudioTheme { AudioApp(state, Actions()) { null } } }
        compose.onNodeWithText("Partial · 2/3 songs offline").assertIsDisplayed()
        compose.onNodeWithTag("offline-collection-play").assertIsNotEnabled()
        compose.onNodeWithText("2. Missing").assertIsDisplayed()
        compose.onNodeWithText("3. Song").performScrollTo().assertIsDisplayed()
    }

    @Test fun nativeAlbumAndPlaylistDownloadsUseCollectionActionsNotIndividualPins() {
        val state = mutableStateOf(AudioUiState(booting = false, screen = Screen.PLAYLIST, connection = connection,
            selectedPlaylist = Playlist("p", "Playlist"), playlistSongs = listOf(song)))
        val actions = object : Actions() {
            var albums = 0
            var playlists = 0
            override fun downloadAlbum() { albums++ }
            override fun downloadPlaylist() { playlists++ }
            override fun downloadSongs(songs: List<Song>) { fail("Collection download used individual-song pins") }
        }
        compose.setContent { AudioTheme { AudioApp(state.value, actions) { null } } }
        compose.onNodeWithTag("playlist-download").assertContentDescriptionEquals("Download playlist").performClick()
        compose.runOnIdle { state.value = state.value.copy(screen = Screen.ALBUM, selectedAlbum = Album("a", "Album"), albumSongs = listOf(song)) }
        compose.onNodeWithTag("album-download").assertContentDescriptionEquals("Download album").performClick()
        compose.runOnIdle { assertEquals(1, actions.playlists); assertEquals(1, actions.albums) }
    }

    private open class Actions : AudioUiActions {
        override fun connect(name: String, url: String, username: String, password: String) = Unit
        override fun showScreen(screen: Screen) = Unit
        override fun setLibrary(id: String, selected: Boolean) = Unit
        override fun saveLibraries() = Unit
        override fun loadPlaylists() = Unit
        override fun loadAlbums() = Unit
        override fun openAlbum(id: String) = Unit
        override fun playAlbum(id: String, shuffle: Boolean) = Unit
        override fun startPlaylist(id: String) = Unit
        override fun playPlaylist(id: String, shuffle: Boolean) = Unit
        override fun togglePlay() = Unit
        override fun next() = Unit
        override fun previous() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun toggleShuffle() = Unit
        override fun toggleRepeat() = Unit
        override fun rateSong(rating: Int) = Unit
        override fun clearError() = Unit
        override fun switchConnection(id: String) = Unit
    }
}
