package dev.marionette.voice.stt

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class FallbackSpeechToTextTest {
    private class Fake(override val name: String, val millis: Long, val text: String?) : SpeechToText {
        var received = 0
        override fun isAvailable() = true
        override suspend fun start(language: String, extraLanguages: List<String>, onPartial: (String) -> Unit) = object : SttSession {
            override fun accept(pcm: ShortArray, count: Int) {
                received += count
            }

            override suspend fun finish(): String {
                delay(millis)
                return text ?: throw IOException("$name offline")
            }

            override fun cancel() = Unit
        }
    }

    private suspend fun run(cloud: Fake, device: Fake): String {
        val session = FallbackSpeechToText(cloud, device).start("es-ES", emptyList()) {}
        session.accept(ShortArray(1600), 1600)
        return session.finish()
    }

    @Test
    fun fastCloudWins() = runTest {
        assertEquals("nube", run(Fake("cloud", 500, "nube"), Fake("android", 100, "local")))
    }

    @Test
    fun cloudFailureUsesTheSameAudioOnDevice() = runTest {
        val device = Fake("android", 100, "local")
        assertEquals("local", run(Fake("cloud", 500, null), device))
        assertEquals(1600, device.received)
    }

    @Test
    fun slowCloudRacesTheDevice() = runTest {
        assertEquals("local", run(Fake("cloud", 30_000, "nube"), Fake("android", 1_000, "local")))
    }

    @Test
    fun slowCloudStillWinsWhenFirst() = runTest {
        assertEquals("nube", run(Fake("cloud", 7_000, "nube"), Fake("android", 5_000, "local")))
    }

    @Test
    fun bothFailingThrows() = runTest {
        val error = runCatching { run(Fake("cloud", 500, null), Fake("android", 100, null)) }.exceptionOrNull()
        assertTrue(error is IOException)
    }

    @Test
    fun endlessCloudGivesUpWithANetworkError() = runTest {
        val error = runCatching { run(Fake("cloud", 600_000, "nube"), Fake("android", 100, null)) }.exceptionOrNull()
        assertTrue(error is IOException)
    }
}
