package dev.marionette.assist

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import dev.marionette.core.Logger

class MarionetteVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        instance = this
        Logger.i("Voice interaction service ready")
    }

    override fun onShutdown() {
        instance = null
        super.onShutdown()
    }

    override fun onLaunchVoiceAssistFromKeyguard() {
        showSession(Bundle(), 0)
    }

    companion object {
        @Volatile
        var instance: MarionetteVoiceInteractionService? = null
            private set
    }
}
