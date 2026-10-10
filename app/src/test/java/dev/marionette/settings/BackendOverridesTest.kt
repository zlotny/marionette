package dev.marionette.settings

import dev.marionette.llm.Provider
import dev.marionette.llm.Thinking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackendOverridesTest {
    @Test
    fun chatOverridesWinOverDefaults() {
        val s = AppSettings(provider = Provider.OPENAI, model = "gpt-a", thinking = "low")
        assertEquals("gpt-a", s.backendFor(null, null).model)
        assertEquals(Thinking.LOW, s.backendFor(null, null).thinking)
        val chat = s.backendFor("gpt-b", "high")
        assertEquals("gpt-b", chat.model)
        assertEquals(Thinking.HIGH, chat.thinking)
        assertNull(s.backendFor(null, "").thinking)
    }

    @Test
    fun agentOverrideGoesToAgentModel() {
        val s = AppSettings(provider = Provider.OPENCLAW, model = "openclaw/marionette", agentModel = "anthropic/claude-opus-5-5")
        val config = s.backendFor("anthropic/claude-haiku-5-5", null)
        assertEquals("openclaw/marionette", config.model)
        assertEquals("anthropic/claude-haiku-5-5", config.agentModel)
        assertEquals("", s.backendFor("", null).agentModel)
        assertEquals("anthropic/claude-opus-5-5", s.backendFor(null, null).agentModel)
    }

    @Test
    fun unsupportedLevelIsDropped() {
        assertNull(AppSettings(provider = Provider.ANTHROPIC, thinking = "off").backendFor(null, null).thinking)
    }

    @Test
    fun recentModelsAreUniqueAndCapped() {
        var s = AppSettings()
        (1..8).forEach { s = s.withRecentModel("OPENAI:m$it") }
        s = s.withRecentModel("OPENAI:m5")
        assertEquals("OPENAI:m5", s.recentModels.first())
        assertEquals(AppSettings.MAX_RECENT_MODELS, s.recentModels.size)
        assertEquals(s.recentModels.distinct(), s.recentModels)
    }
}
