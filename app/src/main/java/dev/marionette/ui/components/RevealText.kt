package dev.marionette.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

fun Modifier.revealMask(
    layout: () -> TextLayoutResult?,
    chars: () -> Float,
    hidden: () -> Float,
    offsetY: () -> Float = { 0f },
    edge: Dp = 28.dp,
): Modifier = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val text = layout() ?: return@drawWithContent
        val n = text.layoutInput.text.length
        val c = chars()
        if (n == 0 || c >= n) return@drawWithContent
        val fade = Color.Black.copy(alpha = hidden().coerceIn(0f, 1f))
        val index = c.toInt().coerceIn(0, n - 1)
        val line = text.getLineForOffset(index)
        val rtl = text.getParagraphDirection(index) == ResolvedTextDirection.Rtl
        val left = text.getHorizontalPosition(index, true)
        val right = if (index + 1 < n && text.getLineForOffset(index + 1) == line) {
            text.getHorizontalPosition(index + 1, true)
        } else {
            if (rtl) text.getLineLeft(line) else text.getLineRight(line)
        }
        val x = left + (right - left) * (c - index)
        val dy = offsetY()
        val top = text.getLineTop(line) + dy
        val bottom = text.getLineBottom(line) + dy
        val half = edge.toPx() / 2
        val stops = if (rtl) listOf(fade, Color.Black) else listOf(Color.Black, fade)
        drawRect(
            Brush.horizontalGradient(stops, startX = x - half, endX = x + half),
            topLeft = Offset(0f, top),
            size = Size(size.width, bottom - top),
            blendMode = BlendMode.DstIn,
        )
        if (bottom < size.height) drawRect(fade, Offset(0f, bottom), Size(size.width, size.height - bottom), blendMode = BlendMode.DstIn)
    }

@Composable
fun rememberShimmerSweep(active: Boolean): State<Float>? {
    val run = active && LocalMotion.current.ambient
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(run) {
        if (!run) return@LaunchedEffect
        while (true) {
            sweep.snapTo(0f)
            sweep.animateTo(1f, tween(1500, easing = LinearEasing))
        }
    }
    return if (run) sweep.asState() else null
}

fun Modifier.shimmer(sweep: State<Float>?): Modifier = if (sweep == null) {
    this
} else {
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val band = size.width * 0.35f
            val x = -band + (size.width + 2 * band) * sweep.value
            drawRect(
                Brush.horizontalGradient(
                    listOf(Color.Black.copy(alpha = 0.45f), Color.Black, Color.Black.copy(alpha = 0.45f)),
                    startX = x - band,
                    endX = x + band,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
}
