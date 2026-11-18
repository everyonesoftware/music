package com.everyonesoftware.audio.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import com.everyonesoftware.audio.data.Connection
import com.everyonesoftware.audio.data.ListeningSession
import com.everyonesoftware.audio.data.Song
import com.everyonesoftware.audio.updates.UpdateRelease
import com.everyonesoftware.audio.updates.UpdateStage
import com.everyonesoftware.audio.updates.UpdateUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UI fixtures only: no network, installer, credentials or second player. */
@RunWith(AndroidJUnit4::class)
class UpdateScreensTest {
    @get:Rule val compose = createComposeRule()
    private val actions = RecordingActions()
    private val release = UpdateRelease(42, "0.1.42", "Music-0.1.42.apk", 10_000_000L, "a".repeat(64), "https://github.com/example/music/releases/download/v42/Music.apk", "Playback fixes and improvements.")
    private val session = ListeningSession("test-server", "test-playlist", "Test playlist", listOf(Song("test-song", "Test song", "Test artist")))
    private fun state(screen: Screen = Screen.UPDATES, updates: UpdateUiState = UpdateUiState()) = AudioUiState(
        booting = false, screen = screen,
        connection = Connection("test-server", "Test server", "https://example.invalid", "test-user"),
        session = session, updates = updates,
    )
    private fun render(state: AudioUiState) {
        compose.setContent { AudioTheme { AudioApp(state, actions) { null } } }
    }

    @Test fun libraryNotificationRoutesToSettingsWithoutReplacingLibraryOrMiniPlayer() {
        render(state(Screen.PLAYLISTS, UpdateUiState(stage = UpdateStage.AVAILABLE, release = release)))
        compose.onNodeWithText("Playlists").assertIsDisplayed()
        compose.onNodeWithText("Albums").assertIsDisplayed()
        compose.onNodeWithTag("mini-player").assertIsDisplayed()
        compose.onNodeWithTag("settings-update-dot", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Settings, update available").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(Screen.SETTINGS), actions.screens); assertEquals(0, actions.pauses) }
    }

    @Test fun playerNotificationPreservesQueueAndPlaylistContext() {
        render(state(Screen.PLAYER, UpdateUiState(stage = UpdateStage.AVAILABLE, release = release)).copy(playing = true))
        compose.onNodeWithText("Test playlist").assertIsDisplayed()
        compose.onNodeWithTag("player-queue").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Settings, update available").assertIsDisplayed().performClick()
        compose.onNodeWithTag("player-toggle").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(Screen.QUEUE, Screen.SETTINGS), actions.screens); assertEquals(0, actions.pauses) }
    }

    @Test fun noNotificationWhenThereIsNoRelease() {
        val current = mutableStateOf(state(Screen.PLAYLISTS))
        compose.setContent { AudioTheme { AudioApp(current.value, actions) { null } } }
        compose.onNodeWithTag("settings-update-dot", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { current.value = current.value.copy(screen = Screen.PLAYER) }
        compose.onNodeWithTag("settings-update-dot", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun savedBrowserUsesActualPlayedHistoryAndAnOfflineOnlyFilter() {
        val played = Song("played", "Actually played", "Artist")
        val prefetched = Song("prefetched", "Only prefetched", "Artist")
        val evicted = Song("evicted", "Played but evicted", "Artist")
        render(state(Screen.OFFLINE).copy(storage = com.everyonesoftware.audio.data.SongCacheState(
            songs = listOf(played, prefetched), cachedSongs = setOf(played.id), downloadedSongs = setOf(prefetched.id),
            recentSongs = listOf(played, evicted), explicitDownloads = setOf(prefetched.id))))
        compose.onNodeWithText("Only prefetched").assertExists()
        compose.onNodeWithText("Remove download").assertExists()
        compose.onNodeWithText("Recently played").performClick()
        compose.onNodeWithText("Only prefetched").assertDoesNotExist()
        compose.onNodeWithText("Actually played").assertExists()
        compose.onNodeWithText("Played but evicted").assertDoesNotExist()
        compose.onNodeWithTag("recent-offline-filter").performClick()
        compose.onNodeWithText("Played but evicted").assertExists()
    }

    @Test fun settingsShowsStorageAndOfflineControls() {
        render(state(Screen.SETTINGS))
        compose.onNodeWithText("Storage & offline").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("storage-auto-save").performScrollTo().assertIsOn()
        compose.onNodeWithTag("storage-mobile").performScrollTo().assertIsOff()
        compose.onNodeWithTag("storage-offline").performScrollTo().assertIsOff()
        compose.onNodeWithTag("storage-clear").performScrollTo().assertIsEnabled()
    }

    @Test fun settingsEntryPreservesConnectionAndLibraryControls() {
        render(state(Screen.SETTINGS))
        compose.onNodeWithText("Add another server").assertExists()
        compose.onNodeWithText("Choose libraries").assertExists()
        compose.onNodeWithTag("settings-updates").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(Screen.UPDATES), actions.screens) }
    }

    @Test fun confirmedCurrentShowsCompactCardAndVersionBesideManualCheck() {
        render(state(updates = UpdateUiState(installedVersion = "0.1.41", lastCheckedAt = 1L)))
        compose.onNodeWithText("Current Version: 0.1.41").assertIsDisplayed()
        compose.onNodeWithText("Up to date").assertIsDisplayed()
        compose.onNodeWithText("No newer Android version is available.").assertIsDisplayed()
        val version = compose.onNodeWithText("Current Version: 0.1.41").fetchSemanticsNode().boundsInRoot
        val check = compose.onNodeWithTag("updates-check").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(version.right <= check.left)
        org.junit.Assert.assertTrue(version.center.y in check.top..check.bottom)
        compose.onNodeWithTag("updates-download").assertDoesNotExist()
        compose.onNodeWithTag("updates-install").assertDoesNotExist()
        compose.onNodeWithText("What’s new").assertDoesNotExist()
        compose.onNodeWithText("Installed version 0.1.41").assertDoesNotExist()
        compose.onNodeWithTag("updates-automatic").assertDoesNotExist()
        compose.onNodeWithTag("updates-source").assertDoesNotExist()
        compose.onNodeWithText("Checks contact GitHub", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        compose.onNodeWithTag("settings-gear").assertIsDisplayed()
        compose.onNodeWithTag("mini-player").assertIsDisplayed()
        if (InstrumentationRegistry.getArguments().getString("captureUpdates") == "true") {
            compose.waitForIdle()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            java.io.File(context.getExternalFilesDir(null), "updates-current.png").outputStream().use {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            image.recycle()
        }
        compose.onNodeWithTag("updates-check").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, actions.checks)
            assertEquals(emptyList<Boolean>(), actions.automatic)
            assertEquals(0, actions.pauses)
            assertEquals(0, actions.downloads)
            assertEquals(0, actions.installs)
        }
    }

    @Test fun checkingIsDisabledAndDoesNotPausePlayback() {
        render(state(updates = UpdateUiState(stage = UpdateStage.CHECKING)).copy(playing = true))
        compose.onNodeWithTag("updates-check").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Checking…").assertExists()
        compose.onNodeWithTag("mini-player-toggle").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, actions.pauses); assertEquals(0, actions.installs) }
    }

    @Test fun availableShowsVersionSizeNotesAndExplicitDownload() {
        render(state(updates = UpdateUiState(stage = UpdateStage.AVAILABLE, release = release)))
        compose.onNodeWithText("Version 0.1.42 · 10.0 MB").assertExists()
        compose.onNodeWithText(release.notes).assertExists()
        compose.onNodeWithTag("updates-download").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, actions.downloads); assertEquals(0, actions.installs) }
    }

    @Test fun downloadShowsProgressAndCancelThenVerificationDoesNotOfferInstall() {
        val current = mutableStateOf(state(updates = UpdateUiState(stage = UpdateStage.DOWNLOADING, release = release, downloadedBytes = 5_000_000L)))
        compose.setContent { AudioTheme { AudioApp(current.value, actions) { null } } }
        compose.onNodeWithText("5.0 MB of 10.0 MB · 50%").assertExists()
        compose.onNodeWithTag("updates-cancel").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, actions.cancels); current.value = current.value.copy(updates = current.value.updates.copy(stage = UpdateStage.VERIFYING)) }
        compose.onNodeWithText("Verifying download…").assertExists()
        compose.onNodeWithTag("updates-install").assertDoesNotExist()
    }

    @Test fun downloadErrorOffersRetryWhileCheckFailureOffersCheckAgain() {
        val current = mutableStateOf(state(updates = UpdateUiState(stage = UpdateStage.ERROR, release = release, error = "Download interrupted. Please retry.")))
        compose.setContent { AudioTheme { AudioApp(current.value, actions) { null } } }
        compose.onNodeWithText("Download interrupted. Please retry.").assertExists()
        compose.onNodeWithTag("updates-download").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, actions.downloads); current.value = current.value.copy(updates = UpdateUiState(stage = UpdateStage.ERROR, error = "Could not check for updates.")) }
        compose.onNodeWithTag("updates-download").assertDoesNotExist()
        compose.onNodeWithTag("updates-check").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, actions.checks) }
    }

    @Test fun readyRequiresExplicitPauseThenConfirmationBeforeInstall() {
        val current = mutableStateOf(state(updates = UpdateUiState(stage = UpdateStage.READY, release = release)).copy(playing = true))
        compose.setContent { AudioTheme { AudioApp(current.value, actions) { null } } }
        compose.onNodeWithTag("updates-install").assertDoesNotExist()
        compose.onNodeWithTag("updates-pause").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, actions.pauses); assertEquals(0, actions.installs); current.value = current.value.copy(playing = false) }
        compose.onNodeWithTag("updates-install").performScrollTo().performClick()
        compose.onNodeWithText("Music will close during installation. Your queue and position are preserved, and playback stays paused. Android will ask you to confirm the update.").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, actions.installs) }
        compose.onNodeWithTag("updates-confirm-install").performClick()
        compose.runOnIdle { assertEquals(1, actions.installs) }
    }

    @Test fun installConfirmationCanBeCancelledAndCannotInstallIfPlaybackResumes() {
        val current = mutableStateOf(state(updates = UpdateUiState(stage = UpdateStage.READY, release = release)))
        compose.setContent { AudioTheme { AudioApp(current.value, actions) { null } } }
        compose.onNodeWithTag("updates-install").performScrollTo().performClick()
        compose.onNodeWithText("Not now").performClick()
        compose.runOnIdle { assertEquals(0, actions.installs) }
        compose.onNodeWithTag("updates-install").performClick()
        compose.runOnIdle { current.value = current.value.copy(playing = true) }
        compose.onNodeWithTag("updates-confirm-install").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, actions.installs) }
    }

    @Test fun permissionActionExplainsSystemSettingsAndDoesNotClaimToInstall() {
        render(state(updates = UpdateUiState(stage = UpdateStage.READY, release = release, installPermissionRequired = true)))
        compose.onNodeWithText("Allow installations").performScrollTo().performClick()
        compose.onNodeWithText("Android settings will open so you can allow Music to install updates. Return here afterward and tap Install update again. This does not install the update.").assertIsDisplayed()
        compose.onNodeWithTag("updates-confirm-install").performClick()
        compose.runOnIdle { assertEquals(1, actions.installs) }
    }

    @Test fun updatesPreservesFullWidthMiniPlayerAndReturnsToCaller() {
        render(state().copy(updatesReturnScreen = Screen.PLAYER))
        compose.onNodeWithTag("settings-gear").assertIsDisplayed()
        val mini = compose.onNodeWithTag("mini-player").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("bottom-navigation").assertDoesNotExist()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertEquals(root.width, mini.width, 1f)
        org.junit.Assert.assertTrue(mini.bottom <= root.bottom)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { assertEquals(listOf(Screen.PLAYER), actions.screens) }
    }

    @Test fun uncheckedDoesNotClaimCurrentOrExposeRemovedSections() {
        render(state())
        compose.onNodeWithText("Not checked yet").assertIsDisplayed()
        compose.onNodeWithText("Up to date").assertDoesNotExist()
        compose.onNodeWithTag("updates-current").assertDoesNotExist()
        compose.onNodeWithTag("updates-source").assertDoesNotExist()
        compose.onNodeWithTag("updates-automatic").assertDoesNotExist()
    }

    @Test fun hardwareBackReturnsToTheOriginatingScreen() {
        render(state().copy(updatesReturnScreen = Screen.PLAYLISTS))
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.runOnIdle { assertEquals(listOf(Screen.PLAYLISTS), actions.screens) }
    }

    @Test fun recheckingAndFailureNeverReuseConfirmedCurrentCard() {
        val current = mutableStateOf(state(updates = UpdateUiState(lastCheckedAt = 1L)))
        compose.setContent { AudioTheme { AudioApp(current.value, actions) { null } } }
        compose.onNodeWithText("Up to date").assertIsDisplayed()
        compose.runOnIdle { current.value = current.value.copy(updates = current.value.updates.copy(stage = UpdateStage.CHECKING)) }
        compose.onNodeWithTag("updates-current").assertDoesNotExist()
        compose.onNodeWithTag("updates-check").assertIsNotEnabled()
        compose.runOnIdle { current.value = current.value.copy(updates = current.value.updates.copy(stage = UpdateStage.ERROR, error = "Could not check for updates.")) }
        compose.onNodeWithTag("updates-current").assertDoesNotExist()
        compose.onNodeWithText("No newer Android version is available.").assertDoesNotExist()
        compose.onNodeWithText("Could not check for updates.").assertIsDisplayed()
        compose.onNodeWithTag("updates-check").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, actions.checks); assertEquals(0, actions.downloads); assertEquals(0, actions.installs) }
    }

    @Test fun updatesWithoutAQueueHasNoSecondPlayerAndStillHasSettings() {
        render(state().copy(session = null, connection = null))
        compose.onNodeWithTag("mini-player").assertDoesNotExist()
        compose.onNodeWithTag("player-toggle").assertDoesNotExist()
        compose.onNodeWithTag("settings-gear").assertIsDisplayed()
    }

    private class RecordingActions : AudioUiActions {
        val screens = mutableListOf<Screen>()
        val automatic = mutableListOf<Boolean>()
        var checks = 0
        var downloads = 0
        var cancels = 0
        var installs = 0
        var pauses = 0
        override fun checkForUpdates() { checks++ }
        override fun setAutomaticUpdates(enabled: Boolean) { automatic += enabled }
        override fun downloadUpdate() { downloads++ }
        override fun cancelUpdateDownload() { cancels++ }
        override fun installUpdate() { installs++ }
        override fun pauseForUpdate() { pauses++ }
        override fun showScreen(screen: Screen) { screens += screen }
        override fun connect(name: String, url: String, username: String, password: String) = Unit
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
