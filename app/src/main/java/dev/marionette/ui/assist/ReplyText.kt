package dev.marionette.ui.assist

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.marionette.ui.components.LocalMotion
import dev.marionette.ui.components.revealMask
import dev.marionette.voice.VoiceBus
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val CHARS_PER_SECOND = 14f
private const val UNSPOKEN_ALPHA = 0.38f

@Composable
fun ReplyText(reply: String, speaking: Boolean, color: Color, modifier: Modifier = Modifier) {
    val motion = LocalMotion.current
    val chars = remember { Animatable(0f) }
    val hidden = remember { Animatable(0f) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var previous by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    val lead = with(LocalDensity.current) { 24.dp.toPx() }
    val spokenText by remember { VoiceBus.speech.map { it.text }.distinctUntilChanged() }.collectAsStateWithLifecycle("")
    val karaoke = speaking && !motion.reduced && spokenText.isNotEmpty() && spokenText == reply

    LaunchedEffect(karaoke, if (karaoke) Unit else reply) {
        val full = reply.length.toFloat()
        when {
            motion.reduced -> chars.snapTo(full)
            karaoke -> {
                previous = reply
                chars.snapTo(0f)
                hidden.snapTo(1f)
                launch { hidden.animateTo(UNSPOKEN_ALPHA, tween(260)) }
                VoiceBus.speech.collectLatest { p ->
                    if (p.text != reply) return@collectLatest
                    val from = maxOf(chars.value, p.spokenTo.toFloat())
                    chars.snapTo(from)
                    val to = p.aheadTo.toFloat()
                    if (to > from) chars.animateTo(to, tween(((to - from) / (CHARS_PER_SECOND * p.rate) * 1000).roundToInt(), easing = LinearEasing))
                }
            }
            else -> {
                hidden.snapTo(0f)
                val start = minOf(chars.value, previous.commonPrefixWith(reply).length.toFloat())
                previous = reply
                chars.snapTo(start)
                chars.animateTo(full, tween(((full - start) * 12).roundToInt().coerceIn(150, 900), easing = FastOutSlowInEasing))
            }
        }
    }
    LaunchedEffect(karaoke) {
        if (!karaoke) return@LaunchedEffect
        snapshotFlow {
            layout?.let { l -> l.getLineTop(l.getLineForOffset(chars.value.toInt().coerceIn(0, maxOf(0, l.layoutInput.text.length - 1)))) }
        }.filterNotNull().distinctUntilChanged().collect { top ->
            scroll.animateScrollTo((top - lead).roundToInt().coerceAtLeast(0))
        }
    }
    Text(
        reply,
        style = MaterialTheme.typography.bodyLarge,
        color = color,
        onTextLayout = { layout = it },
        modifier = modifier
            .heightIn(max = 240.dp)
            .then(if (motion.reduced) Modifier else Modifier.revealMask({ layout }, { chars.value }, { hidden.value }, { -scroll.value.toFloat() }))
            .verticalScroll(scroll),
    )
}
