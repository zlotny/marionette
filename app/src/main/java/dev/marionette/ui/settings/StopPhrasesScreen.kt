package dev.marionette.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.marionette.R
import dev.marionette.access.Access
import dev.marionette.ui.access.AccessList
import dev.marionette.ui.access.rememberAccessState
import dev.marionette.ui.agentName
import dev.marionette.ui.components.SettingsGroup
import dev.marionette.ui.components.SettingsIcon
import dev.marionette.ui.components.SettingsNote
import dev.marionette.ui.components.SettingsRow
import dev.marionette.ui.components.SettingsScaffold
import dev.marionette.ui.voice.ENROLL_STEP_TARGET
import dev.marionette.ui.voice.GuidedEnrollmentContent
import dev.marionette.ui.voice.rememberGuidedEnrollment
import dev.marionette.voice.VoiceBus
import dev.marionette.voice.VoicePhase
import dev.marionette.voice.VoiceService
import dev.marionette.voice.wake.StopPhrase
import kotlinx.coroutines.delay

private data class StopDraft(val id: String, val label: String, val append: Boolean)

@Composable
fun StopPhrasesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val voice by VoiceBus.status.collectAsStateWithLifecycle()
    val access = rememberAccessState()
    val micGranted = access.isGranted(Access.MICROPHONE)
    var draftId by rememberSaveable { mutableStateOf<String?>(null) }
    var draftLabel by rememberSaveable { mutableStateOf("") }
    var draftAppend by rememberSaveable { mutableStateOf(false) }
    val draft = draftId?.let { StopDraft(it, draftLabel, draftAppend) }
    val open: (StopPhrase?) -> Unit = { phrase ->
        draftId = phrase?.id ?: System.currentTimeMillis().toString()
        draftLabel = phrase?.label.orEmpty()
        draftAppend = phrase != null
    }

    LaunchedEffect(micGranted) {
        if (micGranted) VoiceService.send(context, VoiceService.ACTION_RELOAD)
    }

    SettingsScaffold(title = stringResource(R.string.settings_stop_phrases), onBack = onBack) {
        item { SettingsNote(stringResource(R.string.stop_intro, agentName())) }
        if (!micGranted) {
            item { AccessList(null, listOf(Access.MICROPHONE), access) }
            return@SettingsScaffold
        }
        item {
            SettingsGroup(null) {
                voice.stopPhrases.forEach { phrase ->
                    item { i, n ->
                        SettingsRow(
                            i, n,
                            headline = phrase.label,
                            supporting = stringResource(R.string.stop_recordings, phrase.recordings),
                            leading = { SettingsIcon(Icons.Rounded.PanTool) },
                            trailing = {
                                IconButton(onClick = { VoiceService.deleteStopPhrase(context, phrase.id) }) {
                                    Icon(Icons.Rounded.Delete, stringResource(R.string.action_delete))
                                }
                            },
                            enabled = draft == null,
                            onClick = { open(phrase) },
                        )
                    }
                }
                item { i, n ->
                    SettingsRow(
                        i, n,
                        headline = stringResource(R.string.stop_add),
                        leading = { SettingsIcon(Icons.Rounded.Add) },
                        enabled = draft == null,
                        onClick = { open(null) },
                    )
                }
            }
        }
        draft?.let { d ->
            item {
                StopEnrollment(
                    draft = d,
                    onLabel = { draftLabel = it },
                    onClose = { draftId = null },
                )
            }
        }
        if (draft == null && voice.stopPhrases.isNotEmpty()) item { StopTest(voice.lastStopAt) }
        item { SettingsNote(stringResource(R.string.stop_note)) }
    }
}

@Composable
private fun StopEnrollment(draft: StopDraft, onLabel: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val voice by VoiceBus.status.collectAsStateWithLifecycle()
    val guided = rememberGuidedEnrollment(
        start = { append -> VoiceService.enrollStopPhrase(context, draft.id, draft.label.trim(), append) },
        onDone = onClose,
    )

    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (voice.phase == VoicePhase.ENROLLING || guided.saving || guided.active) {
                GuidedEnrollmentContent(guided, draft.label)
                return@Column
            }
            OutlinedTextField(
                value = draft.label,
                onValueChange = onLabel,
                label = { Text(stringResource(R.string.stop_phrase)) },
                placeholder = { Text(stringResource(R.string.stop_phrase_hint, agentName())) },
                singleLine = true,
                enabled = !draft.append,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.wake_record_body, ENROLL_STEP_TARGET),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text(stringResource(R.string.action_cancel)) }
                Spacer(Modifier.size(8.dp))
                Button(
                    onClick = { guided.begin(append = draft.append) },
                    enabled = draft.label.isNotBlank(),
                    modifier = Modifier.height(48.dp),
                ) {
                    Icon(Icons.Rounded.Mic, null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.wake_record))
                }
            }
        }
    }
}

@Composable
private fun StopTest(lastStopAt: Long) {
    val context = LocalContext.current
    val since = remember { mutableLongStateOf(System.currentTimeMillis()) }
    var heard by remember { mutableStateOf(false) }
    LifecycleStartEffect(Unit) {
        VoiceService.send(context, VoiceService.ACTION_STOP_TEST_START)
        onStopOrDispose { VoiceService.send(context, VoiceService.ACTION_STOP_TEST_STOP) }
    }
    LaunchedEffect(lastStopAt) {
        if (lastStopAt > since.longValue) {
            heard = true
            delay(2_500)
            heard = false
        }
    }
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AnimatedContent(
                targetState = heard,
                transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.8f)) togetherWith fadeOut() },
                label = "stopHeard",
            ) { ok ->
                if (ok) {
                    Icon(Icons.Rounded.CheckCircle, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                } else {
                    Icon(Icons.Rounded.Mic, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                stringResource(if (heard) R.string.wake_heard else R.string.stop_test_prompt),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}
