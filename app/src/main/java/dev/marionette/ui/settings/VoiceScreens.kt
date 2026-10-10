package dev.marionette.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.marionette.MarionetteApp
import dev.marionette.R
import dev.marionette.ui.agentName
import dev.marionette.settings.Credential
import dev.marionette.settings.Languages
import dev.marionette.settings.SttCloud
import dev.marionette.settings.SttEngine
import dev.marionette.settings.TtsCloud
import dev.marionette.settings.TtsEngine
import dev.marionette.voice.CloudSpeech
import dev.marionette.voice.CloudVoice
import dev.marionette.voice.SpeechModel
import dev.marionette.ui.components.ChoiceRow
import dev.marionette.ui.components.RadioCardRow
import dev.marionette.ui.components.SettingsGroup
import dev.marionette.ui.components.SettingsIcon
import dev.marionette.ui.components.SettingsNote
import dev.marionette.ui.components.SettingsRow
import dev.marionette.ui.components.SettingsScaffold
import dev.marionette.ui.components.SliderRow
import dev.marionette.ui.components.SwitchRow
import dev.marionette.ui.voice.VoiceTestButton
import dev.marionette.ui.voice.languageOptions
import dev.marionette.ui.voice.rememberSpeaker
import kotlin.math.roundToInt

@Composable
fun RecognitionScreen(onBack: () -> Unit) {
    val settings by MarionetteApp.graph.settings.settings.collectAsStateWithLifecycle()
    var boost by remember { mutableFloatStateOf(settings.micBoost) }

    SettingsScaffold(title = stringResource(R.string.voice_group_recognition), onBack = onBack) {
        item {
            SettingsGroup(stringResource(R.string.voice_engine)) {
                item { i, n ->
                    RadioCardRow(
                        i, n,
                        headline = stringResource(R.string.voice_engine_device),
                        supporting = stringResource(R.string.voice_engine_device_body),
                        selected = settings.sttEngine == SttEngine.ANDROID_ON_DEVICE,
                        onClick = { updateSettings { it.copy(sttEngine = SttEngine.ANDROID_ON_DEVICE) } },
                    )
                }
                item { i, n ->
                    RadioCardRow(
                        i, n,
                        headline = stringResource(R.string.voice_engine_cloud),
                        supporting = stringResource(R.string.voice_engine_cloud_body),
                        selected = settings.sttEngine == SttEngine.CLOUD,
                        onClick = { updateSettings { it.copy(sttEngine = SttEngine.CLOUD) } },
                    )
                }
            }
        }
        if (settings.sttEngine == SttEngine.CLOUD) {
            item {
                SettingsGroup(stringResource(R.string.voice_group_cloud)) {
                    item { i, n ->
                        ChoiceRow(
                            i, n,
                            headline = stringResource(R.string.voice_cloud_service),
                            options = SttCloud.entries.map { it to it.title },
                            selected = settings.sttCloud,
                            onSelect = { cloud -> updateSettings { it.copy(sttCloud = cloud, sttModel = "") } },
                        )
                    }
                    item { i, n ->
                        ChoiceRow(
                            i, n,
                            headline = stringResource(R.string.voice_cloud_model),
                            options = settings.sttCloud.models.map { it to it },
                            selected = settings.sttModel.takeIf { it in settings.sttCloud.models } ?: settings.sttCloud.models.first(),
                            onSelect = { model -> updateSettings { it.copy(sttModel = model) } },
                        )
                    }
                    item { i, n ->
                        CredentialRow(i, n, Credential.of(settings.sttCloud.provider), headline = stringResource(R.string.voice_cloud_key, settings.sttCloud.title))
                    }
                }
            }
        }
        item {
            SettingsGroup(stringResource(R.string.voice_group_language)) {
                item { i, n ->
                    ChoiceRow(
                        i, n,
                        headline = stringResource(R.string.voice_language),
                        options = languageOptions(),
                        selected = settings.language,
                        onSelect = { tag -> updateSettings { it.copy(language = tag, ttsVoice = "", secondLanguage = it.secondLanguage.takeIf { other -> other != tag }.orEmpty()) } },
                        leading = { SettingsIcon(Icons.Rounded.Translate) },
                    )
                }
                item { i, n ->
                    ChoiceRow(
                        i, n,
                        headline = stringResource(R.string.voice_second_language),
                        options = listOf("" to stringResource(R.string.voice_second_language_none)) +
                            languageOptions().filter { (tag, _) -> tag.isNotBlank() && tag != settings.language },
                        selected = settings.secondLanguage,
                        onSelect = { tag -> updateSettings { it.copy(secondLanguage = tag) } },
                        leading = { SettingsIcon(Icons.Rounded.Translate) },
                    )
                }
                item { i, n -> MicBoostRow(i, n, boost) { boost = it } }
            }
        }
    }
}

@Composable
fun SpeechScreen(onBack: () -> Unit) {
    val settings by MarionetteApp.graph.settings.settings.collectAsStateWithLifecycle()
    val speaker = rememberSpeaker()
    var voices by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var rate by remember { mutableFloatStateOf(settings.speechRate) }
    val defaultVoice = stringResource(R.string.voice_default)

    LaunchedEffect(settings.language) {
        val locale = Languages.locale(settings.language)
        voices = speaker.voices()
            .filter { it.locale.language == locale.language && (locale.country.isEmpty() || it.locale.country == locale.country) }
            .map { it.name to voiceLabel(it.name, it.locale.toLanguageTag()) }
    }

    var models by remember { mutableStateOf(emptyList<SpeechModel>()) }
    LaunchedEffect(settings.ttsCloud, settings.ttsEngine) {
        if (settings.ttsEngine == TtsEngine.CLOUD) {
            models = settings.ttsCloud.models.map { SpeechModel(it, it, settings.ttsCloud.voices) }
            runCatching { CloudSpeech.models(settings.ttsCloud) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { models = it }
        }
    }
    val cloud = settings.ttsCloud
    val model = settings.ttsModel.ifBlank { cloud.defaultModel }
    val modelVoices = models.firstOrNull { it.id == model }?.voices.orEmpty()

    SettingsScaffold(title = stringResource(R.string.voice_group_speech), onBack = onBack) {
        item {
            SettingsGroup(stringResource(R.string.voice_speech_engine)) {
                item { i, n ->
                    RadioCardRow(
                        i, n,
                        headline = stringResource(R.string.voice_speech_system),
                        supporting = stringResource(R.string.voice_speech_system_body),
                        selected = settings.ttsEngine == TtsEngine.SYSTEM,
                        onClick = { updateSettings { it.copy(ttsEngine = TtsEngine.SYSTEM) } },
                    )
                }
                item { i, n ->
                    RadioCardRow(
                        i, n,
                        headline = stringResource(R.string.voice_speech_cloud),
                        supporting = stringResource(R.string.voice_speech_cloud_body),
                        selected = settings.ttsEngine == TtsEngine.CLOUD,
                        onClick = { updateSettings { it.copy(ttsEngine = TtsEngine.CLOUD) } },
                    )
                }
            }
        }
        item {
            SettingsGroup(null) {
                if (settings.ttsEngine == TtsEngine.CLOUD) {
                    item { i, n ->
                        ChoiceRow(
                            i, n,
                            headline = stringResource(R.string.voice_cloud_service),
                            options = TtsCloud.entries.map { it to it.title },
                            selected = cloud,
                            onSelect = { choice -> updateSettings { it.copy(ttsCloud = choice, ttsModel = "", ttsCloudVoice = "") } },
                        )
                    }
                    item { i, n ->
                        ChoiceRow(
                            i, n,
                            headline = stringResource(R.string.voice_cloud_model),
                            options = (models.map { it.id to it.name } + (model to model)).distinctBy { it.first },
                            selected = model,
                            onSelect = { id ->
                                val first = models.firstOrNull { it.id == id }?.voices?.firstOrNull().orEmpty()
                                updateSettings { it.copy(ttsModel = id, ttsCloudVoice = if (id == cloud.defaultModel) cloud.defaultVoice else first) }
                            },
                        )
                    }
                    item { i, n ->
                        val selectedVoice = settings.ttsCloudVoice.ifBlank { if (model == cloud.defaultModel) cloud.defaultVoice else "" }
                        ChoiceRow(
                            i, n,
                            headline = stringResource(R.string.voice_voice),
                            options = (listOf("" to defaultVoice) + modelVoices.map { it to it } + listOfNotNull(selectedVoice.takeIf { it.isNotBlank() }?.let { it to it }))
                                .distinctBy { it.first },
                            selected = selectedVoice,
                            onSelect = { name -> updateSettings { it.copy(ttsCloudVoice = name) } },
                            leading = { SettingsIcon(Icons.Rounded.RecordVoiceOver) },
                        )
                    }
                    item { i, n ->
                        CredentialRow(i, n, Credential.of(cloud.provider), headline = stringResource(R.string.voice_cloud_key, cloud.title))
                    }
                } else {
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
        if (settings.ttsEngine == TtsEngine.CLOUD) {
            item {
                SettingsNote(
                    stringResource(if (settings.ttsKey.isBlank()) R.string.voice_speech_cloud_needs_key else R.string.voice_speech_cloud_note, cloud.title),
                )
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                VoiceTestButton(settings.language, settings.ttsVoice, rate, cloud = CloudVoice.from(settings.copy(speechRate = rate)))
            }
        }
    }
}

@Composable
fun ConversationScreen(onBack: () -> Unit, onOpenHoldPhrases: () -> Unit) {
    val settings by MarionetteApp.graph.settings.settings.collectAsStateWithLifecycle()
    var silence by remember { mutableFloatStateOf(settings.silenceMillis.toFloat()) }
    var followUp by remember { mutableFloatStateOf(settings.followUpSeconds.toFloat()) }
    val holdCount = settings.holdPhrases?.size ?: stringArrayResource(R.array.voice_hold_phrases).size

    SettingsScaffold(title = stringResource(R.string.voice_group_conversation), onBack = onBack) {
        item {
            SettingsGroup(null) {
                item { i, n ->
                    SliderRow(
                        i, n,
                        headline = stringResource(R.string.voice_silence),
                        supporting = stringResource(R.string.voice_silence_body, agentName()),
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
                    SettingsRow(
                        i, n,
                        headline = stringResource(R.string.voice_hold_title),
                        supporting = holdCount.let { count ->
                            if (count == 0) stringResource(R.string.voice_hold_off) else pluralStringResource(R.plurals.voice_hold_summary, count, count)
                        },
                        leading = { SettingsIcon(Icons.Rounded.HourglassTop) },
                        onClick = onOpenHoldPhrases,
                    )
                }
                item { i, n ->
                    SwitchRow(
                        i, n,
                        headline = stringResource(R.string.voice_earcons),
                        supporting = stringResource(R.string.voice_earcons_body, agentName()),
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
