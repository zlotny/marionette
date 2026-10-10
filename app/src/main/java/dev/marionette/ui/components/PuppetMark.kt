package dev.marionette.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.marionette.ui.theme.Brand

private const val VIEW_LEFT = 21f
private const val VIEW_TOP = 23f
private const val VIEW_SIZE = 66f

@Composable
fun PuppetMark(modifier: Modifier = Modifier, size: Dp = 24.dp, brush: Brush? = null) {
    val fill = brush ?: Brand.textBrush()
    Canvas(modifier.size(size)) {
        val scale = this.size.minDimension / VIEW_SIZE
        fun p(x: Float, y: Float) = Offset((x - VIEW_LEFT) * scale, (y - VIEW_TOP) * scale)
        val string = 2.8f * scale
        val bar = 7f * scale
        drawCircle(fill, radius = 3.2f * scale, center = p(54f, 29f), style = Stroke(width = 2.6f * scale))
        listOf(p(31f, 44f) to p(37f, 80f), p(77f, 44f) to p(71f, 80f), p(54f, 55f) to p(54f, 84f)).forEach { (from, to) ->
            drawLine(fill, from, to, strokeWidth = string, cap = StrokeCap.Round)
            drawCircle(fill, radius = 3.2f * scale, center = to)
        }
        drawLine(fill, p(54f, 33f), p(54f, 55f), strokeWidth = bar, cap = StrokeCap.Round)
        drawLine(fill, p(31f, 44f), p(77f, 44f), strokeWidth = bar, cap = StrokeCap.Round)
    }
}
