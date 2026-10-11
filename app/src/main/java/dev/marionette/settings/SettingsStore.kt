package dev.marionette.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.marionette.llm.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsStore(context: Context, scope: CoroutineScope) {
    private val store = context.dataStore

    private object Keys {
        val onboarded = booleanPreferencesKey("onboarded")
        val agentName = stringPreferencesKey("agent_name")
        val provider = stringPreferencesKey("provider")
        val baseUrl = stringPreferencesKey("base_url")
        val model = stringPreferencesKey("model")
        val agentModel = stringPreferencesKey("agent_model")
        val thinking = stringPreferencesKey("thinking")
        val recentModels = stringPreferencesKey("recent_models")
        val legacyApiKey = stringPreferencesKey("api_key_sealed")
        val continueConversation = booleanPreferencesKey("continue_conversation")
        val gatewayDeviceKey = stringPreferencesKey("gateway_device_key_sealed")
        val gatewayDeviceToken = stringPreferencesKey("gateway_device_token_sealed")
        val gatewayPairedUrl = stringPreferencesKey("gateway_paired_url")
        val sendContext = booleanPreferencesKey("send_context")
        val sendInstructions = booleanPreferencesKey("send_instructions")
        val instructions = stringPreferencesKey("instructions")
        val unlockTipsShown = intPreferencesKey("unlock_tips_shown")
        val wakeEnabled = booleanPreferencesKey("wake_enabled")
        val wakeThreshold = floatPreferencesKey("wake_threshold")
        val wakePhrase = stringPreferencesKey("wake_phrase")
        val headsetTrigger = booleanPreferencesKey("headset_trigger")
        val sttEngine = stringPreferencesKey("stt_engine")
        val legacySttApiKey = stringPreferencesKey("stt_api_key_sealed")
        val sttCloud = stringPreferencesKey("stt_cloud")
        val sttModel = stringPreferencesKey("stt_model")
        val secondLanguage = stringPreferencesKey("second_language")
        val language = stringPreferencesKey("language")
        val ttsVoice = stringPreferencesKey("tts_voice")
        val ttsEngine = stringPreferencesKey("tts_engine")
        val ttsCloud = stringPreferencesKey("tts_cloud")
        val ttsModel = stringPreferencesKey("tts_model")
        val ttsCloudVoice = stringPreferencesKey("tts_cloud_voice")
        val speechRate = floatPreferencesKey("speech_rate")
        val silenceMillis = intPreferencesKey("silence_millis")
        val micBoost = floatPreferencesKey("mic_boost")
        val followUpSeconds = intPreferencesKey("follow_up_seconds")
        val holdPhrases = stringPreferencesKey("hold_phrases")
        val earcons = booleanPreferencesKey("earcons")
        val relock = booleanPreferencesKey("relock_after_screen_control")
        val warmOnWake = booleanPreferencesKey("warm_on_wake")
        val contextFields = stringSetPreferencesKey("context_fields")
        val confirmationDisabled = stringSetPreferencesKey("confirmation_disabled")
        val disabledTools = stringSetPreferencesKey("disabled_tools")
        val legacyGooglePlacesKey = stringPreferencesKey("google_places_key_sealed")
        val credentials = Credential.entries.associateWith { stringPreferencesKey("credential_${it.name.lowercase()}_sealed") }
        val blocklist = stringSetPreferencesKey("ui_blocklist")
    }

    val settings: StateFlow<AppSettings> = store.data
        .map(::read)
        .stateIn(scope, SharingStarted.Eagerly, runBlocking { read(store.data.first()) })

    val current: AppSettings get() = settings.value

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs -> write(prefs, transform(read(prefs))) }
    }

    private fun read(p: Preferences): AppSettings {
        val defaults = AppSettings()
        val provider = Provider.of(p[Keys.provider])
        return AppSettings(
            onboarded = p[Keys.onboarded] ?: false,
            agentName = p[Keys.agentName]?.takeIf { it.isNotBlank() } ?: defaults.agentName,
            provider = provider,
            baseUrl = p[Keys.baseUrl] ?: provider.defaultBaseUrl,
            model = p[Keys.model] ?: provider.suggestedModel,
            agentModel = p[Keys.agentModel] ?: defaults.agentModel,
            thinking = p[Keys.thinking] ?: defaults.thinking,
            recentModels = p[Keys.recentModels]?.split('\n')?.filter { it.isNotBlank() } ?: defaults.recentModels,
            credentials = readCredentials(p, provider),
            continueConversation = p[Keys.continueConversation] ?: defaults.continueConversation,
            gatewayDeviceKey = SecretStore.decrypt(p[Keys.gatewayDeviceKey].orEmpty()),
            gatewayDeviceToken = SecretStore.decrypt(p[Keys.gatewayDeviceToken].orEmpty()),
            gatewayPairedUrl = p[Keys.gatewayPairedUrl] ?: defaults.gatewayPairedUrl,
            sendContext = p[Keys.sendContext] ?: defaults.sendContext,
            sendInstructions = p[Keys.sendInstructions] ?: defaults.sendInstructions,
            instructions = p[Keys.instructions] ?: defaults.instructions,
            unlockTipsShown = p[Keys.unlockTipsShown] ?: defaults.unlockTipsShown,
            wakeEnabled = p[Keys.wakeEnabled] ?: defaults.wakeEnabled,
            wakeThreshold = p[Keys.wakeThreshold] ?: defaults.wakeThreshold,
            wakePhrase = p[Keys.wakePhrase] ?: defaults.wakePhrase,
            headsetTrigger = p[Keys.headsetTrigger] ?: defaults.headsetTrigger,
            sttEngine = p[Keys.sttEngine]?.let { if (it == "OPENAI_CLOUD") SttEngine.CLOUD else runCatching { SttEngine.valueOf(it) }.getOrNull() } ?: defaults.sttEngine,
            sttCloud = p[Keys.sttCloud]?.let { runCatching { SttCloud.valueOf(it) }.getOrNull() } ?: defaults.sttCloud,
            sttModel = p[Keys.sttModel] ?: defaults.sttModel,
            secondLanguage = p[Keys.secondLanguage] ?: defaults.secondLanguage,
            language = p[Keys.language] ?: defaults.language,
            ttsVoice = p[Keys.ttsVoice] ?: defaults.ttsVoice,
            ttsEngine = p[Keys.ttsEngine]?.let { runCatching { TtsEngine.valueOf(it) }.getOrNull() } ?: defaults.ttsEngine,
            ttsCloud = p[Keys.ttsCloud]?.let { runCatching { TtsCloud.valueOf(it) }.getOrNull() } ?: defaults.ttsCloud,
            ttsModel = p[Keys.ttsModel] ?: defaults.ttsModel,
            ttsCloudVoice = p[Keys.ttsCloudVoice] ?: defaults.ttsCloudVoice,
            speechRate = p[Keys.speechRate] ?: defaults.speechRate,
            silenceMillis = p[Keys.silenceMillis] ?: defaults.silenceMillis,
            micBoost = p[Keys.micBoost] ?: defaults.micBoost,
            followUpSeconds = p[Keys.followUpSeconds] ?: defaults.followUpSeconds,
            holdPhrases = p[Keys.holdPhrases]?.split('\n')?.filter { it.isNotBlank() },
            earcons = p[Keys.earcons] ?: defaults.earcons,
            warmOnWake = p[Keys.warmOnWake] ?: defaults.warmOnWake,
            contextFields = p[Keys.contextFields]?.mapNotNull { runCatching { ContextField.valueOf(it) }.getOrNull() }?.toSet()
                ?: defaults.contextFields,
            confirmationDisabled = p[Keys.confirmationDisabled] ?: defaults.confirmationDisabled,
            disabledTools = p[Keys.disabledTools] ?: defaults.disabledTools,
            uiAutomationBlocklist = p[Keys.blocklist] ?: defaults.uiAutomationBlocklist,
            relockAfterScreenControl = p[Keys.relock] ?: defaults.relockAfterScreenControl,
        )
    }

    private fun readCredentials(p: Preferences, provider: Provider): Map<Credential, String> {
        val stored = Keys.credentials.mapValues { (_, key) -> SecretStore.decrypt(p[key].orEmpty()) }
        val sttCloud = p[Keys.sttCloud]?.let { runCatching { SttCloud.valueOf(it) }.getOrNull() } ?: AppSettings().sttCloud
        val legacy = listOf(
            Credential.of(provider) to p[Keys.legacyApiKey],
            Credential.of(sttCloud.provider) to p[Keys.legacySttApiKey],
            Credential.GOOGLE_PLACES to p[Keys.legacyGooglePlacesKey],
        ).mapNotNull { (credential, sealed) -> SecretStore.decrypt(sealed.orEmpty()).takeIf { it.isNotBlank() }?.let { credential to it } }
        val merged = stored.filterValues { it.isNotBlank() }.toMutableMap()
        legacy.forEach { (credential, value) -> merged.putIfAbsent(credential, value) }
        return merged
    }

    private fun write(p: MutablePreferences, s: AppSettings) {
        p[Keys.onboarded] = s.onboarded
        p[Keys.agentName] = s.agentName
        p[Keys.provider] = s.provider.name
        p[Keys.baseUrl] = s.baseUrl
        p[Keys.model] = s.model
        p[Keys.agentModel] = s.agentModel
        p[Keys.thinking] = s.thinking
        p[Keys.recentModels] = s.recentModels.joinToString("\n")
        Keys.credentials.forEach { (credential, key) ->
            s.credentials[credential]?.takeIf { it.isNotBlank() }?.let { p[key] = SecretStore.seal(it, p[key]) } ?: p.remove(key)
        }
        p.remove(Keys.legacyApiKey)
        p.remove(Keys.legacySttApiKey)
        p.remove(Keys.legacyGooglePlacesKey)
        p[Keys.continueConversation] = s.continueConversation
        p[Keys.gatewayDeviceKey] = SecretStore.seal(s.gatewayDeviceKey, p[Keys.gatewayDeviceKey])
        p[Keys.gatewayDeviceToken] = SecretStore.seal(s.gatewayDeviceToken, p[Keys.gatewayDeviceToken])
        p[Keys.gatewayPairedUrl] = s.gatewayPairedUrl
        p[Keys.sendContext] = s.sendContext
        p[Keys.sendInstructions] = s.sendInstructions
        p[Keys.instructions] = s.instructions
        p[Keys.unlockTipsShown] = s.unlockTipsShown
        p[Keys.wakeEnabled] = s.wakeEnabled
        p[Keys.wakeThreshold] = s.wakeThreshold
        p[Keys.wakePhrase] = s.wakePhrase
        p[Keys.headsetTrigger] = s.headsetTrigger
        p[Keys.sttEngine] = s.sttEngine.name
        p[Keys.sttCloud] = s.sttCloud.name
        p[Keys.sttModel] = s.sttModel
        p[Keys.secondLanguage] = s.secondLanguage
        p[Keys.language] = s.language
        p[Keys.ttsVoice] = s.ttsVoice
        p[Keys.ttsEngine] = s.ttsEngine.name
        p[Keys.ttsCloud] = s.ttsCloud.name
        p[Keys.ttsModel] = s.ttsModel
        p[Keys.ttsCloudVoice] = s.ttsCloudVoice
        p[Keys.speechRate] = s.speechRate
        p[Keys.silenceMillis] = s.silenceMillis
        p[Keys.micBoost] = s.micBoost
        p[Keys.followUpSeconds] = s.followUpSeconds
        s.holdPhrases?.let { p[Keys.holdPhrases] = it.joinToString("\n") } ?: p.remove(Keys.holdPhrases)
        p[Keys.earcons] = s.earcons
        p[Keys.warmOnWake] = s.warmOnWake
        p[Keys.contextFields] = s.contextFields.map { it.name }.toSet()
        p[Keys.confirmationDisabled] = s.confirmationDisabled
        p[Keys.disabledTools] = s.disabledTools
        p[Keys.blocklist] = s.uiAutomationBlocklist
        p[Keys.relock] = s.relockAfterScreenControl
    }
}
