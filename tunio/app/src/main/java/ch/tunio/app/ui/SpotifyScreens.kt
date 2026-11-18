@file:OptIn(ExperimentalMaterial3Api::class)

package ch.tunio.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Color
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
    Column {
        if (user != null) {
            Text("Connected as $user", color = Good)
            Text("Private playlists and Liked Songs can be imported from Library → Spotify.", color = Muted, fontSize = 13.sp)
            Row {
                TextButton(onClick = { Ui.push(Route.SpotifyImport) }) { Text("Open my Spotify playlists") }
                TextButton(onClick = { SpotifyAccount.logout() }) { Text("Log out") }
            }
            return@Column
        }
        Text(
            "Needed only for private playlists and Liked Songs (public links work without it). One-time setup:",
            color = Muted, fontSize = 13.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "1. On developer.spotify.com/dashboard log in and tap Create app (any name/description).\n" +
                "2. Redirect URI: add the one below. Under \"APIs used\" tick Web API. Save.\n" +
                "3. Open the app's Settings, copy the Client ID and paste it here.\n" +
                "4. Tap Log in with Spotify.",
            fontSize = 13.sp,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(SpotifyAccount.REDIRECT_URI, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(SpotifyAccount.REDIRECT_URI))
                PlayerController.messages.tryEmit("Redirect URI copied")
            }) { Text("Copy") }
        }
        Row {
            TextButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://developer.spotify.com/dashboard")))
            }) { Text("Open Spotify dashboard") }
        }
        OutlinedTextField(
            value = clientId,
            onValueChange = { clientId = it.trim() },
            label = { Text("Client ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(
            enabled = clientId.length >= 20,
            onClick = {
                SpotifyAccount.clientId = clientId
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SpotifyAccount.authorizeUrl())))
                }.onFailure { PlayerController.messages.tryEmit("Couldn't open the browser: ${shortError(it)}") }
            },
        ) { Text("Log in with Spotify") }
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
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            TopAppBar(
                title = { Text("Spotify") },
                navigationIcon = { IconButton(onClick = { Ui.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg),
            )
        }
        if (user == null) {
            item {
                Column(Modifier.padding(16.dp)) {
                    Text("Log in to Spotify to import your private playlists and Liked Songs.", color = Muted)
                    Spacer(Modifier.height(8.dp))
                    SpotifyAccountCard()
                }
            }
            return@LazyColumn
        }
        item {
            Text(
                "Tap a playlist to open it, then Play or Save as playlist (saving keeps it in Tunio; download it from there for offline).",
                color = Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        item {
            ImportRow("Liked Songs", "Your saved songs on Spotify", null, liked = true) {
                SearchModel.load("Spotify: Liked Songs") { SpotifyAccount.likedSongs() }
            }
        }
        error?.let { e -> item { Text(e, color = Color(0xFFF87171), modifier = Modifier.padding(16.dp)) } }
        val list = playlists
        if (list == null && error == null) {
            item {
                Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
        }
        if (list != null) {
            items(list, key = { it.id }) { p ->
                ImportRow(p.name, "${p.count} songs", p.cover) {
                    SearchModel.load("Spotify: ${p.name}") { SpotifyAccount.playlist(p.id) }
                }
            }
        }
        item {
            OutlinedButton(onClick = { SpotifyAccount.logout() }, modifier = Modifier.padding(16.dp)) { Text("Log out of Spotify") }
        }
    }
}

@Composable
private fun ImportRow(title: String, subtitle: String, image: String?, liked: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (liked) {
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)).background(Brush.linearGradient(listOf(Accent, Accent2))),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Favorite, null, tint = Color.White) }
        } else {
            Artwork(image, 56.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Muted, fontSize = 13.sp)
        }
    }
}
