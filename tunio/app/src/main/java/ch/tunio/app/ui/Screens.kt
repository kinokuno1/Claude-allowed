@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package ch.tunio.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.tunio.app.data.Library
import ch.tunio.app.data.Source
import ch.tunio.app.data.Track
import ch.tunio.app.engine.AudioStore
import ch.tunio.app.engine.Engine
import ch.tunio.app.engine.EngineStatus
import ch.tunio.app.player.PlayerController
import ch.tunio.app.player.shortError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

// ---------------------------------------------------------------- Home

@Composable
fun HomeScreen() {
    val lib by Library.data.collectAsState()
    val offline by AudioStore.offline.collectAsState()
    val recent = remember(lib) { Library.tracks(lib.recent) }
    val greeting = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        else -> "Good evening"
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(greeting, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { Ui.push(Route.Settings) }) { Icon(Icons.Filled.Settings, "Settings") }
            }
        }
        item { EngineBanner() }
        item {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = 2,
            ) {
                QuickTile("Liked songs", "${lib.liked.size} songs", null, Brush.linearGradient(listOf(Accent, Accent2)), Modifier.weight(1f)) {
                    Ui.push(Route.Liked)
                }
                QuickTile("Downloads", "${offline.size} offline", null, Brush.linearGradient(listOf(Color(0xFF0EA5E9), Good)), Modifier.weight(1f)) {
                    Ui.push(Route.Downloads)
                }
                lib.playlists.take(4).forEach { p ->
                    QuickTile(p.name, "${p.trackIds.size} songs", p.cover ?: Library.track(p.trackIds.firstOrNull() ?: "")?.thumbnail, null, Modifier.weight(1f)) {
                        Ui.push(Route.PlaylistPage(p.id))
                    }
                }
            }
        }
        if (recent.isNotEmpty()) {
            item { SectionTitle("Recently played") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(recent.take(20), key = { _, t -> t.id }) { i, t ->
                        Column(Modifier.width(130.dp).clickable { PlayerController.play(recent, i) }) {
                            Artwork(t.thumbnail, 130.dp, 8.dp)
                            Spacer(Modifier.height(6.dp))
                            Text(t.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(t.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = Muted)
                        }
                    }
                }
            }
        }
        if (lib.liked.isEmpty() && lib.playlists.isEmpty() && recent.isEmpty()) {
            item {
                Card(
                    Modifier.fillMaxWidth().padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Surface1),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Welcome to Tunio", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "• Search YouTube or SoundCloud in the Search tab.\n" +
                                "• Paste a TikTok, Spotify, YouTube or SoundCloud link (song or playlist), " +
                                "or use Share → Tunio from those apps.\n" +
                                "• Tap ⋮ on any song → Download to keep it offline.",
                            color = Muted,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { Ui.tab(Route.Search) }) { Text("Start searching") }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickTile(title: String, subtitle: String, image: String?, brush: Brush?, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Surface2)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (brush != null) {
            Box(Modifier.size(56.dp).background(brush), contentAlignment = Alignment.Center) {
                Icon(if (title == "Downloads") Icons.Filled.DownloadDone else Icons.Filled.Favorite, null, tint = Color.White)
            }
        } else {
            Artwork(image, 56.dp, 0.dp)
        }
        Column(Modifier.padding(horizontal = 10.dp)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(subtitle, maxLines = 1, fontSize = 11.sp, color = Muted)
        }
    }
}

@Composable
fun EngineBanner() {
    val status by Engine.status.collectAsState()
    val text = when (val s = status) {
        EngineStatus.Starting -> "Setting up the music engine… (the first launch takes a little while)"
        EngineStatus.Updating -> "Updating yt-dlp…"
        is EngineStatus.Failed -> "Engine failed to start: ${s.message}"
        EngineStatus.Ready -> return
    }
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = Surface1),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (status !is EngineStatus.Failed) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(text, fontSize = 13.sp, color = Muted)
        }
    }
}

// ---------------------------------------------------------------- Search

@Composable
fun SearchScreen() {
    val query by SearchModel.query.collectAsState()
    val source by SearchModel.source.collectAsState()
    val loading by SearchModel.loading.collectAsState()
    val result by SearchModel.result.collectAsState()
    val error by SearchModel.error.collectAsState()
    val fromLink by SearchModel.fromLink.collectAsState()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Text("Search", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 12.dp))
        }
        item {
            TextField(
                value = query,
                onValueChange = { SearchModel.query.value = it },
                placeholder = { Text("Songs, artists, or paste a link") },
                leadingIcon = { Icon(if (isLink(query)) Icons.Filled.Link else Icons.Filled.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { SearchModel.query.value = "" }) { Icon(Icons.Filled.Clear, "Clear") }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { SearchModel.submit() }),
                shape = RoundedCornerShape(10.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White,
                    focusedTextColor = Color.Black,
                    unfocusedTextColor = Color.Black,
                    focusedLeadingIconColor = Color.Black,
                    unfocusedLeadingIconColor = Color.DarkGray,
                    focusedTrailingIconColor = Color.Black,
                    unfocusedTrailingIconColor = Color.DarkGray,
                    focusedPlaceholderColor = Color.Gray,
                    unfocusedPlaceholderColor = Color.Gray,
                    cursorColor = Accent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
        item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Source.YOUTUBE, Source.SOUNDCLOUD).forEach { s ->
                    FilterChip(
                        selected = source == s,
                        onClick = {
                            SearchModel.source.value = s
                            if (query.isNotBlank() && !isLink(query)) SearchModel.submit()
                        },
                        label = { Text(s.label) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = sourceColor(s), selectedLabelColor = Color.Black),
                    )
                }
            }
        }
        item {
            Text(
                "TikTok & Spotify: paste a link here, or use Share → Tunio in their apps. Spotify songs are played from YouTube.",
                color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (loading) {
            item {
                Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(if (fromLink) "Reading link…" else "Searching…", color = Muted)
                }
            }
        }
        error?.let { e ->
            item { Text(e, color = Color(0xFFF87171), modifier = Modifier.padding(16.dp)) }
        }
        val r = result
        if (r != null && r.tracks.isNotEmpty()) {
            if (fromLink && r.tracks.size > 1) {
                item {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Artwork(r.cover ?: r.tracks.first().thumbnail, 72.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.title ?: "Imported list", fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${r.tracks.size} songs", color = Muted, fontSize = 13.sp)
                        }
                    }
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { PlayerController.play(r.tracks, 0, shuffled = false) }) {
                            Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text("Play")
                        }
                        OutlinedButton(onClick = {
                            val p = Library.createPlaylist(r.title ?: "Imported", r.tracks, r.sourceUrl, r.cover)
                            PlayerController.messages.tryEmit("Saved as playlist \"${p.name}\"")
                            Ui.push(Route.PlaylistPage(p.id))
                        }) {
                            Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null); Spacer(Modifier.width(4.dp)); Text("Save as playlist")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            itemsIndexed(r.tracks, key = { i, t -> "$i-${t.id}" }) { i, t ->
                TrackRow(t, onClick = {
                    // A search result plays on its own; a playlist link plays as a list.
                    if (fromLink) PlayerController.play(r.tracks, i, shuffled = false) else PlayerController.play(listOf(t))
                })
            }
        }
    }
}

// ---------------------------------------------------------------- Library

@Composable
fun LibraryScreen() {
    val lib by Library.data.collectAsState()
    val offline by AudioStore.offline.collectAsState()
    var creating by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 20.dp, end = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Your Library", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { creating = true }) { Icon(Icons.Filled.Add, "New playlist") }
            }
        }
        item {
            LibraryRow("Liked songs", "${lib.liked.size} songs", null, Brush.linearGradient(listOf(Accent, Accent2)), Icons.Filled.Favorite) { Ui.push(Route.Liked) }
        }
        item {
            LibraryRow("Downloads", "${offline.size} songs available offline", null, Brush.linearGradient(listOf(Color(0xFF0EA5E9), Good)), Icons.Filled.DownloadDone) { Ui.push(Route.Downloads) }
        }
        items(lib.playlists, key = { it.id }) { p ->
            LibraryRow(p.name, "Playlist · ${p.trackIds.size} songs", p.cover ?: Library.track(p.trackIds.firstOrNull() ?: "")?.thumbnail, null, null) {
                Ui.push(Route.PlaylistPage(p.id))
            }
        }
    }
    if (creating) {
        NameDialog("New playlist", "", onDismiss = { creating = false }) { name ->
            creating = false
            val p = Library.createPlaylist(name)
            Ui.push(Route.PlaylistPage(p.id))
        }
    }
}

@Composable
private fun LibraryRow(title: String, subtitle: String, image: String?, brush: Brush?, icon: androidx.compose.ui.graphics.vector.ImageVector?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (brush != null && icon != null) {
            Box(Modifier.size(60.dp).clip(RoundedCornerShape(6.dp)).background(brush), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Color.White)
            }
        } else {
            Artwork(image, 60.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Muted, fontSize = 13.sp)
        }
    }
}

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { TextButton(onClick = { onConfirm(name.trim()) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------- Track collections

@Composable
fun CollectionScreen(route: Route) {
    val lib by Library.data.collectAsState()
    val offline by AudioStore.offline.collectAsState()
    val progress by AudioStore.progress.collectAsState()
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val playlist = (route as? Route.PlaylistPage)?.let { r -> lib.playlists.find { it.id == r.id } }
    if (route is Route.PlaylistPage && playlist == null) {
        LaunchedEffect(Unit) { Ui.back() }
        return
    }
    val title = when (route) {
        Route.Liked -> "Liked songs"
        Route.Downloads -> "Downloads"
        else -> playlist?.name ?: ""
    }
    val tracks: List<Track> = remember(lib, offline, route) {
        when (route) {
            Route.Liked -> Library.tracks(lib.liked)
            Route.Downloads -> lib.tracks.values.filter { it.id in offline }.sortedBy { it.title.lowercase() }
            else -> Library.tracks(playlist?.trackIds.orEmpty())
        }
    }
    val cover = playlist?.cover ?: tracks.firstOrNull()?.thumbnail
    val missing = tracks.count { it.id !in offline }
    val downloading = tracks.count { it.id in progress }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Box(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Accent.copy(alpha = 0.45f), Bg))),
            ) {
                Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth()) {
                        IconButton(onClick = { Ui.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                        Spacer(Modifier.weight(1f))
                        if (playlist != null) {
                            IconButton(onClick = { renaming = true }) { Icon(Icons.Filled.Edit, "Rename") }
                            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete") }
                        }
                    }
                    Artwork(cover, 180.dp, 8.dp)
                    Spacer(Modifier.height(14.dp))
                    Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp))
                    Text("${tracks.size} songs", color = Muted, fontSize = 13.sp)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (route != Route.Downloads) {
                    if (missing == 0 && tracks.isNotEmpty()) {
                        Icon(Icons.Filled.DownloadDone, "All downloaded", tint = Good)
                        Spacer(Modifier.width(6.dp))
                        Text("Available offline", color = Good, fontSize = 13.sp)
                    } else if (downloading > 0) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Downloading $downloading…", color = Muted, fontSize = 13.sp)
                    } else if (tracks.isNotEmpty()) {
                        OutlinedButton(onClick = { AudioStore.downloadAll(tracks) }) {
                            Icon(Icons.Filled.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Download $missing")
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { PlayerController.play(tracks, 0, shuffled = true) }, enabled = tracks.isNotEmpty()) {
                    Icon(Icons.Filled.Shuffle, "Shuffle", tint = Accent)
                }
                FilledIconButton(
                    onClick = { PlayerController.play(tracks, 0, shuffled = false) },
                    enabled = tracks.isNotEmpty(),
                    modifier = Modifier.size(52.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Accent),
                ) { Icon(Icons.Filled.PlayArrow, "Play", Modifier.size(30.dp)) }
            }
        }
        if (tracks.isEmpty()) {
            item {
                Text(
                    when (route) {
                        Route.Liked -> "Songs you like appear here. Tap ⋮ → Like on any song."
                        Route.Downloads -> "Nothing downloaded yet. Tap ⋮ → Download on a song, or Download on a playlist."
                        else -> "This playlist is empty. Add songs from Search with ⋮ → Add to playlist."
                    },
                    color = Muted, modifier = Modifier.padding(16.dp),
                )
            }
        }
        itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
            TrackRow(t, onClick = { PlayerController.play(tracks, i, shuffled = false) }, playlistId = playlist?.id)
        }
    }

    if (renaming && playlist != null) {
        NameDialog("Rename playlist", playlist.name, onDismiss = { renaming = false }) { renaming = false; Library.renamePlaylist(playlist.id, it) }
    }
    if (confirmDelete && playlist != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete \"${playlist.name}\"?") },
            text = { Text("Downloaded songs stay in Downloads.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; Library.deletePlaylist(playlist.id) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------- Settings

@Composable
fun SettingsScreen() {
    val status by Engine.status.collectAsState()
    val version by Engine.version.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var updateMsg by remember { mutableStateOf<String?>(null) }
    var extra by remember { mutableStateOf(Engine.extraArgs) }
    var cacheBytes by remember { mutableLongStateOf(0L) }
    var offlineBytes by remember { mutableLongStateOf(0L) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) {
        withContext(Dispatchers.IO) {
            cacheBytes = AudioStore.sizeOf(AudioStore.cacheDir)
            offlineBytes = AudioStore.sizeOf(AudioStore.offlineDir)
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = { IconButton(onClick = { Ui.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg),
        )
        SettingsCard("Music engine (yt-dlp)") {
            Text("Version: ${version ?: "unknown"}", color = Muted)
            Text(
                "YouTube, SoundCloud and TikTok change often. If songs stop loading, update yt-dlp. It also updates itself once a day.",
                color = Muted, fontSize = 13.sp,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                enabled = status == EngineStatus.Ready,
                onClick = {
                    updateMsg = "Updating…"
                    scope.launch { updateMsg = runCatching { Engine.update() }.getOrElse { "Update failed: ${shortError(it)}" } }
                },
            ) { Text(if (status == EngineStatus.Updating) "Updating…" else "Update yt-dlp now") }
            updateMsg?.let { Text(it, color = Muted, fontSize = 13.sp) }
            if (status is EngineStatus.Failed) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { Engine.start(context) }) { Text("Retry starting engine") }
            }
        }
        SettingsCard("Extra yt-dlp options") {
            Text("Advanced. Added to every yt-dlp call, e.g. --extractor-args \"youtube:player_client=default\"", color = Muted, fontSize = 13.sp)
            OutlinedTextField(value = extra, onValueChange = { extra = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("(none)") })
            TextButton(onClick = { Engine.extraArgs = extra; updateMsg = "Options saved" }) { Text("Save") }
        }
        SettingsCard("Storage") {
            Text("Downloaded (offline): ${mb(offlineBytes)}", color = Muted)
            Text("Location: ${AudioStore.offlineDir.absolutePath}", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Text("Playback cache: ${mb(cacheBytes)} (songs you've played, cleared automatically above 600 MB)", color = Muted)
            TextButton(onClick = { AudioStore.clearCache(); refresh++ }) { Text("Clear cache") }
        }
        SettingsCard("About") {
            Text(
                "Tunio — personal music player. Audio comes from YouTube, SoundCloud and TikTok through yt-dlp " +
                    "(packaged by the youtubedl-android project used by Seal). Spotify links are read for song info only " +
                    "and matched to YouTube.",
                color = Muted, fontSize = 13.sp,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun mb(b: Long) = "%.1f MB".format(b / 1024.0 / 1024.0)

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = Surface1),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            content()
        }
    }
}
