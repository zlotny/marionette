package dev.marionette.agent

import android.content.Context
import android.content.res.Resources
import dev.marionette.R
import dev.marionette.core.LinkSpeed
import dev.marionette.core.Logger
import dev.marionette.core.jsonOf
import dev.marionette.core.parseObject
import dev.marionette.core.string
import dev.marionette.launch.Launcher
import dev.marionette.media.PlaybackHold
import dev.marionette.llm.BackendConfig
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
import dev.marionette.llm.LlmBackend
import dev.marionette.llm.ToolCall
import dev.marionette.screen.MarionetteAccessibilityService
import dev.marionette.settings.AppSettings
import dev.marionette.settings.Languages
import dev.marionette.settings.SettingsStore
import dev.marionette.tools.Resolution
import dev.marionette.tools.ShowInChatTool
import dev.marionette.tools.ToolContext
import dev.marionette.tools.ToolRegistry
import dev.marionette.tools.ToolResults
import dev.marionette.tools.imageJpeg
import dev.marionette.tools.isError
import dev.marionette.tools.withoutImage
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import dev.marionette.llm.LlmReply
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
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
    private val modelStats: ModelStats,
) {
    private val history = mutableListOf<ChatMessage>()
    private var historyChat = ""
    private val lock = Mutex()
    private var current: Job? = null
    private var delivering: Job? = null
    private val carried: MutableList<ChatMessage> = Collections.synchronizedList(mutableListOf())
    @Volatile private var carriedChat: String? = null
    @Volatile private var turnChat: String? = null
    @Volatile private var linkPausedUntil = 0L
    @Volatile private var unsentResults: List<ChatMessage> = emptyList()
    private var toolsRan = false
    private val diskWrites = Dispatchers.IO.limitedParallelism(1)
    @Volatile private var lastRequest = 0L

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val courier = Courier()
    val delivery: StateFlow<Delivery> = courier.state

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

    fun warmUp() {
        val s = settings.current
        val now = System.currentTimeMillis()
        if (!s.warmOnWake || !s.isBackendConfigured || now - lastRequest < WARM_GAP_MILLIS) return
        if (LinkSpeed.slow(context)) {
            Logger.i("Warm-up skipped: slow link (${LinkSpeed.uploadKbps?.toInt()} kbit/s up)")
            return
        }
        lastRequest = now
        val config = if (startsNewChat(TurnSource.VOICE, s)) s.backendFor(null, null) else backendConfig(s, conversation.current.value)
        scope.launch(Dispatchers.IO) {
            try {
                val backend = BackendFactory.create(config, sessionUser = "$WARM_SESSION-${java.time.LocalDate.now()}")
                val specs = if (s.provider.isAgent) emptyList() else registry.specs(s.disabledTools)
                backend.complete(prompt.build(s), listOf(ChatMessage.User(WARM_PROMPT)), specs)
                Logger.i("Warm-up done in ${System.currentTimeMillis() - now} ms")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Logger.w("Warm-up failed", e)
            }
        }
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

    private fun backendConfig(s: AppSettings, chat: Chat): BackendConfig {
        val own = chat.overrideProvider == s.provider.name
        return s.backendFor(chat.model.takeIf { own }, chat.thinking.takeIf { own })
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
        turnChat = conversation.current.value.id
        conversation.start(Turn(id, input, source))
        _busy.value = true
        val trace = TraceBuilder(id, input, source)
        try {
            unsentResults = emptyList()
            toolsRan = false
            MarionetteAccessibilityService.instance?.forgetScreen()
            delivering?.let { withTimeoutOrNull(DELIVERY_WAIT_MILLIS) { it.join() } }
            val result = loop(id, input, source, trace, onProgress, onQuiet)
            conversation.update(id) { turn ->
                val steps = if (result.spoken) turn.steps.filterNot { it is Note && it.text == result.text } else turn.steps
                turn.copy(reply = result.text, status = TurnStatus.ANSWERED, replySpoken = result.spoken, steps = steps)
            }
            trace.reply = result.text
            trace.endedEarly = result.spoken
        } catch (e: CancellationException) {
            if (settings.current.provider.isAgent) carry(unsentResults)
            closeOpenCalls()
            conversation.update(id) { it.copy(status = TurnStatus.CANCELLED) }
            trace.error = "cancelled"
            throw e
        } catch (e: Exception) {
            Logger.w("Turn failed", e)
            closeOpenCalls()
            val message = if (toolsRan && e is IOException) spokenResources().getString(R.string.error_not_finished) else friendlyError(e)
            if (settings.current.provider.isAgent) carry(unsentResults)
            conversation.update(id) { it.copy(reply = message, status = TurnStatus.FAILED, error = errorDetail(e), undelivered = e is NotDelivered && !toolsRan) }
            trace.error = e.message ?: e.javaClass.simpleName
            trace.reply = message
        } finally {
            courier.idle()
            _busy.value = false
            val built = trace.build()
            scope.launch(diskWrites) { runCatching { traces.write(id, built) } }
        }
        conversation.turns.value.firstOrNull { it.id == id }
            ?: Turn(id, input, source, reply = trace.reply, status = if (trace.error == null) TurnStatus.ANSWERED else TurnStatus.FAILED)
    }

    private class Reply(val text: String, val spoken: Boolean = false)

    private suspend fun loop(id: Long, input: String, source: TurnSource, trace: TraceBuilder, onProgress: (String) -> Unit, onQuiet: () -> Unit): Reply {
        val s = settings.current
        val chat = conversation.current.value
        val config = backendConfig(s, chat)
        trace.backend = s.provider.name + " " + listOf(config.model, config.agentModel, config.thinking?.id.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
        val statsModel = if (s.provider.isAgent) config.agentModel else config.model
        val user = sessionUser(chat.id)
        val backend = BackendFactory.create(config, sessionUser = user)
        val toolContext = ToolContext(context, device, launcher, s, playback)
        val specs = registry.specs(s.disabledTools)
        trace.offeredTools = specs.map { it.name }
        val pending = mutableListOf<ChatMessage>()
        synchronized(carried) {
            if (s.provider.isAgent && carriedChat == chat.id) pending += carried
            carried.clear()
        }
        unsentResults = pending.filterIsInstance<ChatMessage.ToolResult>()
        val outgoing = { if (s.provider.isAgent) pending.toList() else history.toList() }
        val said = Collections.synchronizedSet(mutableSetOf<String>())
        val roundNotes = Collections.synchronizedList(mutableListOf<String>())
        var quiet = false
        val progress = { text: String ->
            if (said.add(text)) {
                roundNotes += text
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
        var watcher: Job? = null
        val contextBlock = try {
            coroutineScope {
                val block = async(Dispatchers.Default) { if (s.sendContext) ContextBlock.render(device.snapshot(s.contextFields, s.language), s.contextFields) else "" }
                launch {
                    if (s.provider == Provider.OPENCLAW) syncSessionThinking(s, chat, config.thinking, user)
                    if (s.liveProgress) watcher = watchProgress(s, user, linkEvent)
                }
                block.await()
            }
        } catch (e: Throwable) {
            watcher?.cancel()
            throw e
        }
        val message = ChatMessage.User(SystemPrompt.withContext(input, contextBlock))
        pending += message
        history.replaceAll { compact(it) }
        history += message
        trimHistory()
        val system = prompt.build(s)
        trace.contextBlock = contextBlock
        trace.system = system
        trace.setupMillis = System.currentTimeMillis() - id
        try {
            repeat(MAX_ROUNDS) { round ->
                roundNotes.clear()
                val started = System.currentTimeMillis()
                lastRequest = started
                val streamed = StringBuilder()
                var firstText: Long? = null
                var shownAt = 0L
                val reply = courier.send { upload ->
                    backend.complete(system, outgoing(), specs, onAccepted = { delivered(upload) }, onUpload = upload::progress) { delta ->
                        if (firstText == null) firstText = System.currentTimeMillis() - started
                        streamed.append(delta)
                        val now = System.currentTimeMillis()
                        if (now - shownAt >= STREAM_UI_MILLIS) {
                            shownAt = now
                            conversation.update(id) { it.copy(reply = streamed.toString()) }
                        }
                    }
                }
                val elapsed = System.currentTimeMillis() - started
                trace.round(round, elapsed, firstText, reply.text, reply.toolCalls, reply.usage?.inputTokens, reply.usage?.outputTokens)
                scope.launch(diskWrites) { modelStats.record(s.provider.name, statsModel, config.thinking?.id.orEmpty(), elapsed, reply.usage?.inputTokens, reply.usage?.outputTokens) }
                val assistant = ChatMessage.Assistant(reply.text, reply.toolCalls, reply.native)
                history += assistant
                pending.clear()
                if (reply.toolCalls.isEmpty()) return Reply(reply.text.trim())
                if (!s.provider.isAgent) pending += assistant
                reply.text.trim().takeIf { it.isNotEmpty() }?.let(progress)
                conversation.update(id) { it.copy(reply = "") }
                val parallel = reply.toolCalls.size > 1 && reply.toolCalls.all { registry[it.name]?.readOnly == true }
                val early = if (parallel) {
                    coroutineScope { reply.toolCalls.map { call -> async { executeTool(id, call, toolContext, source, trace) } }.awaitAll() }
                } else {
                    null
                }
                val order = reply.toolCalls.indices.sortedBy { if (endsSilent(reply.toolCalls[it])) 1 else 0 }
                val executed = arrayOfNulls<JsonObject>(reply.toolCalls.size)
                for (index in order) {
                    val call = reply.toolCalls[index]
                    var result = early?.get(index) ?: executeTool(id, call, toolContext, source, trace)
                    if (UnlockTip.isUnlockBlock(result)) unlockTip.offer()
                    if (registry[call.name]?.silencesTurn(parseObject(call.arguments), result) == true) {
                        result = JsonObject(result + ("voice" to JsonPrimitive(QUIET_NOTE)))
                        if (!quiet) {
                            quiet = true
                            conversation.update(id) { it.copy(quiet = true) }
                            onQuiet()
                        }
                    }
                    executed[index] = result
                    val message = ChatMessage.ToolResult(call.id, call.name, result.withoutImage().toString(), result.imageJpeg())
                    history += message
                    pending += message
                    toolsRan = true
                    unsentResults = pending.filterIsInstance<ChatMessage.ToolResult>()
                }
                val results = reply.toolCalls.mapIndexed { index, call -> call to executed[index]!! }
                val outcomes = results.map { (call, result) -> outcome(call, result) }
                if (Outcome.OTHER !in outcomes) {
                    val anyDone = Outcome.DONE in outcomes
                    if (anyDone && roundNotes.isEmpty() && s.provider.isAgent && watcher != null) withTimeoutOrNull(LATE_NOTE_MILLIS) { while (roundNotes.isEmpty()) delay(NOTE_POLL_MILLIS) }
                    val notes = if (anyDone) roundNotes.joinToString(" ").trim() else ""
                    val needsText = results.zip(outcomes).any { (pair, o) -> o == Outcome.DONE && registry[pair.first.name]?.endsTurnOnlyWithText == true }
                    val res = spokenResources()
                    val extra = when {
                        Outcome.UNLOCK in outcomes -> res.getString(R.string.voice_unlock_to_continue)
                        Outcome.DECLINED in outcomes && anyDone -> res.getString(R.string.voice_partly_declined)
                        Outcome.DECLINED in outcomes -> res.getString(R.string.voice_declined)
                        else -> null
                    }
                    if (extra != null || notes.isNotEmpty() || !needsText) {
                        val confirmed = results.zip(outcomes).any { (pair, o) ->
                            o == Outcome.DONE && registry[pair.first.name]?.let { it.requiresConfirmation || it.needsConfirmation(parseObject(pair.first.arguments)) } == true
                        }
                        val fresh = extra ?: if (notes.isEmpty() || confirmed) res.getString(R.string.voice_done) else null
                        if (fresh != null && notes.isNotEmpty() && !quiet) onProgress(fresh)
                        val text = listOfNotNull(notes.ifEmpty { null }, fresh).joinToString(" ")
                        deliverInBackground(s, backend, system, pending.toList(), text)
                        return Reply(text, spoken = quiet || notes.isNotEmpty())
                    }
                }
            }
            lastRequest = System.currentTimeMillis()
            val finalTools = if (s.provider.isAgent) emptyList() else specs
            val final = courier.send { upload -> backend.complete(system, outgoing(), finalTools, noTools = true, onAccepted = { delivered(upload) }, onUpload = upload::progress) }
            history += ChatMessage.Assistant(final.text)
            return Reply(final.text.trim())
        } finally {
            watcher?.cancel()
            if (toolContext.dismissedKeyguard && s.relockAfterScreenControl) MarionetteAccessibilityService.lockScreen()
        }
    }

    private fun carry(results: List<ChatMessage>, chatId: String? = turnChat) {
        if (results.isEmpty() || chatId == null) return
        synchronized(carried) {
            if (carriedChat != chatId) carried.clear()
            carriedChat = chatId
            carried += results
        }
    }

    private fun delivered(upload: Upload) {
        unsentResults = emptyList()
        upload.accepted()
    }

    private enum class Outcome { DONE, DECLINED, UNLOCK, OTHER }

    private fun outcome(call: ToolCall, result: JsonObject): Outcome = when {
        registry[call.name]?.endsTurn(parseObject(call.arguments), result) == true -> Outcome.DONE
        result.string("error") == "declined_by_user" -> Outcome.DECLINED
        result.string("status") == "waiting_for_unlock" -> Outcome.UNLOCK
        else -> Outcome.OTHER
    }

    private fun endsSilent(call: ToolCall): Boolean =
        call.name in SILENT_TOOLS || (call.name == "media_control" && parseObject(call.arguments).string("action") in setOf("play", "next", "previous", "toggle"))

    private fun closeOpenCalls() {
        val last = history.indexOfLast { it is ChatMessage.Assistant && it.toolCalls.isNotEmpty() }
        if (last < 0) return
        val answered = history.drop(last + 1).filterIsInstance<ChatMessage.ToolResult>().map { it.callId }.toSet()
        val missing = (history[last] as ChatMessage.Assistant).toolCalls.filter { it.id !in answered }
        if (missing.isEmpty()) return
        val results = missing.map { ChatMessage.ToolResult(it.id, it.name, CANCELLED_RESULT) }
        history += results
        history += ChatMessage.Assistant(CANCELLED_REPLY)
        carry(results)
    }

    private fun deliverInBackground(s: AppSettings, backend: LlmBackend, system: String, results: List<ChatMessage>, spoken: String) {
        if (!s.provider.isAgent) {
            history += ChatMessage.Assistant(spoken)
            return
        }
        val chatId = turnChat
        delivering = scope.launch(Dispatchers.IO) {
            var accepted = false
            try {
                val final = withTimeout(DELIVERY_MILLIS) { backend.complete(system, results, emptyList(), onAccepted = { accepted = true }) }
                Logger.content("Tool results delivered after early end; agent added", final.text.take(80))
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                Logger.w("Tool results not delivered after early end (accepted=$accepted)", e)
                if (!accepted) carry(results.filterIsInstance<ChatMessage.ToolResult>(), chatId)
            }
        }
        history += ChatMessage.Assistant(spoken)
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
        if (wanted == chat.sessionThinking || !s.liveProgress || System.currentTimeMillis() < linkPausedUntil) return
        val identity = DeviceIdentity.parse(s.gatewayDeviceKey) ?: return
        val result = withTimeoutOrNull(PATCH_MILLIS) {
            OpenClawLink.setThinking(s.baseUrl, identity, s.gatewayDeviceToken, user, OpenClawGateway.agentName(s.model), thinking)
        }
        if (result is LinkResult.Paired) conversation.setSessionThinking(chat.id, wanted) else Logger.w("Thinking not applied: $result")
    }

    private fun watchProgress(s: AppSettings, user: String, onEvent: (LinkEvent) -> Unit): Job? {
        if (System.currentTimeMillis() < linkPausedUntil) return null
        val identity = DeviceIdentity.parse(s.gatewayDeviceKey) ?: return null
        return scope.launch(Dispatchers.IO) {
            try {
                OpenClawLink.watch(
                    s.baseUrl, identity, s.gatewayDeviceToken, user, OpenClawGateway.agentName(s.model),
                    onReady = { paired ->
                        Logger.i("Live progress linked")
                        if (paired.token != s.gatewayDeviceToken) scope.launch { settings.update { it.copy(gatewayDeviceToken = paired.token) } }
                    },
                    onEvent = onEvent,
                )
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                Logger.w("Live progress unavailable", e)
                val result = (e as? LinkException)?.result
                if (result is LinkResult.PairingRequired) settings.update { it.copy(gatewayDeviceToken = "") }
                if (result is LinkResult.TokenOutdated || result is LinkResult.PairingRequired) linkPausedUntil = System.currentTimeMillis() + LINK_PAUSE_MILLIS
            }
        }
    }

    private suspend fun executeTool(id: Long, call: ToolCall, ctx: ToolContext, source: TurnSource, trace: TraceBuilder): JsonObject {
        val tool = registry[call.name]
        val requested = parseObject(call.arguments)
        conversation.update(id) { it.copy(steps = it.steps + ToolStep(call.id, call.name, tool?.summary(requested) ?: call.name, StepStatus.RUNNING)) }
        val started = System.currentTimeMillis()
        val result = when (val resolution = registry.resolve(call.name, requested, ctx)) {
            is Resolution.Failed -> resolution.result
            is Resolution.Ready -> {
                val args = resolution.args
                val needsConfirmation = tool != null && tool.needsConfirmation(args) && call.name !in ctx.settings.confirmationDisabled
                if (needsConfirmation) {
                    val res = spokenResources()
                    val summary = tool!!.summary(args)
                    val question = tool.confirmationPrompt(args, res) ?: res.getString(R.string.confirm_generic, summary.ifBlank { call.name })
                    val answer = confirmations.confirm(call.name, question, viaVoice = source == TurnSource.VOICE)
                    when {
                        answer.approved -> registry.execute(call.name, args, ctx)
                        answer.remark != null -> ToolResults.error(
                            "declined_with_correction",
                            "Not done. At the confirmation the user said: \"${answer.remark}\". Apply that change and call the tool again, or follow the new request.",
                        )
                        else -> ToolResults.error("declined_by_user", "The user said no. Do not retry.")
                    }
                } else {
                    registry.execute(call.name, args, ctx)
                }
            }
        }
        val duration = System.currentTimeMillis() - started
        val stored = result.withoutImage()
        val declined = result["error"]?.toString()?.contains("declined_by_user") == true
        val status = when {
            declined -> StepStatus.DECLINED
            result.isError() -> StepStatus.FAILED
            else -> StepStatus.DONE
        }
        conversation.update(id) { turn ->
            turn.copy(steps = turn.steps.map { if (it is ToolStep && it.callId == call.id) it.copy(status = status, detail = if (call.name == ShowInChatTool.NAME) stored.toString() else null) else it })
        }
        val entry = ActionEntry(System.currentTimeMillis(), call.name, call.arguments, stored.toString(), !result.isError(), duration)
        scope.launch(diskWrites) { actionLog.append(entry) }
        trace.tool(call, stored, duration)
        return result
    }

    private fun spokenResources(): Resources = Languages.resources(context, settings.current.language)

    private fun trimHistory() {
        if (history.size <= MAX_HISTORY) return
        while (history.size > MAX_HISTORY / 2) {
            history.removeAt(0)
            while (history.isNotEmpty() && history.first() !is ChatMessage.User) history.removeAt(0)
        }
    }

    private fun compact(message: ChatMessage): ChatMessage = when {
        message !is ChatMessage.ToolResult -> message
        message.name in SCREEN_TOOLS && message.content.length > OLD_SCREEN_CHARS ->
            message.copy(content = """{"ok":true,"note":"older screen content dropped"}""", imageJpeg = null)
        message.imageJpeg != null -> message.copy(imageJpeg = null)
        else -> message
    }

    private fun errorDetail(e: Exception): String {
        val message = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        val status = (e as? LlmException)?.status
        return if (status != null) "HTTP $status · $message" else message
    }

    private fun friendlyError(e: Exception): String = when {
        e is LlmException && (e.status == 401 || e.status == 403) -> spokenResources().getString(R.string.error_backend_auth)
        e is LlmException && e.status == 429 && e.retryAfterSeconds != null ->
            spokenResources().getString(R.string.error_backend_rate_limit_wait, e.retryAfterSeconds.coerceAtLeast(1))
        e is LlmException && e.status == 429 -> spokenResources().getString(R.string.error_backend_rate_limit)
        e is LlmException -> spokenResources().getString(R.string.error_backend, e.message ?: "")
        e is NotDelivered -> spokenResources().getString(R.string.error_not_delivered)
        e is IOException -> spokenResources().getString(R.string.error_network_cut)
        else -> spokenResources().getString(R.string.error_generic)
    }

    private class TraceBuilder(val id: Long, val input: String, val source: TurnSource) {
        var contextBlock = ""
        var system = ""
        var backend = ""
        var offeredTools: List<String> = emptyList()
        var reply = ""
        var error: String? = null
        var setupMillis: Long? = null
        var endedEarly = false
        private val rounds = mutableListOf<Map<String, Any?>>()
        private val tools = mutableListOf<Map<String, Any?>>()

        fun round(index: Int, millis: Long, firstTextMillis: Long?, text: String, calls: List<ToolCall>, input: Int?, output: Int?) {
            rounds += mapOf(
                "round" to index, "millis" to millis, "first_text_millis" to firstTextMillis, "text" to text,
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
            "setup_millis" to setupMillis, "ended_early" to endedEarly, "total_millis" to System.currentTimeMillis() - id,
        )
    }

    companion object {
        fun sessionUser(chatId: String): String = "marionette-$chatId"

        private const val MAX_ROUNDS = 8
        private const val STREAM_UI_MILLIS = 50L
        private val SILENT_TOOLS = setOf("play_media", "call")
        private const val CANCELLED_RESULT = """{"ok":false,"error":"cancelled_by_user","message":"The user cancelled the turn. Do not run this again."}"""
        private const val CANCELLED_REPLY = "(cancelled)"
        private const val LATE_NOTE_MILLIS = 600L
        private const val NOTE_POLL_MILLIS = 50L
        private const val DELIVERY_WAIT_MILLIS = 8_000L
        private const val DELIVERY_MILLIS = 60_000L
        private const val MAX_HISTORY = 40
        private const val OLD_SCREEN_CHARS = 1_500
        private val SCREEN_TOOLS = setOf("ui_read", "ui_act", "open_app", "open_url", "open_settings", "screen_capture")
        private const val SESSION_IDLE_MILLIS = 5 * 60_000L
        private const val PATCH_MILLIS = 4_000L
        private const val WARM_GAP_MILLIS = 4 * 60_000L
        private const val WARM_SESSION = "marionette-warm"
        private const val LINK_PAUSE_MILLIS = 10 * 60_000L
        private const val WARM_PROMPT = "Reply with only: ok"
        private const val QUIET_NOTE = "muted: the user is listening to the music or the call now, so the rest of this turn is shown on screen and not spoken"
    }
}
