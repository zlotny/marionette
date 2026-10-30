package dev.marionette.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin

class SpeechBoostTest {
    private val rate = 24_000

    private fun speech(seconds: Int, amplitude: Double): ByteArray {
        val samples = rate * seconds
        val bytes = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val syllable = if ((i / (rate / 4)) % 2 == 0) 1.0 else 0.0
            val value = (amplitude * syllable * sin(2 * PI * 220 * i / rate) * 32767).toInt()
            bytes[2 * i] = value.toByte()
            bytes[2 * i + 1] = (value shr 8).toByte()
        }
        return bytes
    }

    private fun sample(bytes: ByteArray, i: Int) = ((bytes[2 * i + 1].toInt() shl 8) or (bytes[2 * i].toInt() and 0xFF)).toShort() / 32768.0

    private fun activeDb(bytes: ByteArray, from: Int): Double {
        var sum = 0.0
        var count = 0
        for (i in from until bytes.size / 2) {
            val v = sample(bytes, i)
            if (abs(v) > 0.003) {
                sum += v * v
                count++
            }
        }
        return 10 * log10(sum / count)
    }

    private fun boost(bytes: ByteArray, gain: Float = 2.82f): ByteArray {
        val boost = SpeechBoost(gain)
        var start = 0
        while (start < bytes.size) {
            val end = minOf(bytes.size, start + 4_800)
            val part = bytes.copyOfRange(start, end)
            boost.process(part, part.size)
            part.copyInto(bytes, start)
            start = end
        }
        return bytes
    }

    @Test
    fun speechGainsNineDecibels() {
        val original = speech(4, 0.04)
        val out = boost(original.copyOf())
        assertEquals(activeDb(original, 0) + 9.0, activeDb(out, 0), 0.5)
    }

    @Test
    fun zeroGainMutes() {
        val out = boost(speech(1, 0.5), gain = 0f)
        assertTrue((0 until out.size / 2).all { sample(out, it) == 0.0 })
    }

    @Test
    fun hotSpeechDoesNotClip() {
        val out = boost(speech(4, 0.95))
        val peak = (0 until out.size / 2).maxOf { abs(sample(out, it)) }
        assertTrue(peak < 1.0)
    }
}

class EarconGainTest {
    @Test
    fun gainScalesAndLimitsTones() {
        val tone = Earcons.synthesize(Earcon.WAKE, wireless = false)
        assertTrue(Earcons.applyGain(tone, 0f).all { it.toInt() == 0 })
        assertTrue(Earcons.applyGain(tone, 3f).maxOf { abs(it.toInt()) } in (tone.maxOf { abs(it.toInt()) } + 1)..32767)
    }
}

class WirelessEarconTest {
    private fun db(tone: ShortArray): Double {
        val sum = tone.sumOf { (it / 32768.0) * (it / 32768.0) }
        return 10 * log10(sum / tone.size)
    }

    @Test
    fun wirelessEarconsAreLouderAndDoNotClip() {
        for (earcon in listOf(Earcon.WAKE, Earcon.DONE, Earcon.ERROR, Earcon.WORKING)) {
            val wireless = Earcons.synthesize(earcon, wireless = true)
            val phone = Earcons.synthesize(earcon, wireless = false)
            println("$earcon phone ${db(phone).toInt()} dB, wireless ${db(wireless).toInt()} dB")
            assertTrue(db(wireless) > db(phone) + 3)
            assertTrue(wireless.maxOf { abs(it.toInt()) } < 32767)
        }
    }
}
