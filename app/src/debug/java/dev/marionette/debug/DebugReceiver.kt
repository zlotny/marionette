package dev.marionette.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.marionette.BuildConfig
import dev.marionette.MarionetteApp
import dev.marionette.agent.TurnSource
import dev.marionette.core.Logger
import dev.marionette.llm.Provider
import kotlinx.coroutines.launch

class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        val graph = MarionetteApp.graph
        when (intent.action) {
            ACTION_COMMAND -> {
                val text = intent.getStringExtra("text").orEmpty()
                if (text.isBlank()) return
                Logger.i("DEBUG_COMMAND: $text")
                graph.agent.submit(text, TurnSource.DEBUG) { turn -> Logger.i("DEBUG_REPLY [${turn.status}]: ${turn.reply}") }
            }
            ACTION_BACKEND -> {
                val pending = goAsync()
                graph.scope.launch {
                    graph.settings.update { s ->
                        val provider = intent.getStringExtra("provider")?.let { Provider.of(it.uppercase()) } ?: s.provider
                        s.copy(
                            provider = provider,
                            baseUrl = intent.getStringExtra("url") ?: if (provider != s.provider) provider.defaultBaseUrl else s.baseUrl,
                            apiKey = intent.getStringExtra("key") ?: s.apiKey,
                            model = intent.getStringExtra("model") ?: if (provider != s.provider) provider.suggestedModel else s.model,
                            onboarded = intent.getBooleanExtra("onboarded", s.onboarded),
                            disabledTools = intent.getStringExtra("disabled_tools")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: s.disabledTools,
                        )
                    }
                    Logger.i("DEBUG_BACKEND: ${graph.settings.current.backend.copy(apiKey = "***")}")
                    pending.finish()
                }
            }
            ACTION_RESET -> graph.agent.resetSession()
            ACTION_CONFIRM -> graph.confirmations.pending.value?.let {
                graph.confirmations.answer(it.id, intent.getBooleanExtra("yes", true))
            }
        }
    }

    private companion object {
        const val ACTION_COMMAND = "dev.marionette.DEBUG_COMMAND"
        const val ACTION_BACKEND = "dev.marionette.DEBUG_BACKEND"
        const val ACTION_RESET = "dev.marionette.DEBUG_RESET"
        const val ACTION_CONFIRM = "dev.marionette.DEBUG_CONFIRM"
    }
}
