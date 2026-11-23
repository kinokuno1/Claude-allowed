package ch.tunio.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlashlightOff
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import ch.tunio.app.engine.HomeServer
import ch.tunio.app.engine.Pairing
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** Opens the in-app scanner for the home server's QR code (from the setup or from Settings). */
object QrScan {
    val open = MutableStateFlow(false)
}

private sealed interface Phase {
    data object Looking : Phase
    data object Found : Phase
    data object Connecting : Phase
    data class Connected(val songs: Int) : Phase
    data class Failed(val why: String) : Phase
}

private val ConfettiColors = listOf(
    Color.White, Color(0xFFC9B6FF), Color(0xFFFFA8D8), Color(0xFF6EE7B7), Color(0xFFFFD66B), Color(0xFF7CC4FF),
)

private class Bit(val angle: Float, val speed: Float, val size: Float, val spin: Float, val color: Color, val round: Boolean, val drift: Float)

@Composable
fun QrScannerOverlay() {
    val open by QrScan.open.collectAsState()
    AnimatedVisibility(open, enter = fadeIn(tween(220)) + scaleIn(tween(320, easing = FastOutSlowInEasing), initialScale = 1.06f), exit = fadeOut(tween(260))) {
        QrScanner(onClose = { QrScan.open.value = false })
    }
}

@Composable
private fun QrScanner(onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onClose)

    fun hasCamera() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(hasCamera()) }
    var refused by remember { mutableStateOf(false) }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok; refused = !ok }
    LaunchedEffect(Unit) { if (!granted) askCamera.launch(Manifest.permission.CAMERA) }

    var phase by remember { mutableStateOf<Phase>(Phase.Looking) }
    var hint by remember { mutableStateOf<String?>(null) }
    var torch by remember { mutableStateOf(false) }
    var area by remember { mutableStateOf(IntSize.Zero) }

    // Where the corners are drawn (in px): the guide square, then the QR code itself once found.
    val frame = remember { Animatable(Rect.Zero, Rect.VectorConverter) }
    val green = remember { Animatable(0f) }   // corners white -> green
    val flash = remember { Animatable(0f) }   // green fill inside the corners
    val burst = remember { Animatable(0f) }   // confetti + rings, 0..1
    val tick = remember { Animatable(0f) }    // the check mark pop
    val bits = remember {
        List(56) {
            Bit(
                angle = Random.nextFloat() * 6.2832f,
                speed = 0.55f + Random.nextFloat() * 0.75f,
                size = 5f + Random.nextFloat() * 7f,
                spin = (Random.nextFloat() - 0.5f) * 1080f,
                color = ConfettiColors[it % ConfettiColors.size],
                round = it % 3 == 0,
                drift = (Random.nextFloat() - 0.5f) * 0.4f,
            )
        }
    }

    fun guide(): Rect {
        val side = min(area.width, area.height) * 0.68f
        val cx = area.width / 2f
        val cy = area.height * 0.42f
        return Rect(cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2)
    }
    LaunchedEffect(area) { if (area != IntSize.Zero && phase == Phase.Looking) frame.snapTo(guide()) }
    LaunchedEffect(hint) { if (hint != null) { delay(1800); hint = null } }

    fun buzz(strong: Boolean) {
        val c = if (Build.VERSION.SDK_INT >= 30) (if (strong) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CLOCK_TICK)
        else HapticFeedbackConstants.LONG_PRESS
        view.performHapticFeedback(c)
    }

    fun reset() {
        phase = Phase.Looking
        scope.launch { frame.animateTo(guide(), spring(dampingRatio = 0.7f, stiffness = 300f)) }
        scope.launch { green.animateTo(0f, tween(250)) }
        scope.launch { tick.snapTo(0f); burst.snapTo(0f); flash.snapTo(0f) }
    }

    fun found(address: String, password: String, box: android.graphics.Rect?) {
        phase = Phase.Found
        buzz(true)
        val pad = with(density) { 14.dp.toPx() }
        val target = box?.let { Rect(it.left - pad, it.top - pad, it.right + pad, it.bottom + pad) }
            ?.takeIf { it.width > 40 && it.height > 40 } ?: guide().deflate(guide().width * 0.12f)
        scope.launch {
            launch { green.animateTo(1f, tween(200)) }
            // corners snap onto the code
            frame.animateTo(target, spring(dampingRatio = 0.5f, stiffness = 520f))
            launch { flash.snapTo(0.55f); flash.animateTo(0f, tween(650)) }
            launch { burst.snapTo(0f); burst.animateTo(1f, tween(1300, easing = LinearOutSlowInEasing)) }
            launch { delay(90); buzz(false) }
            tick.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 420f))
            delay(450)
            phase = Phase.Connecting
            HomeServer.configure(address, password)
            val ok = HomeServer.check(force = true)
            val s = HomeServer.state.value
            phase = when {
                ok && s is HomeServer.State.Online -> Phase.Connected(s.songs)
                ok -> Phase.Connected(0)
                s == HomeServer.State.WrongPassword -> Phase.Failed("The server says the password is wrong. Print a fresh QR code from the server.")
                else -> Phase.Failed("Found the code, but can't reach the server at ${address.removePrefix("http://").removePrefix("https://")}. Is your phone on the same Wi-Fi?")
            }
            if (ok) {
                buzz(true)
                delay(1300)
                onClose()
            }
        }
    }

    val onCode by rememberUpdatedState { raw: String, box: android.graphics.Rect? ->
        if (phase == Phase.Looking) {
            val pair = Pairing.parse(raw)
            if (pair == null) hint = "That's not a Tunio QR code" else found(pair.first, pair.second, box)
        }
    }

    val controller = remember {
        LifecycleCameraController(context).apply {
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        }
    }
    val scanner = remember {
        BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { controller.unbind() }
            scanner.close()
        }
    }
    LaunchedEffect(torch, granted) { if (granted) runCatching { controller.enableTorch(torch) } }

    val idle = rememberInfiniteTransition(label = "idle")
    val breathe by idle.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
    val sweep by idle.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart), label = "sweep")

    Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged { area = it }.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}) {
        if (granted) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        val main = ContextCompat.getMainExecutor(ctx)
                        controller.setImageAnalysisAnalyzer(
                            main,
                            MlKitAnalyzer(listOf(scanner), ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED, main) { result ->
                                val code = result?.getValue(scanner)?.firstOrNull { !it.rawValue.isNullOrBlank() }
                                if (code != null) onCode(code.rawValue!!, code.boundingBox)
                            },
                        )
                        controller.bindToLifecycle(lifecycleOwner)
                        this.controller = controller
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Dimmed surroundings, the corners, and the celebration.
        Canvas(Modifier.fillMaxSize()) {
            val looking = phase == Phase.Looking
            val r0 = frame.value
            if (r0 == Rect.Zero) return@Canvas
            val grow = if (looking) breathe * 10f else 0f
            val r = r0.inflate(grow)
            val radius = 28f
            val hole = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(Offset.Zero, size))
                addRoundRect(androidx.compose.ui.geometry.RoundRect(r, CornerRadius(radius)))
            }
            drawPath(hole, Color.Black.copy(alpha = 0.55f))

            val good = Color(0xFF6EE7B7)
            if (flash.value > 0f) drawRoundRect(good.copy(alpha = flash.value), r.topLeft, r.size, CornerRadius(radius))
            if (looking && granted) {
                val y = r.top + 18f + (r.height - 36f) * sweep
                drawLine(Color.White.copy(alpha = 0.55f * sin(sweep * Math.PI).toFloat()), Offset(r.left + 26f, y), Offset(r.right - 26f, y), 3f, StrokeCap.Round)
            }

            // four corners
            val c = lerp(Color.White, good, green.value)
            val len = min(r.width, r.height) * 0.2f
            val w = 6.dp.toPx() + green.value * 2.dp.toPx()
            val corners = Path().apply {
                moveTo(r.left, r.top + len); lineTo(r.left, r.top + radius); quadraticTo(r.left, r.top, r.left + radius, r.top); lineTo(r.left + len, r.top)
                moveTo(r.right - len, r.top); lineTo(r.right - radius, r.top); quadraticTo(r.right, r.top, r.right, r.top + radius); lineTo(r.right, r.top + len)
                moveTo(r.right, r.bottom - len); lineTo(r.right, r.bottom - radius); quadraticTo(r.right, r.bottom, r.right - radius, r.bottom); lineTo(r.right - len, r.bottom)
                moveTo(r.left + len, r.bottom); lineTo(r.left + radius, r.bottom); quadraticTo(r.left, r.bottom, r.left, r.bottom - radius); lineTo(r.left, r.bottom - len)
            }
            drawPath(corners, c, style = Stroke(width = w, cap = StrokeCap.Round))

            // rings + confetti from the code's centre
            val p = burst.value
            if (p > 0f && p < 1f) {
                val center = r.center
                val base = min(r.width, r.height) / 2f
                for (k in 0..1) {
                    val q = ((p - k * 0.12f) / 0.88f).coerceIn(0f, 1f)
                    if (q > 0f) drawCircle(good.copy(alpha = (1f - q) * 0.7f), base * (0.9f + q * 1.4f), center, style = Stroke(width = (1f - q) * 10f + 1f))
                }
                val reach = min(size.width, size.height) * 0.62f
                for (b in bits) {
                    val travel = 1f - (1f - p) * (1f - p) * (1f - p)
                    val d = reach * b.speed * travel
                    val x = center.x + cos(b.angle) * d + b.drift * reach * p
                    val y = center.y + sin(b.angle) * d + p * p * reach * 0.55f
                    val a = (1f - p * p).coerceIn(0f, 1f)
                    val s = b.size.dp.toPx() * (1f - p * 0.35f)
                    if (b.round) drawCircle(b.color.copy(alpha = a), s / 2, Offset(x, y))
                    else rotate(b.spin * p, Offset(x, y)) {
                        drawRoundRect(b.color.copy(alpha = a), Offset(x - s / 2, y - s * 0.3f), Size(s, s * 0.6f), CornerRadius(2f))
                    }
                }
            }
        }

        // the check mark pops in the middle of the code
        if (tick.value > 0f && frame.value != Rect.Zero) {
            val sizePx = with(density) { 76.dp.toPx() }
            val ctr = frame.value.center
            Box(
                Modifier
                    .offset { IntOffset((ctr.x - sizePx / 2).roundToInt(), (ctr.y - sizePx / 2).roundToInt()) }
                    .size(76.dp)
                    .graphicsLayer { scaleX = tick.value; scaleY = tick.value; alpha = tick.value.coerceIn(0f, 1f) }
                    .clip(CircleShape)
                    .background(Color(0xFF6EE7B7)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Check, null, tint = Color(0xFF0B3B2C), modifier = Modifier.size(46.dp))
            }
        }

        // top: close · torch
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleButton(Icons.Rounded.Close, "Close", onClick = onClose)
            Spacer(Modifier.weight(1f))
            if (granted) CircleButton(if (torch) Icons.Rounded.FlashlightOn else Icons.Rounded.FlashlightOff, "Torch", active = torch) { torch = !torch }
        }

        // bottom: what's happening
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val ph = phase) {
                Phase.Looking -> if (!granted) {
                    Icon(Icons.Rounded.PhotoCamera, null, tint = TextPrimary, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(12.dp))
                    Title("Tunio needs the camera")
                    Body("Only to read your home server's QR code. Nothing is recorded.")
                    Spacer(Modifier.height(18.dp))
                    Pill("Allow camera") {
                        if (refused && !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(context as android.app.Activity, Manifest.permission.CAMERA)) {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                        } else askCamera.launch(Manifest.permission.CAMERA)
                    }
                } else {
                    Title("Scan your server's QR code")
                    Body(hint ?: "Fit the code inside the corners. It's the one the installer showed, or the one you printed.")
                }
                Phase.Found -> Title("Got it!")
                Phase.Connecting -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = TextPrimary)
                    Spacer(Modifier.width(12.dp))
                    Title("Connecting to your server…")
                }
                is Phase.Connected -> {
                    Title("Connected!")
                    Body(if (ph.songs > 0) "Your server keeps ${ph.songs} songs for you." else "Your home server is ready.")
                }
                is Phase.Failed -> {
                    Title("Almost")
                    Body(ph.why)
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Pill("Scan again") { reset() }
                        Pill("Close", light = false, onClick = onClose)
                    }
                }
            }
        }
    }
}

@Composable
private fun Title(text: String) = Text(text, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)

@Composable
private fun Body(text: String) = Text(
    text, color = Muted, fontSize = 15.sp, lineHeight = 21.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp),
)

@Composable
private fun Pill(text: String, light: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.height(48.dp).clip(RoundedCornerShape(24.dp))
            .background(if (light) Color.White else Color.White.copy(alpha = 0.14f))
            .clickable(onClick = onClick).padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (light) Bg else TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
private fun CircleButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(46.dp).clip(CircleShape)
            .background(if (active) Color.White else Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = if (active) Bg else TextPrimary, modifier = Modifier.size(24.dp))
    }
}
