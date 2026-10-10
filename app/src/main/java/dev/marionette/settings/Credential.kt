package dev.marionette.settings

import dev.marionette.llm.Provider

enum class Credential(val title: String, val keyUrl: String?) {
    OPENCLAW("OpenClaw", null),
    OPENAI("OpenAI", Provider.OPENAI.keyUrl),
    ANTHROPIC("Anthropic", Provider.ANTHROPIC.keyUrl),
    OPENROUTER("OpenRouter", Provider.OPENROUTER.keyUrl),
    GEMINI("Google Gemini", Provider.GEMINI.keyUrl),
    CUSTOM("OpenAI-compatible", null),
    GOOGLE_PLACES("Google Places", "https://console.cloud.google.com/google/maps-apis/credentials"),
    ;

    companion object {
        fun of(provider: Provider): Credential = when (provider) {
            Provider.OPENCLAW -> OPENCLAW
            Provider.OPENAI -> OPENAI
            Provider.ANTHROPIC -> ANTHROPIC
            Provider.OPENROUTER -> OPENROUTER
            Provider.GEMINI -> GEMINI
            Provider.CUSTOM -> CUSTOM
        }
    }
}
