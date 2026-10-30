package dev.marionette.tools

import android.app.AlarmManager
import android.app.Notification
import android.content.Intent
import android.service.notification.StatusBarNotification
import android.provider.AlarmClock
import dev.marionette.core.arr
import dev.marionette.core.boolean
import dev.marionette.core.int
import dev.marionette.core.string
import dev.marionette.core.text
import dev.marionette.launch.LaunchStatus
import dev.marionette.notifications.MarionetteNotificationListener
import dev.marionette.notifications.body
import dev.marionette.notifications.title
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.util.Calendar
import java.util.Locale

object ClockTimes {
    private val PATTERN = Regex("""^(\d{1,2})[:.h](\d{2})$""")

    fun parse(text: String?): Pair<Int, Int>? {
        val match = PATTERN.find(text.orEmpty().trim()) ?: return null
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].toInt()
        return if (hour in 0..23 && minute in 0..59) hour to minute else null
    }
}

class SetTimerTool : Tool {
    override val name = "set_timer"
    override val category = ToolCategory.TIME
    override val description = "Start a countdown timer in the clock app."
    override val parameters = schema {
        integer("seconds", "Duration in seconds, 1 to 86400.", required = true)
        string("label", "Optional label.")
    }

    override fun summary(args: JsonObject): String {
        val seconds = args.int("seconds") ?: 0
        return if (seconds >= 60 && seconds % 60 == 0) "${seconds / 60} min" else "$seconds s"
    }

    override fun endsTurn(args: JsonObject, result: JsonObject) = result.isDone()

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject {
        val seconds = args.int("seconds")?.takeIf { it in 1..86_400 } ?: return ToolResults.error("bad_arguments", "seconds must be 1 to 86400.")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        args.string("label")?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        return when (ctx.launcher.launch(intent, "timer", unlock = false)) {
            LaunchStatus.FAILED -> ToolResults.error("no_clock_app", "No clock app accepted the timer.")
            else -> ToolResults.ok("seconds" to seconds, "ends_at" to endTime(seconds))
        }
    }

    private fun endTime(seconds: Int): String {
        val c = Calendar.getInstance().apply { add(Calendar.SECOND, seconds) }
        return "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }
}

class SetAlarmTool : Tool {
    override val name = "set_alarm"
    override val category = ToolCategory.TIME
    override val description = "Set an alarm in the clock app at a local time, optionally repeating on weekdays."
    override val parameters = schema {
        string("time", "Local time as HH:mm, 24-hour.")
        integer("in_minutes", "Ring this many minutes from now, instead of time.")
        string("label", "Optional label.")
        stringArray("days", "Repeat days. Omit for a one-time alarm.", enum = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun"))
    }

    override fun summary(args: JsonObject) = args.string("time") ?: args.int("in_minutes")?.let { "+$it min" }.orEmpty()

    override fun endsTurn(args: JsonObject, result: JsonObject) = result.isDone()

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject {
        val (hour, minute) = args.int("in_minutes")?.takeIf { it > 0 }?.let { minutes ->
            Calendar.getInstance().apply { add(Calendar.MINUTE, minutes) }.let { it.get(Calendar.HOUR_OF_DAY) to it.get(Calendar.MINUTE) }
        } ?: ClockTimes.parse(args.string("time")) ?: return ToolResults.error("bad_arguments", "Give time as HH:mm, 00:00 to 23:59, or in_minutes.")
        val ringsAt = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        args.string("label")?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        val days = args["days"].arr()?.mapNotNull { DAY_CODES[it.text()?.lowercase()?.take(3)] }.orEmpty()
        if (days.isNotEmpty()) intent.putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days))
        return when (ctx.launcher.launch(intent, "alarm", unlock = false)) {
            LaunchStatus.FAILED -> ToolResults.error("no_clock_app", "No clock app accepted the alarm.")
            else -> ToolResults.ok("time" to "%02d:%02d".format(Locale.ROOT, hour, minute), "rings_at" to LocalTimes.format(ringsAt, false), "repeats" to days.isNotEmpty().takeIf { it })
        }
    }

    companion object {
        private val DAY_CODES = mapOf(
            "mon" to Calendar.MONDAY, "tue" to Calendar.TUESDAY, "wed" to Calendar.WEDNESDAY, "thu" to Calendar.THURSDAY,
            "fri" to Calendar.FRIDAY, "sat" to Calendar.SATURDAY, "sun" to Calendar.SUNDAY,
        )
    }
}

class NextAlarmTool : Tool {
    override val name = "next_alarm"
    override val readOnly = true
    override val category = ToolCategory.TIME
    override val description = "When the next alarm rings, and which app set it. Only when the context has no clock line. Android shows only the next alarm."
    override val parameters = schema {}

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject {
        val next = ctx.context.getSystemService(AlarmManager::class.java).nextAlarmClock
            ?: return ToolResults.ok("alarm" to null, "message" to "No alarm is set.")
        val pkg = next.showIntent?.creatorPackage
        val pm = ctx.context.packageManager
        val app = pkg?.let { runCatching { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() }.getOrNull() ?: it }
        val minutes = ((next.triggerTime - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
        return ToolResults.ok("time" to LocalTimes.format(next.triggerTime, false), "in_minutes" to minutes, "app" to app)
    }
}

class DismissAlarmTool : Tool {
    override val name = "dismiss_alarm"
    override val category = ToolCategory.TIME
    override val description =
        "Dismiss the alarm that is ringing now. To skip or turn off an upcoming alarm, give its time or label, or next true. " +
            "Dismissing an upcoming alarm skips it or turns it off, as the clock app decides."
    override val parameters = schema {
        string("time", "Local time of the alarm as HH:mm, 24-hour.")
        string("label", "Label of the alarm.")
        boolean("next", "Dismiss the next upcoming alarm even if none is ringing.")
    }

    override fun summary(args: JsonObject) = args.string("time") ?: args.string("label").orEmpty()

    override fun endsTurn(args: JsonObject, result: JsonObject) = result.isDone()

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject {
        val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM)
        val timeText = args.string("time")?.takeIf { it.isNotBlank() }
        val label = args.string("label")?.takeIf { it.isNotBlank() }
        when {
            timeText != null -> {
                val (hour, minute) = ClockTimes.parse(timeText) ?: return ToolResults.error("bad_arguments", "time must be HH:mm, 00:00 to 23:59.")
                intent.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME)
                    .putExtra(AlarmClock.EXTRA_HOUR, hour)
                    .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            }
            label != null -> intent.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_LABEL)
                .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            else -> {
                if (args.boolean("next") != true && ClockAlarms.ringing() == false) {
                    return ToolResults.error("not_ringing", "No alarm is ringing. To skip the next one, pass next true or its time.")
                }
                intent.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT)
            }
        }
        return when (ctx.launcher.launch(intent, "alarm", unlock = false)) {
            LaunchStatus.FAILED -> ToolResults.error("no_clock_app", "No clock app accepted the request.")
            else -> ToolResults.ok("status" to "sent to the clock app")
        }
    }
}

object ClockAlarms {
    fun ringing(): Boolean? {
        val listener = MarionetteNotificationListener.instance ?: return null
        return listener.activeNotifications.orEmpty().any { it.notification.category == Notification.CATEGORY_ALARM && it.isOngoing }
    }
}

class SnoozeAlarmTool : Tool {
    override val name = "snooze_alarm"
    override val category = ToolCategory.TIME
    override val description = "Snooze the alarm that is ringing now."
    override val parameters = schema {
        integer("minutes", "Snooze length. Default: the clock app setting.")
    }

    override fun summary(args: JsonObject) = args.int("minutes")?.let { "$it min" }.orEmpty()

    override fun endsTurn(args: JsonObject, result: JsonObject) = result.isDone()

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject {
        val intent = Intent(AlarmClock.ACTION_SNOOZE_ALARM)
        args.int("minutes")?.coerceIn(1, 60)?.let { intent.putExtra(AlarmClock.EXTRA_ALARM_SNOOZE_DURATION, it) }
        return when (ctx.launcher.launch(intent, "alarm", unlock = false)) {
            LaunchStatus.FAILED -> ToolResults.error("no_clock_app", "No clock app accepted the request.")
            else -> ToolResults.ok("status" to "snoozed")
        }
    }
}

data class ClockTimer(val key: String, val label: String?, val text: String?, val state: String, val endsAt: Long?, val actions: List<String>)

object ClockTimers {
    private val CLOCK_PACKAGES = setOf("com.google.android.deskclock", "com.android.deskclock", "com.sec.android.app.clockpackage", "com.oneplus.deskclock", "com.coloros.alarmclock", "com.huawei.deskclock", "com.miui.clock")
    private val PAUSE = listOf("pause", "pausar", "pausa")
    private val RESUME = listOf("resume", "reanudar", "continuar", "start", "iniciar", "seguir")
    private val RESET = listOf("reset", "restablecer", "eliminar", "delete", "cancel", "borrar", "restaurar", "quitar")
    private val STOP = listOf("stop", "detener", "parar", "dismiss", "descartar", "apagar")

    fun active(): List<StatusBarNotification> {
        val listener = MarionetteNotificationListener.instance ?: return emptyList()
        return listener.activeNotifications.orEmpty()
            .filter { it.packageName in CLOCK_PACKAGES && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .filter { sbn -> sbn.notification.actions.orEmpty().any { isAdd(it.title) } || sbn.notification.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN) }
    }

    fun list(): List<ClockTimer> = active().map(::describe)

    fun describe(sbn: StatusBarNotification): ClockTimer {
        val n = sbn.notification
        val titles = n.actions.orEmpty().mapNotNull { it.title?.toString() }
        val countDown = n.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN) && n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        val state = state(titles)
        return ClockTimer(sbn.key, sbn.title, sbn.body, state, if (countDown && state == "running") n.`when` else null, titles)
    }

    fun state(titles: List<String>): String = when {
        titles.any { has(it, STOP) } && titles.none { has(it, PAUSE) } -> "ringing"
        titles.any { has(it, RESUME) } -> "paused"
        else -> "running"
    }

    fun button(sbn: StatusBarNotification, kind: String): Notification.Action? = sbn.notification.actions.orEmpty().firstOrNull { action ->
        val title = action.title
        when (kind) {
            "add" -> isAdd(title)
            "pause" -> has(title, PAUSE)
            "resume" -> has(title, RESUME)
            "reset" -> has(title, RESET)
            "stop" -> has(title, STOP)
            else -> false
        }
    }

    fun isAdd(title: CharSequence?): Boolean {
        val t = title?.toString().orEmpty().trim().lowercase()
        return t.startsWith("+") || ("1" in t && ("min" in t || ":" in t))
    }

    private fun has(title: CharSequence?, words: List<String>): Boolean {
        val parts = InstalledApps.fold(title?.toString().orEmpty()).split(' ')
        return parts.any { part -> words.any { part.startsWith(it) } }
    }
}

class TimersTool : Tool {
    override val name = "timers"
    override val category = ToolCategory.TIME
    override val description =
        "Manage timers already set in the clock app: list (time left), add minutes, pause, resume, cancel a running or paused timer, " +
            "or stop one that is ringing. With several timers, pick one by label. Start new timers with set_timer."
    override val parameters = schema {
        string("action", "What to do.", required = true, enum = listOf("list", "add", "pause", "resume", "cancel", "stop"))
        integer("minutes", "Minutes to add, for add. 1 to 60.")
        string("label", "Label or text of the timer, when there are several.")
        boolean("all", "Apply the action to every timer, e.g. cancel all.")
        boolean("newest", "Pick the most recently set timer (\"the last one\").")
    }

    override fun summary(args: JsonObject) = listOfNotNull(args.string("action"), args.int("minutes")?.let { "+$it min" }).joinToString(" ")

    override fun endsTurn(args: JsonObject, result: JsonObject) = args.string("action") != "list" && result.isDone()

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject {
        if (MarionetteNotificationListener.instance == null) {
            return ToolResults.error("missing_permission", "Notification access is off. Ask the user to enable it in Marionette settings.")
        }
        val action = args.string("action") ?: return ToolResults.error("bad_arguments", "action is required.")
        val all = ClockTimers.active()
        if (action == "list") return ToolResults.ok("count" to all.size, "timers" to all.map { view(ClockTimers.describe(it)) })
        if (action == "stop" && all.none { ClockTimers.describe(it).state == "ringing" }) return dismissRinging(ctx)
        val label = args.string("label")?.takeIf { it.isNotBlank() }?.let(InstalledApps::fold)
        val candidates = all.filter { label == null || label in InstalledApps.fold("${it.title.orEmpty()} ${it.body.orEmpty()}") }
            .filter { timer ->
                val state = ClockTimers.describe(timer).state
                when (action) {
                    "stop" -> state == "ringing"
                    "pause" -> state == "running"
                    "resume" -> state == "paused"
                    else -> true
                }
            }
        if (candidates.isEmpty()) return ToolResults.error("not_found", if (all.isEmpty()) "No timer is set." else "No timer matches. Timers: ${all.joinToString { it.title.orEmpty() }}.")
        val picked = if (args.boolean("newest") == true && args.boolean("all") != true) listOfNotNull(candidates.maxByOrNull { it.postTime }) else candidates
        if (picked.size > 1 && args.boolean("all") != true) {
            return ToolResults.error("ambiguous", "Several timers: ${candidates.joinToString { view(ClockTimers.describe(it)).toString() }}. Pass label, or all.")
        }
        val minutes = (args.int("minutes") ?: 1).coerceIn(1, MAX_ADD_MINUTES)
        val done = picked.map { target ->
            val pressed = when (action) {
                "add" -> press(target.key, "add", minutes)
                "pause", "resume", "stop" -> press(target.key, action, 1)
                "cancel" -> cancel(target.key)
                else -> return ToolResults.error("bad_arguments", "Unknown action $action.")
            }
            if (!pressed) return ToolResults.error("no_button", "The clock app shows no button for $action. Buttons: ${ClockTimers.describe(target).actions.joinToString()}.")
            target.key
        }
        val after = ClockTimers.active().filter { it.key in done }.map { view(ClockTimers.describe(it)) }
        return ToolResults.ok("status" to "done", "action" to action, "minutes_added" to minutes.takeIf { action == "add" }, "timers" to after)
    }

    private suspend fun press(key: String, kind: String, times: Int): Boolean {
        repeat(times) { index ->
            val sbn = ClockTimers.active().firstOrNull { it.key == key } ?: return index > 0
            val button = ClockTimers.button(sbn, kind) ?: return index > 0
            button.actionIntent.send()
            withTimeoutOrNull(PRESS_WAIT_MILLIS) {
                while (ClockTimers.active().firstOrNull { it.key == key }?.notification?.`when` == sbn.notification.`when` &&
                    ClockTimers.active().firstOrNull { it.key == key }?.postTime == sbn.postTime) delay(POLL_MILLIS)
            }
        }
        return true
    }

    private suspend fun cancel(key: String): Boolean {
        if (press(key, "reset", 1)) return true
        if (!press(key, "pause", 1)) return false
        val reset = withTimeoutOrNull(UPDATE_WAIT_MILLIS) {
            var found: Notification.Action? = null
            while (found == null) {
                found = ClockTimers.active().firstOrNull { it.key == key }?.let { ClockTimers.button(it, "reset") }
                if (found == null) delay(POLL_MILLIS)
            }
            found
        } ?: return false
        reset.actionIntent.send()
        return true
    }

    private suspend fun dismissRinging(ctx: ToolContext): JsonObject =
        when (ctx.launcher.launch(Intent(AlarmClock.ACTION_DISMISS_TIMER), "timer", unlock = false)) {
            LaunchStatus.FAILED -> ToolResults.error("no_clock_app", "No clock app accepted the request.")
            else -> ToolResults.ok("status" to "sent to the clock app")
        }

    private fun view(timer: ClockTimer): Map<String, Any?> = mapOf(
        "label" to timer.label,
        "state" to timer.state,
        "ends_at" to timer.endsAt?.let { LocalTimes.format(it, false).substringAfter('T') },
        "left_seconds" to timer.endsAt?.let { ((it - System.currentTimeMillis()) / 1000).coerceAtLeast(0) },
        "shows" to timer.text,
    ).filterValues { it != null }

    companion object {
        private const val MAX_ADD_MINUTES = 60
        private const val PRESS_WAIT_MILLIS = 400L
        private const val UPDATE_WAIT_MILLIS = 2_500L
        private const val POLL_MILLIS = 40L
    }
}
