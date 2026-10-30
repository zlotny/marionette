package dev.marionette.voice

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import dev.marionette.audio.AudioRoute
import dev.marionette.core.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CueChannel(context: Context, private val scope: CoroutineScope) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var track: AudioTrack? = null
    private var focus: AudioFocusRequest? = null
    private var usage = 0
    private var deviceId: Int? = null
    private var closing: Job? = null

    @Synchronized
    fun open(route: AudioRoute, device: AudioDeviceInfo?) {
        closing?.cancel()
        closing = null
        if (!route.wirelessOutput) return closeNow()
        if (track != null && usage == route.speechUsage && deviceId == device?.id) return
        closeNow()
        val attributes = Speaker.attributes(route.speechUsage)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attributes).build()
        audio.requestAudioFocus(request)
        focus = request
        val frames = RATE * SILENCE_MILLIS / 1000
        runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(frames * 2)
                .build()
                .also { created ->
                    device?.let(created::setPreferredDevice)
                    created.write(ShortArray(frames), 0, frames)
                    created.setLoopPoints(0, frames, -1)
                    created.play()
                }
        }.onSuccess {
            track = it
            usage = route.speechUsage
            deviceId = device?.id
            Logger.ux("cue_open", route.speechUsage)
        }.onFailure { Logger.w("Cue channel failed", it) }
    }

    @Synchronized
    fun close() {
        if (track == null && focus == null) return
        closing?.cancel()
        closing = scope.launch {
            delay(LINGER_MILLIS)
            closeNow()
        }
    }

    @Synchronized
    fun closeNow() {
        closing?.cancel()
        closing = null
        focus?.let(audio::abandonAudioFocusRequest)
        focus = null
        track?.let { current ->
            runCatching { current.stop() }
            current.release()
            Logger.ux("cue_close")
        }
        track = null
    }

    private companion object {
        const val RATE = 24_000
        const val SILENCE_MILLIS = 500
        const val LINGER_MILLIS = 1_500L
    }
}
