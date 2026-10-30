package dev.marionette.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.marionette.BuildConfig
import dev.marionette.MarionetteApp
import dev.marionette.R
import dev.marionette.ui.agentName
import dev.marionette.access.Access
import dev.marionette.settings.AppSettings
import dev.marionette.settings.Languages
import dev.marionette.settings.SttEngine
import dev.marionette.settings.TtsEngine
import dev.marionette.ui.ActivityRoute
import dev.marionette.ui.access.rememberAccessState
import dev.marionette.ui.components.SettingsGroup
import dev.marionette.ui.components.SettingsIcon
import dev.marionette.ui.components.SettingsRow
import dev.marionette.ui.components.SettingsScaffold
import dev.marionette.ui.components.TextRow
import dev.marionette.voice.VoiceBus

@Composable
fun SettingsScreen(onBack: () -> Unit, onOpen: (Any) -> Unit) {
    val context = LocalContext.current
    val graph = MarionetteApp.graph
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val voice by VoiceBus.status.collectAsStateWithLifecycle()
    val audioDevices by graph.audioRoutes.devices.collectAsStateWithLifecycle()
    val audioConnected by graph.audioRoutes.connected.collectAsStateWithLifecycle()
    val access = rememberAccessState()
    val accesses = Access.relevant(context, settings)
    val granted = accesses.count(access::isGranted)
    val missingEssential = accesses.any { it.essential && !access.isGranted(it) }
    val toolCount = graph.registry.tools.size
    val enabledTools = graph.registry.tools.count { it.name !in settings.disabledTools }
    val off = stringResource(R.string.state_off)
    val on = stringResource(R.string.state_on)

    SettingsScaffold(title = stringResource(R.string.settings_title), onBack = onBack) {
        item {
            SettingsGroup(stringResource(R.string.settings_group_assistant)) {
                item { i, n ->
                    TextRow(
                        i, n,
                        headline = stringResource(R.string.settings_agent_name),
                        value = settings.agentName,
                        label = stringResource(R.string.agent_name_label),
                        maxLength = AppSettings.MAX_AGENT_NAME,
                        leading = { SettingsIcon(Icons.Rounded.Badge) },
                        onSave = { name -> if (name.isNotBlank()) updateSettings { it.copy(agentName = name) } },
                    )
                }
                item { i, n ->
                    SettingsRow(
                        i, n,
                        headline = stringResource(R.string.settings_backend),
                        supporting = "${settings.provider.title} · ${settings.model}",
                        leading = { SettingsIcon(Icons.Rounded.Cloud) },
                        onClick = { onOpen(SettingsNavigation.Backend) },
                    )
                }
                item { i, n ->
                    SettingsRow(
                        i, n,
                        headline = stringResource(R.string.settings_instructions),
                        supporting = when {
                            settings.provider.isAgent -> stringResource(R.string.instructions_summary_agent)
                            !settings.sendInstructions -> off
                            settings.instructions.isBlank() -> stringResource(R.string.instructions_summary_default)
                            else -> stringResource(R.string.instructions_summary_custom)
                        },
                        leading = { SettingsIcon(Icons.Rounded.Description) },
                        onClick = { onOpen(SettingsNavigation.Instructions) },
                    )
                }
                item { i, n ->
                    SettingsRow(
                        i, n,
                        headline = stringResource(R.string.settings_context),
                        supporting = stringResource(R.string.settings_context_summary, settings.contextFields.size),
                        leading = { SettingsIcon(Icons.Rounded.DataObject) },
                        onClick = { onOpen(SettingsNavigation.Context) },
                    )
                }
            }
        }
        item {
            SettingsGroup(stringResource(R.string.settings_group_voice)) {
                item { i, n ->
                    SettingsRow(
                        i, n,
                        headline = stringResource(R.string.settings_wake),
                        supporting = when {
                            !settings.wakeEnabled -> off
                            voice.templateCount == 0 -> stringResource(R.string.wake_not_set)
                            else -> settings.wakePhrase.ifBlank { on }
                        },
                        leading = { SettingsIcon(Icons.Rounded.RecordVoiceOver) },
                        onClick = { onOpen(SettingsNavigation.Wake) },
                    )
                }
                item { i, n ->
                    SettingsRow(
                        i, n,
                        headline = stringResource(R.string.settings_stop_phrases),
                        supporting = voice.stopPhrases.takeIf { it.isNotEmpty() }?.joinToString(" · ") { it.label } ?: stringResource(R.string.wake_not_set),
                        leading = { SettingsIcon(Icons.Rounded.PanTool) },
                        onClick = { onOpen(SettingsNavigation.StopPhrases) },
                    )
                }
                item { i, n ->
                    SettingsRow(
                        i, n,
                        headline = stringResource(R.string.voice_group_recognition),
                        supporting = listOf(
                            if (settings.language.isBlank()) stringResource(R.string.voice_language_system) else Languages.displayName(settings.language),
                            stringResource(if (settings.sttEngine == SttEngine.CLOUD) R.string.voice_engine_cloud else R.string.voice_engine_device),
                        ).joinToString(" · "),
                        leading = { SettingsIcon(Icons.Rounded.Translate) },
                        onClick = { onOpen(SettingsNavigation.Recognition) },
                    )
                }
                item { i, n ->
                    SettingsRow(
                        i, n,
                        headline = stringResource(R.string.voice_group_speech),
                        supporting = listOf(
                            if (settings.ttsEngine == TtsEngine.CLOUD) settings.ttsCloud.title else stringResource(R.string.voice_speech_system),
                            "%.1f×".format(settings.speechRate),
                        ).joinToString(" · "),
                        leading = { SettingsIcon(Icons.Rounded.GraphicEq) },
                        onClick = { onOpen(SettingsNavigation.Speech) },
                    )
                }
                item { i, n ->
                    SettingsRow(
                        i, n,
                        headline = stringResource(R.string.audio_devices_title),
                        supporting = audioDevices.firstOrNull { it.enabled && it.id in audioConnected }?.let { deviceName(it) } ?: stringResource(R.string.audio_devices_phone),
                        leading = { SettingsIcon(Icons.Rounded.Headphones) },
                        onClick = { onOpen(SettingsNavigation.AudioDevices) },
                    )
                }
                item { i, n ->
                    SettingsRow(
                        i, n,
                        headline = stringResource(R.string.voice_group_conversation),
                        supporting = stringResource(
                            R.string.settings_conversation_summary,
                            if (settings.followUpSeconds == 0) off else stringResource(R.string.unit_s, settings.followUpSeconds),
                            if (settings.earcons) stringResource(R.string.voice_earcons) else off,
                        ),
                        leading = { SettingsIcon(Icons.Rounded.Forum) },
                        onClick = { onOpen(SettingsNavigation.Conversation) },
                    )
                }
            }
        }
        item {
            SettingsGroup(stringResource(R.string.settings_group_phone)) {
                item { i, n ->
                    SettingsRow(
                        i, n,
                        headline = stringResource(R.string.settings_tools),
                        supporting = stringResource(R.string.settings_tools_summary, enabledTools, toolCount),
                        leading = { SettingsIcon(Icons.Rounded.Build) },
                        onClick = { onOpen(SettingsNavigation.Tools) },
                    )
                }
                item { i, n ->
                    SettingsRow(
                        i, n,
                        headline = stringResource(R.string.settings_permissions),
                        supporting = if (granted == accesses.size) {
                            stringResource(R.string.permissions_summary_all)
                        } else {
                            stringResource(R.string.permissions_summary_missing, granted, accesses.size)
                        },
                        leading = {
                            if (missingEssential) {
                                SettingsIcon(Icons.Rounded.Shield, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                            } else {
                                SettingsIcon(Icons.Rounded.Shield)
                            }
                        },
                        onClick = { onOpen(SettingsNavigation.Permissions) },
                    )
                }
                item { i, n ->
                    SettingsRow(
                        i, n,
                        headline = stringResource(R.string.settings_screen_control),
                        supporting = if (access.isGranted(Access.SCREEN_CONTROL)) on else off,
                        leading = { SettingsIcon(Icons.Rounded.TouchApp) },
                        onClick = { onOpen(SettingsNavigation.ScreenControl) },
                    )
                }
            }
        }
        item {
            SettingsGroup(stringResource(R.string.settings_group_accounts)) {
                item { i, n ->
                    SettingsRow(
                        i, n,
                        headline = stringResource(R.string.settings_credentials),
                        supporting = pluralStringResource(R.plurals.settings_credentials_summary, settings.credentials.size, settings.credentials.size),
                        leading = { SettingsIcon(Icons.Rounded.Key) },
                        onClick = { onOpen(SettingsNavigation.Credentials) },
                    )
                }
            }
        }
        item {
            SettingsGroup(null) {
                item { i, n ->
                    SettingsRow(
                        i, n,
                        headline = stringResource(R.string.activity_title),
                        supporting = stringResource(R.string.settings_activity_summary, agentName()),
                        leading = { SettingsIcon(Icons.Rounded.History) },
                        onClick = { onOpen(ActivityRoute) },
                    )
                }
                item { i, n ->
                    SettingsRow(
                        i, n,
                        headline = stringResource(R.string.settings_about),
                        supporting = stringResource(R.string.settings_about_summary, BuildConfig.VERSION_NAME),
                        leading = { SettingsIcon(Icons.Rounded.Info) },
                        onClick = { onOpen(SettingsNavigation.About) },
                    )
                }
            }
        }
    }
}
