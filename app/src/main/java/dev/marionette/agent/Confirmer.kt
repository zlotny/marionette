package dev.marionette.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ConfirmationRequest(val id: Long, val tool: String, val prompt: String, val answer: CompletableDeferred<Boolean>) {
    @Volatile var remark: String? = null
}

data class ConfirmationAnswer(val approved: Boolean, val remark: String?)

class ConfirmationBroker {
    private val _pending = MutableStateFlow<ConfirmationRequest?>(null)
    val pending: StateFlow<ConfirmationRequest?> = _pending.asStateFlow()

    var voiceHandler: (suspend (ConfirmationRequest) -> Unit)? = null

    suspend fun confirm(tool: String, prompt: String, viaVoice: Boolean): ConfirmationAnswer {
        val request = ConfirmationRequest(System.nanoTime(), tool, prompt, CompletableDeferred())
        _pending.value = request
        try {
            val voice = voiceHandler
            return coroutineScope {
                val listening = if (viaVoice && voice != null) launch { voice(request) } else null
                val approved = request.answer.await()
                listening?.cancel()
                ConfirmationAnswer(approved, request.remark)
            }
        } finally {
            if (_pending.value?.id == request.id) _pending.value = null
        }
    }

    fun answer(id: Long, approved: Boolean) {
        _pending.value?.takeIf { it.id == id }?.answer?.complete(approved)
    }
}
