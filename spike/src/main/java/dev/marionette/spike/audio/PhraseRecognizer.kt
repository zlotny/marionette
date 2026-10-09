package dev.marionette.spike.audio

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

class PhraseRecognizer(assets: AssetManager) {

    data class Update(val partial: String, val final: String?)

    private val recognizer = OnlineRecognizer(
        assets,
        OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = WakeWordEngine.SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$MODEL_DIR/encoder.onnx",
                    decoder = "$MODEL_DIR/decoder.onnx",
                    joiner = "$MODEL_DIR/joiner.onnx",
                ),
                tokens = "$MODEL_DIR/tokens.txt",
                numThreads = 1,
                provider = "cpu",
            ),
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 2.4f, 0f),
                rule2 = EndpointRule(true, 0.6f, 0f),
                rule3 = EndpointRule(false, 0f, 8f),
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        ),
    )

    private val stream = recognizer.createStream()

    fun accept(samples: FloatArray): Update {
        stream.acceptWaveform(samples, WakeWordEngine.SAMPLE_RATE)
        while (recognizer.isReady(stream)) recognizer.decode(stream)
        val text = recognizer.getResult(stream).text.trim()
        if (!recognizer.isEndpoint(stream)) return Update(text, null)
        recognizer.reset(stream)
        return Update("", text.ifEmpty { null })
    }

    fun reset() = recognizer.reset(stream)

    fun release() {
        stream.release()
        recognizer.release()
    }

    companion object {
        private const val MODEL_DIR = "asr"
    }
}
