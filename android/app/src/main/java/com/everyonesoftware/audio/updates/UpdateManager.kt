package com.everyonesoftware.audio.updates

import android.content.Context
import com.everyonesoftware.audio.BuildConfig
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** App-private settings/cache only. No credentials, server client or media URLs enter this subsystem. */
class UpdateManager(context: Context, scope: CoroutineScope, private val client: UpdateClient = UpdateClient()) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("music_updates", Context.MODE_PRIVATE)
    private val installer = UpdateInstaller(app)

    private val directory = File(app.cacheDir, "updates")
    private val installedCode = BuildConfig.VERSION_CODE.toLong()
    private val restored = readRelease()?.takeIf { it.count > installedCode }
    val coordinator = UpdateCoordinator(scope, installedCode, directory,
        latest = client::latest, download = client::download,
        verify = { file, release -> installer.verify(file, release) },
        initialState = UpdateUiState(
            lastCheckedAt = preferences.getLong("checked_at", 0L).takeIf { it > 0 }, release = restored,
            installPermissionRequired = !installer.canInstall(),
            checkOutcome = UpdateCheckOutcome.entries.firstOrNull { it.name == preferences.getString("check_outcome", null) }
                ?: UpdateCheckOutcome.NEVER,
            consecutiveCheckFailures = preferences.getInt("check_failures", 0).coerceIn(0, 5),
            checkRetryAt = preferences.getLong("check_retry_at", 0L).takeIf { it > 0 },
            checkFailure = UpdateFailureKind.entries.firstOrNull { it.name == preferences.getString("check_failure", null) }),
        initialLastAttemptAt = preferences.getLong("attempt_at", 0L).takeIf { it > 0 },
        persist = { state, lastAttempt ->
            preferences.edit().remove("automatic")
                .putLong("checked_at", state.lastCheckedAt ?: 0L).putLong("attempt_at", lastAttempt ?: 0L)
                .putString("check_outcome", state.checkOutcome.name)
                .putInt("check_failures", state.consecutiveCheckFailures.coerceIn(0, 5))
                .putLong("check_retry_at", state.checkRetryAt ?: 0L)
                .putString("check_failure", state.checkFailure?.name)
                .putString("release", state.release?.let(::encodeRelease)).apply()
        })
    val state = coordinator.state


    /** No automatic installer launch on return from permission settings. A fresh tap is required. */
    fun onForeground() {
        coordinator.installerReturned()
        coordinator.permissionRequired(!installer.canInstall())
    }

    fun permissionIntent() = installer.permissionIntent()
    fun canInstall() = installer.canInstall()

    suspend fun verifiedInstallIntent(file: File, release: UpdateRelease) = withContext(Dispatchers.IO) {
        installer.verify(file, release)
        installer.installIntent(file)
    }

    private fun encodeRelease(release: UpdateRelease): String = buildJsonObject {
        put("count", release.count); put("version", release.version); put("apk", release.apk)
        put("bytes", release.bytes); put("sha256", release.sha256)
        put("downloadUrl", release.downloadUrl); put("sourceSha", release.sourceSha)
        release.changelog?.let { put("changelog", it) }
    }.toString()

    private fun readRelease(): UpdateRelease? = try {
        val raw = preferences.getString("release", null)
        if (raw == null || raw.length > 1024 * 1024 + 4096 || raw.toByteArray(Charsets.UTF_8).size > 1024 * 1024 + 4096) null else {
            val item = Json.parseToJsonElement(raw).jsonObject
            val release = UpdateRelease(item.getValue("count").jsonPrimitive.int,
                item.getValue("version").jsonPrimitive.content, item.getValue("apk").jsonPrimitive.content,
                item.getValue("bytes").jsonPrimitive.long, item.getValue("sha256").jsonPrimitive.content,
                item.getValue("downloadUrl").jsonPrimitive.content,
                sourceSha = item["sourceSha"]?.jsonPrimitive?.content.orEmpty(),
                changelog = item["changelog"] as? kotlinx.serialization.json.JsonObject)
            require(release.count in 1..2_100_000_000 && release.version == "0.1.${release.count}")
            require(release.apk == "Music-${release.version}.apk" && release.bytes in 1..157_286_400L)
            require(Regex("[0-9a-f]{64}").matches(release.sha256))
            require(release.sourceSha.isEmpty() || Regex("[0-9a-f]{40}").matches(release.sourceSha))
            require(release.downloadUrl == "https://github.com/everyonesoftware/music/releases/download/v${release.version}/${release.apk}")
            release
        }
    } catch (_: Exception) { null }
}
