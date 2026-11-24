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
        compose.onNodeWithTag("playlist-more").assertContentDescriptionEquals("Device actions for Playlist").performClick()
        compose.runOnIdle { assertEquals(0, actions.playlists) }
        compose.onNodeWithText("Save to Device").performClick()
        compose.runOnIdle { state.value = state.value.copy(screen = Screen.ALBUM, selectedAlbum = Album("a", "Album"), albumSongs = listOf(song)) }
        compose.onNodeWithTag("album-more").assertContentDescriptionEquals("Device actions for Album").performClick()
        compose.onNodeWithText("Save to Device").performClick()
        compose.runOnIdle { assertEquals(1, actions.playlists); assertEquals(1, actions.albums) }
    }

    @Test fun cachedSongHasNoBadgeAndMenuSwitchesToRemoveWhenExplicitlySaved() {
        val state = mutableStateOf(AudioUiState(booting = false, screen = Screen.SEARCH, connection = connection,
            searchQuery = "Song", searchSongs = listOf(song), storage = SongCacheState(cachedSongs = setOf(song.id))))
        var played = 0
        val actions = object : Actions() {
            override fun playSong(song: Song) { played++ }
            override fun downloadSongs(songs: List<Song>) {
                assertEquals(listOf(song), songs)
                state.value = state.value.copy(storage = SongCacheState(downloadedSongs = setOf(song.id), explicitDownloads = setOf(song.id)))
            }
            override fun removeDownload(songId: String) {
                assertEquals(song.id, songId)
                state.value = state.value.copy(storage = SongCacheState())
            }
        }
        compose.setContent { AudioTheme { AudioApp(state.value, actions) { null } } }
        compose.onNodeWithContentDescription("Saved to Device", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Device actions for Song").performClick()
        compose.onNodeWithText("Remove from Device").assertDoesNotExist()
        compose.onNodeWithText("Save to Device").performClick()
        compose.onNodeWithContentDescription("Saved to Device", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Device actions for Song").performClick()
        compose.onNodeWithText("Save to Device").assertDoesNotExist()
        compose.onNodeWithText("Remove from Device").performClick()
        compose.onNodeWithContentDescription("Saved to Device", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, played) }
    }

    @Test fun retainedCollectionCanBeRemovedOfflineEvenBeforeItIsComplete() {
        val state = mutableStateOf(AudioUiState(booting = false, screen = Screen.PLAYLIST, connection = connection,
            selectedPlaylist = Playlist(collection.id, collection.name), playlistSongs = collection.songs,
            storage = SongCacheState(settings = OfflineSettings(offline = true), collections = listOf(collection),
                pendingDownloads = listOf(song))))
        var removed = 0
        val actions = object : Actions() {
            override fun removeOfflineCollection(kind: String, id: String) {
                assertEquals(collection.kind, kind)
                assertEquals(collection.id, id)
                removed++
                state.value = state.value.copy(storage = state.value.storage.copy(collections = emptyList()))
            }
            override fun downloadPlaylist() { fail("Removal invoked Save") }
        }
        compose.setContent { AudioTheme { AudioApp(state.value, actions) { null } } }
        compose.onNodeWithTag("playlist-play").assertIsNotEnabled()
        compose.onAllNodesWithContentDescription("Saved to Device", useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag("playlist-more").assertIsEnabled().performClick()
        compose.onNodeWithText("Save to Device").assertDoesNotExist()
        compose.onNodeWithText("Remove from Device").performClick()
        compose.onNodeWithTag("playlist-more").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, removed) }
    }

    @Test fun changedCollectionOccurrencesCanBeSavedWithoutRemovingPreviousRevision() {
        val b = song.copy(id = "b", title = "B")
        val c = song.copy(id = "c", title = "C")
        val saved = collection.copy(songs = listOf(song, b))
        val state = mutableStateOf(AudioUiState(booting = false, screen = Screen.PLAYLIST, connection = connection,
            selectedPlaylist = Playlist(saved.id, saved.name), playlistSongs = saved.songs,
            storage = SongCacheState(collections = listOf(saved), downloadedSongs = setOf(song.id, b.id, c.id))))
        var saves = 0
        val actions = object : Actions() {
            override fun downloadPlaylist() {
                saves++
                state.value = state.value.copy(storage = state.value.storage.copy(
                    collections = listOf(saved.copy(songs = state.value.playlistSongs))))
            }
            override fun removeOfflineCollection(kind: String, id: String) { fail("Saving a revision must not remove the old claim first") }
        }
        compose.setContent { AudioTheme { AudioApp(state.value, actions) { null } } }
        val badge = hasContentDescription("Saved to Device") and hasAnySibling(hasText(saved.name))
        compose.onNode(badge, useUnmergedTree = true).assertExists()
        compose.runOnIdle { state.value = state.value.copy(playlistSongs = listOf(song, c)) }
        compose.onNode(badge, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("playlist-more").performClick()
        compose.onNodeWithText("Remove from Device").assertExists()
        compose.onNodeWithText("Save to Device").performClick()
        compose.onNode(badge, useUnmergedTree = true).assertExists()
        // A repeat and an order change matter even when all distinct IDs are saved.
        compose.runOnIdle { state.value = state.value.copy(playlistSongs = listOf(c, song, song)) }
        compose.onNode(badge, useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, saves) }
    }

    @Test fun deviceActionFailureIsVisibleOnSearchAndCollectionDetails() {
        val message = "Cannot save this music to the device. Open Device actions and try again."
        val state = mutableStateOf(AudioUiState(booting = false, screen = Screen.SEARCH, connection = connection,
            searchQuery = "Song", searchSongs = listOf(song), selectedPlaylist = Playlist(collection.id, collection.name),
            playlistSongs = collection.songs, selectedAlbum = Album("a", "Album"), albumSongs = listOf(song)))
        fun failSave() { state.value = state.value.copy(deviceActionFailure = DeviceActionFailure(state.value.deviceActionPage, message)) }
        val actions = object : Actions() {
            override fun downloadSongs(songs: List<Song>) = failSave()
            override fun downloadPlaylist() = failSave()
            override fun downloadAlbum() = failSave()
        }
        compose.setContent { AudioTheme { AudioApp(state.value, actions) { null } } }
        for ((screen, description) in listOf(Screen.SEARCH to "Song", Screen.PLAYLIST to collection.name, Screen.ALBUM to "Album")) {
            compose.runOnIdle { state.value = state.value.copy(screen = screen) }
            compose.onNodeWithText(message).assertDoesNotExist()
            compose.onNodeWithContentDescription("Device actions for $description").performClick()
            compose.onNodeWithText("Save to Device").performClick()
            compose.onNodeWithText(message).assertIsDisplayed()
            compose.onNodeWithContentDescription("Device actions for $description").assertIsEnabled()
        }
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
        override fun startPlaylist(id: String, requireLiveContents: Boolean) = Unit
        override fun playPlaylist(id: String, shuffle: Boolean) = Unit
        override fun togglePlay() = Unit
        override fun next() = Unit
        override fun previous() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun shuffleUpNext() = Unit
        override fun toggleRepeat() = Unit
        override fun rateSong(rating: Int) = Unit
        override fun clearError() = Unit
        override fun switchConnection(id: String) = Unit
    }
}
