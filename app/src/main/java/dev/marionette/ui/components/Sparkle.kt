package dev.marionette.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.marionette.ui.theme.Brand

fun sparklePath(size: Size, cx: Float = size.width / 2, cy: Float = size.height / 2, r: Float = size.minDimension / 2): Path {
    val k = 0.08f * r * 2
    return Path().apply {
        moveTo(cx, cy - r)
        cubicTo(cx + k, cy - k * 1.2f, cx + k * 1.2f, cy - k, cx + r, cy)
        cubicTo(cx + k * 1.2f, cy + k, cx + k, cy + k * 1.2f, cx, cy + r)
        cubicTo(cx - k, cy + k * 1.2f, cx - k * 1.2f, cy + k, cx - r, cy)
        cubicTo(cx - k * 1.2f, cy - k, cx - k, cy - k * 1.2f, cx, cy - r)
        close()
    }
}

@Composable
fun Sparkle(modifier: Modifier = Modifier, size: Dp = 24.dp, brush: Brush? = null) {
    val fill = brush ?: Brand.textBrush()
    Canvas(modifier.size(size)) {
        drawPath(sparklePath(this.size), fill)
        val small = this.size.minDimension * 0.18f
        drawPath(sparklePath(this.size, this.size.width * 0.84f, this.size.height * 0.16f, small), fill, alpha = 0.8f)
    }
}
