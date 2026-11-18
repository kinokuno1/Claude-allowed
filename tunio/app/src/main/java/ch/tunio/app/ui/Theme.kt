package ch.tunio.app.ui

import android.os.Build
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
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

val AccentGradient = Brush.linearGradient(listOf(AccentDeep, Accent2Deep))

fun sourceColor(s: Source): Color = when (s) {
    Source.YOUTUBE -> Color(0xFFFF5A52)
    Source.SOUNDCLOUD -> Color(0xFFFF8A3D)
    Source.TIKTOK -> Color(0xFF25F4EE)
    Source.SPOTIFY -> Color(0xFF1ED760)
    Source.OTHER -> Color(0xFFC4C4CC)
}

// ---- Liquid glass (Kyant0's Backdrop library: real lens refraction + blur of what's behind)

/** What in-content glass refracts: the background light layer. Null = draw the frosted fallback. */
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

/** Background light behind the whole app; it is what the glass bends. Static to save battery. */
@Composable
fun Aurora(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        drawRect(Bg)
        val w = size.width
        val h = size.height
        fun blob(color: Color, x: Float, y: Float, r: Float, alpha: Float) {
            val center = Offset(x, y)
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = alpha), Color.Transparent), center, r), r, center)
        }
        blob(Accent, w * 0.18f, h * 0.08f, w * 0.95f, 0.30f)
        blob(Accent2, w * 0.85f, h * 0.42f, w * 0.80f, 0.20f)
        blob(Color(0xFF3B82F6), w * 0.25f, h * 0.82f, w * 0.90f, 0.14f)
        // A few small bright specks give the refraction something to bend, like light through water.
        blob(AccentLight, w * 0.72f, h * 0.16f, w * 0.10f, 0.35f)
        blob(PinkLight, w * 0.12f, h * 0.55f, w * 0.08f, 0.30f)
        blob(Color(0xFF93C5FD), w * 0.80f, h * 0.74f, w * 0.09f, 0.30f)
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
