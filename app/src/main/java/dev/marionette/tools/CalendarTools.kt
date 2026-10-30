package dev.marionette.tools

import android.Manifest
import android.content.ContentUris
import android.content.ContentResolver
import android.content.ContentValues
import android.provider.CalendarContract
import android.content.res.Resources
import dev.marionette.R
import dev.marionette.core.arr
import dev.marionette.core.boolean
import dev.marionette.core.int
import dev.marionette.core.string
import dev.marionette.core.text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    override val description =
        "List calendar events between two local times. Default: from now to the end of tomorrow; with query, or limit without to, the next 365 days. " +
            "query matches every word in the title or location."
    override val parameters = schema {
        string("since", "Start, local yyyy-MM-ddTHH:mm or yyyy-MM-dd. Default now.")
        string("until", "End; a date includes that whole day. Default end of tomorrow.")
        string("query", "Only events whose title contains this text.")
        string("calendar", "Only the calendar with this name, e.g. \"trabajo\".")
        integer("calendar_id", "Only this calendar, from list_calendars.")
        integer("limit", "Maximum results. Default 20.")
    }

    override fun summary(args: JsonObject) = listOfNotNull(args.firstString("since", "from"), args.firstString("until", "to")).joinToString(" – ")

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject = withContext(Dispatchers.IO) {
        val from = LocalTimes.parse(args.firstString("since", "from")) ?: LocalDateTime.now()
        val farDefault = args.string("query") != null || args.int("limit") != null
        val untilText = args.firstString("until", "to")
        val to = LocalTimes.parse(untilText)?.let { if (LocalTimes.isDateOnly(untilText)) it.plusDays(1) else it }
            ?: if (farDefault) from.plusDays(FAR_DAYS) else LocalDate.now().plusDays(2).atStartOfDay()
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
        val calendarId = args.long("calendar_id") ?: args.string("calendar")?.takeIf { it.isNotBlank() }?.let { name ->
            val calendars = Calendars.writable(ctx.context.contentResolver)
            Calendars.byName(calendars, name)?.id
                ?: return@withContext ToolResults.error("not_found", "No calendar named \"$name\". Calendars: ${calendars.joinToString { it.name }}.")
        }
        val selection = calendarId?.let { "${CalendarContract.Instances.CALENDAR_ID} = ?" }
        val selectionArgs = calendarId?.let { arrayOf(it.toString()) }
        val query = args.string("query")?.takeIf { it.isNotBlank() }
        val events = mutableListOf<Map<String, Any?>>()
        ctx.context.contentResolver.query(uri, projection, selection, selectionArgs, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                val title = c.getString(0).orEmpty()
                if (query != null && !EventMatch.matches("$title ${c.getString(4).orEmpty()}", query)) continue
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

    companion object {
        private const val FAR_DAYS = 365L
    }
}

class CreateEventTool : Tool {
    override val name = "create_event"
    override val category = ToolCategory.CALENDAR
    override val permissions = listOf(Manifest.permission.WRITE_CALENDAR, Manifest.permission.READ_CALENDAR)
    override val description =
        "Add an event or reminder to the user's primary calendar, or to a calendar by name. repeat_days makes it weekly on those days."
    override val parameters = schema {
        string("title", "Event title.", required = true)
        string("start", "Local start as yyyy-MM-ddTHH:mm, or yyyy-MM-dd for an all-day event.", required = true)
        string("end", "Local end. Default: start plus 1 hour, or one day for all-day events.")
        string("location", "Optional location.")
        string("description", "Optional notes.")
        integer("reminder_minutes", "Minutes before start for a notification reminder. Default 10. For \"remind me\", use 0.")
        string("calendar", "Calendar name or account, e.g. \"trabajo\". Default: the primary calendar.")
        integer("calendar_id", "Calendar id from list_calendars, instead of calendar.")
        stringArray("repeat_days", "Repeat every week on these days.", enum = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun"))
        string("repeat_until", "Last date of the repeat, yyyy-MM-dd. Default: no end.")
    }

    override fun summary(args: JsonObject) = listOfNotNull(args.string("title"), args.string("start")).joinToString(" · ")

    override fun endsTurn(args: JsonObject, result: JsonObject) = result.isDone()

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject = withContext(Dispatchers.IO) {
        val title = args.string("title")?.takeIf { it.isNotBlank() } ?: return@withContext ToolResults.error("bad_arguments", "title is required.")
        val startText = args.string("start")
        val start = LocalTimes.parse(startText) ?: return@withContext ToolResults.error("bad_arguments", "start must be yyyy-MM-ddTHH:mm or yyyy-MM-dd.")
        val allDay = LocalTimes.isDateOnly(startText)
        val end = LocalTimes.parse(args.string("end"))?.takeIf { it.isAfter(start) } ?: if (allDay) start.plusDays(1) else start.plusHours(1)
        val calendars = Calendars.writable(ctx.context.contentResolver)
        val calendarId = args.long("calendar_id")?.let { id ->
            calendars.firstOrNull { it.id == id }?.id ?: return@withContext ToolResults.error("not_found", "No writable calendar with id $id. Use list_calendars.")
        } ?: args.string("calendar")?.takeIf { it.isNotBlank() }?.let { name ->
            Calendars.byName(calendars, name)?.id
                ?: return@withContext ToolResults.error("not_found", "No calendar named \"$name\". Calendars: ${calendars.joinToString { it.name }}.")
        } ?: Calendars.primary(calendars)?.id ?: return@withContext ToolResults.error("no_calendar", "No writable calendar on this phone.")
        val rule = args["repeat_days"].arr()?.mapNotNull { it.text() }?.takeIf { it.isNotEmpty() }?.let { days ->
            Recurrence.weekly(days, args.string("repeat_until")) ?: return@withContext ToolResults.error("bad_arguments", "repeat_days must be mon to sun; repeat_until yyyy-MM-dd.")
        }
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            args.string("location")?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            args.string("description")?.let { put(CalendarContract.Events.DESCRIPTION, it) }
            putTimes(this, start, end, allDay)
            if (rule != null) {
                put(CalendarContract.Events.RRULE, rule)
                put(CalendarContract.Events.DURATION, Recurrence.duration(getAsLong(CalendarContract.Events.DTEND) - getAsLong(CalendarContract.Events.DTSTART)))
                remove(CalendarContract.Events.DTEND)
            }
        }
        val uri = ctx.context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: return@withContext ToolResults.error("insert_failed", "The calendar rejected the event.")
        val eventId = ContentUris.parseId(uri)
        Calendars.setReminder(ctx.context.contentResolver, eventId, args.int("reminder_minutes") ?: 10)
        ToolResults.ok(
            "status" to "created", "id" to eventId, "title" to title,
            "start" to LocalTimes.format(LocalTimes.millis(start), allDay), "end" to LocalTimes.format(LocalTimes.millis(end), allDay),
            "calendar" to calendars.firstOrNull { it.id == calendarId }?.name,
            "all_day" to allDay.takeIf { it }, "repeats" to (rule != null).takeIf { it },
        )
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

    fun byName(calendars: List<CalendarInfo>, name: String): CalendarInfo? {
        val folded = InstalledApps.fold(name)
        return calendars.firstOrNull { InstalledApps.fold(it.name) == folded }
            ?: calendars.firstOrNull { folded in InstalledApps.fold(it.name) || folded in InstalledApps.fold(it.account) }
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

object Recurrence {
    private val DAYS = mapOf("mon" to "MO", "tue" to "TU", "wed" to "WE", "thu" to "TH", "fri" to "FR", "sat" to "SA", "sun" to "SU")

    fun weekly(days: List<String>, until: String?): String? {
        val codes = days.map { DAYS[it.trim().lowercase().take(3)] ?: return null }.distinct()
        val end = until?.takeIf { it.isNotBlank() }?.let { text ->
            val date = runCatching { LocalDate.parse(text.trim().take(10)) }.getOrNull() ?: return null
            ";UNTIL=${date.format(DateTimeFormatter.BASIC_ISO_DATE)}T235959Z"
        }.orEmpty()
        return "FREQ=WEEKLY;BYDAY=${codes.joinToString(",")}$end"
    }

    fun duration(millis: Long): String = "P${(millis / 1000).coerceAtLeast(60)}S"
}

object EventMatch {
    suspend fun resolve(args: JsonObject, ctx: ToolContext, addTitle: Boolean = true): Resolution {
        args.long("id")?.let { id ->
            if (!addTitle) return Resolution.Ready(args)
            val row = withContext(Dispatchers.IO) {
                ctx.context.contentResolver.query(
                    ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
                    arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART, CalendarContract.Events.ALL_DAY, CalendarContract.Events.RRULE),
                    null, null, null,
                )?.use { c -> if (c.moveToFirst()) Triple(c.getString(0).orEmpty(), LocalTimes.format(c.getLong(1), c.getInt(2) == 1), !c.getString(3).isNullOrBlank()) else null }
            } ?: return Resolution.Failed(ToolResults.error("not_found", "No event with id $id. Use list_events."))
            return Resolution.Ready(JsonObject(args + ("title" to JsonPrimitive(row.first)) + ("when" to JsonPrimitive(row.second)) + ("repeats" to JsonPrimitive(row.third))))
        }
        val match = args.string("match")?.takeIf { it.isNotBlank() } ?: return Resolution.Failed(ToolResults.error("bad_arguments", "Give id or match."))
        val on = args.string("on")?.let { LocalTimes.parse(it) }?.toLocalDate()
        val from = on?.atStartOfDay() ?: LocalDateTime.now().minusHours(2)
        val to = on?.plusDays(1)?.atStartOfDay() ?: LocalDateTime.now().plusDays(SEARCH_DAYS)
        val found = withContext(Dispatchers.IO) { instances(ctx.context.contentResolver, from, to) }.filter { matches(it.second, match) }
        val events = found.distinctBy { it.first }
        return when (events.size) {
            0 -> Resolution.Failed(ToolResults.error("not_found", "No event matches \"$match\"${on?.let { " on $it" }.orEmpty()}. Use list_events."))
            1 -> {
                val repeats = withContext(Dispatchers.IO) { repeats(ctx.context.contentResolver, events[0].first) }
                Resolution.Ready(
                    JsonObject(
                        args + ("id" to JsonPrimitive(events[0].first)) + ("when" to JsonPrimitive(events[0].third)) + ("repeats" to JsonPrimitive(repeats)) +
                            listOfNotNull(if (addTitle) "title" to JsonPrimitive(events[0].second) else null),
                    ),
                )
            }
            else -> Resolution.Failed(
                ToolResults.error("ambiguous", "Several events match: ${events.take(6).joinToString("; ") { "id ${it.first}: ${it.second} ${it.third}" }}. Ask which one, then pass its id."),
            )
        }
    }

    suspend fun nextWithLocation(resolver: ContentResolver, words: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        val now = LocalDateTime.now()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, LocalTimes.millis(now.minusHours(2)))
            ContentUris.appendId(it, LocalTimes.millis(now.plusDays(NEXT_DAYS)))
        }.build()
        val projection = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.EVENT_LOCATION)
        resolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                val title = c.getString(0).orEmpty()
                val location = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                if (matches(title, words)) return@withContext title to location
            }
            null
        }
    }

    private fun repeats(resolver: ContentResolver, id: Long): Boolean =
        resolver.query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), arrayOf(CalendarContract.Events.RRULE), null, null, null)
            ?.use { c -> c.moveToFirst() && !c.getString(0).isNullOrBlank() } == true

    fun matches(title: String, match: String): Boolean {
        val folded = InstalledApps.fold(title)
        val words = InstalledApps.fold(match).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return false
        return words.filter { it.length > 2 }.ifEmpty { words }.all { it in folded }
    }

    private fun instances(resolver: ContentResolver, from: LocalDateTime, to: LocalDateTime): List<Triple<Long, String, String>> {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, LocalTimes.millis(from))
            ContentUris.appendId(it, LocalTimes.millis(to))
        }.build()
        val projection = arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY)
        val out = mutableListOf<Triple<Long, String, String>>()
        resolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) out += Triple(c.getLong(0), c.getString(1).orEmpty(), LocalTimes.format(c.getLong(2), c.getInt(3) == 1))
        }
        return out
    }

    private const val SEARCH_DAYS = 90L
    private const val NEXT_DAYS = 7L
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
        "Change an event: by id from list_events, or directly by match (words of its title) and optionally on (its date), without listing first. " +
            "Pass only the fields to change. A new start without end keeps the duration. " +
            "Changes to a repeating event apply to every repeat, and its times cannot be changed."
    override val parameters = schema {
        integer("id", "Event id from list_events.")
        string("match", "Words of the event title, e.g. \"dentista\". The next 90 days are searched.")
        string("on", "Date of the event, yyyy-MM-dd, when match alone is ambiguous.")
        string("title", "New title.")
        string("move_to_date", "New date, yyyy-MM-dd, keeping the start time and duration (\"move it to Friday\"). The current date goes in on.")
        string("start", "New local start as yyyy-MM-ddTHH:mm, or yyyy-MM-dd for all day.")
        string("end", "New local end.")
        string("location", "New location. Empty text clears it.")
        string("description", "New notes. Empty text clears them.")
        integer("reminder_minutes", "New reminder, minutes before start.")
    }

    override fun summary(args: JsonObject) = args.string("title") ?: args.string("match") ?: args.string("start") ?: args.long("id")?.toString().orEmpty()

    override suspend fun resolve(args: JsonObject, ctx: ToolContext): Resolution = EventMatch.resolve(args, ctx, addTitle = false)

    override fun endsTurn(args: JsonObject, result: JsonObject) = result.isDone()

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject = withContext(Dispatchers.IO) {
        val id = args.long("id") ?: return@withContext ToolResults.error("bad_arguments", "id is required.")
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
        val dateText = args.string("move_to_date")?.takeIf { it.isNotBlank() }
        val startText = args.string("start") ?: dateText?.let { current.onDate(it) ?: return@withContext ToolResults.error("bad_arguments", "move_to_date must be yyyy-MM-dd.") }
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
        val start = values.getAsLong(CalendarContract.Events.DTSTART) ?: current.start
        val end = values.getAsLong(CalendarContract.Events.DTEND) ?: current.end
        val allDay = values.getAsInteger(CalendarContract.Events.ALL_DAY)?.let { it == 1 } ?: current.allDay
        ToolResults.ok(
            "status" to "updated", "id" to id, "title" to (values.getAsString(CalendarContract.Events.TITLE) ?: current.title),
            "start" to LocalTimes.format(start, allDay), "end" to LocalTimes.format(end, allDay),
        )
    }
}

data class EventRow(val title: String, val start: Long, val end: Long, val allDay: Boolean, val repeats: Boolean) {
    fun onDate(date: String): String? {
        val day = runCatching { LocalDate.parse(date.trim().take(10)) }.getOrNull() ?: return null
        if (allDay) return day.toString()
        val time = LocalTimes.parse(LocalTimes.format(start, false))!!.toLocalTime()
        return day.atTime(time).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
    }

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
    override val description =
        "Delete an event: by id from list_events, or directly by match (words of its title) and optionally on (its date). " +
            "A repeating event is deleted with every repeat; deleting one date only is not supported, so tell the user."
    override val parameters = schema {
        integer("id", "Event id from list_events.")
        string("match", "Words of the event title. The next 90 days are searched.")
        string("on", "Date of the event, yyyy-MM-dd.")
    }

    override fun summary(args: JsonObject) = args.string("title") ?: args.string("match") ?: args.long("id")?.toString().orEmpty()

    override suspend fun resolve(args: JsonObject, ctx: ToolContext): Resolution = EventMatch.resolve(args, ctx)

    override fun confirmationPrompt(args: JsonObject, res: Resources): String {
        val name = summary(args)
        val time = args.string("when")
        return when {
            args.boolean("repeats") == true -> res.getString(R.string.confirm_delete_event_series, name)
            time != null -> res.getString(R.string.confirm_delete_event_on, name, time.replace('T', ' '))
            else -> res.getString(R.string.confirm_delete_event, name)
        }
    }

    override fun endsTurn(args: JsonObject, result: JsonObject) = result.isDone()

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject = withContext(Dispatchers.IO) {
        val id = args.long("id") ?: return@withContext ToolResults.error("bad_arguments", "id is required.")
        val deleted = ctx.context.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null)
        if (deleted == 0) ToolResults.error("not_found", "No event with id $id. Use list_events.") else ToolResults.ok("status" to "deleted", "id" to id)
    }
}
