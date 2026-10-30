package dev.marionette.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationButtonsTest {
    @Test
    fun picksExactThenContainedTitles() {
        assertEquals("Mark as read", NotificationButtons.pick(listOf("Reply", "Mark as read"), "mark as read"))
        assertEquals("Add 1 min", NotificationButtons.pick(listOf("Pause", "Add 1 min"), "add"))
        assertEquals("Pausar", NotificationButtons.pick(listOf("Pausar"), "pausar temporizador"))
    }

    @Test
    fun shortTitlesMatchOnlyWholeWords() {
        assertNull(NotificationButtons.pick(listOf("No", "Yes"), "snooze"))
        assertNull(NotificationButtons.pick(listOf("Archive"), "¡!"))
    }

    @Test
    fun flagsButtonsThatCallOrSend() {
        listOf("Devolver llamada", "Call back", "Reply", "Responder", "Videollamada", "Enviar", "Answer", "Aprobar", "Yes, it's me", "Sí, soy yo", "Pagar", "Eliminar", "Delete", "Allow").forEach {
            assertTrue(it, NotificationButtons.callsOrSends(it))
        }
        listOf("Marcar como leído", "Mark as read", "Archive", "Snooze", "Silenciar", "Apagar", "Pausar", "Detener", "Add 1 min").forEach {
            assertFalse(it, NotificationButtons.callsOrSends(it))
        }
    }
}
