package dev.marionette.llm

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.BufferedSource

data class SseEvent(val event: String?, val data: String)

suspend fun BufferedSource.forEachSseEvent(handle: (SseEvent) -> Unit) {
    var event: String? = null
    val data = StringBuilder()
    while (true) {
        currentCoroutineContext().ensureActive()
        val line = readUtf8Line() ?: break
        when {
            line.isEmpty() -> {
                if (data.isNotEmpty()) handle(SseEvent(event, data.toString()))
                event = null
                data.clear()
            }
            line.startsWith(":") -> Unit
            line.startsWith("event:") -> event = line.removePrefix("event:").trim()
            line.startsWith("data:") -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.removePrefix("data:").removePrefix(" "))
            }
        }
    }
    if (data.isNotEmpty()) handle(SseEvent(event, data.toString()))
}
