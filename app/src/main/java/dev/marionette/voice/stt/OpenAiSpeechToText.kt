package dev.marionette.voice.stt

import dev.marionette.core.Http
import dev.marionette.core.await
import dev.marionette.core.parseObject
import dev.marionette.core.string
import dev.marionette.llm.toLlmException
import dev.marionette.voice.AudioSpec
import dev.marionette.core.LinkSpeed
import dev.marionette.core.Logger
import dev.marionette.settings.Languages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

class OpenAiSpeechToText(
    private val apiKey: () -> String,
    private val baseUrl: String,
    private val model: String,
    private val cacheDir: File,
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

        override val resumable = true

        private fun snapshot(): ByteArray = synchronized(audio) { audio.toByteArray() }
    }

    private suspend fun transcribe(pcm: ByteArray, language: String, extraLanguages: List<String>): String {
        if (pcm.size < AudioSpec.SAMPLE_RATE / 2) return ""
        val opus = withContext(Dispatchers.IO) { if (model !in wavOnly) OpusOgg.encode(pcm, cacheDir) else null }
        if (extraLanguages.isEmpty()) return request(pcm, opus, language, hint = null)
        val languages = listOf(language) + extraLanguages
        if (LinkSpeed.measuredSlow()) {
            val text = request(pcm, opus, language, hint = hint(languages))
            if (!TranscriptScript.foreign(text, languages)) return text
            Logger.i("Cloud STT returned another script, retrying in $language")
            return request(pcm, opus, language, hint = null)
        }
        return supervisorScope {
            val plain = async { request(pcm, opus, language, hint = null) }
            val text = try {
                request(pcm, opus, language, hint = hint(languages))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w("Cloud STT hinted request failed, using the $language request", e)
                return@supervisorScope plain.await()
            }
            if (!TranscriptScript.foreign(text, languages)) {
                plain.cancel()
                text
            } else {
                Logger.i("Cloud STT returned another script, using the $language request")
                plain.await()
            }
        }
    }

    private suspend fun request(pcm: ByteArray, opus: ByteArray?, language: String, hint: String?): String = withContext(Dispatchers.IO) {
        retrying(pcm, opus.takeIf { model !in wavOnly }, language, hint)
    }

    private suspend fun retrying(pcm: ByteArray, first: ByteArray?, language: String, hint: String?): String {
        var attempt = 0
        var opus = first
        while (true) {
            val started = System.currentTimeMillis()
            try {
                return send(pcm, opus, language, hint).also {
                    Logger.i("Cloud STT ${pcm.size / 32} ms of audio (${opus?.size ?: pcm.size} bytes) in ${System.currentTimeMillis() - started} ms")
                }
            } catch (e: OpusRejected) {
                Logger.w("Cloud STT rejected Opus for $model, sending WAV")
                wavOnly += model
                opus = null
            } catch (e: IOException) {
                if (++attempt > RETRIES) throw e
                Logger.w("Cloud STT attempt $attempt failed after ${System.currentTimeMillis() - started} ms, retrying", e)
                delay(RETRY_DELAY_MILLIS * attempt)
            }
        }
    }

    private suspend fun send(pcm: ByteArray, opus: ByteArray?, language: String, hint: String?): String {
        val file = if (opus != null) opus.toRequestBody("audio/ogg".toMediaType()) else wav(pcm).toRequestBody("audio/wav".toMediaType())
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "json")
            .apply {
                language.substringBefore('-').takeIf { it.length == 2 }?.let { addFormDataPart("language", it) }
                hint?.let { addFormDataPart("prompt", it) }
            }
            .addFormDataPart("file", if (opus != null) "speech.ogg" else "speech.wav", file)
            .build()
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/audio/transcriptions")
            .header("Authorization", "Bearer ${apiKey()}")
            .post(body)
            .build()
        val started = System.currentTimeMillis()
        Http.speech.newCall(request).await().use { response ->
            response.header("openai-processing-ms")?.toLongOrNull()?.let { processing ->
                LinkSpeed.record(body.contentLength().toInt(), System.currentTimeMillis() - started - processing)
            }
            if (!response.isSuccessful) {
                val error = response.toLlmException()
                if (opus != null && (response.code == 400 || response.code == 415) && FORMAT_ERROR.containsMatchIn(error.message.orEmpty())) throw OpusRejected()
                throw SttException(error.message ?: "HTTP ${response.code}")
            }
            val text = parseObject(response.body.string()).string("text").orEmpty().trim()
            return if (hint != null && echoesHint(text)) "" else text
        }
    }

    private class OpusRejected : Exception()

    companion object {
        private const val RETRIES = 2
        private const val RETRY_DELAY_MILLIS = 700L
        private val wavOnly = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        private val FORMAT_ERROR = Regex("(?i)format|codec|ogg|opus|decode|unsupported|invalid file")

        fun echoesHint(text: String): Boolean = text.contains("Transcribe in the language", ignoreCase = true) || text.contains("The speaker talks", ignoreCase = true)

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
