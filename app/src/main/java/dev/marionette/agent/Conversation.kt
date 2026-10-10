package dev.marionette.agent

import dev.marionette.core.AppJson
import dev.marionette.core.Logger
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

@Serializable
enum class TurnSource { TEXT, VOICE, DEBUG }

@Serializable
enum class TurnStatus { THINKING, ANSWERED, FAILED, CANCELLED }

@Serializable
enum class StepStatus { RUNNING, DONE, FAILED, DECLINED }

@Serializable
sealed interface TurnStep

@Serializable
data class Note(val text: String) : TurnStep

@Serializable
data class ToolStep(val callId: String, val tool: String, val summary: String, val status: StepStatus, val detail: String? = null) : TurnStep

@Serializable
data class Turn(
    val id: Long,
    val input: String,
    val source: TurnSource,
    val steps: List<TurnStep> = emptyList(),
    val reply: String = "",
    val status: TurnStatus = TurnStatus.THINKING,
    val error: String? = null,
    val quiet: Boolean = false,
)

@Serializable
data class Chat(
    val id: String,
    val title: String = "",
    val created: Long = System.currentTimeMillis(),
    val updated: Long = created,
    val archived: Boolean = false,
    val model: String? = null,
    val thinking: String? = null,
    val overrideProvider: String? = null,
    val sessionThinking: String = "",
    val turns: List<Turn> = emptyList(),
) {
    fun matches(query: String): Boolean = query.isBlank() ||
        title.contains(query, ignoreCase = true) ||
        turns.any { it.input.contains(query, ignoreCase = true) || it.reply.contains(query, ignoreCase = true) }
}

class Conversation(private val directory: File? = null, private val scope: CoroutineScope? = null) {
    private val _chats = MutableStateFlow<List<Chat>>(emptyList())
    val chats: StateFlow<List<Chat>> = _chats.asStateFlow()

    private val _current = MutableStateFlow(Chat(newId()))
    val current: StateFlow<Chat> = _current.asStateFlow()

    private val _turns = MutableStateFlow<List<Turn>>(emptyList())
    val turns: StateFlow<List<Turn>> = _turns.asStateFlow()

    private val io = Mutex()

    init {
        directory?.mkdirs()
        scope?.launch(Dispatchers.IO) { load() }
    }

    fun start(turn: Turn) = edit { chat ->
        chat.copy(title = chat.title.ifBlank { titleFrom(turn.input) }, turns = chat.turns + turn, updated = System.currentTimeMillis())
    }

    fun update(id: Long, transform: (Turn) -> Turn) = edit(persist = { chat -> chat.turns.any { it.id == id && it.status != TurnStatus.THINKING } }) { chat ->
        val turns = chat.turns.map { turn -> if (turn.id == id) transform(turn) else turn }
        val settled = turns.any { it.id == id && it.status != TurnStatus.THINKING }
        chat.copy(turns = turns, updated = if (settled) System.currentTimeMillis() else chat.updated)
    }

    fun clear() = newChat()

    fun newChat() {
        if (_current.value.turns.isEmpty()) return
        select(Chat(newId()))
    }

    fun open(id: String) {
        if (id == _current.value.id) return
        _chats.value.firstOrNull { it.id == id }?.let(::select)
    }

    fun rename(id: String, title: String) = change(id) { it.copy(title = title.trim()) }

    fun archive(id: String, archived: Boolean) {
        change(id) { it.copy(archived = archived) }
        if (archived && id == _current.value.id) select(Chat(newId()))
    }

    fun setOverrides(provider: String, model: String?, thinking: String?) = edit {
        it.copy(model = model, thinking = thinking, overrideProvider = provider.takeIf { model != null || thinking != null })
    }

    fun setSessionThinking(id: String, thinking: String) = change(id) { it.copy(sessionThinking = thinking) }

    fun delete(id: String) {
        _chats.update { list -> list.filterNot { it.id == id } }
        if (id == _current.value.id) select(Chat(newId()))
        directory?.let { dir -> scope?.launch(Dispatchers.IO) { io.withLock { File(dir, "$id.json").delete() } } }
    }

    fun deleteArchived() = _chats.value.filter { it.archived }.forEach { delete(it.id) }

    private fun select(chat: Chat) {
        _current.value = chat
        _turns.value = chat.turns
    }

    private fun change(id: String, transform: (Chat) -> Chat) {
        if (id == _current.value.id) {
            edit(transform = transform)
            return
        }
        val changed = _chats.value.firstOrNull { it.id == id }?.let(transform) ?: return
        _chats.update { list -> list.map { if (it.id == id) changed else it } }
        save(changed)
    }

    private fun edit(persist: (Chat) -> Boolean = { true }, transform: (Chat) -> Chat) {
        val next = _current.updateAndGet(transform)
        _turns.value = _current.value.turns
        if (next.turns.isEmpty()) return
        val settled = persist(next)
        if (!settled && _chats.value.any { it.id == next.id }) return
        _chats.update { list -> (list.filterNot { it.id == next.id } + next).sortedByDescending { it.updated } }
        if (settled) save(next)
    }

    private fun save(chat: Chat) {
        val dir = directory ?: return
        scope?.launch(Dispatchers.IO) {
            io.withLock {
                val latest = _chats.value.firstOrNull { it.id == chat.id } ?: return@withLock
                runCatching {
                    val temp = File(dir, "${chat.id}.json.tmp")
                    temp.writeText(AppJson.encodeToString(Chat.serializer(), latest))
                    if (!temp.renameTo(File(dir, "${chat.id}.json"))) error("rename failed")
                }.onFailure { Logger.w("Chat save failed", it) }
            }
        }
    }

    private suspend fun load() {
        val dir = directory ?: return
        val loaded = io.withLock {
            dir.listFiles { file -> file.extension == "json" }.orEmpty().mapNotNull { file ->
                runCatching { AppJson.decodeFromString(Chat.serializer(), file.readText()) }
                    .onFailure { Logger.w("Chat load failed: ${file.name}", it) }
                    .getOrNull()
                    ?.let { chat -> chat.copy(turns = chat.turns.map { if (it.status == TurnStatus.THINKING) it.copy(status = TurnStatus.CANCELLED) else it }) }
            }
        }
        _chats.update { current -> (current + loaded.filterNot { chat -> current.any { it.id == chat.id } }).sortedByDescending { it.updated } }
    }

    companion object {
        private const val TITLE_LENGTH = 48

        fun newId(): String = UUID.randomUUID().toString()

        fun titleFrom(input: String): String {
            val line = input.trim().lineSequence().firstOrNull().orEmpty()
            return if (line.length <= TITLE_LENGTH) line else line.take(TITLE_LENGTH).substringBeforeLast(' ').ifBlank { line.take(TITLE_LENGTH) } + "…"
        }
    }
}
