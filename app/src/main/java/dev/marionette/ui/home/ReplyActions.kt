package dev.marionette.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.marionette.MarionetteApp
import dev.marionette.R
import dev.marionette.agent.Turn
import dev.marionette.core.arr
import dev.marionette.core.int
import dev.marionette.core.obj
import dev.marionette.core.parseObject
import dev.marionette.core.string
import dev.marionette.ui.ToolPresentations
import dev.marionette.voice.VoiceBus
import dev.marionette.voice.VoicePhase
import dev.marionette.voice.VoiceService
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive

@Composable
fun ReplyActions(turn: Turn, last: Boolean) {
    val context = LocalContext.current
    val voice by VoiceBus.status.collectAsStateWithLifecycle()
    var reading by remember { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf(false) }
    val speaking = reading && voice.phase in setOf(VoicePhase.SPEAKING, VoicePhase.LISTENING, VoicePhase.TRANSCRIBING, VoicePhase.THINKING)
    LaunchedEffect(voice.phase) {
        if (reading && voice.phase !in setOf(VoicePhase.SPEAKING, VoicePhase.LISTENING, VoicePhase.TRANSCRIBING, VoicePhase.THINKING)) reading = false
    }
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth().layout { measurable, constraints ->
            val bleed = ACTION_BLEED.roundToPx()
            val width = constraints.maxWidth + 2 * bleed
            val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
            layout(constraints.maxWidth, placeable.height) { placeable.place(-bleed, 0) }
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            copy(context, turn.reply)
        }) { Icon(Icons.Rounded.ContentCopy, stringResource(R.string.reply_copy), Modifier.size(20.dp), tint = tint) }
        IconButton(onClick = { share(context, turn.reply) }) { Icon(Icons.Rounded.Share, stringResource(R.string.reply_share), Modifier.size(20.dp), tint = tint) }
        IconButton(onClick = { details = true }) { Icon(Icons.Rounded.MoreHoriz, stringResource(R.string.reply_details), Modifier.size(20.dp), tint = tint) }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = {
            if (speaking) {
                VoiceService.send(context, VoiceService.ACTION_CANCEL)
                reading = false
            } else {
                reading = true
                VoiceService.readAloud(context, turn.reply, followUp = last)
            }
        }) {
            Icon(
                if (speaking) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.VolumeUp,
                stringResource(if (speaking) R.string.action_stop else R.string.reply_read_aloud),
                Modifier.size(20.dp),
                tint = if (speaking) MaterialTheme.colorScheme.primary else tint,
            )
        }
    }
    if (details) TurnDetailsSheet(turn, onDismiss = { details = false })
}

internal fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(text.take(40), text))
    Toast.makeText(context, R.string.reply_copied, Toast.LENGTH_SHORT).show()
}

private fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private data class TurnDetails(
    val backend: String,
    val totalMillis: Long?,
    val rounds: Int,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val tools: List<Pair<String, Long?>>,
    val context: String,
    val error: String?,
)

private fun readDetails(trace: JsonObject): TurnDetails {
    val rounds = trace["rounds"].arr().orEmpty().mapNotNull { it.obj() }
    val input = rounds.mapNotNull { it.int("input_tokens") }
    val output = rounds.mapNotNull { it.int("output_tokens") }
    return TurnDetails(
        backend = trace.string("backend").orEmpty(),
        totalMillis = trace["total_millis"]?.jsonPrimitive?.longOrNull,
        rounds = rounds.size,
        inputTokens = input.takeIf { it.isNotEmpty() }?.sum(),
        outputTokens = output.takeIf { it.isNotEmpty() }?.sum(),
        tools = trace["tools"].arr().orEmpty().mapNotNull { it.obj() }.map { it.string("name").orEmpty() to it["millis"]?.jsonPrimitive?.longOrNull },
        context = trace.string("system").orEmpty(),
        error = trace.string("error"),
    )
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TurnDetailsSheet(turn: Turn, onDismiss: () -> Unit) {
    val loaded by produceState<Result<TurnDetails?>?>(null, turn.id) {
        value = runCatching {
            withContext(Dispatchers.IO) {
                File(MarionetteApp.graph.traces.directory, "turn-${turn.id}.json").takeIf { it.exists() }?.let { readDetails(parseObject(it.readText())) }
            }
        }
    }
    var showContext by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.reply_details), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            val result = loaded
            if (result == null) {
                Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
                return@Column
            }
            val d = result.getOrNull()
            if (d == null) {
                Text(stringResource(R.string.reply_details_missing), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            DetailLine(stringResource(R.string.reply_details_model), d.backend.substringAfter(' ').ifBlank { d.backend })
            d.totalMillis?.let { DetailLine(stringResource(R.string.reply_details_time), stringResource(R.string.reply_details_seconds, it / 1000.0)) }
            DetailLine(stringResource(R.string.reply_details_rounds), d.rounds.toString())
            if (d.inputTokens != null || d.outputTokens != null) {
                DetailLine(stringResource(R.string.reply_details_tokens), stringResource(R.string.reply_details_tokens_value, d.inputTokens ?: 0, d.outputTokens ?: 0))
            }
            d.error?.let { DetailLine(stringResource(R.string.reply_details_error), it) }
            if (d.tools.isNotEmpty()) {
                HorizontalDivider()
                Text(stringResource(R.string.reply_details_tools), style = MaterialTheme.typography.titleSmall)
                d.tools.forEach { (name, millis) ->
                    val presentation = ToolPresentations.of(name)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(presentation.icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.size(12.dp))
                        Text(stringResource(presentation.label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        millis?.let { Text(stringResource(R.string.reply_details_seconds, it / 1000.0), style = MaterialTheme.typography.labelMedium) }
                    }
                }
            }
            if (d.context.isNotBlank()) {
                HorizontalDivider()
                TextButton(onClick = { showContext = !showContext }) {
                    Text(stringResource(if (showContext) R.string.reply_details_hide_context else R.string.reply_details_show_context))
                }
                AnimatedVisibility(
                    showContext,
                    enter = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeIn(),
                    exit = shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeOut(),
                ) {
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                        SelectionContainer {
                            Text(
                                d.context,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr),
                                modifier = Modifier.padding(12.dp).horizontalScroll(rememberScrollState()),
                            )
                        }
                    }
                }
            }
        }
    }
}

private val ACTION_BLEED = 12.dp

@Composable
private fun DetailLine(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.4f))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.6f))
    }
}
