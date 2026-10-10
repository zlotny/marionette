package dev.marionette.voice

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HoldPhrasesTest {
    @Test
    fun emptyListGivesNothing() {
        assertNull(HoldPhrases.pick(emptyList(), null))
        assertNull(HoldPhrases.pick(listOf(" "), null))
    }

    @Test
    fun neverRepeatsThePreviousPhrase() {
        val phrases = listOf("One second.", "On it.")
        repeat(20) { seed -> assertNotEquals("On it.", HoldPhrases.pick(phrases, "On it.", Random(seed))) }
    }

    @Test
    fun singlePhraseRepeats() {
        assertEquals("On it.", HoldPhrases.pick(listOf("On it."), "On it."))
    }
}
