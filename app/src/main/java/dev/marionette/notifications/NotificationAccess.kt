package dev.marionette.notifications

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.marionette.core.Logger

class MarionetteNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
        Logger.i("Notification listener connected")
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: MarionetteNotificationListener? = null
            private set

        fun component(context: Context) = ComponentName(context, MarionetteNotificationListener::class.java)

        fun isEnabled(context: Context): Boolean =
            context.getSystemService(android.app.NotificationManager::class.java)
                .isNotificationListenerAccessGranted(component(context))
    }
}

object NotificationIds {
    private val keys = LinkedHashMap<String, String>()
    private var next = 1

    @Synchronized
    fun idFor(key: String): String = keys.entries.firstOrNull { it.value == key }?.key ?: "n${next++}".also {
        keys[it] = key
        if (keys.size > 300) keys.remove(keys.keys.first())
    }

    @Synchronized
    fun keyFor(id: String): String? = keys[id]
}

val StatusBarNotification.title: String?
    get() = notification.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
        ?: notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

val StatusBarNotification.body: String?
    get() {
        val extras = notification.extras
        @Suppress("DEPRECATION")
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            ?.mapNotNull { (it as? android.os.Bundle)?.let { b -> listOfNotNull(b.getCharSequence("sender")?.toString(), b.getCharSequence("text")?.toString()).joinToString(": ") } }
            ?.takeLast(5)
        if (!messages.isNullOrEmpty()) return messages.joinToString("\n")
        return extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    }

val StatusBarNotification.replyAction: Notification.Action?
    get() = notification.actions?.firstOrNull { action -> action.remoteInputs?.any { it.allowFreeFormInput } == true }
