package dev.marionette.agent

import android.content.Context
import dev.marionette.core.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class ActionEntry(
    val timestamp: Long,
    val tool: String,
    val arguments: String,
    val result: String,
    val ok: Boolean,
    val durationMillis: Long,
)

class ActionLog(context: Context) {
    private val file = File(context.filesDir, "action-log.jsonl")
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<ActionEntry>> = _entries.asStateFlow()

    suspend fun append(entry: ActionEntry) = withContext(Dispatchers.IO) {
        file.appendText(AppJson.encodeToString(ActionEntry.serializer(), entry) + "\n")
        _entries.update { (listOf(entry) + it).take(MAX_ENTRIES) }
        if (file.length() > MAX_FILE_BYTES) {
            file.writeText(_entries.value.reversed().joinToString("") { AppJson.encodeToString(ActionEntry.serializer(), it) + "\n" })
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        file.delete()
        _entries.value = emptyList()
    }

    private fun load(): List<ActionEntry> = runCatching {
        file.takeIf { it.exists() }?.readLines().orEmpty()
            .mapNotNull { line -> runCatching { AppJson.decodeFromString(ActionEntry.serializer(), line) }.getOrNull() }
            .reversed()
            .take(MAX_ENTRIES)
    }.getOrDefault(emptyList())

    companion object {
        private const val MAX_ENTRIES = 300
        private const val MAX_FILE_BYTES = 512 * 1024
    }
}
