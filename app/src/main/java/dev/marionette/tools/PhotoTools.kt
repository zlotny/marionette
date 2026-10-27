package dev.marionette.tools

import android.Manifest
import android.content.ClipData
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.ExifInterface
import android.os.Build
import android.provider.MediaStore
import dev.marionette.core.boolean
import dev.marionette.core.int
import dev.marionette.core.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

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
        "List the latest photos and screenshots on the phone, newest first, with id, time, album and size. " +
            "Use the id with share_photo. with_location adds the GPS position saved in the photo."
    override val parameters = schema {
        string("kind", "Which images. Default all.", enum = listOf("all", "photos", "screenshots"))
        string("since", "Only images from this local date or time on, yyyy-MM-dd or yyyy-MM-ddTHH:mm.")
        boolean("with_location", "Add the GPS position of each photo when it has one.")
        integer("limit", "Maximum results. Default 10.")
    }

    override fun summary(args: JsonObject) = args.string("kind")?.takeIf { it != "all" }.orEmpty()

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject = withContext(Dispatchers.IO) {
        val kind = args.string("kind") ?: "all"
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
        val selection = since?.let { "${MediaStore.Images.Media.DATE_ADDED} >= ?" }
        val selectionArgs = since?.let { arrayOf((it / 1000).toString()) }
        val photos = mutableListOf<Map<String, Any?>>()
        ctx.context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs, "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c ->
            var scanned = 0
            while (c.moveToNext() && photos.size < limit && scanned++ < MAX_SCAN) {
                val album = c.getString(7)
                val screenshot = Photos.isScreenshot(c.getString(8), album)
                if ((kind == "photos" && screenshot) || (kind == "screenshots" && !screenshot)) continue
                val id = c.getLong(0)
                val taken = c.getLong(2).takeIf { it > 0 } ?: (c.getLong(3) * 1000)
                photos += mapOf(
                    "id" to id,
                    "name" to c.getString(1),
                    "time" to LocalTimes.format(taken, false),
                    "album" to album,
                    "screenshot" to screenshot,
                    "size" to Photos.size(c.getLong(4)),
                    "pixels" to "${c.getInt(5)}x${c.getInt(6)}",
                    "location" to if (withLocation) Photos.location(ctx.context, id) else null,
                ).filterValues { it != null }
            }
        }
        ToolResults.ok("count" to photos.size, "photos" to photos)
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
        "Open an app's share screen with a photo from list_photos attached, for example WhatsApp or Gmail. " +
            "The user, or ui_act, still picks the chat and presses send."
    override val parameters = schema {
        integer("id", "Photo id from list_photos.", required = true)
        string("app", "App name or package to share into. Omit to show the system share sheet.")
        string("text", "Optional text to send with the photo.")
    }

    override fun summary(args: JsonObject) = args.string("app").orEmpty()

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject {
        val id = args.int("id")?.toLong() ?: return ToolResults.error("bad_arguments", "id is required.")
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
        val appQuery = args.string("app")?.takeIf { it.isNotBlank() }
        val intent = if (appQuery != null) {
            val app = InstalledApps.match(InstalledApps.list(ctx.context.packageManager), appQuery).firstOrNull()
                ?: return ToolResults.error("not_installed", "No installed app matches \"$appQuery\".")
            send.setPackage(app.packageName)
        } else {
            Intent.createChooser(send, null)
        }
        return launchResult(ctx.launcher.launch(intent, "share"), "The share screen")
    }
}
