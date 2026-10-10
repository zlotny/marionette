package dev.marionette.tools

import android.content.Intent
import android.provider.AlarmClock
import dev.marionette.core.arr
import dev.marionette.core.int
import dev.marionette.core.string
import dev.marionette.core.text
import dev.marionette.launch.LaunchStatus
import kotlinx.serialization.json.JsonObject
import java.util.Calendar

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
        val match = Regex("""^(\d{1,2}):(\d{2})$""").find(args.string("time").orEmpty().trim())
            ?: return ToolResults.error("bad_arguments", "time must be HH:mm.")
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].toInt()
        if (hour !in 0..23 || minute !in 0..59) return ToolResults.error("bad_arguments", "time out of range.")
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
