package dev.marionette.ui.activity

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Surface
import dev.marionette.core.parseObject
import kotlinx.serialization.json.JsonObject
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.marionette.MarionetteApp
import dev.marionette.R
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.draw.rotate
import dev.marionette.ui.agentName
import dev.marionette.agent.ActionEntry
import dev.marionette.ui.ToolPresentations
import dev.marionette.ui.components.SettingsIcon
import dev.marionette.ui.components.SettingsScaffold
import kotlinx.coroutines.launch

@Composable
fun ActivityScreen(onBack: () -> Unit) {
    val log = MarionetteApp.graph.actionLog
    val entries by log.entries.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    SettingsScaffold(
        title = stringResource(R.string.activity_title),
        onBack = onBack,
        spacing = ListItemDefaults.SegmentedGap,
        actions = {
            if (entries.isNotEmpty()) {
                IconButton(onClick = { scope.launch { log.clear() } }) {
                    Icon(Icons.Rounded.DeleteSweep, stringResource(R.string.activity_clear))
                }
            }
        },
    ) {
        if (entries.isEmpty()) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(top = 96.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Rounded.History, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
                    Text(
                        stringResource(R.string.activity_empty, agentName()),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            itemsIndexed(entries, key = { _, entry -> "${entry.timestamp}-${entry.tool}" }, contentType = { _, _ -> "entry" }) { index, entry ->
                Box(Modifier.animateItem()) { EntryRow(entry, index, entries.size) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EntryRow(entry: ActionEntry, index: Int, count: Int) {
    var expanded by rememberSaveable(entry.timestamp, entry.tool) { mutableStateOf(false) }
    val presentation = ToolPresentations.of(entry.tool)
    val args = remember(entry.arguments) { parseObject(entry.arguments) }
    val result = remember(entry.result) { parseObject(entry.result) }
    val outcome = remember(result) { ActionText.outcome(result) }
    val summary = remember(entry.tool, args) { MarionetteApp.graph.registry[entry.tool]?.summary(args)?.takeIf { it.isNotBlank() } }
    val label = stringResource(presentation.label)
    val failed = outcome != Outcome.DONE && outcome != Outcome.WAITING_UNLOCK
    SegmentedListItem(
        onClick = { expanded = !expanded },
        shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = ListItemDefaults.segmentedColors(),
        verticalAlignment = if (expanded) Alignment.Top else Alignment.CenterVertically,
        leadingContent = {
            if (failed) {
                SettingsIcon(presentation.icon, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
            } else {
                SettingsIcon(presentation.icon)
            }
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(outcome.text),
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AnimatedVisibility(
                    expanded,
                    enter = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
                    exit = shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
                ) { Details(entry, args, result) }
            }
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    DateUtils.getRelativeTimeSpanString(entry.timestamp).toString(),
                    style = MaterialTheme.typography.labelSmall,
                )
                val turn by animateFloatAsState(if (expanded) 180f else 0f, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "chevron")
                Icon(
                    Icons.Rounded.ExpandMore,
                    stringResource(R.string.activity_details),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(turn),
                )
            }
        },
    ) {
        Text(
            if (summary == null) label else stringResource(presentation.activity, summary),
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Details(entry: ActionEntry, args: JsonObject, result: JsonObject) {
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DetailBlock(stringResource(R.string.activity_request, agentName()), ActionText.readable(args))
        DetailBlock(stringResource(R.string.activity_result), ActionText.readable(result, setOf("ok")))
        Text(
            stringResource(R.string.activity_duration, stringResource(R.string.reply_details_seconds, entry.durationMillis / 1000.0)),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DetailBlock(title: String, body: String) {
    if (body.isBlank()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.fillMaxWidth()) {
            Text(body, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
        }
    }
}

private val Outcome.text: Int
    get() = when (this) {
        Outcome.DONE -> R.string.activity_outcome_done
        Outcome.WAITING_UNLOCK -> R.string.activity_outcome_waiting_unlock
        Outcome.DECLINED -> R.string.activity_outcome_declined
        Outcome.PERMISSION -> R.string.activity_outcome_permission
        Outcome.LOCKED -> R.string.activity_outcome_locked
        Outcome.NOT_FOUND -> R.string.activity_outcome_not_found
        Outcome.NOT_ALLOWED -> R.string.activity_outcome_not_allowed
        Outcome.TIMEOUT -> R.string.activity_outcome_timeout
        Outcome.NO_APP -> R.string.activity_outcome_no_app
        Outcome.FAILED -> R.string.activity_outcome_failed
    }
