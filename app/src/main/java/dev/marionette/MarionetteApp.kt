package dev.marionette

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dev.marionette.voice.stt.Parakeet
import dev.marionette.voice.phonon.Phonon

class MarionetteApp : Application() {
    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        Parakeet.init(this)
        Phonon.init(this)
        getSystemService(NotificationManager::class.java).createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_LISTENER, getString(R.string.channel_listener), NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                },
                NotificationChannel(CHANNEL_ALERTS, getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CHANNEL_TIPS, getString(R.string.channel_tips), NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
    }

    companion object {
        const val CHANNEL_LISTENER = "listener"
        const val CHANNEL_ALERTS = "alerts"
        const val CHANNEL_TIPS = "tips"

        lateinit var graph: AppGraph
            private set
    }
}
