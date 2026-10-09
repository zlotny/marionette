package dev.marionette.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.marionette.MarionetteApp
import dev.marionette.llm.BackendConfig
import dev.marionette.llm.BackendFactory
import dev.marionette.llm.BackendProbe
import dev.marionette.llm.ProbeResult
import dev.marionette.llm.Provider
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BackendForm(
    val provider: Provider,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val models: List<String> = emptyList(),
    val loadingModels: Boolean = false,
    val modelsError: String? = null,
    val testing: Boolean = false,
    val probe: ProbeResult? = null,
    val saved: Boolean = false,
) {
    val config get() = BackendConfig(provider, baseUrl.trim(), model.trim(), apiKey.trim())
    val canTest get() = model.isNotBlank() && baseUrl.isNotBlank() && (!provider.keyRequired || apiKey.isNotBlank())
    val passed get() = probe is ProbeResult.Passed
}

class BackendViewModel : ViewModel() {
    private val settings = MarionetteApp.graph.settings
    private val drafts = mutableMapOf<Provider, BackendForm>()
    private var modelsJob: Job? = null

    private val _form = MutableStateFlow(
        settings.current.let { BackendForm(it.provider, it.baseUrl, it.apiKey, it.model, probe = null) },
    )
    val form: StateFlow<BackendForm> = _form.asStateFlow()

    fun selectProvider(provider: Provider) {
        val current = _form.value
        if (provider == current.provider) return
        drafts[current.provider] = current
        val saved = settings.current
        _form.value = drafts[provider] ?: if (saved.provider == provider) {
            BackendForm(provider, saved.baseUrl, saved.apiKey, saved.model)
        } else {
            BackendForm(provider, provider.defaultBaseUrl, "", provider.suggestedModel)
        }
    }

    fun setBaseUrl(value: String) = edit { it.copy(baseUrl = value) }

    fun setApiKey(value: String) = edit { it.copy(apiKey = value) }

    fun setModel(value: String) = edit { it.copy(model = value) }

    private fun edit(transform: (BackendForm) -> BackendForm) = _form.update { transform(it).copy(probe = null, saved = false) }

    fun loadModels() {
        modelsJob?.cancel()
        val form = _form.value
        _form.update { it.copy(loadingModels = true, modelsError = null) }
        modelsJob = viewModelScope.launch {
            val result = runCatching { BackendFactory.create(form.config.copy(model = form.model.ifBlank { "probe" })).listModels() }
            _form.update {
                it.copy(
                    loadingModels = false,
                    models = result.getOrDefault(emptyList()),
                    modelsError = result.exceptionOrNull()?.message,
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
        val config = _form.value.config
        viewModelScope.launch {
            settings.update { it.copy(provider = config.provider, baseUrl = config.baseUrl, apiKey = config.apiKey, model = config.model) }
            _form.update { it.copy(saved = true) }
        }
    }
}
