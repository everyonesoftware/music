package com.everyonesoftware.audio.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.everyonesoftware.audio.playback.output.MediaOutputState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaOutputSheet(state: MediaOutputState, actions: AudioUiActions, onClose: () -> Unit) {
    val context = LocalContext.current
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val maximumHeight = with(LocalDensity.current) { windowHeight.toDp() * 0.8f }
    var requestedId by rememberSaveable { mutableStateOf<String?>(null) }
    var permissionAttempted by rememberSaveable { mutableStateOf(false) }
    var settingsError by remember { mutableStateOf<String?>(null) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        actions.refreshMediaOutputs()
    }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        actions.refreshMediaOutputs()
    }
    fun openSettings(intent: Intent) {
        try { settings.launch(intent); settingsError = null }
        catch (_: ActivityNotFoundException) { settingsError = "Android settings are unavailable on this device." }
        catch (_: SecurityException) { settingsError = "Android did not allow opening device settings." }
    }
    // Only close after Android confirms the route. A rejected transfer stays visible for retry.
    LaunchedEffect(state.selectedId, state.pendingId, state.error) {
        if (requestedId != null && state.selectedId == requestedId && state.pendingId == null) onClose()
        else if (state.error != null) requestedId = null
    }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().testTag("output-sheet-content").heightIn(max = maximumHeight)
            .verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Media output", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
                IconButton(onClose, Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("output-close")) {
                    Icon(Icons.Default.Close, "Close media output")
                }
            }
            Text("Choose where to play your music.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.permissionRequired) {
                Text("Allow Nearby devices to show and switch connected audio outputs.", style = MaterialTheme.typography.bodyMedium)
                Button({
                    permissionAttempted = true
                    if (Build.VERSION.SDK_INT >= 34) permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN))
                }, Modifier.testTag("output-permission")) { Text("Allow device access") }
                if (permissionAttempted) TextButton({
                    openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
                }) { Text("App permissions") }
            }
            if (state.systemPickerRequired) {
                Text("Android manages output switching on this Android version. Use Android’s audio controls or device settings to change outputs.", style = MaterialTheme.typography.bodyMedium)
            }
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.devices.forEach { device ->
                    val selected = device.id == state.selectedId
                    val enabled = state.pendingId == null && (selected || (!state.permissionRequired && !state.systemPickerRequired))
                    Surface(shape = RoundedCornerShape(14.dp), color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).testTag("output-route-${device.id}")
                            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = {
                                if (selected) onClose() else { requestedId = device.id; actions.selectMediaOutput(device.id) }
                            }).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(outputDeviceIcon(device.kind), null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(Modifier.weight(1f)) {
                                Text(device.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(if (state.pendingId == device.id) "Connecting…" else device.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (state.pendingId == device.id) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            else RadioButton(selected = selected, onClick = null)
                        }
                    }
                }
            }
            if (state.devices.isEmpty() && !state.permissionRequired) Text("No audio outputs reported by Android. Check device settings and try again.", style = MaterialTheme.typography.bodyMedium)
            if (state.pendingId != null) Text("Switching audio output…", Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
            (state.error ?: settingsError)?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton({ openSettings(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) { Text("Device settings") }
                TextButton(actions::refreshMediaOutputs, enabled = state.pendingId == null, modifier = Modifier.testTag("output-refresh")) { Text("Refresh") }
            }
        }
    }
}
