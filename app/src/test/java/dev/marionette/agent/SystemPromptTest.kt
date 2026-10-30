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
    fun plainModelGetsInstructions() {
        assertEquals("You are Ada.", prompt.build(plain))
    }

    @Test
    fun customInstructionsReplaceDefault() {
        assertEquals("Be brief, Ada.", prompt.build(plain.copy(instructions = "Be brief, {{name}}.")))
    }

    @Test
    fun instructionsCanBeOff() {
        assertEquals("", prompt.build(plain.copy(sendInstructions = false)))
    }

    @Test
    fun agentGetsNoSystemText() {
        assertEquals("", prompt.build(plain.copy(provider = Provider.OPENCLAW)))
    }

    @Test
    fun contextLeadsTheUserMessage() {
        assertEquals("<phone_context>\ntime: now\n</phone_context>\n\npon música", SystemPrompt.withContext("pon música", "time: now"))
        assertEquals("pon música", SystemPrompt.withContext("pon música", ""))
    }

    @Test
    fun unlockBlocksAreDetected() {
        assertTrue(UnlockTip.isUnlockBlock(JsonObject(mapOf("ok" to JsonPrimitive(true), "status" to JsonPrimitive("waiting_for_unlock")))))
        assertTrue(UnlockTip.isUnlockBlock(dev.marionette.tools.ToolResults.needsUnlock()))
        assertFalse(UnlockTip.isUnlockBlock(JsonObject(mapOf("ok" to JsonPrimitive(true)))))
    }
}
