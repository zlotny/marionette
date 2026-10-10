package dev.marionette.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.marionette.ui.theme.Brand

private const val VIEW_LEFT = 23f
private const val VIEW_TOP = 23f
private const val VIEW_SIZE = 62f
private const val WAVE_CENTER = 67f
private val waveBars = listOf(40f to 12f, 47f to 22f, 54f to 30f, 61f to 22f, 68f to 12f)
private val stringAnchors = mapOf(40f to 32f, 54f to 54f, 68f to 76f)

@Composable
fun PuppetMark(modifier: Modifier = Modifier, size: Dp = 24.dp, brush: Brush? = null) {
    val fill = brush ?: Brand.textBrush()
    Canvas(modifier.size(size)) {
        val scale = this.size.minDimension / VIEW_SIZE
        fun p(x: Float, y: Float) = Offset((x - VIEW_LEFT) * scale, (y - VIEW_TOP) * scale)
        waveBars.forEach { (x, height) ->
            val top = WAVE_CENTER - height / 2
            stringAnchors[x]?.let { anchor -> drawLine(fill, p(anchor, 38f), p(x, top), strokeWidth = 2f * scale, cap = StrokeCap.Round) }
            drawLine(fill, p(x, top), p(x, WAVE_CENTER + height / 2), strokeWidth = 5f * scale, cap = StrokeCap.Round)
        }
        drawLine(fill, p(32f, 36f), p(76f, 36f), strokeWidth = 6.5f * scale, cap = StrokeCap.Round)
        drawLine(fill, p(54f, 26f), p(54f, 46f), strokeWidth = 6.5f * scale, cap = StrokeCap.Round)
    }
}
