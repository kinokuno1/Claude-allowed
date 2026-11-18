package ch.tunio.app.ui

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.layout.offset
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
import ch.tunio.app.engine.SpotifyAccount
import ch.tunio.app.player.PlaybackService
import ch.tunio.app.player.PlayerController
import ch.tunio.app.player.shortError
import com.google.common.util.concurrent.ListenableFuture
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var controller: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Draw behind the status/navigation bars so the glass and aurora reach the screen edges.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        handleShare(intent)
        setContent { TunioTheme { App() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        val data = intent?.data
        if (intent?.action == Intent.ACTION_VIEW && data?.scheme == "tunio" && data.host == "spotify-callback") {
            intent.data = null // don't handle twice on rotation
            lifecycleScope.launch {
                val msg = runCatching { SpotifyAccount.finishLogin(data) }.getOrElse { "Spotify login failed: ${shortError(it)}" }
                PlayerController.messages.tryEmit(msg)
                if (SpotifyAccount.loggedIn) {
                    Ui.tab(Route.Library)
                    Ui.push(Route.SpotifyImport)
                }
            }
            return
        }
        if (intent?.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
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

private data class Tab(val route: Route, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Route.Home, "Home", Icons.Rounded.Home),
    Tab(Route.Search, "Search", Icons.Rounded.Search),
    Tab(Route.Library, "Library", Icons.Rounded.LibraryMusic),
)

@Composable
private fun App() {
    val stack by Ui.stack.collectAsState()
    val playerOpen by Ui.playerOpen.collectAsState()
    val queue by PlayerController.queue.collectAsState()
    val snackbar = remember { SnackbarHostState() }
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
    BackHandler(enabled = playerOpen || stack.size > 1 || route != Route.Home) { Ui.back() }

    // Height of the floating bars, so screens can scroll their last items out from under them.
    val chromeHeight = 64.dp + 16.dp + (if (hasPlayer) 68.dp else 0.dp)

    Box(Modifier.fillMaxSize().background(Bg)) {
        // Everything the floating bars refract: background light + the current screen.
        Box(Modifier.fillMaxSize().layerBackdrop(screenBackdrop)) {
            Aurora(Modifier.layerBackdrop(lightBackdrop))
            CompositionLocalProvider(LocalBottomInset provides chromeHeight, LocalGlassBackdrop provides lightBackdrop) {
                Box(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
                    when (val r = route) {
                        Route.Home -> HomeScreen()
                        Route.Search -> SearchScreen()
                        Route.Library -> LibraryScreen()
                        Route.Settings -> SettingsScreen()
                        Route.SpotifyImport -> SpotifyImportScreen()
                        Route.Liked, Route.Downloads, is Route.PlaylistPage -> CollectionScreen(r)
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
    }
}

/** Floating liquid-glass tab bar; a brighter glass drop slides under the selected tab. */
@Composable
private fun GlassTabBar(backdrop: Backdrop, selected: Route) {
    val shape = RoundedCornerShape(32.dp)
    val index = tabs.indexOfFirst { it.route == selected }.coerceAtLeast(0)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .chromeGlass(backdrop, shape),
    ) {
        val itemWidth = maxWidth / tabs.size
        val lensOffset by animateDpAsState(
            targetValue = itemWidth * index,
            animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
            label = "lens",
        )
        // The moving lens.
        Box(
            Modifier
                .offset(x = lensOffset)
                .width(itemWidth)
                .fillMaxHeight()
                .padding(6.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(Color.White.copy(alpha = 0.10f))
                .background(Glass.sheen)
                .border(1.dp, Glass.rim, RoundedCornerShape(26.dp)),
        )
        Row(Modifier.fillMaxSize()) {
            tabs.forEachIndexed { i, t ->
                val active = i == index
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { Ui.tab(t.route) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    Icon(t.icon, t.label, tint = if (active) TextPrimary else Muted, modifier = Modifier.size(24.dp))
                    Text(
                        t.label,
                        color = if (active) TextPrimary else Muted,
                        fontSize = 11.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}
