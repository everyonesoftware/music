package com.everyonesoftware.audio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun PlaylistDeletionDialog(deletion: PlaylistDeletion, online: Boolean, actions: AudioUiActions) {
    val cancelFocus = remember(deletion.ticket) { FocusRequester() }
    AlertDialog(
        onDismissRequest = actions::dismissPlaylistDeletion,
        modifier = Modifier.onPreviewKeyEvent {
            if (it.key == Key.Escape) {
                if (it.type == KeyEventType.KeyUp) actions.dismissPlaylistDeletion()
                true
            } else false
        },
        properties = DialogProperties(dismissOnBackPress = !deletion.busy, dismissOnClickOutside = !deletion.busy),
        title = { Text(if (deletion.unconfirmed) "Check deletion of “${deletion.name}”" else "Delete “${deletion.name}”?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (deletion.unconfirmed) {
                    Text("Check whether this playlist is still on your music server. This won’t send another delete.")
                } else {
                    Text("This permanently removes the playlist and its song list from your music server. The songs themselves stay in your library.")
                    Text("This can’t be undone.")
                }
                deletion.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = {
            TextButton(actions::dismissPlaylistDeletion, Modifier.focusRequester(cancelFocus).testTag("playlist-delete-cancel"),
                enabled = !deletion.busy) { Text(if (deletion.unconfirmed) "Close" else "Cancel") }
            LaunchedEffect(deletion.busy) { if (!deletion.busy) cancelFocus.requestFocus() }
        },
        confirmButton = {
            if (deletion.unconfirmed) {
                TextButton(actions::confirmPlaylistDeletion, Modifier.testTag("playlist-delete-check"),
                    enabled = online && !deletion.busy) { Text(if (deletion.busy) "Checking…" else "Check again") }
            } else {
                Button(actions::confirmPlaylistDeletion, Modifier.testTag("playlist-delete-confirm"), enabled = online && !deletion.busy,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError)) { Text(if (deletion.busy) "Deleting…" else "Delete playlist") }
            }
        },
    )
}
