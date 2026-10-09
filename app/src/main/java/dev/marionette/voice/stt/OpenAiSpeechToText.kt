package dev.marionette.voice.stt

import dev.marionette.core.Http
import dev.marionette.core.await
import dev.marionette.core.parseObject
import dev.marionette.core.string
import dev.marionette.llm.toLlmException
import dev.marionette.voice.AudioSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OpenAiSpeechToText(
    private val apiKey: () -> String,
    private val baseUrl: String = "https://api.openai.com/v1",
    private val model: String = "whisper-1",
) : SpeechToText {
    override val name = "openai"

    override fun isAvailable(): Boolean = apiKey().isNotBlank()

    override suspend fun start(language: String, onPartial: (String) -> Unit): SttSession = object : SttSession {
        private val audio = ByteArrayOutputStream()

        override fun accept(pcm: ShortArray, count: Int) {
            val bytes = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until count) bytes.putShort(pcm[i])
            synchronized(audio) { audio.write(bytes.array()) }
        }

        override suspend fun finish(): String = withContext(Dispatchers.IO) {
            val pcm = synchronized(audio) { audio.toByteArray() }
            if (pcm.size < AudioSpec.SAMPLE_RATE / 2) return@withContext ""
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", model)
                .addFormDataPart("response_format", "json")
                .apply { language.substringBefore('-').takeIf { it.length == 2 }?.let { addFormDataPart("language", it) } }
                .addFormDataPart("file", "speech.wav", wav(pcm).toRequestBody("audio/wav".toMediaType()))
                .build()
            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/audio/transcriptions")
                .header("Authorization", "Bearer ${apiKey()}")
                .post(body)
                .build()
            Http.client.newCall(request).await().use { response ->
                if (!response.isSuccessful) throw SttException(response.toLlmException().message ?: "HTTP ${response.code}")
                parseObject(response.body.string()).string("text").orEmpty().trim()
            }
        }

        override fun cancel() = Unit
    }

    companion object {
        fun wav(pcm: ByteArray): ByteArray {
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVE".toByteArray())
                put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
                putInt(AudioSpec.SAMPLE_RATE); putInt(AudioSpec.SAMPLE_RATE * 2); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(pcm.size)
            }
            return header.array() + pcm
        }
    }
}
