package dev.marionette.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietHoursTest {
    @Test
    fun windowAcrossMidnight() {
        assertTrue(QuietHours.contains(23 * 60, 22 * 60, 7 * 60))
        assertTrue(QuietHours.contains(3 * 60, 22 * 60, 7 * 60))
        assertFalse(QuietHours.contains(7 * 60, 22 * 60, 7 * 60))
        assertFalse(QuietHours.contains(12 * 60, 22 * 60, 7 * 60))
    }

    @Test
    fun windowWithinDay() {
        assertTrue(QuietHours.contains(90, 60, 8 * 60))
        assertFalse(QuietHours.contains(30, 60, 8 * 60))
        assertFalse(QuietHours.contains(600, 600, 600))
    }

    @Test
    fun minutesUntilEndWraps() {
        assertEquals(60, QuietHours.minutesUntilEnd(6 * 60, 7 * 60))
        assertEquals(9 * 60, QuietHours.minutesUntilEnd(22 * 60, 7 * 60))
    }
}
