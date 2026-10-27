package dev.marionette.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplyLanguageTest {
    private val languages = listOf("gl-ES", "es-ES", "en-US")

    @Test
    fun picksTheBestScoredLanguage() {
        assertEquals("en-US", ReplyLanguage.pick(mapOf("en" to 0.9f, "es" to 0.1f), languages))
        assertEquals("es-ES", ReplyLanguage.pick(mapOf("es" to 0.6f, "gl" to 0.4f), languages))
    }

    @Test
    fun ignoresLanguagesOutsideTheSet() {
        assertEquals("es-ES", ReplyLanguage.pick(mapOf("pt" to 0.8f, "es" to 0.2f), languages))
    }

    @Test
    fun fallsBackToTheMainLanguage() {
        assertEquals("gl-ES", ReplyLanguage.pick(emptyMap(), languages))
        assertEquals("gl-ES", ReplyLanguage.pick(mapOf("fr" to 1f), languages))
    }
}
