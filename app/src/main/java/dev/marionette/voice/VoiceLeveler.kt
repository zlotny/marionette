package dev.marionette.voice

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tanh
import kotlin.math.withSign

class VoiceLeveler(private val rate: Int = AudioSpec.SAMPLE_RATE) {
    private val frame = rate / 100
    private var floorDb = Float.NaN
    private var speechDb = Float.NaN

    var gainDb = 0f
        private set

    fun process(input: FloatArray, count: Int, output: FloatArray?) {
        var start = 0
        while (start < count) {
            val end = min(count, start + frame)
            step(input, start, end, output)
            start = end
        }
    }

    private fun step(input: FloatArray, start: Int, end: Int, output: FloatArray?) {
        val length = end - start
        val seconds = length.toFloat() / rate
        var sum = 0.0
        for (i in start until end) sum += input[i] * input[i]
        val level = (10 * log10(sum / length + 1e-12)).toFloat()
        if (floorDb.isNaN() || level < floorDb) floorDb = level else floorDb += FLOOR_RISE_DB * seconds
        if (level > floorDb + SPEECH_MARGIN_DB && level > MIN_SPEECH_DB) {
            speechDb = if (speechDb.isNaN()) level else speechDb + (level - speechDb) * (1 - exp(-seconds / SPEECH_TAU_SECONDS))
        }
        val wanted = if (speechDb.isNaN()) gainDb else (TARGET_DB - speechDb).coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        val capped = min(wanted, NOISE_CAP_DB - floorDb)
        val next = if (capped > gainDb) min(capped, gainDb + RISE_DB * seconds) else max(capped, gainDb - FALL_DB * seconds)
        if (output != null) {
            var gain = 10f.pow(gainDb / 20)
            val step = 10f.pow((next - gainDb) / 20 / length)
            for (i in start until end) {
                output[i] = limit(input[i] * gain)
                gain *= step
            }
        }
        gainDb = next
    }

    private fun limit(x: Float): Float {
        val a = abs(x)
        if (a <= CEILING) return x
        return (CEILING + (1 - CEILING) * tanh((a - CEILING) / (1 - CEILING))).withSign(x)
    }

    private companion object {
        const val TARGET_DB = -26f
        const val MIN_GAIN_DB = -12f
        const val MAX_GAIN_DB = 30f
        const val NOISE_CAP_DB = -62f
        const val SPEECH_MARGIN_DB = 10f
        const val MIN_SPEECH_DB = -80f
        const val FLOOR_RISE_DB = 2f
        const val SPEECH_TAU_SECONDS = 0.4f
        const val RISE_DB = 12f
        const val FALL_DB = 30f
        const val CEILING = 0.89f
    }
}
