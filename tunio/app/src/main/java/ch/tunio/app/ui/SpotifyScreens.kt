package ch.tunio.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.tunio.app.engine.SpotifyAccount
import ch.tunio.app.player.PlayerController
import ch.tunio.app.player.shortError

/** Settings card: connect a Spotify account so private playlists and Liked Songs can be imported. */
@Composable
fun SpotifyAccountCard() {
    val user by SpotifyAccount.user.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var clientId by remember { mutableStateOf(SpotifyAccount.clientId) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (user != null) {
            Text("Connected as $user", color = Good, fontWeight = FontWeight.SemiBold)
            Text("Private playlists and Liked Songs can be imported from Library → Spotify.", color = Muted, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AccentButton("My playlists", onClick = { Ui.push(Route.SpotifyImport) })
                GlassButton("Log out", icon = Icons.AutoMirrored.Rounded.Logout, onClick = { SpotifyAccount.logout() })
            }
            return@Column
        }
        Text(
            "Needed only for private playlists and Liked Songs (public links work without it). One-time setup:",
            color = Muted, fontSize = 13.sp, lineHeight = 19.sp,
        )
        Text(
            "1. On developer.spotify.com/dashboard log in and tap Create app (any name/description).\n" +
                "2. Redirect URI: add exactly the one below (127.0.0.1, not localhost). Under \"APIs used\" tick Web API. Save.\n" +
                "3. Open the app's Settings, copy the Client ID and paste it here.\n" +
                "4. Tap Log in with Spotify.",
            color = TextPrimary, fontSize = 13.sp, lineHeight = 20.sp,
        )
        Row(
            Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp)).padding(start = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(SpotifyAccount.REDIRECT_URI, color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.weight(1f))
            GlassIconButton(Icons.Rounded.ContentCopy, "Copy", size = 40.dp, onClick = {
                clipboard.setText(AnnotatedString(SpotifyAccount.REDIRECT_URI))
                PlayerController.messages.tryEmit("Redirect URI copied")
            })
        }
        GlassButton("Open Spotify dashboard", icon = Icons.Rounded.OpenInNew, onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://developer.spotify.com/dashboard")))
        })
        OutlinedTextField(
            value = clientId,
            onValueChange = { clientId = it.trim() },
            label = { Text("Client ID") },
            singleLine = true,
            colors = glassFieldColors(),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        AccentButton(
            "Log in with Spotify",
            enabled = clientId.length >= 20,
            onClick = {
                SpotifyAccount.clientId = clientId
                SpotifyAccount.beginLogin(context) { message ->
                    PlayerController.messages.tryEmit(message)
                    if (SpotifyAccount.loggedIn) {
                        Ui.tab(Route.Library)
                        Ui.push(Route.SpotifyImport)
                    }
                }
            },
        )
    }
}

/** Lists the user's Spotify playlists (including private ones) + Liked Songs for import. */
@Composable
fun SpotifyImportScreen() {
    val user by SpotifyAccount.user.collectAsState()
    var playlists by remember { mutableStateOf<List<SpotifyAccount.PlaylistInfo>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(user) {
        if (user == null) return@LaunchedEffect
        error = null
        runCatching { SpotifyAccount.myPlaylists() }
            .onSuccess { playlists = it }
            .onFailure { error = shortError(it) }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current + 12.dp)) {
        item {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = { Ui.back() })
                Spacer(Modifier.width(14.dp))
                Text("Spotify", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
            }
        }
        if (user == null) {
            item {
                GlassCard(Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(Modifier.padding(18.dp)) {
                        Text("Log in to Spotify to import your private playlists and Liked Songs.", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(10.dp))
                        SpotifyAccountCard()
                    }
                }
            }
            return@LazyColumn
        }
        item {
            Text(
                "Tap a playlist to open it, then Play or Save as playlist. Saved playlists can be downloaded for offline.",
                color = Muted, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        item {
            GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    ImportRow("Liked Songs", "Your saved songs on Spotify", null, liked = true) {
                        SearchModel.load("Spotify: Liked Songs") { SpotifyAccount.likedSongs() }
                    }
                }
            }
        }
        error?.let { e -> item { Text(e, color = ErrorText, modifier = Modifier.padding(20.dp)) } }
        val list = playlists
        if (list == null && error == null) {
            item {
                Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = AccentLight)
                }
            }
        }
        if (list != null) {
            item { SectionTitle("Your playlists") }
            items(list, key = { it.id }) { p ->
                ImportRow(p.name, "${p.count} songs", p.cover) {
                    SearchModel.load("Spotify: ${p.name}") { SpotifyAccount.playlist(p.id) }
                }
            }
        }
        item {
            GlassButton("Log out of Spotify", icon = Icons.AutoMirrored.Rounded.Logout, onClick = { SpotifyAccount.logout() }, modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun ImportRow(title: String, subtitle: String, image: String?, liked: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (liked) GradientTile(Icons.Rounded.Favorite, Brush.linearGradient(listOf(AccentDeep, Accent2Deep)), 56.dp, 14.dp)
        else Artwork(image, 56.dp, 14.dp)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Muted, fontSize = 13.sp)
        }
    }
}
