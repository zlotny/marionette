package dev.marionette.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.marionette.ui.theme.Brand

@Composable
fun ListeningGlow(visible: Boolean, modifier: Modifier = Modifier, height: Dp = 220.dp) {
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(450), label = "glowAlpha")
    if (alpha == 0f) return
    val motion = LocalMotion.current
    val transition = rememberInfiniteTransition(label = "glow")
    val drift = transition.animateFloat(0f, 1f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "shift")
    val energy by rememberMicEnergy(8f, spring(dampingRatio = 0.6f, stiffness = 200f))
    Canvas(modifier.fillMaxWidth().height(height).graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val w = size.width
        val colors = Brand.glowColors + Brand.glowColors.first()
        val offset = (if (motion.ambient) drift.value else 0f) * w
        val horizontal = Brush.horizontalGradient(colors, startX = -w + offset, endX = w + offset)
        val reach = size.height * (0.45f + 0.55f * energy)
        drawRect(horizontal, topLeft = Offset(0f, size.height - reach), alpha = alpha * 0.85f)
        drawRect(
            Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = size.height, endY = size.height - reach),
            topLeft = Offset(0f, size.height - reach),
            blendMode = BlendMode.DstIn,
        )
    }
}
