package dev.marionette.ui.settings

import dev.marionette.ui.components.settingsPanelColor
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Mic
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
import dev.marionette.ui.components.SettingsNote
import androidx.compose.foundation.layout.heightIn
import dev.marionette.ui.components.SettingsGroup
import dev.marionette.ui.components.SettingsIcon
import dev.marionette.ui.components.SettingsRow
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

private enum class VoiceTestStep(val key: String, @StringRes val title: Int, @StringRes val body: Int, val countdown: Int, val typing: Boolean = false, val seconds: Int = 30) {
    CLOSE("wake-close", R.string.voice_test_close, R.string.voice_test_close_body, 3),
    ARM("wake-arm", R.string.voice_test_arm, R.string.voice_test_arm_body, 5),
    TWO_METERS("wake-2m", R.string.voice_test_2m, R.string.voice_test_2m_body, 10),
    ACROSS("wake-far", R.string.voice_test_far, R.string.voice_test_far_body, 10),
    TYPING("noise-typing", R.string.voice_test_typing, R.string.voice_test_typing_body, 3, typing = true),
    HANDLING("noise-handling", R.string.voice_test_handling, R.string.voice_test_handling_body, 3),
    BREATHING("noise-breath", R.string.voice_test_breathing, R.string.voice_test_breathing_body, 3),
    TALKING("noise-talk", R.string.voice_test_talking, R.string.voice_test_talking_body, 3, seconds = 120),
    ROOM("noise-room", R.string.voice_test_room, R.string.voice_test_room_body, 5),
}

private enum class VoiceTestPhase { READY, COUNTDOWN, RECORDING }

@Composable
fun VoiceTestScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val steps = VoiceTestStep.entries
    val dir = remember { File(context.getExternalFilesDir(null), "voice-test") }
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    var saved by remember { mutableIntStateOf(0) }
    var phase by remember { mutableStateOf(VoiceTestPhase.READY) }
    var remaining by remember { mutableIntStateOf(0) }
    var progress by remember { mutableFloatStateOf(0f) }
    var typed by remember { mutableStateOf("") }
    val view = LocalView.current
    val recorded = remember(saved) { steps.associateWith { File(dir, "${it.key}.wav").exists() } }

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
    BackHandler(enabled = phase == VoiceTestPhase.READY && selected != null) { selected = null }

    val scope = rememberCoroutineScope()
    val run: (VoiceTestStep) -> Unit = { step ->
        phase = VoiceTestPhase.COUNTDOWN
        scope.launch {
            record(
                step = step,
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
                    saved++
                    selected = null
                }
                phase = VoiceTestPhase.READY
            }
    }

    SettingsScaffold(title = stringResource(R.string.voice_test_title), onBack = onBack) {
        val step = selected?.let(steps::get)
        if (step == null) {
            item {
                Text(
                    stringResource(R.string.voice_test_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            item {
                SettingsGroup(null) {
                    steps.forEachIndexed { index, s ->
                        item { i, n ->
                            val done = recorded[s] == true
                            SettingsRow(
                                i, n,
                                headline = stringResource(s.title),
                                supporting = stringResource(if (done) R.string.voice_test_recorded else R.string.voice_test_not_recorded, s.seconds),
                                leading = { SettingsIcon(if (done) Icons.Rounded.CheckCircle else Icons.Rounded.Mic) },
                                onClick = { selected = index },
                            )
                        }
                    }
                }
            }
        } else {
            item {
                Surface(shape = RoundedCornerShape(28.dp), color = settingsPanelColor(), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(step.title), style = MaterialTheme.typography.headlineSmall)
                        Text(stringResource(step.body), style = MaterialTheme.typography.bodyLarge)
                        if (step.typing && phase == VoiceTestPhase.RECORDING) {
                            OutlinedTextField(value = typed, onValueChange = { typed = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp))
                        }
                        when (phase) {
                            VoiceTestPhase.READY -> {
                                Text(stringResource(R.string.voice_test_ready, step.countdown, step.seconds), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { run(step) }) {
                                        Text(stringResource(if (recorded[step] == true) R.string.voice_test_record_again else R.string.voice_test_start))
                                    }
                                    TextButton(onClick = { selected = null }) { Text(stringResource(R.string.voice_test_back)) }
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
        }
        item {
            SettingsNote(stringResource(R.string.voice_test_note))
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
    val total = step.seconds * AudioSpec.SAMPLE_RATE
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
