package dev.marionette.voice

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Wav {
    fun read(file: File): Pair<Int, ByteArray>? = runCatching { parse(file.readBytes()) }.getOrNull()

    fun parse(bytes: ByteArray): Pair<Int, ByteArray>? {
        if (bytes.size < 12 || String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var rate = 0
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4)
            val size = buffer.getInt(offset + 4)
            val body = offset + 8
            when (id) {
                "fmt " -> rate = buffer.getInt(body + 4)
                "data" -> return if (rate > 0) rate to bytes.copyOfRange(body, if (size <= 0 || body + size > bytes.size) bytes.size else body + size) else null
            }
            offset = body + size + size % 2
        }
        return null
    }
}
