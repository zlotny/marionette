package dev.marionette.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.marionette.MarionetteApp
import dev.marionette.R
import dev.marionette.settings.AppSettings
import dev.marionette.settings.Credential
import dev.marionette.settings.SttEngine
import dev.marionette.ui.components.SettingsGroup
import dev.marionette.ui.components.SettingsIcon
import dev.marionette.ui.components.SettingsNote
import dev.marionette.ui.components.SettingsRow
import dev.marionette.ui.components.SettingsScaffold

@Composable
fun CredentialsScreen(onBack: () -> Unit) {
    SettingsScaffold(title = stringResource(R.string.settings_credentials), onBack = onBack) {
        item { SettingsNote(stringResource(R.string.credentials_intro)) }
        item {
            SettingsGroup(null) {
                Credential.entries.forEach { credential ->
                    item { i, n -> CredentialRow(i, n, credential) }
                }
            }
        }
    }
}

@Composable
fun CredentialRow(index: Int, count: Int, credential: Credential, headline: String = credential.title) {
    val settings by MarionetteApp.graph.settings.settings.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(false) }
    val saved = settings.key(credential)
    val uses = credentialUses(settings, credential)
    SettingsRow(
        index, count,
        headline = headline,
        supporting = when {
            saved.isBlank() -> stringResource(R.string.credentials_not_set)
            uses.isEmpty() -> stringResource(R.string.credentials_saved, mask(saved))
            else -> stringResource(R.string.credentials_saved_used, mask(saved), uses.joinToString(", "))
        },
        leading = { SettingsIcon(Icons.Rounded.Key) },
        onClick = { open = true },
    )
    if (open) {
        CredentialDialog(credential, saved, onDismiss = { open = false })
    }
}

@Composable
private fun credentialUses(settings: AppSettings, credential: Credential): List<String> = buildList {
    if (Credential.of(settings.provider) == credential) add(stringResource(R.string.credentials_use_agent))
    if (settings.sttEngine == SttEngine.CLOUD && Credential.of(settings.sttCloud.provider) == credential) add(stringResource(R.string.credentials_use_stt))
    if (credential == Credential.GOOGLE_PLACES) add(stringResource(R.string.credentials_use_places))
}

private fun mask(key: String): String = "••••" + key.takeLast(4)

@Composable
private fun CredentialDialog(credential: Credential, saved: String, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(saved) }
    var visible by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(credential.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(stringResource(if (credential == Credential.OPENCLAW) R.string.credentials_secret else R.string.credentials_key)) },
                    singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                credential.keyUrl?.let { url ->
                    TextButton(onClick = { uri.openUri(url) }) { Text(stringResource(R.string.backend_get_key)) }
                }
                Text(
                    stringResource(R.string.credentials_stored),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                updateSettings { it.withKey(credential, value) }
                onDismiss()
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            if (saved.isNotBlank()) {
                TextButton(onClick = {
                    updateSettings { it.withKey(credential, "") }
                    onDismiss()
                }) { Text(stringResource(R.string.action_remove)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}
