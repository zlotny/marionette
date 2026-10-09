package dev.marionette.spike.assist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import dev.marionette.spike.DeviceState
import dev.marionette.spike.Prefs
import dev.marionette.spike.SpikeLog
import dev.marionette.spike.launch.LaunchVariant
import dev.marionette.spike.launch.NavLauncher
import dev.marionette.spike.launch.NavTrampolineActivity

class MarionetteSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = Session(this)

    private class Session(context: Context) : VoiceInteractionSession(context) {
        private val main = Handler(Looper.getMainLooper())

        override fun onShow(args: Bundle?, showFlags: Int) {
            super.onShow(args, showFlags)
            val requested = args?.getInt(NavLauncher.EXTRA_VARIANT, 0) ?: 0
            val number = if (requested != 0) requested else Prefs(context).variant
            SpikeLog.log("Session onShow requested=$requested flags=$showFlags | ${DeviceState.summary(context)}")
            when (val variant = LaunchVariant.of(number)) {
                LaunchVariant.ASSISTANT_ACTIVITY, LaunchVariant.DISMISS_KEYGUARD -> runCatching {
                    startAssistantActivity(
                        Intent(context, NavTrampolineActivity::class.java)
                            .putExtra(NavTrampolineActivity.EXTRA_SOURCE, "startAssistantActivity")
                            .putExtra(NavTrampolineActivity.EXTRA_DISMISS, variant == LaunchVariant.DISMISS_KEYGUARD)
                    )
                }.onSuccess { SpikeLog.log("S1 V${variant.number} startAssistantActivity returned OK") }
                    .onFailure { SpikeLog.log("S1 V${variant.number} startAssistantActivity FAILED: $it") }
                LaunchVariant.SESSION_DIRECT -> NavLauncher.startMaps(context, "session")
                else -> if (requested == 0) {
                    SpikeLog.log("Session: assist gesture fires selected variant $number")
                    NavLauncher.fire(context, variant)
                }
            }
            main.postDelayed({ hide() }, 3000)
        }
    }
}
