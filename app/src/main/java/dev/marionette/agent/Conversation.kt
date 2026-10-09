package dev.marionette.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class TurnSource { TEXT, VOICE, DEBUG }

enum class TurnStatus { THINKING, ANSWERED, FAILED, CANCELLED }

enum class StepStatus { RUNNING, DONE, FAILED, DECLINED }

data class ToolStep(val callId: String, val tool: String, val summary: String, val status: StepStatus, val detail: String? = null)

data class Turn(
    val id: Long,
    val input: String,
    val source: TurnSource,
    val steps: List<ToolStep> = emptyList(),
    val reply: String = "",
    val status: TurnStatus = TurnStatus.THINKING,
    val error: String? = null,
)

class Conversation {
    private val _turns = MutableStateFlow<List<Turn>>(emptyList())
    val turns: StateFlow<List<Turn>> = _turns.asStateFlow()

    fun start(turn: Turn) = _turns.update { it + turn }

    fun update(id: Long, transform: (Turn) -> Turn) = _turns.update { list -> list.map { if (it.id == id) transform(it) else it } }

    fun clear() = _turns.update { emptyList() }
}
