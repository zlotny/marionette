package dev.marionette.llm

import dev.marionette.core.arr
import dev.marionette.core.jsonOf
import dev.marionette.core.obj
import dev.marionette.core.string
import dev.marionette.tools.ToolResults
import dev.marionette.tools.imageJpeg
import dev.marionette.tools.withoutImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolImageRequestTest {
    private val messages = listOf(
        ChatMessage.User("what is on screen"),
        ChatMessage.Assistant("", listOf(ToolCall("t1", "screen_capture", "{}"), ToolCall("t2", "clock", "{}"))),
        ChatMessage.ToolResult("t1", "screen_capture", "{\"ok\":true}", "QUJD"),
        ChatMessage.ToolResult("t2", "clock", "{\"ok\":true}"),
    )

    @Test
    fun splitsImageFromToolResult() {
        val result = jsonOf("ok" to true, ToolResults.IMAGE_JPEG to "QUJD")
        assertEquals("QUJD", result.imageJpeg())
        assertNull(result.withoutImage()[ToolResults.IMAGE_JPEG])
    }

    @Test
    fun openAiSendsImagesAsUserMessageAfterToolResults() {
        val sent = OpenAiChatBackend("https://x/v1", "k", "m").requestBody("", messages, emptyList())["messages"].arr()!!
        assertEquals(listOf("user", "assistant", "tool", "tool", "user"), sent.map { it.obj()?.string("role") })
        val parts = sent.last().obj()!!["content"].arr()!!
        assertEquals("data:image/jpeg;base64,QUJD", parts[1].obj()!!["image_url"].obj()?.string("url"))
    }

    @Test
    fun anthropicPutsImageInToolResult() {
        val sent = AnthropicBackend("https://x/v1", "k", "m").requestBody("", messages, emptyList())["messages"].arr()!!
        val results = sent[2].obj()!!["content"].arr()!!
        val withImage = results[0].obj()!!["content"].arr()!!
        assertEquals(listOf("text", "image"), withImage.map { it.obj()?.string("type") })
        assertEquals("QUJD", withImage[1].obj()!!["source"].obj()?.string("data"))
        assertEquals("{\"ok\":true}", results[1].obj()?.string("content"))
    }
}
