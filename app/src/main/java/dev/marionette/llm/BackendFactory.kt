package dev.marionette.llm

object BackendFactory {
    fun create(config: BackendConfig, sessionUser: String? = null): LlmBackend = when (config.provider.style) {
        ApiStyle.ANTHROPIC -> AnthropicBackend(config.baseUrl, config.apiKey, config.model, thinking = config.thinking)
        ApiStyle.OPENAI_CHAT -> OpenAiChatBackend(
            config.baseUrl, config.apiKey, config.model, sessionUser,
            reasoning = config.thinking?.takeUnless { config.provider.isAgent }?.let { Reasoning(it, nested = config.provider == Provider.OPENROUTER) },
            modelHeader = config.agentModel.takeIf { config.provider == Provider.OPENCLAW && it.isNotBlank() },
        )
    }
}
