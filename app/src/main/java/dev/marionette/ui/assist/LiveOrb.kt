package dev.marionette.ui.assist

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.marionette.ui.components.LocalMotion
import dev.marionette.ui.components.rememberMicEnergy
import dev.marionette.ui.theme.Brand
import dev.marionette.voice.VoiceBus
import dev.marionette.voice.VoicePhase
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

enum class OrbMode { IDLE, LISTENING, TRANSCRIBING, THINKING, SPEAKING }

fun VoicePhase.orbMode(): OrbMode = when (this) {
    VoicePhase.LISTENING -> OrbMode.LISTENING
    VoicePhase.TRANSCRIBING -> OrbMode.TRANSCRIBING
    VoicePhase.THINKING -> OrbMode.THINKING
    VoicePhase.SPEAKING -> OrbMode.SPEAKING
    else -> OrbMode.IDLE
}

private class Look(val spin: Float, val spread: Float, val presence: Float, val ring: Float, val breathe: Float)

private fun look(mode: OrbMode) = when (mode) {
    OrbMode.IDLE -> Look(18f, 0.26f, 0.55f, 0f, 0.02f)
    OrbMode.LISTENING -> Look(40f, 0.28f, 1f, 0f, 0.03f)
    OrbMode.TRANSCRIBING -> Look(150f, 0.12f, 0.9f, 1f, 0.02f)
    OrbMode.THINKING -> Look(75f, 0.34f, 0.95f, 0.55f, 0.06f)
    OrbMode.SPEAKING -> Look(45f, 0.24f, 1f, 0f, 0.02f)
}

private class OrbClock {
    val angle = mutableFloatStateOf(0f)
    val seconds = mutableFloatStateOf(0f)
}

@Composable
fun LiveOrb(mode: OrbMode, modifier: Modifier = Modifier, size: Dp = 56.dp) {
    val motion = LocalMotion.current
    val transition = updateTransition(mode, label = "orbMode")
    val spec = spring<Float>(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow)
    val spin = transition.animateFloat({ spec }, "spin") { look(it).spin }
    val spread = transition.animateFloat({ spec }, "spread") { look(it).spread }
    val presence = transition.animateFloat({ spec }, "presence") { look(it).presence }
    val ring = transition.animateFloat({ spec }, "ring") { look(it).ring }
    val breathe = transition.animateFloat({ spec }, "breathe") { look(it).breathe }
    val mic = rememberMicEnergy(9f, spring(0.5f, 300f), enabled = mode == OrbMode.LISTENING && !motion.reduced)
    val voice = rememberSpeechEnergy(enabled = (mode == OrbMode.SPEAKING || mode == OrbMode.THINKING) && !motion.reduced)
    val clock = rememberOrbClock(running = !motion.reduced && mode != OrbMode.IDLE, minFrameNanos = if (motion.calm) CALM_FRAME_NANOS else 0L, spin = spin)

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(size * 0.16f) else Modifier)) {
            val energy = min(1f, mic.value + voice.value)
            val breath = 1f + breathe.value * sin(clock.seconds.floatValue * 2f * PI.toFloat() / 2.4f)
            val base = this.size.minDimension / 2 * 0.42f * breath * (1f + energy * 0.22f) * presence.value
            val r = base * (spread.value + energy * 0.18f)
            val angle = clock.angle.floatValue
            Brand.glowColors.forEachIndexed { i, color ->
                val a = Math.toRadians((angle + i * 90.0) * if (i % 2 == 0) 1 else -1)
                val center = Offset(this.size.width / 2 + (cos(a) * r).toFloat(), this.size.height / 2 + (sin(a) * r).toFloat())
                drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0f)), center, base * 1.15f), base * 1.15f, center)
            }
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.55f * presence.value), Color.Transparent), radius = base * 0.7f), base * 0.7f)
        }
        Canvas(Modifier.fillMaxSize()) {
            val alpha = ring.value
            if (alpha < 0.01f) return@Canvas
            val stroke = 2.dp.toPx()
            rotate(clock.angle.floatValue * 2.5f) {
                drawArc(
                    Brush.sweepGradient(listOf(Color.Transparent, Brand.Violet, Brand.Magenta)),
                    startAngle = 0f,
                    sweepAngle = 300f,
                    useCenter = false,
                    topLeft = Offset(stroke, stroke),
                    size = Size(this.size.width - 2 * stroke, this.size.height - 2 * stroke),
                    alpha = alpha,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
    }
}

@Composable
private fun rememberOrbClock(running: Boolean, minFrameNanos: Long, spin: State<Float>): OrbClock {
    val clock = remember { OrbClock() }
    LaunchedEffect(running, minFrameNanos) {
        if (!running) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val dt = now - last
                if (dt >= minFrameNanos) {
                    val s = min(dt, 50_000_000L) / 1e9f
                    clock.angle.floatValue = (clock.angle.floatValue + spin.value * s) % 360f
                    clock.seconds.floatValue += s
                    last = now
                }
            }
        }
    }
    return clock
}

@Composable
private fun rememberSpeechEnergy(enabled: Boolean): State<Float> {
    val energy = remember { Animatable(0f) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(enabled, lifecycle) {
        if (!enabled) {
            energy.animateTo(0f, tween(300))
            return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            VoiceBus.speechLevel.collectLatest {
                energy.animateTo(it, spring(dampingRatio = 0.55f, stiffness = 500f))
                energy.animateTo(it * 0.3f, tween(260))
            }
        }
    }
    return energy.asState()
}

private const val CALM_FRAME_NANOS = 33_000_000L
