package dev.marionette.tools

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dev.marionette.core.int
import dev.marionette.core.string
import kotlinx.serialization.json.JsonObject
import java.text.Normalizer

data class InstalledApp(val label: String, val packageName: String)

object InstalledApps {
    fun list(pm: PackageManager): List<InstalledApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val infos: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        return infos.map { InstalledApp(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    fun fold(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9]+"), " ").trim()

    fun match(apps: List<InstalledApp>, query: String): List<InstalledApp> {
        val q = fold(query)
        if (q.isEmpty()) return emptyList()
        apps.firstOrNull { it.packageName == query.trim() }?.let { return listOf(it) }
        val scored = apps.mapNotNull { app ->
            val label = fold(app.label)
            val score = when {
                label == q -> 0
                label.startsWith(q) -> 1
                label.split(' ').any { it.startsWith(q) } -> 2
                q in label -> 3
                q in app.packageName.lowercase() -> 4
                q.split(' ').all { it in app.packageName.lowercase().split('.') } -> 5
                else -> null
            }
            score?.let { app to it }
        }
        return scored.sortedWith(compareBy({ it.second }, { it.first.label.length })).map { it.first }
    }
}

class ListAppsTool : Tool {
    override val name = "list_apps"
    override val readOnly = true
    override val category = ToolCategory.APPS
    override val description = "List apps installed on the phone (label and package). Filter with query to keep the result short."
    override val parameters = schema {
        string("query", "Part of the app name or package. Omit to list all apps.")
        integer("limit", "Maximum results. Default 50.")
    }

    override fun summary(args: JsonObject) = args.string("query").orEmpty()

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject {
        val apps = InstalledApps.list(ctx.context.packageManager)
        val query = args.string("query")?.takeIf { it.isNotBlank() }
        val found = if (query == null) apps else InstalledApps.match(apps, query)
        val limit = (args.int("limit") ?: 50).coerceIn(1, 300)
        return ToolResults.ok("count" to found.size, "apps" to found.take(limit).map { mapOf("label" to it.label, "package" to it.packageName) })
    }
}

class OpenAppTool : Tool {
    override val name = "open_app"
    override val category = ToolCategory.APPS
    override val description = "Open an installed app by name or package. If the phone is locked, the app opens after the user unlocks."
    override val parameters = schema {
        string("app", "App name as the user says it (\"WhatsApp\", \"Spotify\") or a package name.", required = true)
    }

    override fun summary(args: JsonObject) = args.string("app").orEmpty()

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject {
        val query = args.string("app") ?: return ToolResults.error("bad_arguments", "app is required.")
        val pm = ctx.context.packageManager
        val matches = InstalledApps.match(InstalledApps.list(pm), query)
        val app = matches.firstOrNull() ?: return ToolResults.error("not_installed", "No installed app matches \"$query\".")
        val intent = pm.getLaunchIntentForPackage(app.packageName) ?: return ToolResults.error("not_launchable", "${app.label} has no launcher screen.")
        return launchResult(ctx.launcher.launch(intent, app.label), app.label, "app" to app.label, "package" to app.packageName)
    }
}

class OpenUrlTool : Tool {
    override val name = "open_url"
    override val category = ToolCategory.APPS
    override val description =
        "Open a URL or deep link (https, tel, mailto, geo, app schemes like spotify: or whatsapp://send?phone=...&text=...). " +
            "Set package to force a specific app."
    override val parameters = schema {
        string("url", "The URL or URI.", required = true)
        string("package", "Optional package name of the app that must handle it.")
    }

    override fun summary(args: JsonObject) = args.string("url")?.take(60).orEmpty()

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject {
        val url = args.string("url")?.trim()?.takeIf { it.isNotEmpty() } ?: return ToolResults.error("bad_arguments", "url is required.")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        args.string("package")?.takeIf { it.isNotBlank() }?.let { intent.setPackage(it) }
        if (intent.resolveActivity(ctx.context.packageManager) == null) return ToolResults.error("no_handler", "No app can open $url.")
        return launchResult(ctx.launcher.launch(intent, url), "The link")
    }
}

class ShareTextTool : Tool {
    override val name = "share_text"
    override val category = ToolCategory.APPS
    override val description =
        "Open an app's share screen with text prefilled, for example to draft a message in WhatsApp or Telegram. " +
            "The user, or ui_act, still has to pick the chat and press send."
    override val parameters = schema {
        string("text", "Text to share.", required = true)
        string("app", "App name or package to share into. Omit to show the system share sheet.")
    }

    override fun summary(args: JsonObject) = listOfNotNull(args.string("app"), args.string("text")?.take(40)).joinToString(" · ")

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject {
        val text = args.string("text") ?: return ToolResults.error("bad_arguments", "text is required.")
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
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

class OpenSettingsTool : Tool {
    override val name = "open_settings"
    override val category = ToolCategory.DEVICE
    override val description =
        "Open an Android settings screen or quick panel. Use for things apps cannot change directly, such as Wi-Fi, mobile data, " +
            "Bluetooth pairing or screen brightness. 'app' opens the settings page of one app."
    override val parameters = schema {
        string("page", "Settings page.", required = true, enum = PAGES.keys.toList() + "app")
        string("app", "App name or package, for page = app.")
    }

    override fun summary(args: JsonObject) = args.string("page")?.replace('_', ' ').orEmpty()

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject {
        val page = args.string("page") ?: return ToolResults.error("bad_arguments", "page is required.")
        val intent = if (page == "app") {
            val query = args.string("app") ?: return ToolResults.error("bad_arguments", "app is required for page = app.")
            val app = InstalledApps.match(InstalledApps.list(ctx.context.packageManager), query).firstOrNull()
                ?: return ToolResults.error("not_installed", "No installed app matches \"$query\".")
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}"))
        } else {
            Intent(PAGES[page] ?: return ToolResults.error("bad_arguments", "Unknown page $page."))
        }
        return launchResult(ctx.launcher.launch(intent, "settings $page"), "The settings screen")
    }

    companion object {
        val PAGES = linkedMapOf(
            "main" to Settings.ACTION_SETTINGS,
            "internet_panel" to Settings.Panel.ACTION_INTERNET_CONNECTIVITY,
            "wifi" to Settings.ACTION_WIFI_SETTINGS,
            "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
            "mobile_data" to Settings.ACTION_DATA_ROAMING_SETTINGS,
            "volume_panel" to Settings.Panel.ACTION_VOLUME,
            "sound" to Settings.ACTION_SOUND_SETTINGS,
            "display" to Settings.ACTION_DISPLAY_SETTINGS,
            "battery" to Intent.ACTION_POWER_USAGE_SUMMARY,
            "battery_saver" to Settings.ACTION_BATTERY_SAVER_SETTINGS,
            "location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
            "notifications" to Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS,
            "do_not_disturb" to Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS,
            "nfc" to Settings.ACTION_NFC_SETTINGS,
            "airplane_mode" to Settings.ACTION_AIRPLANE_MODE_SETTINGS,
            "hotspot" to "android.settings.TETHER_SETTINGS",
            "date_time" to Settings.ACTION_DATE_SETTINGS,
            "accessibility" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
            "security" to Settings.ACTION_SECURITY_SETTINGS,
            "apps" to Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS,
            "storage" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
            "cast" to Settings.ACTION_CAST_SETTINGS,
        )
    }
}
