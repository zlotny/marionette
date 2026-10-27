package dev.marionette.voice.wake

import android.content.Context
import java.io.File

class FalseWakes(context: Context) {
    private val dir = File(context.filesDir, "false-wakes")

    fun load(): List<List<FloatArray>> =
        files().flatMap { runCatching { AcousticWakeDetector.load(it) }.getOrDefault(emptyList()) }

    fun count(): Int = files().size

    fun add(key: String, frames: List<FloatArray>) {
        dir.mkdirs()
        AcousticWakeDetector.save(File(dir, "$key.bin"), listOf(frames))
        files().sortedByDescending { it.name }.drop(MAX_CLIPS).forEach { it.delete() }
    }

    fun remove(key: String) {
        File(dir, "$key.bin").delete()
    }

    fun clear() {
        dir.deleteRecursively()
    }

    private fun files(): List<File> = dir.listFiles { f -> f.extension == "bin" }.orEmpty().toList()

    companion object {
        const val MAX_CLIPS = 20
    }
}
