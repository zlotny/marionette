package dev.marionette.voice.speaker

import android.content.Context
import dev.marionette.core.Logger
import dev.marionette.voice.AudioSpec
import dev.marionette.voice.Vad
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SpeakerProfile(val kind: String, val high: Float, val embedding: FloatArray) {
    companion object {
        private const val WAV_HEADER = 44
        private const val SPEECH_WINDOW = 3 * AudioSpec.SAMPLE_RATE
        private const val SPEECH_STEP = AudioSpec.SAMPLE_RATE
        private const val SPEECH_MIN_VOICED = 0.7f
        private const val WAKE_WINDOW = AudioSpec.SAMPLE_RATE
        private const val WAKE_STEP = AudioSpec.SAMPLE_RATE / 2
        private const val WAKE_MIN_VOICED = 0.4f
        private const val VOICED = 0.5f
        private const val MAX_WINDOWS = 30

        fun load(context: Context): SpeakerProfile? {
            val samples = File(context.filesDir, "wake-samples")
            val speech = wavs(samples, "speech-")
            val wake = wavs(samples, "enroll-")
            val cache = File(context.filesDir, "speaker-profile.json")
            val sources = speech.ifEmpty { wake }
            if (sources.isEmpty() || !SpeakerModel.available(context.assets)) {
                cache.delete()
                return null
            }
            val key = sources.joinToString(",") { "${it.name}:${it.length()}" }
            read(cache, key)?.let { return it }
            val started = System.currentTimeMillis()
            val profile = if (speech.isNotEmpty()) {
                build(context, speech, "speech", SpeakerEnd.HIGH, SPEECH_WINDOW, SPEECH_STEP, SPEECH_MIN_VOICED)
            } else {
                build(context, wake, "wake", SpeakerEnd.HIGH_WAKE_ONLY, WAKE_WINDOW, WAKE_STEP, WAKE_MIN_VOICED)
            } ?: return null
            write(cache, key, profile)
            Logger.i("Speaker profile from ${profile.kind} built in ${System.currentTimeMillis() - started} ms")
            return profile
        }

        private fun wavs(dir: File, prefix: String): List<File> =
            dir.listFiles { f -> f.name.startsWith(prefix) && f.name.endsWith(".wav") && f.length() > WAV_HEADER }.orEmpty().sortedBy { it.name }

        private fun build(context: Context, files: List<File>, kind: String, high: Float, window: Int, step: Int, minVoiced: Float): SpeakerProfile? {
            val picked = Vad(context.assets).use { vad ->
                files.flatMap { file ->
                    val wave = read(file)
                    vad.reset()
                    voicedStarts(wave, vad, window, step, minVoiced).map { start -> wave.copyOfRange(start, start + window) }
                }
            }
            if (picked.isEmpty()) return null
            val stride = maxOf(1, picked.size / MAX_WINDOWS)
            return SpeakerModel(context.assets).use { model ->
                val sum = FloatArray(model.embed(picked[0]).size)
                for (i in picked.indices step stride) {
                    val embedding = model.embed(picked[i])
                    for (j in sum.indices) sum[j] += embedding[j]
                }
                SpeakerProfile(kind, high, SpeakerModel.normalized(sum))
            }
        }

        private fun voicedStarts(wave: FloatArray, vad: Vad, window: Int, step: Int, minVoiced: Float): List<Int> {
            val chunk = AudioSpec.CHUNK_SAMPLES
            val voiced = BooleanArray(wave.size / chunk) { i -> (vad.accept(wave.copyOfRange(i * chunk, (i + 1) * chunk)) ?: 0f) >= VOICED }
            val span = window / chunk
            return (0..wave.size - window step step).filter { start ->
                val first = start / chunk
                (first until minOf(first + span, voiced.size)).count { voiced[it] } >= span * minVoiced
            }
        }

        private fun read(file: File): FloatArray {
            val bytes = file.readBytes()
            val pcm = ByteBuffer.wrap(bytes, WAV_HEADER, bytes.size - WAV_HEADER).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            return FloatArray(pcm.remaining()) { pcm.get() / 32768f }
        }

        private fun read(cache: File, key: String): SpeakerProfile? = runCatching {
            if (!cache.exists()) return null
            val json = JSONObject(cache.readText())
            if (json.getString("key") != key) return null
            val values = json.getJSONArray("embedding")
            SpeakerProfile(json.getString("kind"), json.getDouble("high").toFloat(), FloatArray(values.length()) { values.getDouble(it).toFloat() })
        }.getOrNull()

        private fun write(cache: File, key: String, profile: SpeakerProfile) {
            val values = JSONArray()
            profile.embedding.forEach { values.put(it.toDouble()) }
            val temp = File(cache.path + ".tmp")
            temp.writeText(JSONObject().put("key", key).put("kind", profile.kind).put("high", profile.high.toDouble()).put("embedding", values).toString())
            temp.renameTo(cache)
        }
    }
}
