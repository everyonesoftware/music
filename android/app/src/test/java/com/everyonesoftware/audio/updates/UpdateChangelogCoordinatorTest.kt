package com.everyonesoftware.audio.updates

import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateChangelogCoordinatorTest {
    @get:Rule val folder = TemporaryFolder()
    private val from = "a".repeat(40)
    private val to = "b".repeat(40)
    private val embedded = Json.parseToJsonElement("""{"schema":1,"platform":"android","head":{"version":"0.1.101","sha":"$to"},"releases":[{"version":"0.1.100","sha":"$from","commits":[]},{"version":"0.1.101","sha":"$to","commits":[{"sha":"$to","subject":"feat: Improve playback","kind":"improvement","text":"Improve playback"}]}]}""").jsonObject
    private val release = UpdateRelease(101, "0.1.101", "Music-0.1.101.apk", 3, "a".repeat(64),
        "https://github.com/everyonesoftware/music/releases/download/v0.1.101/Music-0.1.101.apk", sourceSha = to, changelog = embedded)

    @Test fun restorationRebuildsLocalRangeImmediatelyAndDiscardsStaleTransientHistory() = runTest {
        for (target in listOf(release, release.copy(changelog = null))) {
            val stale = UpdateChangelog(from, from, listOf("Old release-body notes"), emptyList(), emptyList())
            val updater = UpdateCoordinator(backgroundScope, 100, folder.newFolder(), latest = { error("No check") },
                download = { _, _, _ -> error("No download") }, verify = { _, _ -> },
                initialState = UpdateUiState(release = target, changelog = stale, changelogStage = ChangelogStage.AVAILABLE))
            assertEquals(UpdateStage.AVAILABLE, updater.state.value.stage)
            assertEquals(if (target.changelog == null) ChangelogStage.UNAVAILABLE else ChangelogStage.AVAILABLE,
                updater.state.value.changelogStage)
            assertFalse(updater.state.value.changelog?.improvements?.contains("Old release-body notes") == true)
            assertEquals(target.localChangelog(100), updater.state.value.changelog)
        }
    }

    @Test fun replacementAndUnavailableHistoryNeverRetainOldNotesOrBlockDownloads() = runTest {
        var offered = release
        var downloads = 0
        val updater = UpdateCoordinator(backgroundScope, 100, folder.newFolder(), latest = { offered },
            download = { _, file, _ -> downloads++; file.writeText("apk") }, verify = { _, _ -> })
        updater.check()!!.join()
        assertNotNull(updater.state.value.changelog)
        // Same version, different package/source identity: never reuse previous local history.
        offered = release.copy(sha256 = "c".repeat(64), sourceSha = "d".repeat(40))
        updater.check()!!.join()
        assertEquals(offered, updater.state.value.release)
        assertNull(updater.state.value.changelog)
        assertEquals(ChangelogStage.UNAVAILABLE, updater.state.value.changelogStage)
        assertNull(updater.state.value.error)
        updater.download()!!.join()
        assertEquals(1, downloads)
        assertEquals(UpdateStage.READY, updater.state.value.stage)
        assertEquals(ChangelogStage.UNAVAILABLE, updater.state.value.changelogStage)
        // Once installed/no offer, history is cleared too.
        offered = release.copy(count = 100)
        updater.check()!!.join()
        assertNull(updater.state.value.release)
        assertNull(updater.state.value.changelog)
        assertEquals(ChangelogStage.IDLE, updater.state.value.changelogStage)
    }

    @Test fun checkPreparesHistoryBeforeOfferingWithoutDisclosureWork() = runTest {
        val updater = UpdateCoordinator(backgroundScope, 100, folder.newFolder(), latest = { release },
            download = { _, _: File, _ -> }, verify = { _, _ -> })
        updater.check()!!.join()
        assertEquals(UpdateStage.AVAILABLE, updater.state.value.stage)
        assertEquals(ChangelogStage.AVAILABLE, updater.state.value.changelogStage)
        assertEquals(listOf("Improve playback"), updater.state.value.changelog?.improvements)
        assertNull(updater.retryChangelog())
        assertEquals(ChangelogStage.AVAILABLE, updater.state.value.changelogStage)
    }
}
