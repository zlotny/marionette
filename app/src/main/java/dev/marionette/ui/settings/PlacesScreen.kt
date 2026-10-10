package dev.marionette.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Place
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.marionette.MarionetteApp
import dev.marionette.R
import dev.marionette.settings.Credential
import dev.marionette.ui.components.SettingsGroup
import dev.marionette.ui.components.SettingsIcon
import dev.marionette.ui.components.SettingsNote
import dev.marionette.ui.components.SettingsScaffold
import dev.marionette.ui.components.SwitchRow

@Composable
fun PlacesScreen(onBack: () -> Unit) {
    val settings by MarionetteApp.graph.settings.settings.collectAsStateWithLifecycle()
    val enabled = PLACES_TOOL !in settings.disabledTools

    SettingsScaffold(title = stringResource(R.string.places_title), onBack = onBack) {
        item { SettingsNote(stringResource(R.string.places_intro)) }
        item {
            SettingsGroup(null) {
                item { i, n ->
                    SwitchRow(
                        i, n,
                        headline = stringResource(R.string.places_enabled),
                        supporting = stringResource(R.string.places_enabled_body),
                        checked = enabled,
                        leading = { SettingsIcon(Icons.Rounded.Place) },
                        onCheckedChange = { on ->
                            updateSettings { it.copy(disabledTools = if (on) it.disabledTools - PLACES_TOOL else it.disabledTools + PLACES_TOOL) }
                        },
                    )
                }
            }
        }
        item {
            SettingsGroup(stringResource(R.string.places_group_provider)) {
                item { i, n -> CredentialRow(i, n, Credential.GOOGLE_PLACES, headline = stringResource(R.string.tools_places_key)) }
            }
        }
        item {
            SettingsNote(stringResource(R.string.tools_places_key_body))
        }
    }
}
