package ch.tunio.app.ui

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode as AnimRepeat
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Storage
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.tunio.app.data.Library
import ch.tunio.app.data.Track
import ch.tunio.app.data.formatDuration
import ch.tunio.app.engine.AudioStore
import ch.tunio.app.engine.HomeServer
import ch.tunio.app.engine.Lyrics
import ch.tunio.app.engine.LyricsResult
import ch.tunio.app.engine.WordLine
import ch.tunio.app.engine.WordLyrics
import ch.tunio.app.engine.WordSync
import ch.tunio.app.player.PlayerController
import ch.tunio.app.player.RepeatMode
import coil.compose.AsyncImage
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

/** Polls ExoPlayer for position/duration (ms) while composed. */
@Composable
private fun rememberProgress(every: Long = 400): Pair<Long, Long> {
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    LaunchedEffect(every) {
        while (true) {
            val exo = PlayerController.exo
            pos = exo.currentPosition.coerceAtLeast(0)
            dur = exo.duration.takeIf { it > 0 } ?: ((PlayerController.current?.durationSec ?: 0) * 1000L)
            delay(every)
        }
    }
    return pos to dur
}

/** Play/pause icon that cross-fades and pops between the two states. */
@Composable
fun PlayPauseIcon(playing: Boolean, tint: Color, size: Dp) {
    AnimatedContent(
        targetState = playing,
        transitionSpec = { (fadeIn(tween(180)) togetherWith fadeOut(tween(120))) },
        label = "pp",
    ) { p ->
        val s by animateFloatAsState(1f, spring(dampingRatio = 0.5f), label = "pps")
        Icon(if (p) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (p) "Pause" else "Play", tint = tint, modifier = Modifier.size(size).scale(s))
    }
}

/** Floating glass mini player above the tab bar. */
@Composable
fun MiniPlayer(backdrop: Backdrop) {
    val queue by PlayerController.queue.collectAsState()
    val index by PlayerController.index.collectAsState()
    val playing by PlayerController.isPlaying.collectAsState()
    val preparing by PlayerController.preparing.collectAsState()
    val track = queue.getOrNull(index) ?: return
    val (pos, dur) = rememberProgress()
    val shape = RoundedCornerShape(18.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .chromeGlass(backdrop, shape)
            .clickable { Ui.playerOpen.value = true },
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(track.thumbnail, 44.dp, 8.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(if (preparing) "Loading…" else track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Muted, fontSize = 12.sp)
            }
            if (preparing) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = TextPrimary)
                }
            } else {
                Box(Modifier.size(40.dp).clip(CircleShape).clickable { PlayerController.togglePlay() }, contentAlignment = Alignment.Center) {
                    PlayPauseIcon(playing, TextPrimary, 28.dp)
                }
            }
            RoundIcon(Icons.Rounded.SkipNext, "Next", 40.dp, 26.dp) { PlayerController.next() }
        }
        val fraction = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
        Box(
            Modifier.align(Alignment.BottomStart).padding(horizontal = 14.dp).fillMaxWidth().height(2.dp)
                .clip(RoundedCornerShape(1.dp)).background(Color.White.copy(alpha = 0.15f)),
        ) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(TextPrimary))
        }
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, description: String, size: Dp, iconSize: Dp, tint: Color = TextPrimary, onClick: () -> Unit) {
    Box(Modifier.size(size).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = tint, modifier = Modifier.size(iconSize))
    }
}

private enum class Panel { COVER, LYRICS, QUEUE }

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
    val home by HomeServer.state.collectAsState()
    val track = queue.getOrNull(index)
    if (track == null) {
        LaunchedEffect(Unit) { Ui.playerOpen.value = false }
        return
    }
    var panel by remember { mutableStateOf(Panel.COVER) }
    val (pos, dur) = rememberProgress(if (panel == Panel.LYRICS) 150 else 400)
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val liked = track.id in lib.liked
    val fetchProgress = progressMap[track.id]

    Box(Modifier.fillMaxSize().background(Bg)) {
        FlowingBackground(track.thumbnail, playing)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 22.dp)) {
            // top: close · playing from · more
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(Icons.Rounded.KeyboardArrowDown, "Close", 44.dp, 30.dp) { Ui.playerOpen.value = false }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("PLAYING FROM", fontSize = 10.sp, color = Muted, letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold)
                    Text(track.source.label, fontSize = 13.sp, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                }
                TrackMenu(track, tint = TextPrimary)
            }
            // cover, lyrics or queue
            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp)) {
                Crossfade(panel, label = "panel") { p ->
                    when (p) {
                        Panel.COVER -> BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            val side = if (maxWidth < maxHeight) maxWidth else maxHeight
                            val s by animateFloatAsState(if (playing || preparing) 1f else 0.88f, spring(dampingRatio = 0.7f, stiffness = 300f), label = "cover")
                            Box(Modifier.size(side).scale(s).clip(RoundedCornerShape(14.dp)).background(Surface30Hi), contentAlignment = Alignment.Center) {
                                AsyncImage(model = track.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                if (preparing) {
                                    Column(
                                        Modifier.clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(18.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        if (fetchProgress != null && fetchProgress >= 0f) CircularProgressIndicator(progress = { fetchProgress }, color = TextPrimary)
                                        else CircularProgressIndicator(color = TextPrimary)
                                        Spacer(Modifier.height(10.dp))
                                        Text(
                                            if (fetchProgress != null && fetchProgress >= 0f) "Fetching audio ${(fetchProgress * 100).toInt()}%" else "Finding audio…",
                                            color = TextPrimary, fontSize = 13.sp,
                                        )
                                    }
                                }
                            }
                        }
                        Panel.LYRICS -> LyricsPanel(track, pos)
                        Panel.QUEUE -> QueuePanel(queue, index)
                    }
                }
            }
            // title · download · like
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(track.title, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 27.sp)
                    Text(track.artist.ifBlank { track.source.label }, fontSize = 16.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                RoundIcon(
                    if (track.id in offline) Icons.Rounded.DownloadDone else Icons.Rounded.Download, if (track.id in offline) "Downloaded" else "Download",
                    40.dp, 26.dp, tint = if (track.id in offline) Good else TextPrimary,
                ) { if (track.id in offline) AudioStore.removeDownload(track) else AudioStore.downloadAll(listOf(track)) }
                val pop by animateFloatAsState(if (liked) 1f else 0.999f, spring(dampingRatio = 0.3f, stiffness = 600f), label = "like")
                Box(Modifier.size(40.dp).clip(CircleShape).clickable { Library.toggleLike(track) }, contentAlignment = Alignment.Center) {
                    val likeScale by animateFloatAsState(if (liked) 1.15f else 1f, spring(dampingRatio = 0.3f, stiffness = 500f), label = "likeScale")
                    Icon(
                        if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Like",
                        tint = if (liked) PinkLight else TextPrimary, modifier = Modifier.size(26.dp).scale(likeScale * pop),
                    )
                }
            }
            // seek bar
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
                    activeTrackColor = if (dragging) AccentLight else Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.22f),
                ),
                modifier = Modifier.padding(top = 6.dp),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(formatDuration(((if (dragging) dragValue * dur else pos.toFloat()) / 1000).toInt()).ifEmpty { "0:00" }, fontSize = 12.sp, color = Muted)
                Spacer(Modifier.weight(1f))
                Text(formatDuration((dur / 1000).toInt()).ifEmpty { "0:00" }, fontSize = 12.sp, color = Muted)
            }
            // controls
            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundIcon(Icons.Rounded.Shuffle, "Shuffle", 48.dp, 24.dp, tint = if (shuffle) AccentLight else TextPrimary) { PlayerController.toggleShuffle() }
                RoundIcon(Icons.Rounded.SkipPrevious, "Previous", 58.dp, 42.dp) { PlayerController.previous() }
                Box(
                    Modifier.size(76.dp).clip(CircleShape).background(Color.White).clickable { PlayerController.togglePlay() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (preparing) CircularProgressIndicator(Modifier.size(30.dp), color = Bg, strokeWidth = 3.dp)
                    else PlayPauseIcon(playing, Bg, 44.dp)
                }
                RoundIcon(Icons.Rounded.SkipNext, "Next", 58.dp, 42.dp) { PlayerController.next() }
                RoundIcon(
                    if (repeat == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat", 48.dp, 24.dp,
                    tint = if (repeat == RepeatMode.OFF) TextPrimary else AccentLight,
                ) { PlayerController.cycleRepeat() }
            }
            // lyrics · home server · queue
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(Icons.Rounded.Lyrics, "Lyrics", 44.dp, 24.dp, tint = if (panel == Panel.LYRICS) AccentLight else TextPrimary) {
                    panel = if (panel == Panel.LYRICS) Panel.COVER else Panel.LYRICS
                }
                Spacer(Modifier.weight(1f))
                if (home is HomeServer.State.Online) {
                    Icon(Icons.Rounded.Storage, null, tint = Muted, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Home server", color = Muted, fontSize = 12.sp)
                }
                Spacer(Modifier.weight(1f))
                RoundIcon(Icons.AutoMirrored.Rounded.QueueMusic, "Queue", 44.dp, 24.dp, tint = if (panel == Panel.QUEUE) AccentLight else TextPrimary) {
                    panel = if (panel == Panel.QUEUE) Panel.COVER else Panel.QUEUE
                }
            }
        }
    }
}

/**
 * Synced lyrics: the list glides to the line being sung, which turns white; tap a line to jump there.
 * "Word" mode lights each word up as it's sung (the timing is only looked up when Word is picked).
 */
@Composable
private fun LyricsPanel(track: Track, posMs: Long) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("ui", 0) }
    var mode by remember { mutableStateOf(prefs.getString("lyrics_mode", "line") ?: "line") }
    var result by remember(track.id) { mutableStateOf<LyricsResult?>(null) }
    var loading by remember(track.id) { mutableStateOf(true) }
    var words by remember(track.id) { mutableStateOf<WordLyrics?>(null) }
    LaunchedEffect(track.id) {
        loading = true
        result = Lyrics.get(track)
        loading = false
    }
    LaunchedEffect(track.id, mode, loading) {
        if (mode == "word" && !loading && words == null) words = WordSync.get(track, result)
    }
    val r = result
    val w = words
    Column(Modifier.fillMaxSize()) {
        if (!loading) {
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.animation.AnimatedVisibility(mode == "word" && w?.source != null, enter = fadeIn(), exit = fadeOut()) {
                    Text(if (w?.estimated == true) "Word timing estimated" else "Word timing: ${w?.source}", color = Muted, fontSize = 11.sp)
                }
                Spacer(Modifier.weight(1f))
                LyricsModeToggle(mode) {
                    mode = it
                    prefs.edit().putString("lyrics_mode", it).apply()
                }
            }
        }
        Box(Modifier.weight(1f)) {
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = TextPrimary)
                        Spacer(Modifier.width(10.dp))
                        Text("Looking for lyrics…", color = Muted)
                    }
                }
                mode == "word" && w == null -> WordsLoading()
                mode == "word" && w != null && w.lines.isNotEmpty() -> WordLyricsList(w.lines, posMs)
                r?.synced != null -> Column {
                    if (mode == "word") Text("No word-by-word timing for this song.", color = Muted, fontSize = 12.sp)
                    LineLyricsList(r.synced, posMs)
                }
                r?.plain != null -> LazyColumn(contentPadding = PaddingValues(vertical = 20.dp)) {
                    item { Text(r.plain, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp) }
                }
                r?.instrumental == true -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("♪ Instrumental ♪", color = Muted, fontSize = 18.sp) }
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No lyrics found for this song.\nLRCLIB is made by its community, so new or rare songs may be missing.",
                        color = Muted, fontSize = 15.sp, lineHeight = 21.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun LineLyricsList(lines: List<ch.tunio.app.engine.LyricLine>, posMs: Long) {
    val t = posMs / 1000.0 + 0.25
    val idx = lines.indexOfLast { it.time <= t }
    val list = rememberLazyListState()
    LaunchedEffect(idx) { if (idx >= 0) list.animateScrollToItem(idx, -220) }
    LazyColumn(state = list, contentPadding = PaddingValues(vertical = 140.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        itemsIndexed(lines) { i, line ->
            val color by animateColorAsState(
                when {
                    i == idx -> Color.White
                    i < idx -> Color.White.copy(alpha = 0.55f)
                    else -> Color.White.copy(alpha = 0.35f)
                }, tween(450), label = "line",
            )
            val s by animateFloatAsState(if (i == idx) 1.03f else 1f, tween(450), label = "lineScale")
            Text(
                line.text.ifBlank { "♪" }, color = color, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 31.sp,
                modifier = Modifier.fillMaxWidth().graphicsLayer { scaleX = s; scaleY = s; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }
                    .clickable { PlayerController.seekTo((line.time * 1000).toLong()) },
            )
        }
    }
}

/** Word by word: the text stays put, only a white highlight runs through the words as they're sung. */
@Composable
private fun WordLyricsList(lines: List<WordLine>, posMs: Long) {
    val idx = lines.indexOfLast { it.start <= posMs / 1000.0 + 0.15 }
    val list = rememberLazyListState()
    LaunchedEffect(idx) { if (idx >= 0) list.animateScrollToItem(idx, -220) }
    LazyColumn(state = list, contentPadding = PaddingValues(vertical = 140.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        itemsIndexed(lines) { i, line ->
            val mod = Modifier.fillMaxWidth().clickable { PlayerController.seekTo((line.start * 1000).toLong()) }
            when {
                i == idx -> CurrentWordLine(line, mod)
                else -> Text(sungText(line, if (i < idx) Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY), fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 31.sp, modifier = mod)
            }
        }
    }
}

/** The line being sung, redrawn every frame from the player's exact position. */
@Composable
private fun CurrentWordLine(line: WordLine, modifier: Modifier) {
    var now by remember { mutableStateOf(PlayerController.exo.currentPosition / 1000.0) }
    LaunchedEffect(line) {
        while (true) {
            androidx.compose.runtime.withFrameNanos { }
            now = PlayerController.exo.currentPosition / 1000.0
        }
    }
    Text(sungText(line, now), fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 31.sp, modifier = modifier)
}

/** Each word as bright as how much of it has been sung (past lines dimmed, upcoming ones darker). */
private fun sungText(line: WordLine, now: Double) = androidx.compose.ui.text.buildAnnotatedString {
    for (w in line.words) {
        val alpha = when (now) {
            Double.POSITIVE_INFINITY -> 0.55f
            Double.NEGATIVE_INFINITY -> 0.35f
            else -> {
                val f = if (w.end > w.start) ((now - w.start) / (w.end - w.start)).coerceIn(0.0, 1.0) else if (now >= w.start) 1.0 else 0.0
                0.35f + 0.65f * f.toFloat()
            }
        }
        withStyle(androidx.compose.ui.text.SpanStyle(color = Color.White.copy(alpha = alpha))) { append(w.text) }
    }
}

/** Line / Word switch: two icons with a white pill sliding between them. */
@Composable
private fun LyricsModeToggle(mode: String, onChange: (String) -> Unit) {
    val view = androidx.compose.ui.platform.LocalView.current
    val pill by animateFloatAsState(if (mode == "word") 1f else 0f, spring(dampingRatio = 0.62f, stiffness = 500f), label = "pill")
    Box(Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.1f)).padding(3.dp)) {
        Box(
            Modifier.graphicsLayer { translationX = pill * 42.dp.toPx() }
                .size(width = 40.dp, height = 30.dp).clip(CircleShape).background(Color.White),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (m in listOf("line", "word")) {
                val on = mode == m
                val tint by animateColorAsState(if (on) Bg else TextPrimary, tween(200), label = "tint")
                Box(
                    Modifier.size(width = 40.dp, height = 30.dp).clip(CircleShape)
                        .clickable {
                            if (!on) {
                                view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                                onChange(m)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.foundation.Canvas(Modifier.size(18.dp, 16.dp)) {
                        val u = size.width / 18f
                        fun bar(x: Float, y: Float, w: Float, h: Float, a: Float) =
                            drawRoundRect(tint.copy(alpha = a), androidx.compose.ui.geometry.Offset(x * u, y * u), androidx.compose.ui.geometry.Size(w * u, h * u), androidx.compose.ui.geometry.CornerRadius(1.5f * u))
                        if (m == "line") {
                            bar(1f, 1.5f, 16f, 3f, 1f); bar(1f, 6.5f, 11f, 3f, 0.55f); bar(1f, 11.5f, 13.5f, 3f, 0.55f)
                        } else {
                            bar(1f, 4f, 5f, 5f, 1f); bar(7.5f, 4f, 4.5f, 5f, 0.55f); bar(13.5f, 4f, 4.5f, 5f, 0.55f); bar(1f, 11f, 5f, 1.6f, 1f)
                        }
                    }
                }
            }
        }
    }
}

/** Three dots bouncing while the word timing is looked up. */
@Composable
private fun WordsLoading() {
    val t = rememberInfiniteTransition(label = "dots")
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (i in 0 until 3) {
                val y by t.animateFloat(
                    0f, 1f,
                    infiniteRepeatable(tween(450, delayMillis = 0, easing = androidx.compose.animation.core.FastOutSlowInEasing), AnimRepeat.Reverse,
                        androidx.compose.animation.core.StartOffset(i * 150)),
                    label = "dot$i",
                )
                Box(Modifier.graphicsLayer { translationY = (4f - 12f * y) * density; alpha = 0.4f + 0.6f * y }.size(10.dp).clip(CircleShape).background(Color.White))
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("Finding word-by-word timing…", color = Muted, fontSize = 14.sp)
    }
}

@Composable
private fun QueuePanel(queue: List<Track>, index: Int) {
    val autoplay by PlayerController.autoplay.collectAsState()
    LazyColumn(Modifier.fillMaxSize()) {
        item { Text("Next in queue", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp)) }
        if (queue.size <= index + 1) {
            item { Text(if (autoplay) "Autoplay adds similar songs when the queue runs low." else "The queue is empty.", color = Muted) }
        }
        itemsIndexed(queue, key = { i, t -> "$i-${t.id}" }) { i, t ->
            if (i > index) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { PlayerController.jumpTo(i) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(t.thumbnail, 44.dp, 6.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.title, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
                        Text(t.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Muted, fontSize = 13.sp)
                    }
                    RoundIcon(Icons.Rounded.Close, "Remove", 40.dp, 20.dp, tint = Muted) { PlayerController.removeFromQueue(i) }
                }
            }
        }
    }
}

/** The player's background: blurred copies of the cover drifting in slow waves (they rest while paused). */
@Composable
private fun FlowingBackground(cover: String?, playing: Boolean) {
    val t = rememberInfiniteTransition(label = "flow")
    val phase by t.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(26_000, easing = LinearEasing), AnimRepeat.Restart), label = "phase")
    var frozen by remember { mutableFloatStateOf(0f) }
    if (playing) frozen = phase
    val p = if (playing) phase else frozen
    BoxWithConstraints(Modifier.fillMaxSize().background(Bg)) {
        if (cover != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val w = maxWidth
            val h = maxHeight
            for (k in 0 until 3) {
                AsyncImage(
                    model = cover, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(w * 1.3f)
                        .graphicsLayer {
                            translationX = (sin(p * (1 + k * 0.3f) + k * 2f) * w.toPx() * 0.3f) - w.toPx() * 0.15f
                            translationY = (cos(p * (0.8f + k * 0.25f) + k) * h.toPx() * 0.22f) + (k - 1) * h.toPx() * 0.25f + h.toPx() * 0.2f
                            rotationZ = p * 57.3f * (if (k == 1) -1f else 1f) * (0.3f + k * 0.15f)
                            alpha = 0.75f
                        }
                        .clip(CircleShape)
                        .blur(70.dp),
                )
            }
        } else if (cover != null) {
            AsyncImage(model = cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.35f })
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
    }
}
