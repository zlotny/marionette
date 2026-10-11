package dev.marionette.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.marionette.MarionetteApp
import dev.marionette.llm.BackendConfig
import dev.marionette.llm.BackendFactory
import dev.marionette.llm.BackendProbe
import dev.marionette.llm.DeviceIdentity
import dev.marionette.llm.GatewayCheck
import dev.marionette.llm.LinkResult
import dev.marionette.llm.OpenClawGateway
import dev.marionette.llm.OpenClawLink
import dev.marionette.llm.ProbeResult
import dev.marionette.llm.Provider
import dev.marionette.settings.Credential
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface PairState {
    data object Unpaired : PairState
    data class Waiting(val requestId: String?) : PairState
    data object Paired : PairState
    data class Failed(val message: String) : PairState
}

data class BackendForm(
    val provider: Provider,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val continueConversation: Boolean = false,
    val models: List<String> = emptyList(),
    val loadingModels: Boolean = false,
    val modelsError: String? = null,
    val checking: Boolean = false,
    val gateway: GatewayCheck? = null,
    val testing: Boolean = false,
    val probe: ProbeResult? = null,
    val saved: Boolean = false,
    val pairing: PairState = PairState.Unpaired,
) {
    val isGateway get() = provider == Provider.OPENCLAW
    val gatewayUrl get() = OpenClawGateway.normalize(baseUrl)
    val config get() = BackendConfig(provider, if (isGateway) gatewayUrl else baseUrl.trim(), model.trim(), apiKey.trim())
    val gatewayReady get() = gateway is GatewayCheck.Ready
    val showSecret get() = !isGateway || apiKey.isNotBlank() || gateway is GatewayCheck.NeedsSecret || gateway is GatewayCheck.Rejected
    val canTest get() = when {
        isGateway -> gatewayReady && model.isNotBlank()
        else -> model.isNotBlank() && baseUrl.isNotBlank() && (!provider.keyRequired || apiKey.isNotBlank())
    }
    val passed get() = probe is ProbeResult.Passed
}

class BackendViewModel : ViewModel() {
    private val settings = MarionetteApp.graph.settings
    private val drafts = mutableMapOf<Provider, BackendForm>()
    private var modelsJob: Job? = null
    private var checkJob: Job? = null
    private var pairJob: Job? = null

    private val _form = MutableStateFlow(saved(settings.current.provider))
    val form: StateFlow<BackendForm> = _form.asStateFlow()

    init {
        if (_form.value.isGateway && _form.value.baseUrl.isNotBlank()) checkGateway()
    }

    private fun saved(provider: Provider): BackendForm {
        val s = settings.current
        return if (s.provider == provider) {
            BackendForm(provider, s.baseUrl, s.apiKey, s.model, s.continueConversation, pairing = pairState(s.baseUrl))
        } else {
            BackendForm(provider, provider.defaultBaseUrl, s.key(Credential.of(provider)), provider.suggestedModel, s.continueConversation)
        }
    }

    fun discard() {
        modelsJob?.cancel()
        checkJob?.cancel()
        pairJob?.cancel()
        drafts.clear()
        _form.value = saved(settings.current.provider)
    }

    fun selectProvider(provider: Provider) {
        val current = _form.value
        if (provider == current.provider) return
        drafts[current.provider] = current
        modelsJob?.cancel()
        checkJob?.cancel()
        pairJob?.cancel()
        _form.value = drafts[provider] ?: saved(provider)
    }

    fun setBaseUrl(value: String) = edit(resetGateway = true) { it.copy(baseUrl = value) }

    fun setApiKey(value: String) {
        edit(resetGateway = false) { it.copy(apiKey = value) }
        val form = _form.value
        if (!form.isGateway && form.provider.keyRequired && value.isNotBlank()) {
            modelsJob?.cancel()
            modelsJob = viewModelScope.launch {
                delay(KEY_DEBOUNCE_MILLIS)
                fetchModels()
            }
        }
    }

    fun setModel(value: String) = edit(resetGateway = false) { it.copy(model = value) }

    fun setContinueConversation(value: Boolean) = edit(resetGateway = false) { it.copy(continueConversation = value) }

    private fun edit(resetGateway: Boolean, transform: (BackendForm) -> BackendForm) = _form.update {
        val next = transform(it).copy(probe = null, saved = false)
        if (resetGateway) {
            pairJob?.cancel()
            next.copy(gateway = null, models = emptyList(), pairing = pairState(next.gatewayUrl))
        } else {
            next
        }
    }

    private fun pairState(url: String): PairState {
        val s = settings.current
        return if (s.gatewayDeviceToken.isNotBlank() && s.gatewayPairedUrl == url) PairState.Paired else PairState.Unpaired
    }

    fun pair() {
        val form = _form.value
        if (!form.gatewayReady) return
        pairJob?.cancel()
        _form.update { it.copy(pairing = PairState.Waiting(null)) }
        pairJob = viewModelScope.launch {
            val identity = DeviceIdentity.parse(settings.current.gatewayDeviceKey)
                ?: DeviceIdentity.create().also { created -> settings.update { it.copy(gatewayDeviceKey = created.serialize()) } }
            repeat(PAIR_ATTEMPTS) {
                when (val result = OpenClawLink.pair(form.gatewayUrl, form.apiKey.trim(), identity, "")) {
                    is LinkResult.Paired -> {
                        settings.update { it.copy(gatewayDeviceToken = result.token, gatewayPairedUrl = form.gatewayUrl) }
                        _form.update { it.copy(pairing = PairState.Paired) }
                        return@launch
                    }
                    is LinkResult.PairingRequired -> _form.update { it.copy(pairing = PairState.Waiting(result.requestId)) }
                    is LinkResult.Failed -> {
                        _form.update { it.copy(pairing = PairState.Failed(result.message)) }
                        return@launch
                    }
                    LinkResult.TokenOutdated -> {
                        _form.update { it.copy(pairing = PairState.Unpaired) }
                        return@launch
                    }
                }
                delay(PAIR_POLL_MILLIS)
            }
            _form.update { it.copy(pairing = PairState.Unpaired) }
        }
    }

    fun unpair() {
        pairJob?.cancel()
        viewModelScope.launch { settings.update { it.copy(gatewayDeviceToken = "", gatewayPairedUrl = "") } }
        _form.update { it.copy(pairing = PairState.Unpaired) }
    }

    fun loadModels() {
        modelsJob?.cancel()
        modelsJob = viewModelScope.launch { fetchModels() }
    }

    private suspend fun fetchModels() {
        val form = _form.value
        _form.update { it.copy(loadingModels = true, modelsError = null) }
        val result = runCatching { BackendFactory.create(form.config.copy(model = form.model.ifBlank { "probe" })).listModels() }
        _form.update { current ->
            val models = result.getOrDefault(emptyList())
            current.copy(
                loadingModels = false,
                models = models,
                modelsError = result.exceptionOrNull()?.message,
                model = current.model.ifBlank { models.firstOrNull { it == current.provider.suggestedModel }.orEmpty() },
            )
        }
    }

    fun checkGateway() {
        val form = _form.value
        if (form.gatewayUrl.isBlank()) return
        checkJob?.cancel()
        _form.update { it.copy(checking = true, gateway = null, probe = null) }
        checkJob = viewModelScope.launch {
            val result = OpenClawGateway.check(form.gatewayUrl, form.apiKey.trim())
            _form.update { current ->
                val agents = (result as? GatewayCheck.Ready)?.agents.orEmpty()
                current.copy(
                    checking = false,
                    gateway = result,
                    models = agents,
                    model = current.model.takeIf { it in agents } ?: agents.firstOrNull().orEmpty(),
                )
            }
        }
    }

    fun test() {
        val form = _form.value
        if (!form.canTest) return
        _form.update { it.copy(testing = true, probe = null) }
        viewModelScope.launch {
            val result = BackendProbe.run(BackendFactory.create(form.config))
            _form.update { it.copy(testing = false, probe = result) }
            if (result is ProbeResult.Passed) save()
        }
    }

    fun save() {
        val form = _form.value
        val config = form.config
        viewModelScope.launch {
            settings.update {
                it.copy(
                    provider = config.provider,
                    baseUrl = config.baseUrl,
                    model = config.model,
                    continueConversation = form.continueConversation,
                ).withKey(Credential.of(config.provider), config.apiKey)
            }
            _form.update { it.copy(saved = true, baseUrl = if (it.isGateway) config.baseUrl else it.baseUrl) }
        }
    }

    private companion object {
        const val KEY_DEBOUNCE_MILLIS = 700L
        const val PAIR_POLL_MILLIS = 3_000L
        const val PAIR_ATTEMPTS = 100
    }
}
