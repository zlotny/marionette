package dev.marionette.ui.components

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.marionette.ui.theme.Brand
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@Composable
fun VoiceOrb(modifier: Modifier = Modifier, size: Dp = 160.dp, listening: Boolean = false, active: Boolean = true) {
    val motion = LocalMotion.current
    val angle = remember { mutableFloatStateOf(0f) }
    val seconds = remember { mutableFloatStateOf(0f) }
    val running = active && !motion.reduced
    LaunchedEffect(running, motion.calm) {
        if (!running) return@LaunchedEffect
        val minFrame = if (motion.calm) CALM_FRAME_NANOS else FRAME_NANOS
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val dt = now - last
                if (dt >= minFrame) {
                    val s = min(dt, 50_000_000L) / 1e9f
                    angle.floatValue = (angle.floatValue + DEGREES_PER_SECOND * s) % 360f
                    seconds.floatValue += s
                    last = now
                }
            }
        }
    }
    val energy by rememberMicEnergy(9f, spring(dampingRatio = 0.5f, stiffness = 300f), enabled = listening && running)
    val presence by animateFloatAsState(if (active) 1f else 0.55f, tween(600), label = "presence")
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .fillMaxSize()
                .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(size * 0.16f) else Modifier),
        ) {
            val breathe = 0.98f + 0.06f * sin(seconds.floatValue * 2f * PI.toFloat() / BREATH_SECONDS)
            val base = this.size.minDimension / 2 * 0.42f * breathe * (1f + energy * 0.22f) * presence
            Brand.glowColors.forEachIndexed { i, color ->
                val a = Math.toRadians((angle.floatValue + i * 90.0) * if (i % 2 == 0) 1 else -1)
                val r = base * (0.28f + energy * 0.18f)
                val center = Offset(this.size.width / 2 + (cos(a) * r).toFloat(), this.size.height / 2 + (sin(a) * r).toFloat())
                drawCircle(
                    brush = Brush.radialGradient(listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0f)), center, base * 1.15f),
                    radius = base * 1.15f,
                    center = center,
                )
            }
            drawCircle(
                brush = Brush.radialGradient(listOf(Color.White.copy(alpha = 0.55f * presence), Color.Transparent), radius = base * 0.7f),
                radius = base * 0.7f,
            )
        }
    }
}

private const val DEGREES_PER_SECOND = 40f
private const val BREATH_SECONDS = 4.8f
private const val FRAME_NANOS = 16_000_000L
private const val CALM_FRAME_NANOS = 33_000_000L
