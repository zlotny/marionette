package dev.marionette.tools

import android.Manifest
import android.content.ContentUris
import android.content.ContentResolver
import android.content.ContentValues
import android.provider.CalendarContract
import android.content.res.Resources
import dev.marionette.R
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
    override val readOnly = true
    override val category = ToolCategory.CALENDAR
    override val permissions = listOf(Manifest.permission.READ_CALENDAR)
    override val description = "List calendar events between two local times. Default: from now to the end of tomorrow."
    override val parameters = schema {
        string("from", "Start, local time as yyyy-MM-ddTHH:mm or yyyy-MM-dd.")
        string("to", "End, local time as yyyy-MM-ddTHH:mm or yyyy-MM-dd.")
        string("query", "Only events whose title contains this text.")
        integer("calendar_id", "Only this calendar, from list_calendars.")
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
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.RRULE,
        )
        val calendarId = args.int("calendar_id")
        val selection = calendarId?.let { "${CalendarContract.Instances.CALENDAR_ID} = ?" }
        val selectionArgs = calendarId?.let { arrayOf(it.toString()) }
        val query = args.string("query")?.let(InstalledApps::fold)
        val events = mutableListOf<Map<String, Any?>>()
        ctx.context.contentResolver.query(uri, projection, selection, selectionArgs, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                val title = c.getString(0).orEmpty()
                if (query != null && query !in InstalledApps.fold(title)) continue
                val allDay = c.getInt(3) == 1
                events += mapOf(
                    "id" to c.getLong(6),
                    "title" to title,
                    "start" to LocalTimes.format(c.getLong(1), allDay),
                    "end" to LocalTimes.format(c.getLong(2), allDay),
                    "all_day" to allDay,
                    "location" to c.getString(4)?.takeIf { it.isNotBlank() },
                    "calendar" to c.getString(5),
                    "repeats" to c.getString(7)?.isNotBlank()?.takeIf { it },
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
    override val description = "Add an event or reminder to the user's primary calendar, or to calendar_id."
    override val parameters = schema {
        string("title", "Event title.", required = true)
        string("start", "Local start as yyyy-MM-ddTHH:mm, or yyyy-MM-dd for an all-day event.", required = true)
        string("end", "Local end. Default: start plus 1 hour, or one day for all-day events.")
        string("location", "Optional location.")
        string("description", "Optional notes.")
        integer("reminder_minutes", "Minutes before start for a notification reminder. Default 10.")
        integer("calendar_id", "Calendar from list_calendars. Default: the primary calendar.")
    }

    override fun summary(args: JsonObject) = listOfNotNull(args.string("title"), args.string("start")).joinToString(" · ")

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject = withContext(Dispatchers.IO) {
        val title = args.string("title")?.takeIf { it.isNotBlank() } ?: return@withContext ToolResults.error("bad_arguments", "title is required.")
        val startText = args.string("start")
        val start = LocalTimes.parse(startText) ?: return@withContext ToolResults.error("bad_arguments", "start must be yyyy-MM-ddTHH:mm or yyyy-MM-dd.")
        val allDay = LocalTimes.isDateOnly(startText)
        val end = LocalTimes.parse(args.string("end"))?.takeIf { it.isAfter(start) } ?: if (allDay) start.plusDays(1) else start.plusHours(1)
        val calendars = Calendars.writable(ctx.context.contentResolver)
        val calendarId = args.int("calendar_id")?.toLong()?.let { id ->
            calendars.firstOrNull { it.id == id }?.id ?: return@withContext ToolResults.error("not_found", "No writable calendar with id $id. Use list_calendars.")
        } ?: Calendars.primary(calendars)?.id ?: return@withContext ToolResults.error("no_calendar", "No writable calendar on this phone.")
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            args.string("location")?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            args.string("description")?.let { put(CalendarContract.Events.DESCRIPTION, it) }
            putTimes(this, start, end, allDay)
        }
        val uri = ctx.context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: return@withContext ToolResults.error("insert_failed", "The calendar rejected the event.")
        val eventId = ContentUris.parseId(uri)
        Calendars.setReminder(ctx.context.contentResolver, eventId, args.int("reminder_minutes") ?: 10)
        ToolResults.ok("status" to "created", "id" to eventId, "title" to title, "start" to start.toString(), "all_day" to allDay)
    }
}

data class CalendarInfo(val id: Long, val name: String, val account: String, val primary: Boolean, val visible: Boolean)

object Calendars {
    fun writable(resolver: ContentResolver): List<CalendarInfo> {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.VISIBLE,
        )
        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}"
        val out = mutableListOf<CalendarInfo>()
        resolver.query(CalendarContract.Calendars.CONTENT_URI, projection, selection, null, null)?.use { c ->
            while (c.moveToNext()) out += CalendarInfo(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getInt(3) == 1, c.getInt(4) == 1)
        }
        return out
    }

    fun primary(calendars: List<CalendarInfo>): CalendarInfo? =
        calendars.firstOrNull { it.primary && it.visible } ?: calendars.firstOrNull { it.visible } ?: calendars.firstOrNull()

    fun setReminder(resolver: ContentResolver, eventId: Long, minutes: Int) {
        resolver.delete(CalendarContract.Reminders.CONTENT_URI, "${CalendarContract.Reminders.EVENT_ID} = ?", arrayOf(eventId.toString()))
        resolver.insert(
            CalendarContract.Reminders.CONTENT_URI,
            ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(CalendarContract.Reminders.MINUTES, minutes.coerceIn(0, 40_320))
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            },
        )
    }
}

private fun putTimes(values: ContentValues, start: LocalDateTime, end: LocalDateTime, allDay: Boolean) = values.apply {
    if (allDay) {
        put(CalendarContract.Events.ALL_DAY, 1)
        put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
        put(CalendarContract.Events.DTSTART, start.toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        put(CalendarContract.Events.DTEND, end.toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
    } else {
        put(CalendarContract.Events.ALL_DAY, 0)
        put(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
        put(CalendarContract.Events.DTSTART, LocalTimes.millis(start))
        put(CalendarContract.Events.DTEND, LocalTimes.millis(end))
    }
}

class ListCalendarsTool : Tool {
    override val name = "list_calendars"
    override val readOnly = true
    override val category = ToolCategory.CALENDAR
    override val permissions = listOf(Manifest.permission.READ_CALENDAR)
    override val description = "List the calendars the user can add events to, with ids for create_event and list_events."
    override val parameters = schema {}

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject = withContext(Dispatchers.IO) {
        val calendars = Calendars.writable(ctx.context.contentResolver)
        val primary = Calendars.primary(calendars)?.id
        ToolResults.ok(
            "calendars" to calendars.map {
                mapOf("id" to it.id, "name" to it.name, "account" to it.account, "primary" to (it.id == primary), "visible" to it.visible)
            },
        )
    }
}

class UpdateEventTool : Tool {
    override val name = "update_event"
    override val category = ToolCategory.CALENDAR
    override val permissions = listOf(Manifest.permission.WRITE_CALENDAR, Manifest.permission.READ_CALENDAR)
    override val description =
        "Change an event by its id from list_events. Pass only the fields to change. A new start without end keeps the duration. " +
            "Changes to a repeating event apply to every repeat, and its times cannot be changed."
    override val parameters = schema {
        integer("id", "Event id from list_events.", required = true)
        string("title", "New title.")
        string("start", "New local start as yyyy-MM-ddTHH:mm, or yyyy-MM-dd for all day.")
        string("end", "New local end.")
        string("location", "New location. Empty text clears it.")
        string("description", "New notes. Empty text clears them.")
        integer("reminder_minutes", "New reminder, minutes before start.")
    }

    override fun summary(args: JsonObject) = args.string("title") ?: args.string("start") ?: args.int("id")?.toString().orEmpty()

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject = withContext(Dispatchers.IO) {
        val id = args.int("id")?.toLong() ?: return@withContext ToolResults.error("bad_arguments", "id is required.")
        val resolver = ctx.context.contentResolver
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
        val projection = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.RRULE,
        )
        val current = resolver.query(uri, projection, null, null, null)?.use { c ->
            if (c.moveToFirst()) EventRow(c.getString(0).orEmpty(), c.getLong(1), c.getLong(2), c.getInt(3) == 1, !c.getString(4).isNullOrBlank()) else null
        } ?: return@withContext ToolResults.error("not_found", "No event with id $id. Use list_events.")
        val values = ContentValues()
        args.string("title")?.takeIf { it.isNotBlank() }?.let { values.put(CalendarContract.Events.TITLE, it) }
        args.string("location")?.let { values.put(CalendarContract.Events.EVENT_LOCATION, it) }
        args.string("description")?.let { values.put(CalendarContract.Events.DESCRIPTION, it) }
        val startText = args.string("start")
        val endText = args.string("end")
        if (startText != null || endText != null) {
            if (current.repeats) return@withContext ToolResults.error("repeating_event", "The times of a repeating event cannot be changed here.")
            val times = current.retime(startText, endText) ?: return@withContext ToolResults.error("bad_arguments", "start and end must be yyyy-MM-ddTHH:mm or yyyy-MM-dd, end after start.")
            putTimes(values, times.first, times.second, times.third)
        }
        val reminder = args.int("reminder_minutes")
        if (values.size() == 0 && reminder == null) return@withContext ToolResults.error("bad_arguments", "Nothing to change.")
        if (values.size() > 0 && resolver.update(uri, values, null, null) == 0) {
            return@withContext ToolResults.error("update_failed", "The calendar rejected the change.")
        }
        reminder?.let { Calendars.setReminder(resolver, id, it) }
        ToolResults.ok("status" to "updated", "id" to id, "title" to (values.getAsString(CalendarContract.Events.TITLE) ?: current.title))
    }
}

data class EventRow(val title: String, val start: Long, val end: Long, val allDay: Boolean, val repeats: Boolean) {
    fun retime(startText: String?, endText: String?): Triple<LocalDateTime, LocalDateTime, Boolean>? {
        val oldStart = LocalTimes.parse(LocalTimes.format(start, allDay))!!
        val oldEnd = LocalTimes.parse(LocalTimes.format(end.coerceAtLeast(start), allDay))!!
        val newStart = if (startText != null) LocalTimes.parse(startText) ?: return null else oldStart
        val allDayNow = if (startText != null) LocalTimes.isDateOnly(startText) else allDay
        val newEnd = when {
            endText != null -> LocalTimes.parse(endText) ?: return null
            else -> newStart.plus(java.time.Duration.between(oldStart, oldEnd))
        }
        val fixedEnd = if (allDayNow && !newEnd.isAfter(newStart)) newStart.plusDays(1) else newEnd
        if (!fixedEnd.isAfter(newStart)) return null
        return Triple(newStart, fixedEnd, allDayNow)
    }
}

class DeleteEventTool : Tool {
    override val name = "delete_event"
    override val category = ToolCategory.CALENDAR
    override val requiresConfirmation = true
    override val permissions = listOf(Manifest.permission.WRITE_CALENDAR, Manifest.permission.READ_CALENDAR)
    override val description = "Delete an event by its id from list_events. For a repeating event this deletes every repeat."
    override val parameters = schema {
        integer("id", "Event id from list_events.", required = true)
        string("title", "Event title, for the confirmation.")
    }

    override fun summary(args: JsonObject) = args.string("title") ?: args.int("id")?.toString().orEmpty()

    override fun confirmationPrompt(args: JsonObject, res: Resources): String = res.getString(R.string.confirm_delete_event, summary(args))

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject = withContext(Dispatchers.IO) {
        val id = args.int("id")?.toLong() ?: return@withContext ToolResults.error("bad_arguments", "id is required.")
        val deleted = ctx.context.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null)
        if (deleted == 0) ToolResults.error("not_found", "No event with id $id. Use list_events.") else ToolResults.ok("status" to "deleted", "id" to id)
    }
}
