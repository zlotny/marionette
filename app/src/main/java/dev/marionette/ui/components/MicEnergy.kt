package dev.marionette.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.marionette.voice.VoiceBus
import kotlinx.coroutines.flow.collectLatest

@Composable
fun rememberMicEnergy(gain: Float, spec: AnimationSpec<Float>, enabled: Boolean = true, floor: Float = 0f): State<Float> {
    val energy = remember { Animatable(floor) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(enabled, lifecycle) {
        if (!enabled) return@LaunchedEffect energy.snapTo(floor)
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            VoiceBus.level.collectLatest { energy.animateTo((it * gain).coerceIn(floor, 1f), spec) }
        }
    }
    return energy.asState()
}
