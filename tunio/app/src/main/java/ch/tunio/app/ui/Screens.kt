@file:OptIn(ExperimentalLayoutApi::class)

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.tunio.app.data.Library
import ch.tunio.app.data.Source
import ch.tunio.app.data.Track
import ch.tunio.app.engine.AudioStore
import ch.tunio.app.engine.Engine
import ch.tunio.app.engine.EngineStatus
import ch.tunio.app.player.PlayerController
import ch.tunio.app.player.StreamCache
import ch.tunio.app.player.shortError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/** Space at the bottom of every screen so content can scroll out from under the floating glass bars. */
val LocalBottomInset = staticCompositionLocalOf { 0.dp }

private val LikedBrush = Brush.linearGradient(listOf(AccentDeep, Accent2Deep))
private val DownloadsBrush = Brush.linearGradient(listOf(Color(0xFF0284C7), Color(0xFF0A8A62)))
private val SpotifyBrush = Brush.linearGradient(listOf(Color(0xFF1ED760), Color(0xFF0B6B35)))

@Composable
private fun bottomPadding(): PaddingValues = PaddingValues(bottom = LocalBottomInset.current + 12.dp)

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
    LazyColumn(Modifier.fillMaxSize(), contentPadding = bottomPadding()) {
        item {
            ScreenTitle(greeting, Modifier.statusBarsPadding()) {
                GlassIconButton(Icons.Rounded.Settings, "Settings", onClick = { Ui.push(Route.Settings) })
            }
        }
        item { EngineBanner() }
        item {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                maxItemsInEachRow = 2,
            ) {
                QuickTile("Liked songs", "${lib.liked.size} songs", null, Icons.Rounded.Favorite, LikedBrush, Modifier.weight(1f)) {
                    Ui.push(Route.Liked)
                }
                QuickTile("Downloads", "${offline.size} offline", null, Icons.Rounded.DownloadDone, DownloadsBrush, Modifier.weight(1f)) {
                    Ui.push(Route.Downloads)
                }
                lib.playlists.take(4).forEach { p ->
                    QuickTile(
                        p.name, "${p.trackIds.size} songs",
                        p.cover ?: Library.track(p.trackIds.firstOrNull() ?: "")?.thumbnail, null, null, Modifier.weight(1f),
                    ) { Ui.push(Route.PlaylistPage(p.id)) }
                }
            }
        }
        if (recent.isNotEmpty()) {
            item { SectionTitle("Recently played") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    itemsIndexed(recent.take(20), key = { _, t -> t.id }) { i, t ->
                        Column(Modifier.width(140.dp).clip(RoundedCornerShape(20.dp)).clickable { PlayerController.play(recent, i) }) {
                            Artwork(t.thumbnail, 140.dp, 20.dp)
                            Spacer(Modifier.height(8.dp))
                            Text(t.title, color = TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(t.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = Muted)
                        }
                    }
                }
            }
        }
        if (lib.liked.isEmpty() && lib.playlists.isEmpty() && recent.isEmpty()) {
            item {
                GlassCard(Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(Modifier.padding(20.dp)) {
                        Text("Welcome to Tunio", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "• Search YouTube or SoundCloud in the Search tab.\n" +
                                "• Paste a TikTok, Spotify, YouTube or SoundCloud link (song or playlist), " +
                                "or use Share → Tunio from those apps.\n" +
                                "• Tap ⋮ on any song → Download to keep it offline.",
                            color = Muted,
                            lineHeight = 21.sp,
                        )
                        Spacer(Modifier.height(16.dp))
                        AccentButton("Start searching", onClick = { Ui.tab(Route.Search) }, icon = Icons.Rounded.Search)
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickTile(
    title: String,
    subtitle: String,
    image: String?,
    icon: ImageVector?,
    brush: Brush?,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .glass(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (brush != null && icon != null) GradientTile(icon, brush, 48.dp, 12.dp) else Artwork(image, 48.dp, 12.dp)
        Column(Modifier.padding(horizontal = 10.dp)) {
            Text(title, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
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
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), corner = 20.dp) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (status !is EngineStatus.Failed) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = AccentLight)
            Spacer(Modifier.width(12.dp))
            Text(text, fontSize = 13.sp, color = if (status is EngineStatus.Failed) ErrorText else Muted)
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

    LazyColumn(Modifier.fillMaxSize(), contentPadding = bottomPadding()) {
        item { ScreenTitle("Search", Modifier.statusBarsPadding()) }
        item {
            GlassTextField(
                value = query,
                onValueChange = { SearchModel.query.value = it },
                placeholder = "Songs, artists, or paste a link",
                leading = if (isLink(query)) Icons.Rounded.Link else Icons.Rounded.Search,
                onSearch = { SearchModel.submit() },
                trailing = {
                    if (query.isNotEmpty()) {
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).clickable { SearchModel.query.value = "" },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.Clear, "Clear", tint = Muted) }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
        item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Source.YOUTUBE, Source.SOUNDCLOUD).forEach { s ->
                    GlassChip(s.label, selected = source == s, color = sourceColor(s)) {
                        SearchModel.source.value = s
                        if (query.isNotBlank() && !isLink(query)) SearchModel.submit()
                    }
                }
            }
        }
        if (result == null && !loading && error == null) {
            item {
                GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), corner = 20.dp) {
                    Text(
                        "TikTok & Spotify: paste a link, or use Share → Tunio in their apps. Spotify songs play from YouTube.\n" +
                            "Private Spotify playlists: Library → Spotify. You can also paste many Spotify song links at once.",
                        color = Muted, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
        if (loading) {
            item {
                Row(Modifier.fillMaxWidth().padding(28.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = AccentLight)
                    Spacer(Modifier.width(12.dp))
                    Text(if (fromLink) "Reading link…" else "Searching…", color = Muted)
                }
            }
        }
        error?.let { e -> item { Text(e, color = ErrorText, modifier = Modifier.padding(20.dp)) } }
        val r = result
        if (r != null && r.tracks.isNotEmpty()) {
            if (fromLink && r.tracks.size > 1) {
                item {
                    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Artwork(r.cover ?: r.tracks.first().thumbnail, 76.dp, 16.dp)
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(r.title ?: "Imported list", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text("${r.tracks.size} songs", color = Muted, fontSize = 13.sp)
                                }
                            }
                            Spacer(Modifier.height(14.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                AccentButton("Play", onClick = { PlayerController.play(r.tracks, 0, shuffled = false) }, icon = Icons.Rounded.PlayArrow)
                                GlassButton("Save as playlist", icon = Icons.AutoMirrored.Rounded.PlaylistAdd, onClick = {
                                    val p = Library.createPlaylist(r.title ?: "Imported", r.tracks, r.sourceUrl, r.cover)
                                    PlayerController.messages.tryEmit("Saved as playlist \"${p.name}\"")
                                    Ui.push(Route.PlaylistPage(p.id))
                                })
                            }
                        }
                    }
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
    LazyColumn(Modifier.fillMaxSize(), contentPadding = bottomPadding()) {
        item {
            ScreenTitle("Your Library", Modifier.statusBarsPadding()) {
                GlassIconButton(Icons.Rounded.Add, "New playlist", onClick = { creating = true })
            }
        }
        item {
            GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    LibraryRow("Liked songs", "${lib.liked.size} songs", null, LikedBrush, Icons.Rounded.Favorite) { Ui.push(Route.Liked) }
                    LibraryRow("Downloads", "${offline.size} songs available offline", null, DownloadsBrush, Icons.Rounded.DownloadDone) { Ui.push(Route.Downloads) }
                    LibraryRow("Spotify", "Import your playlists (private ones too)", null, SpotifyBrush, Icons.Rounded.CloudDownload) {
                        Ui.push(Route.SpotifyImport)
                    }
                }
            }
        }
        if (lib.playlists.isNotEmpty()) item { SectionTitle("Playlists") }
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
private fun LibraryRow(title: String, subtitle: String, image: String?, brush: Brush?, icon: ImageVector?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (brush != null && icon != null) GradientTile(icon, brush, 58.dp, 14.dp) else Artwork(image, 58.dp, 14.dp)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp)
            Text(subtitle, color = Muted, fontSize = 13.sp)
        }
    }
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

    LazyColumn(Modifier.fillMaxSize(), contentPadding = bottomPadding()) {
        item {
            Column(Modifier.fillMaxWidth().statusBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = { Ui.back() })
                    Spacer(Modifier.weight(1f))
                    if (playlist != null) {
                        GlassIconButton(Icons.Rounded.Edit, "Rename", onClick = { renaming = true })
                        GlassIconButton(Icons.Rounded.Delete, "Delete", onClick = { confirmDelete = true })
                    }
                }
                Spacer(Modifier.height(4.dp))
                when (route) {
                    Route.Liked -> GradientTile(Icons.Rounded.Favorite, LikedBrush, 200.dp, 28.dp)
                    Route.Downloads -> GradientTile(Icons.Rounded.DownloadDone, DownloadsBrush, 200.dp, 28.dp)
                    else -> Artwork(cover, 200.dp, 28.dp)
                }
                Spacer(Modifier.height(18.dp))
                Text(
                    title, color = TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp),
                )
                Text("${tracks.size} songs", color = Muted, fontSize = 13.sp)
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (route != Route.Downloads) {
                    when {
                        missing == 0 && tracks.isNotEmpty() -> {
                            Icon(Icons.Rounded.DownloadDone, "All downloaded", tint = Good)
                            Spacer(Modifier.width(6.dp))
                            Text("Available offline", color = Good, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                        downloading > 0 -> {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = AccentLight)
                            Spacer(Modifier.width(8.dp))
                            Text("Downloading $downloading…", color = Muted, fontSize = 13.sp)
                        }
                        tracks.isNotEmpty() -> GlassButton("Download $missing", icon = Icons.Rounded.Download, onClick = { AudioStore.downloadAll(tracks) })
                    }
                }
                Spacer(Modifier.weight(1f))
                GlassIconButton(Icons.Rounded.Shuffle, "Shuffle", onClick = { PlayerController.play(tracks, 0, shuffled = true) }, size = 48.dp, tint = AccentLight, enabled = tracks.isNotEmpty())
                Spacer(Modifier.width(12.dp))
                PlayCircle(enabled = tracks.isNotEmpty(), size = 60.dp) { PlayerController.play(tracks, 0, shuffled = false) }
            }
        }
        if (tracks.isEmpty()) {
            item {
                GlassCard(Modifier.fillMaxWidth().padding(16.dp), corner = 20.dp) {
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
        }
        itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
            TrackRow(t, onClick = { PlayerController.play(tracks, i, shuffled = false) }, playlistId = playlist?.id)
        }
    }

    if (renaming && playlist != null) {
        NameDialog("Rename playlist", playlist.name, onDismiss = { renaming = false }) { renaming = false; Library.renamePlaylist(playlist.id, it) }
    }
    if (confirmDelete && playlist != null) {
        GlassDialog(
            title = "Delete \"${playlist.name}\"?",
            onDismiss = { confirmDelete = false },
            confirm = {
                TextButton(onClick = { confirmDelete = false; Library.deletePlaylist(playlist.id) }) {
                    Text("Delete", color = ErrorText, fontWeight = FontWeight.Bold)
                }
            },
        ) { Text("Downloaded songs stay in Downloads.", color = Muted) }
    }
}

/** The big round accent play button. */
@Composable
fun PlayCircle(enabled: Boolean = true, size: Dp = 60.dp, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (enabled) AccentGradient else Brush.linearGradient(listOf(Glass.fill, Glass.fill)))
            .background(Glass.sheen)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Rounded.PlayArrow, "Play", tint = if (enabled) Color.White else Muted, modifier = Modifier.size(size * 0.55f)) }
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
            cacheBytes = AudioStore.sizeOf(AudioStore.cacheDir) + StreamCache.size()
            offlineBytes = AudioStore.sizeOf(AudioStore.offlineDir)
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(bottom = LocalBottomInset.current + 12.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = { Ui.back() })
            Spacer(Modifier.width(14.dp))
            Text("Settings", style = androidx.compose.material3.MaterialTheme.typography.headlineMedium, color = TextPrimary)
        }
        SettingsCard("Music engine (yt-dlp)") {
            Text("Version: ${version ?: "unknown"}", color = Muted)
            Text(
                "YouTube, SoundCloud and TikTok change often. If songs stop loading, update yt-dlp. It also updates itself once a day.",
                color = Muted, fontSize = 13.sp, lineHeight = 19.sp,
            )
            Spacer(Modifier.height(10.dp))
            AccentButton(
                if (status == EngineStatus.Updating) "Updating…" else "Update yt-dlp now",
                enabled = status == EngineStatus.Ready,
                onClick = {
                    updateMsg = "Updating…"
                    scope.launch { updateMsg = runCatching { Engine.update() }.getOrElse { "Update failed: ${shortError(it)}" } }
                },
            )
            updateMsg?.let { Text(it, color = Muted, fontSize = 13.sp) }
            if (status is EngineStatus.Failed) {
                Spacer(Modifier.height(8.dp))
                GlassButton("Retry starting engine", onClick = { Engine.start(context) })
            }
        }
        SettingsCard("Autoplay") {
            val autoplay by PlayerController.autoplay.collectAsState()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "When 3 or fewer songs are left and you're on Wi-Fi, add similar songs (like Spotify's autoplay) " +
                        "and load the next songs in the background.",
                    color = Muted, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = autoplay,
                    onCheckedChange = { PlayerController.setAutoplay(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = AccentDeep,
                        checkedBorderColor = Color.Transparent,
                        uncheckedThumbColor = Muted,
                        uncheckedTrackColor = Glass.fill,
                        uncheckedBorderColor = Muted,
                    ),
                )
            }
        }
        SettingsCard("Spotify account") { SpotifyAccountCard() }
        SettingsCard("Extra yt-dlp options") {
            Text("Advanced. Added to every yt-dlp call, e.g. --extractor-args \"youtube:player_client=default\"", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = extra, onValueChange = { extra = it }, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("(none)") }, colors = glassFieldColors(), shape = RoundedCornerShape(16.dp),
            )
            Spacer(Modifier.height(8.dp))
            GlassButton("Save", onClick = { Engine.extraArgs = extra; updateMsg = "Options saved" })
        }
        SettingsCard("Storage") {
            Text("Downloaded (offline): ${mb(offlineBytes)}", color = TextPrimary)
            Text("Location: ${AudioStore.offlineDir.absolutePath}", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Text("Playback cache: ${mb(cacheBytes)}", color = TextPrimary)
            Text("Songs you've played, so replays are instant. The oldest are removed automatically.", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            GlassButton("Clear cache", icon = Icons.Rounded.Delete, onClick = { AudioStore.clearCache(); StreamCache.clear(); refresh++ })
        }
        SettingsCard("About") {
            Text(
                "Tunio — personal music player. Audio comes from YouTube, SoundCloud and TikTok through yt-dlp " +
                    "(packaged by the youtubedl-android project used by Seal). Spotify links are read for song info only " +
                    "and matched to YouTube.",
                color = Muted, fontSize = 13.sp, lineHeight = 19.sp,
            )
        }
    }
}

private fun mb(b: Long) = "%.1f MB".format(b / 1024.0 / 1024.0)

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}
