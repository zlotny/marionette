package dev.marionette.assist

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.voice.VoiceInteractionSession
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.marionette.MarionetteApp
import dev.marionette.core.Logger
import dev.marionette.launch.Launcher
import dev.marionette.launch.UnlockTrampolineActivity
import dev.marionette.screen.MarionetteAccessibilityService
import dev.marionette.ui.MainActivity
import dev.marionette.ui.assist.AssistOverlay
import dev.marionette.ui.theme.MarionetteTheme
import dev.marionette.voice.TriggerSource
import dev.marionette.voice.VoiceBus
import dev.marionette.voice.VoiceService
import dev.marionette.voice.idle
import kotlin.math.roundToInt

class AssistantSession(context: Context) : VoiceInteractionSession(context), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    private var sinceTurnId by mutableLongStateOf(0L)
    private var followUp by mutableStateOf(false)
    private var showCount by mutableLongStateOf(0L)
    private val power = context.getSystemService(PowerManager::class.java)
    private val windows = context.getSystemService(WindowManager::class.java)
    private var screenWatched = false
    private var screenWasOn = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    screenWasOn = true
                    turnScreenOn(false)
                }
                Intent.ACTION_SCREEN_OFF -> onScreenOff()
            }
        }
    }

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
        window?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onCreateContentView(): View = ComposeView(context).apply {
        setViewTreeLifecycleOwner(this@AssistantSession)
        setViewTreeSavedStateRegistryOwner(this@AssistantSession)
        setViewTreeViewModelStoreOwner(this@AssistantSession)
        window?.window?.decorView?.let { decor ->
            decor.setViewTreeLifecycleOwner(this@AssistantSession)
            decor.setViewTreeSavedStateRegistryOwner(this@AssistantSession)
            decor.setViewTreeViewModelStoreOwner(this@AssistantSession)
        }
        setContent {
            MarionetteTheme {
                key(showCount) {
                    AssistOverlay(sinceTurnId = sinceTurnId, onCancelTurn = { cancelTurn() }, onDismiss = ::hide, onScrim = ::blurBehind, followUp = followUp)
                }
            }
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        if (args?.getBoolean(Launcher.EXTRA_LAUNCH) == true) {
            runCatching { startAssistantActivity(Intent(context, UnlockTrampolineActivity::class.java)) }
                .onFailure { Logger.w("startAssistantActivity failed", it) }
            hide()
            return
        }
        val graph = MarionetteApp.graph
        if (!graph.settings.current.onboarded || !hasMic()) {
            runCatching { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            hide()
            return
        }
        val attach = args?.getBoolean(EXTRA_ATTACH) == true
        turnScreenOn(args?.getBoolean(EXTRA_SCREEN_ON) == true && !power.isInteractive)
        sinceTurnId = graph.conversation.turns.value.lastOrNull()?.id ?: 0L
        followUp = args?.getBoolean(EXTRA_FOLLOW_UP) == true
        showCount++
        Logger.ux("overlay_show", if (followUp) "follow_up" else if (attach) "attach" else "assist")
        registry.currentState = Lifecycle.State.RESUMED
        shown = this
        watchScreen(true)
        if (!attach) talk()
    }

    override fun onHide() {
        Logger.ux("overlay_hide")
        if (shown === this) shown = null
        val screenTurnedOff = screenWasOn && !power.isInteractive
        watchScreen(false)
        if (screenTurnedOff && busy() && !MarionetteAccessibilityService.lockedRecently) cancelTurn()
        if (registry.currentState.isAtLeast(Lifecycle.State.STARTED)) registry.currentState = Lifecycle.State.CREATED
        blurBehind(0f)
        super.onHide()
    }

    override fun onDestroy() {
        if (shown === this) shown = null
        watchScreen(false)
        registry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
        super.onDestroy()
    }

    private fun watchScreen(on: Boolean) {
        if (on == screenWatched) return
        screenWatched = on
        if (on) {
            screenWasOn = power.isInteractive
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            ContextCompat.registerReceiver(context, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            runCatching { context.unregisterReceiver(screenReceiver) }
        }
    }

    private fun onScreenOff() {
        val wasOn = screenWasOn
        screenWasOn = false
        if (!wasOn || MarionetteAccessibilityService.lockedRecently) return
        Logger.i("Screen turned off while the overlay showed")
        if (busy()) cancel() else hide()
    }

    private fun blurBehind(fraction: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val w = window?.window ?: return
        val radius = if (windows.isCrossWindowBlurEnabled) (fraction * MAX_BLUR_DP * context.resources.displayMetrics.density).roundToInt() else 0
        val attrs = w.attributes
        if (attrs.blurBehindRadius == radius) return
        if (radius > 0) w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND) else w.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        w.attributes = attrs.apply { blurBehindRadius = radius }
    }

    @Suppress("DEPRECATION")
    private fun turnScreenOn(on: Boolean) {
        val flag = WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        window?.window?.apply { if (on) addFlags(flag) else clearFlags(flag) }
    }

    private fun busy(): Boolean = !VoiceBus.status.value.phase.idle || MarionetteApp.graph.confirmations.pending.value != null

    private fun cancel() {
        cancelTurn()
        hide()
    }

    private fun cancelTurn() = VoiceService.send(context, VoiceService.ACTION_CANCEL)

    private fun talk() = VoiceService.send(context, VoiceService.ACTION_TALK, TriggerSource.ASSIST)

    private fun hasMic() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val EXTRA_ATTACH = "dev.marionette.ATTACH"
        const val EXTRA_SCREEN_ON = "dev.marionette.SCREEN_ON"
        const val EXTRA_FOLLOW_UP = "dev.marionette.FOLLOW_UP"
        private const val MAX_BLUR_DP = 24f

        @Volatile
        private var shown: AssistantSession? = null

        val isShown: Boolean get() = shown != null

        fun hideShown(): Boolean {
            val session = shown ?: return false
            Handler(Looper.getMainLooper()).post { session.hide() }
            return true
        }
    }
}
