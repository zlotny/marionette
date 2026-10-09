package dev.marionette.voice

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.marionette.MarionetteApp
import dev.marionette.R
import dev.marionette.agent.ConfirmationRequest
import dev.marionette.agent.TurnSource
import dev.marionette.agent.TurnStatus
import dev.marionette.core.Logger
import dev.marionette.settings.SttEngine
import dev.marionette.ui.MainActivity
import dev.marionette.voice.stt.AndroidSpeechToText
import dev.marionette.voice.stt.OpenAiSpeechToText
import dev.marionette.voice.stt.SpeechToText
import dev.marionette.voice.stt.SttSession
import dev.marionette.voice.wake.AcousticWakeDetector
import dev.marionette.voice.wake.SpeechEmbedder
import dev.marionette.voice.wake.UtteranceCounter
import dev.marionette.voice.wake.WakeTemplates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class VoiceService : LifecycleService() {

    private enum class Mode { WAKE, COMMAND, ENROLL, BUSY }

    private enum class Endpoint { SPEECH_END, NO_SPEECH, MAX_LENGTH, CANCELLED }

    private val graph get() = MarionetteApp.graph
    private val templates by lazy { WakeTemplates(this) }
    private val speaker by lazy { Speaker(this) }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val micDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val detectorLock = Mutex()

    @Volatile private var mode = Mode.WAKE
    @Volatile private var detector: AcousticWakeDetector? = null
    @Volatile private var commandSink: CommandSink? = null
    private var embedder: SpeechEmbedder? = null
    private var vad: Vad? = null
    private var captureJob: Job? = null
    private var commandJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val enrollBuffer = ShortArray(AudioSpec.SAMPLE_RATE * MAX_ENROLL_SECONDS)
    private var enrollSamples = 0
    private var enrollCounter: UtteranceCounter? = null

    private class CommandSink(
        val session: SttSession,
        val noSpeechMillis: Long,
        val endpoint: CompletableDeferred<Endpoint> = CompletableDeferred(),
        var speechStarted: Boolean = false,
        var silenceMillis: Long = 0,
        var elapsedMillis: Long = 0,
    )

    override fun onCreate() {
        super.onCreate()
        running = true
        speaker.warmUp()
        graph.confirmations.voiceHandler = ::confirmByVoice
        lifecycleScope.launch {
            VoiceBus.status.map { it.phase }.distinctUntilChanged().collect { updateNotification() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!startInForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TALK -> trigger(TriggerSource.entries[intent.getIntExtra(EXTRA_SOURCE, TriggerSource.BUTTON.ordinal)])
            ACTION_CANCEL -> cancelCommand()
            ACTION_ENROLL_START -> startEnrollment()
            ACTION_ENROLL_FINISH -> finishEnrollment(save = true)
            ACTION_ENROLL_CANCEL -> finishEnrollment(save = false)
            ACTION_RELOAD -> reloadDetector()
            ACTION_WAKE_TEST_START -> wakeTest = true
            ACTION_WAKE_TEST_STOP -> wakeTest = false
            ACTION_ENROLL_CLEAR -> clearEnrollment()
        }
        ensureCapture()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        wakeTest = false
        graph.confirmations.voiceHandler = null
        commandJob?.cancel()
        captureJob?.cancel()
        commandSink?.session?.cancel()
        speaker.shutdown()
        embedder?.close()
        vad?.close()
        wakeLock?.takeIf { it.isHeld }?.release()
        VoiceBus.update { VoiceStatus(templateCount = it.templateCount) }
        super.onDestroy()
    }

    private fun startInForeground(): Boolean = try {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        true
    } catch (e: Exception) {
        Logger.e("Cannot start microphone foreground service", e)
        VoiceBus.update { it.copy(lastError = e.message) }
        false
    }

    private fun ensureCapture() {
        if (captureJob?.isActive == true) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            VoiceBus.update { it.copy(lastError = getString(R.string.error_mic_permission)) }
            stopSelf()
            return
        }
        captureJob = lifecycleScope.launch(micDispatcher) {
            reloadDetectorNow()
            VoiceBus.update { it.copy(phase = if (mode == Mode.ENROLL) VoicePhase.ENROLLING else VoicePhase.WAITING, lastError = null) }
            capture()
        }
    }

    private fun reloadDetector() {
        lifecycleScope.launch(Dispatchers.IO) { reloadDetectorNow() }
    }

    private suspend fun reloadDetectorNow() = detectorLock.withLock {
        val settings = graph.settings.current
        val loaded = templates.load()
        val embedder = embedder ?: runCatching { SpeechEmbedder(assets) }.onFailure { Logger.e("Embedder load failed", it) }.getOrNull()
        this.embedder = embedder
        detector = if (settings.wakeEnabled && loaded.isNotEmpty() && embedder != null) AcousticWakeDetector(embedder, loaded) else null
        VoiceBus.update { it.copy(templateCount = loaded.size, wakeArmed = detector != null) }
        Logger.i("Wake detector: ${loaded.size} templates, armed=${detector != null}")
    }

    @SuppressLint("MissingPermission")
    private suspend fun capture() {
        val minBuffer = AudioRecord.getMinBufferSize(AudioSpec.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            AudioSpec.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, AudioSpec.CHUNK_SAMPLES * 2 * 8),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            VoiceBus.update { it.copy(lastError = getString(R.string.error_mic_unavailable)) }
            return
        }
        val pcm = ShortArray(AudioSpec.CHUNK_SAMPLES)
        val samples = FloatArray(AudioSpec.CHUNK_SAMPLES)
        var lastLevelPublish = 0L
        var lastScorePublish = 0L
        var windowScore = Float.POSITIVE_INFINITY
        record.startRecording()
        VoiceBus.update { it.copy(inputDevice = record.routedDevice?.productName?.toString()) }
        try {
            while (kotlin.coroutines.coroutineContext.isActive) {
                val n = record.read(pcm, 0, pcm.size)
                if (n <= 0) continue
                var sum = 0.0
                for (i in 0 until n) {
                    val s = pcm[i] / 32768f
                    samples[i] = s
                    sum += s * s
                }
                val chunk = if (n == samples.size) samples else samples.copyOf(n)
                val now = System.currentTimeMillis()
                if (now - lastLevelPublish > 60) {
                    val level = sqrt(sum / n).toFloat()
                    VoiceBus.update { it.copy(level = level) }
                    lastLevelPublish = now
                }
                when (mode) {
                    Mode.WAKE, Mode.BUSY -> detector?.accept(chunk, graph.settings.current.wakeThreshold)?.let { (score, fired) ->
                        windowScore = minOf(windowScore, score)
                        if (now - lastScorePublish > 1000) {
                            VoiceBus.update { it.copy(lastScore = windowScore) }
                            windowScore = Float.POSITIVE_INFINITY
                            lastScorePublish = now
                        }
                        if (fired) {
                            Logger.i("Wake word detected (score $score)")
                            VoiceBus.update { it.copy(lastWakeAt = now) }
                            if (wakeTest) {
                                lifecycleScope.launch { playEarcon(Earcon.DONE) }
                            } else {
                                lifecycleScope.launch { trigger(TriggerSource.WAKE_WORD) }
                            }
                        }
                    }
                    Mode.COMMAND -> commandSink?.let { feedCommand(it, pcm, n, chunk) }
                    Mode.ENROLL -> feedEnrollment(pcm, n, chunk)
                }
            }
        } finally {
            record.stop()
            record.release()
        }
    }

    private fun feedCommand(sink: CommandSink, pcm: ShortArray, n: Int, chunk: FloatArray) {
        if (sink.endpoint.isCompleted) return
        sink.session.accept(pcm, n)
        val vad = vad ?: runCatching { Vad(assets) }.onFailure { Logger.e("VAD load failed", it) }.getOrNull()?.also { vad = it }
        val probability = vad?.accept(chunk) ?: energyProbability(chunk)
        val chunkMillis = n * 1000L / AudioSpec.SAMPLE_RATE
        sink.elapsedMillis += chunkMillis
        if (probability >= SPEECH_PROBABILITY) {
            sink.speechStarted = true
            sink.silenceMillis = 0
        } else if (sink.speechStarted && probability < SILENCE_PROBABILITY) {
            sink.silenceMillis += chunkMillis
        }
        val silenceLimit = graph.settings.current.silenceMillis.toLong()
        when {
            sink.speechStarted && sink.silenceMillis >= silenceLimit -> sink.endpoint.complete(Endpoint.SPEECH_END)
            !sink.speechStarted && sink.elapsedMillis >= sink.noSpeechMillis -> sink.endpoint.complete(Endpoint.NO_SPEECH)
            sink.elapsedMillis >= MAX_COMMAND_MILLIS -> sink.endpoint.complete(Endpoint.MAX_LENGTH)
        }
    }

    private fun energyProbability(chunk: FloatArray): Float {
        var sum = 0.0
        for (s in chunk) sum += s * s
        return if (sqrt(sum / chunk.size) > 0.02) 1f else 0f
    }

    private fun trigger(source: TriggerSource) {
        if (mode == Mode.ENROLL) return
        commandJob?.cancel()
        graph.agent.cancel()
        speaker.stop()
        commandJob = lifecycleScope.launch { runCommand(source) }
    }

    private fun cancelCommand() {
        commandJob?.cancel()
        graph.agent.cancel()
        speaker.stop()
    }

    private suspend fun runCommand(source: TriggerSource) {
        acquireWakeLock()
        try {
            Logger.i("Voice turn from $source")
            var text = listen(NO_SPEECH_MILLIS, earcon = true)
            while (true) {
                if (text.isBlank()) {
                    playEarcon(Earcon.ERROR)
                    break
                }
                VoiceBus.update { it.copy(phase = VoicePhase.THINKING, partial = text) }
                val turn = graph.agent.run(text, TurnSource.VOICE)
                VoiceBus.update { it.copy(phase = VoicePhase.SPEAKING) }
                speak(turn.reply)
                val followUp = graph.settings.current.followUpSeconds
                if (turn.status != TurnStatus.ANSWERED || followUp <= 0) break
                text = listen(followUp * 1000L, earcon = false)
                if (text.isBlank()) break
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w("Voice turn failed", e)
            VoiceBus.update { it.copy(lastError = e.message) }
            playEarcon(Earcon.ERROR)
            speak(getString(R.string.voice_error_spoken))
        } finally {
            mode = Mode.WAKE
            commandSink?.session?.cancel()
            commandSink = null
            VoiceBus.update { it.copy(phase = if (running) VoicePhase.WAITING else VoicePhase.OFF, partial = "") }
            releaseWakeLock()
        }
    }

    private suspend fun listen(noSpeechMillis: Long, earcon: Boolean): String {
        VoiceBus.update { it.copy(phase = VoicePhase.LISTENING, partial = "", lastError = null) }
        mode = Mode.BUSY
        if (earcon) playEarcon(Earcon.WAKE)
        val settings = graph.settings.current
        val engine = sttEngine()
        val session = try {
            engine.start(settings.language) { partial -> VoiceBus.update { it.copy(partial = partial) } }
        } catch (e: Exception) {
            Logger.w("STT start failed", e)
            VoiceBus.update { it.copy(lastError = e.message) }
            return ""
        }
        vad?.reset()
        val sink = CommandSink(session, noSpeechMillis)
        commandSink = sink
        mode = Mode.COMMAND
        val endpoint = try {
            sink.endpoint.await()
        } catch (e: CancellationException) {
            session.cancel()
            throw e
        } finally {
            mode = Mode.BUSY
            commandSink = null
        }
        Logger.i("Endpoint $endpoint after ${sink.elapsedMillis} ms")
        if (endpoint == Endpoint.NO_SPEECH) {
            session.cancel()
            return ""
        }
        VoiceBus.update { it.copy(phase = VoicePhase.TRANSCRIBING) }
        return runCatching { session.finish() }
            .onFailure {
                Logger.w("STT failed", it)
                VoiceBus.update { s -> s.copy(lastError = it.message) }
            }
            .getOrDefault("")
            .also { text -> Logger.i("Heard: $text") }
    }

    private suspend fun confirmByVoice(request: ConfirmationRequest) {
        val previous = VoiceBus.status.value.phase
        VoiceBus.update { it.copy(phase = VoicePhase.SPEAKING) }
        speak(request.prompt)
        repeat(2) {
            val answer = YesNo.parse(listen(CONFIRM_TIMEOUT_MILLIS, earcon = false))
            if (answer != null) {
                request.answer.complete(answer)
                VoiceBus.update { it.copy(phase = previous) }
                return
            }
        }
        request.answer.complete(false)
        VoiceBus.update { it.copy(phase = previous) }
    }

    private suspend fun speak(text: String) {
        if (text.isBlank()) return
        val settings = graph.settings.current
        speaker.speak(text, settings.language, settings.ttsVoice, settings.speechRate)
    }

    private suspend fun playEarcon(earcon: Earcon) {
        if (graph.settings.current.earcons) runCatching { Earcons.play(earcon) }
    }

    private fun sttEngine(): SpeechToText {
        val settings = graph.settings.current
        val openAi = OpenAiSpeechToText({ settings.sttApiKey.ifBlank { if (settings.provider.name == "OPENAI") settings.apiKey else "" } })
        return when {
            settings.sttEngine == SttEngine.OPENAI_CLOUD && openAi.isAvailable() -> openAi
            else -> AndroidSpeechToText(this)
        }
    }

    private fun clearEnrollment() {
        lifecycleScope.launch(Dispatchers.IO) {
            templates.clear()
            reloadDetectorNow()
        }
    }

    private fun startEnrollment() {
        commandJob?.cancel()
        enrollSamples = 0
        enrollCounter = UtteranceCounter(AudioSpec.SAMPLE_RATE)
        mode = Mode.ENROLL
        VoiceBus.update { it.copy(phase = VoicePhase.ENROLLING, enrolledPhrases = 0) }
    }

    private fun feedEnrollment(pcm: ShortArray, n: Int, chunk: FloatArray) {
        val copy = minOf(n, enrollBuffer.size - enrollSamples)
        if (copy > 0) {
            pcm.copyInto(enrollBuffer, enrollSamples, 0, copy)
            enrollSamples += copy
        }
        if (enrollCounter?.accept(chunk) == true) {
            VoiceBus.update { it.copy(enrolledPhrases = it.enrolledPhrases + 1) }
            if (graph.settings.current.earcons) lifecycleScope.launch { runCatching { Earcons.play(Earcon.DONE) } }
        }
        if (enrollSamples >= enrollBuffer.size) lifecycleScope.launch { finishEnrollment(save = true) }
    }

    private fun finishEnrollment(save: Boolean) {
        if (mode != Mode.ENROLL) return
        mode = Mode.BUSY
        val audio = enrollBuffer.copyOf(enrollSamples)
        enrollCounter = null
        lifecycleScope.launch {
            if (save && audio.isNotEmpty()) {
                val built = withContext(Dispatchers.Default) {
                    val e = detectorLock.withLock { embedder ?: SpeechEmbedder(assets).also { embedder = it } }
                    AcousticWakeDetector.buildTemplates(e, audio)
                }
                if (built.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        templates.save(built)
                        saveSample(audio)
                    }
                }
                Logger.i("Enrollment built ${built.size} templates")
            }
            withContext(Dispatchers.IO) { reloadDetectorNow() }
            mode = Mode.WAKE
            VoiceBus.update { it.copy(phase = VoicePhase.WAITING) }
        }
    }

    private fun saveSample(pcm: ShortArray) {
        val bytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { bytes.putShort(it) }
        File(templates.samplesDir, "enroll-${System.currentTimeMillis()}.wav").writeBytes(OpenAiSpeechToText.wav(bytes.array()))
    }

    private fun acquireWakeLock() {
        val lock = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "marionette:turn")
            .also { wakeLock = it }
        if (!lock.isHeld) lock.acquire(TURN_WAKE_LOCK_MILLIS)
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
    }

    private fun updateNotification() {
        if (!running) return
        runCatching { getSystemService(android.app.NotificationManager::class.java).notify(NOTIFICATION_ID, notification()) }
    }

    private fun notification(): Notification {
        val status = VoiceBus.status.value
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val talk = PendingIntent.getService(
            this, 1, intent(this, ACTION_TALK).putExtra(EXTRA_SOURCE, TriggerSource.NOTIFICATION.ordinal),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(this, 2, intent(this, ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val text = when (status.phase) {
            VoicePhase.LISTENING -> getString(R.string.notification_listening)
            VoicePhase.TRANSCRIBING, VoicePhase.THINKING -> getString(R.string.notification_thinking)
            VoicePhase.SPEAKING -> getString(R.string.notification_speaking)
            VoicePhase.ENROLLING -> getString(R.string.notification_enrolling)
            else -> if (status.wakeArmed) getString(R.string.notification_waiting_wake) else getString(R.string.notification_waiting)
        }
        return NotificationCompat.Builder(this, MarionetteApp.CHANNEL_LISTENER)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.action_talk), talk)
            .addAction(0, getString(R.string.action_stop), stop)
            .build()
    }

    companion object {
        const val ACTION_START = "dev.marionette.voice.START"
        const val ACTION_STOP = "dev.marionette.voice.STOP"
        const val ACTION_TALK = "dev.marionette.voice.TALK"
        const val ACTION_CANCEL = "dev.marionette.voice.CANCEL"
        const val ACTION_ENROLL_START = "dev.marionette.voice.ENROLL_START"
        const val ACTION_ENROLL_FINISH = "dev.marionette.voice.ENROLL_FINISH"
        const val ACTION_ENROLL_CANCEL = "dev.marionette.voice.ENROLL_CANCEL"
        const val ACTION_RELOAD = "dev.marionette.voice.RELOAD"
        const val ACTION_ENROLL_CLEAR = "dev.marionette.voice.ENROLL_CLEAR"
        const val ACTION_WAKE_TEST_START = "dev.marionette.voice.WAKE_TEST_START"
        const val ACTION_WAKE_TEST_STOP = "dev.marionette.voice.WAKE_TEST_STOP"
        const val EXTRA_SOURCE = "source"

        private const val NOTIFICATION_ID = 7
        private const val MAX_ENROLL_SECONDS = 90
        private const val NO_SPEECH_MILLIS = 6_000L
        private const val CONFIRM_TIMEOUT_MILLIS = 5_000L
        private const val MAX_COMMAND_MILLIS = 15_000L
        private const val TURN_WAKE_LOCK_MILLIS = 120_000L
        private const val SPEECH_PROBABILITY = 0.55f
        private const val SILENCE_PROBABILITY = 0.35f

        @Volatile
        var running = false
            private set

        @Volatile
        private var wakeTest = false

        fun intent(context: Context, action: String): Intent = Intent(context, VoiceService::class.java).setAction(action)

        fun send(context: Context, action: String, source: TriggerSource? = null) {
            val intent = intent(context, action)
            source?.let { intent.putExtra(EXTRA_SOURCE, it.ordinal) }
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Logger.w("Cannot start voice service for $action", it) }
        }
    }
}
