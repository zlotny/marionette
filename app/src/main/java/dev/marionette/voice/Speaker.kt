package dev.marionette.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import dev.marionette.core.Logger
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class Speaker(private val context: Context) {
    private var tts: TextToSpeech? = null
    private val ready = CompletableDeferred<Boolean>()
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    @Volatile private var stopSignal = CompletableDeferred<Unit>()
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()

    fun warmUp() {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            ready.complete(status == TextToSpeech.SUCCESS)
        }.apply {
            setAudioAttributes(attributes)
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    utteranceId?.let { waiting.remove(it)?.complete(Unit) }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    utteranceId?.let { waiting.remove(it)?.complete(Unit) }
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    utteranceId?.let { waiting.remove(it)?.complete(Unit) }
                }
            })
        }
    }

    suspend fun voices(): List<Voice> {
        warmUp()
        if (!ready.await()) return emptyList()
        return runCatching { tts?.voices?.filterNot { it.isNetworkConnectionRequired }?.sortedBy { it.name } }.getOrNull().orEmpty()
    }

    suspend fun speak(text: String, language: String, voiceName: String, rate: Float, cloud: CloudVoice? = null) {
        if (text.isBlank()) return
        val stop = CompletableDeferred<Unit>().also { stopSignal = it }
        if (cloud != null) {
            audio.requestAudioFocus(focus)
            val rest = try {
                speakCloud(text, cloud, rate, stop)
            } finally {
                audio.abandonAudioFocusRequest(focus)
            }
            if (rest.isEmpty() || stop.isCompleted) return
            speakSystem(rest.joinToString(" "), language, voiceName, rate)
            return
        }
        speakSystem(text, language, voiceName, rate)
    }

    private suspend fun speakCloud(text: String, cloud: CloudVoice, rate: Float, stop: CompletableDeferred<Unit>): List<String> = coroutineScope {
        val chunks = CloudSpeech.chunks(text)
        val started = System.currentTimeMillis()
        val streams = Channel<SpeechStream>(capacity = 1)
        val producer = launch {
            chunks.forEach { chunk ->
                val stream = SpeechStream()
                streams.send(stream)
                if (runCatching { CloudSpeech.stream(cloud, chunk, stream) }.isFailure) return@launch
            }
            streams.close()
        }
        var played = 0
        try {
            while (!stop.isCompleted) {
                val stream = select<SpeechStream?> {
                    streams.onReceiveCatching { it.getOrNull() }
                    stop.onAwait { null }
                } ?: break
                val ok = play(stream, rate, stop) {
                    if (played == 0) Logger.i("Cloud speech first audio after ${System.currentTimeMillis() - started} ms")
                }
                if (!ok) return@coroutineScope chunks.drop(played)
                played++
            }
        } finally {
            producer.cancel()
        }
        if (stop.isCompleted) emptyList() else chunks.drop(played)
    }

    private suspend fun play(stream: SpeechStream, rate: Float, stop: CompletableDeferred<Unit>, onFirstAudio: () -> Unit): Boolean = withContext(Dispatchers.IO) {
        val sampleRate = try {
            select<Int?> {
                stream.rate.onAwait { it }
                stop.onAwait { null }
            } ?: return@withContext true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Logger.w("Cloud speech failed", e)
            return@withContext false
        }
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBuffer * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        var frames = 0L
        var carry: Byte? = null
        try {
            track.playbackParams = track.playbackParams.setSpeed(rate)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching { track.setStartThresholdInFrames(minBuffer / 2 / 2) }
            track.play()
            while (!stop.isCompleted) {
                val received = select<ChannelResult<ByteArray>?> {
                    stream.data.onReceiveCatching { it }
                    stop.onAwait { null }
                } ?: break
                if (received.isClosed) {
                    received.exceptionOrNull()?.let {
                        Logger.w("Cloud speech failed", it)
                        return@withContext false
                    }
                    break
                }
                var bytes = received.getOrThrow()
                carry?.let { bytes = byteArrayOf(it) + bytes }
                carry = if (bytes.size % 2 == 1) bytes.last() else null
                val size = bytes.size - bytes.size % 2
                if (frames == 0L && size > 0) onFirstAudio()
                var offset = 0
                while (offset < size && !stop.isCompleted) {
                    val written = track.write(bytes, offset, size - offset)
                    if (written <= 0) break
                    offset += written
                }
                frames += size / 2
            }
            if (!stop.isCompleted && frames > 0) {
                val drainMillis = (frames * 1000 / sampleRate / rate).toLong() + DRAIN_MARGIN_MILLIS
                withTimeoutOrNull(drainMillis) {
                    while (track.playbackHeadPosition < frames && !stop.isCompleted) delay(POLL_MILLIS)
                }
            }
            true
        } finally {
            runCatching { track.pause(); track.flush() }
            track.release()
        }
    }

    private suspend fun speakSystem(text: String, language: String, voiceName: String, rate: Float) {
        warmUp()
        if (!ready.await()) return
        val engine = tts ?: return
        val locale = language.takeIf { it.isNotBlank() }?.let(Locale::forLanguageTag) ?: Locale.getDefault()
        val voice = voiceName.takeIf { it.isNotBlank() }?.let { name -> runCatching { engine.voices?.firstOrNull { it.name == name } }.getOrNull() }
        if (voice != null) engine.voice = voice else engine.language = locale
        engine.setSpeechRate(rate)
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        waiting[id] = done
        audio.requestAudioFocus(focus)
        try {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            withTimeoutOrNull(MAX_SPEECH_MILLIS) { done.await() }
        } finally {
            waiting.remove(id)
            audio.abandonAudioFocusRequest(focus)
        }
    }

    fun stop() {
        stopSignal.complete(Unit)
        runCatching { tts?.stop() }
        waiting.values.forEach { it.complete(Unit) }
        waiting.clear()
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
    }

    companion object {
        private const val MAX_SPEECH_MILLIS = 60_000L
        private const val DRAIN_MARGIN_MILLIS = 500L
        private const val POLL_MILLIS = 20L
    }
}

