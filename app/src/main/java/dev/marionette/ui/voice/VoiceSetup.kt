package dev.marionette.ui.voice

import android.content.Context
import android.content.res.Resources
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.marionette.R
import dev.marionette.ui.agentName
import dev.marionette.settings.Languages
import dev.marionette.ui.components.VoiceOrb
import dev.marionette.ui.components.Waveform
import dev.marionette.voice.CloudVoice
import dev.marionette.voice.Speaker
import dev.marionette.voice.VoiceBus
import dev.marionette.voice.VoicePhase
import dev.marionette.voice.VoiceService
import dev.marionette.voice.wake.WakeTemplates
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val ENROLL_TARGET = 5

@Composable
fun rememberSpeaker(): Speaker {
    val context = LocalContext.current
    val speaker = remember { Speaker(context.applicationContext) }
    DisposableEffect(speaker) { onDispose { speaker.shutdown() } }
    return speaker
}

fun localizedResources(context: Context, language: String): Resources = Languages.resources(context, language)

fun localizedString(context: Context, language: String, id: Int, vararg args: Any): String =
    localizedResources(context, language).getString(id, *args)

@Composable
fun languageOptions(): List<Pair<String, String>> = Languages.supported.map { tag ->
    tag to if (tag.isBlank()) stringResource(R.string.voice_language_system) else Languages.displayName(tag)
}

@Composable
fun VoiceTestButton(language: String, voice: String, rate: Float, modifier: Modifier = Modifier, cloud: CloudVoice? = null) {
    val context = LocalContext.current
    val speaker = rememberSpeaker()
    val scope = rememberCoroutineScope()
    val name = agentName()
    var speaking by remember { mutableStateOf(false) }
    FilledTonalButton(
        onClick = {
            if (speaking) {
                speaker.stop()
            } else {
                scope.launch {
                    speaking = true
                    try {
                        speaker.speak(localizedString(context, language, R.string.voice_test_phrase, name), language, voice, rate, cloud)
                    } finally {
                        speaking = false
                    }
                }
            }
        },
        modifier = modifier,
    ) {
        Icon(Icons.Rounded.VolumeUp, null, Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(stringResource(if (speaking) R.string.action_stop else R.string.voice_test))
    }
}

@Composable
fun WakeEnrollment(savedPhrase: String, onPhrase: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var phrase by rememberSaveable { mutableStateOf(savedPhrase) }
    val voice by VoiceBus.status.collectAsStateWithLifecycle()
    val enrolling = voice.phase == VoicePhase.ENROLLING && !voice.enrollingSpeech
    val guided = rememberGuidedEnrollment(start = { append -> VoiceService.enroll(context, append) })

    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(
                value = phrase,
                onValueChange = {
                    phrase = it
                    onPhrase(it.trim())
                },
                label = { Text(stringResource(R.string.wake_phrase)) },
                placeholder = { Text(stringResource(R.string.wake_phrase_hint, agentName())) },
                singleLine = true,
                enabled = !enrolling && !guided.active,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            AnimatedContent(
                targetState = when {
                    enrolling || guided.saving || guided.active -> EnrollStage.RECORDING
                    voice.templateCount > 0 -> EnrollStage.READY
                    else -> EnrollStage.IDLE
                },
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "enroll",
            ) { stage ->
                when (stage) {
                    EnrollStage.IDLE -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (voice.enrollRejected) {
                            Text(
                                stringResource(R.string.wake_record_rejected),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Text(
                            stringResource(R.string.wake_record_body, ENROLL_STEP_TARGET),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = { guided.begin(append = false) },
                            enabled = phrase.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) {
                            Icon(Icons.Rounded.Mic, null)
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.wake_record))
                        }
                    }
                    EnrollStage.RECORDING -> GuidedEnrollmentContent(guided, phrase)
                    EnrollStage.READY -> WakeTest(
                        lastWakeAt = voice.lastWakeAt,
                        armed = voice.wakeArmed,
                        templates = voice.templateCount,
                        onAddMore = { VoiceService.enroll(context, true) },
                        onRecordAgain = { guided.begin(append = false) },
                    )
                }
            }
        }
    }
}

private enum class EnrollStage { IDLE, RECORDING, READY }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun Recording(
    phrase: String,
    count: Int,
    minimum: Int,
    saving: Boolean,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
    target: Int = ENROLL_TARGET,
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (saving) {
            LoadingIndicator(Modifier.size(64.dp))
            Text(stringResource(R.string.wake_saving), style = MaterialTheme.typography.titleMedium)
            return@Column
        }
        VoiceOrb(size = 96.dp, listening = true)
        Text(
            stringResource(R.string.wake_say_now, phrase.ifBlank { stringResource(R.string.wake_phrase) }),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(target) { i -> ProgressDot(done = i < count) }
        }
        Text(
            if (count < target) stringResource(R.string.wake_count, count, target) else stringResource(R.string.wake_count_extra, count),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Waveform()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
            Spacer(Modifier.size(8.dp))
            if (count >= target) {
                Button(onClick = onFinish) { Text(stringResource(R.string.wake_finish)) }
            } else {
                FilledTonalButton(onClick = onFinish, enabled = count >= minimum) { Text(stringResource(R.string.wake_finish)) }
            }
        }
    }
}

@Composable
private fun ProgressDot(done: Boolean) {
    val color by animateColorAsState(
        if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
        label = "dot",
    )
    val scale by animateFloatAsState(if (done) 1.15f else 1f, label = "dotScale")
    Box(Modifier.size(16.dp).scale(scale).clip(CircleShape).background(color))
}

@Composable
private fun WakeTest(lastWakeAt: Long, armed: Boolean, templates: Int, onAddMore: () -> Unit, onRecordAgain: () -> Unit) {
    val context = LocalContext.current
    val since = remember { mutableLongStateOf(System.currentTimeMillis()) }
    var heard by remember { mutableStateOf(false) }
    LifecycleStartEffect(Unit) {
        VoiceService.send(context, VoiceService.ACTION_WAKE_TEST_START)
        onStopOrDispose { VoiceService.send(context, VoiceService.ACTION_WAKE_TEST_STOP) }
    }
    LaunchedEffect(lastWakeAt) {
        if (lastWakeAt > since.longValue) {
            heard = true
            delay(2_500)
            heard = false
        }
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AnimatedContent(
            targetState = heard,
            transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.8f)) togetherWith fadeOut() },
            label = "heard",
        ) { ok ->
            if (ok) {
                Icon(Icons.Rounded.CheckCircle, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
            } else {
                Icon(Icons.Rounded.Mic, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            stringResource(
                when {
                    heard -> R.string.wake_heard
                    armed -> R.string.wake_test_prompt
                    else -> R.string.wake_test_off
                },
            ),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.wake_templates, templates),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (templates < WakeTemplates.MAX_TEMPLATES) {
                FilledTonalButton(onClick = onAddMore) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.wake_add_more))
                }
            }
            TextButton(onClick = onRecordAgain) {
                Icon(Icons.Rounded.Replay, null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.wake_record_again))
            }
        }
    }
}
