package dev.marionette.voice.wake

object WakeSensitivity {
    const val MIN_THRESHOLD = 0.08f
    const val MAX_THRESHOLD = 0.20f

    fun toThreshold(sensitivity: Float): Float = MIN_THRESHOLD + sensitivity.coerceIn(0f, 1f) * (MAX_THRESHOLD - MIN_THRESHOLD)

    fun fromThreshold(threshold: Float): Float = ((threshold - MIN_THRESHOLD) / (MAX_THRESHOLD - MIN_THRESHOLD)).coerceIn(0f, 1f)
}
