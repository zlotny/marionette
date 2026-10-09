package dev.marionette.spike.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FuzzyMatcherTest {
    private val variants = listOf("HEY MARIONETTE", "HAY MARIANNET")

    @Test
    fun exactSubstringHasZeroDistance() {
        assertEquals(0, FuzzyMatcher.substringDistance("ABC", "XXABCXX"))
    }

    @Test
    fun matchesPronunciationVariantInsideSentence() {
        assertNotNull(FuzzyMatcher.find("OKAY HEY MARIONET TAKE ME HOME", variants, 0.25f))
    }

    @Test
    fun rejectsUnrelatedSpeech() {
        assertNull(FuzzyMatcher.find("THE MARKET OPENS AT NINE", variants, 0.25f))
    }

    @Test
    fun rejectsPartialPhrase() {
        assertNull(FuzzyMatcher.find("HEY MARI", variants, 0.25f))
    }
}
