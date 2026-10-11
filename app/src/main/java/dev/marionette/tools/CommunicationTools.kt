package dev.marionette.tools

import dev.marionette.R
import android.content.res.Resources
import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.SmsManager
import dev.marionette.core.int
import dev.marionette.core.string
import dev.marionette.launch.LaunchStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

data class ContactPhone(val name: String, val number: String, val type: String, val starred: Boolean)

object Contacts {
    suspend fun search(resolver: ContentResolver, query: String, limit: Int): List<ContactPhone> = withContext(Dispatchers.IO) {
        val folded = InstalledApps.fold(query)
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL,
            ContactsContract.CommonDataKinds.Phone.STARRED,
        )
        val all = mutableListOf<ContactPhone>()
        resolver.query(uri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val number = c.getString(1) ?: continue
                val type = ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                    android.content.res.Resources.getSystem(), c.getInt(2), c.getString(3),
                ).toString()
                all += ContactPhone(name, number, type, c.getInt(4) == 1)
            }
        }
        val digits = query.filter(Char::isDigit)
        all.mapNotNull { contact ->
            val name = InstalledApps.fold(contact.name)
            val score = when {
                name == folded -> 0
                name.startsWith(folded) -> 1
                name.split(' ').any { it.startsWith(folded) } -> 2
                folded.isNotEmpty() && folded in name -> 3
                digits.length >= 4 && contact.number.filter(Char::isDigit).endsWith(digits) -> 1
                else -> null
            }
            score?.let { contact to it }
        }
            .sortedWith(compareBy({ it.second }, { !it.first.starred }))
            .map { it.first }
            .distinctBy { it.name + it.number.filter(Char::isDigit) }
            .take(limit)
    }
}

class SearchContactsTool : Tool {
    override val name = "search_contacts"
    override val readOnly = true
    override val category = ToolCategory.COMMUNICATION
    override val permissions = listOf(Manifest.permission.READ_CONTACTS)
    override val description = "Find contacts by name (accents and case ignored) or by the last digits of a number. Returns names and phone numbers."
    override val parameters = schema {
        string("query", "Name, nickname or digits.", required = true)
        integer("limit", "Maximum results. Default 8.")
    }

    override fun summary(args: JsonObject) = args.string("query").orEmpty()

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject {
        val query = args.string("query")?.takeIf { it.isNotBlank() } ?: return ToolResults.error("bad_arguments", "query is required.")
        val found = Contacts.search(ctx.context.contentResolver, query, (args.int("limit") ?: 8).coerceIn(1, 30))
        return ToolResults.ok(
            "count" to found.size,
            "contacts" to found.map { mapOf("name" to it.name, "number" to it.number, "type" to it.type, "favorite" to it.starred) },
        )
    }
}

class CallTool : Tool {
    override val name = "call"
    override val category = ToolCategory.COMMUNICATION
    override val requiresConfirmation = true
    override val permissions = listOf(Manifest.permission.CALL_PHONE)
    override val description = "Place a phone call to a number. Use search_contacts first to get the number for a name."
    override val parameters = schema {
        string("number", "Phone number to call.", required = true)
        string("name", "Contact name, for the confirmation and logs.")
    }

    override fun summary(args: JsonObject) = args.string("name") ?: args.string("number").orEmpty()

    override fun confirmationPrompt(args: JsonObject, res: Resources): String = res.getString(R.string.confirm_call, summary(args))

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject {
        val number = args.string("number")?.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }?.takeIf { it.isNotEmpty() }
            ?: return ToolResults.error("bad_arguments", "number is required.")
        val intent = Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null))
        ctx.playback.stayPaused()
        return when (ctx.launcher.launch(intent, "call", unlock = false)) {
            LaunchStatus.FAILED -> ToolResults.error("call_failed", "The call could not start.")
            else -> ToolResults.ok("status" to "calling", "number" to number)
        }
    }

    override fun silencesTurn(args: JsonObject, result: JsonObject) = !result.isError()
}

class SendSmsTool : Tool {
    override val name = "send_sms"
    override val category = ToolCategory.COMMUNICATION
    override val requiresConfirmation = true
    override val permissions = listOf(Manifest.permission.SEND_SMS)
    override val description = "Send an SMS text message directly, without opening an app. Use search_contacts first to get the number for a name."
    override val parameters = schema {
        string("number", "Recipient phone number.", required = true)
        string("text", "Message text.", required = true)
        string("name", "Contact name, for the confirmation and logs.")
    }

    override fun summary(args: JsonObject) = args.string("name") ?: args.string("number").orEmpty()

    override fun confirmationPrompt(args: JsonObject, res: Resources): String =
        res.getString(R.string.confirm_sms, args.string("text").orEmpty(), summary(args))

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject {
        val number = args.string("number")?.filter { it.isDigit() || it == '+' }?.takeIf { it.isNotEmpty() }
            ?: return ToolResults.error("bad_arguments", "number is required.")
        val text = args.string("text")?.takeIf { it.isNotBlank() } ?: return ToolResults.error("bad_arguments", "text is required.")
        val sms = smsManager(ctx.context) ?: return ToolResults.error("no_sms", "This device cannot send SMS.")
        sms.sendMultipartTextMessage(number, null, sms.divideMessage(text), null, null)
        return ToolResults.ok("status" to "sent", "number" to number)
    }

    private fun smsManager(context: Context): SmsManager? = context.getSystemService(SmsManager::class.java)
}
