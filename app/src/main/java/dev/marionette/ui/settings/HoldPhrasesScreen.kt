package dev.marionette.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.marionette.MarionetteApp
import dev.marionette.R
import dev.marionette.ui.agentName
import dev.marionette.ui.components.SettingsGroup
import dev.marionette.ui.components.SettingsIcon
import dev.marionette.ui.components.SettingsNote
import dev.marionette.ui.components.SettingsRow
import dev.marionette.ui.components.SettingsScaffold
import dev.marionette.ui.components.TextDialog
import dev.marionette.ui.voice.localizedResources

@Composable
fun HoldPhrasesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings by MarionetteApp.graph.settings.settings.collectAsStateWithLifecycle()
    val defaults = localizedResources(context, settings.language).getStringArray(R.array.voice_hold_phrases).toList()
    val phrases = settings.holdPhrases ?: defaults
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    val save: (List<String>) -> Unit = { list -> updateSettings { it.copy(holdPhrases = list.takeUnless { l -> l == defaults }) } }

    SettingsScaffold(title = stringResource(R.string.voice_hold_title), onBack = onBack) {
        item { SettingsNote(stringResource(R.string.voice_hold_intro, agentName())) }
        item {
            SettingsGroup(null) {
                phrases.forEachIndexed { index, phrase ->
                    item { i, n ->
                        SettingsRow(
                            i, n,
                            headline = phrase,
                            trailing = {
                                IconButton(onClick = { save(phrases.filterIndexed { j, _ -> j != index }) }) {
                                    Icon(Icons.Rounded.Delete, stringResource(R.string.action_delete))
                                }
                            },
                            onClick = { editing = index },
                        )
                    }
                }
                item { i, n ->
                    SettingsRow(
                        i, n,
                        headline = stringResource(R.string.voice_hold_add),
                        leading = { SettingsIcon(Icons.Rounded.Add) },
                        onClick = { editing = phrases.size },
                    )
                }
            }
        }
        if (phrases.isEmpty()) item { SettingsNote(stringResource(R.string.voice_hold_empty, agentName())) }
        if (settings.holdPhrases != null) {
            item {
                Row {
                    TextButton(onClick = { updateSettings { it.copy(holdPhrases = null) } }) { Text(stringResource(R.string.voice_hold_reset)) }
                }
            }
        }
    }

    editing?.let { index ->
        val adding = index >= phrases.size
        TextDialog(
            title = stringResource(if (adding) R.string.voice_hold_add else R.string.voice_hold_edit),
            initial = phrases.getOrNull(index).orEmpty(),
            label = stringResource(R.string.voice_hold_phrase),
            capitalization = KeyboardCapitalization.Sentences,
            onDismiss = { editing = null },
            onSave = { text ->
                save(if (adding) phrases + text else phrases.mapIndexed { j, old -> if (j == index) text else old })
                editing = null
            },
        )
    }
}
