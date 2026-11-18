@file:OptIn(ExperimentalMaterial3Api::class)

package ch.tunio.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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

// ------------------------------------------------------------------ Building blocks

@Composable
fun Artwork(url: String?, size: Dp, corner: Dp = 10.dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(corner)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF2A2440), Color(0xFF1A1A24))))
            .border(1.dp, Glass.rim, shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.MusicNote, null, tint = Muted, modifier = Modifier.size(size / 2.6f))
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        }
    }
}

/** Square tile with the accent gradient and an icon (Liked, Downloads, Spotify). */
@Composable
fun GradientTile(icon: ImageVector, brush: Brush, size: Dp, corner: Dp = 12.dp) {
    val shape = RoundedCornerShape(corner)
    Box(
        Modifier.size(size).clip(shape).background(brush).background(Glass.sheen).border(1.dp, Glass.rim, shape),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(size * 0.42f)) }
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, corner: Dp = 24.dp, content: @Composable () -> Unit) {
    Box(modifier.glass(RoundedCornerShape(corner))) { content() }
}

/** Round glass button for icons. */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    size: Dp = 44.dp,
    tint: Color = TextPrimary,
    enabled: Boolean = true,
) {
    Box(
        Modifier
            .size(size)
            .glass(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = if (enabled) tint else Muted, modifier = Modifier.size(size * 0.5f))
    }
}

/** Primary action: accent gradient pill with white text. */
@Composable
fun AccentButton(text: String, onClick: () -> Unit, icon: ImageVector? = null, enabled: Boolean = true, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .heightIn(min = 46.dp)
            .clip(shape)
            .then(if (enabled) Modifier.background(AccentGradient).background(Glass.sheen) else Modifier.background(Glass.fill))
            .border(1.dp, Glass.rim, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val color = if (enabled) Color.White else Muted
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = color, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

/** Secondary action: glass pill. */
@Composable
fun GlassButton(text: String, onClick: () -> Unit, icon: ImageVector? = null, enabled: Boolean = true, modifier: Modifier = Modifier) {
    Row(
        modifier
            .heightIn(min = 44.dp)
            .glass(RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val color = if (enabled) TextPrimary else Muted
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = color, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

/** Selectable capsule. Selected = solid [color] with black text, otherwise glass with light text. */
@Composable
fun GlassChip(label: String, selected: Boolean, color: Color = AccentLight, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .clip(shape)
            .then(if (selected) Modifier.background(color).background(Glass.sheen) else Modifier.background(Glass.fill))
            .border(1.dp, Glass.rim, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(label, color = if (selected) Color.Black else TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

/** Search/text input inside a glass capsule (white text, never dark-on-dark). */
@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    leading: ImageVector,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
) {
    Row(
        modifier
            .heightIn(min = 52.dp)
            .glass(RoundedCornerShape(50), strong = true)
            .padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(leading, null, tint = TextPrimary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
            if (value.isEmpty()) Text(placeholder, color = Muted, fontSize = 16.sp, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = TextPrimary, fontSize = 16.sp),
                cursorBrush = SolidColor(AccentLight),
                keyboardOptions = KeyboardOptions(imeAction = if (onSearch != null) ImeAction.Search else ImeAction.Done),
                keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        trailing?.invoke()
    }
}

@Composable
fun glassFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedBorderColor = AccentLight,
    unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
    focusedLabelColor = AccentLight,
    unfocusedLabelColor = Muted,
    cursorColor = AccentLight,
    focusedContainerColor = Glass.fill,
    unfocusedContainerColor = Glass.fill,
    focusedPlaceholderColor = Muted,
    unfocusedPlaceholderColor = Muted,
)

@Composable
fun SourceBadge(source: Source) {
    Text(
        source.short,
        color = Color.Black,
        fontSize = 9.sp,
        fontWeight = FontWeight.Black,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
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
        track.id in offline -> Icon(Icons.Rounded.DownloadDone, "Downloaded", tint = Good, modifier = Modifier.size(size))
        p != null && p < 0f -> CircularProgressIndicator(modifier = Modifier.size(size), strokeWidth = 2.dp, color = AccentLight)
        p != null -> CircularProgressIndicator(progress = { p }, modifier = Modifier.size(size), strokeWidth = 2.dp, color = AccentLight)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = SectionStyle, modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 10.dp))
}

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier, actions: @Composable () -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 18.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = androidx.compose.material3.MaterialTheme.typography.headlineMedium, color = TextPrimary, modifier = Modifier.weight(1f))
        actions()
    }
}

// ------------------------------------------------------------------ Tracks

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
            .padding(horizontal = 10.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(18.dp))
            .then(if (isCurrent) Modifier.glass(RoundedCornerShape(18.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(track.thumbnail, 52.dp, 12.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isCurrent) AccentLight else TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
        Box(
            Modifier.size(44.dp).clip(CircleShape).clickable { open = true },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.MoreVert, "More", tint = tint) }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(20.dp),
            containerColor = MenuBg,
            border = BorderStroke(1.dp, Glass.rim),
        ) {
            MenuItem("Play next", Icons.Rounded.SkipNext) { open = false; PlayerController.playNext(track) }
            MenuItem("Add to queue", Icons.AutoMirrored.Rounded.QueueMusic) { open = false; PlayerController.addToQueue(track) }
            MenuItem(if (liked) "Remove from Liked" else "Like", if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder) {
                open = false; Library.toggleLike(track)
            }
            MenuItem("Add to playlist", Icons.AutoMirrored.Rounded.PlaylistAdd) { open = false; Ui.addToPlaylist.value = listOf(track) }
            if (track.id in offline) {
                MenuItem("Remove download", Icons.Rounded.Delete) { open = false; AudioStore.removeDownload(track) }
            } else {
                MenuItem("Download (offline)", Icons.Rounded.Download) {
                    open = false
                    scope.launch {
                        runCatching { AudioStore.download(track) }
                            .onFailure { PlayerController.messages.tryEmit("Download failed: ${shortError(it)}") }
                    }
                }
            }
            if (playlistId != null) {
                HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
                MenuItem("Remove from playlist", Icons.Rounded.RemoveCircleOutline) {
                    open = false; Library.removeFromPlaylist(playlistId, track.id)
                }
            }
        }
    }
}

@Composable
private fun MenuItem(text: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = { Icon(icon, null) },
        onClick = onClick,
        colors = MenuDefaults.itemColors(textColor = TextPrimary, leadingIconColor = AccentLight),
        contentPadding = PaddingValues(horizontal = 16.dp),
    )
}

// ------------------------------------------------------------------ Dialogs

@Composable
fun GlassDialog(
    title: String,
    onDismiss: () -> Unit,
    confirm: @Composable () -> Unit = {},
    dismissLabel: String = "Cancel",
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = TextPrimary, fontWeight = FontWeight.Bold) },
        // Dialogs are a separate window, so glass inside them uses the frosted fallback.
        text = { CompositionLocalProvider(LocalGlassBackdrop provides null) { content() } },
        confirmButton = confirm,
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel, color = AccentLight) } },
        containerColor = MenuBg,
        titleContentColor = TextPrimary,
        textContentColor = Muted,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.border(1.dp, Glass.rim, RoundedCornerShape(28.dp)),
    )
}

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    GlassDialog(
        title = title,
        onDismiss = onDismiss,
        confirm = { TextButton(onClick = { onConfirm(name.trim()) }) { Text("OK", color = AccentLight, fontWeight = FontWeight.Bold) } },
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            label = { Text("Name") },
            colors = glassFieldColors(),
            shape = RoundedCornerShape(16.dp),
        )
    }
}

@Composable
fun AddToPlaylistDialog() {
    val tracks by Ui.addToPlaylist.collectAsState()
    val pending = tracks ?: return
    val lib by Library.data.collectAsState()
    var newName by remember { mutableStateOf("") }
    GlassDialog(
        title = if (pending.size == 1) "Add to playlist" else "Add ${pending.size} songs to playlist",
        onDismiss = { Ui.addToPlaylist.value = null },
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("New playlist") },
                    singleLine = true,
                    colors = glassFieldColors(),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                GlassIconButton(Icons.Rounded.Add, "Create", onClick = {
                    Library.createPlaylist(newName, pending, cover = pending.firstOrNull()?.thumbnail)
                    PlayerController.messages.tryEmit("Created \"${newName.ifBlank { "My playlist" }}\"")
                    Ui.addToPlaylist.value = null
                })
            }
            Spacer(Modifier.height(10.dp))
            LazyColumn(Modifier.heightIn(max = 300.dp)) {
                items(lib.playlists, key = { it.id }) { p ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                Library.addToPlaylist(p.id, pending)
                                PlayerController.messages.tryEmit("Added to ${p.name}")
                                Ui.addToPlaylist.value = null
                            }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(p.cover ?: Library.track(p.trackIds.firstOrNull() ?: "")?.thumbnail, 44.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(p.name, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            Text("${p.trackIds.size} songs", color = Muted, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
