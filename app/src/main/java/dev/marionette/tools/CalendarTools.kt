package dev.marionette.tools

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.provider.CalendarContract
import dev.marionette.core.int
import dev.marionette.core.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object LocalTimes {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun parse(text: String?): LocalDateTime? {
        val t = text?.trim()?.replace(' ', 'T')?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { LocalDateTime.parse(t) }.getOrNull()
            ?: runCatching { LocalDateTime.parse(t.take(16)) }.getOrNull()
            ?: runCatching { LocalDate.parse(t.take(10)).atStartOfDay() }.getOrNull()
    }

    fun isDateOnly(text: String?): Boolean = text?.trim()?.length == 10

    fun millis(time: LocalDateTime): Long = time.atZone(zone).toInstant().toEpochMilli()

    fun format(millis: Long, allDay: Boolean): String {
        val z = if (allDay) ZoneOffset.UTC else zone
        val time = java.time.Instant.ofEpochMilli(millis).atZone(z).toLocalDateTime()
        return if (allDay) time.toLocalDate().toString() else time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
    }
}

class ListEventsTool : Tool {
    override val name = "list_events"
    override val category = ToolCategory.CALENDAR
    override val permissions = listOf(Manifest.permission.READ_CALENDAR)
    override val description = "List calendar events between two local times. Default: from now to the end of tomorrow."
    override val parameters = schema {
        string("from", "Start, local time as yyyy-MM-ddTHH:mm or yyyy-MM-dd.")
        string("to", "End, local time as yyyy-MM-ddTHH:mm or yyyy-MM-dd.")
        string("query", "Only events whose title contains this text.")
        integer("limit", "Maximum results. Default 20.")
    }

    override fun summary(args: JsonObject) = listOfNotNull(args.string("from"), args.string("to")).joinToString(" to ")

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject = withContext(Dispatchers.IO) {
        val from = LocalTimes.parse(args.string("from")) ?: LocalDateTime.now()
        val to = LocalTimes.parse(args.string("to"))?.let { if (LocalTimes.isDateOnly(args.string("to"))) it.plusDays(1) else it }
            ?: LocalDate.now().plusDays(2).atStartOfDay()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, LocalTimes.millis(from))
            ContentUris.appendId(it, LocalTimes.millis(to))
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        )
        val query = args.string("query")?.let(InstalledApps::fold)
        val events = mutableListOf<Map<String, Any?>>()
        ctx.context.contentResolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                val title = c.getString(0).orEmpty()
                if (query != null && query !in InstalledApps.fold(title)) continue
                val allDay = c.getInt(3) == 1
                events += mapOf(
                    "title" to title,
                    "start" to LocalTimes.format(c.getLong(1), allDay),
                    "end" to LocalTimes.format(c.getLong(2), allDay),
                    "all_day" to allDay,
                    "location" to c.getString(4)?.takeIf { it.isNotBlank() },
                    "calendar" to c.getString(5),
                ).filterValues { it != null }
            }
        }
        val limit = (args.int("limit") ?: 20).coerceIn(1, 100)
        ToolResults.ok("count" to events.size, "events" to events.take(limit))
    }
}

class CreateEventTool : Tool {
    override val name = "create_event"
    override val category = ToolCategory.CALENDAR
    override val permissions = listOf(Manifest.permission.WRITE_CALENDAR, Manifest.permission.READ_CALENDAR)
    override val description = "Add an event or reminder to the user's primary calendar."
    override val parameters = schema {
        string("title", "Event title.", required = true)
        string("start", "Local start as yyyy-MM-ddTHH:mm, or yyyy-MM-dd for an all-day event.", required = true)
        string("end", "Local end. Default: start plus 1 hour, or one day for all-day events.")
        string("location", "Optional location.")
        string("description", "Optional notes.")
        integer("reminder_minutes", "Minutes before start for a notification reminder. Default 10.")
    }

    override fun summary(args: JsonObject) = listOfNotNull(args.string("title"), args.string("start")).joinToString(" · ")

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject = withContext(Dispatchers.IO) {
        val title = args.string("title")?.takeIf { it.isNotBlank() } ?: return@withContext ToolResults.error("bad_arguments", "title is required.")
        val startText = args.string("start")
        val start = LocalTimes.parse(startText) ?: return@withContext ToolResults.error("bad_arguments", "start must be yyyy-MM-ddTHH:mm or yyyy-MM-dd.")
        val allDay = LocalTimes.isDateOnly(startText)
        val end = LocalTimes.parse(args.string("end"))?.takeIf { it.isAfter(start) } ?: if (allDay) start.plusDays(1) else start.plusHours(1)
        val calendarId = primaryCalendar(ctx) ?: return@withContext ToolResults.error("no_calendar", "No writable calendar on this phone.")
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            args.string("location")?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            args.string("description")?.let { put(CalendarContract.Events.DESCRIPTION, it) }
            if (allDay) {
                put(CalendarContract.Events.ALL_DAY, 1)
                put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
                put(CalendarContract.Events.DTSTART, start.toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
                put(CalendarContract.Events.DTEND, end.toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
            } else {
                put(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
                put(CalendarContract.Events.DTSTART, LocalTimes.millis(start))
                put(CalendarContract.Events.DTEND, LocalTimes.millis(end))
            }
        }
        val uri = ctx.context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: return@withContext ToolResults.error("insert_failed", "The calendar rejected the event.")
        val eventId = ContentUris.parseId(uri)
        val reminder = (args.int("reminder_minutes") ?: 10).coerceIn(0, 40_320)
        ctx.context.contentResolver.insert(
            CalendarContract.Reminders.CONTENT_URI,
            ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(CalendarContract.Reminders.MINUTES, reminder)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            },
        )
        ToolResults.ok("status" to "created", "title" to title, "start" to start.toString(), "all_day" to allDay)
    }

    private fun primaryCalendar(ctx: ToolContext): Long? {
        val projection = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.VISIBLE)
        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}"
        val candidates = mutableListOf<Triple<Long, Boolean, Boolean>>()
        ctx.context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, selection, null, null)?.use { c ->
            while (c.moveToNext()) candidates += Triple(c.getLong(0), c.getInt(1) == 1, c.getInt(2) == 1)
        }
        return (candidates.firstOrNull { it.second && it.third } ?: candidates.firstOrNull { it.third } ?: candidates.firstOrNull())?.first
    }
}
