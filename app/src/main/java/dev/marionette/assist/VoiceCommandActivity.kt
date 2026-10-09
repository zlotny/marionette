package dev.marionette.assist

import android.app.Activity
import android.os.Bundle
import dev.marionette.MarionetteApp
import dev.marionette.core.Logger
import dev.marionette.voice.TriggerSource
import dev.marionette.voice.VoiceService

class VoiceCommandActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        Logger.i("Headset voice command: ${intent.action}")
        if (MarionetteApp.graph.settings.current.headsetTrigger) {
            VoiceService.send(this, VoiceService.ACTION_TALK, TriggerSource.HEADSET)
        }
        finish()
    }
}
