package dev.marionette.spike.audio

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

class WakeWordEngine(assets: AssetManager) {

    val tokenizer: SentencePieceTokenizer = assets.open("$MODEL_DIR/bpe.model").use { SentencePieceTokenizer.fromModel(it) }

    private val spotter = KeywordSpotter(
        assets,
        KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$MODEL_DIR/encoder.onnx",
                    decoder = "$MODEL_DIR/decoder.onnx",
                    joiner = "$MODEL_DIR/joiner.onnx",
                ),
                tokens = "$MODEL_DIR/tokens.txt",
                numThreads = 1,
                provider = "cpu",
                modelType = "zipformer2",
            ),
            keywordsFile = "kws-default.txt",
        ),
    )

    @Volatile
    private var keywords: String = ""

    private var micStream: OnlineStream? = null

    @Synchronized
    fun setKeywords(phrases: List<String>, boost: Float, threshold: Float): Result<List<String>> {
        val encoded = phrases.mapNotNull { (tokenizer.encode(it) as? SentencePieceTokenizer.Result.Ok)?.tokens?.joinToString(" ") }
            .distinct()
        if (encoded.isEmpty()) return Result.failure(IllegalArgumentException("no encodable phrase in $phrases"))
        val label = SentencePieceTokenizer.normalize(phrases.first()).replace(' ', '_')
        keywords = encoded.joinToString("/") { "$it :$boost #$threshold @$label" }
        micStream?.release()
        micStream = spotter.createStream(keywords)
        return Result.success(encoded)
    }

    @Synchronized
    fun acceptMic(samples: FloatArray): String? {
        val stream = micStream ?: return null
        return feed(stream, samples)
    }

    fun detectInFile(samples: FloatArray): List<Pair<Float, String>> {
        val stream = synchronized(this) { spotter.createStream(keywords) }
        val hits = mutableListOf<Pair<Float, String>>()
        val chunk = SAMPLE_RATE / 10
        var offset = 0
        val padded = samples + FloatArray(SAMPLE_RATE)
        while (offset < padded.size) {
            val end = minOf(offset + chunk, padded.size)
            synchronized(this) { feed(stream, padded.copyOfRange(offset, end)) }?.let { hits += offset.toFloat() / SAMPLE_RATE to it }
            offset = end
        }
        stream.release()
        return hits
    }

    private fun feed(stream: OnlineStream, samples: FloatArray): String? {
        stream.acceptWaveform(samples, SAMPLE_RATE)
        var hit: String? = null
        while (spotter.isReady(stream)) {
            spotter.decode(stream)
            val keyword = spotter.getResult(stream).keyword
            if (keyword.isNotEmpty()) {
                spotter.reset(stream)
                if (keyword != UNUSED) hit = keyword
            }
        }
        return hit
    }

    @Synchronized
    fun release() {
        micStream?.release()
        micStream = null
        spotter.release()
    }

    companion object {
        const val SAMPLE_RATE = 16000
        private const val MODEL_DIR = "kws"
        private const val UNUSED = "__unused__"
    }
}
