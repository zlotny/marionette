package dev.marionette.voice.wake

import dev.marionette.voice.AudioSpec
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

class AcousticWakeDetector(private val embedder: SpeechEmbedder, templates: List<List<FloatArray>>) {

    private class Track(val template: List<FloatArray>) {
        var previous = FloatArray(template.size) { Float.POSITIVE_INFINITY }
        var beforePrevious = FloatArray(template.size) { Float.POSITIVE_INFINITY }

        fun push(frame: FloatArray): Float {
            val column = FloatArray(template.size)
            for (i in template.indices) {
                val cost = 1f - dot(template[i], frame)
                column[i] = if (i == 0) cost else cost + minOf(column[i - 1], previous[i - 1], beforePrevious[i - 1])
            }
            beforePrevious = previous
            previous = column
            return column.last() / template.size
        }
    }

    private val tracks = templates.filter { it.size >= MIN_TEMPLATE_FRAMES }.map(::Track)
    private val pending = ArrayList<Float>()
    private val context = FloatArray(MEL_CONTEXT_SAMPLES)
    private val melFrames = ArrayDeque<FloatArray>()
    private var framesSinceDetection = Int.MAX_VALUE / 2

    val templateCount: Int get() = tracks.size

    fun accept(samples: FloatArray, threshold: Float): Pair<Float, Boolean>? {
        for (s in samples) pending += s * 32768f
        var best: Float? = null
        var fired = false
        while (pending.size >= HOP_SAMPLES) {
            val hop = FloatArray(HOP_SAMPLES) { pending[it] }
            pending.subList(0, HOP_SAMPLES).clear()
            val mel = embedder.mel(context + hop)
            hop.copyInto(context, 0, HOP_SAMPLES - MEL_CONTEXT_SAMPLES)
            mel.takeLast(HOP_SAMPLES / 160).forEach { melFrames.addLast(it) }
            while (melFrames.size > SpeechEmbedder.WINDOW) melFrames.removeFirst()
            if (melFrames.size < SpeechEmbedder.WINDOW) continue
            val frame = embedder.embed(listOf(melFrames.toList())).first()
            val score = tracks.minOf { it.push(frame) }
            framesSinceDetection++
            best = minOf(best ?: score, score)
            if (score < threshold && framesSinceDetection * SpeechEmbedder.STEP_SECONDS > REFRACTORY_SECONDS) {
                framesSinceDetection = 0
                fired = true
            }
        }
        return best?.let { it to fired }
    }

    companion object {
        private const val HOP_SAMPLES = 1280
        private const val MEL_CONTEXT_SAMPLES = 480
        private const val MIN_TEMPLATE_FRAMES = 3
        private const val REFRACTORY_SECONDS = 1.5f

        private fun dot(a: FloatArray, b: FloatArray): Float {
            var s = 0f
            for (i in a.indices) s += a[i] * b[i]
            return s
        }

        fun buildTemplates(embedder: SpeechEmbedder, pcm: ShortArray): List<List<FloatArray>> {
            val samples = FloatArray(pcm.size) { pcm[it].toFloat() }
            val rate = AudioSpec.SAMPLE_RATE
            return EnergySegmenter.segments(samples, rate).map { (start, end) ->
                val a = maxOf(0, ((start - 0.5f) * rate).toInt())
                val b = minOf(samples.size, ((end + 0.2f) * rate).toInt())
                embedder.embeddings(samples.copyOfRange(a, b))
            }.filter { it.size >= MIN_TEMPLATE_FRAMES }
        }

        fun save(file: File, templates: List<List<FloatArray>>) {
            DataOutputStream(file.outputStream().buffered()).use { out ->
                out.writeInt(templates.size)
                templates.forEach { t ->
                    out.writeInt(t.size)
                    t.forEach { frame -> frame.forEach(out::writeFloat) }
                }
            }
        }

        fun load(file: File): List<List<FloatArray>> {
            if (!file.exists()) return emptyList()
            DataInputStream(file.inputStream().buffered()).use { input ->
                return List(input.readInt()) {
                    List(input.readInt()) { FloatArray(SpeechEmbedder.DIM) { input.readFloat() } }
                }
            }
        }
    }
}
