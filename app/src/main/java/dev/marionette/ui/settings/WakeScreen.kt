package dev.marionette.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.marionette.MarionetteApp
import dev.marionette.R
import dev.marionette.access.Access
import dev.marionette.ui.access.AccessList
import dev.marionette.ui.access.rememberAccessState
import dev.marionette.ui.components.SettingsGroup
import dev.marionette.ui.components.SettingsIcon
import dev.marionette.ui.components.SettingsNote
import dev.marionette.ui.components.SettingsRow
import dev.marionette.ui.components.SettingsScaffold
import dev.marionette.ui.components.SliderRow
import dev.marionette.ui.components.SwitchRow
import dev.marionette.ui.voice.WakeEnrollment
import dev.marionette.voice.VoiceBus
import dev.marionette.voice.VoiceService
import dev.marionette.voice.wake.WakeSensitivity
import kotlin.math.roundToInt

@Composable
fun WakeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings by MarionetteApp.graph.settings.settings.collectAsStateWithLifecycle()
    val voice by VoiceBus.status.collectAsStateWithLifecycle()
    val access = rememberAccessState()
    val micGranted = access.isGranted(Access.MICROPHONE)
    var sensitivity by remember { mutableFloatStateOf(WakeSensitivity.fromThreshold(settings.wakeThreshold)) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(micGranted) {
        if (micGranted) VoiceService.send(context, VoiceService.ACTION_RELOAD)
    }

    SettingsScaffold(title = stringResource(R.string.settings_wake), onBack = onBack) {
        if (!micGranted) {
            item { AccessList(null, listOf(Access.MICROPHONE), access) }
        }
        item {
            SettingsGroup(null) {
                item { i, n ->
                    SwitchRow(
                        i, n,
                        headline = stringResource(R.string.wake_enabled),
                        supporting = stringResource(R.string.wake_enabled_body),
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
        if (micGranted) {
            item {
                WakeEnrollment(
                    savedPhrase = settings.wakePhrase,
                    onPhrase = { phrase -> updateSettings { it.copy(wakePhrase = phrase) } },
                )
            }
        }
        item {
            SettingsGroup(stringResource(R.string.wake_group_detection)) {
                item { i, n ->
                    SliderRow(
                        i, n,
                        headline = stringResource(R.string.wake_sensitivity),
                        supporting = stringResource(R.string.wake_sensitivity_body),
                        valueLabel = "${(sensitivity * 100).roundToInt()}%",
                        value = sensitivity,
                        onValueChange = { sensitivity = it },
                        onValueChangeFinished = { updateSettings { it.copy(wakeThreshold = WakeSensitivity.toThreshold(sensitivity)) } },
                    )
                }
                item { i, n ->
                    SwitchRow(
                        i, n,
                        headline = stringResource(R.string.wake_headset),
                        supporting = stringResource(R.string.wake_headset_body),
                        checked = settings.headsetTrigger,
                        leading = { SettingsIcon(Icons.Rounded.Headset) },
                        onCheckedChange = { on -> updateSettings { it.copy(headsetTrigger = on) } },
                    )
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
            text = { Text(stringResource(R.string.wake_delete_body)) },
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
