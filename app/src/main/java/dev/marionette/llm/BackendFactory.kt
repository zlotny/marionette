package dev.marionette.llm

import dev.marionette.core.Http

object BackendFactory {
    fun create(config: BackendConfig, sessionUser: String? = null): LlmBackend = when (config.provider.style) {
        ApiStyle.ANTHROPIC -> AnthropicBackend(config.baseUrl, config.apiKey, config.model, thinking = config.thinking, client = client(config))
        ApiStyle.OPENAI_CHAT -> OpenAiChatBackend(
            config.baseUrl, config.apiKey, config.model, sessionUser,
            reasoning = config.thinking?.takeUnless { config.provider.isAgent }?.let { Reasoning(it, nested = config.provider == Provider.OPENROUTER) },
            modelHeader = config.agentModel.takeIf { config.provider == Provider.OPENCLAW && it.isNotBlank() },
            client = client(config),
        )
    }

    private fun client(config: BackendConfig) = if (config.provider.isAgent) Http.agent else Http.model
}
