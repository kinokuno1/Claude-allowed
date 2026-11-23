package ch.tunio.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Clickable with a small press animation: it sinks in a little while pressed and springs back when let go.
 * Small things (buttons, icons) sink more than big rows, so every press feels about the same.
 */
fun Modifier.tap(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    var width by remember { mutableIntStateOf(0) }
    val sink = with(LocalDensity.current) { 10.dp.toPx() }
    val target = if (pressed && enabled) (1f - (sink / width.coerceAtLeast(1)).coerceIn(0.02f, 0.07f)) else 1f
    val s by animateFloatAsState(
        target,
        if (pressed) tween(durationMillis = 90) else spring(dampingRatio = 0.45f, stiffness = 650f),
        label = "tap",
    )
    this
        .onSizeChanged { width = it.width }
        .graphicsLayer { scaleX = s; scaleY = s }
        .clickable(interactionSource = source, indication = LocalIndication.current, enabled = enabled, onClick = onClick)
}
