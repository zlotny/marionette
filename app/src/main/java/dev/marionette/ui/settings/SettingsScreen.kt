package dev.marionette.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.History
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.marionette.MarionetteApp
import dev.marionette.R
import dev.marionette.ui.ActivityRoute
import dev.marionette.ui.components.SettingsGroup
import dev.marionette.ui.components.SettingsIcon
import dev.marionette.ui.components.SettingsRow
import dev.marionette.ui.components.SettingsScaffold

@Composable
fun SettingsScreen(onBack: () -> Unit, onOpen: (Any) -> Unit) {
    val settings by MarionetteApp.graph.settings.settings.collectAsStateWithLifecycle()
    SettingsScaffold(title = stringResource(R.string.settings_title), onBack = onBack) {
        item {
            SettingsGroup(stringResource(R.string.settings_group_assistant)) {
                item { i, n ->
                    SettingsRow(
                        i, n,
                        headline = stringResource(R.string.settings_backend),
                        supporting = "${settings.provider.title} · ${settings.model}",
                        leading = { SettingsIcon(Icons.Rounded.Cloud) },
                        onClick = { onOpen(SettingsNavigation.Backend) },
                    )
                }
            }
        }
        item {
            SettingsGroup(stringResource(R.string.settings_group_history)) {
                item { i, n ->
                    SettingsRow(
                        i, n,
                        headline = stringResource(R.string.activity_title),
                        supporting = stringResource(R.string.settings_activity_summary),
                        leading = { SettingsIcon(Icons.Rounded.History) },
                        onClick = { onOpen(ActivityRoute) },
                    )
                }
            }
        }
    }
}
