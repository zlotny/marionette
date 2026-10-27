package dev.marionette.llm

import kotlinx.serialization.json.JsonObject

data class ToolCall(val id: String, val name: String, val arguments: String)

sealed interface ChatMessage {
    data class User(val text: String) : ChatMessage
    data class Assistant(val text: String, val toolCalls: List<ToolCall> = emptyList(), val native: List<JsonObject> = emptyList()) : ChatMessage
    data class ToolResult(val callId: String, val name: String, val content: String, val imageJpeg: String? = null) : ChatMessage
}

data class ToolSpec(val name: String, val description: String, val parameters: JsonObject)

data class Usage(val inputTokens: Int, val outputTokens: Int)

data class LlmReply(val text: String, val toolCalls: List<ToolCall>, val usage: Usage?, val native: List<JsonObject> = emptyList())

class LlmException(val status: Int?, message: String, val retryAfterSeconds: Long? = null) : Exception(message)

interface LlmBackend {
    suspend fun complete(
        system: String,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        onText: (String) -> Unit = {},
    ): LlmReply

    suspend fun listModels(): List<String>
}
