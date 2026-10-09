package dev.marionette.assist

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import dev.marionette.ui.MainActivity
import dev.marionette.ui.assist.AssistOverlay
import dev.marionette.ui.theme.MarionetteTheme
import dev.marionette.voice.TriggerSource
import dev.marionette.voice.VoiceService

class AssistantSession(context: Context) : VoiceInteractionSession(context), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    private var sinceTurnId by mutableLongStateOf(0L)
    private var showCount by mutableLongStateOf(0L)

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
                    AssistOverlay(sinceTurnId = sinceTurnId, onTalk = ::talk, onDismiss = ::hide)
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
        sinceTurnId = graph.conversation.turns.value.lastOrNull()?.id ?: 0L
        showCount++
        registry.currentState = Lifecycle.State.RESUMED
        talk()
    }

    override fun onHide() {
        if (registry.currentState.isAtLeast(Lifecycle.State.STARTED)) registry.currentState = Lifecycle.State.CREATED
        super.onHide()
    }

    override fun onDestroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
        super.onDestroy()
    }

    private fun talk() = VoiceService.send(context, VoiceService.ACTION_TALK, TriggerSource.ASSIST)

    private fun hasMic() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}
