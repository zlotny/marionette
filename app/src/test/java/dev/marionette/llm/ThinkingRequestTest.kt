package dev.marionette.llm

import dev.marionette.core.arr
import dev.marionette.core.obj
import dev.marionette.core.parseObject
import dev.marionette.core.string
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThinkingRequestTest {
    private val user = listOf(ChatMessage.User("hi"))

    @Test
    fun openAiSendsReasoningEffort() {
        val body = OpenAiChatBackend("https://api.openai.com/v1", "k", "gpt", reasoning = Reasoning(Thinking.OFF, nested = false)).requestBody("", user, emptyList())
        assertEquals("none", body.string("reasoning_effort"))
        assertNull(body["reasoning"])
    }

    @Test
    fun openRouterNestsReasoning() {
        val body = OpenAiChatBackend("https://openrouter.ai/api/v1", "k", "m", reasoning = Reasoning(Thinking.HIGH, nested = true)).requestBody("", user, emptyList())
        assertEquals("high", body["reasoning"].obj()?.string("effort"))
        assertNull(body["reasoning_effort"])
    }

    @Test
    fun openClawGetsNoReasoningField() {
        val config = BackendConfig(Provider.OPENCLAW, "https://claw/v1", "openclaw/a", "k", Thinking.HIGH, "anthropic/claude-haiku-5-5")
        val body = (BackendFactory.create(config, "u") as OpenAiChatBackend).requestBody("", user, emptyList())
        assertNull(body["reasoning_effort"])
        assertNull(body["reasoning"])
        assertEquals("u", body.string("user"))
    }

    @Test
    fun anthropicSetsEffort() {
        val body = AnthropicBackend("https://api.anthropic.com/v1", "k", "claude-haiku-5-5", thinking = Thinking.MAX).requestBody("", user, emptyList())
        assertEquals("max", body["output_config"].obj()?.string("effort"))
        val plain = AnthropicBackend("https://api.anthropic.com/v1", "k", "claude-haiku-5-5").requestBody("", user, emptyList())
        assertNull(plain["output_config"])
    }

    @Test
    fun anthropicReplaysNativeBlocksInOrder() {
        val thinking = parseObject("""{"type":"thinking","thinking":"","signature":"sig"}""")
        val tool = parseObject("""{"type":"tool_use","id":"t1","name":"clock","input":{}}""")
        val messages = user + ChatMessage.Assistant("", listOf(ToolCall("t1", "clock", "{}")), listOf(thinking, tool)) +
            ChatMessage.ToolResult("t1", "clock", "{}")
        val body = AnthropicBackend("https://api.anthropic.com/v1", "k", "m").requestBody("", messages, emptyList())
        val content = body["messages"].arr()!![1].obj()!!["content"].arr()!!
        assertEquals(listOf("thinking", "tool_use"), content.map { it.obj()?.string("type") })
        assertEquals("sig", content[0].obj()?.string("signature"))
    }

    @Test
    fun levelsDependOnProvider() {
        assertEquals(listOf(Thinking.LOW, Thinking.MEDIUM, Thinking.HIGH, Thinking.MAX), Thinking.levels(Provider.ANTHROPIC))
        assertEquals(Thinking.entries, Thinking.levels(Provider.OPENROUTER))
        assertEquals(false, Thinking.MAX in Thinking.levels(Provider.OPENAI))
    }
}
