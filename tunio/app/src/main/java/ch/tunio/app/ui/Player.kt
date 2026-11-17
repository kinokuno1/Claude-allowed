package ch.tunio.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.tunio.app.data.Library
import ch.tunio.app.data.formatDuration
import ch.tunio.app.engine.AudioStore
import ch.tunio.app.player.PlayerController
import ch.tunio.app.player.RepeatMode
import ch.tunio.app.player.shortError
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

@Composable
fun MiniPlayer() {
    val queue by PlayerController.queue.collectAsState()
    val index by PlayerController.index.collectAsState()
    val playing by PlayerController.isPlaying.collectAsState()
    val preparing by PlayerController.preparing.collectAsState()
    val track = queue.getOrNull(index) ?: return
    val (pos, dur) = rememberProgress()
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Surface2)
            .clickable { Ui.playerOpen.value = true },
    ) {
        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(track.thumbnail, 42.dp, 4.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    if (preparing) "Loading…" else track.artist,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = Muted, fontSize = 12.sp,
                )
            }
            if (preparing) {
                CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = { PlayerController.togglePlay() }) {
                    Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/pause", Modifier.size(30.dp))
                }
            }
            IconButton(onClick = { PlayerController.next() }) { Icon(Icons.Filled.SkipNext, "Next") }
        }
        LinearProgressIndicator(
            progress = { if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = Color.White,
            trackColor = Color.White.copy(alpha = 0.15f),
        )
    }
}

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

    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF3B2A6B), Bg, Bg))),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { Ui.playerOpen.value = false }) { Icon(Icons.Filled.KeyboardArrowDown, "Close", Modifier.size(32.dp)) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("PLAYING FROM ${track.source.label.uppercase()}", fontSize = 11.sp, color = Muted, letterSpacing = 1.sp)
                }
                TrackMenu(track, tint = Color.White)
            }
        }
        item {
            Box(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 20.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(Surface2)) {
                    coil.compose.AsyncImage(
                        model = track.thumbnail,
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (preparing) {
                        Column(
                            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (fetchProgress != null && fetchProgress >= 0f) {
                                CircularProgressIndicator(progress = { fetchProgress }, color = Color.White)
                            } else {
                                CircularProgressIndicator(color = Color.White)
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(
                                if (fetchProgress != null && fetchProgress >= 0f) "Fetching audio ${(fetchProgress * 100).toInt()}%" else "Finding audio…",
                                color = Color.White,
                            )
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(track.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, fontSize = 15.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = {
                    if (track.id in offline) AudioStore.removeDownload(track)
                    else scope.launch {
                        runCatching { AudioStore.download(track) }
                            .onFailure { PlayerController.messages.tryEmit("Download failed: ${shortError(it)}") }
                    }
                }) {
                    if (track.id in offline) Icon(Icons.Filled.DownloadDone, "Downloaded", tint = Good)
                    else Icon(Icons.Filled.Download, "Download", tint = Color.White)
                }
                IconButton(onClick = { Library.toggleLike(track) }) {
                    Icon(if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Like", tint = if (liked) Accent2 else Color.White)
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                val fraction = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
                Slider(
                    value = if (dragging) dragValue else fraction,
                    onValueChange = { dragging = true; dragValue = it },
                    onValueChangeFinished = {
                        if (dur > 0) PlayerController.seekTo((dragValue * dur).toLong())
                        dragging = false
                    },
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.2f)),
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    Text(formatDuration(((if (dragging) dragValue * dur else pos.toFloat()) / 1000).toInt()).ifEmpty { "0:00" }, fontSize = 12.sp, color = Muted)
                    Spacer(Modifier.weight(1f))
                    Text(formatDuration((dur / 1000).toInt()), fontSize = 12.sp, color = Muted)
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { PlayerController.toggleShuffle() }) {
                    Icon(Icons.Filled.Shuffle, "Shuffle", tint = if (shuffle) Accent else Color.White)
                }
                IconButton(onClick = { PlayerController.previous() }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Filled.SkipPrevious, "Previous", Modifier.size(40.dp))
                }
                FilledIconButton(
                    onClick = { PlayerController.togglePlay() },
                    modifier = Modifier.size(72.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color.Black),
                ) {
                    if (preparing) CircularProgressIndicator(Modifier.size(30.dp), color = Color.Black, strokeWidth = 3.dp)
                    else Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/pause", Modifier.size(42.dp))
                }
                IconButton(onClick = { PlayerController.next() }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Filled.SkipNext, "Next", Modifier.size(40.dp))
                }
                IconButton(onClick = { PlayerController.cycleRepeat() }) {
                    Icon(
                        if (repeat == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                        "Repeat",
                        tint = if (repeat == RepeatMode.OFF) Color.White else Accent,
                    )
                }
            }
        }
        item {
            Text("Up next", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp))
        }
        itemsIndexed(queue, key = { i, t -> "$i-${t.id}" }) { i, t ->
            if (i > index) {
                Row(
                    Modifier.fillMaxWidth().clickable { PlayerController.jumpTo(i) }.padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(t.thumbnail, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            SourceBadge(t.source)
                            OfflineIndicator(t, 12.dp)
                            Text(t.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Muted, fontSize = 12.sp)
                        }
                    }
                    IconButton(onClick = { PlayerController.removeFromQueue(i) }) { Icon(Icons.Filled.Close, "Remove", tint = Muted) }
                }
            }
        }
        item { Spacer(Modifier.height(40.dp)) }
    }
}
