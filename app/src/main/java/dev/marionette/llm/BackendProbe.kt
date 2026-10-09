package dev.marionette.llm

import dev.marionette.core.parseObject
import dev.marionette.core.string
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add

sealed interface ProbeResult {
    data class Passed(val latencyMillis: Long) : ProbeResult
    data class NoToolCall(val reply: String) : ProbeResult
    data class Failed(val message: String) : ProbeResult
}

object BackendProbe {
    private const val NONCE = "marionette-7f3a"

    val pingTool = ToolSpec(
        name = "ping",
        description = "Connectivity check. Call it with the token the user gives you.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("token") { put("type", "string") }
            }
            putJsonArray("required") { add("token") }
        },
    )

    suspend fun run(backend: LlmBackend): ProbeResult = runCatching {
        val started = System.currentTimeMillis()
        val reply = backend.complete(
            system = "You test tool calling. Always call the requested tool. Never answer in text.",
            messages = listOf(ChatMessage.User("Call the ping tool with token \"$NONCE\".")),
            tools = listOf(pingTool),
        )
        val call = reply.toolCalls.firstOrNull { it.name == "ping" }
        when {
            call != null && parseObject(call.arguments).string("token") == NONCE -> ProbeResult.Passed(System.currentTimeMillis() - started)
            call != null -> ProbeResult.Passed(System.currentTimeMillis() - started)
            else -> ProbeResult.NoToolCall(reply.text)
        }
    }.getOrElse { ProbeResult.Failed(it.message ?: it.javaClass.simpleName) }
}
