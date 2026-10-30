package dev.marionette.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class ClockTimerStateTest {
    @Test
    fun readsStateFromButtons() {
        assertEquals("running", ClockTimers.state(listOf("Pause", "+1:00")))
        assertEquals("paused", ClockTimers.state(listOf("Resume", "Reset")))
        assertEquals("ringing", ClockTimers.state(listOf("Stop", "+1:00")))
        assertEquals("paused", ClockTimers.state(listOf("Reanudar", "Restablecer")))
        assertEquals("running", ClockTimers.state(listOf("Detener", "Pausar")))
    }

    @Test
    fun matchesWholeWordsNotParts() {
        assertEquals("running", ClockTimers.state(listOf("Pausar", "Reiniciar")))
        assertEquals("running", ClockTimers.state(listOf("Pause", "Restart")))
    }
}
