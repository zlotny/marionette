package dev.marionette.voice.stt

import dev.marionette.core.Http
import dev.marionette.core.await
import dev.marionette.core.parseObject
import dev.marionette.core.string
import dev.marionette.llm.toLlmException
import dev.marionette.voice.AudioSpec
import dev.marionette.core.Logger
import dev.marionette.settings.Languages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

class OpenAiSpeechToText(
    private val apiKey: () -> String,
    private val baseUrl: String,
    private val model: String,
) : SpeechToText {
    override val name = "cloud"

    override fun isAvailable(): Boolean = apiKey().isNotBlank()

    override suspend fun start(language: String, extraLanguages: List<String>, onPartial: (String) -> Unit): SttSession = object : SttSession {
        private val audio = ByteArrayOutputStream()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        @Volatile private var speculation: Deferred<String>? = null

        override fun accept(pcm: ShortArray, count: Int) {
            val bytes = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until count) bytes.putShort(pcm[i])
            synchronized(audio) { audio.write(bytes.array()) }
        }

        override fun speculate() {
            if (speculation != null) return
            val pcm = snapshot()
            speculation = scope.async { transcribe(pcm, language, extraLanguages) }
        }

        override fun dropSpeculation() {
            speculation?.cancel()
            speculation = null
        }

        override suspend fun finish(): String {
            val early = speculation
            if (early != null) {
                runCatching { early.await() }.onSuccess { return it }
            }
            return transcribe(snapshot(), language, extraLanguages)
        }

        override fun cancel() = scope.cancel()

        private fun snapshot(): ByteArray = synchronized(audio) { audio.toByteArray() }
    }

    private suspend fun transcribe(pcm: ByteArray, language: String, extraLanguages: List<String>): String {
        if (extraLanguages.isEmpty()) return request(pcm, language, hint = null)
        val languages = listOf(language) + extraLanguages
        val text = request(pcm, language, hint = hint(languages))
        if (!TranscriptScript.foreign(text, languages)) return text
        Logger.i("Cloud STT returned another script, retrying in $language")
        return request(pcm, language, hint = null)
    }

    private suspend fun request(pcm: ByteArray, language: String, hint: String?): String = withContext(Dispatchers.IO) {
        if (pcm.size < AudioSpec.SAMPLE_RATE / 2) return@withContext ""
        val started = System.currentTimeMillis()
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "json")
            .apply {
                if (hint != null) addFormDataPart("prompt", hint)
                else language.substringBefore('-').takeIf { it.length == 2 }?.let { addFormDataPart("language", it) }
            }
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
                .also { Logger.i("Cloud STT ${pcm.size / 32} ms of audio in ${System.currentTimeMillis() - started} ms") }
        }
    }

    companion object {
        fun hint(languages: List<String>): String =
            "The speaker talks ${languages.map { Languages.locale(it).getDisplayLanguage(Locale.ENGLISH) }.distinct().joinToString(" or ")}. Transcribe in the language spoken."
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
