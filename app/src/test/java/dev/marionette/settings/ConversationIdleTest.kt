package dev.marionette.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationIdleTest {
    private fun minutes(n: Int) = n * 60_000L

    @Test
    fun defaultKeepsFiveMinutes() {
        val s = AppSettings()
        assertFalse(s.conversationExpired(minutes(5)))
        assertTrue(s.conversationExpired(minutes(5) + 1))
    }

    @Test
    fun usesChosenMinutes() {
        val s = AppSettings(conversationMinutes = 60)
        assertFalse(s.conversationExpired(minutes(59)))
        assertTrue(s.conversationExpired(minutes(61)))
    }

    @Test
    fun continuousNeverExpires() {
        assertFalse(AppSettings(continueConversation = true).conversationExpired(minutes(100_000)))
    }

    @Test
    fun stopOfFindsNearestStop() {
        assertEquals(ConversationIdle.STOPS.indexOf(30), ConversationIdle.stopOf(30))
        assertEquals(0, ConversationIdle.stopOf(1))
        assertEquals(ConversationIdle.STOPS.lastIndex, ConversationIdle.stopOf(10_000))
    }
}
