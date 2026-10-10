package dev.marionette.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.marionette.MarionetteApp
import dev.marionette.agent.TurnSource

class HomeViewModel : ViewModel() {
    private val graph = MarionetteApp.graph

    val turns = graph.conversation.turns
    val chats = graph.conversation.chats
    val chat = graph.conversation.current
    val busy = graph.agent.busy
    val confirmation = graph.confirmations.pending
    val settings = graph.settings.settings
    val health = graph.health.backend
    val picker = ModelPicker(graph.settings, graph.conversation, viewModelScope)

    fun checkHealth() = graph.health.check()

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) graph.agent.submit(trimmed, TurnSource.TEXT)
    }

    fun stop() = graph.agent.cancel()

    fun newConversation() = graph.agent.resetSession()

    val chatActions = ChatActions(
        onNew = ::newConversation,
        onOpen = graph.conversation::open,
        onRename = graph.conversation::rename,
        onArchive = graph.conversation::archive,
        onDelete = graph.conversation::delete,
        onDeleteArchived = graph.conversation::deleteArchived,
    )

    fun answer(id: Long, approved: Boolean) = graph.confirmations.answer(id, approved)
}
