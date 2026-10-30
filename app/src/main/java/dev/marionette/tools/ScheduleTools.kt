package dev.marionette.tools

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.AlarmManagerCompat
import dev.marionette.MarionetteApp
import dev.marionette.agent.ActionEntry
import dev.marionette.core.AppJson
import dev.marionette.core.Logger
import dev.marionette.core.arr
import dev.marionette.core.int
import dev.marionette.core.obj
import dev.marionette.core.parseObject
import dev.marionette.core.text
import dev.marionette.core.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Serializable
data class ScheduledCall(val tool: String, val arguments: JsonObject)

@Serializable
data class ScheduledAction(val id: Int, val at: Long, val label: String, val calls: List<ScheduledCall>)

object ScheduledActions {
    private const val FILE = "scheduled-actions.json"
    private const val EXTRA_ID = "id"
    private const val PREFS = "scheduled-actions"
    private const val NEXT_ID = "next_id"
    private const val OVERDUE_GRACE_MILLIS = 12 * 3_600_000L
    private const val OVERDUE_DELAY_MILLIS = 30_000L

    @Synchronized
    fun all(context: Context): List<ScheduledAction> = runCatching {
        AppJson.decodeFromString<List<ScheduledAction>>(File(context.filesDir, FILE).readText())
    }.getOrDefault(emptyList())

    @Synchronized
    private fun save(context: Context, actions: List<ScheduledAction>) {
        val file = File(context.filesDir, FILE)
        val temp = File(context.filesDir, "$FILE.tmp")
        temp.writeText(AppJson.encodeToString(actions))
        temp.renameTo(file)
    }

    @Synchronized
    fun add(context: Context, at: Long, label: String, calls: List<ScheduledCall>): ScheduledAction {
        val current = all(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = maxOf(prefs.getInt(NEXT_ID, 1), (current.maxOfOrNull { it.id } ?: 0) + 1)
        prefs.edit().putInt(NEXT_ID, id + 1).apply()
        val action = ScheduledAction(id, at, label, calls)
        save(context, current + action)
        arm(context, action)
        return action
    }

    @Synchronized
    fun remove(context: Context, id: Int): ScheduledAction? {
        val current = all(context)
        val action = current.firstOrNull { it.id == id } ?: return null
        save(context, current - action)
        context.getSystemService(AlarmManager::class.java).cancel(pending(context, id))
        return action
    }

    fun rearm(context: Context) {
        val now = System.currentTimeMillis()
        all(context).forEach { action ->
            when {
                action.at > now -> arm(context, action)
                now - action.at <= OVERDUE_GRACE_MILLIS -> arm(context, action.copy(at = now + OVERDUE_DELAY_MILLIS))
                else -> remove(context, action.id)
            }
        }
    }

    private fun arm(context: Context, action: ScheduledAction) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = pending(context, action.id)
        if (AlarmManagerCompat.canScheduleExactAlarms(alarms)) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, action.at, intent)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, action.at, intent)
        }
    }

    private fun pending(context: Context, id: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        id,
        Intent(context, ScheduledActionReceiver::class.java).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun idOf(intent: Intent): Int = intent.getIntExtra(EXTRA_ID, -1)
}

class ScheduledActionReceiver : BroadcastReceiver() {
    private companion object {
        const val CALL_MILLIS = 8_000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            runCatching { ScheduledActions.rearm(context) }.onFailure { Logger.w("Scheduled actions not re-armed", it) }
            return
        }
        val id = ScheduledActions.idOf(intent)
        val graph = MarionetteApp.graph
        val done = goAsync()
        graph.scope.launch(Dispatchers.IO) {
            try {
                val action = ScheduledActions.remove(context, id) ?: return@launch
                val settings = graph.settings.current
                val ctx = ToolContext(graph.app, graph.device, graph.launcher, settings, graph.playback)
                action.calls.forEach { call ->
                    val started = System.currentTimeMillis()
                    val result = if (call.tool in settings.disabledTools) {
                        ToolResults.error("disabled", "${call.tool} is disabled.")
                    } else {
                        withTimeoutOrNull(CALL_MILLIS) { graph.registry.execute(call.tool, call.arguments, ctx) }
                            ?: ToolResults.error("timeout", "${call.tool} took too long.")
                    }
                    Logger.i("Scheduled ${call.tool}: $result")
                    graph.actionLog.append(ActionEntry(System.currentTimeMillis(), call.tool, call.arguments.toString(), result.toString(), !result.isError(), System.currentTimeMillis() - started))
                }
            } finally {
                done.finish()
            }
        }
    }
}

object ScheduleTimes {
    private val DATED = Regex("""^(\d{4}-\d{2}-\d{2})[ T](.+)$""")

    fun parse(text: String?, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val trimmed = text?.trim().orEmpty()
        DATED.find(trimmed)?.let { match ->
            val date = runCatching { LocalDate.parse(match.groupValues[1]) }.getOrNull() ?: return null
            val (hour, minute) = ClockTimes.parse(match.groupValues[2]) ?: return null
            return date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
        }
        val (hour, minute) = ClockTimes.parse(trimmed) ?: return null
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atTime(hour, minute).atZone(zone)
        return (if (today.toInstant().toEpochMilli() <= now) today.plusDays(1) else today).toInstant().toEpochMilli()
    }
}

class ScheduleTool : Tool {
    override val name = "schedule"
    override val category = ToolCategory.TIME
    override val description =
        "Run phone tool calls later, on the phone, without you: \"stop the music in 30 minutes\", \"turn off do not disturb at 8\", " +
            "\"turn off the flashlight in 5 minutes\". add takes in_minutes or at, and calls (tool name and arguments, like a normal call). " +
            "Tools that ask for confirmation, screen tools and schedule itself cannot be scheduled. list shows pending ones; cancel takes id. " +
            "Not for reminders the user must hear: use create_event with reminder_minutes 0, set_alarm or set_timer."
    override val parameters = schema {
        string("action", "What to do.", required = true, enum = listOf("add", "list", "cancel"))
        integer("in_minutes", "For add: run this many minutes from now, 1 to 10080.")
        string("at", "For add: local HH:mm (tomorrow if already past), or yyyy-MM-ddTHH:mm, within 7 days.")
        string("label", "For add: short description, e.g. \"stop music\". For cancel: words of that description.")
        objectArray("calls", "For add: tool calls to run, in order.") {
            string("tool", "Tool name, e.g. media_control.", required = true)
            obj("arguments", "That tool's arguments, as in a normal call, e.g. {\"action\":\"pause\"}.")
        }
        integer("id", "For cancel: id from add or list. Without id, cancel matches label, or the newest pending action.")
    }

    override fun summary(args: JsonObject) = args.string("label") ?: args.string("action").orEmpty()

    override fun endsTurn(args: JsonObject, result: JsonObject) = args.string("action") != "list" && result.isDone()

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject {
        val context = ctx.context
        if (args.string("action") == "add") return add(args, ctx)
        return when (args.string("action")) {
            "list" -> ToolResults.ok("scheduled" to ScheduledActions.all(context).map(::view))
            "cancel" -> {
                val pending = ScheduledActions.all(context)
                val label = args.string("label")?.takeIf { it.isNotBlank() }
                val id = args.int("id")
                    ?: label?.let { query -> pending.filter { EventMatch.matches(it.label + " " + it.calls.joinToString { c -> c.tool }, query) }.singleOrNull()?.id }
                    ?: pending.maxByOrNull { it.id }?.takeIf { label == null }?.id
                    ?: return ToolResults.error("not_found", "No such scheduled action. Pending: ${pending.joinToString { "${it.id} ${it.label}" }.ifEmpty { "none" }}.")
                ScheduledActions.remove(context, id)?.let { ToolResults.ok("status" to "cancelled", "scheduled" to view(it)) }
                    ?: ToolResults.error("not_found", "No scheduled action $id. Use list.")
            }
            else -> ToolResults.error("bad_arguments", "action must be add, list or cancel.")
        }
    }

    private suspend fun add(args: JsonObject, ctx: ToolContext): JsonObject {
        val context = ctx.context
        val registry = MarionetteApp.graph.registry
        val calls = args["calls"].arr()?.mapNotNull { it.obj() }?.map { call ->
            val tool = call.string("tool") ?: return ToolResults.error("bad_arguments", "Each call needs tool.")
            val target = registry[tool]?.takeIf { tool !in ctx.settings.disabledTools } ?: return ToolResults.error("bad_arguments", "No tool named $tool.")
            val raw = call["arguments"]
            val arguments = raw.obj() ?: raw.text()?.let { parseObject(it) } ?: JsonObject(emptyMap())
            if (raw.text() != null && arguments.isEmpty() && raw.text()!!.isNotBlank()) return ToolResults.error("bad_arguments", "arguments of $tool is not a JSON object.")
            val resolved = when (val r = registry.resolve(tool, arguments, ctx)) {
                is Resolution.Ready -> r.args
                is Resolution.Failed -> return r.result
            }
            if (target.requiresConfirmation || target.needsConfirmation(resolved) || target.category == ToolCategory.SCREEN || tool == name) {
                return ToolResults.error("not_allowed", "$tool cannot be scheduled.")
            }
            ScheduledCall(tool, resolved)
        }?.takeIf { it.isNotEmpty() } ?: return ToolResults.error("bad_arguments", "calls is required for add.")
        val now = System.currentTimeMillis()
        val at = (args.int("in_minutes") ?: args.int("after_minutes"))?.takeIf { it in 1..MAX_MINUTES }?.let { now + it * 60_000L }
            ?: ScheduleTimes.parse(args.string("at"), now)?.takeIf { it > now && it - now <= MAX_MINUTES * 60_000L }
            ?: return ToolResults.error("bad_arguments", "Give in_minutes (1 to $MAX_MINUTES) or at as HH:mm or yyyy-MM-ddTHH:mm, within 7 days.")
        val label = args.string("label")?.takeIf { it.isNotBlank() } ?: calls.joinToString { it.tool }
        val action = ScheduledActions.add(context, at, label, calls)
        return ToolResults.ok("status" to "scheduled", "scheduled" to view(action))
    }

    private fun view(action: ScheduledAction) = mapOf(
        "id" to action.id,
        "label" to action.label,
        "at" to LocalTimes.format(action.at, false),
        "calls" to action.calls.map { it.tool },
    )

    companion object {
        private const val MAX_MINUTES = 7 * 24 * 60
    }
}
