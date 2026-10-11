package dev.marionette.screen

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.marionette.core.Logger

data class ScreenNode(
    val id: Int,
    val node: AccessibilityNodeInfo,
    val text: String?,
    val role: String,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checked: Boolean?,
    val bounds: Rect,
)

class MarionetteAccessibilityService : AccessibilityService() {
    @Volatile
    var lastNodes: List<ScreenNode> = emptyList()
        private set

    override fun onServiceConnected() {
        instance = this
        Logger.i("Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun foregroundPackage(): String? = rootInActiveWindow?.packageName?.toString()

    fun snapshot(maxNodes: Int): List<ScreenNode> {
        val root = rootInActiveWindow ?: return emptyList<ScreenNode>().also { lastNodes = it }
        val out = mutableListOf<ScreenNode>()
        val screen = Rect().also { root.getBoundsInScreen(it) }
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || out.size >= maxNodes || depth > 60) return
            if (!node.isVisibleToUser) return
            val text = listOfNotNull(
                node.text?.toString()?.takeIf { it.isNotBlank() },
                node.contentDescription?.toString()?.takeIf { it.isNotBlank() },
                node.hintText?.toString()?.takeIf { it.isNotBlank() && node.text.isNullOrBlank() }?.let { "hint: $it" },
            ).distinct().joinToString(" | ").take(200).ifBlank { null }
            val interesting = node.isClickable || node.isEditable || node.isScrollable || node.isCheckable || text != null
            if (interesting) {
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                if (Rect.intersects(bounds, screen)) {
                    out += ScreenNode(
                        id = out.size + 1,
                        node = node,
                        text = text,
                        role = node.className?.toString()?.substringAfterLast('.') ?: "View",
                        clickable = node.isClickable || node.isLongClickable,
                        editable = node.isEditable,
                        scrollable = node.isScrollable,
                        checked = if (node.isCheckable) node.isChecked else null,
                        bounds = bounds,
                    )
                }
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        lastNodes = out
        return out
    }

    fun click(node: AccessibilityNodeInfo, long: Boolean = false): Boolean {
        val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if ((if (long) current.isLongClickable else current.isClickable) && current.performAction(action)) return true
            current = current.parent
        }
        return false
    }

    fun type(node: AccessibilityNodeInfo, text: String): Boolean {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun scroll(node: AccessibilityNodeInfo?, forward: Boolean): Boolean {
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val target = node ?: lastNodes.firstOrNull { it.scrollable }?.node ?: return false
        var current: AccessibilityNodeInfo? = target
        while (current != null) {
            if (current.isScrollable && current.performAction(action)) return true
            current = current.parent
        }
        return false
    }

    companion object {
        @Volatile
        var instance: MarionetteAccessibilityService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val me = ComponentName(context, MarionetteAccessibilityService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }

        fun lockScreen(): Boolean {
            val locked = instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) == true
            Logger.i("Lock screen after screen control: $locked")
            return locked
        }
    }
}
