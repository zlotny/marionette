package dev.marionette.spike

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class SpikeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SpikeLog.init(this)
        getSystemService(NotificationManager::class.java).createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_LISTENER, "Listener", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CHANNEL_LAUNCH, "Navigation launch", NotificationManager.IMPORTANCE_HIGH),
            )
        )
    }

    companion object {
        const val CHANNEL_LISTENER = "listener"
        const val CHANNEL_LAUNCH = "launch"
    }
}
