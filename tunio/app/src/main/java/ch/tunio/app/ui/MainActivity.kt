package ch.tunio.app.ui

import android.Manifest
import android.content.ComponentName
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import ch.tunio.app.data.Library
import ch.tunio.app.engine.HomeServer
import ch.tunio.app.engine.SpotifyAccount
import ch.tunio.app.player.PlaybackService
import ch.tunio.app.player.PlayerController
import ch.tunio.app.player.shortError
import com.google.common.util.concurrent.ListenableFuture
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.launch

/** Shows the login result and, on success, opens the Spotify playlist list. */
fun onSpotifyLogin(message: String) {
    PlayerController.messages.tryEmit(message)
    if (SpotifyAccount.loggedIn) {
        Ui.tab(Route.Library)
        Ui.push(Route.SpotifyImport)
    }
}

class MainActivity : ComponentActivity() {
    private var controller: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Draw behind the status/navigation bars so the glass and aurora reach the screen edges.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        askForNotifications()
        handleShare(intent)
        setContent { TunioTheme { App() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Android 13+: needed to show download progress and the Spotify-login notice (asked once). */
    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("ui", 0)
        if (prefs.getBoolean("asked_notifications", false)) return
        prefs.edit().putBoolean("asked_notifications", true).apply()
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun handleShare(intent: Intent?) {
        val data = intent?.data
        if (intent?.action == Intent.ACTION_VIEW && data?.scheme == "tunio" && data.host == "pair") {
            // "Connect Tunio" button on the home server's page: tunio://pair?u=<address>&k=<password>
            intent.data = null
            val address = data.getQueryParameter("u")
            val password = data.getQueryParameter("k")
            if (!address.isNullOrBlank() && !password.isNullOrBlank()) {
                HomeServer.configure(address, password)
                lifecycleScope.launch { PlayerController.messages.emit(HomeServer.test()) }
            }
            return
        }
        if (intent?.action == Intent.ACTION_VIEW && data?.scheme == "tunio" && data.host == "spotify-callback") {
            intent.data = null // don't handle twice on rotation
            lifecycleScope.launch {
                onSpotifyLogin(runCatching { SpotifyAccount.finishLogin(data) }.getOrElse { "Spotify login failed: ${shortError(it)}" })
            }
            return
        }
        if (intent?.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            if (SpotifyAccount.pastedCallback(text) != null) {
                lifecycleScope.launch { onSpotifyLogin(SpotifyAccount.finishFromPaste(text)) }
                return
            }
            SearchModel.openLink(text)
        }
    }

    override fun onStart() {
        super.onStart()
        // Connecting to the session service is what lets music keep playing in the background.
        controller = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
    }

    override fun onStop() {
        controller?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onStop()
    }
}

/** Outlined icon when not selected, filled when selected. */
private data class Tab(val route: Route, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val tabs = listOf(
    Tab(Route.Home, "Home", Icons.Outlined.Home, Icons.Rounded.Home),
    Tab(Route.Search, "Search", Icons.Outlined.Search, Icons.Rounded.Search),
    Tab(Route.Library, "Library", Icons.Outlined.LibraryMusic, Icons.Rounded.LibraryMusic),
)

@Composable
private fun App() {
    val stack by Ui.stack.collectAsState()
    val playerOpen by Ui.playerOpen.collectAsState()
    val queue by PlayerController.queue.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scanning by QrScan.open.collectAsState()
    var onboarded by remember { mutableStateOf(Onboarding.done(context)) }
    // Two light sources for the glass: the background light (for glass inside screens) and the whole
    // screen incl. scrolling content (for the floating bars, so you see content bend as it passes under).
    val screenBackdrop = rememberLayerBackdrop()
    val lightBackdrop = rememberLayerBackdrop()
    val route = stack.last()
    val tab = stack.first()
    val hasPlayer = queue.isNotEmpty()

    LaunchedEffect(Unit) {
        PlayerController.messages.collect { snackbar.showSnackbar(it) }
    }
    BackHandler(enabled = onboarded && !scanning && (playerOpen || stack.size > 1 || route != Route.Home)) { Ui.back() }

    // Height of the floating bars, so screens can scroll their last items out from under them.
    val chromeHeight = 64.dp + 16.dp + (if (hasPlayer) 68.dp else 0.dp)

    Box(Modifier.fillMaxSize().background(Bg)) {
        // Everything the floating bars refract: background light + the current screen.
        Box(Modifier.fillMaxSize().layerBackdrop(screenBackdrop)) {
            // Menus get a plain gradient; inside a playlist, its cover blurred behind everything.
            val lib by Library.data.collectAsState()
            val cover = when (route) {
                is Route.PlaylistPage -> lib.playlists.find { it.id == route.id }?.let { p ->
                    p.cover ?: p.trackIds.firstOrNull()?.let { lib.tracks[it]?.thumbnail }
                }
                Route.Liked -> lib.liked.firstOrNull()?.let { lib.tracks[it]?.thumbnail }
                else -> null
            }
            if (route is Route.PlaylistPage || route == Route.Liked) {
                CoverBackground(cover, Modifier.layerBackdrop(lightBackdrop))
            } else {
                GradientBackground(Modifier.layerBackdrop(lightBackdrop))
            }
            CompositionLocalProvider(LocalBottomInset provides chromeHeight, LocalGlassBackdrop provides lightBackdrop) {
                Box(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
                    when (val r = route) {
                        Route.Home -> HomeScreen()
                        Route.Search -> SearchScreen()
                        Route.Library -> LibraryScreen()
                        Route.Settings -> SettingsScreen()
                        Route.SpotifyImport -> SpotifyImportScreen()
                        Route.Liked, Route.Downloads, Route.Recent, is Route.PlaylistPage -> CollectionScreen(r)
                    }
                }
            }
        }

        // Floating liquid-glass chrome.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SnackbarHost(snackbar) { data ->
                Snackbar(
                    data,
                    shape = RoundedCornerShape(20.dp),
                    containerColor = MenuBg,
                    contentColor = TextPrimary,
                    actionColor = AccentLight,
                    modifier = Modifier.border(1.dp, Glass.rim, RoundedCornerShape(20.dp)),
                )
            }
            MiniPlayer(screenBackdrop)
            if (hasPlayer) Spacer(Modifier.height(8.dp))
            GlassTabBar(screenBackdrop, tab)
        }

        AnimatedVisibility(
            visible = playerOpen,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            NowPlayingScreen()
        }
        AddToPlaylistDialog()

        // First launch: welcome + home server setup, over everything.
        AnimatedVisibility(visible = !onboarded, enter = fadeIn(), exit = fadeOut()) {
            OnboardingScreen {
                Onboarding.finish(context)
                onboarded = true
            }
        }
        // In-app QR scanner for the home server, above everything (setup included).
        QrScannerOverlay()
    }
}

/**
 * Floating liquid-glass tab bar. Switching tabs: the glass pill first braces (squeezes in), then its
 * leading edge shoots toward the new tab while the trailing edge lags behind — so it stretches like a drop —
 * and both edges overshoot and wobble before settling.
 */
@Composable
private fun GlassTabBar(backdrop: Backdrop, selected: Route) {
    val shape = RoundedCornerShape(32.dp)
    val index = tabs.indexOfFirst { it.route == selected }.coerceAtLeast(0)
    BoxWithConstraints(
        Modifier
            .widthIn(max = 300.dp)
            .fillMaxWidth()
            .height(64.dp)
            .chromeGlass(backdrop, shape),
    ) {
        val density = LocalDensity.current
        val itemPx = with(density) { (maxWidth / tabs.size).toPx() }
        val left = remember(itemPx) { Animatable(index * itemPx) }
        val right = remember(itemPx) { Animatable((index + 1) * itemPx) }
        val squeeze = remember { Animatable(1f) }

        LaunchedEffect(index, itemPx) {
            val targetLeft = index * itemPx
            val targetRight = targetLeft + itemPx
            if (left.value == targetLeft && right.value == targetRight) return@LaunchedEffect
            val toRight = targetLeft > left.value
            // 1. Brace: a quick squeeze.
            squeeze.animateTo(0.86f, tween(durationMillis = 90, easing = FastOutSlowInEasing))
            coroutineScope {
                // 2. Release with a bouncy spring.
                launch { squeeze.animateTo(1f, spring(dampingRatio = 0.32f, stiffness = 520f)) }
                // 3. Leading edge rushes ahead, trailing edge follows later and softer -> stretch, then jiggle.
                val lead = if (toRight) right else left
                val trail = if (toRight) left else right
                launch { lead.animateTo(if (toRight) targetRight else targetLeft, spring(dampingRatio = 0.5f, stiffness = 380f)) }
                launch {
                    delay(70)
                    trail.animateTo(if (toRight) targetLeft else targetRight, spring(dampingRatio = 0.38f, stiffness = 230f))
                }
            }
        }

        // The jelly pill.
        Box(
            Modifier
                .layout { measurable, constraints ->
                    val l = left.value
                    val w = (right.value - l).coerceAtLeast(1f).roundToInt()
                    val placeable = measurable.measure(constraints.copy(minWidth = w, maxWidth = w))
                    layout(constraints.maxWidth, placeable.height) { placeable.place(l.roundToInt(), 0) }
                }
                .fillMaxHeight()
                .graphicsLayer {
                    scaleX = squeeze.value
                    scaleY = 1f - (1f - squeeze.value) * 0.6f
                }
                .padding(6.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(Color.White.copy(alpha = 0.12f)),
        )
        Row(Modifier.fillMaxSize()) {
            tabs.forEachIndexed { i, t ->
                TabItem(t, active = i == index, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** One tab: outlined + muted when idle; when selected it turns filled, flashes and wiggles a little. */
@Composable
private fun TabItem(tab: Tab, active: Boolean, modifier: Modifier) {
    val flash = remember { Animatable(0f) }
    val wiggle = remember { Animatable(0f) }
    val pop = remember { Animatable(1f) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        launch {
            flash.snapTo(1f)
            flash.animateTo(0f, tween(durationMillis = 520, easing = LinearOutSlowInEasing))
        }
        launch {
            pop.snapTo(0.85f)
            pop.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = 600f))
        }
        wiggle.snapTo(0f)
        wiggle.animateTo(0f, keyframes {
            durationMillis = 460
            -9f at 70
            7f at 160
            -4f at 250
            2f at 340
        })
    }
    Column(
        modifier
            .fillMaxHeight()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { Ui.tab(tab.route) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Flash: a soft glow that blooms and fades behind the icon.
            Box(
                Modifier
                    .size(40.dp)
                    .graphicsLayer {
                        alpha = flash.value
                        scaleX = 1f + (1f - flash.value) * 0.5f
                        scaleY = scaleX
                    }
                    .background(Color.White.copy(alpha = 0.18f), CircleShape),
            )
            Icon(
                if (active) tab.selectedIcon else tab.icon,
                tab.label,
                tint = if (active) AccentLight else Muted,
                modifier = Modifier
                    .size(24.dp)
                    .graphicsLayer {
                        rotationZ = wiggle.value
                        scaleX = pop.value
                        scaleY = pop.value
                    },
            )
        }
        Text(
            tab.label,
            color = if (active) AccentLight else Muted,
            fontSize = 11.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
        )
    }
}
