package dev.marionette.voice.wake

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StopTextTest {
    private val labels = listOf("Marioneta, para", "Déjalo estar", "Cállate")
    private val ignored = listOf("Marioneta", "Hey Marioneta")

    @Test
    fun repeatedStopWordMatches() {
        assertTrue(StopText.matches("Para, para.", labels, ignored))
        assertTrue(StopText.matches("Marioneta para", labels, ignored))
    }

    @Test
    fun accentsAndCaseAreIgnored() {
        assertTrue(StopText.matches("dejalo estar", labels, ignored))
        assertTrue(StopText.matches("CÁLLATE!", labels, ignored))
    }

    @Test
    fun commandsWithStopWordsDoNotMatch() {
        assertFalse(StopText.matches("para la música", labels, ignored))
        assertFalse(StopText.matches("déjalo estar en el salón y luego llama a mamá por favor", labels, ignored))
        assertFalse(StopText.matches("", labels, ignored))
        assertFalse(StopText.matches("Marioneta", labels, ignored))
    }
}
