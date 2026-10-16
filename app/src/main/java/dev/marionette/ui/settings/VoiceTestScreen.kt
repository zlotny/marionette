package dev.marionette.ui.settings

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.marionette.R
import dev.marionette.ui.components.SettingsScaffold
import dev.marionette.voice.AudioSpec
import dev.marionette.voice.Earcon
import dev.marionette.voice.Earcons
import dev.marionette.voice.VoiceService
import dev.marionette.voice.stt.OpenAiSpeechToText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

private enum class VoiceTestStep(val key: String, @StringRes val title: Int, @StringRes val body: Int, val countdown: Int, val typing: Boolean = false) {
    CLOSE("wake-close", R.string.voice_test_close, R.string.voice_test_close_body, 3),
    ARM("wake-arm", R.string.voice_test_arm, R.string.voice_test_arm_body, 5),
    TWO_METERS("wake-2m", R.string.voice_test_2m, R.string.voice_test_2m_body, 10),
    ACROSS("wake-far", R.string.voice_test_far, R.string.voice_test_far_body, 10),
    TYPING("noise-typing", R.string.voice_test_typing, R.string.voice_test_typing_body, 3, typing = true),
    HANDLING("noise-handling", R.string.voice_test_handling, R.string.voice_test_handling_body, 3),
    BREATHING("noise-breath", R.string.voice_test_breathing, R.string.voice_test_breathing_body, 3),
    TALKING("noise-talk", R.string.voice_test_talking, R.string.voice_test_talking_body, 3),
    ROOM("noise-room", R.string.voice_test_room, R.string.voice_test_room_body, 5),
}

private enum class VoiceTestPhase { READY, COUNTDOWN, RECORDING }

private const val VOICE_TEST_SECONDS = 30

@Composable
fun VoiceTestScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val steps = VoiceTestStep.entries
    val dir = remember { File(context.getExternalFilesDir(null), "voice-test") }
    var index by rememberSaveable { mutableIntStateOf(steps.indexOfFirst { !File(dir, "${it.key}.wav").exists() }.let { if (it < 0) steps.size else it }) }
    var phase by remember { mutableStateOf(VoiceTestPhase.READY) }
    var remaining by remember { mutableIntStateOf(0) }
    var progress by remember { mutableFloatStateOf(0f) }
    var typed by remember { mutableStateOf("") }
    val view = LocalView.current

    DisposableEffect(Unit) {
        view.keepScreenOn = true
        VoiceService.send(context, VoiceService.ACTION_STOP_TEST_START)
        onDispose {
            view.keepScreenOn = false
            VoiceService.tap = null
            VoiceService.send(context, VoiceService.ACTION_STOP_TEST_STOP)
        }
    }
    BackHandler(enabled = phase != VoiceTestPhase.READY) {}

    val scope = rememberCoroutineScope()
    val run: () -> Unit = {
        phase = VoiceTestPhase.COUNTDOWN
        scope.launch {
            record(
                step = steps[index],
                dir = dir,
                onCountdown = { remaining = it },
                onRecording = { phase = VoiceTestPhase.RECORDING },
                onProgress = { left, fraction ->
                    remaining = left
                    progress = fraction
                },
            )
        }
            .invokeOnCompletion { error ->
                if (error == null) {
                    typed = ""
                    index++
                }
                phase = VoiceTestPhase.READY
            }
    }

    SettingsScaffold(title = stringResource(R.string.voice_test_title), onBack = onBack) {
        item {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (index >= steps.size) {
                        Text(stringResource(R.string.voice_test_done, steps.size), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { index = 0 }) { Text(stringResource(R.string.voice_test_restart)) }
                        return@Column
                    }
                    val step = steps[index]
                    Text(stringResource(R.string.voice_test_step, index + 1, steps.size), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(stringResource(step.title), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(step.body), style = MaterialTheme.typography.bodyLarge)
                    if (step.typing && phase == VoiceTestPhase.RECORDING) {
                        OutlinedTextField(value = typed, onValueChange = { typed = it }, modifier = Modifier.fillMaxWidth().height(120.dp))
                    }
                    when (phase) {
                        VoiceTestPhase.READY -> {
                            Text(stringResource(R.string.voice_test_ready, step.countdown, VOICE_TEST_SECONDS), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = run) { Text(stringResource(R.string.voice_test_start)) }
                                TextButton(onClick = { index++ }) { Text(stringResource(R.string.voice_test_skip)) }
                                if (index > 0) TextButton(onClick = { index-- }) { Text(stringResource(R.string.voice_test_redo_previous)) }
                            }
                        }
                        VoiceTestPhase.COUNTDOWN -> Text(stringResource(R.string.voice_test_starts_in, remaining), style = MaterialTheme.typography.displaySmall)
                        VoiceTestPhase.RECORDING -> {
                            Text(stringResource(R.string.voice_test_recording, remaining), style = MaterialTheme.typography.titleMedium)
                            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
        item {
            Text(
                stringResource(R.string.voice_test_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

private suspend fun record(
    step: VoiceTestStep,
    dir: File,
    onCountdown: (Int) -> Unit,
    onRecording: () -> Unit,
    onProgress: (Int, Float) -> Unit,
) {
    for (s in step.countdown downTo 1) {
        onCountdown(s)
        delay(1_000)
    }
    Earcons.play(Earcon.WAKE)
    delay(300)
    onRecording()
    val total = VOICE_TEST_SECONDS * AudioSpec.SAMPLE_RATE
    val out = ByteBuffer.allocate(total * 2).order(ByteOrder.LITTLE_ENDIAN)
    val done = CompletableDeferred<Unit>()
    VoiceService.tap = { pcm, n ->
        for (i in 0 until minOf(n, out.remaining() / 2)) out.putShort(pcm[i])
        if (!out.hasRemaining()) {
            VoiceService.tap = null
            done.complete(Unit)
        }
    }
    try {
        while (!done.isCompleted) {
            val captured = out.position() / 2
            onProgress((total - captured) / AudioSpec.SAMPLE_RATE, captured.toFloat() / total)
            delay(200)
        }
    } finally {
        VoiceService.tap = null
    }
    withContext(Dispatchers.IO) {
        dir.mkdirs()
        File(dir, "${step.key}.wav").writeBytes(OpenAiSpeechToText.wav(out.array()))
    }
    Earcons.play(Earcon.DONE)
}
