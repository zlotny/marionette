package dev.marionette.ui.assist

import dev.marionette.ui.components.TranscriptText
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.marionette.MarionetteApp
import dev.marionette.R
import dev.marionette.agent.TurnStatus
import dev.marionette.ui.components.VoiceOrb
import dev.marionette.voice.VoiceBus
import dev.marionette.voice.VoicePhase
import kotlinx.coroutines.delay

private const val AUTO_HIDE_MILLIS = 5_000L

@Composable
fun AssistOverlay(sinceTurnId: Long, onTalk: () -> Unit, onDismiss: () -> Unit) {
    val graph = MarionetteApp.graph
    val voice by VoiceBus.status.collectAsStateWithLifecycle()
    val turns by graph.conversation.turns.collectAsStateWithLifecycle()
    val confirmation by graph.confirmations.pending.collectAsStateWithLifecycle()
    val turn = turns.lastOrNull()?.takeIf { it.id > sinceTurnId }
    var engaged by remember { mutableStateOf(false) }
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }

    LaunchedEffect(voice.phase) {
        if (voice.phase != VoicePhase.WAITING && voice.phase != VoicePhase.OFF) engaged = true
    }
    LaunchedEffect(engaged, voice.phase, confirmation) {
        if (engaged && confirmation == null && (voice.phase == VoicePhase.WAITING || voice.phase == VoicePhase.OFF)) {
            delay(AUTO_HIDE_MILLIS)
            onDismiss()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
        AnimatedVisibility(
            visibleState = visible,
            enter = slideInVertically { it / 2 } + fadeIn(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Surface(
                shape = RoundedCornerShape(32.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
            ) {
                Column(Modifier.padding(20.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        VoiceOrb(size = 56.dp, level = voice.level, active = voice.phase != VoicePhase.WAITING)
                        Spacer(Modifier.size(16.dp))
                        TranscriptText(
                            statusText(voice.phase, voice.partial, turn?.input),
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 3,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    val reply = turn?.reply.orEmpty()
                    if (reply.isNotBlank()) {
                        Text(
                            reply,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (turn?.status == TurnStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                        )
                    }
                    confirmation?.let { request ->
                        Text(request.prompt, style = MaterialTheme.typography.bodyLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = { graph.confirmations.answer(request.id, false) }, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.confirm_no))
                            }
                            Button(onClick = { graph.confirmations.answer(request.id, true) }, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.confirm_yes))
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalIconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, stringResource(R.string.home_cancel)) }
                        Spacer(Modifier.size(8.dp))
                        FilledIconButton(onClick = onTalk, enabled = voice.phase == VoicePhase.WAITING || voice.phase == VoicePhase.SPEAKING) {
                            Icon(Icons.Rounded.Mic, stringResource(R.string.home_talk))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun statusText(phase: VoicePhase, partial: String, input: String?): String = when (phase) {
    VoicePhase.LISTENING -> partial.ifBlank { stringResource(R.string.home_listening) }
    VoicePhase.TRANSCRIBING -> partial.ifBlank { stringResource(R.string.home_transcribing) }
    VoicePhase.THINKING -> input ?: partial.ifBlank { stringResource(R.string.home_thinking) }
    else -> input ?: stringResource(R.string.assist_prompt)
}
