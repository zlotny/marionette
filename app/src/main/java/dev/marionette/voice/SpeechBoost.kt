package dev.marionette.voice

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt

class SpeechBoost(private val gain: Float) {
    private var inSum = 0.0
    private var outSum = 0.0
    private var inPeak = 0f
    private var outPeak = 0f
    private var active = 0L
    private var limited = 0L

    fun process(bytes: ByteArray, size: Int) {
        for (i in 0 until size / 2) {
            val input = ((bytes[2 * i + 1].toInt() shl 8) or (bytes[2 * i].toInt() and 0xFF)).toShort() / 32768f
            val output = softLimit(input * gain)
            val value = (output * 32767f).roundToInt().coerceIn(-32768, 32767)
            bytes[2 * i] = value.toByte()
            bytes[2 * i + 1] = (value shr 8).toByte()
            if (abs(input) > ACTIVE) {
                inSum += input * input
                outSum += output * output
                active++
                if (abs(output) > LIMIT) limited++
            }
            inPeak = max(inPeak, abs(input))
            outPeak = max(outPeak, abs(output))
        }
    }

    fun summary(): String = "in ${db(inSum)} peak ${peak(inPeak)} out ${db(outSum)} peak ${peak(outPeak)} limited ${if (active == 0L) 0 else limited * 100 / active}%"

    private fun db(sum: Double): Int = if (active == 0L) -120 else (10 * log10(sum / active + 1e-12)).roundToInt()

    private fun peak(value: Float): Int = (20 * log10(value + 1e-6f)).roundToInt()

    private companion object {
        const val ACTIVE = 0.003f
        const val LIMIT = 0.89f
    }
}
