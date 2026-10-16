package dev.marionette.voice.wake

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

class NoiseProbeTest {
    private val rate = 16_000

    private fun dbfs(x: FloatArray, from: Int, to: Int): Float {
        var sum = 0.0
        for (i in from until to) sum += (x[i] / 32768.0) * (x[i] / 32768.0)
        return 20 * log10(sqrt(sum / (to - from))).toFloat()
    }

    @Test
    fun probeIsDeterministic() {
        assertArrayEquals(NoiseProbe.samples(rate), NoiseProbe.samples(rate), 0f)
    }

    @Test
    fun probeHasQuietFloorAndTransients() {
        val x = NoiseProbe.samples(rate)
        assertEquals(12 * rate, x.size)
        assertEquals(-74f, dbfs(x, 0, rate / 4), 3f)
        val peak = x.maxOf { abs(it) } / 32768f
        assertTrue(peak > 0.03f && peak < 0.12f)
    }

    @Test
    fun noiseLimitFollowsClosestNoiseMatch() {
        val a = FloatArray(SpeechEmbedder.DIM).also { it[0] = 1f }
        val b = FloatArray(SpeechEmbedder.DIM).also { it[1] = 1f }
        assertEquals(-0.05f, AcousticWakeDetector.noiseLimit(listOf(a, a, a), listOf(b, a, a, a, b)), 1e-6f)
        assertEquals(0.95f, AcousticWakeDetector.noiseLimit(listOf(a, a, a), listOf(b, b, b, b)), 1e-6f)
    }
}
