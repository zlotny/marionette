package dev.marionette.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class LocalTimesTest {
    @Test
    fun parsesDateTimesInEveryAcceptedShape() {
        val fourPm = LocalDateTime.of(2026, 5, 2, 16, 0)
        assertEquals(fourPm, LocalTimes.parse("2026-05-02T16:00"))
        assertEquals(fourPm, LocalTimes.parse(" 2026-05-02 16:00 "))
        assertEquals(fourPm, LocalTimes.parse("2026-05-02T16:00Z"))
        assertEquals(fourPm.withSecond(30), LocalTimes.parse("2026-05-02T16:00:30"))
        assertEquals(LocalDateTime.of(2026, 5, 2, 0, 0), LocalTimes.parse("2026-05-02"))
    }

    @Test
    fun rejectsBlankAndFreeText() {
        assertNull(LocalTimes.parse(null))
        assertNull(LocalTimes.parse("  "))
        assertNull(LocalTimes.parse("mañana a las 5"))
        assertNull(LocalTimes.parse("2026-13-02"))
    }

    @Test
    fun tellsDateOnlyInput() {
        assertTrue(LocalTimes.isDateOnly("2026-05-02"))
        assertTrue(LocalTimes.isDateOnly(" 2026-05-02 "))
        assertFalse(LocalTimes.isDateOnly("2026-05-02T16:00"))
        assertFalse(LocalTimes.isDateOnly(null))
    }

    @Test
    fun formatsAllDayEventsInUtc() {
        val midnightUtc = LocalDateTime.of(2026, 5, 2, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals("2026-05-02", LocalTimes.format(midnightUtc, allDay = true))
    }

    @Test
    fun formatsLocalTimesToTheMinute() {
        val millis = LocalTimes.millis(LocalDateTime.of(2026, 5, 2, 16, 5, 59))
        assertEquals("2026-05-02T16:05", LocalTimes.format(millis, allDay = false))
    }
}
