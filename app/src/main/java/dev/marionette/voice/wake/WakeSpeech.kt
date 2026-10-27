package dev.marionette.voice.wake

import android.content.Context
import java.io.File

class WakeSpeech(context: Context) {
    private val file = File(context.filesDir, "wake-speech.bin")

    fun load(): List<List<FloatArray>> = runCatching { AcousticWakeDetector.load(file) }.getOrDefault(emptyList())

    fun add(frames: List<FloatArray>) = AcousticWakeDetector.save(file, (load() + listOf(frames)).takeLast(MAX_RECORDINGS))

    fun clear() {
        file.delete()
    }

    companion object {
        const val MAX_RECORDINGS = 3
    }
}
