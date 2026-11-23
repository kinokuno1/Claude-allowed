package ch.tunio.app.ui

import android.os.Build
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.tunio.app.data.Source

// ---- Palette (same violet/pink identity as before; foreground tints lightened so nothing dark sits on black)
// Every text color below is checked against its worst-case background (incl. glass over the brightest light and
// the player over a pure-white cover): lowest contrast is 5.6:1, above the WCAG 4.5:1 minimum.

val Bg = Color(0xFF0E0E12)
val Surface1 = Color(0xFF17171D)
val Surface2 = Color(0xFF23232B)
val MenuBg = Color(0xFF1E1C28)

/** Background light colors. */
val Accent = Color(0xFF8B5CF6)
val Accent2 = Color(0xFFEC4899)

/** Fills behind white text/icons (a shade deeper than Accent/Accent2 so white text reads ≥ 4.5:1). */
val AccentDeep = Color(0xFF7C4DEE)
val Accent2Deep = Color(0xFFD4337F)

/** Foreground versions for text/icons on dark backgrounds. */
val AccentLight = Color(0xFFC9B6FF)
val PinkLight = Color(0xFFFFA8D8)

val TextPrimary = Color(0xFFF7F7FA)
val Muted = Color(0xFFC8C8D2)
val Good = Color(0xFF6EE7B7)
val ErrorText = Color(0xFFFECACA)

/** Flat colours (no gradients), same as the iPhone and Windows apps. Primary actions are white with dark text. */
val LikedColor = Color(0xFF5B45B8)
val DownloadsColor = Color(0xFF0F6E5C)
val GlowColor = Color(0xFF3A3350)
val Surface30 = Color(0xFF1A1823)
val Surface30Hi = Color(0xFF252231)
val AccentGradient: Brush = androidx.compose.ui.graphics.SolidColor(Color.White)

fun sourceColor(s: Source): Color = when (s) {
    Source.YOUTUBE -> Color(0xFFFF5A52)
    Source.SOUNDCLOUD -> Color(0xFFFF8A3D)
    Source.TIKTOK -> Color(0xFF25F4EE)
    Source.SPOTIFY -> Color(0xFF1ED760)
    Source.OTHER -> Color(0xFFC4C4CC)
}

// ---- Liquid glass (Kyant0's Backdrop library: real lens refraction + blur of what's behind)

/** What in-content glass refracts: the background layer (gradient or blurred cover). Null = draw the frosted fallback. */
val LocalGlassBackdrop = staticCompositionLocalOf<Backdrop?> { null }

object Glass {
    /** Dark tint under every glass surface: keeps light text readable whatever is refracted behind it. */
    val tint = Bg.copy(alpha = 0.40f)
    val frost = Color.White.copy(alpha = 0.06f)
    val frostStrong = Color.White.copy(alpha = 0.10f)

    /** Plain translucent fill for small solid-ish shapes (chips, disabled buttons, text fields). */
    val fill = Color.White.copy(alpha = 0.08f)

    /** Specular rim for elements drawn without the library (artwork, fallback glass). */
    val rim = Brush.linearGradient(
        listOf(Color.White.copy(alpha = 0.38f), Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.16f)),
    )

    /** Soft reflection used on solid fills (buttons, tiles). */
    val sheen = Brush.verticalGradient(0f to Color.White.copy(alpha = 0.10f), 0.55f to Color.Transparent)

    /** Refraction runs on Android 13+, blur on 12+; below that the frosted fallback is used. */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** Frosted fallback (Android ≤ 11, or no backdrop): same tint/frost, no blur or refraction. */
    fun Modifier.frosted(shape: Shape, strong: Boolean = false): Modifier =
        this
            .clip(shape)
            .background(Surface1.copy(alpha = 0.92f), shape)
            .background(if (strong) frostStrong else frost, shape)
            .border(1.dp, rim, shape)
}

/**
 * Liquid glass surface: refracts (bends) and blurs whatever is behind it through a lens at its edges,
 * boosts its colors, then lays a dark tint + light frost so text on top stays readable.
 * [lensDp] is how deep the bent edge band is; bigger = more liquid.
 */
@Composable
fun Modifier.glass(
    shape: RoundedCornerShape = RoundedCornerShape(24.dp),
    strong: Boolean = false,
    backdrop: Backdrop? = LocalGlassBackdrop.current,
    blurDp: Dp = 6.dp,
    lensDp: Dp = 14.dp,
    tint: Color = Glass.tint,
): Modifier {
    if (backdrop == null || !Glass.supported) return with(Glass) { this@glass.frosted(shape, strong) }
    val frost = if (strong) Glass.frostStrong else Glass.frost
    return this
        .drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(blurDp.toPx())
                lens(lensDp.toPx(), lensDp.toPx() * 2f, depthEffect = true, chromaticAberration = true)
            },
            shadow = null,
            onDrawSurface = {
                drawRect(tint)
                drawRect(frost)
            },
        )
        .clip(shape)
}

/** Floating bars (tab bar, mini player): clearer glass with a deeper lens, bending the screen behind them. */
@Composable
fun Modifier.chromeGlass(backdrop: Backdrop, shape: RoundedCornerShape): Modifier =
    if (!Glass.supported) {
        with(Glass) { this@chromeGlass.frosted(shape) }
    } else {
        glass(shape, backdrop = backdrop, blurDp = 4.dp, lensDp = 22.dp, tint = Bg.copy(alpha = 0.50f))
            .border(1.dp, Glass.rim, shape)
    }

/** Menus (Home, Search, Library, Settings): near-black with a soft neutral glow at the top. */
val MenuGradient = Brush.verticalGradient(0f to GlowColor.copy(alpha = 0.6f), 0.35f to Bg, 1f to Bg)

@Composable
fun GradientBackground(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(Bg).background(MenuGradient))
}

/**
 * Inside a playlist (and the player): the cover art, hugely blurred, under a dark wash so all text stays
 * light-on-dark (checked against a pure-white cover). Without a cover or before Android 12: the gradient.
 */
@Composable
fun CoverBackground(cover: String?, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(Bg)) {
        if (cover == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Box(Modifier.fillMaxSize().background(MenuGradient))
        } else {
            Box(Modifier.fillMaxSize().background(MenuGradient))
            AsyncImage(
                model = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.4f; scaleY = 1.4f; alpha = 0.60f }.blur(90.dp),
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.62f), Color.Black.copy(alpha = 0.74f), Color.Black.copy(alpha = 0.86f))),
                ),
            )
        }
    }
}

private val scheme = darkColorScheme(
    // M3 uses `primary` mostly as a foreground (text buttons, progress, focused fields), so it is the light tint.
    primary = AccentLight,
    onPrimary = Color(0xFF1E1236),
    primaryContainer = AccentDeep,
    onPrimaryContainer = Color.White,
    secondary = PinkLight,
    onSecondary = Color(0xFF3A0B24),
    secondaryContainer = Color(0xFF3A2C5C),
    onSecondaryContainer = TextPrimary,
    background = Bg,
    onBackground = TextPrimary,
    surface = Bg,
    onSurface = TextPrimary,
    surfaceVariant = Surface2,
    onSurfaceVariant = Muted,
    surfaceContainer = MenuBg,
    surfaceContainerHigh = MenuBg,
    surfaceContainerHighest = Surface2,
    surfaceContainerLow = Surface1,
    inverseSurface = Color(0xFF2A2838),
    inverseOnSurface = TextPrimary,
    inversePrimary = AccentLight,
    error = ErrorText,
    onError = Color(0xFF450A0A),
    outline = Color(0xFF6B6B78),
    outlineVariant = Color(0xFF3F3F4A),
)

private val base = Typography()
private val typography = Typography(
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.4).sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
)

val SectionStyle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp, color = TextPrimary)

@Composable
fun TunioTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
