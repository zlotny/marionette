package dev.marionette.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import dev.marionette.BuildConfig
import dev.marionette.MarionetteApp
import dev.marionette.agent.TurnSource
import dev.marionette.audio.AudioKind
import dev.marionette.audio.AudioRoute
import dev.marionette.audio.KnownAudioDevice
import dev.marionette.audio.Port
import dev.marionette.core.Logger
import dev.marionette.llm.Provider
import dev.marionette.settings.Credential
import dev.marionette.settings.SttCloud
import dev.marionette.settings.SttEngine
import dev.marionette.settings.TtsEngine
import dev.marionette.voice.AudioSpec
import dev.marionette.voice.TriggerSource
import dev.marionette.voice.VoiceService
import dev.marionette.voice.stt.OpenAiSpeechToText
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
                            sttEngine = intent.getStringExtra("stt_engine")?.let { SttEngine.valueOf(it.uppercase()) } ?: s.sttEngine,
                            sttCloud = intent.getStringExtra("stt_cloud")?.let { SttCloud.valueOf(it.uppercase()) } ?: s.sttCloud,
                            sttModel = intent.getStringExtra("stt_model") ?: s.sttModel,
                            ttsEngine = intent.getStringExtra("tts_engine")?.let { TtsEngine.valueOf(it.uppercase()) } ?: s.ttsEngine,
                            followUpSeconds = intent.getIntExtra("follow_up", s.followUpSeconds),
                            wakeOverlay = intent.getBooleanExtra("overlay", s.wakeOverlay),
                            phoneVolume = intent.getIntExtra("phone_volume", s.phoneVolume),
                            earconVolume = intent.getIntExtra("earcon_volume", s.earconVolume),
                        ).let { next -> intent.getStringExtra("key")?.let { next.withKey(Credential.of(provider), it) } ?: next }
                    }
                    if (intent.hasExtra("stt_url")) VoiceService.sttUrlOverride = intent.getStringExtra("stt_url")?.ifBlank { null }
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
            ACTION_SAY -> intent.getStringExtra("text")?.let { VoiceService.readAloud(context, it, followUp = false) }
            ACTION_CAPTURE -> {
                val name = intent.getStringExtra("name") ?: "capture-${System.currentTimeMillis()}"
                val samples = intent.getIntExtra("seconds", 30) * AudioSpec.SAMPLE_RATE
                val out = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
                Logger.w("DEBUG_CAPTURE: start $name")
                VoiceService.tap = { pcm, n ->
                    for (i in 0 until minOf(n, out.remaining() / 2)) out.putShort(pcm[i])
                    if (!out.hasRemaining()) {
                        VoiceService.tap = null
                        File(context.getExternalFilesDir(null), "$name.wav").writeBytes(OpenAiSpeechToText.wav(out.array()))
                        Logger.w("DEBUG_CAPTURE: saved $name")
                    }
                }
            }
            ACTION_ENROLL_STOP -> {
                val label = intent.getStringExtra("label") ?: return
                VoiceService.enrollStopPhrase(context, intent.getStringExtra("id") ?: System.currentTimeMillis().toString(), label, append = false)
            }
            ACTION_ENROLL_FINISH -> VoiceService.send(context, VoiceService.ACTION_ENROLL_FINISH)
            ACTION_ROUTE -> {
                val car = intent.getBooleanExtra("car", false)
                val sco = intent.getStringExtra("sco").orEmpty()
                val a2dp = intent.getStringExtra("a2dp").orEmpty()
                val ports = if (a2dp.isNotEmpty()) listOf(Port(9003, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, a2dp, "Car", input = false)) else if (sco.isEmpty()) emptyList() else listOf(
                    Port(9001, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, sco, "Car", input = false),
                    Port(9002, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, sco, "Car", input = true),
                )
                val auto = KnownAudioDevice(AudioRoute.ANDROID_AUTO_ID, AudioKind.ANDROID_AUTO, "Android Auto", hasMic = false, deviceSpeaker = !intent.getBooleanExtra("aa_phone", false))
                val known = if (!car && a2dp.isEmpty()) null else listOfNotNull(
                    KnownAudioDevice("bt:$a2dp", AudioKind.BLUETOOTH, "Car", hasMic = false, volume = intent.getIntExtra("volume", 0)).takeIf { a2dp.isNotEmpty() },
                    KnownAudioDevice("bt:$sco", AudioKind.BLUETOOTH, "Car", hasMic = true).takeIf { sco.isNotEmpty() },
                    auto.takeIf { car },
                )
                graph.audioRoutes.debugSimulate(car.takeIf { it }, ports, known)
                Logger.i("DEBUG_ROUTE: car=$car sco=$sco")
            }
            ACTION_TALK -> VoiceService.send(context, VoiceService.ACTION_TALK, intent.getStringExtra("source")?.let { TriggerSource.valueOf(it.uppercase()) } ?: TriggerSource.BUTTON)
            ACTION_CANCEL -> VoiceService.send(context, VoiceService.ACTION_CANCEL)
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
        const val ACTION_TALK = "dev.marionette.DEBUG_TALK"
        const val ACTION_ROUTE = "dev.marionette.DEBUG_ROUTE"
        const val ACTION_CANCEL = "dev.marionette.DEBUG_CANCEL"
        const val ACTION_AUDIO = "dev.marionette.DEBUG_AUDIO"
        const val WAV_HEADER = 44
        const val ACTION_CONFIRM = "dev.marionette.DEBUG_CONFIRM"
        const val ACTION_CAPTURE = "dev.marionette.DEBUG_CAPTURE"
        const val ACTION_SAY = "dev.marionette.DEBUG_SAY"
        const val ACTION_ENROLL_STOP = "dev.marionette.DEBUG_ENROLL_STOP"
        const val ACTION_ENROLL_FINISH = "dev.marionette.DEBUG_ENROLL_FINISH"
    }
}
