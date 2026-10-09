package dev.marionette.spike.launch

import android.app.Activity
import android.app.KeyguardManager
import android.os.Bundle
import dev.marionette.spike.DeviceState
import dev.marionette.spike.SpikeLog

class NavTrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val source = intent.getStringExtra(EXTRA_SOURCE) ?: "unknown"
        SpikeLog.log("S1 trampoline created via $source | ${DeviceState.summary(this)}")
        if (intent.getBooleanExtra(EXTRA_DISMISS, false)) dismissThenStart() else startAndFinish()
    }

    private fun startAndFinish() {
        NavLauncher.startMaps(this, "trampoline")
        window.decorView.postDelayed({ finish() }, 1500)
    }

    private fun dismissThenStart() {
        getSystemService(KeyguardManager::class.java).requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    SpikeLog.log("S1 V6 keyguard dismissed")
                    startAndFinish()
                }

                override fun onDismissCancelled() {
                    SpikeLog.log("S1 V6 keyguard dismiss cancelled")
                    finish()
                }

                override fun onDismissError() {
                    SpikeLog.log("S1 V6 keyguard dismiss error")
                    startAndFinish()
                }
            },
        )
    }

    companion object {
        const val EXTRA_SOURCE = "source"
        const val EXTRA_DISMISS = "dismiss"
    }
}
