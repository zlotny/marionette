package dev.marionette.spike.assist

import android.app.Activity
import android.os.Bundle
import dev.marionette.spike.DeviceState
import dev.marionette.spike.Prefs
import dev.marionette.spike.SpikeLog
import dev.marionette.spike.launch.LaunchVariant
import dev.marionette.spike.launch.NavLauncher

class VoiceCommandActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        SpikeLog.log("Voice command trigger: ${intent.action} | ${DeviceState.summary(this)}")
        NavLauncher.fire(this, LaunchVariant.of(Prefs(this).variant))
        finish()
    }
}
