package dev.marionette.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager
import java.nio.FloatBuffer
import java.nio.LongBuffer

class Vad(assets: AssetManager) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session = env.createSession(
        assets.open("vad/silero_vad.onnx").readBytes(),
        OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) },
    )
    private var state = FloatArray(STATE_SIZE)
    private val context = FloatArray(CONTEXT)
    private val pending = FloatArray(WINDOW)
    private var filled = 0

    fun reset() {
        state = FloatArray(STATE_SIZE)
        context.fill(0f)
        filled = 0
    }

    fun accept(samples: FloatArray): Float? {
        var maxProbability: Float? = null
        var offset = 0
        while (offset < samples.size) {
            val take = minOf(WINDOW - filled, samples.size - offset)
            samples.copyInto(pending, filled, offset, offset + take)
            filled += take
            offset += take
            if (filled == WINDOW) {
                val p = infer(pending.copyOf())
                filled = 0
                maxProbability = maxOf(maxProbability ?: p, p)
            }
        }
        return maxProbability
    }

    private fun infer(window: FloatArray): Float {
        val input = context + window
        window.copyInto(context, 0, WINDOW - CONTEXT)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, input.size.toLong())).use { audio ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2, 1, 128)).use { stateTensor ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(AudioSpec.SAMPLE_RATE.toLong())), longArrayOf()).use { sr ->
                    session.run(mapOf("input" to audio, "state" to stateTensor, "sr" to sr)).use { result ->
                        val probability = (result[0] as OnnxTensor).floatBuffer.get(0)
                        val next = (result[1] as OnnxTensor).floatBuffer
                        state = FloatArray(STATE_SIZE) { next.get(it) }
                        return probability
                    }
                }
            }
        }
    }

    override fun close() = session.close()

    companion object {
        private const val WINDOW = 512
        private const val CONTEXT = 64
        private const val STATE_SIZE = 2 * 128
    }
}
