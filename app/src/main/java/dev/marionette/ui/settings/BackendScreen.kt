package dev.marionette.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.marionette.R
import dev.marionette.ui.components.SettingsScaffold

@Composable
fun BackendScreen(onBack: () -> Unit, viewModel: BackendViewModel = viewModel()) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    SettingsScaffold(
        title = stringResource(R.string.settings_backend),
        onBack = onBack,
        actions = {
            TextButton(onClick = viewModel::save, enabled = form.canTest && !form.saved) {
                Text(stringResource(R.string.action_save))
            }
        },
    ) {
        item {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                BackendFormContent(
                    form = form,
                    onProvider = viewModel::selectProvider,
                    onBaseUrl = viewModel::setBaseUrl,
                    onApiKey = viewModel::setApiKey,
                    onModel = viewModel::setModel,
                    onLoadModels = viewModel::loadModels,
                    onTest = viewModel::test,
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                )
            }
        }
    }
}
