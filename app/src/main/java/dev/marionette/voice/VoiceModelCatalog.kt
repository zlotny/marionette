package dev.marionette.voice

enum class Accuracy { EXCELLENT, GOOD, FAIR, POOR }

enum class ModelTag { RECOMMENDED, FASTEST, CHEAPEST, SLOW }

data class SttBenchmark(val id: String, val errorPercent: Double, val latencySeconds: Double, val usdPerHour: Double, val tags: Set<ModelTag> = emptySet()) {
    val accuracy: Accuracy get() = when {
        errorPercent <= 1.0 -> Accuracy.EXCELLENT
        errorPercent <= 3.0 -> Accuracy.GOOD
        errorPercent <= 7.0 -> Accuracy.FAIR
        else -> Accuracy.POOR
    }
}

data class TtsBenchmark(val id: String, val firstAudioSeconds: Double, val usdPer1kChars: Double, val englishOnly: Boolean = false, val tags: Set<ModelTag> = emptySet()) {
    val usdPerHour: Double get() = usdPer1kChars * VoiceModelCatalog.CHARS_PER_HOUR / 1000
    val allTags: Set<ModelTag> get() = if (firstAudioSeconds >= VoiceModelCatalog.SLOW_SECONDS) tags + ModelTag.SLOW else tags
}

object VoiceModelCatalog {
    const val CHARS_PER_HOUR = 54_000
    const val SLOW_SECONDS = 1.5
    private const val AUDIO_TOKENS_PER_SECOND = 25
    private const val CHARS_PER_TOKEN = 4

    val stt = listOf(
        SttBenchmark("openai/gpt-4o-mini-transcribe", 0.8, 0.69, 0.13, setOf(ModelTag.RECOMMENDED)),
        SttBenchmark("microsoft/mai-transcribe-2", 1.6, 0.52, 0.10, setOf(ModelTag.FASTEST)),
        SttBenchmark("openai/gpt-4o-transcribe", 0.8, 0.88, 0.25),
        SttBenchmark("elevenlabs/scribe-v2", 1.6, 0.76, 0.11),
        SttBenchmark("mistralai/voxtral-mini-transcribe", 2.4, 0.49, 0.18),
        SttBenchmark("openai/gpt-transcribe", 0.0, 0.72, 0.27),
        SttBenchmark("assemblyai/universal-3-5-pro", 0.0, 0.77, 0.45),
        SttBenchmark("fish-audio/transcribe-1-pro", 0.0, 0.53, 0.36),
        SttBenchmark("qwen/qwen3-asr-1.7b", 2.4, 1.68, 0.03, setOf(ModelTag.CHEAPEST)),
        SttBenchmark("openai/whisper-large-v3", 4.9, 0.86, 0.09),
        SttBenchmark("x-ai/grok-stt-1.0", 6.5, 0.64, 0.10),
        SttBenchmark("openai/whisper-large-v3-turbo", 9.8, 0.91, 0.01),
        SttBenchmark("openai/whisper-1", 5.7, 1.47, 0.36),
        SttBenchmark("google/gemini-3.5-transcribe", 1.6, 2.6, 0.32),
        SttBenchmark("google/chirp-3", 0.8, 2.27, 0.96),
    )

    val tts = listOf(
        TtsBenchmark("hexgrad/kokoro-82m", 0.30, 0.0006, englishOnly = true, tags = setOf(ModelTag.FASTEST, ModelTag.CHEAPEST)),
        TtsBenchmark("elevenlabs/eleven-turbo-v2.5", 0.33, 0.02),
        TtsBenchmark("fish-audio/s1", 0.36, 0.015),
        TtsBenchmark("fish-audio/s2-pro", 0.37, 0.015),
        TtsBenchmark("fish-audio/s2.1-pro", 0.37, 0.015),
        TtsBenchmark("x-ai/grok-voice-tts-1.0", 0.38, 0.015, tags = setOf(ModelTag.RECOMMENDED)),
        TtsBenchmark("elevenlabs/eleven-flash-v2.5", 0.43, 0.02),
        TtsBenchmark("elevenlabs/eleven-flash-v2", 0.45, 0.02, englishOnly = true),
        TtsBenchmark("elevenlabs/eleven-turbo-v2", 0.49, 0.02, englishOnly = true),
        TtsBenchmark("mistralai/voxtral-mini-tts-2603", 0.50, 0.016),
        TtsBenchmark("qwen/qwen-audio-3.0-tts-plus", 0.69, 0.02),
        TtsBenchmark("microsoft/mai-voice-2.1-flash", 0.72, 0.015),
        TtsBenchmark("deepgram/aura-2", 0.72, 0.03, englishOnly = true),
        TtsBenchmark("deepgram/flux-tts", 0.72, 0.045, englishOnly = true),
        TtsBenchmark("elevenlabs/eleven-v3-conversational", 0.81, 0.02),
        TtsBenchmark("elevenlabs/eleven-v4-turbo", 0.87, 0.02),
        TtsBenchmark("microsoft/mai-voice-2.1", 1.0, 0.022),
        TtsBenchmark("microsoft/mai-voice-2-flash", 1.05, 0.015),
        TtsBenchmark("microsoft/mai-voice-2", 1.09, 0.022),
        TtsBenchmark("elevenlabs/eleven-multilingual-v2", 1.18, 0.04),
        TtsBenchmark("elevenlabs/eleven-v4", 1.31, 0.04),
        TtsBenchmark("elevenlabs/eleven-v3", 1.35, 0.04),
        TtsBenchmark("qwen/qwen-audio-3.0-tts-flash", 1.6, 0.015),
        TtsBenchmark("google/gemini-3.8-flash-lite-tts", 1.99, 0.011),
        TtsBenchmark("canopylabs/orpheus-3b-0.1-ft", 2.3, 0.007, englishOnly = true),
        TtsBenchmark("google/gemini-3.8-flash-tts", 2.51, 0.015),
        TtsBenchmark("google/gemini-3.1-flash-tts-preview", 3.4, 0.033),
        TtsBenchmark("sesame/csm-1b", 14.7, 0.007, englishOnly = true),
    )

    fun stt(ref: String): SttBenchmark? = find(stt, ref) { it.id }

    fun tts(ref: String): TtsBenchmark? = find(tts, ref) { it.id }

    fun ttsUsdPerHour(promptPrice: Double?, completionPrice: Double?): Double? {
        val prompt = promptPrice ?: return null
        val completion = completionPrice ?: 0.0
        if (prompt <= 0 && completion <= 0) return null
        return if (completion > 0) {
            completion * AUDIO_TOKENS_PER_SECOND * 3600 + prompt * CHARS_PER_HOUR / CHARS_PER_TOKEN
        } else {
            prompt * CHARS_PER_HOUR
        }
    }

    private fun <T> find(items: List<T>, ref: String, id: (T) -> String): T? {
        val key = ref.trim().lowercase()
        if (key.isEmpty()) return null
        return items.firstOrNull { id(it) == key } ?: items.firstOrNull { !key.contains('/') && id(it).substringAfter('/') == key }
    }
}
