package dev.marionette.voice.wake

import android.content.Context
import dev.marionette.core.Logger
import dev.marionette.voice.AudioSpec
import dev.marionette.voice.stt.OpenAiSpeechToText
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

class WakeHitRecorder(private val dir: File?) : AutoCloseable {
    private val ring = ShortArray(AudioSpec.SAMPLE_RATE * SECONDS)
    private var end = 0
    private var filled = 0
    private var lastEvent = 0L
    private var pending: Detection? = null
    private var pendingAt = 0L
    private val writer = Executors.newSingleThreadExecutor()

    fun push(pcm: ShortArray, n: Int) {
        for (i in 0 until n) {
            ring[end] = pcm[i]
            end = (end + 1) % ring.size
        }
        filled = minOf(ring.size, filled + n)
        val blocked = pending ?: return
        if (System.currentTimeMillis() - pendingAt >= BLOCKED_TAIL_MILLIS) {
            pending = null
            save("blocked", blocked, pendingAt)
        }
    }

    fun record(hit: Detection, now: Long, idle: Boolean = true, echo: Boolean = false) {
        when {
            hit.stop -> save(if (echo) "stopecho" else "stop", hit, now)
            hit.wake -> {
                pending = null
                lastEvent = now
                save("wake", hit, now)
            }
            hit.blocked && idle && pending == null && now - lastEvent > QUIET_MILLIS -> {
                lastEvent = now
                pending = hit
                pendingAt = now
            }
        }
    }

    private fun save(kind: String, hit: Detection, at: Long) {
        val dir = dir ?: return
        val pcm = ShortArray(filled) { ring[(end - filled + it + ring.size) % ring.size] }
        val name = "${STAMP.format(Instant.ofEpochMilli(at))}_${kind}_s${"%.3f".format(Locale.ROOT, hit.score)}_v${hit.wakeVotes}.wav"
        writer.execute {
            runCatching {
                val bytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                pcm.forEach { bytes.putShort(it) }
                dir.mkdirs()
                File(dir, name).writeBytes(OpenAiSpeechToText.wav(bytes.array()))
                prune(dir, kind)
            }.onFailure { Logger.w("Wake recording not saved", it) }
        }
    }

    override fun close() {
        writer.shutdown()
    }

    data class Hit(val file: File, val at: Long, val kind: String, val score: Float, val falseWake: Boolean) {
        val key: String get() = file.name.removeSuffix(".wav").removeSuffix(FALSE_SUFFIX)
    }

    companion object {
        private const val SECONDS = 4
        private const val FALSE_SUFFIX = "_false"
        private val CAPS = mapOf("wake" to 60, "stop" to 30, "stopecho" to 20, "blocked" to 40)

        private const val FALSE_CAP = 20

        private fun prune(dir: File, kind: String) {
            val files = dir.listFiles { f -> "_${kind}_" in f.name }.orEmpty().sortedByDescending { it.name }
            val (marked, plain) = files.partition { it.name.endsWith("$FALSE_SUFFIX.wav") }
            plain.drop(CAPS[kind] ?: FALSE_CAP).forEach { it.delete() }
            marked.drop(FALSE_CAP).forEach { it.delete() }
        }

        fun dir(context: Context): File? = context.getExternalFilesDir(null)?.let { File(it, "wake-hits") }

        fun list(dir: File): List<Hit> = dir.listFiles { f -> f.extension == "wav" }.orEmpty().mapNotNull(::parse).sortedByDescending { it.at }

        fun parse(file: File): Hit? {
            val parts = file.name.removeSuffix(".wav").split('_')
            if (parts.size < 3) return null
            val at = runCatching { LocalDateTime.parse(parts[0], STAMP).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull() ?: return null
            val score = parts.firstOrNull { it.startsWith("s") && it.drop(1).toFloatOrNull() != null }?.drop(1)?.toFloat() ?: return null
            return Hit(file, at, parts[1], score, parts.last() == "false")
        }

        fun lastWake(dir: File): Hit? = list(dir).firstOrNull { it.kind == "wake" }

        fun find(dir: File, key: String): Hit? =
            listOf("$key.wav", "$key$FALSE_SUFFIX.wav").map { File(dir, it) }.firstOrNull { it.exists() }?.let(::parse)

        fun mark(hit: Hit, falseWake: Boolean): Hit? {
            if (hit.falseWake == falseWake) return hit
            val target = File(hit.file.parentFile, hit.key + (if (falseWake) FALSE_SUFFIX else "") + ".wav")
            return if (hit.file.renameTo(target)) hit.copy(file = target, falseWake = falseWake) else null
        }

        fun samples(file: File): FloatArray {
            val bytes = file.readBytes()
            val pcm = ByteBuffer.wrap(bytes, WAV_HEADER, bytes.size - WAV_HEADER).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            return FloatArray(pcm.remaining()) { pcm.get(it).toFloat() }
        }

        private const val WAV_HEADER = 44

        private const val BLOCKED_TAIL_MILLIS = 800L
        private const val QUIET_MILLIS = 2_000L
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneId.systemDefault())
    }
}
