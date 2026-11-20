package com.everyonesoftware.audio.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.everyonesoftware.audio.data.Album
import com.everyonesoftware.audio.data.Playlist
import com.everyonesoftware.audio.data.Song
import com.everyonesoftware.audio.data.CollectionKind
import com.everyonesoftware.audio.data.CollectionLayout
import com.everyonesoftware.audio.playback.output.OutputDeviceKind

/** This is the single owner of system/cutout and keyboard insets. */
@Composable
fun AudioApp(state: AudioUiState, actions: AudioUiActions, artworkUrl: (String?) -> String?) {
    val home = if (state.connection != null) Screen.PLAYLISTS else Screen.WELCOME
    BackHandler(enabled = state.screen != Screen.WELCOME && state.screen != Screen.PLAYLISTS) {
        actions.showScreen(state.backDestination)
    }
    CompositionLocalProvider(LocalSettings provides SettingsAffordance(state.updates.release != null) { actions.showScreen(Screen.SETTINGS) }) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom)).imePadding()) {
            if (state.booting) {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.semantics { contentDescription = "Restoring your listening session" })
                }
            } else {
                RefreshablePage(state, actions, Modifier.weight(1f).fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .padding(horizontal = 18.dp)) {
                    when (state.screen) {
                        Screen.WELCOME -> Welcome(actions)
                        Screen.CONNECT -> Connect(state, actions, home)
                        Screen.LIBRARIES -> Libraries(state, actions)
                        Screen.READY -> Ready(state, actions)
                        Screen.PLAYLISTS -> Playlists(state, actions, artworkUrl)
                        Screen.ALL_PLAYLISTS -> Collection(state, actions, CollectionKind.PLAYLISTS, artworkUrl)
                        Screen.ALL_ALBUMS -> Collection(state, actions, CollectionKind.ALBUMS, artworkUrl)
                        Screen.ALL_ARTISTS -> Artists(state, actions, artworkUrl)
                        Screen.ARTIST -> ArtistDetails(state, actions, artworkUrl)
                        Screen.PLAYLIST -> PlaylistDetails(state, actions, artworkUrl)
                        Screen.SEARCH -> Search(state, actions, artworkUrl)
                        Screen.ALBUM -> AlbumDetails(state, actions, artworkUrl)
                        Screen.PLAYER -> Player(state, actions, artworkUrl)
                        Screen.SETTINGS -> Settings(state, actions, home)
                        Screen.QUEUE -> Queue(state, actions, artworkUrl)
                        Screen.UPDATES -> Updates(state, actions)
                        Screen.OFFLINE -> OfflineSongs(state, actions, artworkUrl)
                    }
                }
                if (state.session?.queue?.getOrNull(state.session.index) != null && state.screen in listOf(Screen.SETTINGS, Screen.PLAYLISTS, Screen.ALL_PLAYLISTS, Screen.ALL_ALBUMS, Screen.ALL_ARTISTS, Screen.ARTIST, Screen.PLAYLIST, Screen.SEARCH, Screen.ALBUM, Screen.QUEUE, Screen.UPDATES, Screen.OFFLINE)) {
                    MiniPlayer(state, actions, artworkUrl)
                }
            }
        }
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RefreshablePage(state: AudioUiState, actions: AudioUiActions, modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    val refreshing = when (state.screen) {
        Screen.PLAYLISTS -> state.playlistsRefreshing || state.albumsRefreshing || state.artistsRefreshing
        Screen.ALL_ARTISTS -> state.artistsRefreshing
        Screen.ARTIST -> state.artistLoading
        Screen.ALL_PLAYLISTS -> state.playlistsRefreshing
        Screen.ALL_ALBUMS -> state.albumsRefreshing
        Screen.PLAYLIST -> state.playlistLoading
        Screen.ALBUM -> state.albumLoading
        Screen.SEARCH -> state.searchLoading
        else -> null
    }
    if (refreshing == null || state.connection == null || state.storage.settings.offline) {
        Box(modifier, content = content)
    } else {
        // Reset the gesture on navigation/account changes, outside the persistent mini-player.
        key(state.screen, state.connection.id) {
            PullToRefreshBox(isRefreshing = refreshing, onRefresh = actions::refreshCurrentPage,
                modifier = modifier.testTag("page-refresh").semantics {
                    customActions = listOf(CustomAccessibilityAction("Refresh page") {
                        if (!refreshing) actions.refreshCurrentPage()
                        true
                    })
                }, content = content)
        }
    }
}

private data class SettingsAffordance(val updateAvailable: Boolean, val open: () -> Unit)
private val LocalSettings = staticCompositionLocalOf { SettingsAffordance(false) {} }

@Composable
internal fun SettingsGear() {
    val settings = LocalSettings.current
    IconButton(settings.open, Modifier.sizeIn(minWidth = 48.dp, minHeight = 52.dp).testTag("settings-gear")) {
        Box(Modifier.size(28.dp)) {
            Icon(Icons.Default.Settings, if (settings.updateAvailable) "Settings, update available" else "Settings", Modifier.align(Alignment.Center))
            if (settings.updateAvailable) Box(Modifier.size(7.dp).align(Alignment.TopEnd).background(MaterialTheme.colorScheme.primary, CircleShape).testTag("settings-update-dot"))
        }
    }
}

@Composable
private fun Header(title: String, eyebrow: String = "", back: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        if (back != null) AudioIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", onClick = back)
        Column(Modifier.weight(1f)) {
            if (eyebrow.isNotBlank()) Text(eyebrow, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
        }
        SettingsGear()
    }
}

/** Collection metadata lives below the cover; the top row contains navigation only. */
@Composable
private fun CollectionDetailHeader(tag: String, back: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        AudioIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", onClick = back)
        Spacer(Modifier.weight(1f))
        SettingsGear()
    }
}

@Composable
private fun AudioIcon(icon: ImageVector, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, active: Boolean = false, onClick: () -> Unit) {
    IconButton(onClick, modifier.sizeIn(minWidth = 48.dp, minHeight = 52.dp), enabled = enabled) {
        Icon(icon, label, tint = if (active) MaterialTheme.colorScheme.primary else LocalContentColor.current)
    }
}


@Composable
private fun Primary(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick, modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = enabled, shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) { Text(label) }
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun ErrorMessage(error: String?) {
    if (error != null) Text(error, Modifier.padding(vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun Welcome(actions: AudioUiActions) {
    Column(Modifier.fillMaxSize().padding(vertical = 16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { SettingsGear() }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Hint("WELCOME TO MUSIC")
            Icon(Icons.Default.GraphicEq, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Your music.\nRight where\nyou left it.", style = MaterialTheme.typography.headlineLarge)
            Text("A personal player for the music on your Navidrome server.")
            HorizontalDivider()
            Text("Bring your playlists", style = MaterialTheme.typography.titleMedium)
            Hint("Choose your usual rotation. Shuffle once and start listening.")
            Text("Keep your place", style = MaterialTheme.typography.titleMedium)
            Hint("Next time, open your Library and continue from the saved mini-player.")
        }
        Primary("Connect to Navidrome", Modifier.padding(top = 16.dp)) { actions.showScreen(Screen.CONNECT) }
        Hint("Use your existing Navidrome account.", Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun Connect(state: AudioUiState, actions: AudioUiActions, home: Screen) {
    // Password intentionally never goes into saveable state or mockup-prefilled values.
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Header("Connect your server", "Navidrome account", back = { actions.showScreen(home) })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Hint("Use your Navidrome account. You don’t need to create a Music account.")
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().testTag("connect-name"), enabled = !state.loading, label = { Text("Connection name (optional)") }, singleLine = true)
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth().testTag("connect-server-url"), enabled = !state.loading, label = { Text("Server address") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true)
            Hint("Use the server’s base address, including a subpath if needed.")
            OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth().testTag("connect-username"), enabled = !state.loading, label = { Text("Username") }, singleLine = true)
            OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth().testTag("connect-password"), enabled = !state.loading, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true)
            ErrorMessage(state.error)
            Spacer(Modifier.height(8.dp))
        }
        Primary(if (state.loading) "Checking connection…" else "Connect & discover libraries", Modifier.padding(vertical = 12.dp).testTag("connect-submit"), enabled = !state.loading && url.isNotBlank() && username.isNotBlank() && password.isNotEmpty()) {
            actions.connect(name.trim(), url.trim(), username.trim(), password)
        }
    }
}

@Composable
private fun Libraries(state: AudioUiState, actions: AudioUiActions) {
    Column(Modifier.fillMaxSize()) {
        Header("Choose libraries", "Browsing preferences", back = { actions.showScreen(Screen.SETTINGS) })
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                state.connection?.let { Text(it.name, style = MaterialTheme.typography.titleMedium); Hint(it.username) }
                Text("What do you want to browse?", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.headlineMedium)
                Hint("Choose one or more libraries this account can access. You can change this later.")
            }
            items(state.libraries, key = { it.id }) { library ->
                Row(Modifier.fillMaxWidth().heightIn(min = 86.dp).toggleable(state.selectedLibraries.contains(library.id), enabled = !state.loading, role = Role.Checkbox, onValueChange = { actions.setLibrary(library.id, it) }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(state.selectedLibraries.contains(library.id), onCheckedChange = null, enabled = !state.loading)
                    Text(library.name, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleMedium)
                }
                HorizontalDivider()
            }
            item {
                if (state.libraries.isEmpty() && !state.loading) Hint("No libraries available. Ask your Navidrome administrator to grant this account access.")
                ErrorMessage(state.error)
                Hint("Missing a library? Creation and account access are managed in Navidrome.")
            }
        }
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(vertical = 12.dp)) {
                Hint("${state.selectedLibraries.size} of ${state.libraries.size} selected", Modifier.padding(bottom = 8.dp))
                Primary(if (state.loading) "Saving…" else "Continue", Modifier.testTag("library-continue"), enabled = !state.loading && state.selectedLibraries.isNotEmpty(), onClick = actions::saveLibraries)
            }
        }
    }
}

@Composable
private fun Ready(state: AudioUiState, actions: AudioUiActions) {
    Column(Modifier.fillMaxSize()) {
        Header("You’re connected", "Setup complete", back = { actions.showScreen(Screen.LIBRARIES) })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Icon(Icons.Default.CheckCircle, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Let’s find your\nfirst playlist.", style = MaterialTheme.typography.headlineMedium)
            Hint("Once you start listening, the next launch will remember your place.")
            state.connection?.let { Text(it.name, style = MaterialTheme.typography.titleMedium); Hint(it.username) }
            HorizontalDivider()
            Hint("Browse libraries")
            Text(state.libraries.filter { it.id in state.selectedLibraries }.joinToString { it.name }.ifBlank { "None selected" })
            ErrorMessage(state.error)
        }
        Primary(if (state.loading) "Loading playlists…" else "Choose a playlist", Modifier.padding(vertical = 12.dp), enabled = !state.loading, onClick = actions::loadPlaylists)
    }
}

@Composable
private fun Artwork(id: String?, artworkUrl: (String?) -> String?, modifier: Modifier) {
    val url = remember(id, artworkUrl) { id?.let(artworkUrl) }
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.MusicNote, null, Modifier.fillMaxSize(0.3f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (url != null) AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    }
}

@Composable
private fun Playlists(state: AudioUiState, actions: AudioUiActions, artworkUrl: (String?) -> String?) {
    Column(Modifier.fillMaxSize()) {
        Header("Library", state.connection?.name.orEmpty())
        Surface(Modifier.fillMaxWidth().clickable { actions.showScreen(Screen.SEARCH) }, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Hint("Search playlists, albums, artists, and songs", Modifier.padding(start = 10.dp))
            }
        }
        ErrorMessage(state.error)
        ErrorMessage(state.playlistError)
        ErrorMessage(state.albumsError)

        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("library-content"), contentPadding = PaddingValues(top = 10.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Playlists", style = MaterialTheme.typography.titleLarge)
                    TextButton({ actions.showScreen(Screen.ALL_PLAYLISTS) }, Modifier.testTag("view-all-playlists").semantics { contentDescription = "View all playlists" }) { Text("View all"); Icon(Icons.Default.ChevronRight, null) }
                }
                if (state.playlists.isEmpty() && !state.playlistsRefreshing) Hint("No playlists available on this server.")
                LazyRow(contentPadding = PaddingValues(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.playlists, key = { "playlist-${it.id}" }) { playlist ->
                        PlaylistTile(playlist, playlist.id in state.stalePlaylistIds, artworkUrl, enabled = !state.loading,
                            onOpen = { actions.openPlaylist(playlist.id) })
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Albums", style = MaterialTheme.typography.titleLarge)
                    TextButton({ actions.showScreen(Screen.ALL_ALBUMS) }, Modifier.testTag("view-all-albums").semantics { contentDescription = "View all albums" }) { Text("View all"); Icon(Icons.Default.ChevronRight, null) }
                }
                if (state.albums.isEmpty() && !state.albumsRefreshing && state.albumsError == null) Hint("No albums available on this server.")
                LazyRow(contentPadding = PaddingValues(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.albums, key = { "album-${it.id}" }) { album -> AlbumTile(album, artworkUrl) { actions.openAlbum(album.id) } }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Artists", style = MaterialTheme.typography.titleLarge)
                    TextButton({ actions.showScreen(Screen.ALL_ARTISTS) }, Modifier.testTag("view-all-artists").semantics { contentDescription = "View all artists" }) { Text("View all"); Icon(Icons.Default.ChevronRight, null) }
                }
                ArtistCatalogStatus(state, actions)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.artists, key = { it.id }) { artist ->
                        Column(Modifier.width(132.dp).testTag("artist-card-${artist.id}").clickable { actions.openArtist(artist.id) }, horizontalAlignment = Alignment.CenterHorizontally) {
                            ArtistArtwork(artist.coverArtId, artworkUrl, Modifier.size(132.dp))
                            Text(artist.name, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistArtwork(id: String?, artworkUrl: (String?) -> String?, modifier: Modifier) {
    val url = remember(id, artworkUrl) { id?.let(artworkUrl) }
    Box(modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Person, null, Modifier.fillMaxSize(0.45f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (url != null) AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun ArtistRow(artist: com.everyonesoftware.audio.data.Artist, artworkUrl: (String?) -> String?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 76.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ArtistArtwork(artist.coverArtId, artworkUrl, Modifier.size(56.dp))
        Text(artist.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Icon(Icons.Default.ChevronRight, null)
    }
}

@Composable
private fun ArtistCatalogStatus(state: AudioUiState, actions: AudioUiActions) {
    ErrorMessage(state.artistsError)
    if (state.artistsRefreshing) LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "Loading artists" })
    if (state.artistsError != null) TextButton(actions::loadArtists, enabled = !state.artistsRefreshing && !state.storage.settings.offline) { Text("Retry artists") }
    if (state.storage.settings.offline) Hint("Artists are unavailable offline. Open Saved music for downloaded albums and songs.")
    else if (state.artists.isEmpty() && !state.artistsRefreshing && state.artistsError == null) Hint("No artists available on this server.")
}

@Composable
private fun Artists(state: AudioUiState, actions: AudioUiActions, artworkUrl: (String?) -> String?) {
    val entries = state.artists.filter { it.name.contains(state.artistCollectionQuery.trim(), ignoreCase = true) }
    Column(Modifier.fillMaxSize()) {
        Header("Artists", state.connection?.name.orEmpty(), back = { actions.showScreen(Screen.PLAYLISTS) })
        OutlinedTextField(state.artistCollectionQuery, actions::setArtistQuery, Modifier.fillMaxWidth().testTag("artist-search"),
            singleLine = true, label = { Text("Search artists") }, leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (state.artistCollectionQuery.isNotEmpty()) AudioIcon(Icons.Default.Close, "Clear search") { actions.setArtistQuery("") } })
        if (!state.artistsRefreshing && state.artistsError == null && !state.storage.settings.offline) Hint("${entries.size} artists", Modifier.padding(vertical = 12.dp).testTag("artist-count"))
        ArtistCatalogStatus(state, actions)
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("artists-list"), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (entries.isEmpty() && state.artistCollectionQuery.isNotBlank()) item { Hint("No artists match “${state.artistCollectionQuery.trim()}”.") }
            items(entries, key = { it.id }) { artist ->
                ArtistRow(artist, artworkUrl, Modifier.testTag("artist-card-${artist.id}")) { actions.openArtist(artist.id) }
            }
        }
    }
}

@Composable
private fun ArtistDetails(state: AudioUiState, actions: AudioUiActions, artworkUrl: (String?) -> String?) {
    val artist = state.selectedArtist
    val contents = state.artistContents
    val songs = contents?.songs.orEmpty()
    val ready = artist != null && contents != null && !state.artistLoading && state.artistError == null && !state.storage.settings.offline
    val enabled = ready && songs.isNotEmpty() && !state.loading
    var allSongs by rememberSaveable(state.connection?.id, artist?.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        CollectionDetailHeader("artist-header") { actions.showScreen(state.backDestination) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("artist-content"), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (artist != null) item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ArtistArtwork(artist.coverArtId, artworkUrl, Modifier.size(180.dp).testTag("artist-artwork"))
                    Text(artist.name, Modifier.fillMaxWidth().testTag("artist-name").semantics { heading() },
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.headlineSmall)
                    if (ready) Text("${contents!!.albums.size} ${if (contents.albums.size == 1) "album" else "albums"} · ${songs.size} ${if (songs.size == 1) "song" else "songs"}", Modifier.fillMaxWidth().testTag("artist-summary"),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.testTag("artist-transport"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilledIconButton({ actions.playArtist(artist.id, false) }, Modifier.size(56.dp).testTag("artist-play"), enabled = enabled, shape = CircleShape) {
                            Icon(Icons.Default.PlayArrow, "Play artist in order", Modifier.size(30.dp))
                        }
                        IconButton({ actions.playArtist(artist.id, true) }, Modifier.size(48.dp).testTag("artist-shuffle"), enabled = enabled) {
                            Icon(Icons.Default.Shuffle, "Shuffle artist", Modifier.size(24.dp))
                        }
                    }
                }
            }
            item {
                ErrorMessage(state.error)
                ErrorMessage(state.artistError)
                if (state.artistLoading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "Loading artist" })
                    Hint("Loading albums and songs…")
                }
                if (state.storage.settings.offline) Hint("Artists are unavailable offline. Open Saved music for downloaded albums and songs.")
                if (state.artistError != null && artist != null) TextButton({ actions.openArtist(artist.id) }, enabled = !state.storage.settings.offline) { Text("Retry artist") }
                if (ready && songs.isEmpty()) Hint("This artist has no songs.")
            }
            if (ready) {
                item {
                    TabRow(selectedTabIndex = if (allSongs) 1 else 0) {
                        Tab(!allSongs, { allSongs = false }, Modifier.testTag("artist-albums"), text = { Text("Albums") })
                        Tab(allSongs, { allSongs = true }, Modifier.testTag("artist-all-songs"), text = { Text("All songs") })
                    }
                }
                if (allSongs) items(songs.size, key = { "artist-song-$it" }) { index ->
                    val song = songs[index]
                    // No aggregate download action: only the existing per-song affordance.
                    SongRow(song, artworkUrl, state.storage, enabled, { actions.downloadSongs(listOf(song)) }, Modifier.testTag("artist-song-$index")) {
                        if (state.connection != null) actions.playCollectionSong(CollectionSongSelection(state.connection.id, "artist", artist!!.id, index, songs))
                    }
                } else items(contents!!.albums, key = { it.album.id }) { album ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).testTag("album-card-${album.album.id}").clickable { actions.openAlbum(album.album.id) },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Artwork(album.album.coverArtId, artworkUrl, Modifier.size(56.dp))
                        Column(Modifier.weight(1f)) { Text(album.album.name, style = MaterialTheme.typography.titleMedium); Hint("${album.songs.size} ${if (album.songs.size == 1) "song" else "songs"}") }
                        Icon(Icons.Default.ChevronRight, null)
                    }
                }
            }
        }
    }
}

private data class CollectionEntry(val id: String, val name: String, val subtitle: String, val coverArtId: String?)

@Composable
private fun Collection(state: AudioUiState, actions: AudioUiActions, kind: CollectionKind, artworkUrl: (String?) -> String?) {
    val playlists = kind == CollectionKind.PLAYLISTS
    val noun = if (playlists) "playlists" else "albums"
    val query = if (playlists) state.playlistCollectionQuery else state.albumCollectionQuery
    val layout = if (playlists) state.playlistCollectionLayout else state.albumCollectionLayout
    val refreshing = if (playlists) state.playlistsRefreshing else state.albumsRefreshing
    val error = if (playlists) state.playlistError else state.albumsError
    val total = if (playlists) state.playlists.size else state.albums.size
    val entries = remember(state.playlists, state.albums, state.stalePlaylistIds, query, kind) {
        if (playlists) state.visiblePlaylists.map {
            CollectionEntry(it.id, it.name, "${it.songCount} songs" + if (it.id in state.stalePlaylistIds) " · last known" else "", it.coverArtId)
        } else state.visibleAlbums.map {
            CollectionEntry(it.id, it.name, it.artist + if (it.year > 0) " · ${it.year}" else "", it.coverArtId)
        }
    }
    val open: (String) -> Unit = { if (playlists) actions.openPlaylist(it) else actions.openAlbum(it) }
    Column(Modifier.fillMaxSize()) {
        Header(if (playlists) "Playlists" else "Albums", state.connection?.name.orEmpty(), back = { actions.showScreen(Screen.PLAYLISTS) })
        OutlinedTextField(query, { actions.setCollectionQuery(kind, it) },
            Modifier.fillMaxWidth().testTag("collection-search"), singleLine = true,
            label = { Text(if (playlists) "Search playlists" else "Search albums or artists") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) AudioIcon(Icons.Default.Close, "Clear search") { actions.setCollectionQuery(kind, "") } })
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (query.isBlank()) "${entries.size} $noun" else "${entries.size} of $total $noun",
                Modifier.weight(1f).testTag("collection-count").semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.selectableGroup()) {
                for (mode in CollectionLayout.entries) {
                    val grid = mode == CollectionLayout.GRID
                    IconToggleButton(checked = layout == mode, onCheckedChange = { actions.setCollectionLayout(kind, mode) },
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                        Icon(if (grid) Icons.Default.GridView else Icons.Default.ViewList, if (grid) "Grid view" else "List view",
                            tint = if (layout == mode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        ErrorMessage(state.error)
        ErrorMessage(error)
        if (error != null) TextButton({ actions.refreshCollection(kind) }, enabled = !refreshing) { Text("Retry $noun") }
        if (refreshing) LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "Loading $noun" })
        if (entries.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth().testTag("collection-empty").verticalScroll(rememberScrollState()).padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    refreshing -> Hint("Loading $noun…")
                    error != null -> Hint("Unable to load $noun. Check your connection and retry.")
                    query.isNotBlank() -> {
                        Hint("No $noun match “${query.trim()}”.")
                        TextButton({ actions.setCollectionQuery(kind, "") }) { Text("Clear search") }
                    }
                    else -> Hint("No $noun available on this server.")
                }
            }
        } else if (layout == CollectionLayout.GRID) {
            LazyVerticalGrid(GridCells.Adaptive(128.dp), Modifier.weight(1f).fillMaxWidth().testTag("collection-grid"),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                gridItems(entries, key = { it.id }) { entry ->
                    Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { open(entry.id) }.testTag("${if (playlists) "playlist" else "album"}-card-${entry.id}")) {
                        Artwork(entry.coverArtId, artworkUrl, Modifier.fillMaxWidth().aspectRatio(1f))
                        Text(entry.name, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(entry.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("collection-list"), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(entries, key = { it.id }) { entry ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).clickable(role = Role.Button) { open(entry.id) }.testTag("${if (playlists) "playlist" else "album"}-card-${entry.id}").padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Artwork(entry.coverArtId, artworkUrl, Modifier.size(56.dp))
                        Column(Modifier.weight(1f)) {
                            Text(entry.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(entry.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.Default.ChevronRight, null)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun PlaylistTile(playlist: Playlist, stale: Boolean, artworkUrl: (String?) -> String?, enabled: Boolean, onOpen: () -> Unit) {
    Column(Modifier.width(132.dp).clickable(enabled = enabled, onClick = onOpen).testTag("playlist-card-${playlist.id}")) {
        Artwork(playlist.coverArtId, artworkUrl, Modifier.size(132.dp))
        Text(playlist.name, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Hint("${playlist.songCount} songs" + if (stale) " · last known" else "")
    }
}

@Composable
private fun AlbumTile(album: Album, artworkUrl: (String?) -> String?, onClick: () -> Unit) {
    Column(Modifier.width(132.dp).clickable(onClick = onClick).testTag("album-card-${album.id}")) {
        Artwork(album.coverArtId, artworkUrl, Modifier.size(132.dp))
        Text(album.name, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Hint(album.artist + if (album.year > 0) " · ${album.year}" else "")
    }
}

@Composable
private fun Search(state: AudioUiState, actions: AudioUiActions, artworkUrl: (String?) -> String?) {
    val term = state.searchQuery.trim()
    val playlists = state.playlists.filter { it.name.contains(term, ignoreCase = true) }
    val albums = state.albums.filter { it.name.contains(term, ignoreCase = true) || it.artist.contains(term, ignoreCase = true) }
    val artists = state.artists.filter { it.name.contains(term, ignoreCase = true) }
    Column(Modifier.fillMaxSize()) {
        Header("Search", state.connection?.name.orEmpty(), back = { actions.showScreen(Screen.PLAYLISTS) })
        OutlinedTextField(state.searchQuery, actions::search, Modifier.fillMaxWidth().padding(bottom = 10.dp).testTag("library-search"), label = { Text("Playlists, albums, artists, and songs") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true)
        ErrorMessage(state.artistsError)
        if (state.artistsError != null) TextButton(actions::loadArtists, enabled = !state.artistsRefreshing && !state.storage.settings.offline) { Text("Retry artists") }
        if (state.artistsRefreshing) LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "Loading artists" })
        ErrorMessage(state.albumsError)
        ErrorMessage(state.searchError)
        if (state.searchError != null) TextButton({ actions.search(state.searchQuery) }) { Text("Retry song search") }
        if (state.searchLoading) LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "Searching songs" })
        LazyColumn(Modifier.weight(1f).testTag("search-results"), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (term.isBlank()) item { Hint("Search your playlists, albums, artists, and songs by name or artist.") }
            if (artists.isNotEmpty()) item { Text("Artists", style = MaterialTheme.typography.titleLarge) }
            items(artists, key = { "search-artist-${it.id}" }) { artist ->
                ArtistRow(artist, artworkUrl, Modifier.testTag("search-artist-${artist.id}")) { actions.openArtist(artist.id) }
            }
            if (playlists.isNotEmpty()) item { Text("Playlists", style = MaterialTheme.typography.titleLarge) }
            items(playlists, key = { "search-playlist-${it.id}" }) { playlist ->
                Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable { actions.openPlaylist(playlist.id) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Artwork(playlist.coverArtId, artworkUrl, Modifier.size(52.dp))
                    Column(Modifier.weight(1f)) { Text(playlist.name, style = MaterialTheme.typography.titleMedium); Hint("${playlist.songCount} songs") }
                    AudioIcon(Icons.Default.Shuffle, "Shuffle and play ${playlist.name}") { actions.startPlaylist(playlist.id) }
                }
                HorizontalDivider()
            }
            if (albums.isNotEmpty()) item { Text("Albums", style = MaterialTheme.typography.titleLarge) }
            items(albums, key = { "search-album-${it.id}" }) { album ->
                Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable { actions.openAlbum(album.id) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Artwork(album.coverArtId, artworkUrl, Modifier.size(52.dp))
                    Column(Modifier.weight(1f)) { Text(album.name, style = MaterialTheme.typography.titleMedium); Hint(album.artist) }
                    Icon(Icons.Default.ChevronRight, "Open ${album.name}")
                }
                HorizontalDivider()
            }
            if (state.searchSongs.isNotEmpty()) item { Text("Songs", style = MaterialTheme.typography.titleLarge) }
            items(state.searchSongs, key = { "search-song-${it.id}" }) { song ->
                SongRow(song, artworkUrl, state.storage, enabled = !state.loading, onDownload = { actions.downloadSongs(listOf(song)) }) { actions.playSong(song) }
            }
            if (term.isNotBlank() && playlists.isEmpty() && albums.isEmpty() && artists.isEmpty() && state.searchSongs.isEmpty() && !state.searchLoading && !state.artistsRefreshing && state.searchError == null && state.artistsError == null) item { Hint("No playlists, albums, artists, or songs match “$term”.") }
        }
    }
}

@Composable
private fun SongRow(song: Song, artworkUrl: (String?) -> String?, storage: com.everyonesoftware.audio.data.SongCacheState, enabled: Boolean, onDownload: () -> Unit, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(enabled = enabled, onClick = onClick), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Artwork(song.coverArtId, artworkUrl, Modifier.size(46.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Hint(song.artist)
            OfflineBadge(song.id, storage)
        }
        IconButton(onDownload) { Icon(Icons.Default.Download, "Download ${song.title}") }
        Hint(timeLabel(song.durationSeconds * 1000L))
    }
}

@Composable
private fun PlaylistDetails(state: AudioUiState, actions: AudioUiActions, artworkUrl: (String?) -> String?) {
    val playlist = state.selectedPlaylist
    val transportEnabled = !state.loading && !state.playlistLoading && state.playlistDetailError == null &&
        state.playlistSongs.isNotEmpty() && !state.storage.settings.offline
    Column(Modifier.fillMaxSize()) {
        CollectionDetailHeader("playlist-header") { actions.showScreen(state.backDestination) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("playlist-songs"), contentPadding = PaddingValues(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (playlist != null) item {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.Center) {
                    Artwork(playlist.coverArtId, artworkUrl, Modifier.size(180.dp).testTag("playlist-artwork"))
                }
                Text(playlist.name, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
                Hint(if (state.playlistLoading) "Loading songs…" else "${state.playlistSongs.size} songs", Modifier.padding(vertical = 8.dp))
                Row(Modifier.fillMaxWidth().testTag("playlist-transport"), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledIconButton({ actions.playPlaylist(playlist.id, false) }, Modifier.size(56.dp).testTag("playlist-play"),
                        enabled = transportEnabled, shape = CircleShape) {
                        Icon(Icons.Default.PlayArrow, "Play playlist in order", Modifier.size(30.dp))
                    }
                    IconButton({ actions.playPlaylist(playlist.id, true) }, Modifier.size(48.dp).testTag("playlist-shuffle"),
                        enabled = transportEnabled) {
                        Icon(Icons.Default.Shuffle, "Shuffle playlist", Modifier.size(24.dp))
                    }
                    IconButton({ actions.downloadPlaylist() }, Modifier.size(48.dp).testTag("playlist-download"),
                        enabled = transportEnabled) { Icon(Icons.Default.Download, "Download playlist") }
                }
            }
            if (state.playlistLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item {
                ErrorMessage(state.playlistDetailError)
                if (state.playlistDetailError != null && playlist != null) TextButton({ actions.openPlaylist(playlist.id) }) { Text("Retry") }
                if (!state.playlistLoading && state.playlistDetailError == null && state.playlistSongs.isEmpty()) Hint("This playlist has no songs.")
            }
            // Playlists may contain the same song more than once; retain the server's order.
            items(state.playlistSongs.size) { index ->
                val song = state.playlistSongs[index]
                SongRow(song, artworkUrl, state.storage, enabled = transportEnabled, onDownload = { actions.downloadSongs(listOf(song)) },
                    modifier = Modifier.testTag("playlist-song-$index")) {
                    if (playlist != null && state.connection != null) actions.playCollectionSong(CollectionSongSelection(state.connection.id, "playlist", playlist.id, index, state.playlistSongs))
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun AlbumDetails(state: AudioUiState, actions: AudioUiActions, artworkUrl: (String?) -> String?) {
    val album = state.selectedAlbum
    val transportEnabled = !state.loading && !state.albumLoading && state.albumError == null &&
        state.albumSongs.isNotEmpty() && !state.storage.settings.offline
    Column(Modifier.fillMaxSize()) {
        CollectionDetailHeader("album-header") { actions.showScreen(state.backDestination) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("album-songs"), contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp)) {
            if (album != null) item {
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.Center) {
                    Artwork(album.coverArtId, artworkUrl, Modifier.size(220.dp).testTag("album-artwork"))
                }
                Text(album.name, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Hint(album.artist + if (album.year > 0) " · ${album.year}" else "", Modifier.padding(top = 4.dp, bottom = 8.dp))
                Row(Modifier.fillMaxWidth().testTag("album-transport"), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledIconButton({ actions.playAlbum(album.id, false) }, Modifier.size(56.dp).testTag("album-play"),
                        enabled = transportEnabled, shape = CircleShape) {
                        Icon(Icons.Default.PlayArrow, "Play album in order", Modifier.size(30.dp))
                    }
                    IconButton({ actions.playAlbum(album.id, true) }, Modifier.size(48.dp).testTag("album-shuffle"),
                        enabled = transportEnabled) {
                        Icon(Icons.Default.Shuffle, "Shuffle album", Modifier.size(24.dp))
                    }
                    IconButton({ actions.downloadAlbum() }, Modifier.size(48.dp).testTag("album-download"),
                        enabled = transportEnabled) { Icon(Icons.Default.Download, "Download album") }
                }
            }
            if (state.albumLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp)) }
            item {
                ErrorMessage(state.albumError)
                if (state.albumError != null && album != null) TextButton({ actions.openAlbum(album.id) }) { Text("Retry album") }
                if (!state.albumLoading && state.albumError == null && state.albumSongs.isEmpty()) Hint("This album has no songs.")
            }
            // Album responses can repeat a song; each occurrence needs its own lazy-list identity.
            items(state.albumSongs.size) { index ->
                val song = state.albumSongs[index]
                SongRow(song, artworkUrl, state.storage, enabled = transportEnabled, onDownload = { actions.downloadSongs(listOf(song)) },
                    modifier = Modifier.testTag("album-song-$index")) {
                    if (album != null && state.connection != null) actions.playCollectionSong(CollectionSongSelection(state.connection.id, "album", album.id, index, state.albumSongs))
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun Player(state: AudioUiState, actions: AudioUiActions, artworkUrl: (String?) -> String?) {
    val session = state.session
    val song = session?.queue?.getOrNull(session.index)
    if (session == null || song == null) {
        Column(Modifier.fillMaxSize()) {
            Header("Now playing", back = { actions.showScreen(Screen.PLAYLISTS) })
            Text("Choose something to listen to", style = MaterialTheme.typography.headlineMedium)
            ErrorMessage(state.error)
            Primary("Choose a playlist", Modifier.padding(vertical = 16.dp), enabled = !state.loading, onClick = actions::loadPlaylists)
        }
        return
    }
    var showRating by rememberSaveable(session.connectionId, song.id) { mutableStateOf(false) }
    var showOutput by rememberSaveable { mutableStateOf(false) }
    var attemptedRating by rememberSaveable(session.connectionId, song.id) { mutableStateOf<Int?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val scrollFallback = maxHeight < 580.dp || LocalDensity.current.fontScale > 1.3f
        Column(if (scrollFallback) Modifier.fillMaxSize().verticalScroll(rememberScrollState()) else Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                AudioIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back") { actions.showScreen(Screen.PLAYLISTS) }
                Text(session.playlistName, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                SettingsGear()
            }
            Box(if (scrollFallback) Modifier.fillMaxWidth().height(140.dp) else Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val side = minOf(maxHeight, maxWidth)
                    Artwork(song.coverArtId, artworkUrl, Modifier.size(side))
                }
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = MaterialTheme.typography.headlineMedium, maxLines = if (scrollFallback) 5 else 2, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    OfflineBadge(song.id, state.storage)
                }
                IconButton({ actions.downloadSongs(listOf(song)) }) { Icon(Icons.Default.Download, "Download ${song.title}") }
                OutlinedButton({ showRating = true }, Modifier.width(64.dp).heightIn(min = 64.dp).testTag("player-rating").semantics { contentDescription = "Rate song, ${if (song.userRating == 0) "not rated" else "${song.userRating} of 5 stars"}" }, contentPadding = PaddingValues(4.dp), shape = RoundedCornerShape(12.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(if (song.userRating > 0) Icons.Default.Star else Icons.Outlined.StarBorder, null)
                        Text(if (state.ratingBusy) "…" else if (song.userRating > 0) "${song.userRating}/5" else "Rate", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Column {
                var seeking by remember(song.id) { mutableStateOf<Float?>(null) }
                val duration = state.durationMs.coerceAtLeast(0)
                Slider(value = seeking ?: state.positionMs.coerceIn(0L, duration).toFloat(), onValueChange = { seeking = it }, onValueChangeFinished = { seeking?.let { actions.seekTo(it.toLong()) }; seeking = null }, valueRange = 0f..duration.coerceAtLeast(1L).toFloat(), enabled = duration > 0, modifier = Modifier.fillMaxWidth().testTag("player-seek").semantics { contentDescription = "Playback position" })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Hint(timeLabel((seeking?.toLong() ?: state.positionMs).coerceAtLeast(0))); Hint(timeLabel(duration)) }
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 76.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.size(48.dp))
                AudioIcon(Icons.Default.SkipPrevious, "Previous track", onClick = actions::previous)
                FilledIconButton(actions::togglePlay, Modifier.size(width = 76.dp, height = 72.dp).testTag("player-toggle"), shape = RoundedCornerShape(24.dp)) {
                    Icon(if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (state.playing) "Pause" else "Resume playback", Modifier.size(34.dp))
                }
                AudioIcon(Icons.Default.SkipNext, "Next track", Modifier.testTag("player-next"), onClick = actions::next)
                AudioIcon(if (session.repeatMode == 1) Icons.Default.RepeatOne else Icons.Default.Repeat, "Repeat", Modifier.semantics { stateDescription = when (session.repeatMode) { 1 -> "Repeat one"; 2 -> "Repeat all"; else -> "Off" } }, active = session.repeatMode != 0, onClick = actions::toggleRepeat)
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val device = state.mediaOutput.selected
                val label = device?.name ?: "Choose device"
                Box(Modifier.weight(1f)) {
                    TextButton({ actions.refreshMediaOutputs(); showOutput = true }, Modifier.heightIn(min = 52.dp).testTag("player-output").semantics { contentDescription = "Choose output, $label" }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(outputDeviceIcon(device?.kind), null)
                            Column(Modifier.weight(1f, fill = false)) {
                                Text("Media output", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(label, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                TextButton({ actions.showScreen(Screen.QUEUE) }, Modifier.heightIn(min = 52.dp).testTag("player-queue")) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, null)
                    Text("Queue", Modifier.padding(start = 6.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            ErrorMessage(state.error)
        }
    }
    if (showRating) RatingDialog(song.title, song.artist, song.userRating, state.ratingBusy, state.ratingError, attemptedRating, onRate = { attemptedRating = it; actions.rateSong(it) }, onClose = { showRating = false })
    if (showOutput) MediaOutputSheet(state.mediaOutput, actions, onClose = { showOutput = false })
}

internal fun outputDeviceIcon(kind: OutputDeviceKind?): ImageVector = when (kind) {
    OutputDeviceKind.PHONE -> Icons.Default.PhoneAndroid
    OutputDeviceKind.HEADPHONES -> Icons.Default.Headphones
    OutputDeviceKind.BLUETOOTH -> Icons.Default.BluetoothAudio
    OutputDeviceKind.USB -> Icons.Default.Usb
    OutputDeviceKind.SPEAKER -> Icons.Default.Speaker
    else -> Icons.AutoMirrored.Filled.VolumeUp
}

private fun timeLabel(milliseconds: Long): String {
    val seconds = milliseconds / 1000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

@Composable
private fun RatingDialog(title: String, artist: String, confirmed: Int, busy: Boolean, error: String?, attempted: Int?, onRate: (Int) -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(12.dp).widthIn(max = 420.dp).onPreviewKeyEvent { if (it.key == Key.Escape) { onClose(); true } else false }, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Rate this song", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Hint(artist)
                Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    (1..5).forEach { rating ->
                        Surface(Modifier.weight(1f), color = if (rating == confirmed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, if (rating == confirmed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)) {
                            Box(Modifier.widthIn(min = 48.dp).heightIn(min = 68.dp).testTag("rating-$rating").selectable(selected = rating == confirmed, enabled = !busy, role = Role.RadioButton, onClick = { onRate(rating) }).semantics { contentDescription = "$rating ${if (rating == 1) "star" else "stars"}" }, contentAlignment = Alignment.Center) {
                                Icon(if (rating <= confirmed) Icons.Default.Star else Icons.Outlined.StarBorder, null, Modifier.size(30.dp), tint = if (rating <= confirmed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (busy) Text("Saving rating…", Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
                ErrorMessage(error)
                if (error != null && attempted != null) TextButton({ onRate(attempted) }, enabled = !busy) { Text("Retry") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ onRate(0) }, enabled = !busy && confirmed > 0) { Text("Clear rating", style = MaterialTheme.typography.bodyMedium) }
                    Button(onClose, Modifier.heightIn(min = 52.dp)) { Text("Done") }
                }
            }
        }
    }
}

@Composable
private fun Settings(state: AudioUiState, actions: AudioUiActions, home: Screen) {
    val context = LocalContext.current
    var browserError by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        Header("Server & libraries", "Connection settings", back = { actions.showScreen(state.backDestination) })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(Modifier.fillMaxWidth().testTag("settings-updates").clickable { actions.showScreen(Screen.UPDATES) }, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(Modifier.heightIn(min = 72.dp).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Updates", style = MaterialTheme.typography.titleMedium)
                        Hint(if (state.updates.release != null) "A new version is available" else "Music ${state.updates.installedVersion}")
                    }
                    Icon(Icons.Default.ChevronRight, null)
                }
            }
            HorizontalDivider()
            Hint("Connections on this device. Listening sessions stay separate for each server.")
            state.connections.forEach { connection ->
                Surface(shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, if (connection.id == state.connection?.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(connection.name, style = MaterialTheme.typography.titleMedium); Hint(connection.username); Hint(connection.baseUrl) }
                        if (connection.id == state.connection?.id) Text("Active", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        else TextButton({ actions.switchConnection(connection.id) }, enabled = !state.loading) { Text("Switch", style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
            TextButton({ actions.showScreen(Screen.CONNECT) }, enabled = !state.loading) { Text("Add another server") }
            if (state.connection != null) {
                HorizontalDivider()
                Text("Browse libraries", style = MaterialTheme.typography.titleLarge)
                Hint(state.libraries.filter { it.id in state.selectedLibraries }.joinToString { it.name }.ifBlank { "None selected" })
                Primary("Choose libraries", enabled = !state.loading) { actions.showScreen(Screen.LIBRARIES) }
                HorizontalDivider()
                Text("Server administration", style = MaterialTheme.typography.titleLarge)
                Hint("Create libraries, set folder paths, or grant access in Navidrome. Administrator access may be required.")
                Hint("Opens your connected server in an external browser.")
                OutlinedButton({
                    val uri = Uri.parse(state.connection.baseUrl)
                    if (uri.scheme !in listOf("https", "http") || uri.host.isNullOrBlank() || uri.userInfo != null) browserError = "The server address is not a valid HTTP or HTTPS address."
                    else {
                        try { context.startActivity(Intent(Intent.ACTION_VIEW, uri)); browserError = null }
                        catch (_: android.content.ActivityNotFoundException) { browserError = "No browser is available to open this server." }
                    }
                }, Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Open Navidrome administration", style = MaterialTheme.typography.bodyMedium) }
                ErrorMessage(browserError)
            }
            HorizontalDivider()
            StorageSettings(state, actions)
            ErrorMessage(state.error)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Queue(state: AudioUiState, actions: AudioUiActions, artworkUrl: (String?) -> String?) {
    val session = state.session
    var playedOpen by rememberSaveable(session?.connectionId, session?.queueId) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Header("Queue", session?.playlistName.orEmpty(), back = { actions.showScreen(Screen.PLAYER) })
        LazyColumn(Modifier.weight(1f).testTag("queue-songs"), contentPadding = PaddingValues(bottom = 12.dp)) {
            if (session == null || session.queue.isEmpty()) item { Hint("No listening queue yet.") }
            else {
                item("played-toggle") {
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("queue-played-toggle")
                        .clickable { playedOpen = !playedOpen }.semantics { stateDescription = if (playedOpen) "Expanded" else "Collapsed" },
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (playedOpen) Icons.Default.ExpandMore else Icons.Default.ChevronRight, null)
                        Text("Played", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Hint("${session.played.size} ${if (session.played.size == 1) "song" else "songs"}")
                    }
                }
                if (playedOpen) {
                    if (session.playedIndices.isEmpty()) item("played-empty") { Hint("No songs played yet.", Modifier.padding(vertical = 12.dp)) }
                    items(session.playedIndices.size, key = { "played-$it" }) { visit ->
                        QueueSong(session, session.playedIndices[visit], false, actions, artworkUrl, "played-$visit")
                    }
                }
                item("current-label") { Text("Now playing", Modifier.padding(top = 16.dp, bottom = 8.dp), style = MaterialTheme.typography.titleSmall) }
                item("current") { QueueSong(session, session.index, true, actions, artworkUrl, "current") }
                item("up-next") {
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Up next", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        IconButton(actions::shuffleUpNext, Modifier.size(48.dp).testTag("queue-shuffle-up-next"),
                            enabled = !state.loading && state.connection?.id == session.connectionId &&
                                session.index in session.queue.indices && session.queue.size - session.index - 1 >= 2) {
                            Icon(Icons.Default.Shuffle, "Shuffle Up next")
                        }
                    }
                }
                items((session.queue.size - session.index - 1).coerceAtLeast(0), key = { "next-$it" }) { offset ->
                    QueueSong(session, session.index + 1 + offset, false, actions, artworkUrl, "next-$offset")
                }
                item("end") { Hint("End of ${if (session.sourceType == "album") "album" else "queue"}", Modifier.padding(vertical = 20.dp)) }
            }
        }
        ErrorMessage(state.error)
    }
}

@Composable
private fun QueueSong(session: com.everyonesoftware.audio.data.ListeningSession, index: Int, current: Boolean, actions: AudioUiActions, artworkUrl: (String?) -> String?, tag: String) {
    val song = session.queue.getOrNull(index) ?: return
    Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).testTag("queue-$tag")
        .background(if (current) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.background)
        .clickable { actions.playQueueSong(session.connectionId, session.queueId, session.occurrences[index]) }
        .padding(vertical = 8.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Artwork(song.coverArtId, artworkUrl, Modifier.size(if (current) 52.dp else 44.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Hint(song.artist)
        }
        if (current) Icon(Icons.Default.GraphicEq, "Current song", tint = MaterialTheme.colorScheme.primary)
        else Hint(timeLabel(song.durationSeconds * 1000L))
    }
}

@Composable
private fun MiniPlayer(state: AudioUiState, actions: AudioUiActions, artworkUrl: (String?) -> String?) {
    val session = state.session ?: return
    val song = session.queue.getOrNull(session.index) ?: return
    Surface(Modifier.fillMaxWidth().testTag("mini-player"), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column {
            Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).heightIn(min = 74.dp).padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).clickable(role = Role.Button, onClickLabel = "Open full player") { actions.showScreen(Screen.PLAYER) }.padding(4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Artwork(song.coverArtId, artworkUrl, Modifier.size(52.dp))
                    Column(Modifier.weight(1f)) { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(session.playlistName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                AudioIcon(if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (state.playing) "Pause" else "Resume playback", Modifier.testTag("mini-player-toggle"), onClick = actions::togglePlay)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}
