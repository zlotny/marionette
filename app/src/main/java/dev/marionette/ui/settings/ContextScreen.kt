package dev.marionette.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.marionette.MarionetteApp
import dev.marionette.R
import dev.marionette.ui.agentName
import dev.marionette.agent.ContextBlock
import dev.marionette.settings.ContextField
import dev.marionette.ui.components.SettingsGroup
import dev.marionette.ui.components.SettingsIcon
import dev.marionette.ui.components.SettingsNote
import dev.marionette.ui.components.SettingsScaffold
import dev.marionette.ui.components.SwitchRow

private data class FieldPresentation(val icon: ImageVector, val title: Int, val body: Int)

private val ContextField.presentation: FieldPresentation
    get() = when (this) {
        ContextField.TIME -> FieldPresentation(Icons.Rounded.Schedule, R.string.context_time, R.string.context_time_body)
        ContextField.LOCATION -> FieldPresentation(Icons.Rounded.LocationOn, R.string.context_location, R.string.context_location_body)
        ContextField.MOTION -> FieldPresentation(Icons.Rounded.Speed, R.string.context_motion, R.string.context_motion_body)
        ContextField.BATTERY -> FieldPresentation(Icons.Rounded.BatteryStd, R.string.context_battery, R.string.context_battery_body)
        ContextField.AUDIO -> FieldPresentation(Icons.Rounded.Headphones, R.string.context_audio, R.string.context_audio_body)
        ContextField.SCREEN -> FieldPresentation(Icons.Rounded.PhoneAndroid, R.string.context_screen, R.string.context_screen_body)
    }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ContextScreen(onBack: () -> Unit) {
    val graph = MarionetteApp.graph
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    var preview by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(settings.contextFields, settings.language) {
        preview = runCatching { ContextBlock.render(graph.device.snapshot(settings.contextFields, settings.language), settings.contextFields) }
            .getOrDefault("")
    }

    SettingsScaffold(title = stringResource(R.string.settings_context), onBack = onBack) {
        item { SettingsNote(stringResource(R.string.context_intro, agentName())) }
        item { SettingsGroup(null) { item { i, n -> SendContextRow(i, n) } } }
        item {
            SettingsGroup(null) {
                ContextField.entries.forEach { field ->
                    item { i, n ->
                        val p = field.presentation
                        SwitchRow(
                            i, n,
                            headline = stringResource(p.title),
                            supporting = stringResource(p.body),
                            checked = field in settings.contextFields,
                            leading = { SettingsIcon(p.icon) },
                            onCheckedChange = { on ->
                                updateSettings { it.copy(contextFields = if (on) it.contextFields + field else it.contextFields - field) }
                            },
                        )
                    }
                }
            }
        }
        item {
            Column {
                Text(
                    stringResource(R.string.context_preview, agentName()),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, bottom = 8.dp, top = 8.dp),
                )
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    val text = preview
                    if (text == null) {
                        LoadingIndicator(Modifier.padding(16.dp))
                    } else {
                        Text(
                            text,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}
