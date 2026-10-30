package dev.marionette.agent

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class ActivityTextTest {
    private val today = LocalDate.of(2026, 10, 30)
    private fun at(text: String) = Instant.parse(text).toEpochMilli()

    @Test
    fun cutsAtAWordBoundary() {
        assertEquals("short", ActivityText.cut("short", 10))
        assertEquals("hola mundo…", ActivityText.cut("hola mundo cruel", 12))
        assertEquals("abcd…", ActivityText.cut("abcdefghijkl", 5))
    }

    @Test
    fun spellsDurations() {
        assertEquals("59 min", ActivityText.minutes(59 * 60_000L))
        assertEquals("2 h 5 min", ActivityText.minutes(125 * 60_000L))
        assertEquals("0 min", ActivityText.minutes(-5_000L))
    }

    @Test
    fun stampsRelativeToToday() {
        assertEquals("09:05", ActivityText.stamp(at("2026-10-30T09:05:00Z"), today, ZoneOffset.UTC))
        assertEquals("yesterday 22:00", ActivityText.stamp(at("2026-10-29T22:00:00Z"), today, ZoneOffset.UTC))
        assertEquals("Tue 2026-10-27 08:00", ActivityText.stamp(at("2026-10-27T08:00:00Z"), today, ZoneOffset.UTC))
    }

    @Test
    fun allDayEventsUseTheUtcDate() {
        val allDay = at("2026-10-31T00:00:00Z")
        assertEquals("tomorrow (Sat)", ActivityText.day(allDay, today, ZoneOffset.UTC))
        assertEquals("today (Fri)", ActivityText.day(allDay, today, ZoneId.of("America/New_York")))
    }
}
