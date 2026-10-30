package dev.marionette.voice

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import dev.marionette.audio.AudioRoute
import dev.marionette.core.Logger
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

enum class Earcon(val notes: List<Pair<Double, Int>>, val gain: Double = 0.32, val attackMillis: Double = 8.0) {
    WAKE(listOf(659.25 to 110, 987.77 to 220), gain = 0.6),
    WORKING(listOf(783.99 to 90), gain = 0.5),
    PULSE(listOf(523.25 to 320), gain = 0.07, attackMillis = 120.0),
    WAITING(listOf(349.23 to 260, 349.23 to 260), gain = 0.07, attackMillis = 90.0),
    DONE(listOf(987.77 to 110, 659.25 to 220), gain = 0.6),
    ERROR(listOf(392.0 to 160, 311.13 to 280), gain = 0.6),
}

object Earcons {
    private const val RATE = 24_000
    private const val WIRELESS_GAIN = 2.5
    private const val WIRELESS_MAX_GAIN = 0.75
    private const val DECAY = 4.0
    private const val WIRELESS_DECAY = 1.5
    private val cache = mutableMapOf<Pair<Earcon, Boolean>, ShortArray>()

    suspend fun play(earcon: Earcon, route: AudioRoute = AudioRoute.PHONE, device: AudioDeviceInfo? = null, gain: Float = 1f) = withContext(Dispatchers.IO) {
        Logger.ux("earcon", earcon)
        val tone = applyGain(synchronized(cache) { cache.getOrPut(earcon to route.wirelessOutput) { synthesize(earcon, route.wirelessOutput) } }, gain)
        val lead = latencyMillis(route, device)
        val pcm = if (lead > 0) ShortArray(RATE * lead / 1000) + tone else tone
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(route.toneUsage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        device?.let(track::setPreferredDevice)
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            withTimeoutOrNull(pcm.size * 1000L / RATE + 500) { while (track.playbackHeadPosition < pcm.size) delay(10) }
            delay(lead + 40L)
        } finally {
            track.release()
        }
    }

    fun applyGain(tone: ShortArray, gain: Float): ShortArray =
        if (gain == 1f) tone else ShortArray(tone.size) { (softLimit(tone[it] / 32768f * gain) * 32767f).toInt().toShort() }

    fun durationMillis(earcon: Earcon, route: AudioRoute, device: AudioDeviceInfo?): Long =
        latencyMillis(route, device) + earcon.notes.sumOf { it.second }.toLong() + TAIL_MILLIS

    fun latencyMillis(route: AudioRoute, device: AudioDeviceInfo?): Int = when {
        route.carSpeaker -> CAR_LEAD_MILLIS
        device?.type in AudioRoute.WIRELESS_OUTPUTS -> WIRELESS_LEAD_MILLIS
        device == null && route.device?.deviceSpeaker == true -> WIRELESS_LEAD_MILLIS
        else -> 0
    }

    private const val TAIL_MILLIS = 60
    private const val CAR_LEAD_MILLIS = 450
    private const val WIRELESS_LEAD_MILLIS = 300

    fun synthesize(earcon: Earcon, wireless: Boolean): ShortArray {
        val out = ArrayList<Short>()
        val gain = if (wireless) minOf(earcon.gain * WIRELESS_GAIN, WIRELESS_MAX_GAIN) else earcon.gain
        val decay = if (wireless) WIRELESS_DECAY else DECAY
        earcon.notes.forEach { (frequency, millis) ->
            val n = RATE * millis / 1000
            for (i in 0 until n) {
                val t = i.toDouble() / RATE
                val attack = (i / (RATE * earcon.attackMillis / 1000)).coerceAtMost(1.0)
                val envelope = attack * exp(-decay * i / n)
                val sample = gain * envelope * (sin(2 * PI * frequency * t) + 0.25 * sin(4 * PI * frequency * t))
                out += (sample * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return out.toShortArray()
    }
}
