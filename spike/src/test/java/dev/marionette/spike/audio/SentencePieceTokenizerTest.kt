package dev.marionette.spike.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SentencePieceTokenizerTest {
    private val tokenizer = File("src/main/assets/kws/bpe.model").inputStream().use { SentencePieceTokenizer.fromModel(it) }

    private fun tokens(phrase: String) = (tokenizer.encode(phrase) as SentencePieceTokenizer.Result.Ok).tokens.joinToString(" ")

    @Test
    fun matchesReferenceKeywords() {
        assertEquals("▁HE LL O ▁WORLD", tokens("hello world"))
        assertEquals("▁HI ▁GO O G LE", tokens("Hi Google"))
        assertEquals("▁HE Y ▁S I RI", tokens("hey siri"))
        assertEquals("▁A LE X A", tokens("alexa"))
        assertEquals("▁LOVE ▁AND ▁P E A CE", tokens("love and peace"))
        assertEquals("▁HA PP Y ▁NEW ▁YEAR", tokens("happy new year"))
        assertEquals("▁ME R RY ▁ CH R IST MA S", tokens("merry christmas"))
    }

    @Test
    fun stripsAccentsForSpanish() {
        assertEquals(tokens("OYE MARIONETA"), tokens("oye marionetá"))
    }

    @Test
    fun rejectsEmptyPhrase() {
        assertTrue(tokenizer.encode("  ") is SentencePieceTokenizer.Result.Unsupported)
    }
}
