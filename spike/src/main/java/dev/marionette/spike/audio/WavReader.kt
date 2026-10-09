package dev.marionette.spike.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavReader {
    fun readPcm16Mono16k(file: File): FloatArray {
        val buf = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        require(buf.int == 0x46464952) { "not RIFF" }
        buf.int
        require(buf.int == 0x45564157) { "not WAVE" }
        var channels = 0
        var rate = 0
        var bits = 0
        while (buf.remaining() >= 8) {
            val id = buf.int
            val size = buf.int
            when (id) {
                0x20746d66 -> {
                    val start = buf.position()
                    buf.short
                    channels = buf.short.toInt()
                    rate = buf.int
                    buf.int
                    buf.short
                    bits = buf.short.toInt()
                    buf.position(start + size)
                }
                0x61746164 -> {
                    require(channels == 1 && rate == WakeWordEngine.SAMPLE_RATE && bits == 16) {
                        "need 16 kHz mono PCM16, got ${rate}Hz ${channels}ch ${bits}bit"
                    }
                    val n = minOf(size, buf.remaining()) / 2
                    return FloatArray(n) { buf.short / 32768f }
                }
                else -> buf.position(buf.position() + size + (size and 1))
            }
        }
        error("no data chunk")
    }

    fun writePcm16Mono16k(file: File, pcm: ShortArray) {
        val rate = WakeWordEngine.SAMPLE_RATE
        val data = pcm.size * 2
        val buf = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0x46464952).putInt(36 + data).putInt(0x45564157)
        buf.putInt(0x20746d66).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        buf.putInt(0x61746164).putInt(data)
        pcm.forEach { buf.putShort(it) }
        file.writeBytes(buf.array())
    }
}
