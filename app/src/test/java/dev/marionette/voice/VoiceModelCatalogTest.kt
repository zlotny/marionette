package dev.marionette.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceModelCatalogTest {
    @Test
    fun sttMatchesOpenRouterAndDirectIds() {
        assertEquals("openai/gpt-4o-mini-transcribe", VoiceModelCatalog.stt("gpt-4o-mini-transcribe")?.id)
        assertEquals(Accuracy.EXCELLENT, VoiceModelCatalog.stt("openai/gpt-4o-mini-transcribe")?.accuracy)
        assertEquals(Accuracy.POOR, VoiceModelCatalog.stt("openai/whisper-large-v3-turbo")?.accuracy)
        assertTrue(ModelTag.RECOMMENDED in VoiceModelCatalog.stt("gpt-4o-mini-transcribe")!!.tags)
        assertNull(VoiceModelCatalog.stt("other/gpt-4o-mini-transcribe"))
        assertNull(VoiceModelCatalog.stt(""))
    }

    @Test
    fun ttsMarksSlowModelsAndCost() {
        val gemini = VoiceModelCatalog.tts("google/gemini-3.8-flash-tts")!!
        assertTrue(ModelTag.SLOW in gemini.allTags)
        val flash = VoiceModelCatalog.tts("elevenlabs/eleven-flash-v2.5")!!
        assertEquals(1.08, flash.usdPerHour, 1e-9)
        assertTrue(VoiceModelCatalog.tts("elevenlabs/eleven-flash-v2")!!.englishOnly)
        assertTrue(VoiceModelCatalog.tts("hexgrad/kokoro-82m")!!.englishOnly)
        assertTrue(ModelTag.RECOMMENDED in VoiceModelCatalog.tts("x-ai/grok-voice-tts-1.0")!!.allTags)
    }

    @Test
    fun livePricePerHour() {
        assertEquals(1.08, VoiceModelCatalog.ttsUsdPerHour(0.00002, 0.0)!!, 1e-9)
        assertEquals(0.000009 * 25 * 3600 + 0.0000005 * 54_000 / 4, VoiceModelCatalog.ttsUsdPerHour(0.0000005, 0.000009)!!, 1e-9)
        assertNull(VoiceModelCatalog.ttsUsdPerHour(0.0, 0.0))
        assertNull(VoiceModelCatalog.ttsUsdPerHour(null, null))
    }

    @Test
    fun parseModelsReadsPrice() {
        val body = """{"data":[{"id":"hexgrad/kokoro-82m","name":"Kokoro","pricing":{"prompt":"0.00000062","completion":"0"}}]}"""
        assertEquals(0.00000062 * 54_000, CloudSpeech.parseModels(body).single().usdPerHour!!, 1e-9)
    }
}
