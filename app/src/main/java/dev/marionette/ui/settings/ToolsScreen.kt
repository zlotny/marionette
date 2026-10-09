package dev.marionette.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.marionette.MarionetteApp
import dev.marionette.R
import dev.marionette.access.Access
import dev.marionette.access.AccessKind
import dev.marionette.tools.Tool
import dev.marionette.tools.ToolCategory
import dev.marionette.ui.ToolPresentations
import dev.marionette.ui.access.AccessState
import dev.marionette.ui.access.presentation
import dev.marionette.ui.access.rememberAccessState
import dev.marionette.ui.components.SecretField
import dev.marionette.ui.components.SettingsGroup
import dev.marionette.ui.components.SettingsIcon
import dev.marionette.ui.components.SettingsNote
import dev.marionette.ui.components.SettingsRow
import dev.marionette.ui.components.SettingsScaffold
import dev.marionette.ui.components.SwitchRow

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
fun ToolsScreen(onBack: () -> Unit) {
    val graph = MarionetteApp.graph
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val access = rememberAccessState()
    val context = LocalContext.current
    val relevant = Access.relevant(context, settings)
    val byCategory = graph.registry.tools.groupBy { it.category }

    SettingsScaffold(title = stringResource(R.string.settings_tools), onBack = onBack) {
        item { SettingsNote(stringResource(R.string.tools_intro)) }
        ToolCategory.entries.forEach { category ->
            val tools = byCategory[category].orEmpty()
            if (tools.isEmpty()) return@forEach
            item(key = category.name) {
                SettingsGroup(stringResource(category.title())) {
                    tools.forEach { tool ->
                        val enabled = tool.name !in settings.disabledTools
                        val blocking = relevant.filter { tool.name in it.tools }
                        item { i, n -> ToolRow(i, n, tool, enabled, blocking, access) }
                        if (tool.requiresConfirmation) {
                            item { i, n ->
                                SwitchRow(
                                    i, n,
                                    headline = stringResource(R.string.tools_ask_first),
                                    supporting = stringResource(R.string.tools_ask_first_body),
                                    checked = tool.name !in settings.confirmationDisabled,
                                    enabled = enabled,
                                    leading = { SettingsIcon(Icons.Rounded.VerifiedUser, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer) },
                                    onCheckedChange = { ask ->
                                        updateSettings {
                                            it.copy(confirmationDisabled = if (ask) it.confirmationDisabled - tool.name else it.confirmationDisabled + tool.name)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
            if (category == ToolCategory.PLACES) {
                item(key = "places_key") {
                    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                        SecretField(
                            label = stringResource(R.string.tools_places_key),
                            saved = settings.googlePlacesKey,
                            onSave = { key -> updateSettings { it.copy(googlePlacesKey = key) } },
                            supporting = stringResource(R.string.tools_places_key_body),
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolRow(index: Int, count: Int, tool: Tool, enabled: Boolean, accesses: List<Access>, access: AccessState) {
    val presentation = ToolPresentations.of(tool.name)
    val missing = accesses.firstOrNull { !access.isGranted(it) }
    val toggle = { on: Boolean ->
        updateSettings { it.copy(disabledTools = if (on) it.disabledTools - tool.name else it.disabledTools + tool.name) }
    }
    SettingsRow(
        index, count,
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
}
