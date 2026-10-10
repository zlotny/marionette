package dev.marionette.voice.wake

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class UtteranceCounterTest {
    private val rate = 16_000
    private val chunk = 1_280
    private val random = Random(7)

    private fun noise(seconds: Float, amplitude: Float = 0.001f): List<FloatArray> =
        chunks(seconds) { amplitude * (random.nextFloat() * 2 - 1) }

    private fun speech(seconds: Float, amplitude: Float = 0.2f): List<FloatArray> =
        chunks(seconds) { i -> amplitude * sin(2 * PI * 220 * i / rate).toFloat() }

    private fun chunks(seconds: Float, sample: (Int) -> Float): List<FloatArray> {
        var n = 0
        return List((seconds * rate / chunk).toInt()) { FloatArray(chunk) { sample(n++) } }
    }

    private fun count(audio: List<FloatArray>): Int {
        val counter = UtteranceCounter(rate)
        return audio.count { counter.accept(it) }
    }

    @Test
    fun countsSeparatedPhrases() {
        val audio = noise(1f) + (1..5).flatMap { speech(0.8f) + noise(0.8f) }
        assertEquals(5, count(audio))
    }

    @Test
    fun ignoresSilence() {
        assertEquals(0, count(noise(5f)))
    }

    @Test
    fun ignoresClicksShorterThanAPhrase() {
        val audio = noise(1f) + (1..3).flatMap { speech(0.16f) + noise(0.8f) }
        assertEquals(0, count(audio))
    }

    @Test
    fun shortPausesStayInOnePhrase() {
        val audio = noise(1f) + speech(0.4f) + noise(0.16f) + speech(0.4f) + noise(0.8f)
        assertEquals(1, count(audio))
    }

    @Test
    fun longPhraseIsNotCutWhileSpeaking() {
        assertEquals(0, count(noise(1f) + speech(2.8f)))
        assertEquals(1, count(noise(1f) + speech(2.8f) + noise(0.8f)))
    }

    @Test
    fun steadyLoudNoiseIsNotAPhrase() {
        assertEquals(0, count(noise(1f) + speech(12f, amplitude = 0.05f)))
    }
}
