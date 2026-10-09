package dev.marionette.ui.home

import androidx.lifecycle.ViewModel
import dev.marionette.MarionetteApp
import dev.marionette.agent.TurnSource

class HomeViewModel : ViewModel() {
    private val graph = MarionetteApp.graph

    val turns = graph.conversation.turns
    val busy = graph.agent.busy
    val confirmation = graph.confirmations.pending
    val settings = graph.settings.settings

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) graph.agent.submit(trimmed, TurnSource.TEXT)
    }

    fun stop() = graph.agent.cancel()

    fun newConversation() = graph.agent.resetSession()

    fun answer(id: Long, approved: Boolean) = graph.confirmations.answer(id, approved)
}
