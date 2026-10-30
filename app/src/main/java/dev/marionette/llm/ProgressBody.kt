package dev.marionette.llm

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink

class ProgressBody(private val bytes: ByteArray, private val type: MediaType, private val onUpload: (written: Long, total: Long) -> Unit) : RequestBody() {
    override fun contentType() = type

    override fun contentLength() = bytes.size.toLong()

    override fun writeTo(sink: BufferedSink) {
        var offset = 0
        while (offset < bytes.size) {
            val size = minOf(CHUNK, bytes.size - offset)
            sink.write(bytes, offset, size)
            sink.flush()
            offset += size
            onUpload(offset.toLong(), bytes.size.toLong())
        }
    }

    private companion object {
        const val CHUNK = 4096
    }
}
