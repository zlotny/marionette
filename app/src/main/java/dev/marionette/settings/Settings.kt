package dev.marionette.settings

import dev.marionette.llm.BackendConfig
import dev.marionette.llm.Provider

enum class SttEngine { ANDROID_ON_DEVICE, OPENAI_CLOUD }

enum class ContextField { TIME, LOCATION, MOTION, BATTERY, AUDIO, SCREEN }

data class AppSettings(
    val onboarded: Boolean = false,
    val provider: Provider = Provider.OPENCLAW,
    val baseUrl: String = Provider.OPENCLAW.defaultBaseUrl,
    val model: String = Provider.OPENCLAW.suggestedModel,
    val apiKey: String = "",
    val wakeEnabled: Boolean = true,
    val wakeThreshold: Float = 0.14f,
    val wakePhrase: String = "",
    val headsetTrigger: Boolean = true,
    val sttEngine: SttEngine = SttEngine.ANDROID_ON_DEVICE,
    val sttApiKey: String = "",
    val language: String = "",
    val ttsVoice: String = "",
    val speechRate: Float = 1.0f,
    val silenceMillis: Int = 700,
    val followUpSeconds: Int = 6,
    val earcons: Boolean = true,
    val contextFields: Set<ContextField> = ContextField.entries.toSet(),
    val confirmationDisabled: Set<String> = emptySet(),
    val disabledTools: Set<String> = emptySet(),
    val googlePlacesKey: String = "",
    val uiAutomationBlocklist: Set<String> = DEFAULT_BLOCKLIST,
) {
    val backend: BackendConfig get() = BackendConfig(provider, baseUrl, model, apiKey)

    companion object {
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
