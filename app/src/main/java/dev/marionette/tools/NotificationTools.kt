package dev.marionette.tools

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import dev.marionette.core.boolean
import dev.marionette.core.int
import dev.marionette.core.string
import dev.marionette.notifications.MarionetteNotificationListener
import dev.marionette.notifications.NotificationIds
import dev.marionette.notifications.body
import dev.marionette.notifications.replyAction
import dev.marionette.notifications.title
import kotlinx.serialization.json.JsonObject

private fun listenerOrError(): Pair<MarionetteNotificationListener?, JsonObject?> {
    val listener = MarionetteNotificationListener.instance
    return if (listener == null) {
        null to ToolResults.error("missing_permission", "Notification access is off. Ask the user to enable it in Marionette settings.")
    } else {
        listener to null
    }
}

class ReadNotificationsTool : Tool {
    override val name = "read_notifications"
    override val category = ToolCategory.NOTIFICATIONS
    override val description =
        "Read the current notifications (messages, missed calls, app alerts), newest first. Each has an id for reply_notification, " +
            "notification_action and dismiss_notification."
    override val parameters = schema {
        string("app", "Only notifications from this app (name or package).")
        integer("limit", "Maximum results. Default 10.")
        boolean("include_silent", "Include ongoing and silent notifications such as media or navigation. Default false.")
    }

    override fun summary(args: JsonObject) = args.string("app") ?: "all"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject {
        val (listener, error) = listenerOrError()
        if (listener == null) return error!!
        val pm = ctx.context.packageManager
        val appFilter = args.string("app")?.takeIf { it.isNotBlank() }?.let { query ->
            InstalledApps.match(InstalledApps.list(pm), query).map { it.packageName }.toSet().ifEmpty { setOf(query) }
        }
        val includeSilent = args.boolean("include_silent") ?: false
        val items = listener.activeNotifications.orEmpty()
            .filter { it.packageName != ctx.context.packageName }
            .filter { appFilter == null || it.packageName in appFilter }
            .filter { includeSilent || (!it.isOngoing && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0) }
            .sortedByDescending { it.postTime }
            .take((args.int("limit") ?: 10).coerceIn(1, 50))
        return ToolResults.ok(
            "count" to items.size,
            "notifications" to items.map { sbn ->
                val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
                mapOf(
                    "id" to NotificationIds.idFor(sbn.key),
                    "app" to label,
                    "title" to sbn.title,
                    "text" to sbn.body?.take(600),
                    "minutes_ago" to ((System.currentTimeMillis() - sbn.postTime) / 60_000),
                    "can_reply" to (sbn.replyAction != null),
                    "actions" to sbn.notification.actions?.mapNotNull { it.title?.toString() }.orEmpty(),
                ).filterValues { it != null }
            },
        )
    }
}

class ReplyNotificationTool : Tool {
    override val name = "reply_notification"
    override val category = ToolCategory.NOTIFICATIONS
    override val requiresConfirmation = true
    override val description = "Reply to a message notification (WhatsApp, Telegram, SMS...) by its id from read_notifications."
    override val parameters = schema {
        string("id", "Notification id.", required = true)
        string("text", "Reply text.", required = true)
    }

    override fun summary(args: JsonObject) = args.string("text")?.take(40) ?: "reply"

    override fun confirmationPrompt(args: JsonObject) = "Reply \"${args.string("text").orEmpty()}\"?"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject {
        val (listener, error) = listenerOrError()
        if (listener == null) return error!!
        val text = args.string("text")?.takeIf { it.isNotBlank() } ?: return ToolResults.error("bad_arguments", "text is required.")
        val key = args.string("id")?.let(NotificationIds::keyFor) ?: return ToolResults.error("unknown_id", "Unknown id. Call read_notifications again.")
        val sbn = listener.activeNotifications.orEmpty().firstOrNull { it.key == key } ?: return ToolResults.error("gone", "That notification is gone.")
        val action = sbn.replyAction ?: return ToolResults.error("no_reply", "This notification has no reply action. Use open_app and ui_act instead.")
        val inputs = action.remoteInputs.filter { it.allowFreeFormInput }
        val fill = Intent()
        RemoteInput.addResultsToIntent(inputs.toTypedArray(), fill, Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } })
        action.actionIntent.send(ctx.context, 0, fill)
        return ToolResults.ok("status" to "sent", "to" to sbn.title)
    }
}

class NotificationActionTool : Tool {
    override val name = "notification_action"
    override val category = ToolCategory.NOTIFICATIONS
    override val description =
        "Press a button on a notification by its title, such as \"Mark as read\", \"Exit navigation\" or \"Snooze\". " +
            "Omit action to open the notification itself."
    override val parameters = schema {
        string("id", "Notification id from read_notifications.", required = true)
        string("action", "Button title as listed in actions.")
    }

    override fun summary(args: JsonObject) = args.string("action") ?: "open"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject {
        val (listener, error) = listenerOrError()
        if (listener == null) return error!!
        val key = args.string("id")?.let(NotificationIds::keyFor) ?: return ToolResults.error("unknown_id", "Unknown id. Call read_notifications again.")
        val sbn = listener.activeNotifications.orEmpty().firstOrNull { it.key == key } ?: return ToolResults.error("gone", "That notification is gone.")
        val wanted = args.string("action")?.takeIf { it.isNotBlank() }
        val pending = if (wanted == null) {
            sbn.notification.contentIntent ?: return ToolResults.error("no_action", "This notification cannot be opened.")
        } else {
            val folded = InstalledApps.fold(wanted)
            sbn.notification.actions?.firstOrNull { InstalledApps.fold(it.title?.toString().orEmpty()) == folded }?.actionIntent
                ?: sbn.notification.actions?.firstOrNull { folded in InstalledApps.fold(it.title?.toString().orEmpty()) }?.actionIntent
                ?: return ToolResults.error("no_action", "No button \"$wanted\". Available: ${sbn.notification.actions?.joinToString { it.title }}")
        }
        pending.send()
        return ToolResults.ok("status" to "pressed")
    }
}

class DismissNotificationTool : Tool {
    override val name = "dismiss_notification"
    override val category = ToolCategory.NOTIFICATIONS
    override val description = "Dismiss a notification by id, or all clearable notifications with id = all."
    override val parameters = schema {
        string("id", "Notification id, or all.", required = true)
    }

    override fun summary(args: JsonObject) = args.string("id") ?: ""

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject {
        val (listener, error) = listenerOrError()
        if (listener == null) return error!!
        val id = args.string("id") ?: return ToolResults.error("bad_arguments", "id is required.")
        if (id == "all") {
            listener.cancelAllNotifications()
        } else {
            val key = NotificationIds.keyFor(id) ?: return ToolResults.error("unknown_id", "Unknown id. Call read_notifications again.")
            listener.cancelNotification(key)
        }
        return ToolResults.ok("status" to "dismissed")
    }
}
