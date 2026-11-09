package dev.marionette.voice.stt

import dev.marionette.voice.AudioSpec
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel

class ParakeetSpeechToText : SpeechToText {
    override val name = "parakeet"

    override fun isAvailable(): Boolean = Parakeet.installed()

    override suspend fun start(language: String, extraLanguages: List<String>, onPartial: (String) -> Unit): SttSession {
        Parakeet.preload()
        return object : SttSession {
            private val audio = ArrayList<ShortArray>()
            private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            @Volatile private var speculation: Deferred<String>? = null
            @Volatile private var endSample: Long? = null

            override fun accept(pcm: ShortArray, count: Int) {
                synchronized(audio) { audio += pcm.copyOf(count) }
            }

            override fun speculate() {
                if (speculation != null) return
                val samples = snapshot()
                speculation = scope.async { transcribe(samples) }
            }

            override fun dropSpeculation() {
                speculation?.cancel()
                speculation = null
            }

            override fun endAudioAt(sample: Long?) {
                endSample = sample
            }

            override suspend fun finish(): String {
                speculation?.let { early -> runCatching { early.await() }.onSuccess { return it } }
                return transcribe(snapshot())
            }

            override fun cancel() = scope.cancel()

            override val resumable = true

            override fun recorded(): ShortArray = synchronized(audio) {
                val all = ShortArray(audio.sumOf { it.size })
                var offset = 0
                audio.forEach { chunk ->
                    chunk.copyInto(all, offset)
                    offset += chunk.size
                }
                val end = endSample
                if (end != null && end < all.size) all.copyOf(end.toInt()) else all
            }

            private fun snapshot(): FloatArray {
                val pcm = recorded()
                return FloatArray(pcm.size) { pcm[it] / 32768f }
            }
        }
    }

    private suspend fun transcribe(samples: FloatArray): String {
        if (samples.size < AudioSpec.SAMPLE_RATE / 4) return ""
        return Parakeet.recognizer().transcribe(samples).also { text -> Parakeet.logDir?.let { save(it, samples, text) } }
    }

    private fun save(dir: File, samples: FloatArray, text: String) = runCatching {
        val name = "turn-${System.currentTimeMillis()}"
        val pcm = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { pcm.putShort((it * 32767f).toInt().coerceIn(-32768, 32767).toShort()) }
        File(dir, "$name.wav").writeBytes(OpenAiSpeechToText.wav(pcm.array()))
        File(dir, "$name.txt").writeText(text)
        dir.listFiles().orEmpty().sortedByDescending { it.name }.drop(KEEP_FILES).forEach { it.delete() }
    }

    private companion object {
        const val KEEP_FILES = 60
    }
}
