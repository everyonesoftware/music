package com.everyonesoftware.audio.ui

import org.junit.Assert.*
import org.junit.Test

class OfflineUiContractTest {
    @Test fun `saved music round trip returns Settings to its original browser`() {
        for (origin in listOf(Screen.PLAYLISTS, Screen.ALL_PLAYLISTS, Screen.ALL_ALBUMS, Screen.SEARCH)) {
            var state = AudioUiState(screen = origin).navigateTo(Screen.SETTINGS).navigateTo(Screen.OFFLINE)
            state = state.navigateTo(state.backDestination)
            assertEquals(Screen.SETTINGS, state.screen)
            state = state.navigateTo(state.backDestination)
            assertEquals(origin, state.screen)
        }
    }

    @Test fun `saved detail then Updates preserve the original Settings origin`() {
        var state = AudioUiState(screen = Screen.ALL_ALBUMS).navigateTo(Screen.SETTINGS).navigateTo(Screen.OFFLINE)
            .copy(selectedOfflineCollection = com.everyonesoftware.audio.data.OfflineCollection("album", "a", "Saved", songs = emptyList()))
        assertEquals(Screen.OFFLINE, state.backDestination)
        // showScreen(OFFLINE) clears the selected detail before applying navigateTo.
        state = state.copy(selectedOfflineCollection = null).navigateTo(state.backDestination)
        assertEquals(Screen.OFFLINE, state.screen)
        state = state.navigateTo(state.backDestination).navigateTo(Screen.UPDATES)
        assertEquals(Screen.SETTINGS, state.backDestination)
        state = state.navigateTo(state.backDestination)
        assertEquals(Screen.ALL_ALBUMS, state.backDestination)
        assertEquals(Screen.ALL_ALBUMS, state.navigateTo(state.backDestination).screen)
    }

    @Test fun `offline back navigation preserves downloaded hierarchy and settings origin`() {
        val collection = com.everyonesoftware.audio.data.OfflineCollection("playlist", "p", "Saved", songs = emptyList())
        val detail = AudioUiState(screen = Screen.OFFLINE, selectedOfflineCollection = collection)
        assertEquals(Screen.OFFLINE, detail.backDestination)
        assertEquals(Screen.SETTINGS, detail.copy(selectedOfflineCollection = null).backDestination)
        assertEquals(Screen.ALL_ALBUMS, AudioUiState(screen = Screen.SETTINGS, settingsReturnScreen = Screen.ALL_ALBUMS).backDestination)
    }

    @Test fun `offline storage defaults are safe and visible to the native UI`() {
        val storage = AudioUiState().storage
        assertTrue(storage.settings.autoSave)
        assertEquals(2_000_000_000L, storage.settings.limitBytes)
        assertEquals(3, storage.settings.prefetch)
        assertFalse(storage.settings.mobilePrefetch)
        assertFalse(storage.settings.offline)
        assertTrue(storage.cachedSongs.isEmpty())
        assertTrue(storage.downloadedSongs.isEmpty())
    }
}
