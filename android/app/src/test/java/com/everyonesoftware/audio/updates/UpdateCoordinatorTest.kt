package com.everyonesoftware.audio.updates

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateCoordinatorTest {
    @get:Rule val folder = TemporaryFolder()
    private val release = UpdateRelease(200, "0.1.200", "Music-0.1.200.apk", 3, "a".repeat(64),
        "https://github.com/everyonesoftware/music/releases/download/v0.1.200/Music-0.1.200.apk", "Fixture notes")
    private class Fixture(val directory: File) {
        var clock = 1_000_000L
        var requests = 0
        var downloads = 0
        var verifications = 0
        var fetch: suspend (Long) -> UpdateRelease? = { null }
        var transfer: suspend (UpdateRelease, File, (Long, Long) -> Unit) -> Unit = { _, file, progress ->
            downloads++; file.writeBytes(byteArrayOf(1, 2, 3)); progress(3, 3)
        }
        var verify: suspend (File, UpdateRelease) -> Unit = { _, _ -> verifications++ }
        val persisted = mutableListOf<Pair<UpdateUiState, Long?>>()
        fun coordinator(scope: TestScope, initial: UpdateUiState = UpdateUiState(), attempt: Long? = null) =
            UpdateCoordinator(scope.backgroundScope, 100, directory,
                latest = { code -> requests++; fetch(code) },
                download = { release, file, progress -> transfer(release, file, progress) },
                verify = { file, release -> verify(file, release) }, initialState = initial,
                initialLastAttemptAt = attempt, now = { clock }, persist = { state, lastAttempt -> persisted += state to lastAttempt },
                io = StandardTestDispatcher(scope.testScheduler))
    }
    @Test fun automaticChecksAreDailyAndManualChecksBypassInterval() = runTest {
        val f = Fixture(folder.newFolder())
        val updater = f.coordinator(this)
        updater.check(automatic = true); runCurrent()
        assertEquals(1, f.requests)
        updater.check(automatic = true); runCurrent()
        assertEquals(1, f.requests)
        updater.check(); runCurrent()
        assertEquals(2, f.requests)
        f.clock += 86_400_000L
        updater.check(automatic = true); runCurrent()
        assertEquals(3, f.requests)
    }
    @Test fun failedAutomaticCheckRetriesAfterOneMinuteInsteadOfOneDay() = runTest {
        val f = Fixture(folder.newFolder()).apply { fetch = { error("private URI") } }
        val updater = f.coordinator(this)
        updater.check(automatic = true); runCurrent()
        f.clock += 59_999L
        updater.check(automatic = true); runCurrent()
        assertEquals(1, f.requests)
        f.clock++
        f.fetch = { null }
        updater.check(automatic = true); runCurrent()
        assertEquals(2, f.requests)
        assertEquals(UpdateStage.IDLE, updater.state.value.stage)
        assertNull(updater.state.value.error)
        updater.check(automatic = true); runCurrent()
        assertEquals(2, f.requests)
    }
    @Test fun disabledChecksNeverContactNetworkButManualStillWorks() = runTest {
        val f = Fixture(folder.newFolder())
        val updater = f.coordinator(this, UpdateUiState(automaticChecks = false))
        updater.check(automatic = true); runCurrent()
        assertEquals(0, f.requests)
        updater.check(); runCurrent()
        assertEquals(1, f.requests)
        assertFalse(updater.state.value.automaticChecks)
    }
    @Test fun failuresAreThrottledAcrossProcessRestorationAndDoNotExposeCauses() = runTest {
        val f = Fixture(folder.newFolder()).apply { fetch = { error("private URI") } }
        val updater = f.coordinator(this)
        updater.check(automatic = true); runCurrent()
        assertEquals(UpdateStage.ERROR, updater.state.value.stage)
        assertFalse(updater.state.value.error.orEmpty().contains("private"))
        val restored = f.coordinator(this, attempt = f.persisted.last().second)
        restored.check(automatic = true); runCurrent()
        assertEquals(1, f.requests)
    }
    @Test fun latestFailedCheckSurvivesRecreationWithoutShowingUpToDate() = runTest {
        val f = Fixture(folder.newFolder())
        val updater = f.coordinator(this)
        updater.check(); runCurrent()
        val successfulAt = updater.state.value.lastCheckedAt
        f.clock++
        f.fetch = { error("https://private.example/?token=secret") }
        updater.check(); runCurrent()
        val (saved, attempt) = f.persisted.last()
        val restored = f.coordinator(this, saved, attempt)
        assertEquals(UpdateStage.ERROR, restored.state.value.stage)
        assertEquals(successfulAt, restored.state.value.lastCheckedAt)
        assertNotNull(restored.state.value.error)
        assertFalse(restored.state.value.error!!.contains("secret"))
        f.clock += 60_000L
        f.fetch = { null }
        restored.check(automatic = true); runCurrent()
        assertEquals(3, f.requests)
        assertEquals(UpdateStage.IDLE, restored.state.value.stage)
    }
    @Test fun repeatedFailuresBackOffToFifteenMinutesAcrossRecreationAndSuccessResets() = runTest {
        val f = Fixture(folder.newFolder()).apply { fetch = { error("offline") } }
        var updater = f.coordinator(this)
        updater.check(automatic = true); runCurrent()
        for (delay in listOf(60_000L, 120_000L, 240_000L, 480_000L, 900_000L, 900_000L)) {
            val (saved, attempt) = f.persisted.last()
            updater = f.coordinator(this, saved, attempt)
            val count = f.requests
            f.clock += delay - 1
            updater.check(automatic = true); runCurrent()
            assertEquals("Retry must wait $delay ms", count, f.requests)
            f.clock++
            updater.check(automatic = true); runCurrent()
            assertEquals(count + 1, f.requests)
        }
        f.fetch = { null }
        updater.check(); runCurrent()
        f.fetch = { error("offline") }
        updater.check(); runCurrent()
        val count = f.requests
        f.clock += 60_000L
        updater.check(automatic = true); runCurrent()
        assertEquals(count + 1, f.requests)
    }
    @Test fun typedFailuresPersistSafeMessagesAndUseLongerBoundedCooldown() = runTest {
        for (kind in listOf(UpdateFailureKind.RATE_LIMITED, UpdateFailureKind.INVALID_METADATA)) {
            val f = Fixture(folder.newFolder()).apply { fetch = { throw UpdateException(kind) } }
            val updater = f.coordinator(this)
            updater.check(); runCurrent()
            assertEquals(kind, updater.state.value.checkFailure)
            assertEquals(kind.checkMessage, updater.state.value.error)
            val (saved, attempt) = f.persisted.last()
            val restored = f.coordinator(this, saved.copy(error = "https://user:secret@example.test"), attempt)
            assertEquals(kind.checkMessage, restored.state.value.error)
            f.clock += 899_999L
            restored.check(automatic = true); runCurrent()
            assertEquals(1, f.requests)
            f.clock++
            restored.check(automatic = true); runCurrent()
            assertEquals(2, f.requests)
            restored.check(); runCurrent()
            assertEquals(3, f.requests)
        }
    }
    @Test fun rateLimitDeadlineSurvivesRecreationAndManualChecksRemainExplicit() = runTest {
        val f = Fixture(folder.newFolder()).apply {
            fetch = { throw UpdateException(UpdateFailureKind.RATE_LIMITED, retryAfterMs = 3_600_000L) }
        }
        val updater = f.coordinator(this)
        updater.check(automatic = true); runCurrent()
        val (saved, attempt) = f.persisted.last()
        val restored = f.coordinator(this, saved, attempt)
        f.clock += 3_599_999L
        restored.check(automatic = true); runCurrent()
        assertEquals("Server cooldown must outlast the local fifteen-minute fallback", 1, f.requests)
        f.clock++
        f.fetch = { null }
        restored.check(automatic = true); runCurrent()
        assertEquals(2, f.requests)
        restored.check(); runCurrent()
        assertEquals(3, f.requests)
    }
    @Test fun failedManualChecksCannotEraseOrShortenAnOutstandingServerCooldown() = runTest {
        val f = Fixture(folder.newFolder()).apply {
            fetch = { throw UpdateException(UpdateFailureKind.RATE_LIMITED, retryAfterMs = 3_600_000L) }
        }
        val updater = f.coordinator(this)
        updater.check(); runCurrent()
        val deadline = updater.state.value.checkRetryAt
        f.clock += 1000L
        f.fetch = { throw UpdateException() }
        updater.check(); runCurrent()
        assertEquals(deadline, updater.state.value.checkRetryAt)
        f.fetch = { throw UpdateException(UpdateFailureKind.RATE_LIMITED, retryAfterMs = 60_000L) }
        updater.check(); runCurrent()
        assertEquals(deadline, updater.state.value.checkRetryAt)
        val (saved, attempt) = f.persisted.last()
        val restored = f.coordinator(this, saved, attempt)
        f.clock += 900_000L
        restored.check(automatic = true); runCurrent()
        assertEquals(3, f.requests)
        f.fetch = { null }
        restored.check(); runCurrent()
        assertNull(restored.state.value.checkRetryAt)
    }

    @Test fun checksCoalesceAndDoNotDiscardAvailableReleaseWhilePending() = runTest {
        val gate = CompletableDeferred<UpdateRelease?>()
        val f = Fixture(folder.newFolder()).apply { fetch = { gate.await() } }
        val updater = f.coordinator(this, UpdateUiState(stage = UpdateStage.AVAILABLE, release = release))
        updater.check(); updater.check(); runCurrent()
        assertEquals(1, f.requests)
        assertEquals(release, updater.state.value.release)
        gate.complete(release); runCurrent()
        assertEquals(UpdateStage.AVAILABLE, updater.state.value.stage)
    }
    @Test fun downloadsOnlyWhenRequestedAndBecomesReadyOnlyAfterVerification() = runTest {
        val f = Fixture(folder.newFolder()).apply { fetch = { release } }
        val updater = f.coordinator(this)
        updater.check(); runCurrent()
        assertEquals(0, f.downloads)
        updater.download(); runCurrent()
        assertEquals(1, f.downloads)
        assertEquals(1, f.verifications)
        assertEquals(UpdateStage.READY, updater.state.value.stage)
        assertTrue(File(f.directory, "update.apk").isFile)
        assertEquals(3L, updater.state.value.downloadedBytes)
    }
    @Test fun verificationFailureRemovesDownloadedFileAndAllowsRetry() = runTest {
        val f = Fixture(folder.newFolder()).apply { verify = { _, _ -> error("certificate") } }
        val updater = f.coordinator(this, UpdateUiState(stage = UpdateStage.AVAILABLE, release = release))
        updater.download(); runCurrent()
        assertEquals(UpdateStage.ERROR, updater.state.value.stage)
        assertFalse(File(f.directory, "update.apk").exists())
        assertTrue(f.directory.listFiles().orEmpty().isEmpty())
        f.verify = { _, _ -> }
        updater.download(); runCurrent()
        assertEquals(UpdateStage.READY, updater.state.value.stage)
    }
    @Test fun cancellationRemovesPartialAndNeverPublishesReady() = runTest {
        val f = Fixture(folder.newFolder()).apply { transfer = { _, file, progress ->
            file.writeBytes(byteArrayOf(1)); progress(1, 3); awaitCancellation()
        } }
        val updater = f.coordinator(this, UpdateUiState(stage = UpdateStage.AVAILABLE, release = release))
        updater.download(); runCurrent()
        assertEquals(UpdateStage.DOWNLOADING, updater.state.value.stage)
        updater.cancelDownload(); runCurrent()
        assertEquals(UpdateStage.AVAILABLE, updater.state.value.stage)
        assertTrue(f.directory.listFiles().orEmpty().isEmpty())
        assertEquals(0, f.verifications)
    }
    @Test fun cachedDownloadIsReverifiedWithoutNetwork() = runTest {
        val f = Fixture(folder.newFolder())
        File(f.directory, "update.apk").writeBytes(byteArrayOf(1, 2, 3))
        val updater = f.coordinator(this, UpdateUiState(stage = UpdateStage.AVAILABLE, release = release))
        updater.download(); runCurrent()
        assertEquals(0, f.downloads)
        assertEquals(1, f.verifications)
        assertEquals(UpdateStage.READY, updater.state.value.stage)
    }
    @Test fun installingVersionNeverExposesDowngrade() = runTest {
        val f = Fixture(folder.newFolder()).apply { fetch = { release.copy(count = 99) } }
        val updater = f.coordinator(this, UpdateUiState(release = release.copy(count = 99)))
        assertNull(updater.state.value.release)
        updater.check(); runCurrent()
        assertNull(updater.state.value.release)
    }
    @Test fun turningOffAutomaticChecksCancelsPendingAutomaticRequest() = runTest {
        val f = Fixture(folder.newFolder()).apply { fetch = { awaitCancellation() } }
        val updater = f.coordinator(this)
        updater.check(automatic = true); runCurrent()
        updater.setAutomaticChecks(false); runCurrent()
        assertFalse(updater.state.value.automaticChecks)
        assertEquals(UpdateStage.IDLE, updater.state.value.stage)
    }

    @Test fun lateCancelledDownloadCannotPublishProgressOrReady() = runTest {
        val gate = CompletableDeferred<Unit>()
        val f = Fixture(folder.newFolder()).apply { transfer = { _, file, progress ->
            file.writeBytes(byteArrayOf(1))
            withContext(NonCancellable) { gate.await(); progress(3, 3) }
        } }
        val updater = f.coordinator(this, UpdateUiState(release = release))
        updater.download(); runCurrent()
        updater.cancelDownload(); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals(UpdateStage.AVAILABLE, updater.state.value.stage)
        assertEquals(0L, updater.state.value.downloadedBytes)
        assertFalse(File(f.directory, "update.apk").exists())
    }

    @Test fun installerHandoffFencesReplacementUntilInstallerReturns() = runTest {
        val f = Fixture(folder.newFolder()).apply { fetch = { release } }
        val updater = f.coordinator(this, UpdateUiState(release = release))
        updater.download(); runCurrent()
        assertNotNull(updater.beginInstall())
        updater.check(); updater.download(); runCurrent()
        assertEquals(0, f.requests)
        assertEquals(1, f.downloads)
        assertNull(updater.beginInstall())
        updater.installerReturned()
        assertNotNull(updater.beginInstall())
    }

    @Test fun newDownloadCleansOldInterruptedPartialFiles() = runTest {
        val f = Fixture(folder.newFolder())
        File(f.directory, "old.part").writeText("interrupted")
        val updater = f.coordinator(this, UpdateUiState(release = release))
        updater.download(); runCurrent()
        assertFalse(File(f.directory, "old.part").exists())
        assertEquals(UpdateStage.READY, updater.state.value.stage)
    }

    @Test fun failedInstallReturnsToRecoverableDownloadStage() = runTest {
        val f = Fixture(folder.newFolder())
        val updater = f.coordinator(this, UpdateUiState(release = release))
        updater.download(); runCurrent()
        assertNotNull(updater.beginInstall())
        updater.installFailed()
        assertEquals(UpdateStage.ERROR, updater.state.value.stage)
        assertEquals(release, updater.state.value.release)
        updater.download(); runCurrent()
        assertEquals(UpdateStage.READY, updater.state.value.stage)
        assertEquals(1, f.downloads)
    }
}
