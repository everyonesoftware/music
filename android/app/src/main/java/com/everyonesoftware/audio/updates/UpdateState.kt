package com.everyonesoftware.audio.updates

import com.everyonesoftware.audio.BuildConfig

enum class UpdateStage { IDLE, CHECKING, AVAILABLE, DOWNLOADING, VERIFYING, READY, ERROR }

enum class ChangelogStage { IDLE, LOADING, AVAILABLE, UNAVAILABLE }

enum class UpdateCheckOutcome { NEVER, CHECKING, SUCCESS, FAILED }

enum class UpdateFailureKind(val checkMessage: String) {
    TRANSIENT("Unable to check for updates. Check your connection and try again."),
    RATE_LIMITED("Update checks are temporarily rate limited. Please try again shortly."),
    INVALID_METADATA("The update information could not be validated. Please try again later."),
}

data class UpdateUiState(
    val stage: UpdateStage = UpdateStage.IDLE,
    val lastCheckedAt: Long? = null,
    val release: UpdateRelease? = null,
    val downloadedBytes: Long = 0,
    val error: String? = null,
    val installedVersion: String = BuildConfig.VERSION_NAME,
    val installPermissionRequired: Boolean = false,
    val checkOutcome: UpdateCheckOutcome = UpdateCheckOutcome.NEVER,
    val checkFailure: UpdateFailureKind? = null,
    val consecutiveCheckFailures: Int = 0,
    val checkRetryAt: Long? = null,
    val changelogStage: ChangelogStage = ChangelogStage.IDLE,
    val changelog: UpdateChangelog? = null,
)
