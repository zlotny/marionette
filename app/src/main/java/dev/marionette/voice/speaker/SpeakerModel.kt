package dev.marionette.voice.speaker

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager
import dev.marionette.core.Logger
import java.nio.FloatBuffer
import kotlin.math.sqrt

class SpeakerModel(assets: AssetManager) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(1)
        runCatching { addXnnpack(mapOf("intra_op_num_threads" to "1")) }.onFailure { Logger.w("XNNPACK unavailable", it) }
    }
    private val session = env.createSession(assets.open(ASSET).readBytes(), options)

    fun embed(wave: FloatArray): FloatArray =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(wave), longArrayOf(1, wave.size.toLong())).use { input ->
            session.run(mapOf("wave" to input)).use { result ->
                val output = (result[0] as OnnxTensor).floatBuffer
                normalized(FloatArray(output.remaining()) { output.get() })
            }
        }

    override fun close() {
        session.close()
        options.close()
    }

    companion object {
        private const val ASSET = "speaker/redimnet2_b0.onnx"

        fun available(assets: AssetManager): Boolean = runCatching { assets.open(ASSET).close() }.isSuccess

        fun normalized(v: FloatArray): FloatArray {
            var sum = 0.0
            for (x in v) sum += x * x
            val norm = sqrt(sum).toFloat().takeIf { it > 0f } ?: return v
            return FloatArray(v.size) { v[it] / norm }
        }

        fun cosine(a: FloatArray, b: FloatArray): Float {
            var sum = 0f
            for (i in a.indices) sum += a[i] * b[i]
            return sum
        }
    }
}
