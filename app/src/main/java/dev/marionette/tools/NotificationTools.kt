package dev.marionette.tools

import dev.marionette.R
import android.content.res.Resources
import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import dev.marionette.core.boolean
import dev.marionette.core.int
import dev.marionette.core.string
import dev.marionette.notifications.MarionetteNotificationListener
import dev.marionette.notifications.NotificationIds
import dev.marionette.notifications.body
import dev.marionette.notifications.replyAction
import dev.marionette.notifications.title
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object TitleMatch {
    fun rank(title: String?, query: String): Int? {
        val t = InstalledApps.fold(title.orEmpty())
        val q = InstalledApps.fold(query)
        if (q.isEmpty() || t.isEmpty()) return null
        if (t == q) return 0
        val words = t.split(' ')
        val wanted = q.split(' ')
        val start = (0..words.size - wanted.size).firstOrNull { i -> wanted.indices.all { j -> words[i + j].startsWith(wanted[j]) } }
        if (start != null) return if (start == 0) 1 else 2
        return if (q in t) 3 else null
    }
}

private sealed interface Selection {
    data class Found(val items: List<StatusBarNotification>) : Selection
    data class Failed(val result: JsonObject) : Selection
}

private fun select(
    listener: MarionetteNotificationListener,
    args: JsonObject,
    ctx: ToolContext,
    many: Boolean = false,
    maxRank: Int = 3,
    need: (StatusBarNotification) -> Boolean = { true },
): Selection {
    val blocked = ctx.blockedPackages()
    val active = listener.activeNotifications.orEmpty().filter { it.packageName !in blocked }
    args.string("id")?.takeIf { it.isNotBlank() }?.let { id ->
        val key = NotificationIds.keyFor(id) ?: return Selection.Failed(ToolResults.error("unknown_id", "Unknown id. Call read_notifications again."))
        val sbn = active.firstOrNull { it.key == key } ?: return Selection.Failed(ToolResults.error("gone", "That notification is gone. To message the sender, use send_message with to."))
        return Selection.Found(listOf(sbn))
    }
    val app = args.string("app")?.takeIf { it.isNotBlank() }
    val from = args.string("from")?.takeIf { it.isNotBlank() }?.let(InstalledApps::fold)
    if (app == null && from == null) return Selection.Failed(ToolResults.error("bad_arguments", "Give id, app or from."))
    val packages = app?.let { query -> InstalledApps.match(InstalledApps.list(ctx.context.packageManager), query).map { it.packageName }.toSet().ifEmpty { setOf(query) } }
    val candidates = active
        .filter { packages == null || it.packageName in packages }
        .filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
        .filter(need)
        .map { it to (if (from == null) 0 else TitleMatch.rank(it.title, from)) }
        .filter { it.second != null && it.second!! <= maxRank }
    val best = candidates.minOfOrNull { it.second!! }
    val matches = candidates.filter { many || it.second == best }.map { it.first }.sortedByDescending { it.postTime }
    if (matches.isEmpty()) {
        val hint = if (from != null) "No open notification from that sender. To message them, use send_message with to." else "No matching notification. Call read_notifications."
        return Selection.Failed(ToolResults.error("not_found", hint))
    }
    if (many) return Selection.Found(matches)
    val titles = matches.map { it.packageName to it.title }.distinct()
    if (titles.size > 1) {
        return Selection.Failed(ToolResults.error("ambiguous", "Several notifications match: ${titles.take(6).joinToString { it.second ?: it.first }}. Ask which one, or pass the full from or id."))
    }
    return Selection.Found(listOf(matches.first()))
}

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
    override val readOnly = true
    override val category = ToolCategory.NOTIFICATIONS
    override val description =
        "Read the current notifications (messages, missed calls, app alerts), newest first. Each has an id for reply_notification, " +
            "notification_action and dismiss_notification. Those tools also take app and from directly, so read first only to learn what is there."
    override val parameters = schema {
        string("app", "Only notifications from this app (name or package).")
        string("from", "Only notifications whose title (sender or chat) contains this text.")
        integer("limit", "Maximum results. Default 10.")
        boolean("include_silent", "Include ongoing and silent notifications such as media or navigation. Default false.")
    }

    override fun summary(args: JsonObject) = args.string("app").orEmpty()

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject {
        val (listener, error) = listenerOrError()
        if (listener == null) return error!!
        val pm = ctx.context.packageManager
        val appFilter = args.string("app")?.takeIf { it.isNotBlank() }?.let { query ->
            InstalledApps.match(InstalledApps.list(pm), query).map { it.packageName }.toSet().ifEmpty { setOf(query) }
        }
        val includeSilent = args.boolean("include_silent") ?: false
        val from = args.string("from")?.takeIf { it.isNotBlank() }?.let(InstalledApps::fold)
        val blocked = ctx.blockedPackages()
        val items = listener.activeNotifications.orEmpty()
            .filter { it.packageName !in blocked }
            .filter { appFilter == null || it.packageName in appFilter }
            .filter { from == null || from in InstalledApps.fold(it.title.orEmpty()) }
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
                    "can_reply" to (sbn.replyAction != null).takeIf { it },
                    "actions" to sbn.notification.actions?.mapNotNull { it.title?.toString() }?.takeIf { it.isNotEmpty() },
                ).filterValues { it != null }
            },
        )
    }
}

class ReplyNotificationTool : Tool {
    override val name = "reply_notification"
    override val category = ToolCategory.NOTIFICATIONS
    override val requiresConfirmation = true
    override val description =
        "Reply to one notification by id from read_notifications. Works with the phone locked. " +
            "To answer a person or chat by name, use send_message: it replies through their open notification first and also works locked."
    override val parameters = schema {
        string("id", "Notification id.")
        string("app", "App name, e.g. WhatsApp.")
        string("from", "Sender or chat name as shown in the notification title.")
        string("text", "Reply text.", required = true)
    }

    override fun summary(args: JsonObject) = args.string("text")?.take(40).orEmpty()

    override fun confirmationPrompt(args: JsonObject, res: Resources): String =
        res.getString(R.string.confirm_reply_to, args.string("text").orEmpty(), args.string("chat").orEmpty())

    override fun endsTurn(args: JsonObject, result: JsonObject) = result.isDone()

    override suspend fun resolve(args: JsonObject, ctx: ToolContext): Resolution {
        val (listener, error) = listenerOrError()
        if (listener == null) return Resolution.Failed(error!!)
        if (args.string("text").isNullOrBlank()) return Resolution.Failed(ToolResults.error("bad_arguments", "text is required."))
        return when (val selection = select(listener, args, ctx, maxRank = 2) { it.replyAction != null }) {
            is Selection.Failed -> Resolution.Failed(selection.result)
            is Selection.Found -> selection.items.first().let { sbn ->
                Resolution.Ready(JsonObject(args + mapOf("key" to JsonPrimitive(sbn.key), "chat" to JsonPrimitive(sbn.title ?: sbn.packageName))))
            }
        }
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject {
        val sbn = NotificationReplies.byKey(args.string("key").orEmpty()) ?: return ToolResults.error("gone", "That notification is gone. Use send_message.")
        if ((sbn.title ?: sbn.packageName) != args.string("chat")) return ToolResults.error("changed", "That notification now shows another chat. Nothing was sent. Ask the user again.")
        return NotificationReplies.send(ctx, sbn, args.string("text").orEmpty()) ?: ToolResults.error("no_reply", "This notification has no reply action. Use send_message.")
    }
}

object NotificationReplies {
    fun send(ctx: ToolContext, sbn: StatusBarNotification, text: String): JsonObject? {
        val action = sbn.replyAction ?: return null
        val inputs = action.remoteInputs.filter { it.allowFreeFormInput }
        val fill = Intent()
        RemoteInput.addResultsToIntent(inputs.toTypedArray(), fill, Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } })
        action.actionIntent.send(ctx.context, 0, fill)
        return ToolResults.ok("status" to "sent", "to" to sbn.title)
    }

    sealed interface Chat
    data class One(val sbn: StatusBarNotification) : Chat
    data class Several(val titles: List<String>) : Chat
    data object None : Chat

    fun find(packages: Set<String>?, name: String): Chat {
        val listener = MarionetteNotificationListener.instance ?: return None
        val ranked = listener.activeNotifications.orEmpty()
            .filter { (packages == null || it.packageName in packages) && it.replyAction != null && it.packageName != listener.packageName }
            .filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .mapNotNull { sbn -> TitleMatch.rank(sbn.title, name)?.takeIf { it <= MAX_RANK }?.let { sbn to it } }
        val best = ranked.minOfOrNull { it.second } ?: return None
        val top = ranked.filter { it.second == best }.map { it.first }
        val titles = top.mapNotNull { it.title }.distinct()
        return if (titles.size > 1) Several(titles) else One(top.maxBy { it.postTime })
    }

    fun byKey(key: String): StatusBarNotification? =
        MarionetteNotificationListener.instance?.activeNotifications.orEmpty().firstOrNull { it.key == key }

    private const val MAX_RANK = 2
}

class NotificationActionTool : Tool {
    override val name = "notification_action"
    override val category = ToolCategory.NOTIFICATIONS
    override val description =
        "Press a button on a notification by its title, such as \"Mark as read\", \"Exit navigation\", \"Snooze\" or \"Add 1 min\" on a timer. " +
            "Omit action to open the notification itself. Select it by id, or directly by app and from without reading first. " +
            "times presses the same button several times (add 4 minutes to a timer: action \"Add 1 min\", times 4). all presses it on every matching notification."
    override val parameters = schema {
        string("id", "Notification id from read_notifications.")
        string("app", "App name, e.g. Clock or WhatsApp.")
        string("from", "Text in the notification title.")
        string("action", "Button title as listed in actions. Close wording works.")
        integer("times", "Press it this many times, 1 to 20. Default 1.")
        boolean("all", "Press it on every notification that matches app and from.")
    }

    override fun summary(args: JsonObject) = listOfNotNull(args.string("action"), args.int("times")?.takeIf { it > 1 }?.let { "×$it" }).joinToString(" ")

    override fun endsTurn(args: JsonObject, result: JsonObject) = result.isDone()

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject {
        val (listener, error) = listenerOrError()
        if (listener == null) return error!!
        val wanted = args.string("action")?.takeIf { it.isNotBlank() }
        val all = args.boolean("all") == true
        val targets = when (val selection = select(listener, args, ctx, many = all) { wanted == null || buttonFor(it, wanted) != null }) {
            is Selection.Failed -> return if (wanted != null) noButton(listener, args, ctx, wanted, selection.result) else selection.result
            is Selection.Found -> selection.items
        }
        if (wanted != null) {
            targets.mapNotNull { buttonFor(it, wanted) }.firstOrNull { button ->
                button.remoteInputs?.isNotEmpty() == true || NotificationButtons.callsOrSends(button.title?.toString().orEmpty())
            }?.let { button ->
                return ToolResults.error("not_allowed", "\"${button.title}\" calls, sends, approves or deletes. Use call or send_message, which ask the user first, or tell the user to press it.")
            }
        }
        val times = (args.int("times") ?: 1).coerceIn(1, MAX_TIMES)
        var pressed = 0
        for (target in targets) {
            repeat(if (all) 1 else times) { index ->
                val current = listener.activeNotifications.orEmpty().firstOrNull { it.key == target.key } ?: return@repeat
                val pending = if (wanted == null) current.notification.contentIntent else buttonFor(current, wanted)?.actionIntent
                if (pending == null) return@repeat
                pending.sendAllowingStart(ctx.context)
                pressed++
                if (index < times - 1) delay(PRESS_GAP_MILLIS)
            }
        }
        if (pressed == 0) return ToolResults.error("no_action", "Nothing was pressed. Call read_notifications.")
        return ToolResults.ok("status" to "pressed", "presses" to pressed, "notifications" to targets.size)
    }

    private fun noButton(listener: MarionetteNotificationListener, args: JsonObject, ctx: ToolContext, wanted: String, fallback: JsonObject): JsonObject {
        val any = select(listener, args, ctx) as? Selection.Found ?: return fallback
        val sbn = any.items.first()
        return ToolResults.error("no_action", "No button \"$wanted\". Available: ${sbn.notification.actions?.joinToString { it.title }}")
    }

    private fun buttonFor(sbn: StatusBarNotification, wanted: String): Notification.Action? {
        val actions = sbn.notification.actions.orEmpty()
        val title = NotificationButtons.pick(actions.map { it.title?.toString().orEmpty() }, wanted) ?: return null
        return actions.firstOrNull { it.title?.toString().orEmpty() == title }
    }

    companion object {
        private const val MAX_TIMES = 20
        private const val PRESS_GAP_MILLIS = 350L
    }
}

class DismissNotificationTool : Tool {
    override val name = "dismiss_notification"
    override val category = ToolCategory.NOTIFICATIONS
    override val description =
        "Dismiss notifications: by id, by app and/or from, or every clearable one with all true."
    override val parameters = schema {
        string("id", "Notification id from read_notifications.")
        string("app", "Dismiss every notification of this app.")
        string("from", "Dismiss the notifications whose title contains this text.")
        boolean("all", "Dismiss every clearable notification.")
    }

    override fun summary(args: JsonObject) = args.string("id") ?: listOfNotNull(args.string("app"), args.string("from")).joinToString(" · ")

    override fun endsTurn(args: JsonObject, result: JsonObject) = result.isDone()

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject {
        val (listener, error) = listenerOrError()
        if (listener == null) return error!!
        if (args.string("id") == "all" || (args.boolean("all") == true && args.string("app") == null && args.string("from") == null)) {
            val blocked = ctx.blockedPackages()
            val clearable = listener.activeNotifications.orEmpty().filter { it.isClearable && it.packageName !in blocked }
            clearable.forEach { listener.cancelNotification(it.key) }
            return ToolResults.ok("status" to "dismissed", "count" to clearable.size)
        }
        val targets = when (val selection = select(listener, args, ctx, many = true)) {
            is Selection.Failed -> return selection.result
            is Selection.Found -> selection.items
        }
        targets.forEach { listener.cancelNotification(it.key) }
        return ToolResults.ok("status" to "dismissed", "count" to targets.size)
    }
}

object NotificationButtons {
    private val CALL_OR_SEND = Regex("""(call|llama|devolver|reply|respond|send|envia|answer|contest)""")
    private val APPROVE_OR_DELETE = Regex(
        """(?:^|\s)(approv|aprob|confirm|pay|pag[ao]|allow|permit|autoriz|delete|borra|elimin|sign in|it s me|its me|soy yo|accept|acept|verif|transfer)""",
    )

    fun pick(titles: List<String>, wanted: String): String? {
        val folded = InstalledApps.fold(wanted)
        if (folded.isEmpty()) return null
        val named = titles.map { it to InstalledApps.fold(it) }.filter { it.second.isNotEmpty() }
        return (
            named.firstOrNull { it.second == folded }
                ?: named.firstOrNull { folded in it.second }
                ?: named.firstOrNull { " ${it.second} " in " $folded " }
            )?.first
    }

    fun callsOrSends(title: String): Boolean = InstalledApps.fold(title).let { CALL_OR_SEND.containsMatchIn(it) || APPROVE_OR_DELETE.containsMatchIn(it) }
}
