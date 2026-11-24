package com.everyonesoftware.audio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.everyonesoftware.audio.data.OfflineCollection
import com.everyonesoftware.audio.data.Song
import com.everyonesoftware.audio.data.SongCacheState

internal fun SongCacheState.hasLocalAudio(id: String) = id in downloadedSongs || id in cachedSongs

internal fun SongCacheState.isSaved(collection: OfflineCollection) =
    collection.songs.isNotEmpty() && collection.songs.all { it.id in downloadedSongs }

private fun OfflineCollection.matchesOccurrences(songs: List<Song>) =
    this.songs.size == songs.size && this.songs.indices.all { this.songs[it].id == songs[it].id }

internal fun SongCacheState.isSavedRevision(kind: String, id: String, confirmedSongs: List<Song>?) =
    confirmedSongs != null && collections.any {
        it.kind == kind && it.id == id && it.matchesOccurrences(confirmedSongs) && isSaved(it)
    }

@Composable
internal fun SavedTitle(title: String, saved: Boolean, style: TextStyle, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, Modifier.weight(1f, fill = false), style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        if (saved) Icon(Icons.Default.Check, "Saved to Device",
            Modifier.size(16.dp).background(MaterialTheme.colorScheme.primary, CircleShape).padding(2.dp),
            tint = MaterialTheme.colorScheme.onPrimary)
    }
}

@Composable
internal fun DeviceMenu(
    accountId: String?, itemKey: String, title: String, removable: Boolean,
    canSave: Boolean, canRemove: Boolean, onSave: () -> Unit, onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    onAddToPlaylist: (() -> Unit)? = null,
) {
    var expanded by remember(accountId, itemKey, removable) { mutableStateOf(false) }
    val enabled = accountId != null && (canSave || (removable && canRemove) || onAddToPlaylist != null)
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    Box {
        IconButton({ expanded = true }, modifier.size(48.dp), enabled = enabled) {
            Icon(Icons.Default.MoreHoriz, "Device actions for $title")
        }
        DropdownMenu(expanded && enabled, onDismissRequest = { expanded = false }) {
            if (onAddToPlaylist != null) DropdownMenuItem(text = { Text("Add to Playlist") }, onClick = {
                expanded = false
                onAddToPlaylist()
            })
            if (canSave) DropdownMenuItem(text = { Text("Save to Device") }, onClick = {
                expanded = false
                onSave()
            })
            if (removable && canRemove) DropdownMenuItem(text = { Text("Remove from Device") }, onClick = {
                expanded = false
                onRemove()
            })
        }
    }
}

@Composable
internal fun SongDeviceMenu(state: AudioUiState, actions: AudioUiActions, song: Song, modifier: Modifier = Modifier) {
    val storage = state.storage
    val explicit = song.id in storage.explicitDownloads
    // A collection can retain these bytes independently of the song's own pin.
    DeviceMenu(state.connection?.id, song.id, song.title, explicit,
        canSave = !explicit && !state.loading && (!storage.settings.offline || storage.hasLocalAudio(song.id)),
        canRemove = !state.loading, onSave = { actions.downloadSongs(listOf(song)) },
        onRemove = { actions.removeDownload(song.id) }, modifier = modifier,
        onAddToPlaylist = playlistAction(state, actions, PlaylistSourceKind.SONG, song.id, song.title))
}

@Composable
internal fun CollectionDeviceMenu(
    state: AudioUiState, actions: AudioUiActions, kind: String, id: String, name: String,
    confirmedSongs: List<Song>?, canSave: Boolean, onSave: () -> Unit, modifier: Modifier = Modifier,
) {
    val retained = state.storage.collections.firstOrNull { it.kind == kind && it.id == id }
    val canSaveRevision = canSave && confirmedSongs != null && retained?.matchesOccurrences(confirmedSongs) != true
    DeviceMenu(state.connection?.id, "$kind:$id", name, retained != null, canSaveRevision, !state.loading,
        onSave, { actions.removeOfflineCollection(kind, id) }, modifier,
        playlistAction(state, actions, if (kind == "playlist") PlaylistSourceKind.PLAYLIST else PlaylistSourceKind.ALBUM, id, name))
}

private fun playlistAction(state: AudioUiState, actions: AudioUiActions, kind: PlaylistSourceKind, id: String, title: String): (() -> Unit)? {
    val source = PlaylistSource(kind, id, title)
    return if (state.canEditPlaylists && state.containsPlaylistSource(source)) {
        { actions.choosePlaylist(state.deviceActionPage, source) }
    } else null
}

@Composable
internal fun CollectionPlaylistMenu(state: AudioUiState, actions: AudioUiActions, kind: PlaylistSourceKind,
    id: String, title: String, modifier: Modifier = Modifier, overArtwork: Boolean = false) {
    val action = playlistAction(state, actions, kind, id, title) ?: return
    var expanded by remember(state.deviceActionPage, kind, id) { mutableStateOf(false) }
    Box(modifier) {
        IconButton({ expanded = true }, Modifier.size(48.dp).then(
            if (overArtwork) Modifier.padding(4.dp).background(MaterialTheme.colorScheme.surface, CircleShape) else Modifier)) {
            Icon(Icons.Default.MoreHoriz, "More actions for $title")
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Add to Playlist") }, onClick = { expanded = false; action() })
        }
    }
}

internal fun SongCacheState.isSaved(kind: String, id: String) =
    collections.any { it.kind == kind && it.id == id && isSaved(it) }
