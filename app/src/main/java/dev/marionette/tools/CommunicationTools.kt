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
import dev.marionette.core.arr
import dev.marionette.core.boolean
import dev.marionette.core.int
import dev.marionette.core.obj
import dev.marionette.core.string
import dev.marionette.launch.LaunchStatus
import dev.marionette.tools.places.FindPlacesTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class ContactPhone(val name: String, val number: String, val type: String, val starred: Boolean, val mobile: Boolean = false)

sealed interface ContactPick {
    data class Found(val name: String?, val number: String, val label: String? = null) : ContactPick
    data class Ambiguous(val options: List<String>) : ContactPick
    data object NotFound : ContactPick
}

object Contacts {
    suspend fun search(resolver: ContentResolver, query: String, limit: Int): List<ContactPhone> =
        scored(load(resolver), query)
            .map { it.first }
            .distinctBy { it.name + it.number.filter(Char::isDigit) }
            .take(limit)

    suspend fun pick(context: Context, to: String): ContactPick {
        if (isNumber(to)) return ContactPick.Found(nameFor(context, to), to)
        if (!granted(context, Manifest.permission.READ_CONTACTS)) return ContactPick.NotFound
        return choose(scored(load(context.contentResolver), to))
    }

    private suspend fun load(resolver: ContentResolver): List<ContactPhone> = withContext(Dispatchers.IO) {
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
                all += ContactPhone(name, number, type, c.getInt(4) == 1, c.getInt(2) == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
            }
        }
        all
    }

    fun details(resolver: ContentResolver, name: String): Map<String, List<String>> {
        fun values(uri: Uri, column: String, displayName: String): List<String> =
            resolver.query(uri, arrayOf(column), "$displayName = ?", arrayOf(name), null)?.use { c ->
                buildList { while (c.moveToNext()) c.getString(0)?.takeIf { it.isNotBlank() }?.let(::add) }
            }.orEmpty().distinct()
        return mapOf(
            "emails" to values(ContactsContract.CommonDataKinds.Email.CONTENT_URI, ContactsContract.CommonDataKinds.Email.ADDRESS, ContactsContract.CommonDataKinds.Email.DISPLAY_NAME),
            "addresses" to values(
                ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_URI,
                ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS,
                ContactsContract.CommonDataKinds.StructuredPostal.DISPLAY_NAME,
            ).map { it.replace('\n', ' ') },
        ).filterValues { it.isNotEmpty() }
    }

    fun isNumber(text: String): Boolean = text.count(Char::isDigit) >= 3 && text.none(Char::isLetter)

    fun choose(scored: List<Pair<ContactPhone, Int>>): ContactPick {
        val best = scored.minOfOrNull { it.second } ?: return ContactPick.NotFound
        val top = scored.filter { it.second == best }.map { it.first }
        val names = top.map { it.name }.distinct()
        if (names.size > 1) return ContactPick.Ambiguous(names.take(MAX_OPTIONS))
        val phones = top.distinctBy { it.number.filter(Char::isDigit).takeLast(9) }
        phones.singleOrNull()?.let { return ContactPick.Found(it.name, it.number) }
        val mobile = phones.filter { it.mobile }.singleOrNull()
            ?: return ContactPick.Ambiguous(phones.take(MAX_OPTIONS).map { "${it.name} ${it.type} ${it.number}" })
        return ContactPick.Found(mobile.name, mobile.number, "${mobile.name} (${mobile.type} ${mobile.number})")
    }

    fun scored(all: List<ContactPhone>, query: String): List<Pair<ContactPhone, Int>> {
        val folded = InstalledApps.fold(query)
        val digits = query.filter(Char::isDigit)
        return all.mapNotNull { contact ->
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
        }.sortedWith(compareBy({ it.second }, { !it.first.starred }))
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

    private const val MAX_OPTIONS = 6
}

object Recipients {
    const val LAST_MISSED = "last_missed"
    const val LAST_CALL = "last_call"
    const val TO_HELP = "Contact name as the user says it (\"Lucía\", \"mamá\"), a phone number, or last_missed / last_call for the latest missed or any call."

    private suspend fun lastCall(context: Context, missedOnly: Boolean): Pair<String, String?>? = withContext(Dispatchers.IO) {
        val selection = if (missedOnly) "${CallLog.Calls.TYPE} = ${CallLog.Calls.MISSED_TYPE}" else null
        context.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME), selection, null, "${CallLog.Calls.DATE} DESC")?.use { c ->
            if (!c.moveToFirst()) return@use null
            val number = c.getString(0)?.takeIf { it.count(Char::isDigit) >= 3 } ?: return@use null
            number to c.getString(1)?.takeIf { it.isNotBlank() }
        }
    }

    suspend fun resolve(args: JsonObject, ctx: ToolContext): Resolution {
        val to = args.string("to")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return Resolution.Failed(ToolResults.error("bad_arguments", "to is required."))
        if (to == LAST_MISSED || to == LAST_CALL) {
            if (!Contacts.granted(ctx.context, Manifest.permission.READ_CALL_LOG)) return Resolution.Failed(ToolResults.missingPermission(Manifest.permission.READ_CALL_LOG))
            val (number, name) = lastCall(ctx.context, missedOnly = to == LAST_MISSED)
                ?: return Resolution.Failed(ToolResults.error("not_found", "No such call in the call log."))
            return Resolution.Ready(JsonObject(args + mapOf("number" to JsonPrimitive(number), "name" to JsonPrimitive(name ?: number))))
        }
        if (!Contacts.isNumber(to) && !Contacts.granted(ctx.context, Manifest.permission.READ_CONTACTS)) {
            return Resolution.Failed(ToolResults.missingPermission(Manifest.permission.READ_CONTACTS))
        }
        return when (val pick = Contacts.pick(ctx.context, to)) {
            is ContactPick.Found -> Resolution.Ready(
                JsonObject(
                    args + mapOf(
                        "number" to JsonPrimitive(pick.number), "name" to JsonPrimitive(pick.label ?: pick.name ?: pick.number),
                        "contact_name" to JsonPrimitive(pick.name ?: pick.number),
                    ),
                ),
            )
            is ContactPick.Ambiguous -> Resolution.Failed(
                ToolResults.error("ambiguous", "Several matches: ${pick.options.joinToString("; ")}. Ask the user which one, then pass the full name or the number."),
            )
            ContactPick.NotFound -> Resolution.Failed(ToolResults.error("not_found", "No contact matches \"$to\". Ask for the name as saved, or the number."))
        }
    }
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
    override val description = "Find contacts by name (accents and case ignored) or by the last digits of a number. Returns names, phone numbers, and emails and postal addresses when saved."
    override val parameters = schema {
        string("query", "Name, nickname or digits.", required = true)
        integer("limit", "Maximum results. Default 8.")
    }

    override fun summary(args: JsonObject) = args.string("query").orEmpty()

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject {
        val query = args.string("query")?.takeIf { it.isNotBlank() } ?: return ToolResults.error("bad_arguments", "query is required.")
        val found = Contacts.search(ctx.context.contentResolver, query, (args.int("limit") ?: 8).coerceIn(1, 30))
        val extra = withContext(Dispatchers.IO) { found.map { it.name }.distinct().associateWith { Contacts.details(ctx.context.contentResolver, it) } }
        return ToolResults.ok(
            "count" to found.size,
            "contacts" to found.map {
                mapOf("name" to it.name, "number" to it.number, "label" to it.type.takeIf { _ -> !it.mobile }, "favorite" to it.starred.takeIf { s -> s })
                    .plus(extra[it.name].orEmpty())
                    .filterValues { v -> v != null }
            },
        )
    }
}

class CallTool : Tool {
    override val name = "call"
    override val category = ToolCategory.COMMUNICATION
    override val requiresConfirmation = true
    override val permissions = listOf(Manifest.permission.CALL_PHONE)
    override val description =
        "Place a phone call to a contact or a number. Contact names resolve on the phone; if several match, the result lists them. " +
            "to nearest:NAME calls the closest place with that name or kind (\"nearest:farmacia\"), using its listed phone."
    override val parameters = schema {
        string("to", Recipients.TO_HELP + " Or nearest:NAME for the closest such place.", required = true)
    }

    override fun summary(args: JsonObject) = args.string("name") ?: args.string("to").orEmpty()

    override suspend fun resolve(args: JsonObject, ctx: ToolContext): Resolution {
        val place = args.string("to")?.trim()?.takeIf { it.startsWith(NEAREST, ignoreCase = true) }?.drop(NEAREST.length)?.trim()
            ?: return Recipients.resolve(args, ctx)
        if (place.isEmpty()) return Resolution.Failed(ToolResults.error("bad_arguments", "Give nearest:NAME, e.g. nearest:farmacia."))
        val found = finder.execute(JsonObject(mapOf("query" to JsonPrimitive(place), "limit" to JsonPrimitive(PLACE_CANDIDATES))), ctx)
        if (found.isError()) return Resolution.Failed(found)
        val withPhone = found["places"].arr()?.mapNotNull { it.obj() }?.firstOrNull { !it.string("phone").isNullOrBlank() }
            ?: return Resolution.Failed(ToolResults.error("no_phone", "No nearby \"$place\" lists a phone number. Tell the user, or offer navigate."))
        val label = listOfNotNull(withPhone.string("name"), withPhone.string("address")).joinToString(", ")
        return Resolution.Ready(JsonObject(args + mapOf("number" to JsonPrimitive(withPhone.string("phone")), "name" to JsonPrimitive(label))))
    }

    override fun confirmationPrompt(args: JsonObject, res: Resources): String = res.getString(R.string.confirm_call, summary(args))

    override fun endsTurn(args: JsonObject, result: JsonObject) = result.isDone()

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject {
        val number = args.string("number")?.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }?.takeIf { it.isNotEmpty() }
            ?: return ToolResults.error("bad_arguments", "number is required.")
        val intent = Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null))
        ctx.playback.stayPaused()
        return when (ctx.launcher.launch(intent, "call", unlock = false)) {
            LaunchStatus.FAILED -> ToolResults.error("call_failed", "The call could not start.")
            else -> ToolResults.ok("status" to "calling", "to" to args.string("name"), "number" to number)
        }
    }

    override fun silencesTurn(args: JsonObject, result: JsonObject) = !result.isError()

    private val finder = FindPlacesTool()

    private companion object {
        const val NEAREST = "nearest:"
        const val PLACE_CANDIDATES = 5
    }
}

class SendSmsTool : Tool {
    override val name = "send_sms"
    override val category = ToolCategory.COMMUNICATION
    override val requiresConfirmation = true
    override val permissions = listOf(Manifest.permission.SEND_SMS)
    override val description = "Send an SMS directly, without opening an app. Only when the user says SMS; otherwise use send_message. Contact names resolve on the phone; if several match, the result lists them."
    override val parameters = schema {
        string("to", Recipients.TO_HELP, required = true)
        string("text", "Message text.", required = true)
    }

    override fun summary(args: JsonObject) = args.string("name") ?: args.string("to").orEmpty()

    override suspend fun resolve(args: JsonObject, ctx: ToolContext) = Recipients.resolve(args, ctx)

    override fun confirmationPrompt(args: JsonObject, res: Resources): String =
        res.getString(R.string.confirm_sms, args.string("text").orEmpty(), summary(args))

    override fun endsTurn(args: JsonObject, result: JsonObject) = result.isDone()

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject {
        val number = args.string("number")?.filter { it.isDigit() || it == '+' }?.takeIf { it.isNotEmpty() }
            ?: return ToolResults.error("bad_arguments", "number is required.")
        val text = args.string("text")?.takeIf { it.isNotBlank() } ?: return ToolResults.error("bad_arguments", "text is required.")
        val sms = smsManager(ctx.context) ?: return ToolResults.error("no_sms", "This device cannot send SMS.")
        sms.sendMultipartTextMessage(number, null, sms.divideMessage(text), null, null)
        return ToolResults.ok("status" to "sent", "to" to args.string("name"), "number" to number)
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
                    "contact" to (name ?: address),
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
        string("kind", "Only this kind of call. Default all.", enum = listOf("all", "missed", "incoming", "outgoing", "rejected"))
        string("contact", "Contact name or number.")
        integer("limit", "Maximum results. Default 10.")
    }

    override fun summary(args: JsonObject) = listOfNotNull(args.firstString("kind", "type")?.takeIf { it != "all" }, args.string("contact")).joinToString(" · ")

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject = withContext(Dispatchers.IO) {
        val filter = ContactFilter.of(ctx.context, args.string("contact"))
        val type = args.firstString("kind", "type")?.takeIf { it != "all" }
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

    override fun endsTurn(args: JsonObject, result: JsonObject) = result.isDone()

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

    override fun endsTurn(args: JsonObject, result: JsonObject) = result.isDone()

    override suspend fun resolve(args: JsonObject, ctx: ToolContext): Resolution = withContext(Dispatchers.IO) {
        val query = args.string("contact")?.trim()?.takeIf { it.isNotEmpty() } ?: return@withContext Resolution.Failed(ToolResults.error("bad_arguments", "contact is required."))
        if (args.string("number").isNullOrBlank() && args.string("email").isNullOrBlank()) {
            return@withContext Resolution.Failed(ToolResults.error("bad_arguments", "number or email is required."))
        }
        if (!Contacts.granted(ctx.context, Manifest.permission.READ_CONTACTS)) return@withContext Resolution.Failed(ToolResults.missingPermission(Manifest.permission.READ_CONTACTS))
        val found = Contacts.byName(ctx.context.contentResolver, query)
        when {
            found.isEmpty() -> Resolution.Failed(ToolResults.error("not_found", "No contact matches \"$query\"."))
            found.size > 1 -> Resolution.Failed(ToolResults.error("ambiguous", "Several contacts match: ${found.take(8).joinToString { it.second }}. Use the full name."))
            else -> Resolution.Ready(JsonObject(args + mapOf("contact" to JsonPrimitive(found.first().second), "contact_id" to JsonPrimitive(found.first().first))))
        }
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject = withContext(Dispatchers.IO) {
        val number = args.string("number")?.trim()?.takeIf { it.isNotEmpty() }
        val email = args.string("email")?.trim()?.takeIf { it.isNotEmpty() }
        val contact = (args["contact_id"]?.toString()?.toLongOrNull() ?: return@withContext ToolResults.error("bad_arguments", "contact is required.")) to args.string("contact").orEmpty()
        val resolver = ctx.context.contentResolver
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
