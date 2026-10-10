package dev.marionette.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.marionette.MarionetteApp
import dev.marionette.R
import dev.marionette.ui.agentName
import dev.marionette.access.Access
import dev.marionette.access.AccessKind
import dev.marionette.tools.Tool
import dev.marionette.tools.ToolCategory
import dev.marionette.ui.ToolPresentations
import dev.marionette.ui.access.AccessState
import dev.marionette.ui.access.presentation
import dev.marionette.ui.access.rememberAccessState
import dev.marionette.ui.components.CardRow
import dev.marionette.ui.components.NestedDivider
import dev.marionette.ui.components.NestedRow
import dev.marionette.ui.components.SettingsCard
import dev.marionette.ui.components.SettingsGroup
import dev.marionette.ui.components.SettingsIcon
import dev.marionette.ui.components.SettingsNote
import dev.marionette.ui.components.SettingsScaffold

@StringRes
private fun ToolCategory.title(): Int = when (this) {
    ToolCategory.NAVIGATION -> R.string.category_navigation
    ToolCategory.PLACES -> R.string.category_places
    ToolCategory.TIME -> R.string.category_time
    ToolCategory.COMMUNICATION -> R.string.category_communication
    ToolCategory.NOTIFICATIONS -> R.string.category_notifications
    ToolCategory.MEDIA -> R.string.category_media
    ToolCategory.CALENDAR -> R.string.category_calendar
    ToolCategory.DEVICE -> R.string.category_device
    ToolCategory.APPS -> R.string.category_apps
    ToolCategory.SCREEN -> R.string.category_screen
}

@Composable
fun ToolsScreen(onBack: () -> Unit, onOpenPlaces: () -> Unit) {
    val graph = MarionetteApp.graph
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val access = rememberAccessState()
    val context = LocalContext.current
    val relevant = Access.relevant(context, settings)
    val byCategory = graph.registry.tools.groupBy { it.category }

    SettingsScaffold(title = stringResource(R.string.settings_tools), onBack = onBack) {
        item { SettingsNote(stringResource(R.string.tools_intro, agentName())) }
        ToolCategory.entries.forEach { category ->
            val tools = byCategory[category].orEmpty()
            if (tools.isEmpty()) return@forEach
            item(key = category.name) {
                SettingsGroup(stringResource(category.title())) {
                    tools.forEach { tool ->
                        item { i, n ->
                            ToolCard(
                                i, n, tool,
                                enabled = tool.name !in settings.disabledTools,
                                askFirst = tool.name !in settings.confirmationDisabled,
                                accesses = relevant.filter { tool.name in it.tools },
                                access = access,
                                placesProvider = if (settings.googlePlacesKey.isBlank()) PLACES_OSM else PLACES_GOOGLE,
                                onOpenPlaces = onOpenPlaces,
                            )
                        }
                    }
                }
            }
        }
    }
}

const val PLACES_TOOL = "find_places"
private const val PLACES_OSM = "OpenStreetMap"
private const val PLACES_GOOGLE = "Google Places"

@Composable
private fun ToolCard(
    index: Int,
    count: Int,
    tool: Tool,
    enabled: Boolean,
    askFirst: Boolean,
    accesses: List<Access>,
    access: AccessState,
    placesProvider: String,
    onOpenPlaces: () -> Unit,
) {
    val presentation = ToolPresentations.of(tool.name)
    val missing = accesses.firstOrNull { !access.isGranted(it) }
    val toggle = { on: Boolean ->
        updateSettings { it.copy(disabledTools = if (on) it.disabledTools - tool.name else it.disabledTools + tool.name) }
    }
    SettingsCard(index, count) {
        CardRow(
            headline = stringResource(presentation.label),
            supporting = if (missing != null && enabled) {
                stringResource(R.string.tools_needs, stringResource(missing.presentation.title))
            } else {
                stringResource(presentation.description)
            },
            leading = {
                if (enabled) {
                    SettingsIcon(presentation.icon)
                } else {
                    SettingsIcon(presentation.icon, MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (missing != null && enabled) {
                        TextButton(onClick = { access.request(missing) }) {
                            Text(stringResource(if (missing.kind == AccessKind.RUNTIME) R.string.access_allow else R.string.access_open))
                        }
                    }
                    Switch(checked = enabled, onCheckedChange = toggle)
                }
            },
            onClick = { toggle(!enabled) },
        )
        if (tool.requiresConfirmation) {
            NestedDivider()
            NestedRow(
                headline = stringResource(R.string.tools_ask_first),
                supporting = stringResource(R.string.tools_ask_first_body),
                icon = Icons.Rounded.VerifiedUser,
                enabled = enabled,
                trailing = {
                    Switch(
                        checked = askFirst,
                        enabled = enabled,
                        onCheckedChange = { ask -> setAskFirst(tool.name, ask) },
                    )
                },
                onClick = { setAskFirst(tool.name, !askFirst) },
            )
        }
        if (tool.name == PLACES_TOOL) {
            NestedDivider()
            NestedRow(
                headline = stringResource(R.string.tools_places_settings),
                supporting = placesProvider,
                icon = Icons.Rounded.Tune,
                trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                onClick = onOpenPlaces,
            )
        }
    }
}

private fun setAskFirst(tool: String, ask: Boolean) = updateSettings {
    it.copy(confirmationDisabled = if (ask) it.confirmationDisabled - tool else it.confirmationDisabled + tool)
}
