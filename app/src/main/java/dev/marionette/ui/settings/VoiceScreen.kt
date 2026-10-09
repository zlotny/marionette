package dev.marionette.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.marionette.MarionetteApp
import dev.marionette.R
import dev.marionette.settings.Languages
import dev.marionette.settings.SttEngine
import dev.marionette.ui.components.ChoiceRow
import dev.marionette.ui.components.SecretField
import dev.marionette.ui.components.SettingsGroup
import dev.marionette.ui.components.SettingsIcon
import dev.marionette.ui.components.SettingsScaffold
import dev.marionette.ui.components.SliderRow
import dev.marionette.ui.components.SwitchRow
import dev.marionette.ui.voice.VoiceTestButton
import dev.marionette.ui.voice.languageOptions
import dev.marionette.ui.voice.rememberSpeaker
import kotlin.math.roundToInt

@Composable
fun VoiceScreen(onBack: () -> Unit) {
    val settings by MarionetteApp.graph.settings.settings.collectAsStateWithLifecycle()
    val speaker = rememberSpeaker()
    var voices by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var rate by remember { mutableFloatStateOf(settings.speechRate) }
    var silence by remember { mutableFloatStateOf(settings.silenceMillis.toFloat()) }
    var followUp by remember { mutableFloatStateOf(settings.followUpSeconds.toFloat()) }
    val defaultVoice = stringResource(R.string.voice_default)

    LaunchedEffect(settings.language) {
        val locale = Languages.locale(settings.language)
        voices = speaker.voices()
            .filter { it.locale.language == locale.language && (locale.country.isEmpty() || it.locale.country == locale.country) }
            .map { it.name to voiceLabel(it.name, it.locale.toLanguageTag()) }
    }

    SettingsScaffold(title = stringResource(R.string.settings_voice), onBack = onBack) {
        item {
            SettingsGroup(stringResource(R.string.voice_group_recognition)) {
                item { i, n ->
                    ChoiceRow(
                        i, n,
                        headline = stringResource(R.string.voice_engine),
                        options = listOf(
                            SttEngine.ANDROID_ON_DEVICE to stringResource(R.string.voice_engine_device),
                            SttEngine.OPENAI_CLOUD to stringResource(R.string.voice_engine_openai),
                        ),
                        selected = settings.sttEngine,
                        onSelect = { engine -> updateSettings { it.copy(sttEngine = engine) } },
                        leading = { SettingsIcon(Icons.Rounded.Mic) },
                    )
                }
                item { i, n ->
                    ChoiceRow(
                        i, n,
                        headline = stringResource(R.string.voice_language),
                        options = languageOptions(),
                        selected = settings.language,
                        onSelect = { tag -> updateSettings { it.copy(language = tag, ttsVoice = "") } },
                        leading = { SettingsIcon(Icons.Rounded.Translate) },
                    )
                }
            }
        }
        if (settings.sttEngine == SttEngine.OPENAI_CLOUD) {
            item {
                Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                    SecretField(
                        label = stringResource(R.string.voice_openai_key),
                        saved = settings.sttApiKey,
                        onSave = { key -> updateSettings { it.copy(sttApiKey = key) } },
                        supporting = stringResource(R.string.voice_openai_key_body),
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
        item {
            SettingsGroup(stringResource(R.string.voice_group_speech)) {
                item { i, n ->
                    ChoiceRow(
                        i, n,
                        headline = stringResource(R.string.voice_voice),
                        options = listOf("" to defaultVoice) + voices,
                        selected = settings.ttsVoice.takeIf { name -> voices.any { it.first == name } }.orEmpty(),
                        onSelect = { name -> updateSettings { it.copy(ttsVoice = name) } },
                        leading = { SettingsIcon(Icons.Rounded.RecordVoiceOver) },
                    )
                }
                item { i, n ->
                    SliderRow(
                        i, n,
                        headline = stringResource(R.string.voice_rate),
                        valueLabel = "%.1f×".format(rate),
                        value = rate,
                        valueRange = 0.5f..2f,
                        steps = 14,
                        onValueChange = { rate = it },
                        onValueChangeFinished = { updateSettings { it.copy(speechRate = rate) } },
                    )
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                VoiceTestButton(settings.language, settings.ttsVoice, rate)
            }
        }
        item {
            SettingsGroup(stringResource(R.string.voice_group_conversation)) {
                item { i, n ->
                    SliderRow(
                        i, n,
                        headline = stringResource(R.string.voice_silence),
                        supporting = stringResource(R.string.voice_silence_body),
                        valueLabel = stringResource(R.string.unit_ms, silence.roundToInt()),
                        value = silence,
                        valueRange = 400f..1500f,
                        steps = 10,
                        onValueChange = { silence = it },
                        onValueChangeFinished = { updateSettings { it.copy(silenceMillis = silence.roundToInt()) } },
                    )
                }
                item { i, n ->
                    SliderRow(
                        i, n,
                        headline = stringResource(R.string.voice_follow_up),
                        supporting = stringResource(R.string.voice_follow_up_body),
                        valueLabel = if (followUp.roundToInt() == 0) stringResource(R.string.state_off) else stringResource(R.string.unit_s, followUp.roundToInt()),
                        value = followUp,
                        valueRange = 0f..15f,
                        steps = 14,
                        onValueChange = { followUp = it },
                        onValueChangeFinished = { updateSettings { it.copy(followUpSeconds = followUp.roundToInt()) } },
                    )
                }
                item { i, n ->
                    SwitchRow(
                        i, n,
                        headline = stringResource(R.string.voice_earcons),
                        supporting = stringResource(R.string.voice_earcons_body),
                        checked = settings.earcons,
                        leading = { SettingsIcon(Icons.Rounded.MusicNote) },
                        onCheckedChange = { on -> updateSettings { it.copy(earcons = on) } },
                    )
                }
            }
        }
    }
}

private fun voiceLabel(name: String, tag: String): String {
    val variant = name.substringAfter("-x-", "").substringBefore('-').ifBlank { name }
    return "$tag · ${variant.uppercase()}"
}
