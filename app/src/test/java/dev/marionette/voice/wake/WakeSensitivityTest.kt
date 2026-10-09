package dev.marionette.voice.wake

import org.junit.Assert.assertEquals
import org.junit.Test

class WakeSensitivityTest {
    @Test
    fun mapsEndsOfTheSlider() {
        assertEquals(WakeSensitivity.MIN_THRESHOLD, WakeSensitivity.toThreshold(0f), 1e-6f)
        assertEquals(WakeSensitivity.MAX_THRESHOLD, WakeSensitivity.toThreshold(1f), 1e-6f)
    }

    @Test
    fun defaultThresholdIsMidway() {
        assertEquals(0.5f, WakeSensitivity.fromThreshold(0.14f), 1e-6f)
    }

    @Test
    fun roundTrips() {
        listOf(0f, 0.25f, 0.5f, 0.8f, 1f).forEach {
            assertEquals(it, WakeSensitivity.fromThreshold(WakeSensitivity.toThreshold(it)), 1e-5f)
        }
    }

    @Test
    fun clampsOutOfRangeValues() {
        assertEquals(WakeSensitivity.MAX_THRESHOLD, WakeSensitivity.toThreshold(3f), 1e-6f)
        assertEquals(0f, WakeSensitivity.fromThreshold(0.01f), 1e-6f)
        assertEquals(1f, WakeSensitivity.fromThreshold(0.5f), 1e-6f)
    }
}
