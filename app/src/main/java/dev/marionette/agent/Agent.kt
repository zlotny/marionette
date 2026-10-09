package dev.marionette.agent

import android.content.Context
import dev.marionette.R
import dev.marionette.core.Logger
import dev.marionette.core.jsonOf
import dev.marionette.core.parseObject
import dev.marionette.launch.Launcher
import dev.marionette.llm.BackendFactory
import dev.marionette.llm.ChatMessage
import dev.marionette.llm.LlmException
import dev.marionette.llm.ToolCall
import dev.marionette.settings.SettingsStore
import dev.marionette.tools.ToolContext
import dev.marionette.tools.ToolRegistry
import dev.marionette.tools.ToolResults
import dev.marionette.tools.isError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.util.UUID

class Agent(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val registry: ToolRegistry,
    private val device: DeviceSnapshotProvider,
    private val launcher: Launcher,
    private val prompt: SystemPrompt,
    private val confirmations: ConfirmationBroker,
    private val actionLog: ActionLog,
    private val traces: TraceWriter,
    val conversation: Conversation,
) {
    private val history = mutableListOf<ChatMessage>()
    private var lastInteraction = 0L
    private var sessionId = UUID.randomUUID().toString()
    private val lock = Mutex()
    private var current: Job? = null

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun submit(input: String, source: TurnSource, onReply: suspend (Turn) -> Unit = {}) {
        current?.cancel()
        current = scope.launch {
            val turn = run(input, source)
            onReply(turn)
        }
    }

    fun cancel() {
        current?.cancel()
    }

    fun resetSession() {
        history.clear()
        sessionId = UUID.randomUUID().toString()
        conversation.clear()
    }

    suspend fun run(input: String, source: TurnSource): Turn = lock.withLock {
        val id = System.currentTimeMillis()
        conversation.start(Turn(id, input, source))
        _busy.value = true
        val trace = TraceBuilder(id, input, source)
        try {
            val result = loop(id, input, source, trace)
            conversation.update(id) { it.copy(reply = result, status = TurnStatus.ANSWERED) }
            trace.reply = result
        } catch (e: CancellationException) {
            conversation.update(id) { it.copy(status = TurnStatus.CANCELLED) }
            trace.error = "cancelled"
            throw e
        } catch (e: Exception) {
            Logger.w("Turn failed", e)
            val message = friendlyError(e)
            conversation.update(id) { it.copy(reply = message, status = TurnStatus.FAILED, error = e.message) }
            trace.error = e.message ?: e.javaClass.simpleName
            trace.reply = message
        } finally {
            _busy.value = false
            lastInteraction = System.currentTimeMillis()
            runCatching { traces.write(id, trace.build()) }
        }
        conversation.turns.value.first { it.id == id }
    }

    private suspend fun loop(id: Long, input: String, source: TurnSource, trace: TraceBuilder): String {
        val s = settings.current
        if (System.currentTimeMillis() - lastInteraction > SESSION_IDLE_MILLIS) {
            history.clear()
            sessionId = UUID.randomUUID().toString()
        }
        val snapshot = device.snapshot(s.contextFields, s.language)
        val contextBlock = ContextBlock.render(snapshot, s.contextFields)
        val system = prompt.render(contextBlock)
        trace.contextBlock = contextBlock
        trace.backend = "${s.provider.name} ${s.model}"
        val backend = BackendFactory.create(s.backend, sessionUser = "marionette-$sessionId")
        val toolContext = ToolContext(context, device, launcher, s)
        val specs = registry.specs(s.disabledTools)
        trace.offeredTools = specs.map { it.name }
        history += ChatMessage.User(input)
        trimHistory()

        repeat(MAX_ROUNDS) { round ->
            val started = System.currentTimeMillis()
            val streamed = StringBuilder()
            val reply = backend.complete(system, history.toList(), specs) { delta ->
                streamed.append(delta)
                conversation.update(id) { it.copy(reply = streamed.toString()) }
            }
            trace.round(round, System.currentTimeMillis() - started, reply.text, reply.toolCalls, reply.usage?.inputTokens, reply.usage?.outputTokens)
            history += ChatMessage.Assistant(reply.text, reply.toolCalls)
            if (reply.toolCalls.isEmpty()) return reply.text.trim()
            conversation.update(id) { it.copy(reply = "") }
            reply.toolCalls.forEach { call ->
                val result = executeTool(id, call, toolContext, source, trace)
                history += ChatMessage.ToolResult(call.id, call.name, result.toString())
            }
        }
        val final = backend.complete(system, history.toList(), emptyList())
        history += ChatMessage.Assistant(final.text)
        return final.text.trim()
    }

    private suspend fun executeTool(id: Long, call: ToolCall, ctx: ToolContext, source: TurnSource, trace: TraceBuilder): JsonObject {
        val tool = registry[call.name]
        val args = parseObject(call.arguments)
        val summary = tool?.summary(args) ?: call.name
        conversation.update(id) { it.copy(steps = it.steps + ToolStep(call.id, call.name, summary, StepStatus.RUNNING)) }
        val started = System.currentTimeMillis()
        val needsConfirmation = tool != null && tool.requiresConfirmation && call.name !in ctx.settings.confirmationDisabled
        val result = if (needsConfirmation) {
            val question = tool!!.confirmationPrompt(args) ?: context.getString(R.string.confirm_generic, summary)
            val approved = confirmations.confirm(call.name, question, viaVoice = source == TurnSource.VOICE)
            if (approved) registry.execute(call.name, call.arguments, ctx) else ToolResults.error("declined_by_user", "The user said no. Do not retry.")
        } else {
            registry.execute(call.name, call.arguments, ctx)
        }
        val duration = System.currentTimeMillis() - started
        val declined = result["error"]?.toString()?.contains("declined_by_user") == true
        val status = when {
            declined -> StepStatus.DECLINED
            result.isError() -> StepStatus.FAILED
            else -> StepStatus.DONE
        }
        conversation.update(id) { turn ->
            turn.copy(steps = turn.steps.map { if (it.callId == call.id) it.copy(status = status, detail = result.toString()) else it })
        }
        actionLog.append(ActionEntry(System.currentTimeMillis(), call.name, call.arguments, result.toString(), !result.isError(), duration))
        trace.tool(call, result, duration)
        return result
    }

    private fun trimHistory() {
        while (history.size > MAX_HISTORY) {
            history.removeAt(0)
            while (history.isNotEmpty() && history.first() !is ChatMessage.User) history.removeAt(0)
        }
    }

    private fun friendlyError(e: Exception): String = when {
        e is LlmException && (e.status == 401 || e.status == 403) -> context.getString(R.string.error_backend_auth)
        e is LlmException && e.status == 429 -> context.getString(R.string.error_backend_rate_limit)
        e is LlmException -> context.getString(R.string.error_backend, e.message ?: "")
        e is IOException -> context.getString(R.string.error_network)
        else -> context.getString(R.string.error_generic)
    }

    private class TraceBuilder(val id: Long, val input: String, val source: TurnSource) {
        var contextBlock = ""
        var backend = ""
        var offeredTools: List<String> = emptyList()
        var reply = ""
        var error: String? = null
        private val rounds = mutableListOf<Map<String, Any?>>()
        private val tools = mutableListOf<Map<String, Any?>>()

        fun round(index: Int, millis: Long, text: String, calls: List<ToolCall>, input: Int?, output: Int?) {
            rounds += mapOf(
                "round" to index, "millis" to millis, "text" to text,
                "tool_calls" to calls.map { mapOf("id" to it.id, "name" to it.name, "arguments" to parseObject(it.arguments)) },
                "input_tokens" to input, "output_tokens" to output,
            )
        }

        fun tool(call: ToolCall, result: JsonObject, millis: Long) {
            tools += mapOf("name" to call.name, "arguments" to parseObject(call.arguments), "result" to result, "millis" to millis)
        }

        fun build(): JsonObject = jsonOf(
            "turn" to id, "source" to source.name, "input" to input, "backend" to backend, "offered_tools" to offeredTools,
            "context_block" to contextBlock, "rounds" to rounds, "tools" to tools, "reply" to reply, "error" to error,
            "total_millis" to System.currentTimeMillis() - id,
        )
    }

    companion object {
        private const val MAX_ROUNDS = 8
        private const val MAX_HISTORY = 40
        private const val SESSION_IDLE_MILLIS = 5 * 60_000L
    }
}
