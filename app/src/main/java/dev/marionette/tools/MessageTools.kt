package dev.marionette.tools

import android.Manifest
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import dev.marionette.R
import dev.marionette.core.string
import dev.marionette.launch.LaunchStatus
import dev.marionette.notifications.title
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

class SendMessageTool : Tool {
    override val name = "send_message"
    override val category = ToolCategory.COMMUNICATION
    override val requiresConfirmation = true
    override val description =
        "Send a chat message in WhatsApp, Telegram, Signal or another messaging app in one step. " +
            "Default for \"mensaje\" or \"escríbele\" when the user does not say SMS. " +
            "If a notification from that chat is open, it replies through it, which works with the phone locked. " +
            "Otherwise WhatsApp opens the chat with the contact's number and presses Send; that needs the phone unlocked and screen control. " +
            "For SMS use send_sms."
    override val parameters = schema {
        string("app", "Messaging app. Without it, an open chat notification from any app is used first, else WhatsApp.")
        string("to", "Contact or chat name as the user says it, a phone number, or last_missed / last_call.", required = true)
        string("text", "Message text.", required = true)
    }

    override fun summary(args: JsonObject) = listOfNotNull(args.string("name") ?: args.string("to"), args.string("text")?.take(30)).joinToString(" · ")

    override fun confirmationPrompt(args: JsonObject, res: Resources): String =
        res.getString(R.string.confirm_message, args.string("text").orEmpty(), args.string("name") ?: args.string("to").orEmpty(), args.string("app_label").orEmpty())

    override suspend fun resolve(args: JsonObject, ctx: ToolContext): Resolution {
        val to = args.string("to")?.trim()?.takeIf { it.isNotEmpty() } ?: return Resolution.Failed(ToolResults.error("bad_arguments", "to is required."))
        if (args.string("text").isNullOrBlank()) return Resolution.Failed(ToolResults.error("bad_arguments", "text is required."))
        val query = args.string("app")?.takeIf { it.isNotBlank() } ?: "WhatsApp"
        val apps = InstalledApps.match(InstalledApps.list(ctx.context.packageManager), query)
        val app = apps.firstOrNull() ?: return Resolution.Failed(ToolResults.error("not_installed", "No installed app matches \"$query\"."))
        val base = args + mapOf("app_label" to JsonPrimitive(app.label), "package" to JsonPrimitive(app.packageName))
        val anyApp = args.string("app").isNullOrBlank()
        when (val chat = NotificationReplies.find(if (anyApp) null else apps.map { it.packageName }.toSet(), to)) {
            is NotificationReplies.One -> {
                looseChatConflict(chat.sbn.title, to, ctx)?.let { return Resolution.Failed(it) }
                val pm = ctx.context.packageManager
                val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(chat.sbn.packageName, 0)).toString() }.getOrDefault(app.label)
                return Resolution.Ready(
                    JsonObject(
                        base + mapOf(
                            "via" to JsonPrimitive(VIA_NOTIFICATION), "key" to JsonPrimitive(chat.sbn.key),
                            "name" to JsonPrimitive(chat.sbn.title ?: to), "app_label" to JsonPrimitive(label),
                        ),
                    ),
                )
            }
            is NotificationReplies.Several -> return Resolution.Failed(
                ToolResults.error("ambiguous", "Several open chats match: ${chat.titles.take(6).joinToString("; ")}. Ask which one, then pass the full name."),
            )
            NotificationReplies.None -> Unit
        }
        if (app.packageName !in WhatsApp.packages) {
            return Resolution.Failed(
                ToolResults.error("no_open_chat", "No open ${app.label} notification from \"$to\". Use share_text with app ${app.label}, then ui_act to pick the chat and press send."),
            )
        }
        if (to == Recipients.LAST_MISSED || to == Recipients.LAST_CALL) {
            return when (val r = Recipients.resolve(JsonObject(base), ctx)) {
                is Resolution.Ready -> Resolution.Ready(JsonObject(r.args + ("via" to JsonPrimitive(VIA_CHAT_LINK))))
                else -> r
            }
        }
        if (!Contacts.isNumber(to) && !Contacts.granted(ctx.context, Manifest.permission.READ_CONTACTS)) {
            return Resolution.Failed(ToolResults.missingPermission(Manifest.permission.READ_CONTACTS))
        }
        return when (val pick = Contacts.pick(ctx.context, to)) {
            is ContactPick.Found -> Resolution.Ready(
                JsonObject(
                    base + mapOf(
                        "via" to JsonPrimitive(VIA_CHAT_LINK), "number" to JsonPrimitive(pick.number),
                        "name" to JsonPrimitive(pick.label ?: pick.name ?: pick.number), "contact_name" to JsonPrimitive(pick.name ?: pick.number),
                    ),
                ),
            )
            is ContactPick.Ambiguous -> Resolution.Failed(ToolResults.error("ambiguous", "Several matches: ${pick.options.joinToString("; ")}. Ask the user which one."))
            ContactPick.NotFound -> Resolution.Failed(
                ToolResults.error(
                    "not_found",
                    "No contact or open chat matches \"$to\". For a group: open_app WhatsApp with read_screen, then ui_act steps: tap text \"$to\", type the message, tap text \"Send\".",
                ),
            )
        }
    }

    private suspend fun looseChatConflict(title: String?, to: String, ctx: ToolContext): JsonObject? {
        if (title == null || TitleMatch.rank(title, to) == 0 || Contacts.isNumber(to)) return null
        if (!Contacts.granted(ctx.context, Manifest.permission.READ_CONTACTS)) return null
        val contacts = when (val pick = Contacts.pick(ctx.context, to)) {
            is ContactPick.Found -> listOfNotNull(pick.name ?: pick.number).filter { TitleMatch.rank(title, it) != 0 }
            is ContactPick.Ambiguous -> pick.options
            ContactPick.NotFound -> emptyList()
        }
        if (contacts.isEmpty()) return null
        return ToolResults.error(
            "ambiguous",
            "\"$to\" matches the open chat \"$title\" and the contact ${contacts.take(5).joinToString("; ")}. Ask which one, then pass the full name.",
        )
    }

    override fun endsTurn(args: JsonObject, result: JsonObject) = result.isDone("typed")

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject {
        val text = args.string("text").orEmpty()
        val pkg = args.string("package").orEmpty()
        if (args.string("via") == VIA_NOTIFICATION) {
            val sbn = NotificationReplies.byKey(args.string("key").orEmpty())
                ?: return ToolResults.error("gone", "The chat notification is gone. Call send_message again.")
            if (sbn.title != null && sbn.title != args.string("name")) return ToolResults.error("changed", "That notification now shows another chat. Nothing was sent. Call send_message again.")
            return NotificationReplies.send(ctx, sbn, text) ?: ToolResults.error("no_reply", "Nothing was sent: that chat has no reply action. Tell the user.")
        }
        val phone = WhatsApp.phone(ctx, args.string("number").orEmpty())
            ?: return ToolResults.error("bad_number", "The number has no country code and cannot be completed. Ask the user for it with the country code.")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$phone&text=${Uri.encode(text)}")).setPackage(pkg)
        return when (ctx.launcher.launch(intent, args.string("app_label").orEmpty())) {
            LaunchStatus.FAILED -> ToolResults.error("launch_failed", "WhatsApp could not open the chat.")
            LaunchStatus.WAITING_FOR_UNLOCK -> ToolResults.ok(
                "status" to "waiting_for_unlock",
                "message" to "The chat opens with the text typed once the user unlocks the phone. The user presses send.",
            )
            LaunchStatus.STARTED -> if (ScreenWaits.tap(ctx, pkg, WhatsApp.sendButton, WhatsApp.SEND_WAIT_MILLIS, typed = text, chat = listOfNotNull(args.string("contact_name") ?: args.string("name"), phone))) {
                ToolResults.ok("status" to "sent", "to" to args.string("name"))
            } else {
                ToolResults.ok("status" to "typed", "message" to "The chat is open with the text typed, but Send was not pressed. Use ui_act or ask the user to press send.")
            }
        }
    }

    companion object {
        private const val VIA_NOTIFICATION = "notification"
        private const val VIA_CHAT_LINK = "chat_link"
    }
}

object WhatsApp {
    val packages = setOf("com.whatsapp", "com.whatsapp.w4b")
    val sendButton = ScreenMatch(ids = setOf("send"), texts = setOf("send", "enviar"))
    const val SEND_WAIT_MILLIS = 6_000L

    fun phone(ctx: ToolContext, number: String): String? {
        if (number.trim().startsWith("+") || number.trim().startsWith("00")) return number.filter(Char::isDigit).removePrefix("00")
        val country = ctx.context.getSystemService(TelephonyManager::class.java)?.let { it.simCountryIso.ifBlank { it.networkCountryIso } }
            ?.uppercase(Locale.ROOT)?.ifBlank { null } ?: Locale.getDefault().country
        return PhoneNumberUtils.formatNumberToE164(number, country)?.filter(Char::isDigit)
    }
}
