package dev.marionette.voice.wake

import kotlin.math.sqrt

object SilenceGate {
    private const val MIN_RMS = 0.0012f

    fun isQuiet(pcm: FloatArray, count: Int, scale: Float = 32768f): Boolean {
        var sum = 0.0
        for (i in 0 until count) {
            val s = pcm[i] / scale
            sum += s * s
        }
        return sqrt(sum / count) < MIN_RMS
    }
}
