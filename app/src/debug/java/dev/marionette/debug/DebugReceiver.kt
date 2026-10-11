package dev.marionette.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.marionette.BuildConfig
import dev.marionette.MarionetteApp
import dev.marionette.agent.TurnSource
import dev.marionette.core.Logger
import dev.marionette.llm.Provider
import dev.marionette.settings.Credential
import dev.marionette.voice.VoiceService
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
                            model = intent.getStringExtra("model") ?: if (provider != s.provider) provider.suggestedModel else s.model,
                            onboarded = intent.getBooleanExtra("onboarded", s.onboarded),
                            disabledTools = intent.getStringExtra("disabled_tools")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: s.disabledTools,
                        ).let { next -> intent.getStringExtra("key")?.let { next.withKey(Credential.of(provider), it) } ?: next }
                    }
                    Logger.i("DEBUG_BACKEND: ${graph.settings.current.backend.copy(apiKey = "***")}")
                    pending.finish()
                }
            }
            ACTION_KEY -> {
                val credential = intent.getStringExtra("credential")?.let { name -> Credential.entries.firstOrNull { it.name == name.uppercase() } } ?: return
                val pending = goAsync()
                graph.scope.launch {
                    graph.settings.update { it.withKey(credential, intent.getStringExtra("key").orEmpty()) }
                    Logger.i("DEBUG_KEY: $credential")
                    pending.finish()
                }
            }
            ACTION_AUDIO -> {
                val name = intent.getStringExtra("path") ?: return
                val file = File(name).takeIf { it.isAbsolute } ?: File(context.getExternalFilesDir(null), name)
                val bytes = file.readBytes()
                val data = ByteBuffer.wrap(bytes, WAV_HEADER, bytes.size - WAV_HEADER).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val pcm = ShortArray(data.remaining()).also { data.get(it) }
                Logger.i("DEBUG_AUDIO: ${pcm.size} samples from ${file.name}")
                VoiceService.inject(pcm)
            }
            ACTION_ENROLL_STOP -> {
                val label = intent.getStringExtra("label") ?: return
                VoiceService.enrollStopPhrase(context, intent.getStringExtra("id") ?: System.currentTimeMillis().toString(), label, append = false)
            }
            ACTION_ENROLL_FINISH -> VoiceService.send(context, VoiceService.ACTION_ENROLL_FINISH)
            ACTION_RESET -> graph.agent.resetSession()
            ACTION_CONFIRM -> graph.confirmations.pending.value?.let {
                graph.confirmations.answer(it.id, intent.getBooleanExtra("yes", true))
            }
        }
    }

    private companion object {
        const val ACTION_COMMAND = "dev.marionette.DEBUG_COMMAND"
        const val ACTION_BACKEND = "dev.marionette.DEBUG_BACKEND"
        const val ACTION_KEY = "dev.marionette.DEBUG_KEY"
        const val ACTION_RESET = "dev.marionette.DEBUG_RESET"
        const val ACTION_AUDIO = "dev.marionette.DEBUG_AUDIO"
        const val WAV_HEADER = 44
        const val ACTION_CONFIRM = "dev.marionette.DEBUG_CONFIRM"
        const val ACTION_ENROLL_STOP = "dev.marionette.DEBUG_ENROLL_STOP"
        const val ACTION_ENROLL_FINISH = "dev.marionette.DEBUG_ENROLL_FINISH"
    }
}
