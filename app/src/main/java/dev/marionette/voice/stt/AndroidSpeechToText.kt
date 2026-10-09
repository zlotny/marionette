package dev.marionette.voice.stt

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dev.marionette.core.Logger
import dev.marionette.voice.AudioSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue

class AndroidSpeechToText(private val context: Context) : SpeechToText {
    override val name = "android"
    private val main = Handler(Looper.getMainLooper())

    override fun isAvailable(): Boolean = Build.VERSION.SDK_INT >= 33 &&
        (SpeechRecognizer.isOnDeviceRecognitionAvailable(context) || SpeechRecognizer.isRecognitionAvailable(context))

    override suspend fun start(language: String, onPartial: (String) -> Unit): SttSession = withContext(Dispatchers.Main) {
        if (Build.VERSION.SDK_INT < 33) throw SttException("Android 13 or newer is required for on-device speech recognition.")
        val onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        val recognizer = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
        val pipe = ParcelFileDescriptor.createPipe()
        val session = Session(recognizer, pipe[0], ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]), onPartial)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, AudioSpec.SAMPLE_RATE)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L)
        if (language.isNotBlank()) intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        if (onDevice) intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        recognizer.setRecognitionListener(session.listener)
        recognizer.startListening(intent)
        Logger.i("Android STT started (onDevice=$onDevice, language=${language.ifBlank { "default" }})")
        session
    }

    private inner class Session(
        private val recognizer: SpeechRecognizer,
        private val readSide: ParcelFileDescriptor,
        private val output: OutputStream,
        private val onPartial: (String) -> Unit,
    ) : SttSession {
        private val result = CompletableDeferred<String>()
        private var latestPartial = ""
        @Volatile private var closed = false
        private val queue = LinkedBlockingQueue<ByteArray>()
        private val writer = Thread({
            try {
                while (true) {
                    val chunk = queue.take()
                    if (chunk.isEmpty()) break
                    output.write(chunk)
                }
            } catch (_: IOException) {
            } catch (_: InterruptedException) {
            } finally {
                runCatching { output.close() }
            }
        }, "stt-writer").apply { start() }

        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                text(partialResults)?.takeIf { it.isNotBlank() }?.let {
                    latestPartial = it
                    onPartial(it)
                }
            }

            override fun onResults(results: Bundle?) {
                result.complete(text(results)?.takeIf { it.isNotBlank() } ?: latestPartial)
            }

            override fun onError(error: Int) {
                Logger.w("Android STT error $error")
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    result.complete(latestPartial)
                } else if (!result.isCompleted) {
                    result.completeExceptionally(SttException(errorName(error)))
                }
            }
        }

        override fun accept(pcm: ShortArray, count: Int) {
            if (closed) return
            val bytes = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until count) bytes.putShort(pcm[i])
            queue.offer(bytes.array())
        }

        override suspend fun finish(): String {
            closeAudio()
            main.post { runCatching { recognizer.stopListening() } }
            val text = withTimeoutOrNull(FINISH_TIMEOUT_MILLIS) { result.await() } ?: latestPartial
            destroy()
            return text.trim()
        }

        override fun cancel() {
            closeAudio()
            result.cancel()
            destroy()
        }

        private fun closeAudio() {
            if (closed) return
            closed = true
            queue.offer(ByteArray(0))
        }

        private fun destroy() {
            main.post {
                runCatching { recognizer.cancel() }
                runCatching { recognizer.destroy() }
                runCatching { readSide.close() }
            }
        }

        private fun text(bundle: Bundle?): String? =
            bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
    }

    companion object {
        private const val FINISH_TIMEOUT_MILLIS = 6_000L

        fun errorName(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_AUDIO -> "audio error"
            SpeechRecognizer.ERROR_CLIENT -> "client error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "missing permission"
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network error"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer busy"
            SpeechRecognizer.ERROR_SERVER -> "server error"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "language not supported"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "language not downloaded"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "too many requests"
            else -> "error $code"
        }
    }
}
