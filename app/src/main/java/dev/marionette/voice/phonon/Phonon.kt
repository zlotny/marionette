package dev.marionette.voice.phonon

import android.content.Context
import android.os.SystemClock
import android.system.Os
import dev.marionette.core.ModelDownload
import dev.marionette.core.ModelFile
import dev.marionette.core.ModelState
import kotlinx.coroutines.flow.StateFlow
import java.util.zip.ZipFile
import dev.marionette.core.Logger
import dev.marionette.voice.SpeechStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

data class PhononVoiceInfo(val name: String, val language: String, val description: String)

object Phonon {
    const val SAMPLE_RATE = 24_000
    val LANGUAGES = setOf("en", "es", "fr", "de", "pt")
    private const val FALLBACK_LANGUAGE = "es"
    private const val THREADS = "2"

    private val thread = Executors.newSingleThreadExecutor { Thread(it, "phonon") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(thread)
    private var tts: PhononTTS? = null
    private var language = ""
    private var voice = ""
    private const val RELEASE = "https://github.com/zlotny/marionette/releases/download/phonon-40m-multilingual"
    private const val VOICES_ZIP = "phonon-voices.zip"
    private const val VOICES = "voices"
    private const val CATALOG = "voices.tsv"

    val model = ModelDownload(
        "Phonon",
        listOf(
            ModelFile("config.json", "$RELEASE/config.json", 4_674L, "e312ab694d5ec1f79ccea520a2eeec989969ce07e9b4189ae7e9ad8e035ef82e"),
            ModelFile("tokenizer.json", "$RELEASE/tokenizer.json", 277_749L, "a5e8c6060bf927ed367314f21355148fdf4acff6fadb94e49c86bc7182449433"),
            ModelFile("default-voice.safetensors", "$RELEASE/default-voice.safetensors", 256_136L, "dc537478eaadc1a5257fcda2e3501ab81e14c111a5d95a5838f350db2afd0f1a"),
            ModelFile(VOICES_ZIP, "$RELEASE/$VOICES_ZIP", 26_152_179L, "666b7002055329d29cee64499537b6cfa0a4ca79bd4036aa13423327da20f03d"),
            ModelFile("model.q8.gguf", "$RELEASE/model.q8.gguf", 74_553_568L, "96fa067813244e0c6c5997b98ec2596b65ec8574b14916b48e97db056ff97e27"),
        ),
        unpack = { folder ->
            val voices = File(folder, VOICES).apply { deleteRecursively(); mkdirs() }
            ZipFile(File(folder, VOICES_ZIP)).use { zip ->
                zip.entries().asSequence().filter { !it.isDirectory && !it.name.contains('/') }.forEach { entry ->
                    zip.getInputStream(entry).use { input -> File(voices, entry.name).outputStream().use(input::copyTo) }
                }
            }
        },
        unpacked = { folder -> File(folder, "$VOICES/$CATALOG").isFile },
    )
    val state: StateFlow<ModelState> get() = model.state

    fun init(context: Context) {
        if (model.dir == null) model.init(context.getExternalFilesDir("phonon"))
    }

    fun installed(): Boolean = model.installed()

    fun delete() {
        release()
        model.delete()
    }

    fun catalog(): List<PhononVoiceInfo> {
        val folder = model.dir?.takeIf { installed() } ?: return emptyList()
        val present = File(folder, VOICES).list().orEmpty().map { it.removeSuffix(".safetensors") }.toSet()
        return File(folder, "$VOICES/$CATALOG").readLines()
            .mapNotNull { line -> line.split('\t').takeIf { it.size >= 2 && it[0] in present }?.let { PhononVoiceInfo(it[0], it[1], it.getOrElse(2) { "" }) } }
    }

    fun language(tag: String): String = tag.substringBefore('-').lowercase().takeIf { it in LANGUAGES } ?: FALLBACK_LANGUAGE

    fun preload() {
        if (installed()) scope.launch { runCatching { engine() }.onFailure { Logger.w("Phonon load failed", it) } }
    }

    fun release() {
        scope.launch {
            tts?.close()
            tts = null
            language = ""
            voice = ""
        }
    }

    suspend fun stream(text: String, languageTag: String, voiceName: String, target: SpeechStream) = withContext(thread) {
        val job = currentCoroutineContext()[Job]
        try {
            val engine = engine()
            val lang = language(languageTag)
            if (lang != language) {
                engine.setLanguage(lang)
                language = lang
            }
            val wanted = voiceName.takeIf { it in engine.voices } ?: "default"
            if (wanted != voice) {
                engine.setVoice(wanted)
                voice = wanted
            }
            target.rate.complete(SAMPLE_RATE)
            val result = engine.speak(text) { pcm ->
                val bytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (sample in pcm) bytes.putShort((sample.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                target.data.trySend(bytes.array())
                job?.isActive != false
            }
            Logger.i("Phonon ${result.samples * 1000 / SAMPLE_RATE} ms of audio, first audio ${result.ttfaMs.toLong()} ms, total ${result.totalMs.toLong()} ms ($lang, $wanted)")
            target.data.close()
        } catch (e: Throwable) {
            target.data.close(e)
            throw e
        }
    }

    private fun engine(): PhononTTS = tts ?: run {
        val folder = model.dir?.takeIf { installed() } ?: error("Phonon model missing")
        Os.setenv("RAYON_NUM_THREADS", THREADS, true)
        val started = SystemClock.elapsedRealtime()
        PhononTTS(folder.path, FALLBACK_LANGUAGE).also {
            tts = it
            language = FALLBACK_LANGUAGE
            voice = ""
            Logger.i("Phonon loaded in ${SystemClock.elapsedRealtime() - started} ms, voices ${it.voices.size}")
        }
    }
}
