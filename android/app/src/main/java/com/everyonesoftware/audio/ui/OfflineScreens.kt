package com.everyonesoftware.audio.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.everyonesoftware.audio.data.SongCacheState

@Composable
private fun OfflineCollectionDetails(state: AudioUiState, actions: AudioUiActions,
    collection: com.everyonesoftware.audio.data.OfflineCollection, artworkUrl: (String?) -> String?) {
    val complete = state.storage.cachedSongs + state.storage.downloadedSongs
    val count = collection.availableCount { it in complete }
    val available = count == collection.songs.size && count > 0
    val cover = artworkUrl(collection.coverArtId)
    Column(Modifier.fillMaxSize().testTag("offline-collection-detail")) {
        TextButton({ actions.showScreen(Screen.OFFLINE) }) { Text("Back to saved music") }
        coil.compose.AsyncImage(model = cover, contentDescription = "${collection.name} artwork", modifier = Modifier.size(140.dp))
        Text(collection.name, style = MaterialTheme.typography.headlineSmall)
        Text(listOf(collection.kind.replaceFirstChar { it.uppercase() }, collection.artist, collection.year.takeIf { it > 0 }?.toString().orEmpty()).filter { it.isNotBlank() }.joinToString(" · "))
        Text(if (available) "Complete audio · $count songs offline" else "Partial · $count/${collection.songs.size} songs offline")
        if (collection.coverArtId != null) Text(if (cover != null) "Cover saved offline" else "Cover not saved yet", style = MaterialTheme.typography.labelSmall)
        if (!available) Text("The full collection is unavailable offline. Connect and finish downloading; tracks are never skipped.")
        Button({ actions.playOfflineCollection(collection.kind, collection.id) }, enabled = available && !state.loading,
            modifier = Modifier.testTag("offline-collection-play")) { Text("Play in saved order") }
        TextButton({ actions.removeOfflineCollection(collection.kind, collection.id) }, Modifier.testTag("offline-collection-remove")) {
            Text(if (available) "Remove collection download" else "Cancel collection download")
        }
        Text("Songs needed by another collection or an individual download are kept.", style = MaterialTheme.typography.bodySmall)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.storage.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        LazyColumn(Modifier.weight(1f)) {
            items(collection.songs.size) { index ->
                val song = collection.songs[index]
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text("${index + 1}. ${song.title}")
                    Text(song.artist, style = MaterialTheme.typography.bodySmall)
                    if (song.id in complete) OfflineBadge(song.id, state.storage)
                    else Text("Not available offline", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
internal fun OfflineBadge(id: String, storage: SongCacheState) {
    val label = when (id) {
        in storage.downloadedSongs -> "Downloaded · offline"
        in storage.cachedSongs -> "Saved · offline"
        else -> null
    }
    if (label != null) Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
internal fun StorageSettings(state: AudioUiState, actions: AudioUiActions) {
    val storage = state.storage
    val settings = storage.settings
    var custom by rememberSaveable { mutableStateOf("") }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    Text("Storage & offline", style = MaterialTheme.typography.titleLarge)
    Text("Automatically saved: ${storage.cacheBytes / 1_000_000} MB · ${storage.cachedSongs.size} complete songs")
    Text("Downloads: ${storage.downloadBytes / 1_000_000} MB · ${storage.downloadedSongs.size} complete songs")
    StorageToggle("Save played songs", settings.autoSave, "storage-auto-save") { actions.setOfflineSettings(settings.copy(autoSave = it)) }
    Text("Automatic cache limit (${settings.limitBytes / 1_000_000} MB)")
    val limits = listOf(500L, 1000L, 2000L, 5000L)
    Column {
        limits.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { mb -> FilterChip(settings.limitBytes == mb * 1_000_000,
                    { actions.setOfflineSettings(settings.copy(limitBytes = mb * 1_000_000)) },
                    { Text(if (mb < 1000) "$mb MB" else "${mb / 1000} GB") }) }
            }
        }
    }
    OutlinedTextField(custom, { value -> custom = value.filter(Char::isDigit).take(8) },
        label = { Text("Custom limit (MB)") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("storage-custom"))
    val customMb = custom.toLongOrNull()
    TextButton({ customMb?.let { actions.setOfflineSettings(settings.copy(limitBytes = it * 1_000_000)) } }, enabled = customMb != null && customMb >= 1) { Text("Apply custom limit") }
    Text("Prefetch upcoming songs (${if (settings.prefetch == 0) "off" else settings.prefetch})")
    listOf(0, 1, 3, 5, 10).chunked(3).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { count -> FilterChip(settings.prefetch == count,
                { actions.setOfflineSettings(settings.copy(prefetch = count)) }, { Text(if (count == 0) "Off" else "$count songs") }) }
        }
    }
    Text("Upcoming songs and interrupted saves use Wi-Fi or an unmetered connection by default. The current song is saved on the connection used for playback.", style = MaterialTheme.typography.bodySmall)
    StorageToggle("Prefetch on mobile data", settings.mobilePrefetch, "storage-mobile") { actions.setOfflineSettings(settings.copy(mobilePrefetch = it)) }
    StorageToggle("Offline mode", settings.offline, "storage-offline") { actions.setOfflineSettings(settings.copy(offline = it)) }
    Text("Offline mode blocks music-server requests. Only fully saved songs can play. Downloads are pinned and never removed by automatic cache cleanup.", style = MaterialTheme.typography.bodySmall)
    OutlinedButton({ actions.showScreen(Screen.OFFLINE) }, Modifier.fillMaxWidth().testTag("storage-songs")) { Text("Saved music · songs, albums & playlists") }
    OutlinedButton({ confirmClear = true }, Modifier.fillMaxWidth().testTag("storage-clear")) { Text("Clear automatic cache") }
    storage.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text("Clear automatically saved songs?") },
        text = { Text("Pinned downloads and the currently playing song are kept. Other songs will need a connection to play again.") },
        confirmButton = { TextButton({ confirmClear = false; actions.clearSongCache() }) { Text("Clear cache") } },
        dismissButton = { TextButton({ confirmClear = false }) { Text("Cancel") } })
}

@Composable
private fun StorageToggle(label: String, checked: Boolean, tag: String, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, change, Modifier.testTag(tag))
    }
}

@Composable
internal fun OfflineSongs(state: AudioUiState, actions: AudioUiActions, artworkUrl: (String?) -> String? = { null }) {
    val selected = state.selectedOfflineCollection
    if (selected != null) {
        OfflineCollectionDetails(state, actions, selected, artworkUrl)
        return
    }
    var recent by rememberSaveable { mutableStateOf(false) }
    var offlineOnly by rememberSaveable { mutableStateOf(true) }
    val complete = state.storage.cachedSongs + state.storage.downloadedSongs
    val songs = if (recent) state.storage.recentSongs.filter { !offlineOnly || it.id in complete } else state.storage.songs
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ actions.showScreen(Screen.SETTINGS) }) { Text("Back") }
            Text("Saved music", style = MaterialTheme.typography.headlineSmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!recent, { recent = false }, { Text("All saved") })
            FilterChip(recent, { recent = true }, { Text("Recently played") })
        }
        if (recent) FilterChip(offlineOnly, { offlineOnly = !offlineOnly }, { Text("Offline only") }, modifier = Modifier.testTag("recent-offline-filter"))
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.storage.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (songs.isEmpty()) Text(if (recent) "No played songs match this filter." else "Play a song online or download music to save it here.")
        LazyColumn(Modifier.weight(1f)) {
            items(state.storage.collections, key = { it.owner }) { collection ->
                val count = collection.availableCount { it in complete }
                TextButton({ actions.openOfflineCollection(collection.kind, collection.id) }, Modifier.fillMaxWidth().testTag("offline-${collection.kind}-${collection.id}")) {
                    Column(Modifier.weight(1f)) {
                        Text(collection.name)
                        Text("${collection.kind.replaceFirstChar { it.uppercase() }} · " +
                            if (count == collection.songs.size) "Complete audio · $count songs offline" else "Partial · $count/${collection.songs.size} songs offline")
                    }
                }
            }
            items(state.storage.pendingDownloads, key = { "pending-${it.id}" }) { song ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${song.title} · download pending", Modifier.weight(1f))
                    if (song.id in state.storage.explicitDownloads) TextButton({ actions.removeDownload(song.id) }) { Text("Cancel download") }
                    else Text("Kept by collection", style = MaterialTheme.typography.labelSmall)
                }
            }
            items(songs, key = { it.id }) { song ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ actions.playSong(song) }, Modifier.weight(1f), enabled = !state.storage.settings.offline || song.id in complete) {
                        Column(Modifier.weight(1f)) {
                            Text(song.title)
                            Text(song.artist, style = MaterialTheme.typography.bodySmall)
                            OfflineBadge(song.id, state.storage)
                        }
                    }
                    if (song.id in state.storage.explicitDownloads) TextButton({ actions.removeDownload(song.id) }) { Text("Remove download") }
                }
            }
        }
    }
}
