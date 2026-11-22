package com.everyonesoftware.audio.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import com.everyonesoftware.audio.data.Connection
import com.everyonesoftware.audio.data.ListeningSession
import com.everyonesoftware.audio.data.Song
import com.everyonesoftware.audio.updates.UpdateRelease
import com.everyonesoftware.audio.updates.UpdateStage
import com.everyonesoftware.audio.updates.UpdateUiState
import com.everyonesoftware.audio.updates.ChangelogStage
import com.everyonesoftware.audio.updates.UpdateChangelog
import com.everyonesoftware.audio.updates.UpdateChangeCommit
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UI fixtures only: no network, installer, credentials or second player. */
@RunWith(AndroidJUnit4::class)
class UpdateScreensTest {
    @get:Rule val compose = createComposeRule()
    private val actions = RecordingActions()
    private val release = UpdateRelease(42, "0.1.42", "Music-0.1.42.apk", 10_000_000L, "a".repeat(64), "https://github.com/example/music/releases/download/v42/Music.apk", "## Current downloads\n| Android | installer |\nRaw release body must never appear", sourceSha = "b".repeat(40))
    private val changelog = UpdateChangelog(
        fromSha = "a".repeat(40), toSha = "b".repeat(40),
        improvements = listOf("Faster library browsing"),
        fixes = listOf("Keep playback position", "Keep duplicate songs"),
        commits = listOf(UpdateChangeCommit("c".repeat(40), "[verified] feat(android): faster library browsing"),
            UpdateChangeCommit("d".repeat(40), "fix(android): keep playback position"),
            UpdateChangeCommit("e".repeat(40), "fix(android): keep duplicate songs"),
            UpdateChangeCommit("f".repeat(40), "docs: refresh downloads")),
    )
    private fun available() = UpdateUiState(stage = UpdateStage.AVAILABLE, release = release,
        changelogStage = ChangelogStage.AVAILABLE, changelog = changelog)
    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureUpdates") != "true") return
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png").outputStream().use {
                check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { image.recycle() }
    }
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

    @Test fun availableChangelogIsGroupedCollapsedAndNeverUsesReleaseBody() {
        render(state(updates = available()))
        compose.onNodeWithText("Update available").assertIsDisplayed()
        compose.onNodeWithText("Music 0.1.42").assertIsDisplayed()
        compose.onNodeWithText("3 changes").assertExists()
        compose.onNodeWithTag("updates-changelog").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        compose.onNodeWithText(release.notes).assertDoesNotExist()
        compose.onNodeWithText("Current downloads", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Improvements").assertDoesNotExist()
        compose.onNodeWithTag("updates-commit-details").assertDoesNotExist()
        capture("updates-collapsed")
        compose.onNodeWithTag("updates-changelog").performClick()
        compose.onNodeWithTag("updates-changelog").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
        compose.onNodeWithText("Improvements").assertExists()
        compose.onNodeWithText("Faster library browsing").assertExists()
        compose.onNodeWithText("Fixes").assertExists()
        compose.onNodeWithText("Keep playback position").assertExists()
        compose.onNodeWithText("Keep duplicate songs").assertExists()
        compose.onNodeWithText(changelog.commits.first().subject).assertDoesNotExist()
        capture("updates-expanded")
        compose.onNodeWithTag("updates-commit-details").performScrollTo().performClick()
        compose.onNodeWithText(changelog.commits.first().subject).assertExists()
        compose.onNodeWithText(changelog.commits.first().sha.take(7)).assertExists()
        compose.onNodeWithText("${changelog.fromSha.take(7)}…${changelog.toSha.take(7)}").assertExists()
        compose.onNodeWithText(changelog.commits.last().subject).assertExists()
        compose.onNodeWithText(release.notes).assertDoesNotExist()
        compose.onNodeWithTag("updates-commit-details").performScrollTo().performClick()
        compose.onNodeWithText(changelog.commits.first().subject).assertDoesNotExist()
        compose.onNodeWithTag("updates-changelog").performScrollTo().performClick()
        compose.onNodeWithText("Improvements").assertDoesNotExist()
        compose.onNodeWithTag("updates-changelog").performClick()
        compose.onNodeWithText("Faster library browsing").assertExists()
        compose.onNodeWithTag("updates-download").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, actions.downloads); assertEquals(0, actions.installs); assertEquals(0, actions.changelogRetries) }
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun commitLinksOpenFullGitHubTargetsOnlyAfterExplicitActivation() {
        val opened = mutableListOf<String>()
        compose.setContent {
            CompositionLocalProvider(androidx.compose.ui.platform.LocalUriHandler provides object : androidx.compose.ui.platform.UriHandler {
                override fun openUri(uri: String) { opened += uri }
            }) { AudioTheme { AudioApp(state(updates = available()), actions) { null } } }
        }
        compose.onNodeWithTag("updates-changelog").performClick()
        compose.onNodeWithTag("updates-commit-details").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(emptyList<String>(), opened) }
        val range = compose.onNodeWithTag("updates-source-range")
        range.assertTextEquals("aaaaaaa…bbbbbbb").assertHasClickAction()
            .performScrollTo().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            .performTouchInput { click() }
        compose.runOnIdle { assertEquals(listOf("https://github.com/everyonesoftware/music/compare/${changelog.fromSha}...${changelog.toSha}"), opened) }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
        changelog.commits.forEach { commit ->
            val link = compose.onNodeWithText(commit.sha.take(7))
            link.performScrollTo().assertHasClickAction().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
                .assertContentDescriptionContains("View commit ${commit.sha} on GitHub (opens browser)")
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
            link.assertIsFocused().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Enter) }
            compose.onNodeWithText(commit.subject).assertExists()
        }
        compose.runOnIdle {
            assertEquals(listOf("https://github.com/everyonesoftware/music/compare/${changelog.fromSha}...${changelog.toSha}") +
                changelog.commits.map { "https://github.com/everyonesoftware/music/commit/${it.sha}" }, opened)
            assertEquals(0, actions.changelogRetries)
            assertEquals(0, actions.downloads)
            assertEquals(0, actions.checks)
        }
        compose.onNodeWithTag("updates-commit-details").performScrollTo().performClick()
        compose.onNodeWithTag("updates-commit-details").performClick()
        compose.runOnIdle { assertEquals(5, opened.size); assertEquals(0, actions.changelogRetries) }
    }

    @Test fun unavailableBrowserShowsSafeAccessibleErrorAndLinksCanRetryWithoutChangingUpdates() {
        val initial = state(updates = available()).copy(playing = true)
        val current = mutableStateOf(initial)
        val attempted = mutableListOf<String>()
        var browserAvailable = false
        val error = "Could not open browser. Try again after enabling a browser."
        compose.setContent {
            CompositionLocalProvider(androidx.compose.ui.platform.LocalUriHandler provides object : androidx.compose.ui.platform.UriHandler {
                override fun openUri(uri: String) {
                    attempted += uri
                    if (!browserAvailable) throw IllegalArgumentException("No enabled handler for $uri; private dispatch details")
                }
            }) { AudioTheme { AudioApp(current.value, actions) { null } } }
        }
        compose.onNodeWithTag("updates-changelog").performClick()
        compose.onNodeWithTag("updates-commit-details").performScrollTo().performClick()
        val targets = listOf(
            "https://github.com/everyonesoftware/music/compare/${changelog.fromSha}...${changelog.toSha}",
            "https://github.com/everyonesoftware/music/commit/${changelog.commits.first().sha}",
        )
        val links = listOf(compose.onNodeWithTag("updates-source-range"),
            compose.onNodeWithText(changelog.commits.first().sha.take(7)))
        links.forEachIndexed { index, link ->
            compose.runOnIdle { browserAvailable = false }
            repeat(2) {
                link.performScrollTo().assertIsEnabled().performClick()
                compose.onNodeWithText(error).performScrollTo().assertIsDisplayed()
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, androidx.compose.ui.semantics.LiveRegionMode.Polite))
                compose.onNodeWithText("private dispatch details", substring = true).assertDoesNotExist()
                compose.onNodeWithText(targets[index], substring = true).assertDoesNotExist()
                compose.onNodeWithTag("updates-changelog").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
                compose.onNodeWithTag("updates-commit-details").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
                compose.onNodeWithText(changelog.commits.first().subject).assertExists()
                compose.onNodeWithTag("updates-download").performScrollTo().assertIsEnabled()
                compose.onNodeWithTag("mini-player").assertIsDisplayed()
                compose.runOnIdle {
                    assertEquals(initial, current.value)
                    assertEquals(0, actions.downloads)
                    assertEquals(0, actions.cancels)
                    assertEquals(0, actions.checks)
                    assertEquals(0, actions.changelogRetries)
                    assertEquals(0, actions.pauses)
                    assertEquals(0, actions.installs)
                    assertEquals(emptyList<Screen>(), actions.screens)
                }
            }
            compose.runOnIdle { browserAvailable = true }
            link.performScrollTo().performClick()
            compose.onNodeWithText(error).assertDoesNotExist()
        }
        compose.runOnIdle { assertEquals(targets.flatMap { listOf(it, it, it) }, attempted) }
        compose.onNodeWithTag("updates-download").performScrollTo().performClick()
        val downloading = initial.copy(updates = initial.updates.copy(stage = UpdateStage.DOWNLOADING, downloadedBytes = 5_000_000L))
        compose.runOnIdle { assertEquals(1, actions.downloads); current.value = downloading; browserAvailable = false }
        links.first().performScrollTo().performClick()
        compose.onNodeWithText(error).assertExists()
        compose.onNodeWithText("5.0 MB of 10.0 MB · 50%").assertExists()
        compose.onNodeWithTag("updates-cancel").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(downloading, current.value)
            assertEquals(1, actions.downloads)
            assertEquals(1, actions.cancels)
            assertEquals(0, actions.pauses)
            assertEquals(0, actions.installs)
        }
    }

    @Test fun commitBoxesUseApprovedThemeAndWrapOriginalSubjectsAtLargeFonts() {
        val width = mutableStateOf(320.dp)
        val scale = mutableStateOf(1f)
        val light = mutableStateOf(false)
        val original = "[verified] fix(android): preserve the original subject — punctuation, spacing  and a long ending"
        val notes = changelog.copy(improvements = emptyList(), fixes = emptyList(),
            commits = listOf(changelog.commits.first().copy(subject = original)))
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, scale.value)) {
                AudioTheme {
                    val colors = if (light.value) androidx.compose.material3.lightColorScheme(
                        primary = androidx.compose.ui.graphics.Color(0xFF315B3B)) else androidx.compose.material3.MaterialTheme.colorScheme
                    androidx.compose.material3.MaterialTheme(colorScheme = colors) {
                        Box(Modifier.width(width.value)) { AudioApp(state(updates = available().copy(changelog = notes)), actions) { null } }
                    }
                }
            }
        }
        compose.onNodeWithTag("updates-changelog").performClick()
        compose.onNodeWithTag("updates-commit-details").performScrollTo().performClick()
        for (isLight in listOf(false, true)) for (phoneWidth in listOf(320.dp, 412.dp)) for (fontScale in listOf(1f, 2f)) {
            compose.runOnIdle { width.value = phoneWidth; scale.value = fontScale; light.value = isLight }
            val code = compose.onNodeWithText("ccccccc", useUnmergedTree = true)
            code.performScrollTo().assertIsDisplayed()
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            code.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(androidx.compose.ui.text.font.FontFamily.Monospace, layouts.single().layoutInput.style.fontFamily)
            assertTextFits(layouts.single())
            if (!isLight) {
                assertEquals(androidx.compose.ui.graphics.Color(0xFFBBD8C1), layouts.single().layoutInput.style.color)
                val pixels = compose.onNodeWithText("ccccccc").captureToImage().toPixelMap()
                val colors = (0 until pixels.height).flatMap { y -> (0 until pixels.width).map { x -> pixels[x, y].toArgb() } }
                org.junit.Assert.assertTrue("Dark-filled code box", colors.count { it == 0xFF2A312C.toInt() } > 20)
                org.junit.Assert.assertTrue("Subtle code border", colors.count { it == 0xFF545E56.toInt() } > 10)
            } else assertEquals(androidx.compose.ui.graphics.Color(0xFF315B3B), layouts.single().layoutInput.style.color)
            val link = compose.onNodeWithText("ccccccc").assertHeightIsAtLeast(48.dp).fetchSemanticsNode()
            val subject = compose.onNodeWithText(original).fetchSemanticsNode()
            val offer = compose.onNodeWithTag("updates-offer").fetchSemanticsNode()
            org.junit.Assert.assertTrue(subject.positionInRoot.x >= offer.positionInRoot.x)
            org.junit.Assert.assertTrue(subject.positionInRoot.x + subject.size.width <= offer.positionInRoot.x + offer.size.width)
            if (fontScale == 1f) org.junit.Assert.assertTrue("ID precedes subject on normal row", link.positionInRoot.x + link.size.width <= subject.positionInRoot.x)
            else org.junit.Assert.assertTrue("Subject wraps below code at large fonts", link.positionInRoot.y + link.size.height <= subject.positionInRoot.y)
            compose.onNodeWithText(original).performScrollTo().assertIsDisplayed()
            val subjectLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithText(original).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(subjectLayouts) }
            assertTextFits(subjectLayouts.single())
            compose.onNodeWithTag("updates-source-range").performScrollTo().assertIsDisplayed()
            val rangeLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithText("aaaaaaa…bbbbbbb", useUnmergedTree = true)
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(rangeLayouts) }
            assertTextFits(rangeLayouts.single())
            capture("commit-links-${if (isLight) "light" else "dark"}-${phoneWidth.value.toInt()}-${fontScale.toInt()}")
        }
    }

    private fun assertTextFits(layout: androidx.compose.ui.text.TextLayoutResult) {
        // Tight-width Text can retain a wider paragraph constraint; inspect actual glyph lines.
        org.junit.Assert.assertFalse(layout.multiParagraph.didExceedMaxLines)
        for (line in 0 until layout.lineCount) {
            org.junit.Assert.assertTrue("Text line clips left", layout.getLineLeft(line) >= 0f)
            org.junit.Assert.assertTrue("Text line clips right", layout.getLineRight(line) <= layout.size.width + 0.5f)
            org.junit.Assert.assertTrue("Text line clips bottom", layout.getLineBottom(line) <= layout.size.height + 0.5f)
        }
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

    @Test fun unavailableHistoryHasUnknownCountAndDisclosureNeverStartsWork() {
        val current = mutableStateOf(state(updates = available().copy(changelogStage = ChangelogStage.UNAVAILABLE, changelog = null)))
        compose.setContent { AudioTheme { AudioApp(current.value, actions) { null } } }
        compose.onNodeWithText("0 changes").assertDoesNotExist()
        compose.onNodeWithText("Unavailable").assertExists()
        compose.onNodeWithTag("updates-changelog").performClick()
        compose.onNodeWithText("Changelog unavailable. You can still download this update.").assertExists()
        compose.onNodeWithTag("updates-changelog-loading").assertDoesNotExist()
        compose.onNodeWithTag("updates-changelog-retry").assertDoesNotExist()
        compose.onNodeWithTag("updates-changelog-load").assertDoesNotExist()
        compose.onNodeWithTag("updates-changelog").performClick()
        compose.onNodeWithTag("updates-changelog").performClick()
        compose.runOnIdle { assertEquals(0, actions.changelogRetries) }
        compose.onNodeWithTag("updates-download").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, actions.downloads); current.value = current.value.copy(updates = available()) }
        compose.onNodeWithText("Faster library browsing").assertExists()
        compose.onNodeWithText("3 changes").assertExists()
    }

    @Test fun emptyGroupsAreOmittedAndEmptyAvailableHistoryIsAnHonestZero() {
        val current = mutableStateOf(state(updates = available().copy(changelog = changelog.copy(improvements = emptyList(), fixes = listOf("One fix")))))
        compose.setContent { AudioTheme { AudioApp(current.value, actions) { null } } }
        compose.onNodeWithText("1 change").assertExists()
        compose.onNodeWithTag("updates-changelog").performClick()
        compose.onNodeWithText("Improvements").assertDoesNotExist()
        compose.onNodeWithText("Fixes").assertExists()
        compose.onNodeWithText("One fix").assertExists()
        compose.runOnIdle { current.value = current.value.copy(updates = available().copy(changelog = changelog.copy(improvements = listOf("One improvement"), fixes = emptyList()))) }
        compose.onNodeWithText("Improvements").assertExists()
        compose.onNodeWithText("Fixes").assertDoesNotExist()
        compose.runOnIdle { current.value = current.value.copy(updates = available().copy(changelog = changelog.copy(improvements = emptyList(), fixes = emptyList(), commits = emptyList()))) }
        compose.onNodeWithText("0 changes").assertExists()
        compose.onNodeWithText("Improvements").assertDoesNotExist()
        compose.onNodeWithText("Fixes").assertDoesNotExist()
    }

    @Test fun restoredExpandedDisclosureWithMissingHistoryNeverShowsLoadingOrStartsWork() {
        val current = mutableStateOf(state(updates = available()))
        val restoration = StateRestorationTester(compose)
        restoration.setContent { AudioTheme { AudioApp(current.value, actions) { null } } }
        compose.onNodeWithTag("updates-changelog").performClick()
        compose.onNodeWithText("Faster library browsing").assertExists()
        compose.runOnIdle { current.value = current.value.copy(updates = available().copy(changelogStage = ChangelogStage.IDLE, changelog = null)) }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("updates-changelog").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
        compose.onNodeWithTag("updates-changelog-loading").assertDoesNotExist()
        compose.onNodeWithTag("updates-changelog-load").assertDoesNotExist()
        compose.onNodeWithText("Loading changes…").assertDoesNotExist()
        compose.onNodeWithText("Faster library browsing").assertDoesNotExist()
        compose.onNodeWithText("Changelog unavailable. You can still download this update.").assertExists()
        compose.runOnIdle { assertEquals(0, actions.changelogRetries) }
        compose.runOnIdle { current.value = current.value.copy(updates = available()) }
        compose.onNodeWithText("Faster library browsing").assertExists()
        compose.onNodeWithText("3 changes").assertExists()
    }

    @Test fun disclosuresSurviveRestorationAndDownloadStagesButResetForEachReleaseIdentityPart() {
        val current = mutableStateOf(state(updates = available()))
        val restoration = StateRestorationTester(compose)
        restoration.setContent { AudioTheme { AudioApp(current.value, actions) { null } } }
        compose.onNodeWithTag("updates-changelog").performClick()
        compose.onNodeWithTag("updates-commit-details").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(changelog.commits.first().subject).assertExists()
        for (stage in listOf(UpdateStage.CHECKING, UpdateStage.DOWNLOADING, UpdateStage.VERIFYING, UpdateStage.READY, UpdateStage.ERROR)) {
            compose.runOnIdle { current.value = current.value.copy(updates = current.value.updates.copy(stage = stage)) }
            compose.onNodeWithText(changelog.commits.first().subject).assertExists()
            compose.onNodeWithText("Version 0.1.42 · 10.0 MB").assertDoesNotExist()
            compose.onNodeWithText("Downloading does not interrupt playback.", substring = true).assertDoesNotExist()
        }
        val replacements = listOf(release.copy(sha256 = "f".repeat(64)),
            release.copy(sha256 = "f".repeat(64), sourceSha = "c".repeat(40)),
            release.copy(count = 43, sha256 = "f".repeat(64), sourceSha = "c".repeat(40)))
        for (replacement in replacements) {
            compose.runOnIdle { current.value = current.value.copy(updates = available().copy(release = replacement)) }
            compose.onNodeWithText("Improvements").assertDoesNotExist()
            compose.onNodeWithTag("updates-changelog").performScrollTo().performClick()
            compose.onNodeWithText("Improvements").assertExists()
            compose.onNodeWithText(changelog.commits.first().subject).assertDoesNotExist()
            compose.onNodeWithTag("updates-commit-details").performScrollTo().performClick()
            compose.onNodeWithText(changelog.commits.first().subject).assertExists()
        }
    }

    @Test fun compactOfferWrapsWithoutOverlapAtPhoneWidthsAndLargeFonts() {
        val width = mutableStateOf(320.dp)
        val fontScale = mutableStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale.value)) {
                AudioTheme { Box(Modifier.width(width.value)) { AudioApp(state(updates = available()), actions) { null } } }
            }
        }
        for (phoneWidth in listOf(320.dp, 412.dp)) for (scale in listOf(1f, 2f)) {
            compose.runOnIdle { width.value = phoneWidth; fontScale.value = scale }
            compose.onNodeWithTag("updates-changelog").performScrollTo().assertIsDisplayed()
            val offer = compose.onNodeWithTag("updates-offer").fetchSemanticsNode()
            val label = compose.onNodeWithTag("updates-available-label").fetchSemanticsNode()
            val version = compose.onNodeWithTag("updates-offered-version").fetchSemanticsNode()
            // Layout coordinates stay meaningful when scrolling clips a heading.
            org.junit.Assert.assertTrue(label.positionInRoot.x + label.size.width <= version.positionInRoot.x ||
                label.positionInRoot.y + label.size.height <= version.positionInRoot.y)
            org.junit.Assert.assertTrue(label.positionInRoot.x >= offer.positionInRoot.x &&
                label.positionInRoot.x + label.size.width <= offer.positionInRoot.x + offer.size.width)
            org.junit.Assert.assertTrue(version.positionInRoot.x >= offer.positionInRoot.x &&
                version.positionInRoot.x + version.size.width <= offer.positionInRoot.x + offer.size.width)
            val disclosure = compose.onNodeWithTag("updates-changelog").fetchSemanticsNode()
            val download = compose.onNodeWithTag("updates-download").fetchSemanticsNode()
            org.junit.Assert.assertTrue(disclosure.positionInRoot.y + disclosure.size.height <= download.positionInRoot.y)
            compose.onNodeWithTag("updates-changelog").performClick()
            compose.onNodeWithText("Keep duplicate songs").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("updates-commit-details").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("updates-download").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("mini-player").assertIsDisplayed()
            compose.onNodeWithTag("updates-changelog").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Back").assertIsDisplayed()
            compose.onNodeWithTag("settings-gear").assertIsDisplayed()
        }
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
        var checks = 0
        var changelogRetries = 0
        var downloads = 0
        var cancels = 0
        var installs = 0
        var pauses = 0
        override fun checkForUpdates() { checks++ }
        override fun retryUpdateChangelog() { changelogRetries++ }
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
        override fun shuffleUpNext() = Unit
        override fun toggleRepeat() = Unit
        override fun rateSong(rating: Int) = Unit
        override fun clearError() = Unit
        override fun switchConnection(id: String) = Unit
    }
}
