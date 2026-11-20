package com.everyonesoftware.audio.updates

import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
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
    private val loadChangelog: suspend (Long, UpdateRelease) -> UpdateChangelog = { _, _ -> error("History unavailable") },
) {
    private val initialRelease = initialState.release?.takeIf { it.count > installedCode }
    private val unfinishedCheck = initialState.checkOutcome in listOf(UpdateCheckOutcome.CHECKING, UpdateCheckOutcome.FAILED) ||
        (initialState.checkOutcome == UpdateCheckOutcome.NEVER && initialLastAttemptAt != null &&
            (initialState.lastCheckedAt == null || initialLastAttemptAt > initialState.lastCheckedAt))
    private val current = MutableStateFlow(initialState.copy(release = initialRelease,
        stage = when { unfinishedCheck -> UpdateStage.ERROR; initialRelease != null -> UpdateStage.AVAILABLE; else -> UpdateStage.IDLE },
        checkOutcome = if (unfinishedCheck) UpdateCheckOutcome.FAILED else initialState.checkOutcome,
        checkFailure = if (unfinishedCheck) initialState.checkFailure ?: UpdateFailureKind.TRANSIENT else null,
        error = if (unfinishedCheck) (initialState.checkFailure ?: UpdateFailureKind.TRANSIENT).checkMessage else null))
    val state = current.asStateFlow()
    private var request: Job? = null
    private var automaticRequest = false
    private var lastAttemptAt = initialLastAttemptAt
    @Volatile private var generation = 0L
    private var installationOpen = false
    private var changelogRequest: Job? = null
    private var changelogGeneration = 0L

    /** Independent request: missing history never invalidates a verified update or blocks download. */
    fun retryChangelog(): Job? {
        val release = current.value.release ?: return null
        if (changelogRequest?.isActive == true && current.value.changelogStage == ChangelogStage.LOADING) return changelogRequest
        return startChangelog(release)
    }

    private fun startChangelog(release: UpdateRelease?): Job? {
        val ticket = ++changelogGeneration
        changelogRequest?.cancel()
        current.update { it.copy(changelog = null, changelogStage = if (release == null) ChangelogStage.IDLE else ChangelogStage.LOADING) }
        if (release == null) return null
        // Assign before starting: Main.immediate may complete the request inline.
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                val history = loadChangelog(installedCode, release)
                if (ticket == changelogGeneration && current.value.release == release) {
                    current.update { it.copy(changelog = history, changelogStage = ChangelogStage.AVAILABLE) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (ticket == changelogGeneration && current.value.release == release) {
                    current.update { it.copy(changelog = null, changelogStage = ChangelogStage.UNAVAILABLE) }
                }
            }
        }
        changelogRequest = job
        job.start()
        return job
    }

    fun check(automatic: Boolean = false): Job? {
        if (installationOpen || request?.isCompleted == false || current.value.stage in listOf(UpdateStage.DOWNLOADING, UpdateStage.VERIFYING)) return request
        val time = now()
        val failed = current.value.checkOutcome == UpdateCheckOutcome.FAILED
        val interval = if (failed) retryDelay() else DAY_MS
        val serverCooldown = failed && current.value.checkRetryAt?.let { it > time && it - time <= DAY_MS } == true
        if (automatic && (!current.value.automaticChecks || serverCooldown ||
                lastAttemptAt?.let { time >= it && time - it < interval } == true)) return null
        val ticket = ++generation
        val before = current.value
        lastAttemptAt = time
        automaticRequest = automatic
        current.update { it.copy(stage = UpdateStage.CHECKING, error = null, checkOutcome = UpdateCheckOutcome.CHECKING) }
        save()
        request = scope.launch {
            try {
                val found = latest(installedCode)?.takeIf { it.count > installedCode }
                if (ticket == generation) {
                    current.update { it.copy(release = found, lastCheckedAt = now(), error = null,
                        checkOutcome = UpdateCheckOutcome.SUCCESS, checkFailure = null, consecutiveCheckFailures = 0, checkRetryAt = null,
                        downloadedBytes = if (found == before.release && before.stage == UpdateStage.READY) before.downloadedBytes else 0,
                        stage = when { found == null -> UpdateStage.IDLE; found == before.release && before.stage == UpdateStage.READY -> UpdateStage.READY; else -> UpdateStage.AVAILABLE }) }
                    if (found != before.release) startChangelog(null)
                    save()
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
                    current.update { it.copy(stage = if (before.stage == UpdateStage.READY) UpdateStage.READY else UpdateStage.ERROR,
                        consecutiveCheckFailures = before.consecutiveCheckFailures.coerceIn(0, 4) + 1,
                        checkOutcome = UpdateCheckOutcome.FAILED, checkFailure = kind,
                        checkRetryAt = retryAt,
                        error = kind.checkMessage) }
                    save()
                }
            } finally {
                if (ticket == generation && current.value.stage == UpdateStage.CHECKING) current.value = before.copy(automaticChecks = current.value.automaticChecks)
            }
        }
        return request
    }

    fun setAutomaticChecks(enabled: Boolean) {
        current.update { it.copy(automaticChecks = enabled) }
        if (!enabled && automaticRequest && current.value.stage == UpdateStage.CHECKING) {
            ++generation
            request?.cancel()
            current.update { it.copy(stage = if (it.release != null) UpdateStage.AVAILABLE else UpdateStage.IDLE) }
        }
        save()
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

    private fun save() { persist(current.value, lastAttemptAt) }

    private companion object {
        const val DAY_MS = 86_400_000L
        const val RETRY_MS = 60_000L
    }
}
