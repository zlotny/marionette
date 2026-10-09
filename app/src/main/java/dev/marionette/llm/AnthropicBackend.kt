package dev.marionette.llm

import dev.marionette.core.AppJson
import dev.marionette.core.Http
import dev.marionette.core.arr
import dev.marionette.core.await
import dev.marionette.core.int
import dev.marionette.core.obj
import dev.marionette.core.parseObject
import dev.marionette.core.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class AnthropicBackend(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val maxTokens: Int = 1024,
    private val client: OkHttpClient = Http.client,
) : LlmBackend {

    override suspend fun complete(
        system: String,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        onText: (String) -> Unit,
    ): LlmReply = withContext(Dispatchers.IO) {
        val request = headers(Request.Builder().url("${baseUrl.trimEnd('/')}/messages"))
            .post(AppJson.encodeToString(JsonObject.serializer(), requestBody(system, messages, tools)).toRequestBody(JSON))
            .build()
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw response.toLlmException()
            val accumulator = Accumulator()
            response.body.source().forEachSseEvent { event -> accumulator.event(parseObject(event.data), onText) }
            accumulator.reply()
        }
    }

    override suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        val request = headers(Request.Builder().url("${baseUrl.trimEnd('/')}/models?limit=100")).get().build()
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw response.toLlmException()
            parseObject(response.body.string())["data"].arr().orEmpty().mapNotNull { it.obj()?.string("id") }
        }
    }

    private fun headers(builder: Request.Builder) = builder
        .header("x-api-key", apiKey)
        .header("anthropic-version", "2023-06-01")

    fun requestBody(system: String, messages: List<ChatMessage>, tools: List<ToolSpec>): JsonObject = buildJsonObject {
        put("model", model)
        put("max_tokens", maxTokens)
        put("stream", true)
        put("system", system)
        putJsonArray("messages") { groupTurns(messages).forEach { turn -> addTurn(turn) } }
        if (tools.isNotEmpty()) {
            putJsonArray("tools") {
                tools.forEach { tool ->
                    addJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("input_schema", tool.parameters)
                    }
                }
            }
        }
    }

    private fun groupTurns(messages: List<ChatMessage>): List<List<ChatMessage>> {
        val turns = mutableListOf<MutableList<ChatMessage>>()
        messages.forEach { message ->
            val role = if (message is ChatMessage.Assistant) "assistant" else "user"
            val last = turns.lastOrNull()
            val lastRole = last?.let { if (it.first() is ChatMessage.Assistant) "assistant" else "user" }
            if (last != null && lastRole == role) last += message else turns += mutableListOf(message)
        }
        return turns
    }

    private fun JsonArrayBuilder.addTurn(turn: List<ChatMessage>) {
        addJsonObject {
            put("role", if (turn.first() is ChatMessage.Assistant) "assistant" else "user")
            putJsonArray("content") {
                turn.forEach { message ->
                    when (message) {
                        is ChatMessage.User -> addJsonObject {
                            put("type", "text")
                            put("text", message.text)
                        }
                        is ChatMessage.Assistant -> {
                            if (message.text.isNotBlank()) addJsonObject {
                                put("type", "text")
                                put("text", message.text)
                            }
                            message.toolCalls.forEach { call ->
                                addJsonObject {
                                    put("type", "tool_use")
                                    put("id", call.id)
                                    put("name", call.name)
                                    put("input", parseObject(call.arguments))
                                }
                            }
                        }
                        is ChatMessage.ToolResult -> addJsonObject {
                            put("type", "tool_result")
                            put("tool_use_id", message.callId)
                            put("content", message.content)
                        }
                    }
                }
            }
        }
    }

    private class Block(val type: String, val id: String, val name: String, val text: StringBuilder = StringBuilder())

    private class Accumulator {
        private val blocks = sortedMapOf<Int, Block>()
        private var input = 0
        private var output = 0

        fun event(json: JsonObject, onText: (String) -> Unit) {
            when (json.string("type")) {
                "message_start" -> json["message"].obj()?.get("usage").obj()?.let { input = it.int("input_tokens") ?: 0 }
                "content_block_start" -> {
                    val index = json.int("index") ?: return
                    val block = json["content_block"].obj() ?: return
                    blocks[index] = Block(block.string("type").orEmpty(), block.string("id").orEmpty(), block.string("name").orEmpty())
                }
                "content_block_delta" -> {
                    val block = blocks[json.int("index") ?: return] ?: return
                    val delta = json["delta"].obj() ?: return
                    when (delta.string("type")) {
                        "text_delta" -> delta.string("text")?.let {
                            block.text.append(it)
                            onText(it)
                        }
                        "input_json_delta" -> delta.string("partial_json")?.let { block.text.append(it) }
                    }
                }
                "message_delta" -> json["usage"].obj()?.let { output = it.int("output_tokens") ?: output }
                "error" -> throw LlmException(null, json["error"].obj()?.string("message") ?: json.toString())
            }
        }

        fun reply() = LlmReply(
            blocks.values.filter { it.type == "text" }.joinToString("") { it.text },
            blocks.values.filter { it.type == "tool_use" }.map { ToolCall(it.id, it.name, it.text.toString().ifBlank { "{}" }) },
            Usage(input, output),
        )
    }

    companion object {
        private val JSON = "application/json".toMediaType()
    }
}
