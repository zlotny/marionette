package dev.marionette.voice.wake

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager
import dev.marionette.core.Logger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.sqrt

class SpeechEmbedder(assets: AssetManager) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(1)
        runCatching { addXnnpack(mapOf("intra_op_num_threads" to "1")) }.onFailure { Logger.w("XNNPACK unavailable", it) }
    }
    private val melSession = env.createSession(assets.open("oww/melspectrogram.onnx").readBytes(), options)
    private val embeddingSession = env.createSession(assets.open("oww/embedding_model.onnx").readBytes(), options)

    fun mel(samples: FloatArray): List<FloatArray> {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(samples), longArrayOf(1, samples.size.toLong())).use { input ->
            melSession.run(mapOf("input" to input)).use { result ->
                val tensor = result[0] as OnnxTensor
                val frames = tensor.info.shape[2].toInt()
                val buffer = tensor.floatBuffer
                return List(frames) { FloatArray(MEL_BINS) { buffer.get() / 10f + 2f } }
            }
        }
    }

    fun embed(windows: List<List<FloatArray>>): List<FloatArray> {
        if (windows.isEmpty()) return emptyList()
        val data = FloatBuffer.allocate(windows.size * WINDOW * MEL_BINS)
        windows.forEach { window -> window.forEach { data.put(it) } }
        data.rewind()
        val shape = longArrayOf(windows.size.toLong(), WINDOW.toLong(), MEL_BINS.toLong(), 1)
        OnnxTensor.createTensor(env, data, shape).use { input ->
            embeddingSession.run(mapOf("input_1" to input)).use { result ->
                val buffer = (result[0] as OnnxTensor).floatBuffer
                return List(windows.size) { normalize(FloatArray(DIM) { buffer.get() }) }
            }
        }
    }

    private val window = ByteBuffer.allocateDirect(WINDOW * MEL_BINS * Float.SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val windowShape = longArrayOf(1, WINDOW.toLong(), MEL_BINS.toLong(), 1)

    @Synchronized
    fun embedWindow(frames: Collection<FloatArray>): FloatArray {
        window.clear()
        frames.forEach { window.put(it) }
        window.rewind()
        OnnxTensor.createTensor(env, window, windowShape).use { input ->
            embeddingSession.run(mapOf("input_1" to input)).use { result ->
                val buffer = (result[0] as OnnxTensor).floatBuffer
                return normalize(FloatArray(DIM) { buffer.get() })
            }
        }
    }

    fun embeddings(samples: FloatArray): List<FloatArray> {
        val frames = mel(samples)
        val windows = (0..frames.size - WINDOW step STRIDE).map { frames.subList(it, it + WINDOW) }
        return embed(windows)
    }

    override fun close() {
        melSession.close()
        embeddingSession.close()
    }

    companion object {
        const val MEL_BINS = 32
        const val WINDOW = 76
        const val STRIDE = 8
        const val DIM = 96
        const val STEP_SECONDS = 0.08f

        fun normalize(v: FloatArray): FloatArray {
            var sum = 0f
            for (x in v) sum += x * x
            val norm = sqrt(sum) + 1e-9f
            return FloatArray(v.size) { v[it] / norm }
        }
    }
}
