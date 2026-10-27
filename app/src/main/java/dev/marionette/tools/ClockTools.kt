package dev.marionette.tools

import android.app.AlarmManager
import android.content.Intent
import android.provider.AlarmClock
import dev.marionette.core.arr
import dev.marionette.core.int
import dev.marionette.core.string
import dev.marionette.core.text
import dev.marionette.launch.LaunchStatus
import kotlinx.serialization.json.JsonObject
import java.util.Calendar

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
        string("time", "Local time as HH:mm, 24-hour.", required = true)
        string("label", "Optional label.")
        stringArray("days", "Repeat days: mon, tue, wed, thu, fri, sat, sun. Omit for a one-time alarm.")
    }

    override fun summary(args: JsonObject) = args.string("time").orEmpty()

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject {
        val (hour, minute) = ClockTimes.parse(args.string("time")) ?: return ToolResults.error("bad_arguments", "time must be HH:mm, 00:00 to 23:59.")
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        args.string("label")?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        val days = args["days"].arr()?.mapNotNull { DAY_CODES[it.text()?.lowercase()?.take(3)] }.orEmpty()
        if (days.isNotEmpty()) intent.putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days))
        return when (ctx.launcher.launch(intent, "alarm", unlock = false)) {
            LaunchStatus.FAILED -> ToolResults.error("no_clock_app", "No clock app accepted the alarm.")
            else -> ToolResults.ok("time" to "%02d:%02d".format(hour, minute), "repeats" to days.isNotEmpty())
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
    override val description = "When the next alarm rings, and which app set it. Android shows only the next alarm, not the full list."
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
        "Dismiss an alarm in the clock app: the one ringing now or the next one, or the alarm at time or with label. " +
            "Dismissing an upcoming alarm skips it or turns it off, as the clock app decides."
    override val parameters = schema {
        string("time", "Local time of the alarm as HH:mm, 24-hour.")
        string("label", "Label of the alarm.")
    }

    override fun summary(args: JsonObject) = args.string("time") ?: args.string("label").orEmpty()

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
            else -> intent.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT)
        }
        return when (ctx.launcher.launch(intent, "alarm", unlock = false)) {
            LaunchStatus.FAILED -> ToolResults.error("no_clock_app", "No clock app accepted the request.")
            else -> ToolResults.ok("status" to "sent to the clock app")
        }
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

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject {
        val intent = Intent(AlarmClock.ACTION_SNOOZE_ALARM)
        args.int("minutes")?.coerceIn(1, 60)?.let { intent.putExtra(AlarmClock.EXTRA_ALARM_SNOOZE_DURATION, it) }
        return when (ctx.launcher.launch(intent, "alarm", unlock = false)) {
            LaunchStatus.FAILED -> ToolResults.error("no_clock_app", "No clock app accepted the request.")
            else -> ToolResults.ok("status" to "snoozed")
        }
    }
}

class DismissTimerTool : Tool {
    override val name = "dismiss_timer"
    override val category = ToolCategory.TIME
    override val description = "Stop the timer that is ringing in the clock app. Running timers that have not finished cannot be cancelled this way: use ui_act in the clock app."
    override val parameters = schema {}

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject =
        when (ctx.launcher.launch(Intent(AlarmClock.ACTION_DISMISS_TIMER), "timer", unlock = false)) {
            LaunchStatus.FAILED -> ToolResults.error("no_clock_app", "No clock app accepted the request.")
            else -> ToolResults.ok("status" to "sent to the clock app")
        }
}
