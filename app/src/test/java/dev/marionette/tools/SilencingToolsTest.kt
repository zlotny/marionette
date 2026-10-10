package dev.marionette.tools

import dev.marionette.core.jsonOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilencingToolsTest {
    private val media = MediaControlTool()

    @Test
    fun startingPlaybackSilencesTheTurn() {
        listOf("play", "next", "previous").forEach {
            assertTrue(media.silencesTurn(jsonOf("action" to it), ToolResults.ok("state" to "playing")))
        }
        assertTrue(PlayMediaTool().silencesTurn(jsonOf("query" to "jazz"), ToolResults.ok("status" to "playing")))
        assertTrue(CallTool().silencesTurn(jsonOf("number" to "123"), ToolResults.ok()))
    }

    @Test
    fun pausingKeepsTheConversation() {
        assertFalse(media.silencesTurn(jsonOf("action" to "pause"), ToolResults.ok("state" to "paused")))
        assertFalse(media.silencesTurn(jsonOf("action" to "status"), ToolResults.ok("state" to "playing")))
        assertFalse(media.silencesTurn(jsonOf("action" to "toggle"), ToolResults.ok("state" to "paused")))
    }

    @Test
    fun failuresAreSpoken() {
        assertFalse(media.silencesTurn(jsonOf("action" to "play"), ToolResults.error("x")))
        assertFalse(CallTool().silencesTurn(jsonOf("number" to "123"), ToolResults.error("call_failed")))
        assertFalse(PlayMediaTool().silencesTurn(jsonOf("query" to "jazz"), ToolResults.error("not_playing")))
        assertFalse(PlayMediaTool().silencesTurn(jsonOf("url" to "https://x"), ToolResults.ok("status" to "waiting_for_unlock")))
    }
}
