package dev.marionette.ui.voice

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.marionette.R
import dev.marionette.voice.Earcon
import dev.marionette.voice.Earcons
import dev.marionette.voice.VoiceBus
import dev.marionette.voice.VoicePhase
import dev.marionette.voice.VoiceService
import kotlinx.coroutines.delay

const val ENROLL_STEP_TARGET = 3

internal enum class EnrollDistance(@StringRes val title: Int, @StringRes val body: Int, val countdown: Int) {
    CLOSE(R.string.wake_step_close, R.string.wake_step_close_body, 0),
    ARM(R.string.wake_step_arm, R.string.wake_step_arm_body, 5),
    FAR(R.string.wake_step_far, R.string.wake_step_far_body, 10),
}

@Stable
internal class GuidedEnrollment {
    var step by mutableIntStateOf(-1)
    var countdown by mutableIntStateOf(0)
    var rejected by mutableStateOf(false)
    var saving by mutableStateOf(false)
    internal var appendFirst = false
    internal var start: (Boolean) -> Unit = {}
    internal var done: () -> Unit = {}

    val active: Boolean get() = step >= 0

    internal fun append(index: Int) = index > 0 || appendFirst

    fun begin(append: Boolean) {
        appendFirst = append
        startStep(0)
    }

    fun end() {
        step = -1
        done()
    }

    fun startStep(index: Int) {
        step = index
        rejected = false
        countdown = EnrollDistance.entries[index].countdown
        if (countdown == 0) start(append(index))
    }
}

@Composable
internal fun rememberGuidedEnrollment(start: (append: Boolean) -> Unit, onDone: () -> Unit = {}): GuidedEnrollment {
    val context = LocalContext.current
    val state = remember { GuidedEnrollment() }
    val currentStart by rememberUpdatedState(start)
    val currentDone by rememberUpdatedState(onDone)
    state.start = { currentStart(it) }
    state.done = { currentDone() }
    val voice by VoiceBus.status.collectAsStateWithLifecycle()
    val enrolling = voice.phase == VoicePhase.ENROLLING

    LaunchedEffect(voice.phase) {
        if (voice.phase == VoicePhase.ENROLLING) return@LaunchedEffect
        if (state.active) {
            if (!state.saving) {
                state.end()
            } else {
                state.rejected = voice.enrollRejected
                if (!state.rejected) state.step++
                if (state.step >= EnrollDistance.entries.size) state.end()
            }
        }
        state.saving = false
    }
    LaunchedEffect(state.countdown) {
        if (state.countdown <= 0) return@LaunchedEffect
        delay(1_000)
        if (state.countdown == 1) {
            Earcons.play(Earcon.WAKE)
            delay(400)
            currentStart(state.append(state.step))
        }
        state.countdown--
    }
    LaunchedEffect(voice.enrolledPhrases) {
        if (state.active && enrolling && !state.saving && voice.enrolledPhrases >= ENROLL_STEP_TARGET) {
            delay(500)
            state.saving = true
            VoiceService.send(context, VoiceService.ACTION_ENROLL_FINISH)
        }
    }
    DisposableEffect(Unit) {
        onDispose { if (VoiceBus.status.value.phase == VoicePhase.ENROLLING) VoiceService.send(context, VoiceService.ACTION_ENROLL_CANCEL) }
    }
    val view = LocalView.current
    val busy = enrolling || state.saving || state.active
    DisposableEffect(busy) {
        view.keepScreenOn = busy
        onDispose { view.keepScreenOn = false }
    }
    BackHandler(enabled = enrolling || state.countdown > 0) {}
    return state
}

@Composable
internal fun GuidedEnrollmentContent(state: GuidedEnrollment, phrase: String, unguidedMinimum: Int = 1) {
    val context = LocalContext.current
    val voice by VoiceBus.status.collectAsStateWithLifecycle()
    val distances = EnrollDistance.entries
    if (voice.phase == VoicePhase.ENROLLING || state.saving) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.active) {
                Text(
                    stringResource(R.string.wake_step, state.step + 1, distances.size) + " · " + stringResource(distances[state.step].title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
            Recording(
                phrase = phrase,
                count = voice.enrolledPhrases,
                minimum = if (!state.active) unguidedMinimum else if (state.step > 0) 0 else 1,
                target = if (state.active) ENROLL_STEP_TARGET else ENROLL_TARGET,
                saving = state.saving,
                onFinish = {
                    state.saving = true
                    VoiceService.send(context, VoiceService.ACTION_ENROLL_FINISH)
                },
                onCancel = { VoiceService.send(context, VoiceService.ACTION_ENROLL_CANCEL) },
            )
        }
        return
    }
    if (!state.active) return
    val distance = distances[state.step]
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.wake_step, state.step + 1, distances.size), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(stringResource(distance.title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(distance.body, ENROLL_STEP_TARGET), style = MaterialTheme.typography.bodyMedium)
        if (state.rejected) {
            Text(stringResource(R.string.wake_step_rejected), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        if (state.countdown > 0) {
            Text(stringResource(R.string.wake_step_starts_in, state.countdown), style = MaterialTheme.typography.headlineSmall)
            return@Column
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (state.step > 0) TextButton(onClick = state::end) { Text(stringResource(R.string.wake_finish)) }
            Spacer(Modifier.size(8.dp))
            Button(onClick = { state.startStep(state.step) }) {
                Text(stringResource(if (state.rejected) R.string.wake_step_repeat else R.string.wake_step_start))
            }
        }
    }
}
