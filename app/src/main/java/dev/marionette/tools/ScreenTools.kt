package dev.marionette.tools

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import androidx.annotation.RequiresApi
import dev.marionette.assist.AssistantSession
import dev.marionette.core.arr
import dev.marionette.core.boolean
import dev.marionette.core.int
import dev.marionette.core.obj
import dev.marionette.core.string
import dev.marionette.screen.MarionetteAccessibilityService
import dev.marionette.screen.ScreenNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

private const val KEYGUARD_SETTLE_MILLIS = 800L
private const val OVERLAY_SETTLE_MILLIS = 400L
private const val GUARD_POLL_MILLIS = 50L
private val KEYGUARD_PACKAGES = setOf("com.android.systemui")
private const val EMPTY_RETRIES = 6
private const val EMPTY_RETRY_MILLIS = 250L

private suspend fun screenGuard(ctx: ToolContext): Pair<MarionetteAccessibilityService?, JsonObject?> {
    val service = MarionetteAccessibilityService.instance
        ?: return null to ToolResults.error("missing_permission", "Screen control is off. Ask the user to enable the Marionette accessibility service in Marionette settings.")
    if (AssistantSession.hideShown()) {
        delay(GUARD_POLL_MILLIS)
        withTimeoutOrNull(OVERLAY_SETTLE_MILLIS) { while (service.foregroundPackage() == ctx.context.packageName) delay(GUARD_POLL_MILLIS) }
    }
    val keyguardShowing = ctx.device.isKeyguardShowing
    if (ctx.device.isLocked && ctx.device.isScreenOn) return null to ToolResults.needsUnlock()
    if (!ctx.launcher.dismissKeyguard()) return null to ToolResults.needsUnlock()
    if (keyguardShowing) {
        ctx.dismissedKeyguard = true
        delay(GUARD_POLL_MILLIS)
        withTimeoutOrNull(KEYGUARD_SETTLE_MILLIS) { while (service.foregroundPackage() in KEYGUARD_PACKAGES || service.rootInActiveWindow == null) delay(GUARD_POLL_MILLIS) }
    }
    val pkg = service.foregroundPackage()
    if (pkg != null && pkg in ctx.blockedPackages()) {
        return null to ToolResults.error("blocked_app", "Screen control is disabled for this app ($pkg) for safety.")
    }
    return service to null
}

private val ALWAYS_BLOCKED = setOf(
    "com.google.android.permissioncontroller",
    "com.android.permissioncontroller",
    "com.google.android.packageinstaller",
    "com.android.packageinstaller",
)

fun ToolContext.blockedPackages(): Set<String> = settings.uiAutomationBlocklist + ALWAYS_BLOCKED + context.packageName

private suspend fun describeScreen(service: MarionetteAccessibilityService, ctx: ToolContext, maxNodes: Int): JsonObject {
    var nodes = service.snapshot(maxNodes, ctx.blockedPackages())
    repeat(EMPTY_RETRIES) {
        if (nodes.isNotEmpty()) return@repeat
        delay(EMPTY_RETRY_MILLIS)
        nodes = service.snapshot(maxNodes, ctx.blockedPackages())
    }
    val pkg = service.lastPackage
    val pm = ctx.context.packageManager
    val label = pkg?.let { runCatching { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() }.getOrNull() }
    if (nodes.isEmpty()) {
        return ToolResults.ok(
            "app" to (label ?: pkg),
            "package" to pkg,
            "elements" to emptyList<String>(),
            "note" to "The screen exposes no readable elements right now (loading, animating, or drawn without accessibility data). Screen control still works: call ui_read again, or use back.",
        )
    }
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
    override val readOnly = true
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
            "(back, home, recents, notifications, quick_settings, lock). Target an element by id from the latest ui_read, or by its visible text (waits up to 3 s for it to show, so it works right after opening an app). " +
            "steps runs several actions in order in one call and stops at the first that fails (for example type a search, then tap a result). " +
            "Ids are valid only until the screen changes, so target later steps by text. Returns the screen after the last action, also when an action fails. Needs the phone unlocked."
    override val parameters = schema {
        string("action", "What to do, for a single action.", enum = ACTIONS)
        integer("id", "Element id from ui_read, for tap, long_tap, type and optionally scroll.")
        string("text", "Text to type, for type. For tap and long_tap: visible text of the element when you have no id.")
        boolean("submit", "For type: press the keyboard action key afterwards (search, go). Chat apps add a new line instead: tap their Send button.")
        string("set", "For tap on a switch: the state wanted (on, off). The tap is skipped when the switch is already so.", enum = listOf("on", "off"))
        string("wait_for", "Text that must show after the action (or the last step), e.g. a result or the next screen's title. Waits up to 6 s for it. With scroll, keeps scrolling up to 8 pages until it shows.")
        objectArray("steps", "Several actions in order, instead of action.") {
            string("action", "What to do.", required = true, enum = ACTIONS)
            integer("id", "Element id, only for elements on the screen before the steps.")
            string("text", "Text to type, or visible text of the element to tap.")
            boolean("submit", "For type: press the keyboard action key afterwards.")
            string("set", "For tap on a switch: the state wanted. The tap is skipped when the switch is already so.", enum = listOf("on", "off"))
            string("wait_for", "Text that must show before the next step.")
        }
    }

    override fun summary(args: JsonObject): String {
        val steps = args["steps"].arr()
        if (steps != null) return steps.mapNotNull { it.obj()?.string("action") }.joinToString(", ")
        return listOfNotNull(args.string("action"), args.int("id")?.toString(), args.string("text")?.take(30)).joinToString(" ")
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject {
        val steps = args["steps"].arr()?.mapNotNull { it.obj() } ?: listOf(args)
        if (steps.isEmpty() || steps.any { it.string("action") == null }) return ToolResults.error("bad_arguments", "action is required in every step.")
        if (steps.size == 1 && steps[0].string("action") == "lock") {
            if (MarionetteAccessibilityService.instance == null) return screenGuard(ctx).second!!
            MarionetteAccessibilityService.lockScreen()
            return ToolResults.ok("status" to "locked")
        }
        val (service, error) = screenGuard(ctx)
        if (service == null) return error!!
        steps.forEachIndexed { index, step ->
            val before = signature(service, ctx)
            val waitFor = (step.string("wait_for") ?: if (index == steps.lastIndex) args.string("wait_for") else null)?.let(InstalledApps::fold)
            val scrolls = if (waitFor != null && step.string("action")!!.startsWith("scroll")) MAX_SCROLLS else 1
            var failure: JsonObject? = null
            for (attempt in 1..scrolls) {
                failure = perform(service, step, ctx)
                if (failure != null) break
                settle(service, ctx, before, if (scrolls > 1) null else waitFor)
                if (waitFor == null || waitFor in InstalledApps.fold(signature(service, ctx))) break
                if (attempt == scrolls) failure = ToolResults.error("wait_failed", "\"${step.string("wait_for") ?: args.string("wait_for")}\" did not show.")
            }
            if (failure != null) {
                val screen = describeScreen(service, ctx, 120)
                val progress = if (steps.size == 1) emptyMap() else mapOf("failed_step" to JsonPrimitive(index + 1), "done_steps" to JsonPrimitive(index))
                return JsonObject(failure + progress + screen.filterKeys { it in setOf("app", "elements", "note") })
            }
        }
        val (after, blocked) = screenGuard(ctx)
        return if (after == null) blocked!! else describeScreen(after, ctx, 120)
    }

    private suspend fun perform(service: MarionetteAccessibilityService, step: JsonObject, ctx: ToolContext): JsonObject? {
        val action = step.string("action")!!
        if (action == "lock") {
            MarionetteAccessibilityService.lockScreen()
            return null
        }
        GLOBALS[action]?.let { global ->
            return if (service.performGlobalAction(global)) null else ToolResults.error("action_failed", "The screen did not accept $action.")
        }
        if (service.lastNodes.isEmpty()) service.snapshot(400, ctx.blockedPackages())
        val id = step.int("id")
        val text = step.string("text")
        val target = when {
            id != null -> service.lastNodes.firstOrNull { it.id == id }?.takeIf { node ->
                node.node.refresh() && (node.text == null || node.node.text?.toString() == node.text.substringBefore(" | ") || node.node.contentDescription?.toString()?.let { it in node.text } == true)
            } ?: return ToolResults.error("not_found", "Element $id is gone or changed. Target it by text, or call ui_read again.")
            action != "type" && text != null -> findByText(service, ctx, text)
                ?: withTimeoutOrNull(TEXT_WAIT_MILLIS) {
                    var found: ScreenNode? = null
                    while (found == null) {
                        delay(POLL_MILLIS)
                        found = findByText(service, ctx, text)
                    }
                    found
                }
            else -> null
        }
        val wanted = step.string("set")?.let { it == "on" }
        if (wanted != null && target?.checked == wanted) return null
        val done = when (action) {
            "tap", "long_tap" -> target?.let { service.click(it.node, long = action == "long_tap") }
                ?: return ToolResults.error("not_found", "No element ${id ?: "\"$text\""}. Call ui_read again.")
            "type" -> {
                val node = target ?: service.snapshot(400, ctx.blockedPackages()).let { nodes -> nodes.firstOrNull { it.node.isFocused && it.editable } ?: nodes.firstOrNull { it.editable } }
                    ?: return ToolResults.error("not_found", "No text field. Call ui_read again.")
                service.type(node.node, text ?: return ToolResults.error("bad_arguments", "text is required for type.")) &&
                    (step.boolean("submit") != true || service.submit(node.node))
            }
            "scroll_down" -> service.scroll(target?.node, forward = true)
            "scroll_up" -> service.scroll(target?.node, forward = false)
            else -> return ToolResults.error("bad_arguments", "Unknown action $action.")
        }
        return if (done) null else ToolResults.error("action_failed", "The screen did not accept $action.")
    }

    private fun signature(service: MarionetteAccessibilityService, ctx: ToolContext): String =
        service.snapshot(400, ctx.blockedPackages(), remember = false).joinToString("|") { "${it.role}${it.text}${it.checked}" }

    private suspend fun settle(service: MarionetteAccessibilityService, ctx: ToolContext, before: String, waitFor: String?) {
        delay(MIN_SETTLE_MILLIS)
        withTimeoutOrNull(if (waitFor != null) WAIT_FOR_MILLIS else MAX_SETTLE_MILLIS) {
            var last = ""
            var changed = false
            while (true) {
                val now = signature(service, ctx)
                if (waitFor != null) {
                    if (waitFor in InstalledApps.fold(now)) return@withTimeoutOrNull
                } else {
                    if (now != before) changed = true
                    if (changed && now == last) return@withTimeoutOrNull
                }
                last = now
                delay(POLL_MILLIS)
            }
        }
    }

    private fun findByText(service: MarionetteAccessibilityService, ctx: ToolContext, text: String): ScreenNode? {
        val folded = InstalledApps.fold(text)
        val nodes = service.snapshot(400, ctx.blockedPackages())
        return nodes.firstOrNull { InstalledApps.fold(it.text.orEmpty()) == folded }
            ?: nodes.firstOrNull { node -> InstalledApps.fold(node.text.orEmpty()).split(" | ").any { it == folded } }
            ?: nodes.firstOrNull { folded in InstalledApps.fold(it.text.orEmpty()) }
    }

    companion object {
        private const val MAX_SCROLLS = 8
        private const val TEXT_WAIT_MILLIS = 3_000L
        private const val MIN_SETTLE_MILLIS = 150L
        private const val MAX_SETTLE_MILLIS = 2_000L
        private const val WAIT_FOR_MILLIS = 6_000L
        private const val POLL_MILLIS = 150L
        private val ACTIONS = listOf("tap", "long_tap", "type", "scroll_down", "scroll_up", "back", "home", "recents", "notifications", "quick_settings", "lock")
        private val GLOBALS = mapOf(
            "back" to AccessibilityService.GLOBAL_ACTION_BACK,
            "home" to AccessibilityService.GLOBAL_ACTION_HOME,
            "recents" to AccessibilityService.GLOBAL_ACTION_RECENTS,
            "notifications" to AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS,
            "quick_settings" to AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS,
        )
    }
}

data class ScreenMatch(val ids: Set<String> = emptySet(), val texts: Set<String> = emptySet()) {
    fun matches(node: ScreenNode): Boolean {
        val id = node.node.viewIdResourceName?.substringAfter(":id/")
        if (id != null && id in ids) return true
        val parts = InstalledApps.fold(node.text.orEmpty()).split(" | ")
        return node.clickable && parts.any { it in texts }
    }
}

object ScreenWaits {
    suspend fun tap(ctx: ToolContext, pkg: String, match: ScreenMatch, timeoutMillis: Long, typed: String? = null, chat: List<String> = emptyList()): Boolean {
        val draft = typed?.let { InstalledApps.fold(it).take(DRAFT_CHARS) }
        val names = chat.map(InstalledApps::fold).filter { it.isNotBlank() }
        val digits = chat.map { it.filter(Char::isDigit).takeLast(NUMBER_DIGITS) }.filter { it.length == NUMBER_DIGITS }
        val service = MarionetteAccessibilityService.instance ?: return false
        val found = withTimeoutOrNull(timeoutMillis) {
            var hit: ScreenNode? = null
            while (hit == null) {
                val nodes = service.snapshot(400, ctx.blockedPackages())
                val typedReady = draft == null || nodes.any { it.editable && InstalledApps.fold(it.text.orEmpty()).startsWith(draft) }
                val chatShown = chat.isEmpty() || nodes.any { node ->
                    !node.editable && node.text.orEmpty().let { text ->
                        InstalledApps.fold(text).let { folded -> names.any { it == folded } } || text.filter(Char::isDigit).let { d -> digits.any { d.endsWith(it) } }
                    }
                }
                val ready = typedReady && chatShown
                hit = if (service.lastPackage == pkg && ready) nodes.firstOrNull(match::matches) else null
                if (hit == null) delay(POLL_MILLIS)
            }
            hit
        } ?: return false
        return service.click(found.node)
    }

    suspend fun readAfterLaunch(ctx: ToolContext, timeoutMillis: Long): JsonObject? {
        val service = MarionetteAccessibilityService.instance ?: return null
        val before = service.foregroundPackage()
        withTimeoutOrNull(timeoutMillis) { while (service.foregroundPackage() == before) delay(POLL_MILLIS) }
        return service.foregroundPackage()?.let { readWhenShown(ctx, it, timeoutMillis) }
    }

    suspend fun readWhenShown(ctx: ToolContext, pkg: String, timeoutMillis: Long): JsonObject? {
        val service = MarionetteAccessibilityService.instance ?: return null
        withTimeoutOrNull(timeoutMillis) {
            while (service.foregroundPackage() != pkg) delay(POLL_MILLIS)
        }
        withTimeoutOrNull(APP_SETTLE_MILLIS) {
            var last = ""
            while (true) {
                val now = service.snapshot(400, ctx.blockedPackages()).joinToString("|") { "${it.role}${it.text}" }
                if (now.isNotEmpty() && now == last) break
                last = now
                delay(POLL_MILLIS)
            }
        }
        return describeScreen(service, ctx, 120)
    }

    private const val POLL_MILLIS = 250L
    private const val APP_SETTLE_MILLIS = 600L
    private const val DRAFT_CHARS = 20
    private const val NUMBER_DIGITS = 9
}

object ScreenImages {
    fun fit(width: Int, height: Int, maxSide: Int): Pair<Int, Int> {
        val longest = max(width, height)
        if (longest <= maxSide) return width to height
        val scale = maxSide.toFloat() / longest
        return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
    }

    fun jpegBase64(bitmap: Bitmap, maxSide: Int, quality: Int): String {
        val (w, h) = fit(bitmap.width, bitmap.height, maxSide)
        val scaled = if (w == bitmap.width && h == bitmap.height) bitmap else Bitmap.createScaledBitmap(bitmap, w, h, true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (scaled !== bitmap) scaled.recycle()
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }
}

class ScreenCaptureTool : Tool {
    override val name = "screen_capture"
    override val readOnly = true
    override val category = ToolCategory.SCREEN
    override val description =
        "Take a screenshot of the phone screen and see it as an image. Use it when ui_read text is not enough: " +
            "photos, maps, charts, games or a layout. Secure screens show black. Needs the phone unlocked. " +
            "It does not save a screenshot: for \"haz una captura\" say saving is not supported."
    override val parameters = schema {}

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return ToolResults.error("unsupported", "Screenshots need Android 11 or later.")
        val (service, error) = screenGuard(ctx)
        if (service == null) return error!!
        var shot = service.screenshot()
        if (shot.second == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
            delay(RETRY_MILLIS)
            shot = service.screenshot()
        }
        val bitmap = shot.first ?: return ToolResults.error("capture_failed", "The screen could not be captured (error ${shot.second}).")
        val (w, h) = ScreenImages.fit(bitmap.width, bitmap.height, MAX_SIDE)
        val image = withContext(Dispatchers.Default) {
            try {
                ScreenImages.jpegBase64(bitmap, MAX_SIDE, JPEG_QUALITY)
            } finally {
                bitmap.recycle()
            }
        }
        return ToolResults.ok(
            "package" to service.foregroundPackage(),
            "width" to w,
            "height" to h,
            "note" to "The screenshot is attached as an image.",
            ToolResults.IMAGE_JPEG to image,
        )
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun AccessibilityService.screenshot(): Pair<Bitmap?, Int> = suspendCancellableCoroutine { cont ->
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            Dispatchers.Default.asExecutor(),
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.let { hardware ->
                        hardware.copy(Bitmap.Config.ARGB_8888, false).also { hardware.recycle() }
                    }
                    buffer.close()
                    cont.resume(bitmap to 0) { _, value, _ -> value.first?.recycle() }
                }

                override fun onFailure(errorCode: Int) {
                    cont.resume(null to errorCode)
                }
            },
        )
    }

    companion object {
        private const val MAX_SIDE = 1024
        private const val JPEG_QUALITY = 70
        private const val RETRY_MILLIS = 400L
    }
}
