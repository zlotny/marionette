package dev.marionette.spike.audio

import kotlin.math.sqrt

object EnergySegmenter {
    private const val FRAME_SECONDS = 0.03f
    private const val MIN_SECONDS = 0.4f
    private const val MAX_SECONDS = 2.5f
    private const val GAP_SECONDS = 0.35f

    fun segments(samples: FloatArray, rate: Int): List<Pair<Float, Float>> {
        val frame = (FRAME_SECONDS * rate).toInt()
        val rms = (0 until samples.size - frame step frame).map { start ->
            var sum = 0.0
            for (i in start until start + frame) sum += samples[i] * samples[i]
            sqrt(sum / frame).toFloat()
        }
        if (rms.isEmpty()) return emptyList()
        val level = rms.sorted()[(rms.size * 0.95).toInt().coerceAtMost(rms.size - 1)] * 0.15f
        val result = mutableListOf<Pair<Float, Float>>()
        var start = -1
        var last = -1
        rms.forEachIndexed { i, value ->
            if (value > level) {
                if (start < 0) start = i
                last = i
            } else if (start >= 0 && (i - last) * FRAME_SECONDS > GAP_SECONDS) {
                result += start * FRAME_SECONDS to (last + 1) * FRAME_SECONDS
                start = -1
            }
        }
        if (start >= 0) result += start * FRAME_SECONDS to (last + 1) * FRAME_SECONDS
        return result.filter { (a, b) -> b - a in MIN_SECONDS..MAX_SECONDS }
    }
}
