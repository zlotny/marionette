package dev.marionette.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.view.KeyEvent
import dev.marionette.MarionetteApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class PlaybackHold(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .build()

    @Volatile private var paused: MediaController? = null
    @Volatile private var focusHeld = false
    @Volatile private var keepPaused = false

    fun hold() {
        if (paused != null || focusHeld) return
        keepPaused = false
        val playing = playingController()
        if (playing != null) {
            playing.transportControls.pause()
            paused = playing
        } else if (audio.isMusicActive) {
            focusHeld = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    fun isHeld(packageName: String?): Boolean = focusHeld || (packageName != null && paused?.packageName == packageName)

    fun stayPaused() {
        paused = null
        keepPaused = true
    }

    fun handOver() {
        paused = null
        keepPaused = false
    }

    suspend fun release() {
        paused?.let { controller ->
            if (audio.mode in CALL_MODES) MarionetteApp.graph.scope.launch { resumeAfterCall(controller) } else controller.transportControls.play()
        }
        paused = null
        if (focusHeld) {
            audio.abandonAudioFocusRequest(focus)
            focusHeld = false
            if (keepPaused) {
                delay(FOCUS_SETTLE_MILLIS)
                sendKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
            }
        }
        keepPaused = false
    }

    private suspend fun resumeAfterCall(controller: MediaController) {
        withTimeoutOrNull(CALL_WAIT_MILLIS) { while (audio.mode in CALL_MODES) delay(CALL_POLL_MILLIS) } ?: return
        delay(FOCUS_SETTLE_MILLIS)
        if (controller.playbackState?.state == PlaybackState.STATE_PAUSED && playingController() == null) controller.transportControls.play()
    }

    private fun playingController(): MediaController? =
        MediaSessions.active(context).firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }

    private fun sendKey(code: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    companion object {
        private const val FOCUS_SETTLE_MILLIS = 400L
        private const val CALL_POLL_MILLIS = 1_000L
        private const val CALL_WAIT_MILLIS = 2 * 3_600_000L
        private val CALL_MODES = setOf(AudioManager.MODE_RINGTONE, AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_CALL_SCREENING)
    }
}
