package dev.marionette.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.marionette.MarionetteApp
import dev.marionette.R
import dev.marionette.access.Access
import dev.marionette.ui.access.AccessList
import dev.marionette.ui.access.rememberAccessState
import dev.marionette.ui.components.SettingsGroup
import dev.marionette.ui.components.SettingsNote
import dev.marionette.ui.components.SettingsScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class LaunchableApp(val pkg: String, val label: String, val icon: ImageBitmap?)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ScreenControlScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings by MarionetteApp.graph.settings.settings.collectAsStateWithLifecycle()
    val access = rememberAccessState()
    var apps by remember { mutableStateOf<List<LaunchableApp>?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val initialBlocked = remember { settings.uiAutomationBlocklist }

    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { launchableApps(context) } }

    val visible = apps.orEmpty()
        .filter { query.isBlank() || it.label.contains(query, ignoreCase = true) || it.pkg.contains(query, ignoreCase = true) }
        .sortedWith(compareByDescending<LaunchableApp> { it.pkg in initialBlocked }.thenBy { it.label.lowercase() })

    SettingsScaffold(title = stringResource(R.string.settings_screen_control), onBack = onBack) {
        item { AccessList(null, listOf(Access.SCREEN_CONTROL), access) }
        item { SettingsNote(stringResource(R.string.screen_control_body)) }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.screen_control_blocklist_search)) },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (apps == null) {
            item { LoadingIndicator() }
        }
        item {
            SettingsGroup(stringResource(R.string.screen_control_apps)) {
                visible.forEach { app ->
                    item { i, n ->
                        val blocked = app.pkg in settings.uiAutomationBlocklist
                        val toggle = { on: Boolean ->
                            updateSettings { it.copy(uiAutomationBlocklist = if (on) it.uiAutomationBlocklist + app.pkg else it.uiAutomationBlocklist - app.pkg) }
                        }
                        SegmentedListItem(
                            onClick = { toggle(!blocked) },
                            shapes = ListItemDefaults.segmentedShapes(i, n),
                            colors = ListItemDefaults.segmentedColors(),
                            leadingContent = { app.icon?.let { Image(it, null, Modifier.size(40.dp)) } },
                            supportingContent = { Text(stringResource(if (blocked) R.string.screen_control_blocked else R.string.screen_control_allowed)) },
                            trailingContent = { Switch(checked = blocked, onCheckedChange = toggle) },
                        ) {
                            Text(app.label)
                        }
                    }
                }
            }
        }
    }
}

private fun launchableApps(context: Context): List<LaunchableApp> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .distinctBy { it.activityInfo.packageName }
        .filter { it.activityInfo.packageName != context.packageName }
        .map { info ->
            LaunchableApp(
                pkg = info.activityInfo.packageName,
                label = info.loadLabel(pm).toString(),
                icon = runCatching { info.loadIcon(pm).toBitmap(96, 96).asImageBitmap() }.getOrNull(),
            )
        }
}
