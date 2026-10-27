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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class AnthropicBackend(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val maxTokens: Int = 16_000,
    private val thinking: Thinking? = null,
    private val client: OkHttpClient = Http.agent,
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
        thinking?.let { level -> putJsonObject("output_config") { put("effort", level.id) } }
        if (system.isNotBlank()) put("system", system)
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
                        is ChatMessage.Assistant -> if (message.native.isNotEmpty()) {
                            message.native.forEach { add(it) }
                        } else {
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
                            if (message.imageJpeg == null) {
                                put("content", message.content)
                            } else {
                                putJsonArray("content") {
                                    addJsonObject {
                                        put("type", "text")
                                        put("text", message.content)
                                    }
                                    addJsonObject {
                                        put("type", "image")
                                        putJsonObject("source") {
                                            put("type", "base64")
                                            put("media_type", "image/jpeg")
                                            put("data", message.imageJpeg)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private class Block(val start: JsonObject, val text: StringBuilder = StringBuilder(), val signature: StringBuilder = StringBuilder()) {
        val type: String = start.string("type").orEmpty()
        val id: String = start.string("id").orEmpty()
        val name: String = start.string("name").orEmpty()

        fun native(): JsonObject? = when (type) {
            "text" -> buildJsonObject {
                put("type", "text")
                put("text", text.toString())
            }.takeIf { text.isNotBlank() }
            "tool_use" -> buildJsonObject {
                put("type", "tool_use")
                put("id", id)
                put("name", name)
                put("input", parseObject(text.toString().ifBlank { "{}" }))
            }
            "thinking" -> JsonObject(
                start + mapOf(
                    "thinking" to JsonPrimitive(start.string("thinking").orEmpty() + text),
                    "signature" to JsonPrimitive(start.string("signature").orEmpty() + signature),
                ),
            )
            "redacted_thinking" -> start
            else -> null
        }
    }

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
                    blocks[index] = Block(block)
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
                        "thinking_delta" -> delta.string("thinking")?.let { block.text.append(it) }
                        "signature_delta" -> delta.string("signature")?.let { block.signature.append(it) }
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
            blocks.values.mapNotNull { it.native() },
        )
    }

    companion object {
        private val JSON = "application/json".toMediaType()
    }
}
