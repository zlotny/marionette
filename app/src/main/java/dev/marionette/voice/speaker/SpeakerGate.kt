package dev.marionette.voice.speaker

import android.content.Context
import dev.marionette.core.Logger
import dev.marionette.voice.AudioSpec
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class SpeakerGate(private val context: Context, private val profile: SpeakerProfile) {
    private val rule = SpeakerEnd(profile.high)
    private val ring = FloatArray(WINDOW)
    private val window = FloatArray(WINDOW)
    private val voiced = BooleanArray(WINDOW_CHUNKS)
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "speaker-gate") }
    private val busy = AtomicBoolean(true)
    private var model: SpeakerModel? = null
    private var written = 0L
    private var sinceHop = 0
    private var chunks = 0L
    private val hops = JSONArray()
    private var dropped = 0
    private var inferTotalMillis = 0L
    private var inferMaxMillis = 0L
    private var inferCount = 0

    @Volatile var endpoint: String? = null

    val gone: Boolean get() = rule.cutSample != null
    val voiceBack: Boolean get() = rule.voiceBack
    val cutSample: Long? get() = rule.cutSample

    init {
        executor.execute {
            model = runCatching { SpeakerModel(context.assets) }.onFailure { Logger.w("Speaker model load failed", it) }.getOrNull()
            if (model != null) busy.set(false)
        }
    }

    fun accept(samples: FloatArray?, count: Int, speech: Boolean) {
        for (i in 0 until count) ring[((written + i) % WINDOW).toInt()] = samples?.get(i) ?: 0f
        written += count
        sinceHop += count
        voiced[(chunks % WINDOW_CHUNKS).toInt()] = speech
        chunks++
        if (sinceHop < HOP || written < WINDOW) return
        sinceHop %= HOP
        val voicedShare = voiced.count { it } / WINDOW_CHUNKS.toFloat()
        val recent = (0 until HOP_CHUNKS).count { voiced[((chunks - 1 - it) % WINDOW_CHUNKS).toInt()] }
        val end = written
        if (voicedShare < MIN_VOICED) {
            rule.skip(end)
            return
        }
        if (!busy.compareAndSet(false, true)) {
            dropped++
            return
        }
        val start = (end % WINDOW).toInt()
        ring.copyInto(window, 0, start, WINDOW)
        ring.copyInto(window, WINDOW - start, 0, start)
        executor.execute { score(end, voicedShare, recent * 2 >= HOP_CHUNKS) }
    }

    private fun score(end: Long, voicedShare: Float, recentSpeech: Boolean) {
        val active = model ?: return
        val started = System.nanoTime()
        val result = runCatching { SpeakerModel.cosine(active.embed(window), profile.embedding) }
            .onFailure { Logger.w("Speaker inference failed", it) }
            .getOrNull()
            ?: return
        val millis = (System.nanoTime() - started) / 1_000_000
        inferTotalMillis += millis
        inferMaxMillis = maxOf(inferMaxMillis, millis)
        inferCount++
        rule.scored(end, result, recentSpeech)
        hops.put(JSONArray().put(end * 1000 / AudioSpec.SAMPLE_RATE).put(round(result)).put(round(voicedShare)).put(if (recentSpeech) 1 else 0).put(millis))
        if (millis > MAX_INFER_MILLIS) {
            Logger.w("Speaker gate too slow: $millis ms")
            return
        }
        busy.set(false)
    }

    fun rearm() = rule.rearm()

    fun close() {
        busy.set(true)
        executor.execute {
            runCatching { model?.close() }
            model = null
            runCatching { log() }.onFailure { Logger.w("Speaker gate log failed", it) }
        }
        executor.shutdown()
    }

    private fun log() {
        val average = if (inferCount > 0) inferTotalMillis / inferCount else 0
        val cut = rule.cutSample?.let { it * 1000 / AudioSpec.SAMPLE_RATE }
        Logger.i("Speaker gate: profile ${profile.kind}, seen ${rule.seen}, cut $cut ms, endpoint $endpoint, windows $inferCount, dropped $dropped, avg $average ms, max $inferMaxMillis ms")
        val dir = context.getExternalFilesDir(null)?.let { File(it, "voice-test") } ?: return
        dir.mkdirs()
        val file = File(dir, "gate-log.jsonl")
        if (file.length() > MAX_LOG_BYTES) file.delete()
        val line = JSONObject()
            .put("at", System.currentTimeMillis())
            .put("profile", profile.kind)
            .put("high", profile.high.toDouble())
            .put("seen", rule.seen)
            .put("cut_ms", cut ?: JSONObject.NULL)
            .put("endpoint", endpoint ?: JSONObject.NULL)
            .put("audio_ms", written * 1000 / AudioSpec.SAMPLE_RATE)
            .put("dropped", dropped)
            .put("infer_avg_ms", average)
            .put("infer_max_ms", inferMaxMillis)
            .put("hops", hops)
        file.appendText(line.toString() + "\n")
    }

    private fun round(value: Float): Double = Math.round(value * 1000) / 1000.0

    private companion object {
        const val WINDOW = AudioSpec.SAMPLE_RATE
        const val HOP = 5_120
        const val HOP_CHUNKS = HOP / AudioSpec.CHUNK_SAMPLES
        const val WINDOW_CHUNKS = WINDOW / AudioSpec.CHUNK_SAMPLES
        const val MIN_VOICED = 0.35f
        const val MAX_INFER_MILLIS = 1_000L
        const val MAX_LOG_BYTES = 5_000_000L
    }
}
