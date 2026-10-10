package dev.marionette.media

import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import dev.marionette.notifications.MarionetteNotificationListener

object MediaSessions {
    fun available(): Boolean = MarionetteNotificationListener.instance != null

    fun active(context: Context): List<MediaController> {
        if (!available()) return emptyList()
        val manager = context.getSystemService(MediaSessionManager::class.java)
        return runCatching { manager.getActiveSessions(MarionetteNotificationListener.component(context)) }.getOrNull().orEmpty()
    }
}
