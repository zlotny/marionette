package dev.marionette.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextTest {
    @Test
    fun dropsMarkdown() {
        assertEquals("Hecho. Tienes dos citas", SpeechText.clean("**Hecho.**\n\n- Tienes dos citas"))
        assertEquals("Paso uno. Paso dos", SpeechText.clean("1. Paso uno\n2. Paso dos"))
    }

    @Test
    fun readsLinksAndUrlsByName() {
        assertEquals("Mira la web", SpeechText.clean("Mira [la web](https://example.com/a?b=c)"))
        assertEquals("Está en example.com", SpeechText.clean("Está en https://www.example.com/path/x"))
    }

    @Test
    fun dropsEmojiAndCode() {
        assertEquals("Listo", SpeechText.clean("Listo 👍"))
        assertEquals("Usa esto: .", SpeechText.clean("Usa esto: ```val x = 1```."))
    }

    @Test
    fun keepsPlainTextAndNumbers() {
        assertEquals("Son las 10:30, 22 °C.", SpeechText.clean("Son las 10:30, 22 °C."))
    }
}
