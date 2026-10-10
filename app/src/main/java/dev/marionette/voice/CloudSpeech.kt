package dev.marionette.voice

import dev.marionette.core.AppJson
import dev.marionette.core.Http
import dev.marionette.core.arr
import dev.marionette.core.await
import dev.marionette.core.obj
import dev.marionette.core.parseObject
import dev.marionette.core.string
import dev.marionette.llm.toLlmException
import dev.marionette.settings.AppSettings
import dev.marionette.settings.TtsCloud
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class CloudVoice(val baseUrl: String, val key: String, val model: String, val voice: String) {
    companion object {
        fun from(s: AppSettings): CloudVoice? {
            if (!s.cloudSpeech) return null
            val cloud = s.ttsCloud
            val model = s.ttsModel.ifBlank { cloud.defaultModel }
            val voice = s.ttsCloudVoice.ifBlank { if (model == cloud.defaultModel) cloud.defaultVoice else "" }
            return CloudVoice(cloud.baseUrl, s.ttsKey, model, voice)
        }
    }
}

data class SpeechClip(val file: File, val sampleRate: Int)

data class SpeechModel(val id: String, val name: String, val voices: List<String>)

object CloudSpeech {
    private val JSON = "application/json".toMediaType()
    private const val FIRST_CHUNK = 160
    private const val CHUNK = 600
    private const val SAMPLE_RATE = 24_000

    fun requestBody(voice: CloudVoice, text: String): JsonObject = buildJsonObject {
        put("model", voice.model)
        put("input", text)
        if (voice.voice.isNotBlank()) put("voice", voice.voice)
        put("response_format", "pcm")
    }

    suspend fun synthesize(voice: CloudVoice, text: String, target: File, client: OkHttpClient = Http.client): SpeechClip = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${voice.baseUrl.trimEnd('/')}/audio/speech")
            .header("Authorization", "Bearer ${voice.key}")
            .post(AppJson.encodeToString(JsonObject.serializer(), requestBody(voice, text)).toRequestBody(JSON))
            .build()
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw response.toLlmException()
            target.outputStream().use { response.body.byteStream().copyTo(it) }
            SpeechClip(target, sampleRate(response.header("Content-Type")))
        }
    }

    fun sampleRate(contentType: String?): Int =
        contentType?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("rate=") }?.removePrefix("rate=")?.toIntOrNull() ?: SAMPLE_RATE

    suspend fun models(cloud: TtsCloud, client: OkHttpClient = Http.client): List<SpeechModel> {
        if (cloud != TtsCloud.OPENROUTER) return cloud.models.map { SpeechModel(it, it, cloud.voices) }
        return withContext(Dispatchers.IO) {
            val request = Request.Builder().url("${cloud.baseUrl}/models?output_modalities=speech").get().build()
            client.newCall(request).await().use { response ->
                if (!response.isSuccessful) throw response.toLlmException()
                parseModels(response.body.string())
            }
        }
    }

    fun parseModels(body: String): List<SpeechModel> = parseObject(body)["data"].arr().orEmpty().mapNotNull { element ->
        val model = element.obj() ?: return@mapNotNull null
        val id = model.string("id") ?: return@mapNotNull null
        SpeechModel(id, model.string("name") ?: id, model["supported_voices"].arr().orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull })
    }

    fun chunks(text: String): List<String> {
        val sentences = Regex("(?<=[.!?…;:])\\s+|\\n+").split(text.trim()).map { it.trim() }.filter { it.isNotEmpty() }
        val result = mutableListOf<String>()
        val current = StringBuilder()
        sentences.forEach { sentence ->
            val limit = if (result.isEmpty()) FIRST_CHUNK else CHUNK
            if (current.isNotEmpty() && current.length + sentence.length + 1 > limit) {
                result += current.toString()
                current.clear()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(sentence)
        }
        if (current.isNotEmpty()) result += current.toString()
        return result
    }
}
