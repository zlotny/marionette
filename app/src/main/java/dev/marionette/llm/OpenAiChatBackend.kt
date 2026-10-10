package dev.marionette.llm

import dev.marionette.core.AppJson
import dev.marionette.core.Http
import dev.marionette.core.arr
import dev.marionette.core.await
import dev.marionette.core.int
import dev.marionette.core.obj
import dev.marionette.core.parseObject
import dev.marionette.core.string
import dev.marionette.core.text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class Reasoning(val level: Thinking, val nested: Boolean)

class OpenAiChatBackend(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val sessionUser: String? = null,
    private val reasoning: Reasoning? = null,
    private val modelHeader: String? = null,
    private val client: OkHttpClient = Http.client,
) : LlmBackend {

    override suspend fun complete(
        system: String,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        onText: (String) -> Unit,
    ): LlmReply = withContext(Dispatchers.IO) {
        val body = requestBody(system, messages, tools)
        val request = authorized(Request.Builder().url("${baseUrl.trimEnd('/')}/chat/completions"))
            .header("Accept", "text/event-stream")
            .post(AppJson.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON))
            .build()
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw response.toLlmException()
            val accumulator = Accumulator()
            val contentType = response.header("Content-Type").orEmpty()
            if (contentType.contains("event-stream")) {
                response.body.source().forEachSseEvent { event ->
                    if (event.data == "[DONE]") return@forEachSseEvent
                    val chunk = parseObject(event.data)
                    chunk["error"].obj()?.let { throw LlmException(null, it.string("message") ?: event.data) }
                    accumulator.chunk(chunk, onText)
                }
            } else {
                accumulator.full(parseObject(response.body.string()), onText)
            }
            accumulator.reply()
        }
    }

    override suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        val request = authorized(Request.Builder().url("${baseUrl.trimEnd('/')}/models")).get().build()
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw response.toLlmException()
            parseObject(response.body.string())["data"].arr().orEmpty()
                .mapNotNull { it.obj()?.string("id") }
                .map { it.removePrefix("models/") }
                .sorted()
        }
    }

    private fun authorized(builder: Request.Builder): Request.Builder {
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
        modelHeader?.let { builder.header(MODEL_HEADER, it) }
        return builder
    }

    fun requestBody(system: String, messages: List<ChatMessage>, tools: List<ToolSpec>): JsonObject = buildJsonObject {
        put("model", model)
        put("stream", true)
        putJsonObject("stream_options") { put("include_usage", true) }
        sessionUser?.let { put("user", it) }
        reasoning?.let {
            if (it.nested) putJsonObject("reasoning") { put("effort", it.level.effort) } else put("reasoning_effort", it.level.effort)
        }
        putJsonArray("messages") {
            if (system.isNotBlank()) {
                addJsonObject {
                    put("role", "system")
                    put("content", system)
                }
            }
            messages.forEach { message ->
                when (message) {
                    is ChatMessage.User -> addJsonObject {
                        put("role", "user")
                        put("content", message.text)
                    }
                    is ChatMessage.Assistant -> addJsonObject {
                        put("role", "assistant")
                        put("content", message.text)
                        if (message.toolCalls.isNotEmpty()) {
                            putJsonArray("tool_calls") {
                                message.toolCalls.forEach { call ->
                                    addJsonObject {
                                        put("id", call.id)
                                        put("type", "function")
                                        putJsonObject("function") {
                                            put("name", call.name)
                                            put("arguments", call.arguments)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    is ChatMessage.ToolResult -> addJsonObject {
                        put("role", "tool")
                        put("tool_call_id", message.callId)
                        put("content", message.content)
                    }
                }
            }
        }
        if (tools.isNotEmpty()) {
            putJsonArray("tools") {
                tools.forEach { tool ->
                    addJsonObject {
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", tool.parameters)
                        }
                    }
                }
            }
            put("tool_choice", "auto")
        }
    }

    private class PartialCall(var id: String = "", var name: String = "", val arguments: StringBuilder = StringBuilder())

    private class Accumulator {
        private val text = StringBuilder()
        private val calls = sortedMapOf<Int, PartialCall>()
        private var usage: Usage? = null

        fun chunk(chunk: JsonObject, onText: (String) -> Unit) {
            readUsage(chunk)
            val choice = chunk["choices"].arr()?.firstOrNull().obj() ?: return
            val delta = choice["delta"].obj() ?: return
            delta["content"].text()?.takeIf { it.isNotEmpty() }?.let {
                text.append(it)
                onText(it)
            }
            delta["tool_calls"].arr()?.forEachIndexed { position, element ->
                val call = element.obj() ?: return@forEachIndexed
                val index = call.int("index") ?: position
                val partial = calls.getOrPut(index) { PartialCall() }
                call.string("id")?.takeIf { it.isNotEmpty() }?.let { partial.id = it }
                call["function"].obj()?.let { function ->
                    function.string("name")?.takeIf { it.isNotEmpty() }?.let { partial.name = it }
                    function.string("arguments")?.let { partial.arguments.append(it) }
                }
            }
        }

        fun full(body: JsonObject, onText: (String) -> Unit) {
            readUsage(body)
            val message = body["choices"].arr()?.firstOrNull().obj()?.get("message").obj() ?: return
            message.string("content")?.takeIf { it.isNotEmpty() }?.let {
                text.append(it)
                onText(it)
            }
            message["tool_calls"].arr()?.forEachIndexed { index, element ->
                val call = element.obj() ?: return@forEachIndexed
                val function = call["function"].obj() ?: return@forEachIndexed
                calls[index] = PartialCall(
                    call.string("id").orEmpty(),
                    function.string("name").orEmpty(),
                    StringBuilder(function.string("arguments").orEmpty()),
                )
            }
        }

        private fun readUsage(json: JsonObject) {
            val u = json["usage"].obj() ?: return
            usage = Usage(u.int("prompt_tokens") ?: 0, u.int("completion_tokens") ?: 0)
        }

        fun reply(): LlmReply = LlmReply(
            text.toString(),
            calls.values.filter { it.name.isNotEmpty() }.mapIndexed { i, c ->
                ToolCall(c.id.ifEmpty { "call_$i" }, c.name, c.arguments.toString().ifBlank { "{}" })
            },
            usage,
        )
    }

    companion object {
        const val MODEL_HEADER = "x-openclaw-model"
        private val JSON = "application/json".toMediaType()
    }
}

