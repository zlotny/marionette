package dev.marionette.spike.audio

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer

class SentencePieceTokenizer(private val scores: Map<String, Float>) {

    sealed interface Result {
        data class Ok(val tokens: List<String>) : Result
        data class Unsupported(val chars: Set<String>) : Result
    }

    fun encode(phrase: String): Result {
        val words = normalize(phrase).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return Result.Unsupported(emptySet())
        val tokens = mutableListOf<String>()
        val missing = mutableSetOf<String>()
        for (word in words) {
            val pieces = segment(WORD_START + word)
            if (pieces == null) missing += word.filterNot { it.toString() in scores }.map { it.toString() } else tokens += pieces
        }
        return if (missing.isEmpty() && tokens.isNotEmpty()) Result.Ok(tokens) else Result.Unsupported(missing)
    }

    private fun segment(word: String): List<String>? {
        val n = word.length
        val best = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        val from = IntArray(n + 1) { -1 }
        best[0] = 0.0
        for (end in 1..n) {
            for (start in maxOf(0, end - MAX_PIECE_LENGTH) until end) {
                if (best[start] == Double.NEGATIVE_INFINITY) continue
                val score = scores[word.substring(start, end)] ?: continue
                if (best[start] + score > best[end]) {
                    best[end] = best[start] + score
                    from[end] = start
                }
            }
        }
        if (from[n] < 0) return null
        val pieces = ArrayDeque<String>()
        var end = n
        while (end > 0) {
            pieces.addFirst(word.substring(from[end], end))
            end = from[end]
        }
        return pieces
    }

    companion object {
        const val WORD_START = "▁"
        private const val MAX_PIECE_LENGTH = 16
        private const val TYPE_NORMAL = 1

        fun normalize(phrase: String): String =
            Normalizer.normalize(phrase, Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
                .uppercase()
                .replace(Regex("[^A-Z' ]"), " ")
                .replace(Regex(" +"), " ")
                .trim()

        fun fromModel(input: InputStream): SentencePieceTokenizer {
            val buf = ByteBuffer.wrap(input.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val scores = HashMap<String, Float>()
            while (buf.hasRemaining()) {
                val key = readVarint(buf).toInt()
                val field = key ushr 3
                if (field == 1 && key and 7 == 2) {
                    val len = readVarint(buf).toInt()
                    val piece = ByteBuffer.wrap(buf.array(), buf.position(), len).order(ByteOrder.LITTLE_ENDIAN)
                    buf.position(buf.position() + len)
                    parsePiece(piece)?.let { (text, score) -> scores[text] = score }
                } else {
                    skip(buf, key and 7)
                }
            }
            return SentencePieceTokenizer(scores)
        }

        private fun parsePiece(buf: ByteBuffer): Pair<String, Float>? {
            var text = ""
            var score = 0f
            var type = TYPE_NORMAL
            while (buf.hasRemaining()) {
                val key = readVarint(buf).toInt()
                when (key) {
                    (1 shl 3) or 2 -> {
                        val len = readVarint(buf).toInt()
                        val bytes = ByteArray(len).also { buf.get(it) }
                        text = String(bytes, Charsets.UTF_8)
                    }
                    (2 shl 3) or 5 -> score = buf.float
                    (3 shl 3) -> type = readVarint(buf).toInt()
                    else -> skip(buf, key and 7)
                }
            }
            return if (type == TYPE_NORMAL) text to score else null
        }

        private fun readVarint(buf: ByteBuffer): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = buf.get().toInt()
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }

        private fun skip(buf: ByteBuffer, wireType: Int) {
            when (wireType) {
                0 -> readVarint(buf)
                1 -> buf.position(buf.position() + 8)
                2 -> {
                    val len = readVarint(buf).toInt()
                    buf.position(buf.position() + len)
                }
                5 -> buf.position(buf.position() + 4)
                else -> error("unsupported wire type $wireType")
            }
        }
    }
}
