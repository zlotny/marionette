package dev.marionette.agent

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.provider.CalendarContract
import android.provider.CallLog
import androidx.core.content.ContextCompat
import dev.marionette.media.MediaSessions
import dev.marionette.notifications.MarionetteNotificationListener
import dev.marionette.notifications.body
import dev.marionette.notifications.replyAction
import dev.marionette.notifications.title
import dev.marionette.tools.ClockTimers
import dev.marionette.tools.NavigateTool
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

object PhoneActivity {
    private const val MAX_SENDERS = 4
    private const val MAX_SNIPPET = 60
    private const val MAX_CHARS = 300
    private const val MAX_EVENTS = 5
    private const val MAX_MISSED = 3
    private const val NEXT_EVENT_DAYS = 7L
    private const val MISSED_WINDOW_MILLIS = 24 * 3_600_000L

    fun media(context: Context, held: (String?) -> Boolean): String? {
        if (!MediaSessions.available()) return null
        val sessions = MediaSessions.active(context)
        val controller = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING || held(it.packageName) } ?: return "nothing playing"
        val app = label(context, controller.packageName)
        val meta = controller.metadata
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val track = listOfNotNull(title?.let { "\"$it\"" }, artist?.let { "by $it" }).joinToString(" ")
        val state = if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) "playing" else "playing (lowered while the user talks)"
        return listOf("$app $state", track).filter { it.isNotBlank() }.joinToString(": ")
    }

    fun clock(context: Context): String {
        val now = System.currentTimeMillis()
        val timers = ClockTimers.list().map { timer ->
            val label = timer.label?.let { "\"$it\" " }.orEmpty()
            val left = timer.endsAt?.let { ", ${minutesText(it - now)} left, ends ${hour(it)}" } ?: timer.text?.let { ", shows $it" }.orEmpty()
            "timer $label${timer.state}$left"
        }
        val next = context.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.let { "next alarm ${day(it.triggerTime)} ${hour(it.triggerTime)}" }
        return (timers.ifEmpty { listOf("no timers") } + (next ?: "no alarm set")).joinToString("; ")
    }

    fun messages(context: Context): String? {
        val listener = MarionetteNotificationListener.instance ?: return null
        val byApp = listener.activeNotifications.orEmpty()
            .filter { it.packageName != context.packageName && it.replyAction != null && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .sortedByDescending { it.postTime }
            .groupBy { it.packageName }
        if (byApp.isEmpty()) return "none"
        return byApp.entries.joinToString("; ") { (pkg, items) ->
            val senders = items.distinctBy { it.title }.take(MAX_SENDERS).joinToString(", ") { sbn ->
                val snippet = sbn.body?.replace('\n', ' ')?.let { " \"${cut(it, MAX_SNIPPET)}\"" }.orEmpty()
                "${sbn.title}$snippet ${stamp(sbn.postTime)}"
            }
            "${label(context, pkg)}: $senders"
        }.let { cut(it, MAX_CHARS) }
    }

    fun missedCalls(context: Context): String? {
        if (!granted(context, Manifest.permission.READ_CALL_LOG)) return null
        val since = System.currentTimeMillis() - MISSED_WINDOW_MILLIS
        val projection = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE)
        val selection = "${CallLog.Calls.TYPE} = ${CallLog.Calls.MISSED_TYPE} AND ${CallLog.Calls.DATE} > $since AND ${CallLog.Calls.NEW} = 1"
        val calls = context.contentResolver.query(CallLog.Calls.CONTENT_URI, projection, selection, null, "${CallLog.Calls.DATE} DESC")?.use { c ->
            buildList {
                while (c.moveToNext() && size < MAX_MISSED) {
                    val who = c.getString(1)?.takeIf { it.isNotBlank() } ?: c.getString(0)?.takeIf { it.isNotBlank() } ?: "private number"
                    add("$who ${stamp(c.getLong(2))}")
                }
            }
        }.orEmpty()
        return calls.joinToString(", ").ifEmpty { "none" }
    }

    fun agenda(context: Context): String? {
        if (!granted(context, Manifest.permission.READ_CALENDAR)) return null
        val now = System.currentTimeMillis()
        val endOfDay = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val today = events(context, now, endOfDay, MAX_EVENTS).map { (title, begin, end, allDay, place) ->
            val time = if (begin <= now) "now until ${hour(end)}" else "${hour(begin)} (in ${minutesText(begin - now)})"
            if (allDay) "all day \"$title\"" else "$time \"$title\"${place?.let { " at $it" }.orEmpty()}"
        }
        if (today.isNotEmpty()) return "today: ${today.joinToString("; ")}".take(MAX_CHARS)
        val next = events(context, endOfDay, endOfDay + NEXT_EVENT_DAYS * 86_400_000L, 1).firstOrNull()
            ?: return "nothing today or in the next $NEXT_EVENT_DAYS days"
        val (title, begin, _, allDay, place) = next
        return "nothing more today; next ${day(begin, utc = allDay)}${if (allDay) " all day" else " ${hour(begin)}"} \"$title\"${place?.let { " at $it" }.orEmpty()}".take(MAX_CHARS)
    }

    fun navigation(): String? {
        val listener = MarionetteNotificationListener.instance ?: return null
        val maps = listener.activeNotifications.orEmpty().firstOrNull { it.packageName == NavigateTool.MAPS_PACKAGE && it.isOngoing } ?: return "none"
        return listOfNotNull("Google Maps active", maps.title, maps.body).joinToString(", ").take(160)
    }

    private data class Event(val title: String, val begin: Long, val end: Long, val allDay: Boolean, val place: String?)

    private fun events(context: Context, from: Long, to: Long, limit: Int): List<Event> {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, to)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.END,
        )
        return context.contentResolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            buildList {
                while (c.moveToNext() && size < limit) {
                    val allDay = c.getInt(2) == 1
                    if (!allDay && c.getLong(4) <= from) continue
                    add(Event(c.getString(0).orEmpty(), c.getLong(1), c.getLong(4), allDay, c.getString(3)?.takeIf { it.isNotBlank() }))
                }
            }
        }.orEmpty()
    }

    private fun cut(text: String, max: Int) = ActivityText.cut(text, max)

    private fun stamp(millis: Long) = ActivityText.stamp(millis, LocalDate.now(), ZoneId.systemDefault())

    private fun minutesText(millis: Long) = ActivityText.minutes(millis)

    private fun hour(millis: Long) = ActivityText.hour(millis, ZoneId.systemDefault())

    private fun day(millis: Long, utc: Boolean = false) = ActivityText.day(millis, LocalDate.now(), if (utc) ZoneOffset.UTC else ZoneId.systemDefault())

    private fun label(context: Context, pkg: String): String =
        runCatching { context.packageManager.let { pm -> pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } }.getOrDefault(pkg)

    private fun granted(context: Context, permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

internal object ActivityText {
    private val HOUR = DateTimeFormatter.ofPattern("HH:mm")

    fun cut(text: String, max: Int): String = if (text.length <= max) text else text.take(max - 1).substringBeforeLast(' ').trimEnd() + "…"

    fun minutes(millis: Long): String {
        val minutes = (millis / 60_000L).coerceAtLeast(0)
        return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
    }

    fun hour(millis: Long, zone: ZoneId): String = Instant.ofEpochMilli(millis).atZone(zone).format(HOUR)

    fun stamp(millis: Long, today: LocalDate, zone: ZoneId): String = when (Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()) {
        today -> hour(millis, zone)
        today.minusDays(1) -> "yesterday ${hour(millis, zone)}"
        else -> "${day(millis, today, zone)} ${hour(millis, zone)}"
    }

    fun day(millis: Long, today: LocalDate, zone: ZoneId): String {
        val date = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        val name = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
        return when (date) {
            today -> "today ($name)"
            today.plusDays(1) -> "tomorrow ($name)"
            else -> "$name $date"
        }
    }
}
