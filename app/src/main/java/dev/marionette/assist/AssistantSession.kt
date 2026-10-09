package dev.marionette.assist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import dev.marionette.core.Logger
import dev.marionette.launch.Launcher
import dev.marionette.launch.UnlockTrampolineActivity

class AssistantSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        if (args?.getBoolean(Launcher.EXTRA_LAUNCH) == true) {
            runCatching { startAssistantActivity(Intent(context, UnlockTrampolineActivity::class.java)) }
                .onFailure { Logger.w("startAssistantActivity failed", it) }
            hide()
            return
        }
        hide()
    }
}
