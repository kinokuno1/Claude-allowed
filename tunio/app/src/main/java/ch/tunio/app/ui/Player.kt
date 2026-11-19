package ch.tunio.app.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.tunio.app.data.Library
import ch.tunio.app.data.formatDuration
import ch.tunio.app.engine.AudioStore
import ch.tunio.app.player.PlayerController
import ch.tunio.app.player.RepeatMode
import ch.tunio.app.player.shortError
import coil.compose.AsyncImage
import androidx.compose.runtime.CompositionLocalProvider
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Polls ExoPlayer for position/duration (ms) while composed. */
@Composable
private fun rememberProgress(): Pair<Long, Long> {
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            val exo = PlayerController.exo
            pos = exo.currentPosition.coerceAtLeast(0)
            dur = exo.duration.takeIf { it > 0 } ?: ((PlayerController.current?.durationSec ?: 0) * 1000L)
            delay(400)
        }
    }
    return pos to dur
}

/** Floating liquid-glass mini player: bends the screen content scrolling under it. */
@Composable
fun MiniPlayer(backdrop: Backdrop) {
    val queue by PlayerController.queue.collectAsState()
    val index by PlayerController.index.collectAsState()
    val playing by PlayerController.isPlaying.collectAsState()
    val preparing by PlayerController.preparing.collectAsState()
    val track = queue.getOrNull(index) ?: return
    val (pos, dur) = rememberProgress()
    val shape = RoundedCornerShape(26.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .chromeGlass(backdrop, shape)
            .clickable { Ui.playerOpen.value = true },
    ) {
        Row(Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(track.thumbnail, 44.dp, 14.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    if (preparing) "Loading…" else track.artist,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = Muted, fontSize = 12.sp,
                )
            }
            if (preparing) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = TextPrimary)
                }
            } else {
                RoundIcon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/pause", 44.dp, 30.dp) { PlayerController.togglePlay() }
            }
            RoundIcon(Icons.Rounded.SkipNext, "Next", 44.dp, 26.dp) { PlayerController.next() }
        }
        // Thin progress line along the bottom edge of the glass.
        val fraction = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 18.dp)
                .fillMaxWidth()
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(Color.White.copy(alpha = 0.15f)),
        ) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(AccentGradient))
        }
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, description: String, size: Dp, iconSize: Dp, tint: Color = TextPrimary, onClick: () -> Unit) {
    Box(Modifier.size(size).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = tint, modifier = Modifier.size(iconSize))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen() {
    val queue by PlayerController.queue.collectAsState()
    val index by PlayerController.index.collectAsState()
    val playing by PlayerController.isPlaying.collectAsState()
    val preparing by PlayerController.preparing.collectAsState()
    val shuffle by PlayerController.shuffle.collectAsState()
    val repeat by PlayerController.repeat.collectAsState()
    val lib by Library.data.collectAsState()
    val offline by AudioStore.offline.collectAsState()
    val progressMap by AudioStore.progress.collectAsState()
    val scope = rememberCoroutineScope()
    val track = queue.getOrNull(index)
    if (track == null) {
        LaunchedEffect(Unit) { Ui.playerOpen.value = false }
        return
    }
    val (pos, dur) = rememberProgress()
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val liked = track.id in lib.liked
    val fetchProgress = progressMap[track.id]
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val playerBackdrop = rememberLayerBackdrop()
    Box(Modifier.fillMaxSize().background(Bg)) {
        // Background: the cover art, hugely blurred, under a dark wash so all text stays light-on-dark.
        // The glass controls on top refract this layer.
        // The glass controls on top refract this layer (the blurred cover).
        CoverBackground(track.thumbnail, Modifier.layerBackdrop(playerBackdrop))

        CompositionLocalProvider(LocalGlassBackdrop provides playerBackdrop) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlassIconButton(Icons.Rounded.KeyboardArrowDown, "Close", onClick = { Ui.playerOpen.value = false })
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("PLAYING FROM", fontSize = 10.sp, color = Muted, letterSpacing = 1.5.sp)
                        Text(track.source.label, fontSize = 13.sp, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    }
                    Box(Modifier.size(44.dp).glass(CircleShape), contentAlignment = Alignment.Center) {
                        TrackMenu(track, tint = TextPrimary)
                    }
                }
            }
            item {
                Box(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 22.dp), contentAlignment = Alignment.Center) {
                    val shape = RoundedCornerShape(30.dp)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(shape)
                            .background(Surface2)
                            .border(1.dp, Glass.rim, shape),
                    ) {
                        AsyncImage(model = track.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        Box(Modifier.fillMaxSize().background(Glass.sheen))
                        if (preparing) {
                            Column(
                                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                if (fetchProgress != null && fetchProgress >= 0f) {
                                    CircularProgressIndicator(progress = { fetchProgress }, color = TextPrimary)
                                } else {
                                    CircularProgressIndicator(color = TextPrimary)
                                }
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    if (fetchProgress != null && fetchProgress >= 0f) "Fetching audio ${(fetchProgress * 100).toInt()}%" else "Finding audio…",
                                    color = TextPrimary,
                                )
                            }
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(track.title, color = TextPrimary, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(track.artist, fontSize = 15.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(8.dp))
                    GlassIconButton(
                        if (track.id in offline) Icons.Rounded.DownloadDone else Icons.Rounded.Download,
                        if (track.id in offline) "Downloaded" else "Download",
                        tint = if (track.id in offline) Good else TextPrimary,
                        onClick = {
                            if (track.id in offline) AudioStore.removeDownload(track)
                            else AudioStore.downloadAll(listOf(track))
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    GlassIconButton(
                        if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Like",
                        tint = if (liked) PinkLight else TextPrimary,
                        onClick = { Library.toggleLike(track) },
                    )
                }
            }
            item {
                // Transport controls on one glass slab.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp)
                        .glass(RoundedCornerShape(32.dp), blurDp = 10.dp, lensDp = 24.dp)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    val fraction = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
                    Slider(
                        value = if (dragging) dragValue else fraction,
                        onValueChange = { dragging = true; dragValue = it },
                        onValueChangeFinished = {
                            if (dur > 0) PlayerController.seekTo((dragValue * dur).toLong())
                            dragging = false
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color.White.copy(alpha = 0.22f),
                        ),
                    )
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                        Text(formatDuration(((if (dragging) dragValue * dur else pos.toFloat()) / 1000).toInt()).ifEmpty { "0:00" }, fontSize = 12.sp, color = Muted)
                        Spacer(Modifier.weight(1f))
                        Text(formatDuration((dur / 1000).toInt()), fontSize = 12.sp, color = Muted)
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RoundIcon(Icons.Rounded.Shuffle, "Shuffle", 48.dp, 24.dp, tint = if (shuffle) AccentLight else TextPrimary) { PlayerController.toggleShuffle() }
                        RoundIcon(Icons.Rounded.SkipPrevious, "Previous", 58.dp, 40.dp) { PlayerController.previous() }
                        Box(
                            Modifier
                                .size(76.dp)
                                .clip(CircleShape)
                                .background(Color.White)
                                .clickable { PlayerController.togglePlay() },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (preparing) CircularProgressIndicator(Modifier.size(30.dp), color = Color.Black, strokeWidth = 3.dp)
                            else Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/pause", tint = Color.Black, modifier = Modifier.size(44.dp))
                        }
                        RoundIcon(Icons.Rounded.SkipNext, "Next", 58.dp, 40.dp) { PlayerController.next() }
                        RoundIcon(
                            if (repeat == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat", 48.dp, 24.dp,
                            tint = if (repeat == RepeatMode.OFF) TextPrimary else AccentLight,
                        ) { PlayerController.cycleRepeat() }
                    }
                }
            }
            if (queue.size > index + 1) {
                item {
                    Text("Up next", style = SectionStyle, modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 8.dp))
                }
            }
            itemsIndexed(queue, key = { i, t -> "$i-${t.id}" }) { i, t ->
                if (i > index) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .clickable { PlayerController.jumpTo(i) }
                            .padding(start = 10.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(t.thumbnail, 46.dp, 12.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.title, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                SourceBadge(t.source)
                                OfflineIndicator(t, 12.dp)
                                Text(t.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Muted, fontSize = 12.sp)
                            }
                        }
                        RoundIcon(Icons.Rounded.Close, "Remove", 44.dp, 20.dp, tint = Muted) { PlayerController.removeFromQueue(i) }
                    }
                }
            }
            item { Spacer(Modifier.navigationBarsPadding().height(16.dp)) }
        }
        }
    }
}
