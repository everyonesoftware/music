package com.everyonesoftware.audio.updates

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateChangelogCoordinatorTest {
    @get:Rule val folder = TemporaryFolder()
    private val release = UpdateRelease(200, "0.1.200", "Music-0.1.200.apk", 3, "a".repeat(64),
        "https://github.com/everyonesoftware/music/releases/download/v0.1.200/Music-0.1.200.apk")
    private val history = UpdateChangelog("a".repeat(40), "b".repeat(40), listOf("Improve playback"), emptyList(),
        listOf(UpdateChangeCommit("b".repeat(40), "feat: Improve playback")))

    @Test fun historyFailureNeverBlocksDownloadAndRetryIsIndependent() = runTest {
        var calls = 0
        var downloads = 0
        val updater = UpdateCoordinator(backgroundScope, 100, folder.newFolder(), latest = { release },
            download = { _, file, _ -> downloads++; file.writeText("apk") }, verify = { _, _ -> },
            loadChangelog = { _, _ -> if (++calls == 1) error("private URL") else history })
        updater.check(); runCurrent()
        assertEquals(0, calls)
        updater.retryChangelog(); runCurrent()
        assertEquals(UpdateStage.AVAILABLE, updater.state.value.stage)
        assertEquals(ChangelogStage.UNAVAILABLE, updater.state.value.changelogStage)
        assertNull(updater.state.value.error)
        updater.retryChangelog(); runCurrent()
        assertEquals(history, updater.state.value.changelog)
        assertEquals(ChangelogStage.AVAILABLE, updater.state.value.changelogStage)
        updater.download()!!.join()
        assertEquals(1, downloads)
        assertEquals(UpdateStage.READY, updater.state.value.stage)
        assertEquals(history, updater.state.value.changelog)
    }

    @Test fun obsoleteHistoryCannotDescribeReplacementEvenIfCancellationIsIgnored() = runTest {
        val oldGate = CompletableDeferred<Unit>()
        var offered = release
        val updater = UpdateCoordinator(backgroundScope, 100, folder.newFolder(), latest = { offered },
            download = { _, _: File, _ -> }, verify = { _, _ -> },
            loadChangelog = { _, target ->
                if (target == release) withContext(NonCancellable) { oldGate.await() }
                history.copy(toSha = target.sha256.take(40))
            })
        updater.check(); runCurrent()
        updater.retryChangelog(); runCurrent()
        assertEquals(ChangelogStage.LOADING, updater.state.value.changelogStage)
        offered = release.copy(count = 201, version = "0.1.201", sha256 = "c".repeat(64))
        updater.check(); runCurrent()
        assertEquals(ChangelogStage.IDLE, updater.state.value.changelogStage)
        updater.retryChangelog(); runCurrent()
        assertEquals("c".repeat(40), updater.state.value.changelog?.toSha)
        oldGate.complete(Unit); runCurrent()
        assertEquals(offered, updater.state.value.release)
        assertEquals("c".repeat(40), updater.state.value.changelog?.toSha)
    }

    @Test fun synchronouslyCompletingHistoryCanBeRetriedWithoutAnUnassignedJob() = runTest {
        var calls = 0
        val immediate = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val updater = UpdateCoordinator(immediate, 100, folder.newFolder(), latest = { release },
            download = { _, _, _ -> }, verify = { _, _ -> },
            initialState = UpdateUiState(release = release), loadChangelog = { _, _ ->
                if (++calls == 1) error("Transient history failure") else history
            })
        assertTrue(updater.retryChangelog()!!.isCompleted)
        assertEquals(ChangelogStage.UNAVAILABLE, updater.state.value.changelogStage)
        assertTrue(updater.retryChangelog()!!.isCompleted)
        assertEquals(2, calls)
        assertEquals(history, updater.state.value.changelog)
        assertEquals(ChangelogStage.AVAILABLE, updater.state.value.changelogStage)
    }

    @Test fun restoredReleaseLoadsHistoryWithoutCheckingOrDownloading() = runTest {
        var calls = 0
        val updater = UpdateCoordinator(backgroundScope, 100, folder.newFolder(), latest = { error("No check") },
            download = { _, _, _ -> error("No download") }, verify = { _, _ -> },
            initialState = UpdateUiState(release = release), loadChangelog = { code, target ->
                assertEquals(100L, code); assertEquals(release, target); calls++; history
            })
        updater.retryChangelog(); runCurrent()
        assertEquals(1, calls)
        assertEquals(history, updater.state.value.changelog)
        assertEquals(UpdateStage.AVAILABLE, updater.state.value.stage)
    }
}
