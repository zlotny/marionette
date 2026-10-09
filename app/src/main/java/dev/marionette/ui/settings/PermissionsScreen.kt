package dev.marionette.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.marionette.MarionetteApp
import dev.marionette.R
import dev.marionette.access.Access
import dev.marionette.ui.access.AccessList
import dev.marionette.ui.access.rememberAccessState
import dev.marionette.ui.components.SettingsScaffold

@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings by MarionetteApp.graph.settings.settings.collectAsStateWithLifecycle()
    val state = rememberAccessState()
    val accesses = Access.relevant(context, settings)
    SettingsScaffold(title = stringResource(R.string.settings_permissions), onBack = onBack) {
        item {
            Text(
                stringResource(R.string.permissions_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        item { AccessList(stringResource(R.string.access_group_essential), accesses.filter { it.essential }, state) }
        item { AccessList(stringResource(R.string.access_group_optional), accesses.filterNot { it.essential }, state) }
    }
}
