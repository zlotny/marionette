package dev.marionette.ui.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.SignalWifiOff
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.marionette.MarionetteApp
import dev.marionette.R
import dev.marionette.health.BackendHealth
import dev.marionette.settings.AppSettings
import dev.marionette.settings.Languages
import dev.marionette.ui.agentName
import dev.marionette.voice.VoicePhase
import dev.marionette.voice.VoiceService
import dev.marionette.voice.VoiceStatus
import dev.marionette.voice.stt.AndroidSpeechToText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URI

enum class Issue { OFFLINE, UNREACHABLE, REJECTED, FAILING, VOICE_OFF, PAUSED, SPEECH_LANGUAGE, NO_WAKE }

private const val VOICE_SETTLE_MILLIS = 2_500L

@Composable
fun rememberIssue(backend: BackendHealth, voice: VoiceStatus, settings: AppSettings): Issue? {
    var settled by remember { mutableStateOf(backend) }
    LaunchedEffect(backend) { if (backend != BackendHealth.Checking) settled = backend }
    var voiceOff by remember { mutableStateOf(false) }
    var noWake by remember { mutableStateOf(false) }
    val off = voice.phase == VoicePhase.OFF
    val missingWake = settings.wakeEnabled && !off && voice.phase != VoicePhase.ENROLLING && voice.templateCount == 0
    LaunchedEffect(off, missingWake) {
        voiceOff = false
        noWake = false
        delay(VOICE_SETTLE_MILLIS)
        voiceOff = off
        noWake = missingWake
    }
    return when {
        settled == BackendHealth.Offline -> Issue.OFFLINE
        settled == BackendHealth.Unreachable -> Issue.UNREACHABLE
        settled == BackendHealth.Rejected -> Issue.REJECTED
        settled is BackendHealth.Failing -> Issue.FAILING
        voiceOff -> Issue.VOICE_OFF
        voice.phase == VoicePhase.PAUSED -> Issue.PAUSED
        voice.speechLanguageMissing && !off -> Issue.SPEECH_LANGUAGE
        noWake -> Issue.NO_WAKE
        else -> null
    }
}

private data class IssuePresentation(val icon: ImageVector, val pill: Int, val title: Int)

private val Issue.presentation: IssuePresentation
    get() = when (this) {
        Issue.OFFLINE -> IssuePresentation(Icons.Rounded.SignalWifiOff, R.string.health_offline_pill, R.string.health_offline_title)
        Issue.UNREACHABLE -> IssuePresentation(Icons.Rounded.CloudOff, R.string.health_unreachable_pill, R.string.health_unreachable_title)
        Issue.REJECTED -> IssuePresentation(Icons.Rounded.Key, R.string.health_auth_pill, R.string.health_auth_title)
        Issue.FAILING -> IssuePresentation(Icons.Rounded.Warning, R.string.health_error_pill, R.string.health_error_title)
        Issue.VOICE_OFF -> IssuePresentation(Icons.Rounded.MicOff, R.string.status_voice_off, R.string.health_voice_off_title)
        Issue.PAUSED -> IssuePresentation(Icons.Rounded.PauseCircle, R.string.health_paused_pill, R.string.health_paused_title)
        Issue.SPEECH_LANGUAGE -> IssuePresentation(Icons.Rounded.Translate, R.string.health_language_pill, R.string.health_language_title)
        Issue.NO_WAKE -> IssuePresentation(Icons.Rounded.RecordVoiceOver, R.string.health_wake_pill, R.string.health_wake_title)
    }

private val Issue.severe: Boolean get() = this != Issue.NO_WAKE && this != Issue.PAUSED

@Composable
fun HealthPill(issue: Issue, onClick: () -> Unit) {
    val severe = issue.severe
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (severe) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = if (severe) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.padding(end = 4.dp),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (severe) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary))
            Spacer(Modifier.size(6.dp))
            Text(stringResource(issue.presentation.pill), style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HealthSheet(
    issue: Issue,
    backend: BackendHealth,
    settings: AppSettings,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onOpenBackend: () -> Unit,
    onOpenWake: () -> Unit,
    onOpenPermissions: () -> Unit,
) {
    val context = LocalContext.current
    val name = agentName()
    val host = remember(settings.baseUrl) { runCatching { URI(settings.baseUrl).host }.getOrNull() ?: settings.baseUrl }
    val body = when (issue) {
        Issue.OFFLINE -> stringResource(R.string.health_offline_body, name)
        Issue.UNREACHABLE -> stringResource(R.string.health_unreachable_body, host)
        Issue.REJECTED -> stringResource(R.string.health_auth_body, host)
        Issue.FAILING -> stringResource(R.string.health_error_body, host, (backend as? BackendHealth.Failing)?.message.orEmpty())
        Issue.VOICE_OFF -> stringResource(R.string.health_voice_off_body)
        Issue.PAUSED -> stringResource(R.string.health_paused_body, name)
        Issue.SPEECH_LANGUAGE -> stringResource(R.string.health_language_body, Languages.displayName(settings.language.ifBlank { LocalConfiguration.current.locales[0].toLanguageTag() }))
        Issue.NO_WAKE -> stringResource(R.string.health_wake_body, name)
    }
    val backendIssue = issue in setOf(Issue.OFFLINE, Issue.UNREACHABLE, Issue.REJECTED, Issue.FAILING)
    val checking = backend == BackendHealth.Checking
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(issue.presentation.icon, null, Modifier.size(32.dp), tint = if (issue.severe) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary)
            Text(stringResource(issue.presentation.title, name), style = MaterialTheme.typography.headlineSmall)
            Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(4.dp))
            when (issue) {
                Issue.OFFLINE -> SheetButton(R.string.health_action_network) {
                    runCatching { context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
                }
                Issue.UNREACHABLE, Issue.REJECTED, Issue.FAILING -> SheetButton(R.string.health_action_connection) {
                    onDismiss()
                    onOpenBackend()
                }
                Issue.VOICE_OFF -> SheetButton(R.string.health_action_voice) {
                    val mic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    onDismiss()
                    if (mic) VoiceService.send(context, VoiceService.ACTION_START) else onOpenPermissions()
                }
                Issue.PAUSED -> SheetButton(R.string.health_action_resume) {
                    onDismiss()
                    MarionetteApp.graph.scope.launch { MarionetteApp.graph.settings.update { it.copy(listeningPaused = false) } }
                }
                Issue.SPEECH_LANGUAGE -> SheetButton(R.string.health_action_download) {
                    AndroidSpeechToText.downloadLanguage(context, settings.language)
                    onDismiss()
                }
                Issue.NO_WAKE -> SheetButton(R.string.health_action_wake) {
                    onDismiss()
                    onOpenWake()
                }
            }
            if (backendIssue) {
                OutlinedButton(onClick = onRetry, enabled = !checking, modifier = Modifier.fillMaxWidth()) {
                    if (checking) {
                        LoadingIndicator(Modifier.size(20.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.health_checking))
                    } else {
                        Text(stringResource(R.string.health_action_retry))
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetButton(label: Int, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(stringResource(label)) }
}
