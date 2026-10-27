package dev.marionette.voice

import dev.marionette.voice.stt.JunkTranscript
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JunkTranscriptTest {
    private val languages = emptyList<String>()

    @Test
    fun shortAndFillerTextIsJunk() {
        assertTrue(JunkTranscript.isJunk("", languages))
        assertTrue(JunkTranscript.isJunk("a", languages))
        assertTrue(JunkTranscript.isJunk("Ok, vamos.", languages))
        assertTrue(JunkTranscript.isJunk("Eh... vale", languages))
    }

    @Test
    fun realRequestsAreKept() {
        assertFalse(JunkTranscript.isJunk("pon un temporizador de diez minutos", languages))
        assertFalse(JunkTranscript.isJunk("¿Qué hora es?", languages))
        assertFalse(JunkTranscript.isJunk("para", languages))
    }
}
