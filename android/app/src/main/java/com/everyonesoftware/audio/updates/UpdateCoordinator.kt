package com.everyonesoftware.audio.updates

import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Independent of the music account and player. Requests are serialized, never owned by a screen. */
class UpdateCoordinator(
    private val scope: CoroutineScope,
    private val installedCode: Long,
    private val directory: File,
    private val latest: suspend (Long) -> UpdateRelease?,
    private val download: suspend (UpdateRelease, File, (Long, Long) -> Unit) -> Unit,
    private val verify: suspend (File, UpdateRelease) -> Unit,
    initialState: UpdateUiState = UpdateUiState(),
    initialLastAttemptAt: Long? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val persist: (UpdateUiState, Long?) -> Unit = { _, _ -> },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val initialRelease = initialState.release?.takeIf { it.count > installedCode }
    private val unfinishedCheck = initialState.checkOutcome in listOf(UpdateCheckOutcome.CHECKING, UpdateCheckOutcome.FAILED) ||
        (initialState.checkOutcome == UpdateCheckOutcome.NEVER && initialLastAttemptAt != null &&
            (initialState.lastCheckedAt == null || initialLastAttemptAt > initialState.lastCheckedAt))
    private val initialHistory = initialRelease?.localChangelog(installedCode)
    private val current = MutableStateFlow(initialState.copy(release = initialRelease,
        changelog = initialHistory,
        changelogStage = when { initialRelease == null -> ChangelogStage.IDLE; initialHistory == null -> ChangelogStage.UNAVAILABLE; else -> ChangelogStage.AVAILABLE },
        stage = when { unfinishedCheck -> UpdateStage.ERROR; initialRelease != null -> UpdateStage.AVAILABLE; else -> UpdateStage.IDLE },
        checkOutcome = if (unfinishedCheck) UpdateCheckOutcome.FAILED else initialState.checkOutcome,
        checkFailure = if (unfinishedCheck) initialState.checkFailure ?: UpdateFailureKind.TRANSIENT else null,
        error = if (unfinishedCheck) (initialState.checkFailure ?: UpdateFailureKind.TRANSIENT).checkMessage else null))
    val state = current.asStateFlow()
    private var request: Job? = null
    private var lastAttemptAt = initialLastAttemptAt
    @Volatile private var generation = 0L
    private var installationOpen = false
    /** Kept for existing action callers; disclosure never performs work or network requests. */
    fun retryChangelog(): Job? = null

    fun check(): Job? = check(CheckTrigger.MANUAL)
    fun checkAtLaunch(): Job? = check(CheckTrigger.LAUNCH)
    fun recoverCheck(): Job? = check(CheckTrigger.RECOVERY)

    private fun check(trigger: CheckTrigger): Job? {
        if (installationOpen || request?.isCompleted == false || current.value.stage in listOf(UpdateStage.DOWNLOADING, UpdateStage.VERIFYING)) return request
        val time = now()
        val before = current.value
        val serverCooldown = before.checkRetryAt?.let { it > time && it - time <= DAY_MS } == true
        if (trigger != CheckTrigger.MANUAL && serverCooldown) return null
        if (trigger == CheckTrigger.RECOVERY && (before.checkOutcome != UpdateCheckOutcome.FAILED ||
                lastAttemptAt?.let { time >= it && time - it < retryDelay() } == true)) return null
        val ticket = ++generation
        lastAttemptAt = time
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                save(current.value, time)
                val found = latest(installedCode)?.takeIf { it.count > installedCode }
                if (ticket == generation) {
                    val history = found?.localChangelog(installedCode)
                    finishCheck(current.value.copy(release = found, changelog = history,
                        changelogStage = when { found == null -> ChangelogStage.IDLE; history == null -> ChangelogStage.UNAVAILABLE; else -> ChangelogStage.AVAILABLE },
                        lastCheckedAt = now(), error = null,
                        checkOutcome = UpdateCheckOutcome.SUCCESS, checkFailure = null, consecutiveCheckFailures = 0, checkRetryAt = null,
                        downloadedBytes = if (found == before.release && before.stage == UpdateStage.READY) before.downloadedBytes else 0,
                        stage = when { found == null -> UpdateStage.IDLE; found == before.release && before.stage == UpdateStage.READY -> UpdateStage.READY; else -> UpdateStage.AVAILABLE }), time)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (ticket == generation) {
                    val kind = (failure as? UpdateException)?.kind ?: UpdateFailureKind.TRANSIENT
                    val failedAt = now()
                    val retryAt = listOfNotNull(
                        before.checkRetryAt?.takeIf { it > failedAt },
                        (failure as? UpdateException)?.retryAfterMs?.let { failedAt + it.coerceIn(0L, DAY_MS) },
                    ).maxOrNull()
                    finishCheck(current.value.copy(stage = if (before.stage == UpdateStage.READY) UpdateStage.READY else UpdateStage.ERROR,
                        consecutiveCheckFailures = before.consecutiveCheckFailures.coerceIn(0, 4) + 1,
                        checkOutcome = UpdateCheckOutcome.FAILED, checkFailure = kind,
                        checkRetryAt = retryAt,
                        error = kind.checkMessage), time)
                }
            } finally {
                if (ticket == generation && current.value.stage == UpdateStage.CHECKING) {
                    current.update { before.copy(installPermissionRequired = it.installPermissionRequired) }
                }
            }
        }
        // All entry points are main-thread confined. Reserve before publishing state or scheduling work.
        request = job
        current.update { it.copy(stage = UpdateStage.CHECKING, error = null, checkOutcome = UpdateCheckOutcome.CHECKING) }
        job.start()
        return job
    }

    fun download(): Job? {
        if (installationOpen || request?.isCompleted == false || current.value.stage !in listOf(UpdateStage.AVAILABLE, UpdateStage.ERROR)) return request
        val release = current.value.release?.takeIf { it.count > installedCode } ?: return null
        val ticket = ++generation
        current.update { it.copy(stage = UpdateStage.DOWNLOADING, downloadedBytes = 0, error = null) }
        request = scope.launch {
            val temporary = File(directory, "${UUID.randomUUID()}.part")
            val candidate = File(directory, "update.apk")
            var promoted = false
            try {
                withContext(io) {
                    check(directory.isDirectory || directory.mkdirs())
                    directory.listFiles()?.filter { it.extension == "part" && it != temporary }?.forEach { it.delete() }
                    // Cached bytes survive process death, but must pass all checks again before use.
                    val cached = if (candidate.isFile) {
                        try { verify(candidate, release); true }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { check(candidate.delete()); false }
                    } else false
                    if (!cached) {
                        download(release, temporary) { received, _ ->
                            current.update { if (ticket == generation) it.copy(downloadedBytes = received.coerceIn(0, release.bytes)) else it }
                        }
                        currentCoroutineContext().ensureActive()
                        current.update { if (ticket == generation) it.copy(stage = UpdateStage.VERIFYING) else it }
                        check(!candidate.exists())
                        check(temporary.renameTo(candidate))
                        promoted = true
                        verify(candidate, release)
                    }
                    currentCoroutineContext().ensureActive()
                }
                if (ticket == generation) current.update { it.copy(stage = UpdateStage.READY, downloadedBytes = release.bytes, error = null) }
            } catch (cancelled: CancellationException) {
                if (promoted) candidate.delete()
                throw cancelled
            } catch (_: Exception) {
                if (promoted) candidate.delete()
                if (ticket == generation) current.update { it.copy(stage = UpdateStage.ERROR, downloadedBytes = 0,
                    error = "The update could not be downloaded or verified. Nothing was installed. Try again.") }
            } finally {
                temporary.delete()
                if (ticket == generation && current.value.stage in listOf(UpdateStage.DOWNLOADING, UpdateStage.VERIFYING)) {
                    current.update { it.copy(stage = UpdateStage.AVAILABLE, downloadedBytes = 0) }
                }
            }
        }
        return request
    }

    fun cancelDownload() {
        if (current.value.stage != UpdateStage.DOWNLOADING) return
        ++generation
        request?.cancel()
        current.update { it.copy(stage = UpdateStage.AVAILABLE, downloadedBytes = 0, error = null) }
    }

    /** Freezes all replacement/cleanup until Android's installer has returned to the activity. */
    fun beginInstall(): Pair<File, UpdateRelease>? {
        if (installationOpen || request?.isCompleted == false || current.value.stage != UpdateStage.READY) return null
        val release = current.value.release ?: return null
        installationOpen = true
        return File(directory, "update.apk") to release
    }

    fun installerReturned() { installationOpen = false }
    fun permissionRequired(required: Boolean) { current.update { it.copy(installPermissionRequired = required) } }
    fun installFailed() {
        installationOpen = false
        current.update { it.copy(stage = UpdateStage.ERROR,
            error = "Unable to open the installer. Recheck the download and try again.") }
    }
    private fun retryDelay(): Long {
        if (current.value.checkFailure in listOf(UpdateFailureKind.RATE_LIMITED, UpdateFailureKind.INVALID_METADATA)) return 15 * RETRY_MS
        return (RETRY_MS * (1L shl (current.value.consecutiveCheckFailures.coerceIn(1, 5) - 1)))
            .coerceAtMost(15 * RETRY_MS)
    }

    private suspend fun save(snapshot: UpdateUiState, attemptAt: Long) = withContext(io) {
        persist(snapshot, attemptAt)
    }

    private suspend fun finishCheck(result: UpdateUiState, attemptAt: Long) {
        // Keep the check reserved and visible as Checking until its final snapshot is saved.
        save(result, attemptAt)
        current.update { result.copy(installPermissionRequired = it.installPermissionRequired) }
    }

    private enum class CheckTrigger { MANUAL, LAUNCH, RECOVERY }

    private companion object {
        const val DAY_MS = 86_400_000L
        const val RETRY_MS = 60_000L
    }
}
