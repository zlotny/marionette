package dev.marionette.launch

import android.app.Activity
import android.app.KeyguardManager
import android.os.Bundle
import dev.marionette.MarionetteApp
import dev.marionette.core.Logger

class UnlockTrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val launcher = MarionetteApp.graph.launcher
        getSystemService(KeyguardManager::class.java).requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    Logger.i("Keyguard dismissed")
                    launcher.onKeyguardDismissed(this@UnlockTrampolineActivity)
                    finish()
                }

                override fun onDismissCancelled() {
                    Logger.i("Keyguard dismiss cancelled")
                    launcher.onKeyguardDismissCancelled()
                    finish()
                }

                override fun onDismissError() {
                    Logger.w("Keyguard dismiss error")
                    launcher.onKeyguardDismissCancelled()
                    finish()
                }
            },
        )
    }
}
