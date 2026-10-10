package dev.marionette.ui.components

import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.marionette.ui.theme.Brand
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun VoiceOrb(modifier: Modifier = Modifier, size: Dp = 160.dp, level: Float = 0f, active: Boolean = true) {
    val transition = rememberInfiniteTransition(label = "orb")
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "angle")
    val breathe by transition.animateFloat(0.92f, 1.04f, infiniteRepeatable(tween(2400), RepeatMode.Reverse), label = "breathe")
    val energy by animateFloatAsState((level * 9f).coerceIn(0f, 1f), spring(dampingRatio = 0.5f, stiffness = 300f), label = "energy")
    val presence by animateFloatAsState(if (active) 1f else 0.55f, tween(600), label = "presence")
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .fillMaxSize()
                .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(size * 0.16f) else Modifier),
        ) {
            val base = this.size.minDimension / 2 * 0.42f * breathe * (1f + energy * 0.22f) * presence
            Brand.glowColors.forEachIndexed { i, color ->
                val a = Math.toRadians((angle + i * 90.0) * if (i % 2 == 0) 1 else -1)
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
