package dev.marionette.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.marionette.MarionetteApp
import dev.marionette.R
import dev.marionette.ui.components.SwitchRow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.marionette.llm.GatewayCheck
import dev.marionette.llm.OpenClawGateway
import dev.marionette.llm.ProbeResult
import dev.marionette.llm.Provider
import dev.marionette.ui.components.PuppetMark
import dev.marionette.ui.components.SettingsGroup
import dev.marionette.ui.components.SettingsRow
import java.net.URI

class BackendActions(
    val onBaseUrl: (String) -> Unit,
    val onApiKey: (String) -> Unit,
    val onModel: (String) -> Unit,
    val onLoadModels: () -> Unit,
    val onCheckGateway: () -> Unit,
    val onContinueConversation: (Boolean) -> Unit,
    val onPair: () -> Unit,
    val onUnpair: () -> Unit,
    val onTest: () -> Unit,
)

fun BackendViewModel.actions() = BackendActions(
    onBaseUrl = ::setBaseUrl,
    onApiKey = ::setApiKey,
    onModel = ::setModel,
    onLoadModels = ::loadModels,
    onCheckGateway = ::checkGateway,
    onContinueConversation = ::setContinueConversation,
    onPair = ::pair,
    onUnpair = ::unpair,
    onTest = ::test,
)

@Composable
fun ProviderPicker(selected: Provider, onSelect: (Provider) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val chosen = selected == Provider.OPENCLAW
        Surface(
            onClick = { onSelect(Provider.OPENCLAW) },
            shape = RoundedCornerShape(28.dp),
            color = if (chosen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
            border = if (chosen) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.Top) {
                PuppetMark(size = 32.dp)
                Spacer(Modifier.size(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(Provider.OPENCLAW.title, style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.size(8.dp))
                        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primary) {
                            Text(
                                stringResource(R.string.provider_recommended),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Text(stringResource(R.string.provider_openclaw_pitch), style = MaterialTheme.typography.bodyMedium)
                }
                RadioButton(selected = chosen, onClick = { onSelect(Provider.OPENCLAW) })
            }
        }
        val plain = Provider.entries.filter { it != Provider.OPENCLAW }
        Column {
            SettingsGroup(stringResource(R.string.provider_group_llm)) {
                plain.forEach { provider ->
                    item { i, n ->
                        SettingsRow(
                            i, n,
                            headline = provider.title,
                            supporting = stringResource(providerHint(provider)),
                            trailing = { RadioButton(selected = provider == selected, onClick = { onSelect(provider) }) },
                            onClick = { onSelect(provider) },
                        )
                    }
                }
            }
            Text(
                stringResource(R.string.provider_group_llm_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
        }
    }
}

@Composable
fun BackendSetup(form: BackendForm, actions: BackendActions, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (form.isGateway) GatewaySetup(form, actions) else ApiSetup(form, actions)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ApiSetup(form: BackendForm, actions: BackendActions) {
    Text(stringResource(providerHint(form.provider)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    form.provider.keyUrl?.let { url ->
        val uri = LocalUriHandler.current
        OutlinedButton(onClick = { uri.openUri(url) }) { Text(stringResource(R.string.backend_get_key)) }
    }
    if (form.provider.baseUrlEditable) {
        OutlinedTextField(
            value = form.baseUrl,
            onValueChange = actions.onBaseUrl,
            label = { Text(stringResource(R.string.backend_base_url)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    SecretInput(
        value = form.apiKey,
        onValue = actions.onApiKey,
        label = stringResource(if (form.provider.keyRequired) R.string.backend_api_key else R.string.backend_api_key_optional),
    )
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded && form.models.isNotEmpty(), onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = form.model,
            onValueChange = actions.onModel,
            label = { Text(stringResource(R.string.backend_model)) },
            singleLine = true,
            supportingText = form.modelsError?.let { { Text(it, maxLines = 2) } },
            trailingIcon = {
                if (form.loadingModels) {
                    LoadingIndicator(Modifier.size(24.dp))
                } else {
                    IconButton(onClick = {
                        actions.onLoadModels()
                        expanded = true
                    }) { Icon(Icons.Rounded.Refresh, stringResource(R.string.backend_load_models)) }
                }
            },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
        )
        ExposedDropdownMenu(expanded = expanded && form.models.isNotEmpty(), onDismissRequest = { expanded = false }) {
            form.models.take(200).forEach { model ->
                DropdownMenuItem(
                    text = { Text(model) },
                    onClick = {
                        actions.onModel(model)
                        expanded = false
                    },
                )
            }
        }
    }
    SettingsGroup(null) {
        item { i, n -> SendInstructionsRow(i, n) }
    }
    TestSection(form, actions)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun GatewaySetup(form: BackendForm, actions: BackendActions) {
    Text(stringResource(R.string.backend_hint_openclaw), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(
        value = form.baseUrl,
        onValueChange = actions.onBaseUrl,
        label = { Text(stringResource(R.string.gateway_address)) },
        placeholder = { Text(stringResource(R.string.gateway_address_placeholder)) },
        singleLine = true,
        supportingText = form.gatewayUrl.takeIf { it.isNotBlank() && it != form.baseUrl.trim() }?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { actions.onCheckGateway() }),
        modifier = Modifier.fillMaxWidth(),
    )
    if (form.showSecret) {
        SecretInput(
            value = form.apiKey,
            onValue = actions.onApiKey,
            label = stringResource(R.string.gateway_secret),
            supporting = stringResource(R.string.gateway_secret_help),
            imeAction = ImeAction.Go,
            onGo = actions.onCheckGateway,
        )
    }
    GatewayStatus(form.gateway, hostOf(form.gatewayUrl))
    if (!form.gatewayReady) {
        Button(
            onClick = actions.onCheckGateway,
            enabled = form.gatewayUrl.isNotBlank() && !form.checking,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (form.checking) {
                LoadingIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.gateway_connecting))
            } else {
                Text(stringResource(if (form.gateway == null) R.string.gateway_connect else R.string.gateway_check_again))
            }
        }
        return
    }
    SettingsGroup(stringResource(R.string.gateway_agent)) {
        form.models.forEach { id ->
            item { i, n ->
                SettingsRow(
                    i, n,
                    headline = OpenClawGateway.agentName(id) ?: stringResource(R.string.gateway_agent_default),
                    supporting = if (id == OpenClawGateway.DEFAULT_AGENT) stringResource(R.string.gateway_agent_default_body) else null,
                    trailing = { RadioButton(selected = id == form.model, onClick = { actions.onModel(id) }) },
                    onClick = { actions.onModel(id) },
                )
            }
        }
    }
    AgentSetupCard()
    SettingsGroup(null) {
        item { i, n -> SendContextRow(i, n) }
    }
    SettingsGroup(stringResource(R.string.conversation_mode)) {
        item { i, n ->
            SettingsRow(
                i, n,
                headline = stringResource(R.string.conversation_fresh),
                supporting = stringResource(R.string.conversation_fresh_body),
                trailing = { RadioButton(selected = !form.continueConversation, onClick = { actions.onContinueConversation(false) }) },
                onClick = { actions.onContinueConversation(false) },
            )
        }
        item { i, n ->
            SettingsRow(
                i, n,
                headline = stringResource(R.string.conversation_continuous),
                supporting = stringResource(R.string.conversation_continuous_body),
                trailing = { RadioButton(selected = form.continueConversation, onClick = { actions.onContinueConversation(true) }) },
                onClick = { actions.onContinueConversation(true) },
            )
        }
    }
    LiveProgressSection(form, actions)
    TestSection(form, actions)
}

@Composable
private fun GatewayStatus(check: GatewayCheck?, host: String) {
    when (check) {
        is GatewayCheck.Unreachable -> StatusCard(false, stringResource(R.string.gateway_unreachable_title), stringResource(R.string.gateway_unreachable_body, host))
        GatewayCheck.NotGateway -> StatusCard(false, stringResource(R.string.gateway_not_gateway_title), stringResource(R.string.gateway_not_gateway_body, host))
        GatewayCheck.NeedsSecret -> StatusCard(null, stringResource(R.string.gateway_needs_secret_title), stringResource(R.string.gateway_needs_secret_body))
        GatewayCheck.Rejected -> StatusCard(false, stringResource(R.string.gateway_rejected_title), stringResource(R.string.gateway_rejected_body))
        GatewayCheck.RateLimited -> StatusCard(false, stringResource(R.string.gateway_rate_limited_title), stringResource(R.string.gateway_rate_limited_body))
        is GatewayCheck.Failed -> StatusCard(false, stringResource(R.string.gateway_failed_title), check.message)
        GatewayCheck.EndpointDisabled -> {
            StatusCard(null, stringResource(R.string.gateway_disabled_title), stringResource(R.string.gateway_disabled_body))
            CommandBox(OpenClawGateway.ENABLE_COMMAND)
        }
        is GatewayCheck.Ready -> StatusCard(true, stringResource(R.string.gateway_connected, host), null)
        null -> Unit
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun LiveProgressSection(form: BackendForm, actions: BackendActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.live_progress), style = MaterialTheme.typography.titleMedium)
        when (val pairing = form.pairing) {
            PairState.Unpaired -> {
                Text(stringResource(R.string.live_progress_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = actions.onPair, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.live_progress_pair)) }
            }
            is PairState.Waiting -> {
                StatusCard(null, stringResource(R.string.live_progress_waiting_title), stringResource(R.string.live_progress_waiting_body))
                pairing.requestId?.let { CommandBox("openclaw devices approve $it") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LoadingIndicator(Modifier.size(24.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.live_progress_waiting), style = MaterialTheme.typography.bodyMedium)
                }
            }
            PairState.Paired -> {
                StatusCard(true, stringResource(R.string.live_progress_on), stringResource(R.string.live_progress_on_body))
                TextButton(onClick = actions.onUnpair) { Text(stringResource(R.string.live_progress_unpair)) }
            }
            is PairState.Blocked -> {
                GatewayStatus(pairing.check, hostOf(form.gatewayUrl))
                OutlinedButton(onClick = actions.onPair, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.live_progress_retry)) }
            }
            is PairState.Failed -> {
                StatusCard(false, stringResource(R.string.live_progress_failed), pairing.message)
                OutlinedButton(onClick = actions.onPair, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.live_progress_retry)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun TestSection(form: BackendForm, actions: BackendActions) {
    Button(onClick = actions.onTest, enabled = form.canTest && !form.testing, modifier = Modifier.fillMaxWidth()) {
        if (form.testing) {
            LoadingIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.backend_testing))
        } else {
            Text(stringResource(R.string.backend_test))
        }
    }
    AnimatedVisibility(form.probe != null) {
        form.probe?.let { ProbeCard(it) }
    }
}

@Composable
private fun SecretInput(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    supporting: String? = null,
    imeAction: ImeAction = ImeAction.Done,
    onGo: () -> Unit = {},
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        singleLine = true,
        supportingText = supporting?.let { { Text(it) } },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = imeAction),
        keyboardActions = KeyboardActions(onGo = { onGo() }),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null)
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun AgentSetupCard(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.agent_setup_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.agent_setup_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CommandBox(stringResource(R.string.agent_snippet), monospace = false)
    }
}

@Composable
fun SendContextRow(index: Int, count: Int) {
    val settings by MarionetteApp.graph.settings.settings.collectAsStateWithLifecycle()
    SwitchRow(
        index, count,
        headline = stringResource(R.string.send_context),
        supporting = stringResource(R.string.send_context_body),
        checked = settings.sendContext,
        onCheckedChange = { on -> updateSettings { it.copy(sendContext = on) } },
    )
}

@Composable
fun SendInstructionsRow(index: Int, count: Int) {
    val settings by MarionetteApp.graph.settings.settings.collectAsStateWithLifecycle()
    SwitchRow(
        index, count,
        headline = stringResource(R.string.instructions_send),
        supporting = stringResource(R.string.instructions_send_body),
        checked = settings.sendInstructions,
        onCheckedChange = { on -> updateSettings { it.copy(sendInstructions = on) } },
    )
}

@Composable
internal fun CommandBox(command: String, monospace: Boolean = true) {
    val context = LocalContext.current
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                command,
                style = if (monospace) MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("command", command))
            }) {
                Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.action_copy))
            }
        }
    }
}

@Composable
private fun ProbeCard(result: ProbeResult) {
    when (result) {
        is ProbeResult.Passed -> StatusCard(true, stringResource(R.string.backend_test_passed), stringResource(R.string.backend_test_passed_detail, result.latencyMillis / 1000.0))
        is ProbeResult.NoToolCall -> StatusCard(false, stringResource(R.string.backend_test_no_tools), stringResource(R.string.backend_test_no_tools_detail))
        is ProbeResult.Failed -> StatusCard(false, stringResource(R.string.backend_test_failed), result.message)
    }
}

@Composable
private fun StatusCard(ok: Boolean?, title: String, body: String?) {
    val success = Color(0xFF34A853)
    val container = when (ok) {
        true -> success.copy(alpha = 0.12f)
        false -> MaterialTheme.colorScheme.errorContainer
        null -> MaterialTheme.colorScheme.secondaryContainer
    }
    val tint = when (ok) {
        true -> success
        false -> MaterialTheme.colorScheme.onErrorContainer
        null -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    val icon = when (ok) {
        true -> Icons.Rounded.CheckCircle
        false -> Icons.Rounded.ErrorOutline
        null -> Icons.Rounded.Info
    }
    Surface(shape = RoundedCornerShape(20.dp), color = container, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = tint)
            Spacer(Modifier.size(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                body?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 8) }
            }
        }
    }
}

private fun hostOf(url: String): String = runCatching { URI(url).authority }.getOrNull() ?: url

private fun providerHint(provider: Provider): Int = when (provider) {
    Provider.OPENAI -> R.string.backend_hint_openai
    Provider.ANTHROPIC -> R.string.backend_hint_anthropic
    Provider.OPENROUTER -> R.string.backend_hint_openrouter
    Provider.GEMINI -> R.string.backend_hint_gemini
    Provider.OPENCLAW -> R.string.backend_hint_openclaw
    Provider.CUSTOM -> R.string.backend_hint_custom
}
