package dev.marionette.ui.home

import dev.marionette.agent.Agent
import dev.marionette.agent.Chat
import dev.marionette.agent.Conversation
import dev.marionette.agent.ModelStats
import dev.marionette.agent.StepStats
import dev.marionette.llm.ModelPrice
import dev.marionette.llm.ModelPrices
import dev.marionette.llm.BackendFactory
import dev.marionette.llm.DeviceIdentity
import dev.marionette.llm.LinkResult
import dev.marionette.llm.OpenClawGateway
import dev.marionette.llm.OpenClawLink
import dev.marionette.llm.Provider
import dev.marionette.llm.Thinking
import dev.marionette.settings.AppSettings
import dev.marionette.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ModelOption(val ref: String, val label: String, val thinking: List<Thinking>)

sealed interface ModelList {
    data object Idle : ModelList
    data object Loading : ModelList
    data object NeedsPairing : ModelList
    data class Loaded(val options: List<ModelOption>) : ModelList
    data class Failed(val message: String) : ModelList
}

sealed interface ThinkingSync {
    data object Idle : ThinkingSync
    data object Applying : ThinkingSync
    data class NeedsApproval(val requestId: String) : ThinkingSync
    data class Failed(val message: String) : ThinkingSync
}

data class ModelChoice(val model: String, val thinking: String, val chatModel: String?, val chatThinking: String?)

class ModelPicker(
    private val settings: SettingsStore,
    private val conversation: Conversation,
    private val scope: CoroutineScope,
    private val modelPrices: ModelPrices,
    modelStats: ModelStats,
) {
    private val _prices = MutableStateFlow<Map<String, ModelPrice>>(emptyMap())
    val prices: StateFlow<Map<String, ModelPrice>> = _prices.asStateFlow()
    val stats: StateFlow<Map<String, StepStats>> = modelStats.stats

    private val _models = MutableStateFlow<ModelList>(ModelList.Idle)
    val models: StateFlow<ModelList> = _models.asStateFlow()

    private val _sync = MutableStateFlow<ThinkingSync>(ThinkingSync.Idle)
    val sync: StateFlow<ThinkingSync> = _sync.asStateFlow()

    private var loadedFor = ""
    private var loading: Job? = null

    fun load() {
        if (_prices.value.isEmpty()) scope.launch { _prices.value = modelPrices.load() }
        val s = settings.current
        val key = listOf(s.provider.name, s.baseUrl, s.model, s.liveProgress).joinToString("|")
        if (key == loadedFor && _models.value is ModelList.Loaded) return
        if (s.provider == Provider.OPENCLAW && !s.liveProgress) {
            _models.value = ModelList.NeedsPairing
            return
        }
        loading?.cancel()
        _models.value = ModelList.Loading
        loading = scope.launch {
            _models.value = runCatching { options(s) }
                .fold({ loadedFor = key; ModelList.Loaded(it) }, { ModelList.Failed(it.message ?: it.javaClass.simpleName) })
        }
    }

    private suspend fun options(s: AppSettings): List<ModelOption> = if (s.provider == Provider.OPENCLAW) {
        val identity = DeviceIdentity.parse(s.gatewayDeviceKey) ?: error("not paired")
        OpenClawLink.listModels(s.baseUrl, identity, s.gatewayDeviceToken).map { ModelOption(it.ref, it.name, it.thinking) }
    } else {
        BackendFactory.create(s.backend).listModels().map { ModelOption(it, it, Thinking.levels(s.provider)) }
    }

    fun selectModel(value: String, asDefault: Boolean) = scope.launch {
        val s = settings.current
        val chat = conversation.current.value
        val thinking = chat.own(s)?.thinking
        settings.update { current ->
            val next = if (asDefault) {
                if (current.provider.isAgent) current.copy(agentModel = value) else current.copy(model = value)
            } else {
                current
            }
            next.withRecentModel(recentKey(current.provider, value))
        }
        val default = if (s.provider.isAgent) s.agentModel else s.model
        val chatModel = if (asDefault || value == default) null else value
        conversation.setOverrides(s.provider.name, chatModel, thinking)
    }

    fun selectThinking(value: String, asDefault: Boolean) = scope.launch {
        val s = settings.current
        val chat = conversation.current.value
        val model = chat.own(s)?.model
        if (asDefault) settings.update { it.copy(thinking = value) }
        val chatThinking = if (asDefault || value == s.thinking) null else value
        conversation.setOverrides(s.provider.name, model, chatThinking)
        applyThinking()
    }

    fun applyThinking() {
        scope.launch {
            val s = settings.current
            if (s.provider != Provider.OPENCLAW || !s.liveProgress) return@launch
            val chat = conversation.current.value
            val level = s.backendFor(null, chat.own(s)?.thinking).thinking
            val identity = DeviceIdentity.parse(s.gatewayDeviceKey) ?: return@launch
            _sync.value = ThinkingSync.Applying
            val result = OpenClawLink.setThinking(
                s.baseUrl, identity, s.gatewayDeviceToken, Agent.sessionUser(chat.id), OpenClawGateway.agentName(s.model), level,
            )
            _sync.value = when (result) {
                is LinkResult.Paired -> {
                    conversation.setSessionThinking(chat.id, level?.id.orEmpty())
                    ThinkingSync.Idle
                }
                is LinkResult.PairingRequired -> ThinkingSync.NeedsApproval(result.requestId)
                is LinkResult.Failed -> ThinkingSync.Failed(result.message)
            }
        }
    }

    fun choice(s: AppSettings, chat: Chat): ModelChoice {
        val own = chat.own(s)
        val default = if (s.provider.isAgent) s.agentModel else s.model
        return ModelChoice(
            model = own?.model ?: default,
            thinking = own?.thinking ?: s.thinking,
            chatModel = own?.model,
            chatThinking = own?.thinking,
        )
    }

    fun recents(s: AppSettings): List<String> {
        val prefix = s.provider.name + ":"
        return s.recentModels.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
    }

    companion object {
        fun recentKey(provider: Provider, value: String): String = if (value.isBlank()) "" else "${provider.name}:$value"

        private fun Chat.own(s: AppSettings): Chat? = takeIf { it.overrideProvider == s.provider.name }
    }
}
