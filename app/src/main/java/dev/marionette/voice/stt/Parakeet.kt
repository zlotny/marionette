package dev.marionette.voice.stt

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.SystemClock
import dev.marionette.core.Lab
import dev.marionette.core.Logger
import dev.marionette.core.ModelDownload
import dev.marionette.core.ModelFile
import dev.marionette.core.ModelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer

object Parakeet {
    private const val RELEASE = "https://github.com/zlotny/marionette/releases/download/parakeet-tdt-0.6b-v3"
    private const val PREPROCESSOR = "nemo128.onnx"
    private const val ENCODER = "encoder-model.int8.onnx"
    private const val DECODER = "decoder_joint-model.int8.onnx"
    private const val VOCAB = "vocab.txt"

    val model = ModelDownload(
        "Parakeet",
        listOf(
            ModelFile(VOCAB, "$RELEASE/$VOCAB", 93_939L, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
            ModelFile(PREPROCESSOR, "$RELEASE/$PREPROCESSOR", 139_764L, "a9fde1486ebfcc08f328d75ad4610c67835fea58c73ba57e3209a6f6cf019e9f"),
            ModelFile(DECODER, "$RELEASE/$DECODER", 18_202_004L, "eea7483ee3d1a30375daedc8ed83e3960c91b098812127a0d99d1c8977667a70"),
            ModelFile(ENCODER, "$RELEASE/$ENCODER", 652_183_999L, "6139d2fa7e1b086097b277c7149725edbab89cc7c7ae64b23c741be4055aff09"),
        ),
    )
    val state: StateFlow<ModelState> get() = model.state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loadLock = Mutex()
    @Volatile private var recognizer: Recognizer? = null

    var logDir: File? = null
        private set

    fun init(context: Context) {
        if (model.dir == null) model.init(File(context.filesDir, "parakeet-v3"))
        if (Lab.ON) logDir = context.getExternalFilesDir("parakeet-log")
    }

    fun installed(): Boolean = model.installed()

    fun delete() {
        model.cancel()
        scope.launch {
            loadLock.withLock {
                recognizer?.closeWhenIdle()
                recognizer = null
                model.delete()
            }
        }
    }

    suspend fun recognizer(): Recognizer = recognizer ?: loadLock.withLock {
        recognizer ?: withContext(Dispatchers.IO) {
            if (!installed()) throw SttException("Parakeet model not downloaded")
            val started = SystemClock.elapsedRealtime()
            Recognizer(model.dir!!).also {
                recognizer = it
                Logger.i("Parakeet loaded in ${SystemClock.elapsedRealtime() - started} ms")
            }
        }
    }

    fun preload() {
        if (installed()) scope.launch { runCatching { recognizer() }.onFailure { Logger.w("Parakeet load failed", it) } }
    }

    fun release() {
        scope.launch {
            loadLock.withLock {
                recognizer?.closeWhenIdle()
                recognizer = null
            }
        }
    }

    class Recognizer(dir: File) : AutoCloseable {
        private val env = OrtEnvironment.getEnvironment()
        private val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(THREADS)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        private val preprocessor = env.createSession(File(dir, PREPROCESSOR).path, options)
        private val encoder = env.createSession(File(dir, ENCODER).path, options)
        private val decoder = env.createSession(File(dir, DECODER).path, options)
        private val vocab = File(dir, VOCAB).readLines().map { it.substringBeforeLast(' ') }
        private val blank = vocab.size - 1
        private val runLock = Mutex()

        suspend fun transcribe(samples: FloatArray): String = runLock.withLock {
            withContext(Dispatchers.Default) {
                val started = SystemClock.elapsedRealtime()
                val (frames, count) = encode(samples)
                val tokens = decode(frames, count)
                val text = tokens.joinToString("") { vocab[it] }.replace('▁', ' ').trim()
                Logger.i("Parakeet ${samples.size / 16} ms of audio in ${SystemClock.elapsedRealtime() - started} ms")
                text
            }
        }

        private fun encode(samples: FloatArray): Pair<Array<FloatArray>, Int> {
            val features = OnnxTensor.createTensor(env, FloatBuffer.wrap(samples), longArrayOf(1, samples.size.toLong())).use { wave ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(samples.size.toLong())), longArrayOf(1)).use { length ->
                    preprocessor.run(mapOf("waveforms" to wave, "waveforms_lens" to length)).use { out ->
                        val tensor = out[0] as OnnxTensor
                        Triple(tensor.floatBuffer, tensor.info.shape, (out[1] as OnnxTensor).longBuffer.get(0))
                    }
                }
            }
            val (buffer, shape, featureLength) = features
            return OnnxTensor.createTensor(env, buffer, shape).use { audio ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(featureLength)), longArrayOf(1)).use { length ->
                    encoder.run(mapOf("audio_signal" to audio, "length" to length)).use { out ->
                        val tensor = out[0] as OnnxTensor
                        val dims = tensor.info.shape[1].toInt()
                        val steps = tensor.info.shape[2].toInt()
                        val flat = tensor.floatBuffer
                        val count = (out[1] as OnnxTensor).longBuffer.get(0).toInt()
                        Array(count) { t -> FloatArray(dims) { d -> flat.get(d * steps + t) } } to count
                    }
                }
            }
        }

        private suspend fun decode(frames: Array<FloatArray>, count: Int): List<Int> {
            val tokens = ArrayList<Int>()
            var state1 = FloatArray(STATE_SIZE)
            var state2 = FloatArray(STATE_SIZE)
            var t = 0
            var emitted = 0
            val stateShape = longArrayOf(2, 1, STATE_DIM)
            while (t < count) {
                currentCoroutineContext().ensureActive()
                val frame = frames[t]
                val inputs = mapOf(
                    "encoder_outputs" to OnnxTensor.createTensor(env, FloatBuffer.wrap(frame), longArrayOf(1, frame.size.toLong(), 1)),
                    "targets" to OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(tokens.lastOrNull() ?: blank)), longArrayOf(1, 1)),
                    "target_length" to OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(1)), longArrayOf(1)),
                    "input_states_1" to OnnxTensor.createTensor(env, FloatBuffer.wrap(state1), stateShape),
                    "input_states_2" to OnnxTensor.createTensor(env, FloatBuffer.wrap(state2), stateShape),
                )
                try {
                    decoder.run(inputs).use { out ->
                        val logits = (out[0] as OnnxTensor).floatBuffer
                        val token = argmax(logits, 0, blank + 1)
                        val step = argmax(logits, blank + 1, logits.limit()) - (blank + 1)
                        if (token != blank) {
                            state1 = floats(out[2] as OnnxTensor)
                            state2 = floats(out[3] as OnnxTensor)
                            tokens += token
                            emitted++
                        }
                        when {
                            step > 0 -> { t += step; emitted = 0 }
                            token == blank || emitted == MAX_TOKENS_PER_STEP -> { t++; emitted = 0 }
                        }
                    }
                } finally {
                    inputs.values.forEach { it.close() }
                }
            }
            return tokens
        }

        private fun floats(tensor: OnnxTensor): FloatArray = tensor.floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } }

        private fun argmax(buffer: FloatBuffer, from: Int, to: Int): Int {
            var best = from
            for (i in from + 1 until to) if (buffer.get(i) > buffer.get(best)) best = i
            return best
        }

        suspend fun closeWhenIdle() = runLock.withLock { close() }

        override fun close() {
            preprocessor.close()
            encoder.close()
            decoder.close()
            options.close()
        }
    }

    private const val THREADS = 4
    private const val STATE_DIM = 640L
    private const val STATE_SIZE = 2 * 640
    private const val MAX_TOKENS_PER_STEP = 10
}
