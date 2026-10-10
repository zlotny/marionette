package dev.marionette.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.marionette.MarionetteApp
import dev.marionette.R
import dev.marionette.llm.Provider
import dev.marionette.settings.AppSettings
import dev.marionette.settings.Credential
import dev.marionette.ui.components.ChoiceRow
import dev.marionette.ui.components.PuppetMark
import dev.marionette.ui.components.SettingsGroup
import dev.marionette.ui.components.SettingsRow
import dev.marionette.ui.components.SettingsScaffold
import java.net.URI

@Composable
fun BackendScreen(onBack: () -> Unit, viewModel: BackendViewModel = viewModel()) {
    val settings by MarionetteApp.graph.settings.settings.collectAsStateWithLifecycle()
    val form by viewModel.form.collectAsStateWithLifecycle()
    val configured = settings.isBackendConfigured
    var editing by rememberSaveable { mutableStateOf(!configured) }

    LaunchedEffect(form.saved) {
        if (form.saved && configured) editing = false
    }
    BackHandler(enabled = editing && configured) {
        viewModel.discard()
        editing = false
    }

    SettingsScaffold(
        title = stringResource(R.string.settings_backend),
        onBack = {
            if (editing && configured) {
                viewModel.discard()
                editing = false
            } else {
                onBack()
            }
        },
        actions = {
            if (editing) {
                TextButton(onClick = viewModel::save, enabled = form.canTest && !form.saved) {
                    Text(stringResource(R.string.action_save))
                }
            }
        },
    ) {
        if (editing) {
            item { ProviderPicker(form.provider, viewModel::selectProvider) }
            item {
                Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                    BackendSetup(form, viewModel.actions(), Modifier.fillMaxWidth().padding(20.dp))
                }
            }
        } else {
            item { BackendSummaryCard(settings, onChange = { editing = true }) }
            item {
                SettingsGroup(stringResource(R.string.backend_details)) {
                    if (settings.provider.baseUrlEditable) {
                        item { i, n ->
                            SettingsRow(
                                i, n,
                                headline = stringResource(if (settings.provider == Provider.OPENCLAW) R.string.gateway_address else R.string.backend_base_url),
                                supporting = settings.baseUrl,
                            )
                        }
                    }
                    item { i, n ->
                        SettingsRow(
                            i, n,
                            headline = stringResource(if (settings.provider.isAgent) R.string.gateway_agent else R.string.backend_model),
                            supporting = settings.model,
                        )
                    }
                    item { i, n ->
                        CredentialRow(
                            i, n, Credential.of(settings.provider),
                            headline = stringResource(if (settings.provider == Provider.OPENCLAW) R.string.gateway_secret else R.string.backend_api_key),
                        )
                    }
                    if (settings.provider.isAgent) {
                        item { i, n ->
                            ChoiceRow(
                                i, n,
                                headline = stringResource(R.string.conversation_mode),
                                options = listOf(
                                    false to stringResource(R.string.conversation_fresh),
                                    true to stringResource(R.string.conversation_continuous),
                                ),
                                selected = settings.continueConversation,
                                onSelect = { on ->
                                    updateSettings { it.copy(continueConversation = on) }
                                    viewModel.setContinueConversation(on)
                                },
                            )
                        }
                    }
                }
            }
            if (settings.provider == Provider.OPENCLAW) {
                item {
                    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                        Column(Modifier.fillMaxWidth().padding(20.dp)) { LiveProgressSection(form, viewModel.actions()) }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { TestSection(form, viewModel.actions()) }
            }
        }
    }
}

@Composable
private fun BackendSummaryCard(settings: AppSettings, onChange: () -> Unit) {
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (settings.provider == Provider.OPENCLAW) {
                    PuppetMark(size = 36.dp)
                } else {
                    Icon(Icons.Rounded.Cloud, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.size(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(settings.provider.title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(
                        listOfNotNull(settings.model, hostOf(settings.baseUrl).takeIf { settings.provider.baseUrlEditable }).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Button(onClick = onChange, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Edit, null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.backend_change))
            }
        }
    }
}

private fun hostOf(url: String): String = runCatching { URI(url).authority }.getOrNull() ?: url
