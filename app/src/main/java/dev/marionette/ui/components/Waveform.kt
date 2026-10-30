package dev.marionette.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun Waveform(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary, bars: Int = 5) {
    val energy by rememberMicEnergy(10f, spring(dampingRatio = 0.55f, stiffness = 400f), floor = 0.08f)
    val motion = LocalMotion.current
    val transition = rememberInfiniteTransition(label = "waveIdle")
    val wave = transition.animateFloat(0f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "phase")
    Canvas(modifier.size(width = 36.dp, height = 24.dp)) {
        val gap = size.width / (bars * 2 - 1)
        repeat(bars) { i ->
            val shape = listOf(0.45f, 0.8f, 1f, 0.75f, 0.5f)[i % 5]
            val phase = if (motion.ambient) wave.value else 0.5f
            val wobble = if (i % 2 == 0) phase else 1 - phase
            val h = (size.height * shape * (0.25f + 0.75f * energy) * (0.8f + 0.2f * wobble)).coerceAtLeast(gap)
            drawRoundRect(
                color = color,
                topLeft = Offset(i * gap * 2, (size.height - h) / 2),
                size = Size(gap, h),
                cornerRadius = CornerRadius(gap / 2),
            )
        }
    }
}
