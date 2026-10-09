package dev.marionette.spike.assist

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import dev.marionette.spike.SpikeLog

class MarionetteVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        instance = this
        SpikeLog.log("VIS ready")
    }

    override fun onShutdown() {
        instance = null
        SpikeLog.log("VIS shutdown")
        super.onShutdown()
    }

    override fun onLaunchVoiceAssistFromKeyguard() {
        SpikeLog.log("VIS launch from keyguard")
        showSession(Bundle(), 0)
    }

    companion object {
        @Volatile
        var instance: MarionetteVoiceInteractionService? = null
            private set
    }
}
