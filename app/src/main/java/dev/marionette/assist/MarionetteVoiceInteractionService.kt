package dev.marionette.assist

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import androidx.core.content.ContextCompat
import dev.marionette.MarionetteApp
import dev.marionette.core.Logger
import dev.marionette.voice.VoiceService

class MarionetteVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        instance = this
        Logger.i("Voice interaction service ready")
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (MarionetteApp.graph.settings.current.onboarded && micGranted) runCatching { VoiceService.send(this, VoiceService.ACTION_START) }
            .onFailure { Logger.w("Voice service start refused", it) }
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
