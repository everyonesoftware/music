package com.everyonesoftware.audio.ui

import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.everyonesoftware.audio.data.Connection
import com.everyonesoftware.audio.data.CollectionKind
import com.everyonesoftware.audio.data.CollectionLayout
import com.everyonesoftware.audio.data.Album
import com.everyonesoftware.audio.data.ListeningSession
import com.everyonesoftware.audio.data.MusicLibrary
import com.everyonesoftware.audio.data.Playlist
import com.everyonesoftware.audio.data.Song
import com.everyonesoftware.audio.playback.output.MediaOutputState
import com.everyonesoftware.audio.playback.output.OutputDevice
import com.everyonesoftware.audio.playback.output.OutputDeviceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real native UI; all account/media fixtures and action spies live only in androidTest. */
@RunWith(AndroidJUnit4::class)
class AudioScreensTest {
    @Test fun pullToRefreshWorksOnBrowsePagesIncludingEmptyCollectionsButNotDownloads() {
        val state = mutableStateOf(playerState().copy(screen = Screen.PLAYLISTS,
            selectedPlaylist = Playlist("p", "Playlist"), playlistSongs = listOf(song),
            selectedAlbum = Album("a", "Album", "Artist"), albumSongs = listOf(song)))
        compose.setContent { AudioTheme { AudioApp(state.value, actions, artworkUrl = { null }) } }
        val cases = listOf(
            Screen.PLAYLISTS to "library-content",
            Screen.ALL_PLAYLISTS to "collection-empty",
            Screen.ALL_ALBUMS to "collection-empty",
            Screen.PLAYLIST to "playlist-songs",
            Screen.ALBUM to "album-songs",
            Screen.SEARCH to "search-results",
        )
        for ((index, case) in cases.withIndex()) {
            compose.runOnIdle { state.value = state.value.copy(screen = case.first) }
            compose.onNodeWithTag(case.second).performTouchInput { swipeDown() }
            compose.runOnIdle { assertEquals("Refresh ${case.first}", index + 1, actions.pageRefreshes) }
        }
        for (screen in listOf(Screen.OFFLINE, Screen.SETTINGS, Screen.PLAYER, Screen.QUEUE)) {
            compose.runOnIdle { state.value = state.value.copy(screen = screen) }
            compose.onNodeWithTag("page-refresh").assertDoesNotExist()
        }
        compose.runOnIdle {
            assertEquals(0, actions.playToggles)
            assertTrue(actions.startedPlaylists.isEmpty())
            assertTrue(actions.playedAlbums.isEmpty())
        }
    }

    @Test fun pullToRefreshWorksWithPopulatedCollectionGridsAndListsAndIsDisabledOffline() {
        val state = mutableStateOf(playerState().copy(screen = Screen.ALL_PLAYLISTS,
            playlists = listOf(Playlist("p", "Playlist")), albums = listOf(Album("a", "Album", "Artist"))))
        compose.setContent { AudioTheme { AudioApp(state.value, actions, artworkUrl = { null }) } }
        var expected = 0
        for (screen in listOf(Screen.ALL_PLAYLISTS, Screen.ALL_ALBUMS)) {
            for (layout in CollectionLayout.entries) {
                compose.runOnIdle { state.value = state.value.copy(screen = screen, playlistCollectionLayout = layout, albumCollectionLayout = layout) }
                compose.onNodeWithTag(if (layout == CollectionLayout.GRID) "collection-grid" else "collection-list")
                    .performTouchInput { swipeDown() }
                compose.runOnIdle { assertEquals(++expected, actions.pageRefreshes) }
            }
        }
        compose.runOnIdle { state.value = state.value.copy(storage = state.value.storage.copy(settings = state.value.storage.settings.copy(offline = true))) }
        compose.onNodeWithTag("page-refresh").assertDoesNotExist()
    }

    @Test fun libraryRefreshDoesNotShowHorizontalProgressBar() {
        render(playerState().copy(screen = Screen.PLAYLISTS, playlistsRefreshing = true, albumsRefreshing = true))
        compose.onNodeWithContentDescription("Refreshing library").assertDoesNotExist()
        compose.onNodeWithTag("view-all-playlists").assertIsDisplayed()
    }

    @Test fun albumCollectionSearchesArtistsAndShowsLoadingEmptyErrorAndRetry() {
        val state = mutableStateOf(playerState().copy(screen = Screen.ALL_ALBUMS,
            albums = listOf(Album("a", "Journey", "Austin Wintory"), Album("b", "Celeste", "Lena Raine")),
            albumCollectionQuery = "lena"))
        compose.setContent { AudioTheme { AudioApp(state.value, actions, artworkUrl = { null }) } }
        compose.onNodeWithText("Search albums or artists").assertIsDisplayed()
        compose.onNodeWithText("1 of 2 albums").assertIsDisplayed()
        compose.onNodeWithTag("album-card-b").assertIsDisplayed().performClick()
        compose.onNodeWithTag("album-card-a").assertDoesNotExist()
        captureOutputEvidence("collection-albums-grid", compose.onRoot())
        compose.runOnIdle {
            assertEquals(listOf("b"), actions.openedAlbums)
            state.value = state.value.copy(albumCollectionLayout = CollectionLayout.LIST)
        }
        compose.onNodeWithTag("collection-list").assertIsDisplayed()
        captureOutputEvidence("collection-albums-list", compose.onRoot())
        compose.runOnIdle { state.value = state.value.copy(albumCollectionQuery = "no-match") }
        compose.onNodeWithText("No albums match “no-match”.").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(albums = emptyList(), albumCollectionQuery = "", albumsRefreshing = true) }
        compose.onNodeWithText("Loading albums…").assertIsDisplayed()
        compose.onNodeWithText("No albums available on this server.").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(albumsRefreshing = false, albumsError = "Fixture album error") }
        compose.onNodeWithText("Fixture album error").assertIsDisplayed()
        compose.onNodeWithText("Retry albums").performClick()
        compose.runOnIdle {
            assertEquals(listOf(CollectionKind.ALBUMS), actions.collectionRefreshes)
            state.value = state.value.copy(albumsError = null)
        }
        compose.onNodeWithText("No albums available on this server.").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, actions.playToggles); assertTrue(actions.playedAlbums.isEmpty()) }
    }

    @Test fun albumDetailErrorOffersRetryWithoutStartingPlayback() {
        render(playerState().copy(screen = Screen.ALBUM, selectedAlbum = Album("a", "Album", "Artist"), albumError = "Fixture detail error"))
        compose.onNodeWithText("Retry album").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("a"), actions.openedAlbums); assertTrue(actions.playedAlbums.isEmpty()) }
    }

    @Test fun collectionAndDetailAndSettingsBackUseTheSameOriginForToolbarAndSystem() {
        val state = mutableStateOf(playerState().copy(screen = Screen.ALL_ALBUMS))
        compose.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            AudioTheme { AudioApp(state.value, actions, artworkUrl = { null }) }
        }
        for ((screen, origin) in listOf(Screen.ALL_ALBUMS to Screen.PLAYLISTS, Screen.ALL_PLAYLISTS to Screen.PLAYLISTS,
            Screen.ALBUM to Screen.ALL_ALBUMS, Screen.PLAYLIST to Screen.ALL_PLAYLISTS, Screen.SETTINGS to Screen.ALL_ALBUMS)) {
            compose.runOnIdle { state.value = state.value.copy(screen = screen, albumReturnScreen = Screen.ALL_ALBUMS,
                playlistReturnScreen = Screen.ALL_PLAYLISTS, settingsReturnScreen = Screen.ALL_ALBUMS) }
            compose.onNodeWithContentDescription("Back").performClick()
            compose.runOnIdle { backDispatcher.onBackPressed(); assertEquals(listOf(origin, origin), actions.screens.takeLast(2)) }
            compose.onNodeWithTag("mini-player").assertIsDisplayed()
            compose.onNodeWithTag("settings-gear").assertIsDisplayed()
        }
        compose.runOnIdle { assertEquals(0, actions.playToggles) }
    }

    @Test fun libraryViewAllActionsShareTheirHeadingRowsAndDoNotStartPlayback() {
        render(playerState().copy(screen = Screen.PLAYLISTS))
        compose.onNodeWithText("Refresh").assertDoesNotExist()
        for ((tag, heading, screen) in listOf(
            Triple("view-all-playlists", "Playlists", Screen.ALL_PLAYLISTS),
            Triple("view-all-albums", "Albums", Screen.ALL_ALBUMS),
        )) {
            val button = compose.onNodeWithTag(tag).assertIsDisplayed()
            val buttonBounds = button.fetchSemanticsNode().boundsInRoot
            val headingBounds = compose.onNodeWithText(heading).fetchSemanticsNode().boundsInRoot
            assertTrue(buttonBounds.top <= headingBounds.center.y && buttonBounds.bottom >= headingBounds.center.y)
            button.performClick()
            compose.runOnIdle { assertEquals(screen, actions.screens.last()) }
        }
        captureOutputEvidence("collection-library", compose.onRoot())
        compose.runOnIdle { assertEquals(0, actions.playToggles); assertTrue(actions.startedPlaylists.isEmpty()) }
    }

    @Test fun playlistCollectionHasScopedSearchCountsClearAndScrollableGridAndList() {
        val state = mutableStateOf(playerState().copy(screen = Screen.ALL_PLAYLISTS,
            playlists = (1..120).map { Playlist("p$it", "Playlist $it", songCount = it) }))
        compose.setContent { AudioTheme { AudioApp(state.value, actions, artworkUrl = { null }) } }
        val first = compose.onNodeWithTag("playlist-card-p1").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("playlist-card-p2").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals("Phone grid should show two columns", first.top, second.top, 1f)
        assertTrue(first.right <= second.left)
        compose.onNodeWithTag("collection-search").assertIsDisplayed().performTextInput("120")
        compose.runOnIdle {
            assertEquals(CollectionKind.PLAYLISTS to "120", actions.collectionQueries.last())
            state.value = state.value.copy(playlistCollectionQuery = "120")
        }
        compose.onNodeWithText("1 of 120 playlists").assertIsDisplayed()
        compose.onNodeWithTag("playlist-card-p120").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("List view").performClick()
        compose.runOnIdle {
            assertEquals(CollectionKind.PLAYLISTS to CollectionLayout.LIST, actions.collectionLayouts.last())
            state.value = state.value.copy(playlistCollectionLayout = CollectionLayout.LIST)
            assertEquals(listOf("p120"), actions.openedPlaylists)
            assertEquals(0, actions.playToggles)
        }
        compose.onNodeWithTag("collection-list").assertIsDisplayed()
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.runOnIdle {
            assertEquals(CollectionKind.PLAYLISTS to "", actions.collectionQueries.last())
            state.value = state.value.copy(playlistCollectionQuery = "")
        }
        compose.onNodeWithText("120 playlists").assertIsDisplayed()
        compose.onNodeWithTag("collection-list").performScrollToNode(hasTestTag("playlist-card-p120"))
        compose.onNodeWithTag("playlist-card-p120").assertIsDisplayed()
        captureOutputEvidence("collection-playlists-list", compose.onRoot())
        compose.runOnIdle { state.value = state.value.copy(playlistCollectionLayout = CollectionLayout.GRID) }
        compose.onNodeWithTag("collection-grid").performScrollToNode(hasTestTag("playlist-card-p120"))
        compose.onNodeWithTag("playlist-card-p120").assertIsDisplayed()
        compose.onNodeWithTag("mini-player").assertIsDisplayed()
        compose.onNodeWithTag("settings-gear").assertIsDisplayed()
        captureOutputEvidence("collection-playlists-grid", compose.onRoot())
    }

    @get:Rule val compose = createComposeRule()

    private val actions = RecordingActions()
    private lateinit var backDispatcher: OnBackPressedDispatcher
    private val connection = Connection("test-server", "Test server", "https://example.invalid", "test-user")
    private val song = Song("test-song", "Test song", "Test artist", userRating = 2)
    private val session = ListeningSession("test-server", "test-playlist", "Test playlist", listOf(song))

    private fun render(state: AudioUiState) {
        compose.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            AudioTheme { AudioApp(state, actions, artworkUrl = { null }) }
        }
    }

    private fun playerState() = AudioUiState(
        booting = false,
        screen = Screen.PLAYER,
        connection = connection,
        session = session,
        durationMs = 180_000L,
    )

    @Test fun mediaOutputIsBelowPlaybackAndLeftOfQueueAndOpensPicker() {
        render(playerState().copy(playing = true))
        val output = compose.onNodeWithTag("player-output").assertIsDisplayed()
        val outputBounds = output.fetchSemanticsNode().boundsInRoot
        val queueBounds = compose.onNodeWithTag("player-queue").fetchSemanticsNode().boundsInRoot
        val playBounds = compose.onNodeWithTag("player-toggle").fetchSemanticsNode().boundsInRoot
        assertTrue(outputBounds.top >= playBounds.bottom)
        assertTrue(outputBounds.right <= queueBounds.left)
        output.performClick()
        compose.onNodeWithTag("output-sheet-content").assertIsDisplayed()
        compose.onNodeWithTag("output-close").performClick()
        compose.onNodeWithTag("output-sheet-content").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(actions.screens.isEmpty())
            assertEquals(0, actions.playToggles)
        }
    }

    @Test fun mediaOutputTriggerWrapsShortNamesAndBoundsLongNames() {
        val phone = OutputDevice("phone", "Phone", "This phone", OutputDeviceKind.PHONE)
        val state = mutableStateOf(playerState().copy(mediaOutput = MediaOutputState(listOf(phone), phone.id)))
        compose.setContent { AudioTheme { AudioApp(state.value, actions, artworkUrl = { null }) } }
        val output = compose.onNodeWithTag("player-output").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        val shortBounds = output.fetchSemanticsNode().boundsInRoot
        val labelBounds = compose.onNodeWithText("Media output", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val allowance = with(InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics) { 56 * density }
        assertTrue("Short output trigger should hug its icon and two-line label", shortBounds.width <= labelBounds.width + allowance)
        captureOutputEvidence("output-compact-trigger", compose.onRoot())
        val longName = "Wireless headphones with a very long audio output device name"
        compose.runOnIdle { state.value = state.value.copy(mediaOutput = MediaOutputState(listOf(phone.copy(name = longName)), phone.id)) }
        val longBounds = output.fetchSemanticsNode().boundsInRoot
        val queueBounds = compose.onNodeWithTag("player-queue").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Long output names must not displace Queue", longBounds.right <= queueBounds.left)
        assertTrue(longBounds.width > shortBounds.width)
        output.performClick()
        compose.onNodeWithTag("output-sheet-content").assertIsDisplayed()
        compose.onNodeWithTag("output-close").performClick()
        captureOutputEvidence("output-long-trigger", compose.onRoot())
    }

    @Test fun outputPickerWaitsForConfirmationAndKeepsFailuresVisible() {
        val phone = OutputDevice("phone", "This phone", "Built-in speaker", OutputDeviceKind.PHONE)
        val headphones = OutputDevice("headphones", "Wireless headphones", "Bluetooth", OutputDeviceKind.HEADPHONES)
        val state = mutableStateOf(playerState().copy(playing = true,
            mediaOutput = MediaOutputState(devices = listOf(phone, headphones), selectedId = phone.id)))
        compose.setContent { AudioTheme { AudioApp(state.value, actions, artworkUrl = { null }) } }
        compose.onNodeWithTag("player-output").performClick()
        compose.onNodeWithTag("output-route-headphones").performClick()
        compose.onNodeWithTag("output-sheet-content").assertIsDisplayed()
        captureOutputEvidence("output-picker", compose.onNodeWithTag("output-sheet-content"))
        compose.runOnIdle {
            assertEquals(listOf("headphones"), actions.selectedOutputs)
            state.value = state.value.copy(mediaOutput = state.value.mediaOutput.copy(pendingId = "headphones"))
        }
        compose.onNodeWithText("Switching audio output…").assertIsDisplayed()
        compose.onNodeWithTag("output-route-phone").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(mediaOutput = state.value.mediaOutput.copy(pendingId = null, error = "Output disconnected")) }
        compose.onNodeWithText("Output disconnected").assertIsDisplayed()
        compose.onNodeWithTag("output-route-headphones").performClick()
        compose.runOnIdle { state.value = state.value.copy(mediaOutput = state.value.mediaOutput.copy(selectedId = "headphones", error = null)) }
        compose.onNodeWithTag("output-close").assertDoesNotExist()
        compose.onNodeWithContentDescription("Choose output, Wireless headphones").assertIsDisplayed()
        captureOutputEvidence("output-player", compose.onRoot())
        compose.runOnIdle {
            assertEquals(listOf("headphones", "headphones"), actions.selectedOutputs)
            assertEquals(0, actions.playToggles)
            assertEquals(session, state.value.session)
            assertTrue(state.value.playing)
        }
    }

    @Test fun outputPickerReflectsDisconnectsAndPermissionDenial() {
        val phone = OutputDevice("phone", "This phone", "Built-in speaker", OutputDeviceKind.PHONE)
        val headphones = OutputDevice("headphones", "Headphones", "Bluetooth", OutputDeviceKind.HEADPHONES)
        val state = mutableStateOf(playerState().copy(mediaOutput = MediaOutputState(listOf(phone, headphones), "headphones")))
        compose.setContent { AudioTheme { AudioApp(state.value, actions, artworkUrl = { null }) } }
        compose.onNodeWithTag("player-output").performClick()
        compose.runOnIdle { state.value = state.value.copy(mediaOutput = MediaOutputState(listOf(phone), "phone", permissionRequired = true)) }
        compose.onNodeWithTag("output-route-headphones").assertDoesNotExist()
        compose.onNodeWithTag("output-permission").assertIsDisplayed()
        compose.onNodeWithTag("output-close").performClick()
        compose.onNodeWithContentDescription("Choose output, This phone").assertIsDisplayed()
        compose.runOnIdle { assertTrue(actions.selectedOutputs.isEmpty()) }
    }

    @Test fun olderAndroidOutputFallbackDoesNotPretendToSwitchDevices() {
        val phone = OutputDevice("phone", "This phone", "Built-in speaker", OutputDeviceKind.PHONE)
        val headphones = OutputDevice("headphones", "Headphones", "Bluetooth", OutputDeviceKind.HEADPHONES)
        render(playerState().copy(mediaOutput = MediaOutputState(listOf(phone, headphones), "phone", systemPickerRequired = true)))
        compose.onNodeWithTag("player-output").performClick()
        compose.onNodeWithTag("output-route-headphones").assertIsNotEnabled()
        compose.onNodeWithText("Device settings").assertIsDisplayed()
        compose.onNodeWithText("Android manages output switching", substring = true).assertIsDisplayed()
        compose.runOnIdle { assertTrue(actions.selectedOutputs.isEmpty()) }
    }

    @Test fun systemBackFromPlayerOpensLibraryWithoutPausingPlayback() {
        render(playerState().copy(playing = true))
        compose.runOnIdle { backDispatcher.onBackPressed() }
        compose.runOnIdle {
            assertEquals(listOf(Screen.PLAYLISTS), actions.screens)
            assertEquals(0, actions.playToggles)
        }
    }

    @Test fun settingsBackReturnsToLibraryEvenWithASavedSession() {
        render(playerState().copy(screen = Screen.SETTINGS))
        compose.runOnIdle { backDispatcher.onBackPressed() }
        compose.runOnIdle { assertEquals(listOf(Screen.PLAYLISTS), actions.screens) }
    }

    @Test fun everyPageHasASettingsGearAndNoBottomTabs() {
        val state = mutableStateOf(playerState())
        compose.setContent { AudioTheme { AudioApp(state.value, actions, artworkUrl = { null }) } }
        for (screen in Screen.entries) {
            compose.runOnIdle { state.value = state.value.copy(screen = screen) }
            compose.onNodeWithTag("settings-gear").assertIsDisplayed().performClick()
            compose.onNodeWithTag("bottom-navigation").assertDoesNotExist()
            compose.onNodeWithTag("settings-update-dot", useUnmergedTree = true).assertDoesNotExist()
        }
        compose.runOnIdle { assertTrue(actions.screens.all { it == Screen.SETTINGS }) }
    }

    @Test fun systemBackFromLibraryFallsThroughToAndroid() {
        render(playerState().copy(screen = Screen.PLAYLISTS))
        compose.runOnIdle {
            assertEquals(false, backDispatcher.hasEnabledCallbacks())
            var fellThrough = false
            val fallback = object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { fellThrough = true }
            }
            backDispatcher.addCallback(fallback)
            try {
                backDispatcher.onBackPressed()
                assertTrue(fellThrough)
                assertTrue(actions.screens.isEmpty())
            } finally {
                fallback.remove()
            }
        }
    }

    @Test fun systemBackFromQueueStillOpensPlayer() {
        render(playerState().copy(screen = Screen.QUEUE))
        compose.runOnIdle { backDispatcher.onBackPressed() }
        compose.runOnIdle { assertEquals(listOf(Screen.PLAYER), actions.screens) }
    }

    @Test fun welcomeConnectOpensTheRealConnectionRoute() {
        render(AudioUiState(booting = false))
        compose.onNodeWithText("WELCOME TO MUSIC").assertIsDisplayed()
        compose.onNodeWithText("Connect to Navidrome").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(Screen.CONNECT), actions.screens) }
    }

    @Test fun connectionStartsBlankAndPasswordUsesHiddenSemantics() {
        render(AudioUiState(booting = false, screen = Screen.CONNECT))
        compose.onNodeWithText("Use your Navidrome account. You don’t need to create a Music account.").assertIsDisplayed()
        listOf("connect-server-url", "connect-username", "connect-password").forEach { tag ->
            compose.onNodeWithTag(tag).assertIsDisplayed()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        }
        compose.onNodeWithTag("connect-password")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
    }

    @Test fun connectionSubmitsOnlyEnteredCredentials() {
        render(AudioUiState(booting = false, screen = Screen.CONNECT))
        compose.onNodeWithTag("connect-server-url").performTextInput("https://example.invalid")
        compose.onNodeWithTag("connect-username").performTextInput("entered-user")
        compose.onNodeWithTag("connect-password").performTextInput("test-only-password")
        compose.onNodeWithTag("connect-submit").performClick()
        compose.runOnIdle {
            assertEquals(1, actions.connects.size)
            val submitted = actions.connects.single()
            assertEquals("https://example.invalid", submitted.url)
            assertEquals("entered-user", submitted.username)
            assertEquals("test-only-password", submitted.password)
        }
    }

    @Test fun connectingDisablesDuplicateSubmissionAndShowsRealError() {
        render(AudioUiState(
            booting = false, screen = Screen.CONNECT, loading = true,
            error = "Test connection failed",
        ))
        compose.onNodeWithTag("connect-submit").assertIsNotEnabled()
        compose.onNodeWithText("Test connection failed").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, actions.connects.size) }
    }

    @Test fun libraryContinueIsVisibleWithoutScrollingEvenForLongLibraryList() {
        render(AudioUiState(
            booting = false,
            screen = Screen.LIBRARIES,
            connection = connection,
            libraries = (1..20).map { MusicLibrary("library-$it", "Test library $it") },
            selectedLibraries = setOf("library-1"),
        ))
        compose.onNodeWithText("What happens to playlists?", substring = true).assertDoesNotExist()
        // Deliberately no performScrollTo: the setup action must have a fixed visible footer.
        compose.onNodeWithTag("library-continue").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, actions.librarySaves) }
    }

    @Test fun librarySaveDisablesDuplicateContinue() {
        render(AudioUiState(
            booting = false, screen = Screen.LIBRARIES, loading = true,
            libraries = listOf(MusicLibrary("library-1", "Test library")),
            selectedLibraries = setOf("library-1"),
        ))
        compose.onNodeWithTag("library-continue").assertIsDisplayed().assertIsNotEnabled()
    }

    @Test fun libraryPlaylistCardsHaveNoPlaybackShortcut() {
        render(AudioUiState(
            booting = false, screen = Screen.PLAYLISTS, connection = connection,
            playlists = listOf(Playlist("test-playlist", "Test playlist", songCount = 3)),
        ))
        compose.onNodeWithTag("playlist-card-test-playlist").assertIsDisplayed()
        compose.onNodeWithTag("playlist-shuffle-test-playlist", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { assertTrue(actions.startedPlaylists.isEmpty()) }
    }

    @Test fun playlistCardOpensDetailsWithoutStartingPlayback() {
        render(playerState().copy(screen = Screen.PLAYLISTS,
            playlists = listOf(Playlist("test-playlist", "Test playlist", songCount = 1))))
        compose.onNodeWithTag("playlist-card-test-playlist").performClick()
        compose.runOnIdle {
            assertEquals(listOf("test-playlist"), actions.openedPlaylists)
            assertTrue(actions.startedPlaylists.isEmpty())
            assertEquals(0, actions.playToggles)
        }
    }

    @Test fun playlistDetailsListsSongsAndProvidesExplicitShuffle() {
        render(playerState().copy(screen = Screen.PLAYLIST,
            selectedPlaylist = Playlist("test-playlist", "Test playlist", songCount = 1),
            playlistSongs = listOf(song.copy(title = "Playlist track"))))
        compose.onNodeWithText("Playlist track").assertIsDisplayed()
        compose.onNodeWithTag("playlist-play").performClick()
        compose.runOnIdle { assertEquals(listOf("test-playlist"), actions.startedPlaylists) }
    }

    @Test fun searchIncludesServerSongsAndOpensThemForPlayback() {
        render(playerState().copy(screen = Screen.SEARCH, searchQuery = "rare",
            searchSongs = listOf(song.copy(title = "Rare server song"))))
        compose.onNodeWithText("Rare server song").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("test-song"), actions.playedSongs) }
    }

    @Test fun cachedZeroDoesNotPreventCheckingPlaylistContents() {
        render(AudioUiState(
            booting = false, screen = Screen.PLAYLISTS, connection = connection,
            playlists = listOf(Playlist("test-playlist", "Test playlist", songCount = 0)),
        ))
        compose.onNodeWithTag("playlist-card-test-playlist").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(listOf("test-playlist"), actions.openedPlaylists)
            assertTrue(actions.startedPlaylists.isEmpty())
        }
    }

    @Test fun libraryShowsPlaylistAndAlbumShelvesAndSearchWithoutTabs() {
        render(AudioUiState(
            booting = false, screen = Screen.PLAYLISTS, connection = connection,
            playlists = listOf(Playlist("test-playlist", "Test playlist", songCount = 3)),
            albums = listOf(Album("test-album", "Test album", "Test artist", songCount = 2)),
        ))
        compose.onNodeWithText("Playlists").assertIsDisplayed()
        compose.onNodeWithText("Albums").assertIsDisplayed()
        compose.onNodeWithTag("playlist-card-test-playlist").assertIsDisplayed()
        compose.onNodeWithTag("playlist-shuffle-test-playlist", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("album-card-test-album").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("test-album"), actions.openedAlbums) }
        compose.onNodeWithTag("bottom-navigation").assertDoesNotExist()
        compose.onNodeWithText("Search playlists, albums, and songs").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(Screen.SEARCH, actions.screens.last()) }
    }

    @Test fun miniPlayerSpansWidthWithoutTabsAcrossScreens() {
        val state = mutableStateOf(playerState().copy(screen = Screen.PLAYLISTS))
        compose.setContent { AudioTheme { AudioApp(state.value, actions, artworkUrl = { null }) } }
        for (screen in listOf(Screen.PLAYLISTS, Screen.ALL_PLAYLISTS, Screen.ALL_ALBUMS, Screen.PLAYLIST, Screen.SEARCH, Screen.ALBUM, Screen.QUEUE, Screen.SETTINGS, Screen.PLAYER)) {
            compose.runOnIdle { state.value = state.value.copy(screen = screen) }
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag("bottom-navigation").assertDoesNotExist()
            val pixels = compose.onRoot().captureToImage().toPixelMap()
            val edgeX = (root.width - 2).toInt()

            if (screen == Screen.PLAYER) {
                compose.onNodeWithTag("mini-player").assertDoesNotExist()
            } else {
                val mini = compose.onNodeWithTag("mini-player").fetchSemanticsNode().boundsInRoot
                assertEquals("$screen: full-width mini-player", root.width, mini.width, 1f)
                assertTrue("$screen: mini-player stays above system navigation", mini.bottom <= root.bottom)
                assertEquals("$screen: lighter player", Color(0xFF354239), pixels[edgeX, mini.center.y.toInt()])
                compose.onNodeWithTag("mini-player-toggle").assertIsDisplayed().performClick()
            }
        }
        compose.runOnIdle { assertEquals(8, actions.playToggles) }
        assertTrue(actions.screens.isEmpty())
    }

    @Test fun tonalMiniPlayerOpensFullPlayerAndCapturesLibraryAndPlayer() {
        val state = mutableStateOf(playerState().copy(
            screen = Screen.PLAYLISTS,
            playlists = listOf(Playlist("test-playlist", "Test playlist", songCount = 3)),
            albums = listOf(Album("test-album", "Test album", "Test artist", songCount = 2)),
        ))
        compose.setContent { AudioTheme { AudioApp(state.value, actions, artworkUrl = { null }) } }
        for (screen in listOf(Screen.PLAYLISTS, Screen.PLAYER)) {
            compose.runOnIdle { state.value = state.value.copy(screen = screen) }
            compose.waitForIdle()
            val image = compose.onRoot().captureToImage().asAndroidBitmap()
            val directory = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "evidence").apply { mkdirs() }
            java.io.File(directory, "tonal-${screen.name.lowercase()}.png").outputStream().use {
                assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
            image.recycle()
        }
        compose.runOnIdle { state.value = state.value.copy(screen = Screen.PLAYLISTS) }
        compose.onNodeWithText("Test song").performClick()
        compose.runOnIdle { assertEquals(listOf(Screen.PLAYER), actions.screens) }
    }

    @Test fun albumDetailsStartsTheSelectedAlbumAndListsItsTracks() {
        render(AudioUiState(
            booting = false, screen = Screen.ALBUM, connection = connection,
            selectedAlbum = Album("test-album", "Test album", "Test artist", songCount = 1),
            albumSongs = listOf(song.copy(id = "album-track", title = "Album track")),
        ))
        compose.onNodeWithText("Album track").assertIsDisplayed()
        compose.onNodeWithTag("album-play").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("test-album"), actions.playedAlbums) }
    }

    @Test fun playerTransportCallsParentActions() {
        render(playerState())
        compose.onNodeWithTag("player-toggle").assertIsDisplayed().performClick()
        compose.onNodeWithTag("player-next").assertIsDisplayed().performClick()
        compose.onNodeWithTag("player-seek").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, actions.playToggles)
            assertEquals(1, actions.nextCalls)
        }
    }

    @Test fun playerQueueIsBelowTransportAndStillOpensQueueWithoutTogglingPlayback() {
        render(playerState())
        val transport = compose.onNodeWithTag("player-toggle").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val queue = compose.onNodeWithTag("player-queue").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Queue must be below the transport buttons", queue.top >= transport.bottom)
        compose.onNodeWithContentDescription("Shuffle").assertIsDisplayed()
        compose.onNodeWithContentDescription("Repeat").assertIsDisplayed()
        compose.onNodeWithTag("player-queue").performClick()
        compose.runOnIdle {
            assertEquals(listOf(Screen.QUEUE), actions.screens)
            assertEquals(0, actions.playToggles)
        }
    }

    @Test fun ratingModalHasFiveLargeRadioTargetsAndDoneClosesWithoutSaving() {
        render(playerState())
        compose.onNodeWithTag("player-rating").assertIsDisplayed().performClick()
        compose.onNodeWithTag("player-queue").assertIsDisplayed()
        compose.onNodeWithTag("settings-gear").assertIsDisplayed()
        compose.onNodeWithText("Change playlist").assertDoesNotExist()
        compose.onNodeWithText("READY TO RESUME").assertDoesNotExist()
        compose.onNodeWithText("Tap a star to save to Navidrome. Clear removes your song rating.").assertDoesNotExist()
        (1..5).forEach { value ->
            compose.onNodeWithTag("rating-$value")
                .assertIsDisplayed()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(64.dp)
        }
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithTag("rating-1").assertDoesNotExist()
        compose.onNodeWithTag("player-rating").assertIsDisplayed()
        compose.runOnIdle { assertEquals(emptyList<Int>(), actions.ratings) }
    }

    @Test fun tappingRatingCallsParentWithoutMutatingTheSessionSong() {
        render(playerState())
        compose.onNodeWithTag("player-rating").performClick()
        compose.onNodeWithTag("rating-4").performClick()
        compose.runOnIdle {
            assertEquals(listOf(4), actions.ratings)
            assertEquals(2, session.queue.single().userRating)
        }
    }

    @Test fun clearRatingSendsZeroToParent() {
        render(playerState())
        compose.onNodeWithTag("player-rating").performClick()
        compose.onNodeWithText("Clear rating").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(0), actions.ratings) }
    }

    @Test fun pendingRatingDisablesChoicesAndErrorRetryResendsTheAttempt() {
        val state = mutableStateOf(playerState())
        compose.setContent { AudioTheme { AudioApp(state.value, actions, artworkUrl = { null }) } }
        compose.onNodeWithTag("player-rating").performClick()
        compose.onNodeWithTag("rating-4").performClick()
        compose.runOnIdle { state.value = state.value.copy(ratingBusy = true) }
        (1..5).forEach { compose.onNodeWithTag("rating-$it").assertIsNotEnabled() }
        compose.onNodeWithText("Clear rating").assertIsNotEnabled()
        compose.runOnIdle {
            state.value = state.value.copy(ratingBusy = false, ratingError = "Test rating save failed")
        }
        compose.onNodeWithText("Test rating save failed").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(listOf(4, 4), actions.ratings)
            assertEquals(2, state.value.session!!.queue.single().userRating)
        }
    }

    private fun captureOutputEvidence(name: String, node: androidx.compose.ui.test.SemanticsNodeInteraction) {
        val image = node.captureToImage().asAndroidBitmap()
        val directory = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "evidence").apply { mkdirs() }
        java.io.File(directory, "$name.png").outputStream().use { assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        image.recycle()
    }

    private data class ConnectCall(val name: String, val url: String, val username: String, val password: String)

    private class RecordingActions : AudioUiActions {
        val collectionQueries = mutableListOf<Pair<CollectionKind, String>>()
        val collectionLayouts = mutableListOf<Pair<CollectionKind, CollectionLayout>>()
        val collectionRefreshes = mutableListOf<CollectionKind>()
        var pageRefreshes = 0
        override fun refreshCurrentPage() { pageRefreshes++ }
        override fun refreshCollection(kind: CollectionKind) { collectionRefreshes += kind }
        override fun setCollectionQuery(kind: CollectionKind, query: String) { collectionQueries += kind to query }
        override fun setCollectionLayout(kind: CollectionKind, layout: CollectionLayout) { collectionLayouts += kind to layout }
        val screens = mutableListOf<Screen>()
        val connects = mutableListOf<ConnectCall>()
        val startedPlaylists = mutableListOf<String>()
        val openedPlaylists = mutableListOf<String>()
        val playedSongs = mutableListOf<String>()
        val openedAlbums = mutableListOf<String>()
        val playedAlbums = mutableListOf<String>()
        val ratings = mutableListOf<Int>()
        val selectedOutputs = mutableListOf<String>()
        override fun selectMediaOutput(id: String) { selectedOutputs += id }
        var librarySaves = 0
        var playToggles = 0
        var nextCalls = 0
        override fun connect(name: String, url: String, username: String, password: String) {
            connects += ConnectCall(name, url, username, password)
        }
        override fun showScreen(screen: Screen) { screens += screen }
        override fun setLibrary(id: String, selected: Boolean) = Unit
        override fun saveLibraries() { librarySaves++ }
        override fun loadPlaylists() = Unit
        override fun loadAlbums() = Unit
        override fun openAlbum(id: String) { openedAlbums += id }
        override fun playAlbum(id: String) { playedAlbums += id }
        override fun startPlaylist(id: String) { startedPlaylists += id }
        override fun openPlaylist(id: String) { openedPlaylists += id }
        override fun playSong(song: Song) { playedSongs += song.id }
        override fun togglePlay() { playToggles++ }
        override fun next() { nextCalls++ }
        override fun previous() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun toggleShuffle() = Unit
        override fun toggleRepeat() = Unit
        override fun rateSong(rating: Int) { ratings += rating }
        override fun clearError() = Unit
        override fun switchConnection(id: String) = Unit
    }
}
