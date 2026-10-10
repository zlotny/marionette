package dev.marionette.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

@Composable
fun TranscriptText(
    text: String,
    style: TextStyle,
    maxLines: Int,
    modifier: Modifier = Modifier,
    minFontSize: TextUnit = 11.sp,
) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth
        val (shown, fitted) = remember(text, style, width, maxLines) { fit(measurer, text, style, width, maxLines, minFontSize) }
        Text(shown, style = fitted, maxLines = maxLines, overflow = TextOverflow.Clip)
    }
}

private fun fit(measurer: TextMeasurer, text: String, style: TextStyle, width: Int, maxLines: Int, minFontSize: TextUnit): Pair<String, TextStyle> {
    if (width <= 0 || !style.fontSize.isSp) return text to style
    val fits = { candidate: String, sized: TextStyle ->
        !measurer.measure(candidate, sized, maxLines = maxLines, constraints = Constraints(maxWidth = width)).hasVisualOverflow
    }
    var size = style.fontSize.value
    while (size > minFontSize.value) {
        val sized = scaled(style, size)
        if (fits(text, sized)) return text to sized
        size -= 1f
    }
    val smallest = scaled(style, minFontSize.value)
    if (fits(text, smallest)) return text to smallest
    var low = 1
    var high = text.length
    while (low < high) {
        val mid = (low + high) / 2
        if (fits(tail(text, mid), smallest)) high = mid else low = mid + 1
    }
    val wordStart = text.indexOf(' ', low).takeIf { it in low until low + MAX_WORD_SKIP }?.plus(1) ?: low
    return tail(text, wordStart) to smallest
}

private fun tail(text: String, start: Int) = "…" + text.substring(start.coerceAtMost(text.length)).trimStart()

private fun scaled(style: TextStyle, size: Float): TextStyle {
    val ratio = size / style.fontSize.value
    return style.copy(
        fontSize = size.sp,
        lineHeight = if (style.lineHeight.isSp) (style.lineHeight.value * ratio).sp else style.lineHeight,
    )
}

private const val MAX_WORD_SKIP = 16
