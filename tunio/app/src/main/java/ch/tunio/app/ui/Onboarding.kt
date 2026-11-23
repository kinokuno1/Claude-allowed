package ch.tunio.app.ui

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.tunio.app.engine.HomeServer
import kotlinx.coroutines.launch

/** First launch is remembered in the "ui" preferences. */
object Onboarding {
    private const val KEY = "onboarded"
    fun done(context: Context) = context.getSharedPreferences("ui", 0).getBoolean(KEY, false)
    fun finish(context: Context) = context.getSharedPreferences("ui", 0).edit().putBoolean(KEY, true).apply()
}

private enum class Step { WELCOME, SERVER, PASSWORD, DONE }

/** First launch: a short welcome, then connecting the Tunio home server (optional). Same steps as on iPhone. */
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val home by HomeServer.state.collectAsState()
    var step by remember { mutableStateOf(Step.WELCOME) }
    var address by remember { mutableStateOf(HomeServer.address.removePrefix("http://")) }
    var password by remember { mutableStateOf("") }
    var connecting by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    // The QR code page (tunio://pair) can finish the setup at any step.
    LaunchedEffect(home) {
        if (home is HomeServer.State.Online && step != Step.DONE && !connecting && HomeServer.configured) step = Step.DONE
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .background(Brush.verticalGradient(0f to GlowColor.copy(alpha = 0.7f), 0.5f to Color.Transparent))
            .clickable(enabled = false) {},
    ) {
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                (slideInHorizontally(spring(dampingRatio = 0.88f, stiffness = 400f)) { if (forward) it / 3 else -it / 3 } + fadeIn(tween(250)))
                    .togetherWith(slideOutHorizontally(tween(250)) { if (forward) -it / 3 else it / 3 } + fadeOut(tween(200)))
            },
            label = "onboarding",
        ) { s ->
            Column(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 24.dp).padding(bottom = 12.dp),
            ) {
                when (s) {
                    Step.WELCOME -> Welcome { step = Step.SERVER }
                    Step.SERVER -> ServerStep(
                        onPick = { a -> address = a.removePrefix("http://"); problem = null; step = Step.PASSWORD },
                        onType = { address = ""; problem = null; step = Step.PASSWORD },
                        onSkip = { step = Step.DONE },
                    )
                    Step.PASSWORD -> PasswordStep(
                        address = address, onAddress = { address = it },
                        password = password, onPassword = { password = it },
                        connecting = connecting, problem = problem,
                        onBack = { step = Step.SERVER },
                        onConnect = { ok, why ->
                            connecting = ok == null
                            if (ok == true) step = Step.DONE
                            problem = why
                        },
                    )
                    Step.DONE -> Done(home) { onFinish() }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.Welcome(next: () -> Unit) {
    Spacer(Modifier.weight(1f))
    Box(Modifier.size(84.dp).clip(RoundedCornerShape(22.dp)).background(Surface30Hi), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.MusicNote, null, tint = TextPrimary, modifier = Modifier.size(44.dp))
    }
    Text("Tunio", color = TextPrimary, fontSize = 44.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 22.dp))
    Text("Your music from YouTube, SoundCloud and Spotify, in one place.", color = Muted, fontSize = 19.sp, lineHeight = 25.sp, modifier = Modifier.padding(top = 6.dp))
    Column(Modifier.padding(top = 34.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Feature(Icons.Rounded.Search, "Search or paste a link", "Songs, playlists and albums from YouTube, SoundCloud and Spotify.")
        Feature(Icons.Rounded.Download, "Listen offline", "Download songs and whole playlists to your phone.")
        Feature(Icons.Rounded.Lyrics, "Synced lyrics", "The words follow the song as it plays.")
    }
    Spacer(Modifier.weight(1f))
    Primary("Get started", onClick = next)
}

@Composable
private fun ColumnScope.ServerStep(onPick: (String) -> Unit, onType: () -> Unit, onSkip: () -> Unit) {
    val scope = rememberCoroutineScope()
    var searching by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<String?>(null) }
    var notFound by remember { mutableStateOf(false) }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(40.dp))
        Icon(Icons.Rounded.Storage, null, tint = AccentLight, modifier = Modifier.size(36.dp))
        Text("Got a Tunio home server?", color = TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.Black, lineHeight = 35.sp, modifier = Modifier.padding(top = 14.dp))
        Text(
            "A Raspberry Pi with Tunio on it finds and keeps songs for your phone, so they start faster. It's optional: Tunio also works on its own.",
            color = Muted, fontSize = 16.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 8.dp),
        )
        Column(Modifier.padding(top = 26.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Option(Icons.Rounded.Wifi, "Find it on my Wi-Fi", "Looks for a Tunio server on the network you're on.") {
                if (searching) return@Option
                searching = true; notFound = false; found = null
                scope.launch {
                    found = HomeServer.discover(10_000)
                    notFound = found == null
                    searching = false
                }
            }
            AnimatedVisibility(searching || found != null || notFound) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface30).padding(14.dp)) {
                    val f = found
                    when {
                        f != null -> Row(
                            Modifier.fillMaxWidth().clickable { onPick(f) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.CheckCircle, null, tint = Good)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Tunio server", color = TextPrimary, fontWeight = FontWeight.Bold)
                                Text(f.removePrefix("http://"), color = Muted, fontSize = 13.sp)
                            }
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Muted)
                        }
                        searching -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = TextPrimary)
                            Spacer(Modifier.width(10.dp))
                            Text("Looking for your server…", color = Muted)
                        }
                        else -> Text("No server found on this Wi-Fi. Is the Pi on, and your phone on the same network? You can also type its address.", color = Muted, fontSize = 14.sp, lineHeight = 19.sp)
                    }
                }
            }
            Option(
                Icons.Rounded.QrCodeScanner, "Scan its QR code",
                "Open your camera (or Google Lens), point it at the QR code the installer showed, open the link, then tap “Connect Tunio”.",
            ) {}
            Option(Icons.Rounded.Keyboard, "Type its address", "For example 192.168.1.20, its Tailscale address, or your own https address.", onType)
        }
    }
    Text(
        "I don't have one", color = Muted, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onSkip).padding(vertical = 12.dp),
    )
}

/** onConnect(null, null) = started; (true, null) = connected; (false, reason) = failed. */
@Composable
private fun ColumnScope.PasswordStep(
    address: String, onAddress: (String) -> Unit,
    password: String, onPassword: (String) -> Unit,
    connecting: Boolean, problem: String?,
    onBack: () -> Unit,
    onConnect: (Boolean?, String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    Row(
        Modifier.padding(top = 16.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onBack).padding(vertical = 6.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, null, tint = AccentLight)
        Text("Back", color = AccentLight, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Text("Connect your server", color = TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 20.dp))
        Text(
            "The installer showed the password at the end. You can also find it on the server's page in your browser.",
            color = Muted, fontSize = 16.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 8.dp),
        )
        Column(Modifier.padding(top = 24.dp).clip(RoundedCornerShape(14.dp)).background(Surface30)) {
            FlatField(address, onAddress, "Address, e.g. 192.168.1.20", KeyboardType.Uri, secret = false)
            Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(1.dp).background(Color.White.copy(alpha = 0.08f)))
            FlatField(password, onPassword, "Password (xxxx-xxxx-xxxx)", KeyboardType.Password, secret = true)
        }
        if (problem != null) {
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.Warning, null, tint = Color(0xFFFFCACA), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(problem, color = Color(0xFFFFCACA), fontSize = 14.sp, lineHeight = 19.sp)
            }
        }
    }
    val ready = address.isNotBlank() && password.isNotBlank() && !connecting
    Primary(if (connecting) "Connecting…" else "Connect", busy = connecting, enabled = ready) {
        onConnect(null, null)
        HomeServer.configure(address, password)
        scope.launch {
            val ok = HomeServer.check(force = true)
            when {
                ok -> onConnect(true, null)
                HomeServer.state.value == HomeServer.State.WrongPassword -> onConnect(false, "That password isn't right.")
                else -> onConnect(false, "Can't reach the server. Check the address, and that your phone is on the same Wi-Fi (or that the address works from outside).")
            }
        }
    }
}

@Composable
private fun ColumnScope.Done(home: HomeServer.State, finish: () -> Unit) {
    val online = home is HomeServer.State.Online && HomeServer.configured
    Spacer(Modifier.weight(1f))
    Icon(
        if (online) Icons.Rounded.CheckCircle else Icons.Rounded.MusicNote, null,
        tint = if (online) Good else AccentLight, modifier = Modifier.size(68.dp).align(Alignment.CenterHorizontally),
    )
    Text(
        "You're all set", color = TextPrimary, fontSize = 32.sp, fontWeight = FontWeight.Black,
        modifier = Modifier.padding(top = 20.dp).align(Alignment.CenterHorizontally),
    )
    Text(
        if (home is HomeServer.State.Online && online) "Your home server is connected and keeps ${home.songs} songs for you. When you're away from home, Tunio works on its own."
        else "Tunio works on its own. You can connect a home server any time in Settings.",
        color = Muted, fontSize = 16.sp, lineHeight = 22.sp, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
    )
    Spacer(Modifier.weight(1f))
    Primary("Start listening", onClick = finish)
}

// pieces

@Composable
private fun Feature(icon: ImageVector, title: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = AccentLight, modifier = Modifier.padding(top = 2.dp).size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(text, color = Muted, fontSize = 14.sp, lineHeight = 19.sp)
        }
    }
}

@Composable
private fun Option(icon: ImageVector, title: String, text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface30).clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = AccentLight, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(text, color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun FlatField(value: String, onChange: (String) -> Unit, hint: String, type: KeyboardType, secret: Boolean) {
    TextField(
        value = value, onValueChange = onChange, singleLine = true,
        placeholder = { Text(hint, color = Muted.copy(alpha = 0.7f)) },
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = type, autoCorrectEnabled = false),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = AccentLight,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Primary(text: String, busy: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(27.dp))
            .background(Color.White.copy(alpha = if (enabled || busy) 1f else 0.5f))
            .clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Bg)
            Spacer(Modifier.width(10.dp))
        }
        Text(text, color = Bg, fontWeight = FontWeight.Bold, fontSize = 17.sp)
    }
}
