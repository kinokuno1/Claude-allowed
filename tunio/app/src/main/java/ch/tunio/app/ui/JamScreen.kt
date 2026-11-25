package ch.tunio.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.tunio.app.engine.HomeServer
import ch.tunio.app.engine.Jam
import ch.tunio.app.engine.JamBeacon
import ch.tunio.app.engine.JamFound
import ch.tunio.app.engine.JamState
import ch.tunio.app.engine.JamTrack
import ch.tunio.app.player.PlayerController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Opens the Jam sheet (from the player's Jam button, the Jam bar, or a Jam link). */
object JamUi {
    val open = MutableStateFlow(false)
}

private val Side = 20.dp // the sheet's side margin

/**
 * The Jam sheet: comes up from the bottom (half height); drag the bar up for all of it, or down to close it.
 * Most people only join: it opens on the Jams nearby. Starting one is behind the small + at the top.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JamOverlay() {
    val open by JamUi.open.collectAsState()
    val pending by Jam.pendingLink.collectAsState()
    val opened by Jam.opened.collectAsState()
    LaunchedEffect(pending) { if (pending != null) JamUi.open.value = true }
    LaunchedEffect(opened) { if (opened > 0) JamUi.open.value = true }
    if (!open) return
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(
        onDismissRequest = { JamUi.open.value = false },
        sheetState = sheet,
        containerColor = Surface30,
        contentColor = TextPrimary,
        scrimColor = Color.Black.copy(alpha = 0.6f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Muted.copy(alpha = 0.5f), width = 36.dp, height = 4.dp) },
    ) {
        val s by Jam.state.collectAsState()
        var creating by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = Side, end = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    s?.name ?: if (creating) "New Jam" else "Jam", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Black,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (s == null) PlusButton(creating) { creating = !creating }
            }
            val st = s
            AnimatedContent(
                targetState = when { st != null -> 2; creating -> 1; else -> 0 },
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "jamPage",
            ) { page ->
                when (page) {
                    2 -> st?.let { InJam(it) }
                    1 -> CreateJam { creating = false }
                    else -> Nearby()
                }
            }
        }
        JoinDialog()
    }
}

/**
 * The small + that opens "New Jam": it pops and turns as it changes into an ×, a bit like Face ID's icon
 * (the strokes shrink, spin and grow back into the new shape).
 */
@Composable
private fun PlusButton(open: Boolean, onClick: () -> Unit) {
    val scope = rememberCoroutineScope()
    val turn by animateFloatAsState(if (open) 135f else 0f, spring(dampingRatio = 0.55f, stiffness = 260f), label = "turn")
    val squeeze = remember { Animatable(1f) }
    Box(
        Modifier.size(44.dp).clip(CircleShape).tap {
            onClick()
            scope.launch {
                squeeze.animateTo(0.45f, tween(110))
                squeeze.animateTo(1f, spring(dampingRatio = 0.38f, stiffness = 420f))
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp).graphicsLayer { rotationZ = turn }) {
            val c = center
            val half = size.minDimension / 2 * squeeze.value
            val w = 2.2.dp.toPx() + (1f - squeeze.value) * 3.dp.toPx() // thicker while it's squeezed
            val col = if (open) TextPrimary else Muted
            drawLine(col, Offset(c.x - half, c.y), Offset(c.x + half, c.y), w, StrokeCap.Round)
            drawLine(col, Offset(c.x, c.y - half), Offset(c.x, c.y + half), w, StrokeCap.Round)
        }
    }
}

/** The bar above the tab bar while you're in a Jam but the music plays on the host's phone. */
@Composable
fun JamBar() {
    val s by Jam.state.collectAsState()
    val along by Jam.playAlong.collectAsState()
    val st = s ?: return
    if (Jam.isHost || along) return
    val cur = st.current
    Row(
        Modifier.padding(bottom = 8.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Surface30Hi).tap { JamUi.open.value = true }.padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(cur?.thumbnail, 44.dp, 8.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(cur?.title ?: "Nothing playing yet", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${st.name} · ${st.host}'s phone", color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(44.dp).clip(CircleShape).tap { PlayerController.togglePlay() }, contentAlignment = Alignment.Center) {
            PlayPauseIcon(st.playing, TextPrimary, 28.dp)
        }
    }
}

/** The player's Jam button: outlined, filled while you're in a Jam (with how many people). */
@Composable
fun JamButton(size: Dp = 44.dp) {
    val s by Jam.state.collectAsState()
    Box(Modifier.size(size).clip(CircleShape).tap { JamUi.open.value = true }, contentAlignment = Alignment.Center) {
        Icon(if (s != null) Icons.Rounded.Groups else Icons.Outlined.Groups, "Jam", tint = TextPrimary, modifier = Modifier.size(26.dp))
        s?.let {
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 2.dp).size(16.dp).clip(CircleShape).background(Color.White),
                contentAlignment = Alignment.Center,
            ) { Text("${it.people.size}", color = Bg, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

// ---------------------------------------------------------------- Jams nearby (what the sheet opens on)

@Composable
private fun Nearby() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var scanning by remember { mutableStateOf(true) }
    var found by remember { mutableStateOf<List<JamFound>>(emptyList()) }
    var joining by remember { mutableStateOf<String?>(null) }
    var allowed by remember { mutableStateOf(JamBeacon.allowed(context)) }
    val askBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { allowed = true }
    LaunchedEffect(Unit) { if (!allowed) askBluetooth.launch(JamBeacon.permissions()) }
    LaunchedEffect(allowed) {
        if (!allowed) return@LaunchedEffect
        while (true) { // keeps looking while the sheet is open
            scanning = true
            found = Jam.nearby()
            scanning = false
            delay(8000)
        }
    }

    Box(Modifier.fillMaxWidth().heightIn(min = 280.dp)) {
        when {
            found.isNotEmpty() -> Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                found.forEach { f ->
                    Row(
                        Modifier.fillMaxWidth().tap {
                            if (f.info.locked) Jam.pendingLink.value = f
                            else {
                                joining = f.info.id
                                scope.launch {
                                    runCatching { Jam.join(f.server, f.info.id, "") }.onFailure { Jam.messages.tryEmit(it.message ?: "Couldn't join") }
                                    joining = null
                                }
                            }
                        }.padding(horizontal = Side, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Surface30Hi), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Groups, null, tint = TextPrimary)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.info.name, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(f.info.host.ifBlank { null }, if (f.info.members > 0) "${f.info.members} listening" else null).joinToString(" · "),
                                color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        when {
                            joining == f.info.id -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = TextPrimary)
                            f.info.locked -> Icon(Icons.Rounded.Lock, "Password", tint = Muted, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            scanning -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(22.dp), strokeWidth = 2.dp, color = Muted)
            else -> Column(Modifier.align(Alignment.Center).padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Tumbleweed()
                Text("Looks like nothing's here.", color = Muted, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

/** A small tumbleweed rolling by with little hops, over a faint ground line. */
@Composable
private fun Tumbleweed() {
    val t = rememberInfiniteTransition(label = "tumbleweed")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(5200, easing = LinearEasing), RepeatMode.Restart), label = "roll")
    Canvas(Modifier.width(220.dp).height(72.dp)) {
        val r = 14.dp.toPx()
        val ground = size.height - 6.dp.toPx()
        drawLine(Muted.copy(alpha = 0.18f), Offset(0f, ground), Offset(size.width, ground), 1.dp.toPx(), StrokeCap.Round)
        val x = -r * 2 + (size.width + r * 4) * p
        val hop = abs(sin(p * PI * 5)).toFloat() * 12.dp.toPx()
        val y = ground - r - hop
        val fade = when { p < 0.12f -> p / 0.12f; p > 0.88f -> (1f - p) / 0.12f; else -> 1f }
        val col = Muted.copy(alpha = 0.55f * fade)
        rotate(degrees = (x / r) * 57.3f, pivot = Offset(x, y)) {
            for (k in 0 until 7) { // tangled loops
                val a = k * 0.9f
                val rr = r * (0.5f + 0.07f * k)
                drawOval(
                    col, topLeft = Offset(x - rr + cos(a) * r * 0.25f, y - rr * 0.7f + sin(a) * r * 0.25f),
                    size = Size(rr * 2, rr * 1.4f), style = Stroke(1.4.dp.toPx()),
                )
            }
            drawCircle(col, r, Offset(x, y), style = Stroke(1.6.dp.toPx()))
        }
    }
}

/** Asks for the password of a Jam that has one (open Jams are joined right away). */
@Composable
private fun JoinDialog() {
    val pending by Jam.pendingLink.collectAsState()
    val p = pending ?: return
    val scope = rememberCoroutineScope()
    var password by remember(p) { mutableStateOf("") }
    var busy by remember(p) { mutableStateOf(false) }
    var problem by remember(p) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) Jam.pendingLink.value = null },
        containerColor = Surface30Hi,
        title = { Text(p.info.name, color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.clip(RoundedCornerShape(12.dp)).background(Surface30)) {
                    Field(password, { password = it; problem = null }, "Password", secret = true)
                }
                problem?.let { Text(it, color = ErrorText, fontSize = 14.sp) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && password.isNotBlank(), onClick = {
                busy = true
                scope.launch {
                    runCatching { Jam.join(p.server, p.info.id, password) }
                        .onSuccess { Jam.pendingLink.value = null }
                        .onFailure { problem = it.message ?: "Couldn't join" }
                    busy = false
                }
            }) { Text(if (busy) "Joining…" else "Join", color = TextPrimary, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = { Jam.pendingLink.value = null }) { Text("Cancel", color = Muted) } },
    )
}

// ---------------------------------------------------------------- starting a Jam (behind the +)

@Composable
private fun CreateJam(onStarted: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var locked by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var starting by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(horizontal = Side).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface30Hi)) {
            Field(name, { name = it }, "${Jam.myName}'s Jam")
            Divider()
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp).heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Password", color = TextPrimary, fontSize = 16.sp, modifier = Modifier.weight(1f))
                FlatSwitch(locked) { locked = it }
            }
            AnimatedVisibility(locked) {
                Column {
                    Divider()
                    Field(password, { password = it }, "Password for guests", secret = true)
                }
            }
        }
        problem?.let { Text(it, color = ErrorText, fontSize = 14.sp) }
        if (!HomeServer.configured) Text("Needs your Tunio home server (Settings).", color = Muted, fontSize = 13.sp)
        WhitePill(if (starting) "Starting…" else "Start", enabled = HomeServer.configured && !starting && (!locked || password.isNotBlank()), busy = starting) {
            starting = true
            problem = null
            scope.launch {
                runCatching {
                    Jam.create(
                        name.ifBlank { "${Jam.myName}'s Jam" }, if (locked) password else "",
                        PlayerController.queue.value, PlayerController.index.value,
                        PlayerController.exo.currentPosition / 1000.0, PlayerController.exo.playWhenReady,
                    )
                }.onSuccess { onStarted() }.onFailure { problem = it.message ?: "Couldn't start the Jam" }
                starting = false
            }
        }
    }
}

// ---------------------------------------------------------------- in a Jam

@Composable
private fun InJam(s: JamState) {
    val context = LocalContext.current
    val along by Jam.playAlong.collectAsState()
    val host = s.you?.host == true
    val cur = s.current
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 28.dp)) {
        // who's here
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = Side), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(s.people, key = { it.id }) { p ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(52.dp)) {
                        Box(Modifier.size(44.dp).clip(CircleShape).background(if (p.host) Color.White else Surface30Hi), contentAlignment = Alignment.Center) {
                            Text(p.name.take(1).uppercase(), color = if (p.host) Bg else TextPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        }
                        Text(
                            if (p.id == s.you?.id) "You" else p.name, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
        // where it plays, like a device card, with the controls
        item {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface30Hi)) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (host) "This phone" else "${s.host}'s phone", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Black)
                        Text(
                            cur?.let { "${it.title} – ${it.artist}" } ?: "Nothing playing", color = AccentLight, fontSize = 14.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Icon(Icons.Rounded.PhoneAndroid, null, tint = AccentLight, modifier = Modifier.size(36.dp))
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CircleIcon(Icons.Rounded.SkipPrevious, "Previous", 48.dp) { Jam.previous() }
                    Spacer(Modifier.width(20.dp))
                    Box(Modifier.size(56.dp).clip(CircleShape).background(Color.White).tap { Jam.setPlaying(!s.playing) }, contentAlignment = Alignment.Center) {
                        PlayPauseIcon(s.playing, Bg, 32.dp)
                    }
                    Spacer(Modifier.width(20.dp))
                    CircleIcon(Icons.Rounded.SkipNext, "Next", 48.dp) { Jam.next() }
                }
                if (!host) {
                    Divider()
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Play on this phone too", color = TextPrimary, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        FlatSwitch(along) { Jam.playAlong.value = it }
                    }
                }
            }
        }
        // up next
        val upNext = s.upNext
        item {
            Text("Up next", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = Side, vertical = 6.dp))
            if (upNext.isEmpty()) {
                Text("Tap any song to add it.", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = Side, vertical = 4.dp))
            }
        }
        itemsIndexed(upNext, key = { _, t -> t.qid }) { i, t ->
            QueueRow(t, first = i == 0, last = i == upNext.lastIndex, position = s.index + 1 + i, modifier = Modifier.animateItem())
        }
        // invite · leave
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = Side).padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Pill("Invite", Modifier.weight(1f), light = true) {
                    val text = "Join my Jam on Tunio" + (if (s.locked) " (ask me for the password)" else "") + ": ${Jam.link}"
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Invite to the Jam"))
                }
                Pill(if (host) "End" else "Leave", Modifier.weight(1f), light = false) { Jam.leave() }
            }
        }
    }
}

@Composable
private fun QueueRow(t: JamTrack, first: Boolean, last: Boolean, position: Int, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().tap { Jam.jump(t.qid) }.padding(start = Side, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(t.thumbnail, 44.dp, 6.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, color = TextPrimary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${t.artist} · ${t.addedBy}", color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        CircleIcon(Icons.Rounded.KeyboardArrowUp, "Move up", 40.dp, tint = if (first) Muted.copy(alpha = 0.3f) else TextPrimary) {
            if (!first) Jam.move(t.qid, position - 1)
        }
        CircleIcon(Icons.Rounded.KeyboardArrowDown, "Move down", 40.dp, tint = if (last) Muted.copy(alpha = 0.3f) else TextPrimary) {
            if (!last) Jam.move(t.qid, position + 1)
        }
        CircleIcon(Icons.Rounded.Close, "Remove", 40.dp, tint = Muted) { Jam.remove(t.qid) }
    }
}

// ---------------------------------------------------------------- pieces

@Composable
private fun Divider() = Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(1.dp).background(Color.White.copy(alpha = 0.08f)))

@Composable
private fun Field(value: String, onChange: (String) -> Unit, hint: String, secret: Boolean = false) {
    TextField(
        value = value, onValueChange = onChange, singleLine = true,
        placeholder = { Text(hint, color = Muted.copy(alpha = 0.7f)) },
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = TextPrimary,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun WhitePill(text: String, enabled: Boolean, busy: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp))
            .background(Color.White.copy(alpha = if (enabled || busy) 1f else 0.4f)).tap(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Bg)
            Spacer(Modifier.width(10.dp))
        }
        Text(text, color = Bg, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
private fun Pill(text: String, modifier: Modifier, light: Boolean, onClick: () -> Unit) {
    Box(
        modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(if (light) Color.White else Surface30Hi).tap(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (light) Bg else TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun CircleIcon(icon: ImageVector, description: String, size: Dp, tint: Color = TextPrimary, onClick: () -> Unit) {
    Box(Modifier.size(size).clip(CircleShape).tap(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = tint, modifier = Modifier.size(size * 0.58f))
    }
}
