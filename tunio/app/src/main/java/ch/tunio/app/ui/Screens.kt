@file:OptIn(ExperimentalLayoutApi::class)

package ch.tunio.app.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
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
import androidx.compose.ui.graphics.SolidColor
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
import ch.tunio.app.engine.HomeServer
import ch.tunio.app.player.PlayerController
import ch.tunio.app.player.StreamCache
import ch.tunio.app.player.shortError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/** Space at the bottom of every screen so content can scroll out from under the floating glass bars. */
val LocalBottomInset = staticCompositionLocalOf { 0.dp }

/** Flat colours for the built-in collections (same as the iPhone and Windows apps). */
val LikedBrush: Brush = SolidColor(LikedColor)
val DownloadsBrush: Brush = SolidColor(DownloadsColor)
val RecentBrush: Brush = SolidColor(Surface30Hi)
val SpotifyBrush: Brush = SolidColor(Color(0xFF1DB954))

@Composable
private fun bottomPadding(): PaddingValues = PaddingValues(bottom = LocalBottomInset.current + 12.dp)

// ---------------------------------------------------------------- shared pieces (iPhone style)

/** Big bold page title, like iOS's large navigation titles. */
@Composable
fun LargeTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, color = TextPrimary, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.6).sp,
        modifier = modifier.padding(horizontal = 16.dp).padding(top = 2.dp, bottom = 12.dp),
    )
}

/** Small top bar: an optional "‹ Back" on the left, actions on the right. */
@Composable
fun NavBar(backLabel: String? = null, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().height(48.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (backLabel != null) {
            Row(
                Modifier.clip(RoundedCornerShape(10.dp)).clickable { Ui.back() }.padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = AccentLight, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(4.dp))
                Text(backLabel, color = AccentLight, fontSize = 17.sp)
            }
        }
        Spacer(Modifier.weight(1f))
        actions()
    }
}

/** The three moving bars next to the song that's playing. */
@Composable
fun PlayingBars(playing: Boolean, color: Color = AccentLight) {
    val t = rememberInfiniteTransition(label = "bars")
    Row(Modifier.size(14.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        repeat(3) { i ->
            val h by t.animateFloat(
                initialValue = 0.25f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(420 + i * 130, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bar$i",
            )
            Box(Modifier.width(3.dp).fillMaxHeight(if (playing) h else 0.3f).clip(RoundedCornerShape(1.dp)).background(color))
        }
    }
}

/** The round white play button (white with a dark icon, like the iPhone app). */
@Composable
fun PlayCircle(enabled: Boolean = true, size: Dp = 56.dp, playing: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (enabled) Color.White else Glass.fill)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play",
            tint = if (enabled) Bg else Muted, modifier = Modifier.size(size * 0.55f),
        )
    }
}

/** Grey rounded group of rows, like iOS settings. */
@Composable
fun Group(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(12.dp)).background(Surface30)) { content() }
}

@Composable
fun GroupHeader(text: String) {
    Text(text.uppercase(), color = Muted, fontSize = 13.sp, letterSpacing = 0.4.sp, modifier = Modifier.padding(start = 32.dp, top = 22.dp, bottom = 6.dp))
}

@Composable
fun GroupFooter(text: String) {
    Text(text, color = Muted, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 6.dp))
}

@Composable
fun GroupDivider() = HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(start = 16.dp))

@Composable
fun FlatSwitch(checked: Boolean, onChange: (Boolean) -> Unit) = Switch(
    checked = checked, onCheckedChange = onChange,
    colors = SwitchDefaults.colors(
        checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF34C759), checkedBorderColor = Color.Transparent,
        uncheckedThumbColor = Color.White, uncheckedTrackColor = Color(0x52787880), uncheckedBorderColor = Color.Transparent,
    ),
)

/** Is this list what's playing right now? (Its song is current and the queue started from it.) */
@Composable
private fun listIsPlaying(tracks: List<Track>): Pair<Boolean, Boolean> {
    val queue by PlayerController.queue.collectAsState()
    val index by PlayerController.index.collectAsState()
    val playing by PlayerController.isPlaying.collectAsState()
    val current = queue.getOrNull(index)
    val isThis = current != null && tracks.any { it.id == current.id } && queue.take(3).any { q -> tracks.any { it.id == q.id } }
    return isThis to (isThis && playing)
}

// ---------------------------------------------------------------- Home

@Composable
fun HomeScreen() {
    val lib by Library.data.collectAsState()
    val offline by AudioStore.offline.collectAsState()
    val home by HomeServer.state.collectAsState()
    val recent = remember(lib) { Library.tracks(lib.recent).take(15) }
    val greeting = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        else -> "Good evening"
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = bottomPadding()) {
        item {
            NavBar {
                GlassIconButton(Icons.Rounded.Settings, "Settings", onClick = { Ui.push(Route.Settings) }, size = 40.dp, tint = AccentLight)
            }
            LargeTitle(greeting)
        }
        item { EngineBanner() }
        item {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = 2,
            ) {
                QuickTile("Liked songs", null, Icons.Rounded.Favorite, LikedBrush, Modifier.weight(1f)) { Ui.push(Route.Liked) }
                QuickTile("Downloads", null, Icons.Rounded.DownloadDone, DownloadsBrush, Modifier.weight(1f)) { Ui.push(Route.Downloads) }
                lib.playlists.take(6).forEach { p ->
                    QuickTile(p.name, p.cover ?: Library.track(p.trackIds.firstOrNull() ?: "")?.thumbnail, null, null, Modifier.weight(1f)) {
                        Ui.push(Route.PlaylistPage(p.id))
                    }
                }
            }
        }
        if (recent.isNotEmpty()) {
            item { ShelfHeader("Recently played") { Ui.push(Route.Recent) } }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    itemsIndexed(recent, key = { _, t -> t.id }) { i, t ->
                        Card(t.thumbnail, t.title, t.artist.ifBlank { t.source.label }) { PlayerController.play(recent, i, shuffled = false) }
                    }
                }
            }
        }
        if (lib.playlists.isNotEmpty()) {
            item { ShelfHeader("Your playlists", null) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(lib.playlists, key = { it.id }) { p ->
                        Card(p.cover ?: Library.track(p.trackIds.firstOrNull() ?: "")?.thumbnail, p.name, "Playlist · ${p.trackIds.size} songs") {
                            Ui.push(Route.PlaylistPage(p.id))
                        }
                    }
                }
            }
        }
        if (lib.liked.isEmpty() && lib.playlists.isEmpty() && recent.isEmpty()) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(14.dp)).background(Surface30).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Welcome to Tunio", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text(
                        "Search YouTube or SoundCloud, or paste a YouTube, SoundCloud, TikTok or Spotify link (song, playlist or album) in Search. " +
                            "You can also use Share → Tunio in those apps.\n\nTap ⋯ on any song to download it for offline listening.",
                        color = Muted, lineHeight = 21.sp,
                    )
                    if (home == HomeServer.State.NotSetUp) {
                        Text(
                            "Have a Tunio home server? Connect it", color = AccentLight, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable { Ui.push(Route.Settings) }.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShelfHeader(title: String, onMore: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
        if (onMore != null) Text("Show all", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onMore))
    }
}

@Composable
private fun Card(image: String?, title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.width(140.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)) {
        Artwork(image, 140.dp, 8.dp)
        Spacer(Modifier.height(6.dp))
        Text(title, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = Muted)
    }
}

@Composable
private fun QuickTile(title: String, image: String?, icon: ImageVector?, brush: Brush?, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(56.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.08f)).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (brush != null && icon != null) GradientTile(icon, brush, 56.dp, 0.dp) else Artwork(image, 56.dp, 0.dp)
        Text(
            title, color = TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, fontSize = 14.sp,
            lineHeight = 17.sp, modifier = Modifier.padding(horizontal = 10.dp),
        )
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
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(12.dp)).background(Surface30).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (status !is EngineStatus.Failed) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = TextPrimary)
        Spacer(Modifier.width(12.dp))
        Text(text, fontSize = 13.sp, color = if (status is EngineStatus.Failed) ErrorText else Muted)
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
    val context = LocalContext.current

    LazyColumn(Modifier.fillMaxSize(), contentPadding = bottomPadding()) {
        item {
            Spacer(Modifier.statusBarsPadding().height(8.dp))
            LargeTitle("Search")
            SearchField(query)
            Segmented(
                listOf("YouTube", "SoundCloud"), if (source == Source.SOUNDCLOUD) 1 else 0,
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            ) { i ->
                SearchModel.source.value = if (i == 1) Source.SOUNDCLOUD else Source.YOUTUBE
                if (query.isNotBlank() && !isLink(query)) SearchModel.submit()
            }
        }
        if (result == null && !loading && error == null) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(Surface30).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Search, null, tint = TextPrimary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Search YouTube or SoundCloud", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    Text(
                        "Or paste a link: YouTube, SoundCloud, TikTok or Spotify (song, playlist or album). Spotify songs play from YouTube. " +
                            "Private Spotify playlists: Library → Spotify.",
                        color = Muted, fontSize = 14.sp, lineHeight = 19.sp,
                    )
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(Surface30Hi).clickable {
                            val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                            val text = clip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                            if (text.isNotBlank()) SearchModel.openLink(text)
                        }.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.ContentPaste, null, tint = TextPrimary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Paste", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                }
            }
        }
        if (loading) {
            item {
                Row(Modifier.fillMaxWidth().padding(34.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = TextPrimary)
                    Spacer(Modifier.width(12.dp))
                    Text(if (fromLink) "Reading link…" else "Searching…", color = Muted)
                }
            }
        }
        error?.let { e -> item { Text(e, color = ErrorText, modifier = Modifier.padding(20.dp)) } }
        val r = result
        if (r != null && r.tracks.isNotEmpty()) {
            val asList = fromLink && r.tracks.size > 1
            if (asList) {
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Artwork(r.cover ?: r.tracks.first().thumbnail, 96.dp, 8.dp)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Imported list", color = Muted, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text(r.title ?: "Imported list", color = TextPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val coversLeft by SearchModel.coversLeft.collectAsState()
                                Text("${r.tracks.size} songs" + if (coversLeft > 0) " · getting covers ($coversLeft left)…" else "", color = Muted, fontSize = 13.sp)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            PlayCircle(size = 50.dp) { PlayerController.play(r.tracks, 0, shuffled = false) }
                            Icon(
                                Icons.Rounded.Shuffle, "Shuffle", tint = TextPrimary,
                                modifier = Modifier.size(44.dp).clip(CircleShape).clickable { PlayerController.play(r.tracks, 0, shuffled = true) }.padding(10.dp),
                            )
                            Spacer(Modifier.weight(1f))
                            GlassButton("Save as playlist", icon = Icons.AutoMirrored.Rounded.PlaylistAdd, onClick = {
                                val p = Library.createPlaylist(r.title ?: "Imported", r.tracks, r.sourceUrl, r.cover)
                                PlayerController.messages.tryEmit("Saved as playlist \"${p.name}\"")
                            })
                        }
                    }
                }
            }
            itemsIndexed(r.tracks, key = { i, t -> "$i-${t.id}" }) { i, t ->
                TrackRow(t, number = if (asList) i + 1 else null, onClick = {
                    // A search result plays on its own (autoplay adds similar songs); a playlist link plays as a list.
                    if (fromLink) PlayerController.play(r.tracks, i, shuffled = false) else PlayerController.play(listOf(t))
                })
            }
        }
    }
}

@Composable
private fun SearchField(query: String) {
    GlassTextField(
        value = query,
        onValueChange = { SearchModel.query.value = it },
        placeholder = "Songs, artists, or paste a link",
        leading = if (isLink(query)) Icons.Rounded.Link else Icons.Rounded.Search,
        onSearch = { SearchModel.submit() },
        trailing = {
            if (query.isNotEmpty()) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).clickable { SearchModel.query.value = "" },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Clear, "Clear", tint = Muted, modifier = Modifier.size(20.dp)) }
            }
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
}

/** iOS-style segmented control: the light pill slides to the chosen option. */
@Composable
fun Segmented(options: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    BoxWithConstraints(modifier.fillMaxWidth().height(32.dp).clip(RoundedCornerShape(9.dp)).background(Color(0x3D767680)).padding(2.dp)) {
        val w = maxWidth / options.size
        val x by animateFloatAsState(selected.toFloat(), spring(dampingRatio = 0.75f, stiffness = 500f), label = "seg")
        Box(Modifier.offset(x = w * x).width(w).fillMaxHeight().clip(RoundedCornerShape(7.dp)).background(Color(0xFF636366)))
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, label ->
                Box(Modifier.weight(1f).fillMaxHeight().clickable { onSelect(i) }, contentAlignment = Alignment.Center) {
                    Text(label, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
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
            NavBar {
                Icon(
                    Icons.Rounded.Add, "Create playlist", tint = AccentLight,
                    modifier = Modifier.size(40.dp).clip(CircleShape).clickable { creating = true }.padding(8.dp),
                )
            }
            LargeTitle("Your Library")
            val coversLeft by Library.coversLeft.collectAsState()
            androidx.compose.animation.AnimatedVisibility(coversLeft > 0) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Muted)
                    Spacer(Modifier.width(8.dp))
                    Text("Getting song covers… $coversLeft left", color = Muted, fontSize = 13.sp)
                }
            }
        }
        item { LibraryRow("Liked songs", "Playlist · ${lib.liked.size} songs", null, LikedBrush, Icons.Rounded.Favorite) { Ui.push(Route.Liked) } }
        item { LibraryRow("Downloads", "${offline.size} songs on this phone", null, DownloadsBrush, Icons.Rounded.DownloadDone) { Ui.push(Route.Downloads) } }
        item { LibraryRow("Recently played", "${lib.recent.size} songs", null, RecentBrush, Icons.Rounded.History) { Ui.push(Route.Recent) } }
        item { LibraryRow("Spotify", "Import your playlists, private ones too", null, SpotifyBrush, Icons.Rounded.CloudDownload) { Ui.push(Route.SpotifyImport) } }
        item {
            Text("PLAYLISTS", color = Muted, fontSize = 13.sp, letterSpacing = 0.4.sp, modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp))
        }
        items(lib.playlists, key = { it.id }) { p ->
            val allOffline = p.trackIds.isNotEmpty() && p.trackIds.all { it in offline }
            LibraryRow(p.name, "Playlist · ${p.trackIds.size} songs" + if (allOffline) " · offline" else "", p.cover ?: Library.track(p.trackIds.firstOrNull() ?: "")?.thumbnail, null, null) {
                Ui.push(Route.PlaylistPage(p.id))
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().clickable { creating = true }.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Add, null, tint = AccentLight)
                Spacer(Modifier.width(10.dp))
                Text("Create playlist", color = AccentLight, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
    if (creating) {
        NameDialog("Create playlist", "My playlist #${lib.playlists.size + 1}", onDismiss = { creating = false }) { name ->
            creating = false
            val p = Library.createPlaylist(name)
            Ui.push(Route.PlaylistPage(p.id))
        }
    }
}

@Composable
private fun LibraryRow(title: String, subtitle: String, image: String?, brush: Brush?, icon: ImageVector?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (brush != null && icon != null) GradientTile(icon, brush, 56.dp, 6.dp) else Artwork(image, 56.dp, 6.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp)
            Text(subtitle, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------------------------------------------------------- Track collections

@Composable
fun CollectionScreen(route: Route) {
    val lib by Library.data.collectAsState()
    val offline by AudioStore.offline.collectAsState()
    val progress by AudioStore.progress.collectAsState()
    val shuffle by PlayerController.shuffle.collectAsState()
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    val playlist = (route as? Route.PlaylistPage)?.let { r -> lib.playlists.find { it.id == r.id } }
    if (route is Route.PlaylistPage && playlist == null) {
        LaunchedEffect(Unit) { Ui.back() }
        return
    }
    val title = when (route) {
        Route.Liked -> "Liked songs"
        Route.Downloads -> "Downloads"
        Route.Recent -> "Recently played"
        else -> playlist?.name ?: ""
    }
    val tracks: List<Track> = remember(lib, offline, route) {
        when (route) {
            Route.Liked -> Library.tracks(lib.liked)
            Route.Recent -> Library.tracks(lib.recent)
            Route.Downloads -> lib.tracks.values.filter { it.id in offline }.sortedBy { it.title.lowercase() }
            else -> Library.tracks(playlist?.trackIds.orEmpty())
        }
    }
    val (isThis, isPlaying) = listIsPlaying(tracks)
    val cover = playlist?.cover ?: tracks.firstOrNull()?.thumbnail
    val missing = tracks.count { it.id !in offline }
    val downloading = tracks.count { it.id in progress }
    val minutes = tracks.sumOf { it.durationSec } / 60

    LazyColumn(Modifier.fillMaxSize(), contentPadding = bottomPadding()) {
        item {
            NavBar(backLabel = if (Ui.stack.value.firstOrNull() == Route.Home) "Home" else "Library")
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                when (route) {
                    Route.Liked -> GradientTile(Icons.Rounded.Favorite, LikedBrush, 200.dp, 10.dp)
                    Route.Downloads -> GradientTile(Icons.Rounded.DownloadDone, DownloadsBrush, 200.dp, 10.dp)
                    Route.Recent -> GradientTile(Icons.Rounded.History, RecentBrush, 200.dp, 10.dp)
                    else -> Artwork(cover, 200.dp, 10.dp)
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    title, color = TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp),
                )
                Text("${tracks.size} songs" + if (minutes > 0) ", about $minutes min" else "", color = Muted, fontSize = 13.sp)
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (route != Route.Downloads && tracks.isNotEmpty()) {
                    when {
                        missing == 0 -> Icon(Icons.Rounded.DownloadDone, "Downloaded", tint = Good, modifier = Modifier.size(28.dp))
                        downloading > 0 -> {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = TextPrimary)
                            Spacer(Modifier.width(8.dp))
                            Text("$downloading", color = Muted, fontSize = 13.sp)
                        }
                        else -> Icon(
                            Icons.Rounded.Download, "Download $missing songs", tint = TextPrimary,
                            modifier = Modifier.size(44.dp).clip(CircleShape).clickable { AudioStore.downloadAll(tracks) }.padding(8.dp),
                        )
                    }
                }
                if (playlist != null) {
                    Box {
                        Icon(
                            Icons.Rounded.MoreHoriz, "More", tint = TextPrimary,
                            modifier = Modifier.size(44.dp).clip(CircleShape).clickable { menu = true }.padding(9.dp),
                        )
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(14.dp), containerColor = MenuBg) {
                            DropdownMenuItem(
                                text = { Text("Rename") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                                onClick = { menu = false; renaming = true }, colors = MenuDefaults.itemColors(textColor = TextPrimary, leadingIconColor = TextPrimary),
                            )
                            DropdownMenuItem(
                                text = { Text("Delete playlist") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                                onClick = { menu = false; confirmDelete = true }, colors = MenuDefaults.itemColors(textColor = ErrorText, leadingIconColor = ErrorText),
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Rounded.Shuffle, "Shuffle", tint = if (shuffle) AccentLight else TextPrimary,
                    modifier = Modifier.size(44.dp).clip(CircleShape).clickable(enabled = tracks.isNotEmpty()) {
                        PlayerController.play(tracks, tracks.indices.random(), shuffled = true)
                    }.padding(9.dp),
                )
                Spacer(Modifier.width(14.dp))
                PlayCircle(enabled = tracks.isNotEmpty(), playing = isPlaying) {
                    if (isThis) PlayerController.togglePlay() else PlayerController.play(tracks, 0, shuffled = false)
                }
            }
        }
        if (tracks.isEmpty()) {
            item {
                Text(
                    when (route) {
                        Route.Liked -> "Songs you like appear here. Tap ⋯ → Like on any song."
                        Route.Downloads -> "Nothing downloaded yet. Tap ⋯ → Download on a song, or ⤓ on a playlist."
                        Route.Recent -> "Songs you play appear here."
                        else -> "This playlist is empty. Search for songs, then ⋯ → Add to playlist."
                    },
                    color = Muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
            TrackRow(t, number = i + 1, onClick = { PlayerController.play(tracks, i, shuffled = false) }, playlistId = playlist?.id)
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

// ---------------------------------------------------------------- Settings

@Composable
fun SettingsScreen() {
    val status by Engine.status.collectAsState()
    val version by Engine.version.collectAsState()
    val autoplay by PlayerController.autoplay.collectAsState()
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = LocalBottomInset.current + 12.dp)) {
        NavBar(backLabel = "Home")
        LargeTitle("Settings")
        Group {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Autoplay", color = TextPrimary, fontSize = 16.sp, modifier = Modifier.weight(1f))
                FlatSwitch(autoplay) { PlayerController.setAutoplay(it) }
            }
        }
        GroupFooter("When 3 or fewer songs are left and you're on Wi-Fi, similar songs are added, and the next songs load in advance.")

        GroupHeader("Home server")
        HomeServerGroup()

        GroupHeader("Spotify account")
        Group { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { SpotifyAccountCard() } }

        GroupHeader("Offline")
        Group {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Downloaded songs: ${mb(offlineBytes)}", color = TextPrimary)
                Text(AudioStore.offlineDir.absolutePath, color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Text("Playback cache: ${mb(cacheBytes)}", color = TextPrimary)
                Text("Songs you've played, so replays are instant. The oldest are removed automatically.", color = Muted, fontSize = 12.sp)
            }
            GroupDivider()
            Text(
                "Clear cache", color = Color(0xFFFF6961), fontSize = 16.sp,
                modifier = Modifier.fillMaxWidth().clickable { AudioStore.clearCache(); StreamCache.clear(); refresh++ }.padding(16.dp),
            )
        }

        GroupHeader("Music engine (yt-dlp)")
        Group {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Version: ${version ?: "unknown"}", color = TextPrimary)
                Text("YouTube, SoundCloud and TikTok change often. If songs stop loading, update it. It also updates itself once a day.", color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
                updateMsg?.let { Text(it, color = Muted, fontSize = 13.sp) }
            }
            GroupDivider()
            Text(
                if (status == EngineStatus.Updating) "Updating…" else "Update yt-dlp now", color = AccentLight, fontSize = 16.sp,
                modifier = Modifier.fillMaxWidth().clickable(enabled = status == EngineStatus.Ready) {
                    updateMsg = "Updating…"
                    scope.launch { updateMsg = runCatching { Engine.update() }.getOrElse { "Update failed: ${shortError(it)}" } }
                }.padding(16.dp),
            )
            if (status is EngineStatus.Failed) {
                GroupDivider()
                Text("Retry starting the engine", color = AccentLight, fontSize = 16.sp, modifier = Modifier.fillMaxWidth().clickable { Engine.start(context) }.padding(16.dp))
            }
        }

        GroupHeader("Extra yt-dlp options")
        Group {
            Column(Modifier.padding(12.dp)) {
                GlassTextField(value = extra, onValueChange = { extra = it }, placeholder = "(none)", leading = Icons.Rounded.Settings)
            }
            GroupDivider()
            Text("Save", color = AccentLight, fontSize = 16.sp, modifier = Modifier.fillMaxWidth().clickable { Engine.extraArgs = extra; updateMsg = "Options saved" }.padding(16.dp))
        }
        GroupFooter("Advanced. Added to every yt-dlp call, e.g. --extractor-args \"youtube:player_client=default\"")

        GroupHeader("About")
        Group {
            Text(
                "Tunio for Android. Music from YouTube, SoundCloud and TikTok through yt-dlp. Spotify links are read for song info " +
                    "and play from YouTube. Lyrics from LRCLIB.",
                color = Muted, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(16.dp),
            )
        }
    }
}

private fun mb(b: Long) = "%.1f MB".format(b / 1024.0 / 1024.0)

/** Home server settings, grouped like the iPhone app. */
@Composable
private fun HomeServerGroup() {
    val state by HomeServer.state.collectAsState()
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf(HomeServer.address.removeSuffix(":${HomeServer.DEFAULT_PORT}").removePrefix("http://")) }
    var password by remember { mutableStateOf(HomeServer.password) }
    var message by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    // A scanned QR code fills in the fields.
    LaunchedEffect(state) {
        if (state is HomeServer.State.Online) {
            address = HomeServer.address.removeSuffix(":${HomeServer.DEFAULT_PORT}").removePrefix("http://")
            password = HomeServer.password
        }
    }
    val (statusText, statusColor) = when (val s = state) {
        is HomeServer.State.Online -> "Online · ${s.songs} songs kept" to Good
        HomeServer.State.Checking -> "Checking…" to Muted
        HomeServer.State.Offline -> "Can't reach it" to Muted
        HomeServer.State.WrongPassword -> "Wrong password" to ErrorText
        HomeServer.State.NotSetUp -> "Not set up" to Muted
    }
    Group {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Status", color = TextPrimary, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(statusText, color = statusColor, fontSize = 15.sp)
        }
        GroupDivider()
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassTextField(value = address, onValueChange = { address = it }, placeholder = "Address, e.g. 192.168.1.20", leading = Icons.Rounded.Link)
            GlassTextField(value = password, onValueChange = { password = it }, placeholder = "Password", leading = Icons.Rounded.Settings)
        }
        GroupDivider()
        Text(
            "Save and test", color = AccentLight, fontSize = 16.sp,
            modifier = Modifier.fillMaxWidth().clickable {
                HomeServer.configure(address, password)
                message = "Checking…"
                scope.launch { message = HomeServer.test() }
            }.padding(16.dp),
        )
        GroupDivider()
        Text(
            "Scan its QR code", color = AccentLight, fontSize = 16.sp,
            modifier = Modifier.fillMaxWidth().clickable { message = null; QrScan.open.value = true }.padding(16.dp),
        )
        GroupDivider()
        Text(
            if (searching) "Looking on your Wi-Fi…" else "Find it on my Wi-Fi", color = AccentLight, fontSize = 16.sp,
            modifier = Modifier.fillMaxWidth().clickable(enabled = !searching) {
                searching = true
                message = null
                scope.launch {
                    val found = HomeServer.discover()
                    searching = false
                    if (found != null) {
                        address = found.removePrefix("http://").removeSuffix(":${HomeServer.DEFAULT_PORT}")
                        message = if (password.isBlank()) "Found it! Type the password, then Save and test." else "Found it! Tap Save and test."
                    } else {
                        message = "No Tunio server found on this Wi-Fi. Check the Pi is on, or type its address."
                    }
                }
            }.padding(16.dp),
        )
        if (HomeServer.configured) {
            GroupDivider()
            Text(
                "Forget this server", color = Color(0xFFFF6961), fontSize = 16.sp,
                modifier = Modifier.fillMaxWidth().clickable {
                    HomeServer.forget(); address = ""; password = ""; message = null
                }.padding(16.dp),
            )
        }
    }
    GroupFooter(
        message ?: ("A Tunio home server (a Raspberry Pi) finds and keeps songs for your phone, so they start faster and your battery lasts longer. " +
            "When it can't be reached, Tunio works on its own."),
    )
}
