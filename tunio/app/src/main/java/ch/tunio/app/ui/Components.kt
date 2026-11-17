@file:OptIn(ExperimentalMaterial3Api::class)

package ch.tunio.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.tunio.app.data.Library
import ch.tunio.app.data.Source
import ch.tunio.app.data.Track
import ch.tunio.app.data.formatDuration
import ch.tunio.app.engine.AudioStore
import ch.tunio.app.player.PlayerController
import ch.tunio.app.player.shortError
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

@Composable
fun Artwork(url: String?, size: Dp, corner: Dp = 6.dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(corner)).background(Surface2),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.MusicNote, null, tint = Muted, modifier = Modifier.size(size / 2.5f))
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        }
    }
}

@Composable
fun SourceBadge(source: Source) {
    Text(
        source.short,
        color = Color.Black,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(sourceColor(source))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

/** Small icon that shows "downloaded" / download progress for a track. */
@Composable
fun OfflineIndicator(track: Track, size: Dp = 16.dp) {
    val offline by AudioStore.offline.collectAsState()
    val progress by AudioStore.progress.collectAsState()
    val p = progress[track.id]
    when {
        track.id in offline -> Icon(Icons.Filled.DownloadDone, "Downloaded", tint = Good, modifier = Modifier.size(size))
        p != null && p < 0f -> CircularProgressIndicator(modifier = Modifier.size(size), strokeWidth = 2.dp)
        p != null -> CircularProgressIndicator(progress = { p }, modifier = Modifier.size(size), strokeWidth = 2.dp)
    }
}

@Composable
fun TrackRow(
    track: Track,
    onClick: () -> Unit,
    playlistId: String? = null,
) {
    val queue by PlayerController.queue.collectAsState()
    val index by PlayerController.index.collectAsState()
    val isCurrent = queue.getOrNull(index)?.id == track.id
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(track.thumbnail, 50.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isCurrent) Accent else Color.White,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                SourceBadge(track.source)
                OfflineIndicator(track, 13.dp)
                Text(
                    listOf(track.artist, formatDuration(track.durationSec)).filter { it.isNotBlank() }.joinToString(" · "),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Muted,
                    fontSize = 13.sp,
                )
            }
        }
        TrackMenu(track, playlistId)
    }
}

@Composable
fun TrackMenu(track: Track, playlistId: String? = null, tint: Color = Muted) {
    var open by remember { mutableStateOf(false) }
    val lib by Library.data.collectAsState()
    val offline by AudioStore.offline.collectAsState()
    val scope = rememberCoroutineScope()
    val liked = track.id in lib.liked
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "More", tint = tint) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuItem("Play next", Icons.Filled.SkipNext) { open = false; PlayerController.playNext(track) }
            MenuItem("Add to queue", Icons.AutoMirrored.Filled.QueueMusic) { open = false; PlayerController.addToQueue(track) }
            MenuItem(if (liked) "Remove from Liked" else "Like", if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder) {
                open = false; Library.toggleLike(track)
            }
            MenuItem("Add to playlist", Icons.AutoMirrored.Filled.PlaylistAdd) { open = false; Ui.addToPlaylist.value = listOf(track) }
            if (track.id in offline) {
                MenuItem("Remove download", Icons.Filled.Delete) { open = false; AudioStore.removeDownload(track) }
            } else {
                MenuItem("Download (offline)", Icons.Filled.Download) {
                    open = false
                    scope.launch {
                        runCatching { AudioStore.download(track) }
                            .onFailure { PlayerController.messages.tryEmit("Download failed: ${shortError(it)}") }
                    }
                }
            }
            if (playlistId != null) {
                HorizontalDivider()
                MenuItem("Remove from playlist", Icons.Filled.RemoveCircleOutline) {
                    open = false; Library.removeFromPlaylist(playlistId, track.id)
                }
            }
        }
    }
}

@Composable
private fun MenuItem(text: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, leadingIcon = { Icon(icon, null) }, onClick = onClick)
}

@Composable
fun AddToPlaylistDialog() {
    val tracks by Ui.addToPlaylist.collectAsState()
    val pending = tracks ?: return
    val lib by Library.data.collectAsState()
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { Ui.addToPlaylist.value = null },
        title = { Text(if (pending.size == 1) "Add to playlist" else "Add ${pending.size} songs to playlist") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("New playlist") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        Library.createPlaylist(newName, pending, cover = pending.firstOrNull()?.thumbnail)
                        PlayerController.messages.tryEmit("Created \"${newName.ifBlank { "My playlist" }}\"")
                        Ui.addToPlaylist.value = null
                    }) { Icon(Icons.Filled.Add, "Create") }
                }
                Spacer(Modifier.size(8.dp))
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(lib.playlists, key = { it.id }) { p ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    Library.addToPlaylist(p.id, pending)
                                    PlayerController.messages.tryEmit("Added to ${p.name}")
                                    Ui.addToPlaylist.value = null
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Artwork(p.cover ?: Library.track(p.trackIds.firstOrNull() ?: "")?.thumbnail, 40.dp)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${p.trackIds.size} songs", color = Muted, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { Ui.addToPlaylist.value = null }) { Text("Cancel") } },
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
}
