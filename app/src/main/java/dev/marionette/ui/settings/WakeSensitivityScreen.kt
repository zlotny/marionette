package dev.marionette.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.marionette.MarionetteApp
import dev.marionette.R
import dev.marionette.ui.agentName
import dev.marionette.ui.components.SettingsGroup
import dev.marionette.ui.components.SettingsIcon
import dev.marionette.ui.components.SettingsNote
import dev.marionette.ui.components.SettingsScaffold
import dev.marionette.ui.components.SliderRow
import dev.marionette.ui.components.SwitchRow
import dev.marionette.voice.VoiceBus
import dev.marionette.voice.VoiceService
import dev.marionette.voice.wake.WakeSensitivity
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun WakeSensitivityScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings by MarionetteApp.graph.settings.settings.collectAsStateWithLifecycle()
    val voice by VoiceBus.status.collectAsStateWithLifecycle()
    var sensitivity by remember { mutableFloatStateOf(WakeSensitivity.fromThreshold(settings.wakeThreshold)) }
    var testing by remember { mutableStateOf(false) }

    LifecycleStartEffect(testing) {
        if (testing) VoiceService.send(context, VoiceService.ACTION_WAKE_TEST_START)
        onStopOrDispose { if (testing) VoiceService.send(context, VoiceService.ACTION_WAKE_TEST_STOP) }
    }

    SettingsScaffold(title = stringResource(R.string.wake_sensitivity), onBack = onBack) {
        item {
            SettingsGroup(null) {
                item { i, n ->
                    SwitchRow(
                        i, n,
                        headline = stringResource(R.string.wake_test_live),
                        supporting = stringResource(R.string.wake_test_live_body, agentName()),
                        checked = testing,
                        enabled = voice.wakeArmed,
                        leading = { SettingsIcon(Icons.Rounded.Hearing) },
                        onCheckedChange = { testing = it },
                    )
                }
            }
        }
        if (testing) item { DetectionCard(voice.lastWakeAt) }
        item {
            SettingsGroup(null) {
                item { i, n ->
                    SliderRow(
                        i, n,
                        headline = stringResource(R.string.wake_sensitivity),
                        supporting = stringResource(R.string.wake_sensitivity_body),
                        valueLabel = "${(sensitivity * 100).roundToInt()}%",
                        value = sensitivity,
                        steps = 19,
                        onValueChange = { value ->
                            sensitivity = value
                            updateSettings { it.copy(wakeThreshold = WakeSensitivity.toThreshold(value)) }
                        },
                    )
                }
            }
        }
        if (!voice.wakeArmed) item { SettingsNote(stringResource(R.string.wake_test_unavailable)) }
    }
}

@Composable
private fun DetectionCard(lastWakeAt: Long) {
    val since = remember { mutableLongStateOf(System.currentTimeMillis()) }
    var detections by remember { mutableIntStateOf(0) }
    var heard by remember { mutableStateOf(false) }
    LaunchedEffect(lastWakeAt) {
        if (lastWakeAt > since.longValue) {
            detections++
            heard = true
            delay(1_500)
            heard = false
        }
    }
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AnimatedContent(
                targetState = heard,
                transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.8f)) togetherWith fadeOut() },
                label = "heard",
            ) { ok ->
                if (ok) {
                    Icon(Icons.Rounded.CheckCircle, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                } else {
                    Icon(Icons.Rounded.Mic, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                stringResource(if (heard) R.string.wake_heard else R.string.wake_test_prompt),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.wake_test_detections, detections),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
