package ch.tunio.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import ch.tunio.app.data.Source

val Bg = Color(0xFF0E0E12)
val Surface1 = Color(0xFF17171D)
val Surface2 = Color(0xFF23232B)
val Accent = Color(0xFF8B5CF6)
val Accent2 = Color(0xFFEC4899)
val Muted = Color(0xFFA1A1AA)
val Good = Color(0xFF34D399)

fun sourceColor(s: Source): Color = when (s) {
    Source.YOUTUBE -> Color(0xFFFF4E45)
    Source.SOUNDCLOUD -> Color(0xFFFF7A1A)
    Source.TIKTOK -> Color(0xFF25F4EE)
    Source.SPOTIFY -> Color(0xFF1ED760)
    Source.OTHER -> Muted
}

private val scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = Accent2,
    onSecondary = Color.White,
    background = Bg,
    onBackground = Color.White,
    surface = Bg,
    onSurface = Color.White,
    surfaceVariant = Surface2,
    onSurfaceVariant = Muted,
    surfaceContainer = Surface1,
    surfaceContainerHigh = Surface2,
    surfaceContainerHighest = Surface2,
    surfaceContainerLow = Surface1,
    outline = Color(0xFF3F3F46),
)

@Composable
fun TunioTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, content = content)
