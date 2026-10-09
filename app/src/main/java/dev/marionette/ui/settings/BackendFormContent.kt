package dev.marionette.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.marionette.R
import dev.marionette.llm.ProbeResult
import dev.marionette.llm.Provider

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BackendFormContent(
    form: BackendForm,
    onProvider: (Provider) -> Unit,
    onBaseUrl: (String) -> Unit,
    onApiKey: (String) -> Unit,
    onModel: (String) -> Unit,
    onLoadModels: () -> Unit,
    onTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Provider.entries.forEach { provider ->
                FilterChip(
                    selected = provider == form.provider,
                    onClick = { onProvider(provider) },
                    label = { Text(provider.title) },
                    leadingIcon = if (provider == form.provider) {
                        { Icon(Icons.Rounded.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }
                    } else {
                        null
                    },
                )
            }
        }
        Text(
            stringResource(providerHint(form.provider)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (form.provider.baseUrlEditable) {
            OutlinedTextField(
                value = form.baseUrl,
                onValueChange = onBaseUrl,
                label = { Text(stringResource(R.string.backend_base_url)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        var showKey by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = form.apiKey,
            onValueChange = onApiKey,
            label = {
                Text(stringResource(if (form.provider == Provider.OPENCLAW) R.string.backend_gateway_token else if (form.provider.keyRequired) R.string.backend_api_key else R.string.backend_api_key_optional))
            },
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { showKey = !showKey }) {
                    Icon(if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = expanded && form.models.isNotEmpty(), onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = form.model,
                onValueChange = onModel,
                label = { Text(stringResource(R.string.backend_model)) },
                singleLine = true,
                supportingText = form.modelsError?.let { { Text(it, maxLines = 2) } },
                trailingIcon = {
                    if (form.loadingModels) {
                        LoadingIndicator(Modifier.size(24.dp))
                    } else {
                        IconButton(onClick = {
                            onLoadModels()
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
                            onModel(model)
                            expanded = false
                        },
                    )
                }
            }
        }
        Button(onClick = onTest, enabled = form.canTest && !form.testing, modifier = Modifier.fillMaxWidth()) {
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
}

@Composable
private fun ProbeCard(result: ProbeResult) {
    val (ok, title, body) = when (result) {
        is ProbeResult.Passed -> Triple(true, stringResource(R.string.backend_test_passed), stringResource(R.string.backend_test_passed_detail, result.latencyMillis / 1000.0))
        is ProbeResult.NoToolCall -> Triple(false, stringResource(R.string.backend_test_no_tools), stringResource(R.string.backend_test_no_tools_detail))
        is ProbeResult.Failed -> Triple(false, stringResource(R.string.backend_test_failed), result.message)
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (ok) Color(0xFF34A853).copy(alpha = 0.12f) else MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(
                if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                null,
                tint = if (ok) Color(0xFF34A853) else MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.size(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodyMedium, maxLines = 6)
            }
        }
    }
}

private fun providerHint(provider: Provider): Int = when (provider) {
    Provider.OPENAI -> R.string.backend_hint_openai
    Provider.ANTHROPIC -> R.string.backend_hint_anthropic
    Provider.OPENROUTER -> R.string.backend_hint_openrouter
    Provider.GEMINI -> R.string.backend_hint_gemini
    Provider.OPENCLAW -> R.string.backend_hint_openclaw
    Provider.CUSTOM -> R.string.backend_hint_custom
}
