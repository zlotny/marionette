package dev.marionette.llm

enum class ApiStyle { OPENAI_CHAT, ANTHROPIC }

enum class Provider(
    val title: String,
    val defaultBaseUrl: String,
    val style: ApiStyle,
    val keyRequired: Boolean,
    val baseUrlEditable: Boolean,
    val suggestedModel: String,
    val keyUrl: String? = null,
    val isAgent: Boolean = false,
) {
    OPENCLAW("OpenClaw", "", ApiStyle.OPENAI_CHAT, false, true, "openclaw/default", isAgent = true),
    OPENAI("OpenAI", "https://api.openai.com/v1", ApiStyle.OPENAI_CHAT, true, false, "", "https://platform.openai.com/api-keys"),
    ANTHROPIC("Anthropic", "https://api.anthropic.com/v1", ApiStyle.ANTHROPIC, true, false, "claude-haiku-5-5", "https://console.anthropic.com/settings/keys"),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", ApiStyle.OPENAI_CHAT, true, false, "", "https://openrouter.ai/keys"),
    GEMINI("Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", ApiStyle.OPENAI_CHAT, true, false, "", "https://aistudio.google.com/apikey"),
    CUSTOM("OpenAI-compatible", "http://10.0.2.2:11434/v1", ApiStyle.OPENAI_CHAT, false, true, ""),
    ;

    companion object {
        fun of(name: String?): Provider = entries.firstOrNull { it.name == name } ?: OPENCLAW
    }
}

data class BackendConfig(
    val provider: Provider,
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val thinking: Thinking? = null,
    val agentModel: String = "",
)
