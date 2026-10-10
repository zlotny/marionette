package dev.marionette.tools

import android.accessibilityservice.AccessibilityService
import dev.marionette.core.int
import dev.marionette.core.string
import dev.marionette.screen.MarionetteAccessibilityService
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

private fun screenGuard(ctx: ToolContext): Pair<MarionetteAccessibilityService?, JsonObject?> {
    val service = MarionetteAccessibilityService.instance
        ?: return null to ToolResults.error("missing_permission", "Screen control is off. Ask the user to enable the Marionette accessibility service in Marionette settings.")
    if (ctx.device.isLocked) return null to ToolResults.needsUnlock()
    val pkg = service.foregroundPackage()
    if (pkg != null && pkg in ctx.settings.uiAutomationBlocklist) {
        return null to ToolResults.error("blocked_app", "Screen control is disabled for this app ($pkg) for safety.")
    }
    return service to null
}

private fun describeScreen(service: MarionetteAccessibilityService, ctx: ToolContext, maxNodes: Int): JsonObject {
    val pkg = service.foregroundPackage()
    val pm = ctx.context.packageManager
    val label = pkg?.let { runCatching { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() }.getOrNull() }
    val nodes = service.snapshot(maxNodes)
    return ToolResults.ok(
        "app" to (label ?: pkg),
        "package" to pkg,
        "elements" to nodes.map { n ->
            buildList {
                add("${n.id}")
                add(n.role)
                n.text?.let { add("\"$it\"") }
                if (n.clickable) add("tap")
                if (n.editable) add("edit")
                if (n.scrollable) add("scroll")
                n.checked?.let { add(if (it) "on" else "off") }
            }.joinToString(" ")
        },
    )
}

class UiReadTool : Tool {
    override val name = "ui_read"
    override val category = ToolCategory.SCREEN
    override val description =
        "Read what is on the phone screen now: the foreground app and a numbered list of elements (\"id role \\\"text\\\" flags\"). " +
            "Use the ids with ui_act. Needs the phone unlocked."
    override val parameters = schema {
        integer("max_elements", "Maximum elements. Default 120.")
    }

    override fun summary(args: JsonObject) = ""

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject {
        val (service, error) = screenGuard(ctx)
        if (service == null) return error!!
        return describeScreen(service, ctx, (args.int("max_elements") ?: 120).coerceIn(10, 400))
    }
}

class UiActTool : Tool {
    override val name = "ui_act"
    override val category = ToolCategory.SCREEN
    override val description =
        "Operate the app on screen like a finger would: tap, long_tap, type text into a field, scroll, or global actions " +
            "(back, home, recents, notifications, quick_settings, lock). Element ids come from the latest ui_read. " +
            "Returns the new screen after the action. Needs the phone unlocked."
    override val parameters = schema {
        string(
            "action",
            "What to do.",
            required = true,
            enum = listOf("tap", "long_tap", "type", "scroll_down", "scroll_up", "back", "home", "recents", "notifications", "quick_settings", "lock"),
        )
        integer("id", "Element id from ui_read, for tap, long_tap, type and optionally scroll.")
        string("text", "Text to type, for type. Or visible text of the element to tap when you have no id.")
    }

    override fun summary(args: JsonObject) = listOfNotNull(args.string("action"), args.int("id")?.toString(), args.string("text")?.take(30)).joinToString(" ")

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject {
        val action = args.string("action") ?: return ToolResults.error("bad_arguments", "action is required.")
        if (action == "lock") {
            val service = MarionetteAccessibilityService.instance ?: return screenGuard(ctx).second!!
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            return ToolResults.ok("status" to "locked")
        }
        val (service, error) = screenGuard(ctx)
        if (service == null) return error!!
        val global = GLOBALS[action]
        val done = if (global != null) {
            service.performGlobalAction(global)
        } else {
            if (service.lastNodes.isEmpty()) service.snapshot(400)
            val id = args.int("id")
            val text = args.string("text")
            val target = when {
                id != null -> service.lastNodes.firstOrNull { it.id == id }
                action != "type" && text != null -> {
                    val folded = InstalledApps.fold(text)
                    service.snapshot(400).let { nodes ->
                        nodes.firstOrNull { InstalledApps.fold(it.text.orEmpty()) == folded } ?: nodes.firstOrNull { folded in InstalledApps.fold(it.text.orEmpty()) }
                    }
                }
                else -> null
            }
            when (action) {
                "tap", "long_tap" -> target?.let { service.click(it.node, long = action == "long_tap") }
                    ?: return ToolResults.error("not_found", "No such element. Call ui_read again.")
                "type" -> {
                    val node = target ?: service.lastNodes.firstOrNull { it.editable }
                        ?: return ToolResults.error("not_found", "No text field. Call ui_read again.")
                    service.type(node.node, text ?: return ToolResults.error("bad_arguments", "text is required for type."))
                }
                "scroll_down" -> service.scroll(target?.node, forward = true)
                "scroll_up" -> service.scroll(target?.node, forward = false)
                else -> return ToolResults.error("bad_arguments", "Unknown action.")
            }
        }
        if (!done) return ToolResults.error("action_failed", "The screen did not accept $action.")
        delay(SETTLE_MILLIS)
        val (after, blocked) = screenGuard(ctx)
        return if (after == null) blocked!! else describeScreen(after, ctx, 120)
    }

    companion object {
        private const val SETTLE_MILLIS = 700L
        private val GLOBALS = mapOf(
            "back" to AccessibilityService.GLOBAL_ACTION_BACK,
            "home" to AccessibilityService.GLOBAL_ACTION_HOME,
            "recents" to AccessibilityService.GLOBAL_ACTION_RECENTS,
            "notifications" to AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS,
            "quick_settings" to AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS,
        )
    }
}
