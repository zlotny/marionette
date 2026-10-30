package dev.marionette.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ScheduleTimesTest {
    private val zone = ZoneId.of("Europe/Madrid")
    private val now = millis(2026, 10, 29, 22, 0)

    private fun millis(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun laterTodayStaysToday() = assertEquals(millis(2026, 10, 29, 23, 30), ScheduleTimes.parse("23:30", now, zone))

    @Test
    fun pastTimeMovesToTomorrow() = assertEquals(millis(2026, 10, 30, 8, 0), ScheduleTimes.parse("8:00", now, zone))

    @Test
    fun datedTimeIsExact() = assertEquals(millis(2026, 11, 2, 9, 15), ScheduleTimes.parse("2026-11-02 09:15", now, zone))

    @Test
    fun isoSeparatorWorks() = assertEquals(millis(2026, 11, 2, 9, 15), ScheduleTimes.parse("2026-11-02T09:15", now, zone))

    @Test
    fun junkIsRejected() {
        assertNull(ScheduleTimes.parse("mañana", now, zone))
        assertNull(ScheduleTimes.parse("2026-13-02 09:15", now, zone))
        assertNull(ScheduleTimes.parse(null, now, zone))
    }
}
