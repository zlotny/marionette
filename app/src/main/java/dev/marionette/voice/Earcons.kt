package dev.marionette.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

enum class Earcon(val notes: List<Pair<Double, Int>>) {
    WAKE(listOf(659.25 to 90, 987.77 to 140)),
    DONE(listOf(987.77 to 80, 659.25 to 120)),
    ERROR(listOf(392.0 to 140, 311.13 to 200)),
}

object Earcons {
    private const val RATE = 24_000
    private val cache = mutableMapOf<Earcon, ShortArray>()

    suspend fun play(earcon: Earcon) = withContext(Dispatchers.IO) {
        val pcm = synchronized(cache) { cache.getOrPut(earcon) { synthesize(earcon) } }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            delay(pcm.size * 1000L / RATE + 40)
        } finally {
            track.release()
        }
    }

    private fun synthesize(earcon: Earcon): ShortArray {
        val out = ArrayList<Short>()
        earcon.notes.forEach { (frequency, millis) ->
            val n = RATE * millis / 1000
            for (i in 0 until n) {
                val t = i.toDouble() / RATE
                val attack = (i / (RATE * 0.008)).coerceAtMost(1.0)
                val envelope = attack * exp(-4.0 * i / n)
                val sample = 0.32 * envelope * (sin(2 * PI * frequency * t) + 0.25 * sin(4 * PI * frequency * t))
                out += (sample * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return out.toShortArray()
    }
}
