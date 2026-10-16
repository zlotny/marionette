package dev.marionette.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.random.Random

class VoiceLevelerTest {
    private val rate = 16_000
    private val chunk = 1_280
    private val random = Random(3)

    private fun noise(seconds: Float, amplitude: Float): FloatArray =
        FloatArray((seconds * rate).toInt()) { amplitude * (random.nextFloat() * 2 - 1) }

    private fun tone(seconds: Float, amplitude: Float, noise: Float = 0.0000548f): FloatArray =
        FloatArray((seconds * rate).toInt()) { i -> amplitude * sin(2 * PI * 220 * i / rate).toFloat() + noise * (random.nextFloat() * 2 - 1) }

    private fun run(leveler: VoiceLeveler, input: FloatArray, size: Int = chunk): FloatArray {
        val out = FloatArray(input.size)
        var start = 0
        while (start < input.size) {
            val n = minOf(size, input.size - start)
            val part = input.copyOfRange(start, start + n)
            val leveled = FloatArray(n)
            leveler.process(part, n, leveled)
            leveled.copyInto(out, start)
            start += n
        }
        return out
    }

    private fun dbfs(x: FloatArray, from: Int = 0): Float {
        var sum = 0.0
        for (i in from until x.size) sum += x[i] * x[i]
        return (10 * log10(sum / (x.size - from))).toFloat()
    }

    private fun tail(x: FloatArray, seconds: Float) = x.size - (seconds * rate).toInt()

    @Test
    fun raisesQuietSpeechToTarget() {
        val leveler = VoiceLeveler(rate)
        run(leveler, noise(1f, 0.0000548f))
        val out = run(leveler, tone(3f, 0.007f))
        assertEquals(-26f, dbfs(out, tail(out, 0.5f)), 1.5f)
    }

    @Test
    fun lowersLoudSpeechByAtMostTwelveDb() {
        val leveler = VoiceLeveler(rate)
        run(leveler, noise(1f, 0.0000548f))
        val input = tone(2f, 0.5f)
        val out = run(leveler, input)
        assertEquals(dbfs(input, tail(input, 0.5f)) - 12f, dbfs(out, tail(out, 0.5f)), 1f)
    }

    @Test
    fun noisyRoomCapsTheGain() {
        val leveler = VoiceLeveler(rate)
        run(leveler, noise(1f, 0.00087f))
        run(leveler, tone(3f, 0.007f, noise = 0.00087f))
        assertTrue(leveler.gainDb <= 4.5f)
    }

    @Test
    fun steadyNoiseKeepsUnityGain() {
        val leveler = VoiceLeveler(rate)
        val input = noise(3f, 0.0005f)
        val out = run(leveler, input)
        assertEquals(dbfs(input), dbfs(out), 0.1f)
    }

    @Test
    fun loudBurstAfterQuietSpeechStaysBelowFullScale() {
        val leveler = VoiceLeveler(rate)
        run(leveler, noise(1f, 0.0000548f) + tone(3f, 0.007f))
        val out = run(leveler, tone(0.5f, 0.95f))
        assertTrue(out.all { abs(it) <= 1f })
    }

    @Test
    fun trackingWithoutOutputMatchesProcessing() {
        val input = noise(1f, 0.0000548f) + tone(2f, 0.01f)
        val tracked = VoiceLeveler(rate)
        var start = 0
        while (start < input.size) {
            val n = minOf(chunk, input.size - start)
            tracked.process(input.copyOfRange(start, start + n), n, null)
            start += n
        }
        val processed = VoiceLeveler(rate)
        run(processed, input)
        assertEquals(processed.gainDb, tracked.gainDb, 0f)
    }

    @Test
    fun oddChunkSizesLevelLikeFullFrames() {
        val input = noise(1f, 0.0000548f) + tone(3f, 0.007f)
        val a = run(VoiceLeveler(rate), input, size = 1_000)
        val b = run(VoiceLeveler(rate), input)
        assertEquals(dbfs(b, tail(b, 0.5f)), dbfs(a, tail(a, 0.5f)), 1f)
    }
}
