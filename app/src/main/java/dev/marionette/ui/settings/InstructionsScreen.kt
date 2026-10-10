package dev.marionette.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.marionette.MarionetteApp
import dev.marionette.R
import dev.marionette.ui.agentName
import dev.marionette.ui.components.SettingsGroup
import dev.marionette.ui.components.SettingsNote
import dev.marionette.ui.components.SettingsScaffold

@Composable
fun InstructionsScreen(onBack: () -> Unit) {
    val graph = MarionetteApp.graph
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val default = graph.prompt.defaultInstructions
    var draft by rememberSaveable { mutableStateOf(settings.instructions.ifBlank { default }) }
    val stored = settings.instructions.ifBlank { default }
    val agent = settings.provider.isAgent

    SettingsScaffold(
        title = stringResource(R.string.settings_instructions),
        onBack = onBack,
        actions = {
            if (!agent) {
                TextButton(
                    onClick = { updateSettings { it.copy(instructions = draft.trim().takeUnless { text -> text == default }.orEmpty()) } },
                    enabled = draft.trim() != stored && draft.isNotBlank(),
                ) { Text(stringResource(R.string.action_save)) }
            }
        },
    ) {
        if (agent) {
            item { SettingsNote(stringResource(R.string.instructions_agent_intro, agentName(), settings.provider.title)) }
            item {
                Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                    AgentSetupCard(Modifier.fillMaxWidth().padding(20.dp))
                }
            }
            item { SettingsGroup(null) { item { i, n -> SendContextRow(i, n) } } }
        } else {
            item { SettingsNote(stringResource(R.string.instructions_intro, agentName())) }
            item { SettingsGroup(null) { item { i, n -> SendInstructionsRow(i, n) } } }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text(stringResource(R.string.settings_instructions)) },
                        supportingText = { Text(stringResource(R.string.instructions_name_hint)) },
                        enabled = settings.sendInstructions,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp),
                    )
                    Row {
                        TextButton(onClick = { draft = default }, enabled = settings.sendInstructions && draft != default) {
                            Text(stringResource(R.string.instructions_reset))
                        }
                    }
                }
            }
            item { SettingsGroup(null) { item { i, n -> SendContextRow(i, n) } } }
        }
    }
}
