package dev.marionette.ui.voice

import dev.marionette.ui.components.settingsPanelColor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.marionette.R
import androidx.compose.foundation.layout.heightIn
import dev.marionette.voice.VoiceBus
import dev.marionette.voice.VoicePhase
import dev.marionette.voice.VoiceService
import kotlinx.coroutines.delay

private const val SPEECH_SECONDS = 40
private val SPEECH_TEXTS = listOf(R.string.wake_speech_text_1, R.string.wake_speech_text_2, R.string.wake_speech_text_3)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SpeechEnrollment(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val voice by VoiceBus.status.collectAsStateWithLifecycle()
    var reading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    val enrolling = voice.phase == VoicePhase.ENROLLING
    val enrollingSpeech = enrolling && voice.enrollingSpeech
    val view = LocalView.current

    LaunchedEffect(reading) {
        if (!reading) return@LaunchedEffect
        progress = 0f
        val started = System.currentTimeMillis()
        while (progress < 1f) {
            delay(200)
            progress = ((System.currentTimeMillis() - started) / (SPEECH_SECONDS * 1000f)).coerceAtMost(1f)
        }
        VoiceService.send(context, VoiceService.ACTION_ENROLL_FINISH)
    }
    var wasEnrolling by remember { mutableStateOf(false) }
    LaunchedEffect(enrollingSpeech) {
        if (wasEnrolling && !enrollingSpeech) reading = false
        wasEnrolling = enrollingSpeech
    }
    DisposableEffect(reading) {
        view.keepScreenOn = reading
        onDispose {
            view.keepScreenOn = false
            if (reading && VoiceBus.status.value.phase == VoicePhase.ENROLLING) VoiceService.send(context, VoiceService.ACTION_ENROLL_CANCEL)
        }
    }
    BackHandler(enabled = reading) {}

    Surface(shape = RoundedCornerShape(28.dp), color = settingsPanelColor(), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.wake_speech_title), style = MaterialTheme.typography.titleLarge)
            when {
                reading && progress >= 1f -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LoadingIndicator(Modifier.size(40.dp))
                    Text(stringResource(R.string.wake_saving), style = MaterialTheme.typography.titleMedium)
                }
                reading -> {
                    Text(
                        stringResource(R.string.wake_speech_reading, ((1f - progress) * SPEECH_SECONDS).toInt()),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(SPEECH_TEXTS[voice.speechRecordings % SPEECH_TEXTS.size]), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.wake_speech_keep),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { VoiceService.send(context, VoiceService.ACTION_ENROLL_CANCEL) }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                }
                else -> {
                    Text(
                        stringResource(R.string.wake_speech_body, SPEECH_SECONDS),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (voice.speechRecordings > 0) {
                        Text(
                            stringResource(R.string.wake_speech_status, voice.speechRecordings, voice.usableTemplates, voice.templateCount),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (voice.usableTemplates < 2) {
                            Text(
                                stringResource(R.string.wake_speech_few_active),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    FilledTonalButton(
                        onClick = {
                            reading = true
                            VoiceService.enrollSpeech(context)
                        },
                        enabled = !enrolling,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) {
                        Icon(Icons.Rounded.RecordVoiceOver, null)
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(if (voice.speechRecordings > 0) R.string.wake_speech_again else R.string.wake_speech_start))
                    }
                }
            }
        }
    }
}
