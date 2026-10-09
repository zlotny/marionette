package dev.marionette.voice.wake

import kotlin.math.max
import kotlin.math.sqrt

class UtteranceCounter(private val rate: Int) {
    private var floor = 0f
    private var speechSeconds = 0f
    private var silenceSeconds = 0f

    fun accept(samples: FloatArray): Boolean {
        var sum = 0.0
        for (s in samples) sum += s * s
        val rms = sqrt(sum / samples.size).toFloat()
        val seconds = samples.size.toFloat() / rate
        floor = if (floor == 0f || rms < floor) rms else floor + (rms - floor) * 0.01f
        if (rms > max(floor * 4f, MIN_RMS)) {
            speechSeconds += seconds + silenceSeconds
            silenceSeconds = 0f
            return false
        }
        if (speechSeconds == 0f) return false
        silenceSeconds += seconds
        if (silenceSeconds < GAP_SECONDS) return false
        val ended = speechSeconds in MIN_SECONDS..MAX_SECONDS
        speechSeconds = 0f
        silenceSeconds = 0f
        return ended
    }

    private companion object {
        const val MIN_RMS = 0.005f
        const val GAP_SECONDS = 0.35f
        const val MIN_SECONDS = 0.3f
        const val MAX_SECONDS = 3f
    }
}
