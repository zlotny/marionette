package dev.marionette.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
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
    minFontSize: TextUnit = TextUnit.Unspecified,
    animate: Boolean = false,
    shimmer: State<Float>? = null,
) {
    val measurer = rememberTextMeasurer()
    val chars = remember { Animatable(0f) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var previous by remember { mutableStateOf("") }
    val floor = if (minFontSize.isSp) minFontSize else (style.fontSize.value * MIN_SCALE).sp
    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth
        val (shown, fitted) = remember(text, style, width, maxLines, floor) { fit(measurer, text, style, width, maxLines, floor) }
        if (animate) {
            LaunchedEffect(text, shown) {
                val fresh = text.length - previous.commonPrefixWith(text).length
                previous = text
                val start = (shown.length - fresh).coerceAtLeast(0).toFloat()
                if (start < chars.value) chars.snapTo(start)
                chars.animateTo(shown.length.toFloat(), tween((fresh * 22).coerceIn(160, 700), easing = FastOutSlowInEasing))
            }
        }
        Text(
            shown,
            style = fitted,
            maxLines = maxLines,
            overflow = TextOverflow.Clip,
            onTextLayout = { layout = it },
            modifier = Modifier
                .fillMaxWidth()
                .clearAndSetSemantics { this.text = AnnotatedString(text) }
                .then(if (animate) Modifier.revealMask({ layout }, { chars.value }, { 0f }) else Modifier)
                .shimmer(shimmer),
        )
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
private const val MIN_SCALE = 0.75f
