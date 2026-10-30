package dev.marionette.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import dev.marionette.voice.QuietHours
import dev.marionette.voice.VoiceStatus
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.marionette.BuildConfig
import dev.marionette.MarionetteApp
import dev.marionette.R
import androidx.compose.foundation.layout.size
import dev.marionette.ui.agentName
import dev.marionette.access.Access
import dev.marionette.ui.access.AccessList
import dev.marionette.ui.access.rememberAccessState
import dev.marionette.ui.components.ChoiceRow
import dev.marionette.ui.components.SettingsGroup
import dev.marionette.ui.components.SettingsIcon
import dev.marionette.ui.components.SettingsNote
import dev.marionette.ui.components.SettingsRow
import dev.marionette.ui.components.SettingsScaffold
import dev.marionette.ui.components.SwitchRow
import dev.marionette.ui.voice.SpeechEnrollment
import dev.marionette.ui.voice.WakeEnrollment
import dev.marionette.voice.VoiceBus
import dev.marionette.voice.VoiceService
import dev.marionette.voice.wake.WakeSensitivity
import kotlin.math.roundToInt

@Composable
fun WakeScreen(onBack: () -> Unit, onOpenSensitivity: () -> Unit, onOpenVoiceTest: () -> Unit, onOpenActivations: () -> Unit) {
    val context = LocalContext.current
    val settings by MarionetteApp.graph.settings.settings.collectAsStateWithLifecycle()
    val voice by VoiceBus.status.collectAsStateWithLifecycle()
    val access = rememberAccessState()
    val micGranted = access.isGranted(Access.MICROPHONE)
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(micGranted) {
        if (micGranted) VoiceService.send(context, VoiceService.ACTION_RELOAD)
    }

    SettingsScaffold(title = stringResource(R.string.wake_training_title), onBack = onBack) {
        if (!micGranted) {
            item { AccessList(null, listOf(Access.MICROPHONE), access) }
        }
        item {
            SettingsGroup(null) {
                item { i, n ->
                    SwitchRow(
                        i, n,
                        headline = stringResource(R.string.wake_enabled),
                        supporting = stringResource(R.string.wake_enabled_body, agentName()),
                        checked = settings.wakeEnabled,
                        leading = { SettingsIcon(Icons.Rounded.RecordVoiceOver) },
                        onCheckedChange = { on ->
                            updateSettings { it.copy(wakeEnabled = on) }
                            VoiceService.send(context, VoiceService.ACTION_RELOAD)
                        },
                    )
                }
            }
        }
        if (micGranted && BuildConfig.DEBUG) {
            item {
                SettingsGroup(null) {
                    item { i, n ->
                        SettingsRow(
                            i, n,
                            headline = stringResource(R.string.voice_test_title),
                            supporting = stringResource(R.string.voice_test_entry_body),
                            leading = { SettingsIcon(Icons.Rounded.GraphicEq) },
                            onClick = onOpenVoiceTest,
                        )
                    }
                }
            }
        }
        if (micGranted) {
            item { TrainingCard(voice) }
            item {
                SettingsGroup(null) {
                    item { i, n ->
                        SettingsRow(
                            i, n,
                            headline = stringResource(R.string.activations_title),
                            supporting = stringResource(R.string.activations_entry_body),
                            leading = { SettingsIcon(Icons.Rounded.History) },
                            onClick = onOpenActivations,
                        )
                    }
                }
            }
            item {
                WakeEnrollment(
                    savedPhrase = settings.wakePhrase,
                    onPhrase = { phrase -> updateSettings { it.copy(wakePhrase = phrase) } },
                )
            }
            if (voice.templateCount > 0) {
                item { SpeechEnrollment() }
            }
        }
        item {
            SettingsGroup(stringResource(R.string.wake_group_detection)) {
                item { i, n ->
                    SettingsRow(
                        i, n,
                        headline = stringResource(R.string.wake_sensitivity),
                        supporting = stringResource(
                            R.string.wake_sensitivity_summary,
                            (WakeSensitivity.fromThreshold(settings.wakeThreshold) * 100).roundToInt(),
                        ),
                        leading = { SettingsIcon(Icons.Rounded.Tune) },
                        onClick = onOpenSensitivity,
                    )
                }
                item { i, n ->
                    SwitchRow(
                        i, n,
                        headline = stringResource(R.string.wake_headset),
                        supporting = stringResource(R.string.wake_headset_body, agentName()),
                        checked = settings.headsetTrigger,
                        leading = { SettingsIcon(Icons.Rounded.Headset) },
                        onCheckedChange = { on -> updateSettings { it.copy(headsetTrigger = on) } },
                    )
                }
                item { i, n ->
                    SwitchRow(
                        i, n,
                        headline = stringResource(R.string.wake_notification_talk),
                        supporting = stringResource(R.string.wake_notification_talk_body, agentName()),
                        checked = settings.notificationTalk,
                        leading = { SettingsIcon(Icons.Rounded.Notifications) },
                        onCheckedChange = { on -> updateSettings { it.copy(notificationTalk = on) } },
                    )
                }
                item { i, n ->
                    val assistant = access.isGranted(Access.ASSISTANT)
                    SwitchRow(
                        i, n,
                        headline = stringResource(R.string.wake_overlay),
                        supporting = if (assistant) {
                            stringResource(R.string.wake_overlay_body, agentName())
                        } else {
                            stringResource(R.string.wake_overlay_needs_assistant, stringResource(R.string.app_name))
                        },
                        checked = settings.wakeOverlay && assistant,
                        enabled = assistant,
                        leading = { SettingsIcon(Icons.Rounded.Wallpaper) },
                        onCheckedChange = { on -> updateSettings { it.copy(wakeOverlay = on) } },
                    )
                }
                item { i, n ->
                    val enabled = access.isGranted(Access.ASSISTANT) && settings.wakeOverlay
                    SwitchRow(
                        i, n,
                        headline = stringResource(R.string.wake_screen_on),
                        supporting = stringResource(R.string.wake_screen_on_body),
                        checked = settings.wakeScreenOn && enabled,
                        enabled = enabled,
                        leading = { SettingsIcon(Icons.Rounded.LightMode) },
                        onCheckedChange = { on -> updateSettings { it.copy(wakeScreenOn = on) } },
                    )
                }
                item { i, n ->
                    SwitchRow(
                        i, n,
                        headline = stringResource(R.string.quiet_hours),
                        supporting = if (settings.quietHours) {
                            stringResource(R.string.quiet_hours_body, QuietHours.label(settings.quietStart), QuietHours.label(settings.quietEnd))
                        } else {
                            stringResource(R.string.quiet_hours_off_body)
                        },
                        checked = settings.quietHours,
                        leading = { SettingsIcon(Icons.Rounded.Bedtime) },
                        onCheckedChange = { on -> updateSettings { it.copy(quietHours = on) } },
                    )
                }
                if (settings.quietHours) {
                    item { i, n ->
                        ChoiceRow(
                            i, n,
                            headline = stringResource(R.string.quiet_hours_start),
                            options = HALF_HOURS.map { it to QuietHours.label(it) },
                            selected = settings.quietStart,
                            onSelect = { minute -> updateSettings { it.copy(quietStart = minute) } },
                        )
                    }
                    item { i, n ->
                        ChoiceRow(
                            i, n,
                            headline = stringResource(R.string.quiet_hours_end),
                            options = HALF_HOURS.map { it to QuietHours.label(it) },
                            selected = settings.quietEnd,
                            onSelect = { minute -> updateSettings { it.copy(quietEnd = minute) } },
                        )
                    }
                }
                item { i, n ->
                    SettingsRow(
                        i, n,
                        headline = stringResource(R.string.wake_delete),
                        supporting = stringResource(R.string.wake_templates, voice.templateCount),
                        leading = {
                            SettingsIcon(Icons.Rounded.DeleteOutline, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                        },
                        enabled = voice.templateCount > 0,
                        onClick = { confirmDelete = true },
                    )
                }
            }
        }
        item { SettingsNote(stringResource(R.string.wake_privacy)) }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.wake_delete_title)) },
            text = { Text(stringResource(R.string.wake_delete_body, agentName())) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    VoiceService.send(context, VoiceService.ACTION_ENROLL_CLEAR)
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun TrainingCard(voice: VoiceStatus) {
    val steps = listOf(
        (voice.templateCount > 0) to stringResource(R.string.wake_training_phrase),
        (voice.speechRecordings > 0) to stringResource(R.string.wake_training_speech),
        (voice.falseWakes > 0) to if (voice.falseWakes > 0) {
            pluralStringResource(R.plurals.wake_training_false, voice.falseWakes, voice.falseWakes)
        } else {
            stringResource(R.string.wake_training_false_none)
        },
    )
    val next = when {
        voice.templateCount == 0 -> R.string.wake_training_next_phrase
        voice.speechRecordings == 0 -> R.string.wake_training_next_speech
        voice.usableTemplates < 2 -> R.string.wake_training_next_more
        else -> R.string.wake_training_next_use
    }
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.wake_training_intro, agentName()), style = MaterialTheme.typography.bodyLarge)
            steps.forEach { (done, label) ->
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(
                        if (done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                        null,
                        modifier = Modifier.size(20.dp),
                        tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text(stringResource(next), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

private val HALF_HOURS = (0 until QuietHours.DAY_MINUTES step 30).toList()
