package com.everyonesoftware.audio.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.everyonesoftware.audio.data.NavidromeClient

@Composable
internal fun NewPlaylistButton(state: AudioUiState, actions: AudioUiActions) {
    FilledTonalButton(actions::newPlaylist, Modifier.heightIn(min = 48.dp).testTag("new-playlist"), enabled = state.canEditPlaylists) {
        Icon(Icons.Default.Add, null)
        Spacer(Modifier.width(6.dp))
        Text("New playlist")
    }
}

@Composable
internal fun PlaylistEditorDialog(editor: PlaylistEditor, canEdit: Boolean, actions: AudioUiActions) {
    var new by rememberSaveable(editor.ticket) { mutableStateOf(editor.creating) }
    var name by rememberSaveable(editor.ticket) { mutableStateOf("") }
    val creating = new || editor.creating
    val songs = editor.songs
    val enabled = canEdit && !editor.busy && !editor.unconfirmed && songs != null
    AlertDialog(
        onDismissRequest = actions::dismissPlaylistEditor,
        title = { Text(if (creating) "New playlist" else "Add to Playlist") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                editor.source?.let { Text(it.title, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                if (songs != null) {
                    val summary = when {
                        songs.isEmpty() -> "Starts empty. Find songs in Library to add later."
                        songs.size == 1 -> "1 song · ${songs.first().title}"
                        else -> "${songs.size} songs"
                    }
                    Text(summary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("playlist-seed-summary"))
                }
                if (editor.busy) LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "Working on playlist" })
                if (!canEdit) Text("Connect and turn off offline mode to change playlists.")
                editor.error?.let {
                    Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error)
                }
                if (creating) {
                    OutlinedTextField(name, { if (it.length <= NavidromeClient.MAX_PLAYLIST_NAME) name = it },
                        Modifier.fillMaxWidth().testTag("playlist-name"), label = { Text("Playlist name") },
                        placeholder = { Text("e.g. Sunday drive") }, singleLine = true, enabled = enabled,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))
                } else if (songs != null && !editor.unconfirmed) {
                    TextButton({ new = true }, enabled = enabled) { Icon(Icons.Default.Add, null); Text("New playlist") }
                    if (editor.destinations.isEmpty() && editor.error == null) Text("No writable playlists yet.")
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 208.dp).testTag("playlist-destinations")) {
                        items(editor.destinations, key = { it.id }) { playlist ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("playlist-destination-${playlist.id}")
                                .clickable(enabled = enabled, role = Role.Button) { actions.appendToPlaylist(playlist.id) },
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(playlist.name, Modifier.padding(8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (creating && !editor.unconfirmed) Button({ actions.createPlaylist(name) },
                enabled = enabled && name.isNotBlank() && name.none { it.isISOControl() }) { Text("Create") }
        },
        dismissButton = {
            TextButton(actions::dismissPlaylistEditor, enabled = !editor.busy || songs == null) {
                Text(if (editor.unconfirmed) "Close" else "Cancel")
            }
        },
    )
}
