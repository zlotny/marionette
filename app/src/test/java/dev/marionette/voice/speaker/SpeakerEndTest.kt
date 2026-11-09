package dev.marionette.voice.speaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerEndTest {
    private val hop = 5_120L

    private fun SpeakerEnd.feed(from: Int, to: Int, score: Float, speech: Boolean = true) {
        for (i in from until to) scored(i * hop, score, speech)
    }

    @Test
    fun `never ends before the user was seen`() {
        val end = SpeakerEnd(SpeakerEnd.HIGH)
        end.feed(1, 40, 0.05f)
        assertNull(end.cutSample)
    }

    @Test
    fun `ends after the absent limit and cuts near the last voice`() {
        val end = SpeakerEnd(SpeakerEnd.HIGH)
        end.feed(1, 10, 0.6f)
        end.feed(10, 16, 0.05f)
        assertNull(end.cutSample)
        end.feed(16, 17, 0.05f)
        assertEquals(9 * hop + SpeakerEnd.CUT_MARGIN_SAMPLES, end.cutSample)
    }

    @Test
    fun `a mid score keeps the turn open`() {
        val end = SpeakerEnd(SpeakerEnd.HIGH)
        end.feed(1, 5, 0.6f)
        for (i in 5 until 40) end.scored(i * hop, if (i % 5 == 0) 0.2f else 0.05f, true)
        assertNull(end.cutSample)
    }

    @Test
    fun `silence does not count as absent`() {
        val end = SpeakerEnd(SpeakerEnd.HIGH)
        end.feed(1, 5, 0.6f)
        end.feed(5, 40, 0.05f, speech = false)
        assertNull(end.cutSample)
    }

    @Test
    fun `a dropped hop still counts its audio`() {
        val end = SpeakerEnd(SpeakerEnd.HIGH)
        end.feed(1, 5, 0.6f)
        end.scored(12 * hop, 0.05f, true)
        assertTrue(end.cutSample != null)
    }

    @Test
    fun `the voice coming back after the end is reported and rearm clears it`() {
        val end = SpeakerEnd(SpeakerEnd.HIGH)
        end.feed(1, 5, 0.6f)
        end.feed(5, 12, 0.05f)
        assertTrue(end.cutSample != null)
        assertFalse(end.voiceBack)
        end.scored(12 * hop, 0.5f, true)
        assertTrue(end.voiceBack)
        end.rearm()
        assertNull(end.cutSample)
        assertFalse(end.voiceBack)
    }
}
