package dev.marionette.agent

import android.content.Context
import dev.marionette.core.AppJson
import dev.marionette.core.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File

class TraceWriter(context: Context) {
    val directory = File(context.filesDir, "traces").apply { mkdirs() }

    suspend fun write(turnId: Long, trace: JsonObject) = withContext(Dispatchers.IO) {
        val text = AppJson.encodeToString(JsonObject.serializer(), trace)
        Logger.i("TRACE $text")
        File(directory, "turn-$turnId.json").writeText(text)
        directory.listFiles()?.sortedByDescending { it.name }?.drop(MAX_TRACES)?.forEach { it.delete() }
    }

    fun latest(): File? = directory.listFiles()?.maxByOrNull { it.name }

    companion object {
        private const val MAX_TRACES = 50
    }
}
