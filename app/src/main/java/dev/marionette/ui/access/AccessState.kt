package dev.marionette.ui.access

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.edit
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.marionette.access.Access
import dev.marionette.access.AccessKind

@Stable
class AccessState internal constructor(private val context: Context) {
    private var version by mutableIntStateOf(0)
    internal var launch: (Access) -> Unit = {}

    val stamp: Int get() = version

    fun refresh() {
        version++
    }

    fun isGranted(access: Access): Boolean {
        version
        return access.isGranted(context)
    }

    fun isReady(access: Access): Boolean = access.requires?.let(::isGranted) ?: true

    fun isBlocked(access: Access): Boolean {
        version
        if (access.kind != AccessKind.RUNTIME || access.isGranted(context)) return false
        val activity = context as? Activity ?: return false
        return context.getSharedPreferences("access", Context.MODE_PRIVATE).getBoolean("asked_${access.name}", false) && !canAsk(activity, access)
    }

    fun request(access: Access) = launch(access)

    fun openSettings(access: Access) {
        val intent = if (access.kind == AccessKind.RUNTIME) Access.appDetails(context) else access.settingsIntent(context)
        start(context, intent)
    }
}

@Composable
fun rememberAccessState(): AccessState {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val state = remember { AccessState(context) }
    val prefs = remember { context.getSharedPreferences("access", Context.MODE_PRIVATE) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { state.refresh() }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { state.refresh() }
    LifecycleResumeEffect(Unit) {
        state.refresh()
        onPauseOrDispose { }
    }
    state.launch = { access ->
        when (access.kind) {
            AccessKind.RUNTIME -> {
                val key = "asked_${access.name}"
                if (prefs.getBoolean(key, false) && activity != null && !canAsk(activity, access)) {
                    start(context, Access.appDetails(context))
                } else {
                    prefs.edit { putBoolean(key, true) }
                    permissions.launch(access.permissions.toTypedArray())
                }
            }
            else -> try {
                settings.launch(access.settingsIntent(context))
            } catch (e: ActivityNotFoundException) {
                start(context, Access.appDetails(context))
            }
        }
    }
    return state
}

private fun canAsk(activity: Activity, access: Access) =
    access.permissions.any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }

private fun start(context: Context, intent: android.content.Intent) {
    runCatching { context.startActivity(intent) }.recoverCatching { context.startActivity(Access.appDetails(context)) }
}
