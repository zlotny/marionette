package dev.marionette.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ConfirmationRequest(val id: Long, val tool: String, val prompt: String, val answer: CompletableDeferred<Boolean>)

class ConfirmationBroker {
    private val _pending = MutableStateFlow<ConfirmationRequest?>(null)
    val pending: StateFlow<ConfirmationRequest?> = _pending.asStateFlow()

    var voiceHandler: (suspend (ConfirmationRequest) -> Unit)? = null

    suspend fun confirm(tool: String, prompt: String, viaVoice: Boolean): Boolean {
        val request = ConfirmationRequest(System.nanoTime(), tool, prompt, CompletableDeferred())
        _pending.value = request
        try {
            val voice = voiceHandler
            if (viaVoice && voice != null) voice(request)
            return request.answer.await()
        } finally {
            if (_pending.value?.id == request.id) _pending.value = null
        }
    }

    fun answer(id: Long, approved: Boolean) {
        _pending.value?.takeIf { it.id == id }?.answer?.complete(approved)
    }
}
