package dev.marionette.agent

import dev.marionette.llm.Provider
import dev.marionette.settings.AppSettings
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPromptTest {
    private val prompt = SystemPrompt("You are {{name}}.")
    private val plain = AppSettings(provider = Provider.OPENAI, agentName = "Ada")

    @Test
    fun plainModelGetsInstructionsAndContext() {
        assertEquals("You are Ada.\n\nPhone context:\ntime: now", prompt.build(plain, "time: now"))
    }

    @Test
    fun customInstructionsReplaceDefault() {
        assertEquals("Be brief, Ada.", prompt.build(plain.copy(instructions = "Be brief, {{name}}.", sendContext = false), "time: now"))
    }

    @Test
    fun instructionsCanBeOff() {
        assertEquals("Phone context:\ntime: now", prompt.build(plain.copy(sendInstructions = false), "time: now"))
    }

    @Test
    fun agentGetsOnlyContext() {
        val agent = plain.copy(provider = Provider.OPENCLAW)
        assertEquals("Phone context:\ntime: now", prompt.build(agent, "time: now"))
        assertEquals("", prompt.build(agent.copy(sendContext = false), "time: now"))
    }

    @Test
    fun unlockBlocksAreDetected() {
        assertTrue(UnlockTip.isUnlockBlock(JsonObject(mapOf("ok" to JsonPrimitive(true), "status" to JsonPrimitive("waiting_for_unlock")))))
        assertTrue(UnlockTip.isUnlockBlock(dev.marionette.tools.ToolResults.needsUnlock()))
        assertFalse(UnlockTip.isUnlockBlock(JsonObject(mapOf("ok" to JsonPrimitive(true)))))
    }
}
