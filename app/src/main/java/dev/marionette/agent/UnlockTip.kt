package dev.marionette.agent

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import dev.marionette.MarionetteApp
import dev.marionette.R
import dev.marionette.core.string
import dev.marionette.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

class UnlockTip(private val context: Context, private val settings: SettingsStore, private val scope: CoroutineScope) {
    fun offer() {
        if (settings.current.unlockTipsShown >= MAX_TIPS) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val body = context.getString(R.string.unlock_tip_body)
        val notification = Notification.Builder(context, MarionetteApp.CHANNEL_TIPS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.unlock_tip_title))
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
        scope.launch { settings.update { it.copy(unlockTipsShown = it.unlockTipsShown + 1) } }
    }

    companion object {
        const val MAX_TIPS = 3
        private const val NOTIFICATION_ID = 4107

        fun isUnlockBlock(result: JsonObject): Boolean =
            result.string("status") == "waiting_for_unlock" || result["error"]?.toString()?.contains("needs_unlock") == true
    }
}
