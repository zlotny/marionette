package dev.marionette.tools

import dev.marionette.R
import android.content.res.Resources
import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import dev.marionette.core.boolean
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

    fun byName(resolver: ContentResolver, query: String): List<Pair<Long, String>> {
        val folded = InstalledApps.fold(query)
        val all = mutableListOf<Pair<Long, String>>()
        resolver.query(ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) all += c.getLong(0) to (c.getString(1) ?: continue)
        }
        val scored = all.mapNotNull { contact ->
            val name = InstalledApps.fold(contact.second)
            val score = when {
                name == folded -> 0
                name.startsWith(folded) -> 1
                name.split(' ').any { it.startsWith(folded) } -> 2
                folded.isNotEmpty() && folded in name -> 3
                else -> null
            }
            score?.let { contact to it }
        }
        val best = scored.minOfOrNull { it.second } ?: return emptyList()
        return scored.filter { it.second == best }.map { it.first }
    }

    fun nameFor(context: Context, number: String): String? {
        if (number.count(Char::isDigit) < 3 || !granted(context, Manifest.permission.READ_CONTACTS)) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return runCatching {
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    fun granted(context: Context, permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

object PhoneNumbers {
    private const val MATCH_DIGITS = 9

    fun same(a: String, b: String): Boolean {
        val x = a.filter(Char::isDigit)
        val y = b.filter(Char::isDigit)
        if (x.length < 4 || y.length < 4) return InstalledApps.fold(a) == InstalledApps.fold(b)
        val n = minOf(MATCH_DIGITS, x.length, y.length)
        return x.takeLast(n) == y.takeLast(n)
    }
}

class ContactFilter(private val query: String, private val numbers: List<String>) {
    private val folded = InstalledApps.fold(query)

    fun matches(address: String, name: String?): Boolean =
        numbers.any { PhoneNumbers.same(it, address) } ||
            folded in InstalledApps.fold(address) ||
            (name != null && folded in InstalledApps.fold(name))

    companion object {
        suspend fun of(context: Context, query: String?): ContactFilter? {
            val q = query?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val numbers = if (q.count(Char::isDigit) >= 4) {
                listOf(q)
            } else if (Contacts.granted(context, Manifest.permission.READ_CONTACTS)) {
                Contacts.search(context.contentResolver, q, 10).map { it.number }
            } else {
                emptyList()
            }
            return ContactFilter(q, numbers)
        }
    }
}

object MessageKinds {
    fun sms(type: Int): String = when (type) {
        Telephony.Sms.MESSAGE_TYPE_INBOX -> "received"
        Telephony.Sms.MESSAGE_TYPE_SENT -> "sent"
        Telephony.Sms.MESSAGE_TYPE_DRAFT -> "draft"
        Telephony.Sms.MESSAGE_TYPE_FAILED -> "failed"
        Telephony.Sms.MESSAGE_TYPE_OUTBOX, Telephony.Sms.MESSAGE_TYPE_QUEUED -> "sending"
        else -> "other"
    }

    fun call(type: Int): String = when (type) {
        CallLog.Calls.INCOMING_TYPE, CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> "incoming"
        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
        CallLog.Calls.MISSED_TYPE -> "missed"
        CallLog.Calls.VOICEMAIL_TYPE -> "voicemail"
        CallLog.Calls.REJECTED_TYPE -> "rejected"
        CallLog.Calls.BLOCKED_TYPE -> "blocked"
        else -> "other"
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

class ReadSmsTool : Tool {
    override val name = "read_sms"
    override val readOnly = true
    override val category = ToolCategory.COMMUNICATION
    override val permissions = listOf(Manifest.permission.READ_SMS)
    override val description = "Read recent SMS text messages, newest first, received and sent. Filter by contact name or number, and by text."
    override val parameters = schema {
        string("contact", "Contact name, number or sender name.")
        string("query", "Only messages that contain this text.")
        boolean("unread_only", "Only unread received messages.")
        integer("limit", "Maximum results. Default 10.")
    }

    override fun summary(args: JsonObject) = listOfNotNull(args.string("contact"), args.string("query")).joinToString(" · ")

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject = withContext(Dispatchers.IO) {
        val filter = ContactFilter.of(ctx.context, args.string("contact"))
        val text = args.string("query")?.trim()?.takeIf { it.isNotEmpty() }?.let(InstalledApps::fold)
        val unreadOnly = args.boolean("unread_only") == true
        val limit = (args.int("limit") ?: 10).coerceIn(1, 50)
        val projection = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ)
        val names = mutableMapOf<String, String?>()
        val messages = mutableListOf<Map<String, Any?>>()
        ctx.context.contentResolver.query(Telephony.Sms.CONTENT_URI, projection, null, null, "${Telephony.Sms.DATE} DESC")?.use { c ->
            var scanned = 0
            while (c.moveToNext() && messages.size < limit && scanned++ < MAX_SCAN) {
                val address = c.getString(0).orEmpty()
                val body = c.getString(1).orEmpty()
                val read = c.getInt(4) == 1
                if (unreadOnly && (read || c.getInt(3) != Telephony.Sms.MESSAGE_TYPE_INBOX)) continue
                if (text != null && text !in InstalledApps.fold(body)) continue
                val name = if (address in names) names[address] else Contacts.nameFor(ctx.context, address).also { names[address] = it }
                if (filter != null && !filter.matches(address, name)) continue
                messages += mapOf(
                    "from_or_to" to (name ?: address),
                    "number" to address.takeIf { name != null },
                    "kind" to MessageKinds.sms(c.getInt(3)),
                    "time" to LocalTimes.format(c.getLong(2), false),
                    "read" to read,
                    "text" to body.take(MAX_BODY),
                ).filterValues { it != null }
            }
        }
        ToolResults.ok("count" to messages.size, "messages" to messages)
    }

    companion object {
        private const val MAX_SCAN = 2000
        private const val MAX_BODY = 600
    }
}

class RecentCallsTool : Tool {
    override val name = "recent_calls"
    override val readOnly = true
    override val category = ToolCategory.COMMUNICATION
    override val permissions = listOf(Manifest.permission.READ_CALL_LOG)
    override val description = "Read the call history, newest first: who, missed, incoming or outgoing, when and how long."
    override val parameters = schema {
        string("type", "Only this kind of call. Default all.", enum = listOf("all", "missed", "incoming", "outgoing"))
        string("contact", "Contact name or number.")
        integer("limit", "Maximum results. Default 10.")
    }

    override fun summary(args: JsonObject) = listOfNotNull(args.string("type")?.takeIf { it != "all" }, args.string("contact")).joinToString(" · ")

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject = withContext(Dispatchers.IO) {
        val filter = ContactFilter.of(ctx.context, args.string("contact"))
        val type = args.string("type")?.takeIf { it != "all" }
        val limit = (args.int("limit") ?: 10).coerceIn(1, 50)
        val projection = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION)
        val calls = mutableListOf<Map<String, Any?>>()
        ctx.context.contentResolver.query(CallLog.Calls.CONTENT_URI, projection, null, null, "${CallLog.Calls.DATE} DESC")?.use { c ->
            var scanned = 0
            while (c.moveToNext() && calls.size < limit && scanned++ < MAX_SCAN) {
                val kind = MessageKinds.call(c.getInt(2))
                if (type != null && kind != type) continue
                val number = c.getString(0).orEmpty()
                val name = c.getString(1)?.takeIf { it.isNotBlank() }
                if (filter != null && !filter.matches(number, name)) continue
                calls += mapOf(
                    "name" to name,
                    "number" to number.ifBlank { "private" },
                    "kind" to kind,
                    "time" to LocalTimes.format(c.getLong(3), false),
                    "seconds" to c.getLong(4),
                ).filterValues { it != null }
            }
        }
        ToolResults.ok("count" to calls.size, "calls" to calls)
    }

    companion object {
        private const val MAX_SCAN = 1000
    }
}

class CreateContactTool : Tool {
    override val name = "create_contact"
    override val category = ToolCategory.COMMUNICATION
    override val requiresConfirmation = true
    override val permissions = listOf(Manifest.permission.WRITE_CONTACTS)
    override val description = "Save a new contact on the phone with a name and a phone number or email."
    override val parameters = schema {
        string("name", "Full name.", required = true)
        string("number", "Phone number.")
        string("email", "Email address.")
    }

    override fun summary(args: JsonObject) = args.string("name").orEmpty()

    override fun confirmationPrompt(args: JsonObject, res: Resources): String = res.getString(R.string.confirm_create_contact, summary(args))

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject = withContext(Dispatchers.IO) {
        val name = args.string("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return@withContext ToolResults.error("bad_arguments", "name is required.")
        val number = args.string("number")?.trim()?.takeIf { it.isNotEmpty() }
        val email = args.string("email")?.trim()?.takeIf { it.isNotEmpty() }
        val ops = arrayListOf(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build(),
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                .build(),
        )
        number?.let { ops += ContactInfo.phone(it).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0).build() }
        email?.let { ops += ContactInfo.email(it).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0).build() }
        ctx.context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        ToolResults.ok("status" to "created", "name" to name, "number" to number, "email" to email)
    }
}

class AddContactInfoTool : Tool {
    override val name = "add_contact_info"
    override val category = ToolCategory.COMMUNICATION
    override val requiresConfirmation = true
    override val permissions = listOf(Manifest.permission.WRITE_CONTACTS, Manifest.permission.READ_CONTACTS)
    override val description = "Add a phone number or email to an existing contact, found by name."
    override val parameters = schema {
        string("contact", "Contact name.", required = true)
        string("number", "Phone number to add.")
        string("email", "Email address to add.")
    }

    override fun summary(args: JsonObject) = listOfNotNull(args.string("number") ?: args.string("email"), args.string("contact")).joinToString(" · ")

    override fun confirmationPrompt(args: JsonObject, res: Resources): String =
        res.getString(R.string.confirm_add_contact_info, args.string("number") ?: args.string("email").orEmpty(), args.string("contact").orEmpty())

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject = withContext(Dispatchers.IO) {
        val query = args.string("contact")?.trim()?.takeIf { it.isNotEmpty() } ?: return@withContext ToolResults.error("bad_arguments", "contact is required.")
        val number = args.string("number")?.trim()?.takeIf { it.isNotEmpty() }
        val email = args.string("email")?.trim()?.takeIf { it.isNotEmpty() }
        if (number == null && email == null) return@withContext ToolResults.error("bad_arguments", "number or email is required.")
        val resolver = ctx.context.contentResolver
        val found = Contacts.byName(resolver, query)
        val contact = when {
            found.isEmpty() -> return@withContext ToolResults.error("not_found", "No contact matches \"$query\".")
            found.size > 1 -> return@withContext ToolResults.error("ambiguous", "Several contacts match: ${found.take(8).joinToString { it.second }}. Use the full name.")
            else -> found.first()
        }
        val rawId = resolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID, ContactsContract.RawContacts.ACCOUNT_TYPE),
            "${ContactsContract.RawContacts.CONTACT_ID} = ? AND ${ContactsContract.RawContacts.DELETED} = 0",
            arrayOf(contact.first.toString()),
            null,
        )?.use { c ->
            val entries = buildList { while (c.moveToNext()) add(c.getLong(0) to c.getString(1)) }
            (entries.firstOrNull { it.second == GOOGLE_ACCOUNT } ?: entries.firstOrNull { it.second == null } ?: entries.firstOrNull())?.first
        } ?: return@withContext ToolResults.error("not_found", "The contact has no editable entry.")
        val ops = arrayListOf<ContentProviderOperation>()
        number?.let { ops += ContactInfo.phone(it).withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId).build() }
        email?.let { ops += ContactInfo.email(it).withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId).build() }
        resolver.applyBatch(ContactsContract.AUTHORITY, ops)
        ToolResults.ok("status" to "added", "contact" to contact.second, "number" to number, "email" to email)
    }
}

private const val GOOGLE_ACCOUNT = "com.google"

private object ContactInfo {
    fun phone(number: String): ContentProviderOperation.Builder = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
        .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
        .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)

    fun email(address: String): ContentProviderOperation.Builder = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
        .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, address)
        .withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_OTHER)
}
