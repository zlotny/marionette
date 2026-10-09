package dev.marionette.llm

object BackendFactory {
    fun create(config: BackendConfig, sessionUser: String? = null): LlmBackend = when (config.provider.style) {
        ApiStyle.ANTHROPIC -> AnthropicBackend(config.baseUrl, config.apiKey, config.model)
        ApiStyle.OPENAI_CHAT -> OpenAiChatBackend(config.baseUrl, config.apiKey, config.model, sessionUser)
    }
}
