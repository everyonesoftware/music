package com.everyonesoftware.audio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.everyonesoftware.audio.updates.UpdateStage
import com.everyonesoftware.audio.updates.ChangelogStage
import com.everyonesoftware.audio.updates.UpdateUiState
import java.util.Locale

/** A quiet header affordance, never a banner over the music library. */
@Composable
internal fun UpdateNotificationIcon(onClick: () -> Unit) {
    IconButton(onClick, Modifier.sizeIn(minWidth = 48.dp, minHeight = 52.dp)) {
        Box(Modifier.size(28.dp)) {
            Icon(Icons.Default.Download, "Update available", Modifier.size(24.dp).align(Alignment.Center))
            Box(Modifier.size(7.dp).align(Alignment.TopEnd).background(MaterialTheme.colorScheme.primary, CircleShape))
        }
    }
}

/** Presentation only: checking/downloading never issues a player command. */
@Composable
internal fun Updates(state: AudioUiState, actions: AudioUiActions) {
    val update = state.updates
    val release = update.release
    val busy = update.stage in listOf(UpdateStage.CHECKING, UpdateStage.DOWNLOADING, UpdateStage.VERIFYING)
    // A resumed player or changed release invalidates any previous install consent.
    var confirmInstall by rememberSaveable(release?.count, release?.sha256, release?.sourceSha, update.stage, state.playing, update.installPermissionRequired) { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ actions.showScreen(state.updatesReturnScreen) }, Modifier.sizeIn(minWidth = 48.dp, minHeight = 52.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
            Column(Modifier.weight(1f)) {
                Text("Settings", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Updates", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            }
            SettingsGear()
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Current Version: ${update.installedVersion}", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.labelLarge)
                OutlinedButton(actions::checkForUpdates, Modifier.heightIn(min = 48.dp).testTag("updates-check"), enabled = !busy, shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 13.dp, vertical = 10.dp)) {
                    Text(if (update.stage == UpdateStage.CHECKING) "Checking…" else "Check now", style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (update.stage == UpdateStage.IDLE && update.lastCheckedAt == null && release == null) {
                UpdateHint("Not checked yet")
            }
            if (update.stage == UpdateStage.CHECKING) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                UpdateHint("You can keep listening while Music checks.")
            }
            if (update.stage == UpdateStage.IDLE && update.lastCheckedAt != null && release == null && update.error == null) {
                Surface(
                    modifier = Modifier.fillMaxWidth().testTag("updates-current").semantics { liveRegion = LiveRegionMode.Polite },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 21.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Text("Up to date", style = MaterialTheme.typography.titleLarge)
                        }
                        UpdateHint("No newer Android version is available.")
                    }
                }
            }
            if (release != null) {
                UpdateOffer(update)
            }
            if (update.error != null) {
                Text(update.error, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            when (update.stage) {
                UpdateStage.AVAILABLE, UpdateStage.ERROR -> if (release != null) {
                    UpdateButton(if (update.stage == UpdateStage.ERROR) "Retry download" else "Download update", "updates-download", actions::downloadUpdate)
                }
                UpdateStage.DOWNLOADING -> {
                    val total = release?.bytes ?: 0L
                    val downloaded = update.downloadedBytes.coerceAtLeast(0L).let { if (total > 0) it.coerceAtMost(total) else it }
                    if (total > 0) {
                        val fraction = (downloaded.toDouble() / total).toFloat().coerceIn(0f, 1f)
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().testTag("updates-progress"))
                        Text("${updateSize(downloaded)} of ${updateSize(total)} · ${(fraction * 100).toInt()}%", Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Downloading update…")
                    }
                    OutlinedButton(actions::cancelUpdateDownload, Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("updates-cancel"), shape = RoundedCornerShape(12.dp)) { Text("Cancel download") }
                }
                UpdateStage.VERIFYING -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Verifying download…", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                UpdateStage.READY -> if (release != null) {
                    if (state.playing) {
                        UpdateHint("Pause playback before continuing. The update will not pause your music automatically.")
                        UpdateButton("Pause playback", "updates-pause", actions::pauseForUpdate)
                    } else {
                        if (update.installPermissionRequired) UpdateHint("Android needs your permission for Music to install updates. Allow this in system settings, return here, then tap Install update again.")
                        UpdateButton(if (update.installPermissionRequired) "Allow installations" else "Install update", "updates-install") { confirmInstall = true }
                    }
                }
                else -> Unit
            }
        }
    }
    if (confirmInstall && update.stage == UpdateStage.READY && !state.playing && release != null) {
        AlertDialog(
            onDismissRequest = { confirmInstall = false },
            title = { Text(if (update.installPermissionRequired) "Allow installations?" else "Install Music ${release.version}?") },
            text = { Text(if (update.installPermissionRequired)
                "Android settings will open so you can allow Music to install updates. Return here afterward and tap Install update again. This does not install the update."
            else "Music will close during installation. Your queue and position are preserved, and playback stays paused. Android will ask you to confirm the update.") },
            confirmButton = {
                TextButton({
                    confirmInstall = false
                    // Recheck the latest state; consent cannot bypass resumed playback.
                    if (!state.playing && update.stage == UpdateStage.READY) actions.installUpdate()
                }, Modifier.testTag("updates-confirm-install")) { Text(if (update.installPermissionRequired) "Open Android settings" else "Continue to Android") }
            },
            dismissButton = { TextButton({ confirmInstall = false }) { Text("Not now") } },
        )
    }
}

/** The release body is download inventory, never changelog evidence. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UpdateOffer(update: UpdateUiState) {
    val release = update.release ?: return
    // Do not reset disclosures on progress, retry or changelog-stage transitions.
    var expanded by rememberSaveable(release.count, release.sha256, release.sourceSha, release.downloadUrl) { mutableStateOf(false) }
    var commitsExpanded by rememberSaveable(release.count, release.sha256, release.sourceSha, release.downloadUrl) { mutableStateOf(false) }
    var browserUnavailable by remember(release.count, release.sha256, release.sourceSha, release.downloadUrl) { mutableStateOf(false) }
    val onBrowserResult: (Boolean) -> Unit = { opened -> browserUnavailable = !opened }
    val changelog = update.changelog.takeIf { update.changelogStage == ChangelogStage.AVAILABLE }
    Column(Modifier.fillMaxWidth().testTag("updates-offer"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Update available", Modifier.padding(end = 12.dp).testTag("updates-available-label"), style = MaterialTheme.typography.titleMedium)
            Text("Music ${release.version}", Modifier.testTag("updates-offered-version"), style = MaterialTheme.typography.titleMedium)
        }
        val detail = changelog?.let { "${it.count} ${if (it.count == 1) "change" else "changes"}" } ?: "Unavailable"
        UpdateDisclosure("What’s new", "updates-changelog", expanded, detail = detail) { expanded = !expanded }
        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (changelog != null) {
                    UpdateChangeGroup("Improvements", changelog.improvements)
                    UpdateChangeGroup("Fixes", changelog.fixes)
                    UpdateDisclosure("Commit details", "updates-commit-details", commitsExpanded) { commitsExpanded = !commitsExpanded }
                    if (commitsExpanded) {
                        if (browserUnavailable) {
                            Text("Could not open browser. Try again after enabling a browser.",
                                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        }
                        CommitLink("${changelog.fromSha.take(7)}…${changelog.toSha.take(7)}",
                            "https://github.com/everyonesoftware/music/compare/${changelog.fromSha}...${changelog.toSha}",
                            "Compare source range on GitHub (opens browser)", onBrowserResult, Modifier.testTag("updates-source-range"))
                        changelog.commits.forEach { commit ->
                            // Keep the original subject intact; stack rather than squeeze it at large type.
                            val link: @Composable () -> Unit = {
                                CommitLink(commit.sha.take(7), "https://github.com/everyonesoftware/music/commit/${commit.sha}",
                                    "View commit ${commit.sha} on GitHub (opens browser)", onBrowserResult)
                            }
                            if (LocalDensity.current.fontScale >= 1.5f) {
                                Column { link(); Text(commit.subject, style = MaterialTheme.typography.bodyMedium) }
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                    link()
                                    Text(commit.subject, Modifier.weight(1f).padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                } else ChangelogUnavailable()
            }
        }
    }
}

@Composable
private fun CommitLink(label: String, url: String, description: String, onOpenResult: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    val fill = if (dark) Color(0xFF2A312C) else colors.surfaceContainerHigh
    val edge = if (dark) Color(0xFF545E56) else colors.outlineVariant
    val ink = if (dark) Color(0xFFBBD8C1) else colors.primary
    val shape = RoundedCornerShape(4.dp)
    // The compact visual box has a full native 48dp focus/keyboard/touch target.
    Box(modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
        .border(2.dp, if (focused) ink else Color.Transparent, shape)
        .semantics { contentDescription = description }
        .clickable(interactionSource = interactions, indication = LocalIndication.current,
            role = Role.Button, onClickLabel = "Open in browser") {
                val opened = try {
                    uriHandler.openUri(url)
                    true
                } catch (_: IllegalArgumentException) {
                    // AndroidUriHandler wraps an unavailable activity; never expose its URI-bearing message.
                    false
                }
                onOpenResult(opened)
            }
        .padding(3.dp),
        contentAlignment = Alignment.Center) {
        Text(label, Modifier.background(fill, shape).border(1.dp, edge, shape).padding(horizontal = 6.dp, vertical = 1.dp),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 20.sp), color = ink)
    }
}

@Composable
private fun ChangelogUnavailable() {
    UpdateHint("Changelog unavailable. You can still download this update.")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UpdateDisclosure(label: String, tag: String, expanded: Boolean, detail: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)
        .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
        .clickable(onClickLabel = if (expanded) "Collapse $label" else "Expand $label", onClick = onClick),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(if (expanded) Icons.Default.ExpandMore else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null, modifier = Modifier.size(24.dp))
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, Modifier.padding(end = 12.dp), style = MaterialTheme.typography.titleMedium)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun UpdateChangeGroup(title: String, changes: List<String>) {
    if (changes.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        changes.forEach { change ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("•", style = MaterialTheme.typography.bodyMedium)
                Text(change, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun UpdateHint(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun UpdateButton(label: String, tag: String, onClick: () -> Unit) {
    Button(onClick, Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(tag), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) { Text(label) }
}

private fun updateSize(bytes: Long): String = String.format(Locale.ROOT, "%.1f MB", bytes.coerceAtLeast(0L).toDouble() / 1_000_000.0)
