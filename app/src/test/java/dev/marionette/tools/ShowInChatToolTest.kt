package dev.marionette.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShowInChatToolTest {
    @Test
    fun parsesAttachmentFromResult() {
        val detail = """{"ok":true,"attachment":{"kind":"code","content":"val x = 1","language":"kotlin"},"message":"Shown in the chat."}"""
        assertEquals(Attachment(AttachmentKind.CODE, "val x = 1", language = "kotlin"), Attachment.parse(detail))
    }

    @Test
    fun ignoresOtherResults() {
        assertNull(Attachment.parse("""{"ok":true,"message":"done"}"""))
        assertNull(Attachment.parse("""{"ok":true,"attachment":{"kind":"video","content":"x"}}"""))
        assertNull(Attachment.parse(null))
    }
}
