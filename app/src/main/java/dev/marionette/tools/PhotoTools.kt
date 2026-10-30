package dev.marionette.tools

import android.Manifest
import android.content.ClipData
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.media.ExifInterface
import android.os.Build
import android.provider.MediaStore
import dev.marionette.R
import dev.marionette.core.boolean
import dev.marionette.core.int
import dev.marionette.core.string
import dev.marionette.launch.LaunchStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object Photos {
    val permission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    fun isScreenshot(path: String?, album: String?): Boolean =
        listOfNotNull(path, album).any { it.contains("screenshot", ignoreCase = true) }

    fun size(bytes: Long): String = when {
        bytes >= 1_048_576 -> "%.1f MB".format(java.util.Locale.ROOT, bytes / 1_048_576.0)
        else -> "${(bytes + 1023) / 1024} KB"
    }

    fun location(context: Context, id: Long): String? {
        if (!Contacts.granted(context, Manifest.permission.ACCESS_MEDIA_LOCATION)) return null
        val uri = MediaStore.setRequireOriginal(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id))
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val latLong = FloatArray(2)
                if (ExifInterface(stream).getLatLong(latLong)) "%.5f,%.5f".format(java.util.Locale.ROOT, latLong[0], latLong[1]) else null
            }
        }.getOrNull()
    }
}

class ListPhotosTool : Tool {
    override val name = "list_photos"
    override val readOnly = true
    override val category = ToolCategory.MEDIA
    override val permissions get() = listOf(Photos.permission)
    override val description =
        "List the latest photos and screenshots on the phone, newest first, with id, time, album and size; count is the total that matches. " +
            "Use the id with share_photo. with_location adds the GPS position saved in the photo."
    override val parameters = schema {
        string("kind", "Which images. Default any.", enum = listOf("any", "photo", "screenshot"))
        string("since", "Only images from this local date or time on, yyyy-MM-dd or yyyy-MM-ddTHH:mm.")
        string("until", "Only images before this local date or time; a date includes that whole day.")
        boolean("with_location", "Add the GPS position of each photo when it has one.")
        integer("limit", "Maximum results. Default 10.")
    }

    override fun summary(args: JsonObject) = args.string("kind")?.takeIf { it != "any" && it != "all" }.orEmpty()

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject = withContext(Dispatchers.IO) {
        val kind = when (args.string("kind")) {
            "photo", "photos" -> "photos"
            "screenshot", "screenshots" -> "screenshots"
            else -> "all"
        }
        val since = LocalTimes.parse(args.string("since"))?.let { LocalTimes.millis(it) }
        val withLocation = args.boolean("with_location") == true
        val limit = (args.int("limit") ?: 10).coerceIn(1, 30)
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
        )
        val until = LocalTimes.parse(args.string("until"))?.let { if (LocalTimes.isDateOnly(args.string("until"))) it.plusDays(1) else it }?.let { LocalTimes.millis(it) }
        val clauses = listOfNotNull(since?.let { "${MediaStore.Images.Media.DATE_ADDED} >= ?" }, until?.let { "${MediaStore.Images.Media.DATE_ADDED} < ?" })
        val selection = clauses.joinToString(" AND ").ifEmpty { null }
        val selectionArgs = listOfNotNull(since, until).map { (it / 1000).toString() }.toTypedArray().takeIf { it.isNotEmpty() }
        var total = 0
        val photos = mutableListOf<Map<String, Any?>>()
        ctx.context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs, "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c ->
            var scanned = 0
            while (c.moveToNext() && scanned++ < MAX_SCAN) {
                val album = c.getString(7)
                val screenshot = Photos.isScreenshot(c.getString(8), album)
                if ((kind == "photos" && screenshot) || (kind == "screenshots" && !screenshot)) continue
                total++
                if (photos.size >= limit) continue
                val id = c.getLong(0)
                val taken = c.getLong(2).takeIf { it > 0 } ?: (c.getLong(3) * 1000)
                photos += mapOf(
                    "id" to id,
                    "time" to LocalTimes.format(taken, false),
                    "screenshot" to screenshot.takeIf { it },
                    "location" to if (withLocation) Photos.location(ctx.context, id) else null,
                ).filterValues { it != null }
            }
        }
        ToolResults.ok("count" to total, "listed" to photos.size, "photos" to photos)
    }

    companion object {
        private const val MAX_SCAN = 500
    }
}

class SharePhotoTool : Tool {
    override val name = "share_photo"
    override val category = ToolCategory.MEDIA
    override val permissions get() = listOf(Photos.permission)
    override val description =
        "Share a photo or screenshot: by id from list_photos, or the newest one with latest (no list_photos needed). " +
            "With app WhatsApp and to (a contact), it opens that chat with the photo and presses Send. " +
            "Otherwise it opens the app's share screen and the user, or ui_act, picks the chat and presses send."
    override val parameters = schema {
        integer("id", "Photo id from list_photos.")
        string("latest", "Share the newest image of this kind instead of an id.", enum = listOf("photo", "screenshot", "any"))
        string("app", "App name or package to share into. Omit to show the system share sheet.")
        string("to", "WhatsApp contact name or number: send straight to that chat.")
        string("text", "Optional text to send with the photo.")
    }

    override fun summary(args: JsonObject) = listOfNotNull(args.string("name") ?: args.string("to"), args.string("app")).joinToString(" · ")

    override fun needsConfirmation(args: JsonObject) = args.string("number") != null

    override fun confirmationPrompt(args: JsonObject, res: Resources): String =
        res.getString(R.string.confirm_photo, args.string("name").orEmpty(), args.string("app_label").orEmpty())

    override suspend fun resolve(args: JsonObject, ctx: ToolContext): Resolution {
        val to = args.string("to")?.trim()?.takeIf { it.isNotEmpty() } ?: return Resolution.Ready(args)
        val app = InstalledApps.match(InstalledApps.list(ctx.context.packageManager), args.string("app") ?: "WhatsApp").firstOrNull { it.packageName in WhatsApp.packages }
            ?: return Resolution.Failed(ToolResults.error("bad_arguments", "to works only with WhatsApp. For other apps omit to and pick the chat with ui_act."))
        val resolved = Recipients.resolve(args, ctx)
        if (resolved !is Resolution.Ready) return resolved
        return Resolution.Ready(JsonObject(resolved.args + mapOf("app" to JsonPrimitive(app.packageName), "app_label" to JsonPrimitive(app.label))))
    }

    override fun endsTurn(args: JsonObject, result: JsonObject) = result.isDone("attached")

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject {
        val id = args.int("id")?.toLong() ?: args.string("latest")?.let { newest(ctx, it) }
            ?: return ToolResults.error(if (args.containsKey("latest")) "not_found" else "bad_arguments", if (args.containsKey("latest")) "No matching image on the phone." else "Give id or latest.")
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        val mime = withContext(Dispatchers.IO) {
            ctx.context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media.MIME_TYPE), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) ?: "image/*" else null
            }
        } ?: return ToolResults.error("not_found", "No photo with id $id. Use list_photos.")
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(null, uri)
        args.string("text")?.takeIf { it.isNotBlank() }?.let { send.putExtra(Intent.EXTRA_TEXT, it) }
        val number = args.string("number")
        if (number != null) {
            val pkg = args.string("app").orEmpty()
            val phone = WhatsApp.phone(ctx, number) ?: return ToolResults.error("bad_number", "The number has no country code. Ask the user for it.")
            send.setPackage(pkg).putExtra("jid", "$phone@s.whatsapp.net")
            return when (val status = ctx.launcher.launch(send, "share")) {
                LaunchStatus.STARTED -> if (ScreenWaits.tap(ctx, pkg, WhatsApp.sendButton, WhatsApp.SEND_WAIT_MILLIS, chat = listOfNotNull(args.string("contact_name") ?: args.string("name"), phone))) {
                    ToolResults.ok("status" to "sent", "to" to args.string("name"), "photo_id" to id)
                } else {
                    ToolResults.ok("status" to "attached", "photo_id" to id, "message" to "The chat is open with the photo attached, but Send was not pressed. Use ui_act or ask the user.")
                }
                else -> launchResult(status, "The chat", "photo_id" to id)
            }
        }
        val appQuery = args.string("app")?.takeIf { it.isNotBlank() }
        val intent = if (appQuery != null) {
            val app = InstalledApps.match(InstalledApps.list(ctx.context.packageManager), appQuery).firstOrNull()
                ?: return ToolResults.error("not_installed", "No installed app matches \"$appQuery\".")
            send.setPackage(app.packageName)
        } else {
            Intent.createChooser(send, null)
        }
        return launchResult(ctx.launcher.launch(intent, "share"), "The share screen", "photo_id" to id)
    }

    private suspend fun newest(ctx: ToolContext, kind: String): Long? = withContext(Dispatchers.IO) {
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME, MediaStore.Images.Media.RELATIVE_PATH)
        ctx.context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
            var scanned = 0
            while (c.moveToNext() && scanned++ < MAX_SCAN) {
                val screenshot = Photos.isScreenshot(c.getString(2), c.getString(1))
                if (kind == "any" || (kind == "screenshot") == screenshot) return@withContext c.getLong(0)
            }
            null
        }
    }

    companion object {
        private const val MAX_SCAN = 500
    }
}
