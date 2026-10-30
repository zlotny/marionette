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
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard.isDeviceLocked) {
            window.decorView.postDelayed({ dismiss(keyguard, launcher) }, TRUST_SETTLE_MILLIS)
        } else {
            dismiss(keyguard, launcher)
        }
    }

    private fun dismiss(keyguard: KeyguardManager, launcher: Launcher) {
        if (isFinishing) return
        Logger.i("Keyguard dismiss (device locked: ${keyguard.isDeviceLocked})")
        keyguard.requestDismissKeyguard(
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

    private companion object {
        const val TRUST_SETTLE_MILLIS = 700L
    }
}
