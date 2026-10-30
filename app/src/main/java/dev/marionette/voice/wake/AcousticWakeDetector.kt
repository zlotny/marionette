package dev.marionette.voice.wake

import dev.marionette.voice.AudioSpec
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.log10
import kotlin.math.sqrt

data class Detection(val score: Float, val wake: Boolean, val stop: Boolean, val wakeVotes: Int = 0, val blocked: Boolean = false)

class AcousticWakeDetector(
    private val embedder: SpeechEmbedder,
    wakeTemplates: List<List<FloatArray>>,
    stopTemplates: List<List<FloatArray>> = emptyList(),
    speech: List<List<FloatArray>> = emptyList(),
    private val minPhraseDbfs: Float = Float.NEGATIVE_INFINITY,
) {

    private class Track(val template: List<FloatArray>, val limit: Float = Float.POSITIVE_INFINITY) {
        var lastHit = Int.MIN_VALUE / 2
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

        fun reset() {
            previous.fill(Float.POSITIVE_INFINITY)
            beforePrevious.fill(Float.POSITIVE_INFINITY)
        }
    }

    private val noise = NoiseProbe.embeddings(embedder)
    private val wakeTracks = wakeTemplates.filter { it.size >= MIN_TEMPLATE_FRAMES }.map { Track(it, limit(it, noise, speech)) }
    private val stopTracks = stopTemplates.filter { it.size >= MIN_TEMPLATE_FRAMES }.map { Track(it, limit(it, noise, speech)) }
    private var hop = 0
    private val levels = ArrayDeque<Float>()
    private val pending = FloatArray(HOP_SAMPLES * 4)
    private var pendingCount = 0
    private val melInput = FloatArray(MEL_CONTEXT_SAMPLES + HOP_SAMPLES)
    private val melFrames = ArrayDeque<FloatArray>()
    private var framesSinceWake = Int.MAX_VALUE / 2
    private var framesSinceStop = Int.MAX_VALUE / 2
    private var quietHops = Int.MAX_VALUE / 2
    private val gate = SilenceGate(HOP_SAMPLES.toFloat() / AudioSpec.SAMPLE_RATE)

    val wakeArmed: Boolean get() = wakeTracks.isNotEmpty()

    fun reset() {
        wakeTracks.forEach { it.reset() }
        stopTracks.forEach { it.reset() }
        melFrames.clear()
        levels.clear()
        pendingCount = 0
        melInput.fill(0f)
        quietHops = Int.MAX_VALUE / 2
    }

    val stopArmed: Boolean get() = stopTracks.isNotEmpty()

    val wakeUsable: Int get() = wakeTracks.count { it.limit >= WakeSensitivity.MIN_THRESHOLD }

    fun accept(samples: FloatArray, threshold: Float, listenForStop: Boolean = false): Detection? {
        var best: Float? = null
        var wake = false
        var stop = false
        var votes = 0
        var blocked = false
        var offset = 0
        while (offset < samples.size) {
            val take = minOf(samples.size - offset, pending.size - pendingCount)
            for (i in 0 until take) pending[pendingCount + i] = samples[offset + i] * 32768f
            pendingCount += take
            offset += take
            while (pendingCount >= HOP_SAMPLES) {
                processHop(threshold, listenForStop)?.let { hop ->
                    best = minOf(best ?: hop.score, hop.score)
                    wake = wake || hop.wake
                    stop = stop || hop.stop
                    votes = maxOf(votes, hop.wakeVotes)
                    blocked = blocked || hop.blocked
                }
                pending.copyInto(pending, 0, HOP_SAMPLES, pendingCount)
                pendingCount -= HOP_SAMPLES
            }
        }
        return best?.let { Detection(it, wake && !stop, stop, votes, blocked && !wake && !stop) }
    }

    private fun processHop(threshold: Float, listenForStop: Boolean): Detection? {
        pending.copyInto(melInput, MEL_CONTEXT_SAMPLES, 0, HOP_SAMPLES)
        val mel = embedder.mel(melInput)
        melInput.copyInto(melInput, 0, HOP_SAMPLES, HOP_SAMPLES + MEL_CONTEXT_SAMPLES)
        mel.takeLast(HOP_SAMPLES / 160).forEach { melFrames.addLast(it) }
        while (melFrames.size > SpeechEmbedder.WINDOW) melFrames.removeFirst()
        quietHops = if (gate.isQuiet(pending, HOP_SAMPLES)) quietHops + 1 else 0
        levels.addLast(levelDbfs(pending, HOP_SAMPLES))
        while (levels.size > LEVEL_HOPS) levels.removeFirst()
        framesSinceWake++
        framesSinceStop++
        hop++
        if (melFrames.size < SpeechEmbedder.WINDOW) return null
        if (!listenForStop) stopTracks.forEach { it.reset() }
        if (quietHops > WINDOW_HOPS) {
            wakeTracks.forEach { it.reset() }
            stopTracks.forEach { it.reset() }
            return null
        }
        val stopping = listenForStop && stopTracks.isNotEmpty()
        if (wakeTracks.isEmpty() && !stopping) return null
        val frame = embedder.embedWindow(melFrames)
        val wakeScores = wakeTracks.map { it.push(frame) }
        val stopScores = if (stopping) stopTracks.map { it.push(frame) } else emptyList()
        val wakeScore = wakeScores.minOrNull() ?: Float.POSITIVE_INFINITY
        val stopScore = stopScores.minOrNull() ?: Float.POSITIVE_INFINITY
        val stopVotes = votes(stopTracks, stopScores, maxOf(threshold, STOP_THRESHOLD))
        val wakeVotes = votes(wakeTracks, wakeScores, threshold)
        val stopHit = stopVotes > 0
        val usable = wakeTracks.count { it.limit >= threshold }.coerceAtLeast(1)
        val wakeHit = wakeVotes >= minOf(VOTES, usable) && levels.max() >= minPhraseDbfs
        val stop = stopVotes > 0 && stopHit && framesSinceStop * SpeechEmbedder.STEP_SECONDS > REFRACTORY_SECONDS
        val wake = !stop && wakeVotes > 0 && wakeHit && framesSinceWake * SpeechEmbedder.STEP_SECONDS > REFRACTORY_SECONDS
        if (stop) framesSinceStop = 0
        if (wake) framesSinceWake = 0
        val blocked = !wake && framesSinceWake * SpeechEmbedder.STEP_SECONDS > REFRACTORY_SECONDS &&
            wakeScores.any { it < threshold }
        return Detection(minOf(wakeScore, stopScore), wake, stop, wakeVotes, blocked)
    }

    private fun votes(tracks: List<Track>, scores: List<Float>, threshold: Float): Int {
        scores.indices.forEach { if (scores[it] < minOf(threshold, tracks[it].limit)) tracks[it].lastHit = hop }
        return tracks.count { hop - it.lastHit < VOTE_HOPS }
    }

    companion object {
        private const val HOP_SAMPLES = 1280
        private const val MEL_CONTEXT_SAMPLES = 480
        private const val MIN_TEMPLATE_FRAMES = 3
        private const val REFRACTORY_SECONDS = 1.5f
        private const val WINDOW_HOPS = SpeechEmbedder.WINDOW * 160 / HOP_SAMPLES + 1
        private const val NOISE_MARGIN = 0.05f
        private const val SPEECH_MARGIN = 0f
        private const val VOTES = 2
        private const val STOP_THRESHOLD = 0.16f
        private const val VOTE_HOPS = 4
        private const val LEVEL_HOPS = 20
        fun levelDbfs(pcm: FloatArray, count: Int, offset: Int = 0): Float {
            var sum = 0.0
            for (i in offset until offset + count) {
                val s = pcm[i] / 32768f
                sum += s * s
            }
            return 20 * log10(sqrt(sum / count).toFloat() + 1e-9f)
        }

        fun noiseLimit(template: List<FloatArray>, noise: List<FloatArray>, margin: Float = NOISE_MARGIN): Float {
            val track = Track(template)
            return noise.minOfOrNull { track.push(it) }?.minus(margin) ?: Float.POSITIVE_INFINITY
        }

        fun limit(template: List<FloatArray>, noise: List<FloatArray>, speech: List<List<FloatArray>>): Float =
            speech.fold(noiseLimit(template, noise)) { acc, frames -> minOf(acc, noiseLimit(template, frames, SPEECH_MARGIN)) }

        private fun dot(a: FloatArray, b: FloatArray): Float {
            var s = 0f
            for (i in a.indices) s += a[i] * b[i]
            return s
        }

        fun buildTemplates(embedder: SpeechEmbedder, pcm: ShortArray): List<List<FloatArray>> {
            val samples = FloatArray(pcm.size) { pcm[it].toFloat() }
            val rate = AudioSpec.SAMPLE_RATE
            val noise = NoiseProbe.embeddings(embedder)
            return EnergySegmenter.segments(samples, rate).map { (start, end) ->
                val a = maxOf(0, ((start - 0.5f) * rate).toInt())
                val b = minOf(samples.size, ((end + 0.2f) * rate).toInt())
                embedder.embeddings(samples.copyOfRange(a, b))
            }.filter { it.size >= MIN_TEMPLATE_FRAMES && noiseLimit(it, noise) >= WakeSensitivity.MIN_THRESHOLD }
        }

        fun save(file: File, templates: List<List<FloatArray>>) {
            val temp = File(file.parentFile, "${file.name}.tmp")
            DataOutputStream(temp.outputStream().buffered()).use { out ->
                out.writeInt(templates.size)
                templates.forEach { t ->
                    out.writeInt(t.size)
                    t.forEach { frame -> frame.forEach(out::writeFloat) }
                }
            }
            temp.renameTo(file)
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
