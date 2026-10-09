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
        val provider = stringPreferencesKey("provider")
        val baseUrl = stringPreferencesKey("base_url")
        val model = stringPreferencesKey("model")
        val apiKey = stringPreferencesKey("api_key_sealed")
        val wakeEnabled = booleanPreferencesKey("wake_enabled")
        val wakeThreshold = floatPreferencesKey("wake_threshold")
        val wakePhrase = stringPreferencesKey("wake_phrase")
        val headsetTrigger = booleanPreferencesKey("headset_trigger")
        val sttEngine = stringPreferencesKey("stt_engine")
        val sttApiKey = stringPreferencesKey("stt_api_key_sealed")
        val language = stringPreferencesKey("language")
        val ttsVoice = stringPreferencesKey("tts_voice")
        val speechRate = floatPreferencesKey("speech_rate")
        val silenceMillis = intPreferencesKey("silence_millis")
        val followUpSeconds = intPreferencesKey("follow_up_seconds")
        val earcons = booleanPreferencesKey("earcons")
        val contextFields = stringSetPreferencesKey("context_fields")
        val confirmationDisabled = stringSetPreferencesKey("confirmation_disabled")
        val disabledTools = stringSetPreferencesKey("disabled_tools")
        val googlePlacesKey = stringPreferencesKey("google_places_key_sealed")
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
            provider = provider,
            baseUrl = p[Keys.baseUrl] ?: provider.defaultBaseUrl,
            model = p[Keys.model] ?: provider.suggestedModel,
            apiKey = SecretStore.decrypt(p[Keys.apiKey].orEmpty()),
            wakeEnabled = p[Keys.wakeEnabled] ?: defaults.wakeEnabled,
            wakeThreshold = p[Keys.wakeThreshold] ?: defaults.wakeThreshold,
            wakePhrase = p[Keys.wakePhrase] ?: defaults.wakePhrase,
            headsetTrigger = p[Keys.headsetTrigger] ?: defaults.headsetTrigger,
            sttEngine = p[Keys.sttEngine]?.let { runCatching { SttEngine.valueOf(it) }.getOrNull() } ?: defaults.sttEngine,
            sttApiKey = SecretStore.decrypt(p[Keys.sttApiKey].orEmpty()),
            language = p[Keys.language] ?: defaults.language,
            ttsVoice = p[Keys.ttsVoice] ?: defaults.ttsVoice,
            speechRate = p[Keys.speechRate] ?: defaults.speechRate,
            silenceMillis = p[Keys.silenceMillis] ?: defaults.silenceMillis,
            followUpSeconds = p[Keys.followUpSeconds] ?: defaults.followUpSeconds,
            earcons = p[Keys.earcons] ?: defaults.earcons,
            contextFields = p[Keys.contextFields]?.mapNotNull { runCatching { ContextField.valueOf(it) }.getOrNull() }?.toSet()
                ?: defaults.contextFields,
            confirmationDisabled = p[Keys.confirmationDisabled] ?: defaults.confirmationDisabled,
            disabledTools = p[Keys.disabledTools] ?: defaults.disabledTools,
            googlePlacesKey = SecretStore.decrypt(p[Keys.googlePlacesKey].orEmpty()),
            uiAutomationBlocklist = p[Keys.blocklist] ?: defaults.uiAutomationBlocklist,
        )
    }

    private fun write(p: MutablePreferences, s: AppSettings) {
        p[Keys.onboarded] = s.onboarded
        p[Keys.provider] = s.provider.name
        p[Keys.baseUrl] = s.baseUrl
        p[Keys.model] = s.model
        p[Keys.apiKey] = SecretStore.encrypt(s.apiKey)
        p[Keys.wakeEnabled] = s.wakeEnabled
        p[Keys.wakeThreshold] = s.wakeThreshold
        p[Keys.wakePhrase] = s.wakePhrase
        p[Keys.headsetTrigger] = s.headsetTrigger
        p[Keys.sttEngine] = s.sttEngine.name
        p[Keys.sttApiKey] = SecretStore.encrypt(s.sttApiKey)
        p[Keys.language] = s.language
        p[Keys.ttsVoice] = s.ttsVoice
        p[Keys.speechRate] = s.speechRate
        p[Keys.silenceMillis] = s.silenceMillis
        p[Keys.followUpSeconds] = s.followUpSeconds
        p[Keys.earcons] = s.earcons
        p[Keys.contextFields] = s.contextFields.map { it.name }.toSet()
        p[Keys.confirmationDisabled] = s.confirmationDisabled
        p[Keys.disabledTools] = s.disabledTools
        p[Keys.googlePlacesKey] = SecretStore.encrypt(s.googlePlacesKey)
        p[Keys.blocklist] = s.uiAutomationBlocklist
    }
}
