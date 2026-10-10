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
import java.io.File
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
        val batch = UUID.randomUUID().toString()
        val clips = Channel<Result<SpeechClip>>(capacity = 1)
        val producer = launch {
            chunks.forEachIndexed { index, chunk ->
                val file = File(context.cacheDir, "speech-$batch-$index.pcm")
                val result = runCatching { CloudSpeech.synthesize(cloud, chunk, file) }
                clips.send(result)
                if (result.isFailure) return@launch
            }
            clips.close()
        }
        var played = 0
        try {
            while (!stop.isCompleted) {
                val next = select<Result<SpeechClip>?> {
                    clips.onReceiveCatching { it.getOrNull() }
                    stop.onAwait { null }
                } ?: break
                val clip = next.getOrElse {
                    Logger.w("Cloud speech failed", it)
                    return@coroutineScope chunks.drop(played)
                }
                try {
                    play(clip, rate, stop)
                } finally {
                    clip.file.delete()
                }
                played++
            }
        } finally {
            producer.cancel()
        }
        if (stop.isCompleted) emptyList() else chunks.drop(played)
    }

    private suspend fun play(clip: SpeechClip, rate: Float, stop: CompletableDeferred<Unit>) = withContext(Dispatchers.IO) {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(clip.sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minBuffer = AudioTrack.getMinBufferSize(clip.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBuffer * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            track.playbackParams = track.playbackParams.setSpeed(rate)
            track.play()
            val pcm = clip.file.readBytes()
            val frames = pcm.size / 2
            var offset = 0
            while (offset < frames * 2 && !stop.isCompleted) {
                val written = track.write(pcm, offset, minOf(minBuffer, frames * 2 - offset))
                if (written <= 0) break
                offset += written
            }
            if (!stop.isCompleted) {
                val drainMillis = (frames * 1000 / clip.sampleRate / rate).toLong() + DRAIN_MARGIN_MILLIS
                withTimeoutOrNull(drainMillis) {
                    while (track.playbackHeadPosition < frames && !stop.isCompleted) delay(POLL_MILLIS)
                }
            }
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

