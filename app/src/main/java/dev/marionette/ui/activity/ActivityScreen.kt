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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.marionette.MarionetteApp
import dev.marionette.R
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
                        stringResource(R.string.activity_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                    entries.forEachIndexed { index, entry -> EntryRow(entry, index, entries.size) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EntryRow(entry: ActionEntry, index: Int, count: Int) {
    var expanded by remember { mutableStateOf(false) }
    val presentation = ToolPresentations.of(entry.tool)
    SegmentedListItem(
        onClick = { expanded = !expanded },
        shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = ListItemDefaults.segmentedColors(),
        leadingContent = {
            if (entry.ok) {
                SettingsIcon(presentation.icon)
            } else {
                SettingsIcon(presentation.icon, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
            }
        },
        supportingContent = {
            Text(
                if (expanded) "${entry.arguments}\n\n${entry.result}" else entry.arguments,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Text(
                DateUtils.getRelativeTimeSpanString(entry.timestamp).toString(),
                style = MaterialTheme.typography.labelSmall,
            )
        },
    ) {
        Text(stringResource(presentation.label))
    }
}
