package dev.marionette.settings

import dev.marionette.llm.BackendConfig
import dev.marionette.llm.Provider
import dev.marionette.llm.Thinking

enum class SttEngine { ANDROID_ON_DEVICE, CLOUD }

enum class SttCloud(val title: String, val baseUrl: String, val provider: Provider, val models: List<String>) {
    OPENAI("OpenAI", "https://api.openai.com/v1", Provider.OPENAI, listOf("gpt-4o-mini-transcribe", "gpt-4o-transcribe", "whisper-1")),
    OPENROUTER(
        "OpenRouter", "https://openrouter.ai/api/v1", Provider.OPENROUTER,
        listOf(
            "openai/gpt-4o-mini-transcribe", "microsoft/mai-transcribe-2", "openai/gpt-4o-transcribe", "elevenlabs/scribe-v2",
            "mistralai/voxtral-mini-transcribe", "openai/gpt-transcribe", "assemblyai/universal-3-5-pro", "qwen/qwen3-asr-1.7b",
            "openai/whisper-large-v3-turbo",
        ),
    ),
}

enum class TtsEngine { SYSTEM, CLOUD }

enum class TtsCloud(
    val title: String,
    val baseUrl: String,
    val provider: Provider,
    val defaultModel: String,
    val defaultVoice: String,
    val models: List<String>,
    val voices: List<String>,
) {
    OPENAI(
        "OpenAI", "https://api.openai.com/v1", Provider.OPENAI, "gpt-4o-mini-tts", "marin",
        listOf("gpt-4o-mini-tts", "tts-1", "tts-1-hd"),
        listOf("marin", "cedar", "alloy", "ash", "ballad", "coral", "echo", "fable", "nova", "onyx", "sage", "shimmer", "verse"),
    ),
    OPENROUTER(
        "OpenRouter", "https://openrouter.ai/api/v1", Provider.OPENROUTER, "x-ai/grok-voice-tts-1.0", "eve",
        listOf("x-ai/grok-voice-tts-1.0", "elevenlabs/eleven-flash-v2.5", "microsoft/mai-voice-2.1-flash", "hexgrad/kokoro-82m", "google/gemini-3.8-flash-tts"),
        emptyList(),
    ),
}

enum class ContextField { TIME, LOCATION, MOTION, BATTERY, AUDIO, SCREEN }

data class AppSettings(
    val onboarded: Boolean = false,
    val agentName: String = DEFAULT_AGENT_NAME,
    val provider: Provider = Provider.OPENCLAW,
    val baseUrl: String = Provider.OPENCLAW.defaultBaseUrl,
    val model: String = Provider.OPENCLAW.suggestedModel,
    val agentModel: String = "",
    val thinking: String = "",
    val recentModels: List<String> = emptyList(),
    val credentials: Map<Credential, String> = emptyMap(),
    val continueConversation: Boolean = false,
    val gatewayDeviceKey: String = "",
    val gatewayDeviceToken: String = "",
    val gatewayPairedUrl: String = "",
    val sendContext: Boolean = true,
    val sendInstructions: Boolean = true,
    val instructions: String = "",
    val unlockTipsShown: Int = 0,
    val wakeEnabled: Boolean = true,
    val wakeThreshold: Float = 0.14f,
    val wakePhrase: String = "",
    val headsetTrigger: Boolean = true,
    val sttEngine: SttEngine = SttEngine.ANDROID_ON_DEVICE,
    val sttCloud: SttCloud = SttCloud.OPENAI,
    val sttModel: String = "",
    val secondLanguage: String = "",
    val language: String = "",
    val ttsVoice: String = "",
    val ttsEngine: TtsEngine = TtsEngine.SYSTEM,
    val ttsCloud: TtsCloud = TtsCloud.OPENROUTER,
    val ttsModel: String = "",
    val ttsCloudVoice: String = "",
    val speechRate: Float = 1.2f,
    val silenceMillis: Int = 700,
    val micBoost: Float = 1f,
    val followUpSeconds: Int = 6,
    val holdPhrases: List<String>? = null,
    val earcons: Boolean = true,
    val contextFields: Set<ContextField> = ContextField.entries.toSet(),
    val confirmationDisabled: Set<String> = emptySet(),
    val disabledTools: Set<String> = emptySet(),
    val uiAutomationBlocklist: Set<String> = DEFAULT_BLOCKLIST,
) {
    val apiKey: String get() = key(Credential.of(provider))
    val googlePlacesKey: String get() = key(Credential.GOOGLE_PLACES)
    val isBackendConfigured: Boolean get() = onboarded && baseUrl.isNotBlank() && model.isNotBlank() && (!provider.keyRequired || apiKey.isNotBlank())
    val backend: BackendConfig get() = BackendConfig(provider, baseUrl, model, apiKey, Thinking.of(thinking), agentModel)
    val ttsKey: String get() = key(Credential.of(ttsCloud.provider))
    val cloudSpeech: Boolean get() = ttsEngine == TtsEngine.CLOUD && ttsKey.isNotBlank()

    fun backendFor(chatModel: String?, chatThinking: String?): BackendConfig {
        val base = backend
        val thinkingLevel = Thinking.of(chatThinking ?: thinking)?.takeIf { it in Thinking.levels(provider) }
        return when {
            provider.isAgent -> base.copy(agentModel = chatModel ?: agentModel, thinking = thinkingLevel)
            else -> base.copy(model = chatModel?.takeIf { it.isNotBlank() } ?: model, thinking = thinkingLevel)
        }
    }

    fun withRecentModel(ref: String): AppSettings =
        if (ref.isBlank()) this else copy(recentModels = (listOf(ref) + recentModels.filterNot { it == ref }).take(MAX_RECENT_MODELS))

    fun key(credential: Credential): String = credentials[credential].orEmpty()

    fun withKey(credential: Credential, value: String): AppSettings =
        copy(credentials = if (value.isBlank()) credentials - credential else credentials + (credential to value.trim()))
    val liveProgress: Boolean get() = provider == Provider.OPENCLAW && gatewayDeviceToken.isNotBlank() && gatewayPairedUrl == baseUrl

    companion object {
        const val DEFAULT_AGENT_NAME = "Marionette"
        const val MAX_AGENT_NAME = 24
        const val MAX_RECENT_MODELS = 6

        val DEFAULT_BLOCKLIST = setOf(
            "com.google.android.apps.walletnfcrel",
            "com.paypal.android.p2pmobile",
            "com.google.android.apps.authenticator2",
            "com.azure.authenticator",
            "com.authy.authy",
            "es.bancosantander.apps",
            "com.bbva.bbvacontigo",
            "es.lacaixa.mobile.android.newwapicon",
            "com.ingdirect.android",
            "com.revolut.revolut",
            "de.number26.android",
        )
    }
}
