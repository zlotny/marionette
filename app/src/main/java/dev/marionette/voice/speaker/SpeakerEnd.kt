package dev.marionette.voice.speaker

class SpeakerEnd(private val high: Float, private val low: Float = LOW, private val absentLimitSamples: Long = ABSENT_SAMPLES) {
    private var lastEnd = 0L
    private var absent = 0L
    private var lastVoiceEnd = 0L
    private var lastHighEnd = 0L

    @Volatile var seen = false
        private set

    @Volatile var cutSample: Long? = null
        private set

    @Volatile var voiceBack = false
        private set

    @Synchronized
    fun skip(endSample: Long) {
        lastEnd = endSample
    }

    @Synchronized
    fun scored(endSample: Long, score: Float, recentSpeech: Boolean) {
        val elapsed = endSample - lastEnd
        lastEnd = endSample
        if (cutSample != null) {
            if (score >= high) voiceBack = true
            return
        }
        if (score >= high) {
            seen = true
            lastHighEnd = endSample
        }
        if (score >= low) {
            absent = 0
            lastVoiceEnd = endSample
        } else if (recentSpeech) {
            absent += elapsed
        }
        if (seen && absent >= absentLimitSamples) cutSample = maxOf(lastHighEnd, lastVoiceEnd) + CUT_MARGIN_SAMPLES
    }

    @Synchronized
    fun rearm() {
        cutSample = null
        voiceBack = false
        absent = 0
    }

    companion object {
        const val HIGH = 0.35f
        const val HIGH_WAKE_ONLY = 0.30f
        const val LOW = 0.10f
        const val ABSENT_SAMPLES = 35_840L
        const val CUT_MARGIN_SAMPLES = 3_200L
    }
}
