package dev.marionette.voice.stt

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import dev.marionette.core.Logger
import dev.marionette.voice.AudioSpec
import java.io.File

object OpusOgg {
    private const val BITRATE = 16_000
    private const val TIMEOUT_US = 10_000L

    val available: Boolean by lazy {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, AudioSpec.SAMPLE_RATE, 1)
        MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format) != null
    }

    fun encode(pcm: ByteArray, dir: File): ByteArray? {
        if (!available) return null
        val out = File.createTempFile("speech", ".ogg", dir)
        return try {
            write(pcm, out)
            out.readBytes().takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Logger.w("Opus encoding failed", e)
            null
        } finally {
            out.delete()
        }
    }

    private fun write(pcm: ByteArray, out: File) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, AudioSpec.SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
        var track = -1
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var offset = 0
            var inputDone = false
            while (true) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(if (offset == 0) TIMEOUT_US else 1_000L)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        val size = minOf(buffer.remaining(), pcm.size - offset)
                        buffer.put(pcm, offset, size)
                        val timeUs = offset / 2 * 1_000_000L / AudioSpec.SAMPLE_RATE
                        offset += size
                        inputDone = offset >= pcm.size
                        codec.queueInputBuffer(index, 0, size, timeUs, if (inputDone) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    }
                }
                val index = codec.dequeueOutputBuffer(info, if (inputDone) TIMEOUT_US else 0)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                    }
                    index >= 0 -> {
                        val buffer = codec.getOutputBuffer(index)!!
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (track >= 0 && info.size > 0 && !config) muxer.writeSampleData(track, buffer, info)
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            runCatching { if (track >= 0) muxer.stop() }
            runCatching { muxer.release() }
        }
    }
}
