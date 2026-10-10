package dev.marionette.agent

import android.content.Context
import dev.marionette.R
import dev.marionette.core.Logger
import dev.marionette.core.jsonOf
import dev.marionette.core.parseObject
import dev.marionette.launch.Launcher
import dev.marionette.media.PlaybackHold
import dev.marionette.llm.BackendFactory
import dev.marionette.llm.ChatMessage
import dev.marionette.llm.DeviceIdentity
import dev.marionette.llm.LinkEvent
import dev.marionette.llm.LinkException
import dev.marionette.llm.LinkResult
import dev.marionette.llm.LlmException
import dev.marionette.llm.OpenClawGateway
import dev.marionette.llm.OpenClawLink
import dev.marionette.llm.Provider
import dev.marionette.llm.Thinking
import dev.marionette.llm.ToolCall
import dev.marionette.settings.AppSettings
import dev.marionette.settings.SettingsStore
import dev.marionette.tools.ToolContext
import dev.marionette.tools.ToolRegistry
import dev.marionette.tools.ToolResults
import dev.marionette.tools.isError
import java.io.IOException
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class Agent(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val registry: ToolRegistry,
    private val device: DeviceSnapshotProvider,
    private val launcher: Launcher,
    private val playback: PlaybackHold,
    private val prompt: SystemPrompt,
    private val confirmations: ConfirmationBroker,
    private val actionLog: ActionLog,
    private val traces: TraceWriter,
    val conversation: Conversation,
    private val unlockTip: UnlockTip,
) {
    private val history = mutableListOf<ChatMessage>()
    private var historyChat = ""
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
        conversation.newChat()
    }

    private fun sessionUser(chatId: String): String = Companion.sessionUser(chatId)

    private fun startsNewChat(source: TurnSource, s: AppSettings): Boolean {
        val chat = conversation.current.value
        return source != TurnSource.TEXT && !s.continueConversation && chat.turns.isNotEmpty() &&
            System.currentTimeMillis() - chat.updated > SESSION_IDLE_MILLIS
    }

    private fun syncHistory(chat: Chat) {
        if (chat.id == historyChat) return
        history.clear()
        chat.turns.filter { it.status == TurnStatus.ANSWERED && it.reply.isNotBlank() }.forEach {
            history += ChatMessage.User(it.input)
            history += ChatMessage.Assistant(it.reply)
        }
        historyChat = chat.id
    }

    suspend fun run(input: String, source: TurnSource, onProgress: (String) -> Unit = {}, onQuiet: () -> Unit = {}): Turn = lock.withLock {
        val id = System.currentTimeMillis()
        if (startsNewChat(source, settings.current)) conversation.newChat()
        syncHistory(conversation.current.value)
        conversation.start(Turn(id, input, source))
        _busy.value = true
        val trace = TraceBuilder(id, input, source)
        try {
            val result = loop(id, input, source, trace, onProgress, onQuiet)
            conversation.update(id) { it.copy(reply = result, status = TurnStatus.ANSWERED) }
            trace.reply = result
        } catch (e: CancellationException) {
            conversation.update(id) { it.copy(status = TurnStatus.CANCELLED) }
            trace.error = "cancelled"
            throw e
        } catch (e: Exception) {
            Logger.w("Turn failed", e)
            val message = friendlyError(e)
            conversation.update(id) { it.copy(reply = message, status = TurnStatus.FAILED, error = errorDetail(e)) }
            trace.error = e.message ?: e.javaClass.simpleName
            trace.reply = message
        } finally {
            _busy.value = false
            runCatching { traces.write(id, trace.build()) }
        }
        conversation.turns.value.first { it.id == id }
    }

    private suspend fun loop(id: Long, input: String, source: TurnSource, trace: TraceBuilder, onProgress: (String) -> Unit, onQuiet: () -> Unit): String {
        val s = settings.current
        val contextBlock = if (s.sendContext) ContextBlock.render(device.snapshot(s.contextFields, s.language), s.contextFields) else ""
        val system = prompt.build(s, contextBlock)
        trace.contextBlock = contextBlock
        trace.system = system
        val chat = conversation.current.value
        val own = chat.overrideProvider == s.provider.name
        val config = s.backendFor(chat.model.takeIf { own }, chat.thinking.takeIf { own })
        trace.backend = s.provider.name + " " + listOf(config.model, config.agentModel, config.thinking?.id.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
        val user = sessionUser(chat.id)
        if (s.provider == Provider.OPENCLAW) syncSessionThinking(s, chat, config.thinking, user)
        val backend = BackendFactory.create(config, sessionUser = user)
        val toolContext = ToolContext(context, device, launcher, s, playback)
        val specs = registry.specs(s.disabledTools)
        trace.offeredTools = specs.map { it.name }
        val pending = mutableListOf<ChatMessage>(ChatMessage.User(input))
        history += pending
        trimHistory()
        val outgoing = { if (s.provider.isAgent) pending.toList() else history.toList() }
        val said = Collections.synchronizedSet(mutableSetOf<String>())
        var quiet = false
        val progress = { text: String ->
            if (said.add(text)) {
                conversation.update(id) { it.copy(steps = it.steps + Note(text)) }
                if (!quiet) onProgress(text)
            }
        }
        val linkEvent = { event: LinkEvent ->
            when (event) {
                is LinkEvent.Progress -> progress(event.text)
                is LinkEvent.Tool -> if (registry[event.name] == null) showAgentTool(id, event)
            }
        }
        val watcher = if (s.liveProgress) watchProgress(s, user, linkEvent) else null
        try {
            repeat(MAX_ROUNDS) { round ->
                val started = System.currentTimeMillis()
                val streamed = StringBuilder()
                val reply = backend.complete(system, outgoing(), specs) { delta ->
                    streamed.append(delta)
                    conversation.update(id) { it.copy(reply = streamed.toString()) }
                }
                trace.round(round, System.currentTimeMillis() - started, reply.text, reply.toolCalls, reply.usage?.inputTokens, reply.usage?.outputTokens)
                val assistant = ChatMessage.Assistant(reply.text, reply.toolCalls, reply.native)
                history += assistant
                pending.clear()
                if (reply.toolCalls.isEmpty()) return reply.text.trim()
                if (!s.provider.isAgent) pending += assistant
                reply.text.trim().takeIf { it.isNotEmpty() }?.let(progress)
                conversation.update(id) { it.copy(reply = "") }
                reply.toolCalls.forEach { call ->
                    var result = executeTool(id, call, toolContext, source, trace)
                    if (UnlockTip.isUnlockBlock(result)) unlockTip.offer()
                    if (registry[call.name]?.silencesTurn(parseObject(call.arguments), result) == true) {
                        result = JsonObject(result + ("voice" to JsonPrimitive(QUIET_NOTE)))
                        if (!quiet) {
                            quiet = true
                            conversation.update(id) { it.copy(quiet = true) }
                            onQuiet()
                        }
                    }
                    val message = ChatMessage.ToolResult(call.id, call.name, result.toString())
                    history += message
                    pending += message
                }
            }
            val final = backend.complete(system, outgoing(), emptyList())
            history += ChatMessage.Assistant(final.text)
            return final.text.trim()
        } finally {
            watcher?.cancel()
        }
    }

    private fun showAgentTool(id: Long, event: LinkEvent.Tool) {
        val status = when {
            event.failed -> StepStatus.FAILED
            event.done -> StepStatus.DONE
            else -> StepStatus.RUNNING
        }
        conversation.update(id) { turn ->
            val known = turn.steps.any { it is ToolStep && it.callId == event.id }
            val steps = if (known) {
                turn.steps.map { if (it is ToolStep && it.callId == event.id) it.copy(status = status) else it }
            } else {
                turn.steps + ToolStep(event.id, event.name, event.summary, status)
            }
            turn.copy(steps = steps)
        }
    }

    private suspend fun syncSessionThinking(s: AppSettings, chat: Chat, thinking: Thinking?, user: String) {
        val wanted = thinking?.id.orEmpty()
        if (wanted == chat.sessionThinking || !s.liveProgress) return
        val identity = DeviceIdentity.parse(s.gatewayDeviceKey) ?: return
        val result = withTimeoutOrNull(PATCH_MILLIS) {
            OpenClawLink.setThinking(s.baseUrl, identity, s.gatewayDeviceToken, user, OpenClawGateway.agentName(s.model), thinking)
        }
        if (result is LinkResult.Paired) conversation.setSessionThinking(chat.id, wanted) else Logger.w("Thinking not applied: $result")
    }

    private suspend fun watchProgress(s: AppSettings, user: String, onEvent: (LinkEvent) -> Unit): Job? {
        val identity = DeviceIdentity.parse(s.gatewayDeviceKey) ?: return null
        val ready = CompletableDeferred<Unit>()
        val job = scope.launch(Dispatchers.IO) {
            try {
                OpenClawLink.watch(
                    s.baseUrl, identity, s.gatewayDeviceToken, user, OpenClawGateway.agentName(s.model),
                    onReady = { paired ->
                        Logger.i("Live progress linked")
                        ready.complete(Unit)
                        if (paired.token != s.gatewayDeviceToken) scope.launch { settings.update { it.copy(gatewayDeviceToken = paired.token) } }
                    },
                    onEvent = onEvent,
                )
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                Logger.w("Live progress unavailable", e)
                if ((e as? LinkException)?.result is LinkResult.PairingRequired) settings.update { it.copy(gatewayDeviceToken = "") }
            } finally {
                ready.complete(Unit)
            }
        }
        withTimeoutOrNull(LINK_READY_MILLIS) { ready.await() }
        return job
    }

    private suspend fun executeTool(id: Long, call: ToolCall, ctx: ToolContext, source: TurnSource, trace: TraceBuilder): JsonObject {
        val tool = registry[call.name]
        val args = parseObject(call.arguments)
        val summary = tool?.summary(args) ?: call.name
        conversation.update(id) { it.copy(steps = it.steps + ToolStep(call.id, call.name, summary, StepStatus.RUNNING)) }
        val started = System.currentTimeMillis()
        val needsConfirmation = tool != null && tool.requiresConfirmation && call.name !in ctx.settings.confirmationDisabled
        val result = if (needsConfirmation) {
            val question = tool!!.confirmationPrompt(args) ?: context.getString(R.string.confirm_generic, summary.ifBlank { call.name })
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
            turn.copy(steps = turn.steps.map { if (it is ToolStep && it.callId == call.id) it.copy(status = status, detail = result.toString()) else it })
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

    private fun errorDetail(e: Exception): String {
        val message = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        val status = (e as? LlmException)?.status
        return if (status != null) "HTTP $status · $message" else message
    }

    private fun friendlyError(e: Exception): String = when {
        e is LlmException && (e.status == 401 || e.status == 403) -> context.getString(R.string.error_backend_auth)
        e is LlmException && e.status == 429 && e.retryAfterSeconds != null ->
            context.getString(R.string.error_backend_rate_limit_wait, e.retryAfterSeconds.coerceAtLeast(1))
        e is LlmException && e.status == 429 -> context.getString(R.string.error_backend_rate_limit)
        e is LlmException -> context.getString(R.string.error_backend, e.message ?: "")
        e is IOException -> context.getString(R.string.error_network)
        else -> context.getString(R.string.error_generic)
    }

    private class TraceBuilder(val id: Long, val input: String, val source: TurnSource) {
        var contextBlock = ""
        var system = ""
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
            "system" to system, "context_block" to contextBlock, "rounds" to rounds, "tools" to tools, "reply" to reply, "error" to error,
            "total_millis" to System.currentTimeMillis() - id,
        )
    }

    companion object {
        fun sessionUser(chatId: String): String = "marionette-$chatId"

        private const val MAX_ROUNDS = 8
        private const val MAX_HISTORY = 40
        private const val SESSION_IDLE_MILLIS = 5 * 60_000L
        private const val LINK_READY_MILLIS = 1_500L
        private const val PATCH_MILLIS = 4_000L
        private const val QUIET_NOTE = "muted: the user is listening to the music or the call now, so the rest of this turn is shown on screen and not spoken"
    }
}
