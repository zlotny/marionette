package dev.marionette.tools

import android.provider.CallLog
import android.provider.Telephony
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class PhoneDataTest {
    @Test
    fun parsesClockTimes() {
        assertEquals(7 to 30, ClockTimes.parse("7:30"))
        assertEquals(23 to 5, ClockTimes.parse(" 23.05 "))
        assertNull(ClockTimes.parse("24:00"))
        assertNull(ClockTimes.parse("7"))
    }

    @Test
    fun matchesNumbersByLastDigits() {
        assertTrue(PhoneNumbers.same("+34 600 111 222", "600111222"))
        assertFalse(PhoneNumbers.same("+34600111222", "+34600111223"))
        assertTrue(PhoneNumbers.same("Amazon", "amazon"))
    }

    @Test
    fun filtersByNameNumberOrSender() {
        val filter = ContactFilter("lucia", listOf("+34600111222"))
        assertTrue(filter.matches("600111222", null))
        assertTrue(filter.matches("+1555", "Lucía Martínez"))
        assertFalse(filter.matches("+1555", "Pedro"))
        assertTrue(ContactFilter("bank", emptyList()).matches("MyBank", null))
    }

    @Test
    fun namesMessageAndCallKinds() {
        assertEquals("received", MessageKinds.sms(Telephony.Sms.MESSAGE_TYPE_INBOX))
        assertEquals("sent", MessageKinds.sms(Telephony.Sms.MESSAGE_TYPE_SENT))
        assertEquals("missed", MessageKinds.call(CallLog.Calls.MISSED_TYPE))
        assertEquals("outgoing", MessageKinds.call(CallLog.Calls.OUTGOING_TYPE))
    }

    @Test
    fun retimeKeepsDuration() {
        val start = LocalTimes.millis(LocalDateTime.of(2026, 5, 1, 10, 0))
        val row = EventRow("Dentist", start, start + 90 * 60_000L, allDay = false, repeats = false)
        val (newStart, newEnd, allDay) = row.retime("2026-05-02T16:00", null)!!
        assertEquals(LocalDateTime.of(2026, 5, 2, 16, 0), newStart)
        assertEquals(LocalDateTime.of(2026, 5, 2, 17, 30), newEnd)
        assertFalse(allDay)
        assertNull(row.retime("2026-05-02T16:00", "2026-05-02T15:00"))
        assertTrue(row.retime("2026-05-03", null)!!.third)
    }

    @Test
    fun describesPhotos() {
        assertTrue(Photos.isScreenshot("Pictures/Screenshots/", null))
        assertFalse(Photos.isScreenshot("DCIM/Camera/", "Camera"))
        assertEquals("2 KB", Photos.size(1500))
        assertEquals("2.5 MB", Photos.size(2_621_440))
    }

    @Test
    fun fitsScreenshotsInLongSide() {
        assertEquals(473 to 1024, ScreenImages.fit(1280, 2772, 1024))
        assertEquals(800 to 600, ScreenImages.fit(800, 600, 1024))
    }
}
