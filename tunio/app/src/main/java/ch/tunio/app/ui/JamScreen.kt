package ch.tunio.app.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.tunio.app.engine.HomeServer
import ch.tunio.app.engine.Jam
import ch.tunio.app.engine.JamFound
import ch.tunio.app.engine.JamState
import ch.tunio.app.engine.JamTrack
import ch.tunio.app.player.PlayerController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Opens the Jam sheet (from the player's Jam button, the Jam bar, or a Jam link). */
object JamUi {
    val open = MutableStateFlow(false)
}

@Composable
fun JamOverlay() {
    val open by JamUi.open.collectAsState()
    val pending by Jam.pendingLink.collectAsState()
    LaunchedEffect(pending) { if (pending != null) JamUi.open.value = true }
    AnimatedVisibility(open, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
        JamSheet()
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
        Modifier.padding(bottom = 8.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Surface30Hi).clickable { JamUi.open.value = true }.padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(cur?.thumbnail, 44.dp, 8.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(cur?.title ?: "Nothing playing yet", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Jam · ${st.name} · on ${st.host}'s phone", color = AccentLight, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(40.dp).clip(CircleShape).clickable { PlayerController.togglePlay() }, contentAlignment = Alignment.Center) {
            PlayPauseIcon(st.playing, TextPrimary, 28.dp)
        }
    }
}

/** The player's Jam button: people icon, lit with a count while in a Jam. */
@Composable
fun JamButton(size: Dp = 44.dp) {
    val s by Jam.state.collectAsState()
    Box(Modifier.size(size).clip(CircleShape).clickable { JamUi.open.value = true }, contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Groups, "Jam", tint = if (s != null) AccentLight else TextPrimary, modifier = Modifier.size(26.dp))
        s?.let {
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 2.dp).size(16.dp).clip(CircleShape).background(AccentLight),
                contentAlignment = Alignment.Center,
            ) { Text("${it.people.size}", color = Bg, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun JamSheet() {
    BackHandler { JamUi.open.value = false }
    val s by Jam.state.collectAsState()
    Box(
        Modifier.fillMaxSize().background(Bg)
            .background(Brush.verticalGradient(0f to GlowColor.copy(alpha = 0.6f), 0.4f to Color.Transparent))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).clickable { JamUi.open.value = false }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.KeyboardArrowDown, "Close", tint = TextPrimary, modifier = Modifier.size(30.dp))
                }
                Text("Jam", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.size(44.dp))
            }
            val st = s
            if (st == null) StartOrJoin() else InJam(st)
        }
        JoinDialog()
    }
}

// ---------------------------------------------------------------- not in a Jam: start one, or join one nearby

@Composable
private fun StartOrJoin() {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var you by remember { mutableStateOf(Jam.myName) }
    var name by remember { mutableStateOf("") }
    var locked by remember { mutableStateOf(0) }
    var password by remember { mutableStateOf("") }
    var starting by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(true) }
    var found by remember { mutableStateOf<List<JamFound>>(emptyList()) }
    val home by HomeServer.state.collectAsState()

    fun scan() {
        scanning = true
        scope.launch {
            found = Jam.nearby()
            scanning = false
        }
    }
    LaunchedEffect(Unit) { scan() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Text("Listen together", color = TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            Text(
                "Everyone adds songs to one queue. The music plays on the host's phone and its speaker, and anyone can play along on their own phone, in sync.",
                color = Muted, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        // nearby
        item {
            Row(Modifier.padding(start = 32.dp, end = 20.dp, top = 22.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("JAMS NEARBY", color = Muted, fontSize = 13.sp, letterSpacing = 0.4.sp, modifier = Modifier.weight(1f))
                if (scanning) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Muted)
                else Icon(Icons.Rounded.Refresh, "Look again", tint = AccentLight, modifier = Modifier.size(22.dp).clickable { scan() })
            }
            Group {
                if (found.isEmpty()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (scanning) Radar()
                        else Text("No Jam found on this Wi-Fi. Ask the host for the link.", color = Muted, fontSize = 15.sp)
                    }
                }
                found.forEachIndexed { i, f ->
                    if (i > 0) GroupDivider()
                    Row(
                        Modifier.fillMaxWidth().clickable { Jam.pendingLink.value = f }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Surface30Hi), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Groups, null, tint = AccentLight)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.info.name, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val now = f.info.now?.title?.let { " · ♪ $it" }.orEmpty()
                            Text("${f.info.host} · ${f.info.members} listening$now", color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(if (f.info.locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen, if (f.info.locked) "Password" else "Open", tint = Muted, modifier = Modifier.size(20.dp))
                    }
                }
                GroupDivider()
                Row(
                    Modifier.fillMaxWidth().clickable {
                        val text = clipboard.getText()?.text.orEmpty()
                        val link = Jam.parseLink(text)
                        if (link == null) problem = "Copy the Jam link first, then tap here."
                        else scope.launch { problem = null; Jam.openLink(link.first, link.second) }
                    }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Link, null, tint = AccentLight, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Paste a Jam link", color = AccentLight, fontSize = 16.sp)
                }
            }
        }
        // start
        item {
            GroupHeader("Start a Jam")
            Group {
                Field(you, { you = it }, "Your name")
                GroupDivider()
                Field(name, { name = it }, "Jam name (e.g. ${you.ifBlank { "My" }}'s Jam)")
                GroupDivider()
                Segmented(listOf("Open to all", "Password"), locked, Modifier.padding(12.dp)) { locked = it }
                AnimatedVisibility(locked == 1) {
                    Column {
                        GroupDivider()
                        Field(password, { password = it }, "Password for guests", secret = true)
                    }
                }
            }
            problem?.let { Text(it, color = ErrorText, fontSize = 14.sp, modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 10.dp)) }
            val canStart = HomeServer.configured && !starting && (locked == 0 || password.isNotBlank())
            Box(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                WhitePill(if (starting) "Starting…" else "Start the Jam", enabled = canStart, busy = starting) {
                    starting = true
                    problem = null
                    Jam.myName = you
                    scope.launch {
                        runCatching {
                            Jam.create(
                                name.ifBlank { "${you.ifBlank { "My" }}'s Jam" }, if (locked == 1) password else "",
                                PlayerController.queue.value, PlayerController.index.value,
                                PlayerController.exo.currentPosition / 1000.0, PlayerController.exo.playWhenReady,
                            )
                        }.onFailure { problem = it.message ?: "Couldn't start the Jam" }
                        starting = false
                    }
                }
            }
            GroupFooter(
                if (!HomeServer.configured) "A Jam runs on your Tunio home server: set it up in Settings first. You can still join other people's Jams."
                else if (home !is HomeServer.State.Online) "Your home server isn't answering right now."
                else "Runs on your home server. What's playing now becomes the start of the queue. Guests on your Wi-Fi see it here; send the link to anyone else.",
            )
        }
    }
}

/** Joining a Jam that was tapped or opened from a link (asks for the password if it has one). */
@Composable
private fun JoinDialog() {
    val pending by Jam.pendingLink.collectAsState()
    val p = pending ?: return
    val scope = rememberCoroutineScope()
    var you by remember(p) { mutableStateOf(Jam.myName) }
    var password by remember(p) { mutableStateOf("") }
    var busy by remember(p) { mutableStateOf(false) }
    var problem by remember(p) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) Jam.pendingLink.value = null },
        containerColor = Surface30,
        title = { Text(p.info.name, color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Hosted by ${p.info.host} · ${p.info.members} listening", color = Muted, fontSize = 14.sp)
                Column(Modifier.clip(RoundedCornerShape(12.dp)).background(Surface30Hi)) {
                    Field(you, { you = it }, "Your name")
                    if (p.info.locked) {
                        GroupDivider()
                        Field(password, { password = it; problem = null }, "Jam password", secret = true)
                    }
                }
                problem?.let { Text(it, color = ErrorText, fontSize = 14.sp) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && (!p.info.locked || password.isNotBlank()), onClick = {
                busy = true
                Jam.myName = you
                scope.launch {
                    runCatching { Jam.join(p.server, p.info.id, password) }
                        .onSuccess { Jam.pendingLink.value = null }
                        .onFailure { problem = it.message ?: "Couldn't join" }
                    busy = false
                }
            }) { Text(if (busy) "Joining…" else "Join", color = AccentLight, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = { Jam.pendingLink.value = null }) { Text("Cancel", color = Muted) } },
    )
}

// ---------------------------------------------------------------- in a Jam

@Composable
private fun InJam(s: JamState) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val along by Jam.playAlong.collectAsState()
    val host = s.you?.host == true
    val cur = s.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Text(s.name, color = TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            Text(
                (if (host) "You're the host" else "Hosted by ${s.host}") + " · ${s.people.size} listening" + if (s.locked) " · password" else " · open to all",
                color = Muted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp),
            )
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(s.people, key = { it.id }) { p ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(56.dp)) {
                        Box(
                            Modifier.size(44.dp).clip(CircleShape).background(if (p.host) AccentLight else Surface30Hi),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(p.name.take(1).uppercase(), color = if (p.host) Bg else TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                        Text(if (p.id == s.you?.id) "You" else p.name, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        // now playing + controls
        item {
            Group {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(cur?.thumbnail, 64.dp, 8.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(cur?.title ?: "Nothing playing", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(cur?.let { "${it.artist} · added by ${it.addedBy}" } ?: "Add a song and it starts playing", color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CircleIcon(Icons.Rounded.SkipPrevious, "Previous", 52.dp) { Jam.previous() }
                    Spacer(Modifier.width(18.dp))
                    Box(Modifier.size(60.dp).clip(CircleShape).background(Color.White).clickable { Jam.setPlaying(!s.playing) }, contentAlignment = Alignment.Center) {
                        PlayPauseIcon(s.playing, Bg, 34.dp)
                    }
                    Spacer(Modifier.width(18.dp))
                    CircleIcon(Icons.Rounded.SkipNext, "Next", 52.dp) { Jam.next() }
                }
            }
        }
        // where it plays
        item {
            GroupHeader("Where it plays")
            Group {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Speaker, null, tint = AccentLight)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        if (host) {
                            Text("On this phone", color = TextPrimary, fontSize = 16.sp)
                            Text("And the speaker or Bluetooth device it's connected to.", color = Muted, fontSize = 13.sp)
                        } else {
                            Text("Play on this phone too", color = TextPrimary, fontSize = 16.sp)
                            Text("In sync with ${s.host}'s phone: for your own headphones or speaker.", color = Muted, fontSize = 13.sp)
                        }
                    }
                    if (!host) FlatSwitch(along) { Jam.playAlong.value = it }
                }
            }
        }
        // invite
        item {
            GroupHeader("Invite")
            Group {
                Text(Jam.link, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp))
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SmallPill("Copy link", Icons.Rounded.ContentCopy, Modifier.weight(1f)) {
                        clipboard.setText(AnnotatedString(Jam.link))
                        Jam.messages.tryEmit("Jam link copied")
                    }
                    SmallPill("Share", Icons.Rounded.Share, Modifier.weight(1f)) {
                        val text = "Join my Jam \"${s.name}\" on Tunio" + (if (s.locked) " (ask me for the password)" else "") + ": ${Jam.link}"
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Invite to the Jam"))
                    }
                }
            }
            GroupFooter("People on the same Wi-Fi find the Jam in Tunio's Jam button. The link works from anywhere your home server can be reached.")
        }
        // queue
        item {
            Row(Modifier.padding(start = 32.dp, end = 20.dp, top = 22.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("UP NEXT", color = Muted, fontSize = 13.sp, letterSpacing = 0.4.sp, modifier = Modifier.weight(1f))
                Row(
                    Modifier.clip(RoundedCornerShape(50)).clickable { JamUi.open.value = false; Ui.tab(Route.Search) }.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Add, null, tint = AccentLight, modifier = Modifier.size(18.dp))
                    Text("Add songs", color = AccentLight, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        val upNext = s.upNext
        if (upNext.isEmpty()) {
            item { GroupFooter("Nothing queued. Tap any song in Search or your Library to add it to the Jam.") }
        }
        itemsIndexed(upNext, key = { _, t -> t.qid }) { i, t ->
            QueueRow(t, first = i == 0, last = i == upNext.lastIndex, position = s.index + 1 + i, modifier = Modifier.animateItem())
        }
        item {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 24.dp)) {
                Text(
                    if (host) "End the Jam for everyone" else "Leave the Jam", color = Color(0xFFFF6961), fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface30).clickable { Jam.leave() }.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun QueueRow(t: JamTrack, first: Boolean, last: Boolean, position: Int, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().clickable { Jam.jump(t.qid) }.padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(t.thumbnail, 44.dp, 6.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, color = TextPrimary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${t.artist.ifBlank { t.source.lowercase().replaceFirstChar { it.uppercase() } }} · ${t.addedBy}", color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        CircleIcon(Icons.Rounded.KeyboardArrowUp, "Move up", 36.dp, tint = if (first) Muted.copy(alpha = 0.3f) else TextPrimary) {
            if (!first) Jam.move(t.qid, position - 1)
        }
        CircleIcon(Icons.Rounded.KeyboardArrowDown, "Move down", 36.dp, tint = if (last) Muted.copy(alpha = 0.3f) else TextPrimary) {
            if (!last) Jam.move(t.qid, position + 1)
        }
        CircleIcon(Icons.Rounded.Close, "Remove", 36.dp, tint = Muted) { Jam.remove(t.qid) }
    }
}

// ---------------------------------------------------------------- pieces

/** Rings spreading out while looking for Jams nearby. */
@Composable
private fun Radar() {
    val t = rememberInfiniteTransition(label = "radar")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "p")
    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
        for (k in 0..1) {
            val q = (p + k * 0.5f) % 1f
            Box(Modifier.size(28.dp).graphicsLayer { scaleX = 0.3f + q; scaleY = 0.3f + q; alpha = 1f - q }.clip(CircleShape).background(AccentLight.copy(alpha = 0.5f)))
        }
        Icon(Icons.Rounded.Groups, null, tint = TextPrimary, modifier = Modifier.size(16.dp))
    }
    Spacer(Modifier.width(12.dp))
    Text("Looking for Jams on this Wi-Fi…", color = Muted, fontSize = 15.sp)
}

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
            focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = AccentLight,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun WhitePill(text: String, enabled: Boolean, busy: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp))
            .background(Color.White.copy(alpha = if (enabled || busy) 1f else 0.4f)).clickable(enabled = enabled, onClick = onClick),
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
private fun SmallPill(text: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(Surface30Hi).clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = TextPrimary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

@Composable
private fun CircleIcon(icon: ImageVector, description: String, size: Dp, tint: Color = TextPrimary, onClick: () -> Unit) {
    Box(Modifier.size(size).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = tint, modifier = Modifier.size(size * 0.62f))
    }
}
