package ch.tunio.app.ui

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import ch.tunio.app.player.PlaybackService
import ch.tunio.app.player.PlayerController
import com.google.common.util.concurrent.ListenableFuture

class MainActivity : ComponentActivity() {
    private var controller: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShare(intent)
        setContent { TunioTheme { App() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
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

@Composable
private fun App() {
    val stack by Ui.stack.collectAsState()
    val playerOpen by Ui.playerOpen.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val route = stack.last()
    val tab = stack.first()

    LaunchedEffect(Unit) {
        PlayerController.messages.collect { snackbar.showSnackbar(it) }
    }
    BackHandler(enabled = playerOpen || stack.size > 1 || route != Route.Home) { Ui.back() }

    Box(Modifier.fillMaxSize().background(Bg).safeDrawingPadding()) {
        Scaffold(
            containerColor = Bg,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                Column {
                    MiniPlayer()
                    NavigationBar(containerColor = Bg) {
                        listOf(
                            Triple(Route.Home, "Home", Icons.Filled.Home),
                            Triple(Route.Search, "Search", Icons.Filled.Search),
                            Triple(Route.Library, "Library", Icons.Filled.LibraryMusic),
                        ).forEach { (r, label, icon) ->
                            NavigationBarItem(
                                selected = tab == r,
                                onClick = { Ui.tab(r) },
                                icon = { Icon(icon, label) },
                                label = { Text(label) },
                                colors = NavigationBarItemDefaults.colors(indicatorColor = Surface2),
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding)) {
                when (val r = route) {
                    Route.Home -> HomeScreen()
                    Route.Search -> SearchScreen()
                    Route.Library -> LibraryScreen()
                    Route.Settings -> SettingsScreen()
                    Route.Liked, Route.Downloads, is Route.PlaylistPage -> CollectionScreen(r)
                }
            }
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
