package dev.marionette.voice

import dev.marionette.llm.Provider
import dev.marionette.settings.AppSettings
import dev.marionette.settings.Credential
import dev.marionette.settings.TtsCloud
import dev.marionette.settings.TtsEngine
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSpeechTest {
    @Test
    fun shortTextIsOneChunk() {
        assertEquals(listOf("Hello there. How are you?"), CloudSpeech.chunks("  Hello there.   How are you?  "))
        assertTrue(CloudSpeech.chunks("   ").isEmpty())
    }

    @Test
    fun firstChunkIsShortAndLaterChunksAreLonger() {
        val sentence = "This sentence has exactly fifty characters in it. "
        val chunks = CloudSpeech.chunks(sentence.repeat(20))
        assertTrue(chunks.first().length <= 160)
        assertTrue(chunks.drop(1).dropLast(1).all { it.length in 161..600 })
        assertEquals(sentence.repeat(20).trim(), chunks.joinToString(" "))
    }

    @Test
    fun newlinesSplitSentences() {
        assertEquals(listOf("One two"), CloudSpeech.chunks("One\n\ntwo"))
    }

    @Test
    fun requestBodyOmitsBlankVoice() {
        val body = CloudSpeech.requestBody(CloudVoice("u", "k", "tts-1", ""), "Hi")
        assertEquals("tts-1", body["model"]?.jsonPrimitive?.content)
        assertEquals("Hi", body["input"]?.jsonPrimitive?.content)
        assertEquals("pcm", body["response_format"]?.jsonPrimitive?.content)
        assertFalse("voice" in body)
        assertEquals("nova", CloudSpeech.requestBody(CloudVoice("u", "k", "tts-1", "nova"), "Hi")["voice"]?.jsonPrimitive?.content)
    }

    @Test
    fun sampleRateComesFromContentType() {
        assertEquals(22050, CloudSpeech.sampleRate("audio/pcm;rate=22050;channels=1"))
        assertEquals(24000, CloudSpeech.sampleRate("audio/pcm"))
        assertEquals(24000, CloudSpeech.sampleRate(null))
    }

    @Test
    fun parsesOpenRouterSpeechModels() {
        val body = """{"data":[{"id":"a/tts","name":"A TTS","supported_voices":["Kore","Puck"]},{"id":"b/tts"},{"name":"no id"}]}"""
        assertEquals(
            listOf(SpeechModel("a/tts", "A TTS", listOf("Kore", "Puck")), SpeechModel("b/tts", "b/tts", emptyList())),
            CloudSpeech.parseModels(body),
        )
    }

    @Test
    fun cloudVoiceNeedsCloudEngineAndKey() {
        val keyed = AppSettings(ttsEngine = TtsEngine.CLOUD, ttsCloud = TtsCloud.OPENAI).withKey(Credential.of(Provider.OPENAI), "sk")
        assertNull(CloudVoice.from(keyed.copy(ttsEngine = TtsEngine.SYSTEM)))
        assertNull(CloudVoice.from(AppSettings(ttsEngine = TtsEngine.CLOUD, ttsCloud = TtsCloud.OPENAI)))
        assertEquals(CloudVoice("https://api.openai.com/v1", "sk", "gpt-4o-mini-tts", "marin"), CloudVoice.from(keyed))
        assertEquals(CloudVoice("https://api.openai.com/v1", "sk", "tts-1", ""), CloudVoice.from(keyed.copy(ttsModel = "tts-1")))
        assertEquals("nova", CloudVoice.from(keyed.copy(ttsModel = "tts-1", ttsCloudVoice = "nova"))?.voice)
    }
}
