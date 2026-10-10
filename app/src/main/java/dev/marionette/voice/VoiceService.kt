package dev.marionette.voice

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Resources
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.marionette.BuildConfig
import dev.marionette.MarionetteApp
import dev.marionette.R
import dev.marionette.settings.Languages
import dev.marionette.agent.ConfirmationRequest
import dev.marionette.agent.Turn
import dev.marionette.agent.TurnSource
import dev.marionette.agent.TurnStatus
import dev.marionette.core.Logger
import dev.marionette.settings.Credential
import dev.marionette.settings.SttEngine
import dev.marionette.ui.MainActivity
import dev.marionette.voice.stt.AndroidSpeechToText
import dev.marionette.voice.stt.OpenAiSpeechToText
import dev.marionette.voice.stt.SpeechToText
import dev.marionette.voice.stt.SttException
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.sqrt

class VoiceService : LifecycleService() {

    private enum class Mode { WAKE, COMMAND, ENROLL, BUSY }

    private enum class Endpoint { SPEECH_END, NO_SPEECH, MAX_LENGTH, CANCELLED }

    private val graph get() = MarionetteApp.graph
    private val templates by lazy { WakeTemplates(this) }
    private val speaker by lazy { Speaker(this) }
    private val audio by lazy { getSystemService(AudioManager::class.java) }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val micDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val detectorLock = Mutex()
    private val speech = Mutex()

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
    private var enrollAppend = false
    private var lastHoldPhrase: String? = null
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
        lifecycleScope.launch {
            graph.settings.settings.map { Triple(it.language, it.sttEngine, it.sttCloud) }.distinctUntilChanged().collect { checkSpeechLanguage() }
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
            ACTION_READ_ALOUD -> intent.getStringExtra(EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let {
                trigger(TriggerSource.BUTTON, readAloud = it, followUp = intent.getBooleanExtra(EXTRA_FOLLOW_UP, false))
            }
            ACTION_CANCEL -> cancelCommand()
            ACTION_ENROLL_START -> startEnrollment(append = intent.getBooleanExtra(EXTRA_APPEND, false))
            ACTION_ENROLL_FINISH -> finishEnrollment(save = true)
            ACTION_ENROLL_CANCEL -> finishEnrollment(save = false)
            ACTION_RELOAD -> reloadDetector()
            ACTION_WAKE_TEST_START -> wakeTests++
            ACTION_WAKE_TEST_STOP -> wakeTests = (wakeTests - 1).coerceAtLeast(0)
            ACTION_ENROLL_CLEAR -> clearEnrollment()
        }
        ensureCapture()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        wakeTests = 0
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
            while (isActive) {
                if (inCall()) {
                    delay(CALL_POLL_MILLIS)
                    continue
                }
                if (!capture()) break
            }
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

    private fun inCall(): Boolean = audio.mode in CALL_MODES

    @SuppressLint("MissingPermission")
    private suspend fun capture(): Boolean {
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
            return false
        }
        val pcm = ShortArray(AudioSpec.CHUNK_SAMPLES)
        val samples = FloatArray(AudioSpec.CHUNK_SAMPLES)
        var lastLevelPublish = 0L
        var lastCallCheck = 0L
        record.startRecording()
        VoiceBus.update { it.copy(inputDevice = record.routedDevice?.productName?.toString()) }
        try {
            while (kotlin.coroutines.coroutineContext.isActive) {
                val n = record.read(pcm, 0, pcm.size)
                if (n <= 0) continue
                if (BuildConfig.DEBUG) injected.poll()?.let { it.copyInto(pcm, 0, 0, minOf(it.size, n)) }
                val gain = graph.settings.current.micBoost
                var sum = 0.0
                for (i in 0 until n) {
                    if (gain != 1f) pcm[i] = (pcm[i] * gain).coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()
                    val s = pcm[i] / 32768f
                    samples[i] = s
                    sum += s * s
                }
                val chunk = if (n == samples.size) samples else samples.copyOf(n)
                val now = System.currentTimeMillis()
                if (now - lastCallCheck > CALL_POLL_MILLIS) {
                    lastCallCheck = now
                    if (inCall()) {
                        Logger.i("Call in progress, microphone released")
                        commandSink?.endpoint?.complete(Endpoint.CANCELLED)
                        VoiceBus.level(0f)
                        return true
                    }
                }
                if (now - lastLevelPublish > 60) {
                    VoiceBus.level(sqrt(sum / n).toFloat())
                    lastLevelPublish = now
                }
                when (mode) {
                    Mode.WAKE, Mode.BUSY -> detector?.accept(chunk, graph.settings.current.wakeThreshold)?.let { (score, fired) ->
                        if (fired) {
                            Logger.i("Wake word detected (score $score)")
                            VoiceBus.update { it.copy(lastWakeAt = now) }
                            if (wakeTests > 0) {
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
        return false
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

    private fun trigger(source: TriggerSource, readAloud: String? = null, followUp: Boolean = true) {
        if (mode == Mode.ENROLL) return
        if (inCall()) {
            Logger.i("Ignored $source trigger during a call")
            return
        }
        val previous = commandJob
        graph.agent.cancel()
        speaker.stop()
        commandJob = lifecycleScope.launch {
            previous?.cancelAndJoin()
            runCommand(source, readAloud, followUp)
        }
    }

    private fun cancelCommand() {
        commandJob?.cancel()
        graph.agent.cancel()
        speaker.stop()
    }

    private suspend fun runCommand(source: TriggerSource, readAloud: String?, followUp: Boolean) {
        acquireWakeLock()
        try {
            Logger.i("Voice turn from $source")
            graph.settings.current.let { s -> if (s.sendContext) lifecycleScope.launch { graph.device.prefetch(s.contextFields, s.language) } }
            graph.playback.hold()
            var text = if (readAloud == null) {
                listen(NO_SPEECH_MILLIS, earcon = true)
            } else {
                VoiceBus.update { it.copy(phase = VoicePhase.SPEAKING) }
                speak(readAloud)
                val seconds = graph.settings.current.followUpSeconds
                if (!followUp || seconds <= 0) return
                listen(seconds * 1000L, earcon = true).ifBlank { return }
            }
            while (true) {
                if (text.isBlank()) {
                    if (!inCall()) playEarcon(Earcon.ERROR)
                    break
                }
                VoiceBus.update { it.copy(phase = VoicePhase.THINKING, partial = text) }
                val turn = runWithHold(text)
                if (turn.quiet) break
                VoiceBus.update { it.copy(phase = VoicePhase.SPEAKING) }
                speak(if (turn.status == TurnStatus.FAILED) spoken(R.string.voice_error_check_chat) else turn.reply)
                val followUp = graph.settings.current.followUpSeconds
                if (turn.status != TurnStatus.ANSWERED || followUp <= 0) break
                text = listen(followUp * 1000L, earcon = true)
                if (text.isBlank()) break
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w("Voice turn failed", e)
            VoiceBus.update { it.copy(lastError = e.message) }
            playEarcon(Earcon.ERROR)
            speak(spoken(R.string.voice_error_spoken))
        } finally {
            mode = Mode.WAKE
            commandSink?.session?.cancel()
            commandSink = null
            VoiceBus.update { it.copy(phase = if (running) VoicePhase.WAITING else VoicePhase.OFF, partial = "") }
            withContext(NonCancellable) { graph.playback.release() }
            releaseWakeLock()
        }
    }

    private suspend fun runWithHold(text: String): Turn = coroutineScope {
        val lines = Channel<String>(Channel.UNLIMITED)
        var spoken = false
        var talking = false
        val voice = launch {
            for (line in lines) {
                spoken = true
                talking = true
                speak(line)
                talking = false
            }
        }
        val pulse = launch {
            while (true) {
                delay(PULSE_MILLIS)
                if (!talking && VoiceBus.status.value.phase == VoicePhase.THINKING) playEarcon(Earcon.PULSE)
            }
        }
        val hold = launch {
            delay(HOLD_MILLIS)
            if (!spoken && VoiceBus.status.value.phase == VoicePhase.THINKING) holdPhrase()?.let(lines::trySend)
        }
        val turn = graph.agent.run(
            text, TurnSource.VOICE,
            onProgress = { line ->
                hold.cancel()
                lines.trySend(line)
            },
            onQuiet = {
                hold.cancel()
                pulse.cancel()
                lines.close()
                voice.cancel()
                speaker.stop()
                VoiceBus.update { it.copy(phase = VoicePhase.WAITING, partial = "") }
            },
        )
        hold.cancel()
        pulse.cancel()
        lines.close()
        voice.join()
        turn
    }

    private suspend fun listen(noSpeechMillis: Long, earcon: Boolean): String {
        VoiceBus.update { it.copy(phase = VoicePhase.LISTENING, partial = "", lastError = null) }
        mode = Mode.BUSY
        if (earcon) playEarcon(Earcon.WAKE)
        val settings = graph.settings.current
        val engine = sttEngine()
        val session = try {
            engine.start(settings.language, settings.secondLanguage) { partial -> VoiceBus.update { it.copy(partial = partial) } }
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
        if (endpoint == Endpoint.NO_SPEECH || endpoint == Endpoint.CANCELLED) {
            session.cancel()
            return ""
        }
        lifecycleScope.launch { playEarcon(Earcon.WORKING) }
        VoiceBus.update { it.copy(phase = VoicePhase.TRANSCRIBING) }
        return runCatching { session.finish() }
            .onSuccess { VoiceBus.update { s -> s.copy(speechLanguageMissing = false) } }
            .onFailure {
                Logger.w("STT failed", it)
                val missing = (it as? SttException)?.languageMissing == true
                VoiceBus.update { s -> s.copy(lastError = it.message, speechLanguageMissing = missing || s.speechLanguageMissing) }
                if (missing) speak(spoken(R.string.voice_language_missing))
            }
            .getOrDefault("")
            .also { text -> Logger.i("Heard: $text") }
    }

    private suspend fun confirmByVoice(request: ConfirmationRequest) {
        val previous = VoiceBus.status.value.phase
        VoiceBus.update { it.copy(phase = VoicePhase.SPEAKING) }
        speak(request.prompt)
        repeat(2) {
            val answer = YesNo.parse(listen(CONFIRM_TIMEOUT_MILLIS, earcon = true))
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
        speech.withLock { speaker.speak(text, settings.language, settings.ttsVoice, settings.speechRate, CloudVoice.from(settings)) }
    }

    private fun holdPhrase(): String? {
        val phrases = graph.settings.current.holdPhrases ?: localizedResources().getStringArray(R.array.voice_hold_phrases).toList()
        return HoldPhrases.pick(phrases, lastHoldPhrase)?.also { lastHoldPhrase = it }
    }

    private fun localizedResources(): Resources = Languages.resources(this, graph.settings.current.language)

    private fun spoken(id: Int): String = localizedResources().getString(id)

    private suspend fun playEarcon(earcon: Earcon) {
        if (graph.settings.current.earcons) runCatching { Earcons.play(earcon) }
    }

    private suspend fun checkSpeechLanguage() {
        if (sttEngine() !is AndroidSpeechToText) return VoiceBus.update { it.copy(speechLanguageMissing = false) }
        val installed = AndroidSpeechToText.languageInstalled(this, graph.settings.current.language) ?: return
        VoiceBus.update { it.copy(speechLanguageMissing = !installed) }
    }

    private fun sttEngine(): SpeechToText {
        val settings = graph.settings.current
        val cloud = settings.sttCloud
        val remote = OpenAiSpeechToText(
            apiKey = { graph.settings.current.key(Credential.of(cloud.provider)) },
            baseUrl = cloud.baseUrl,
            model = settings.sttModel.ifBlank { cloud.models.first() },
        )
        return when {
            settings.sttEngine == SttEngine.CLOUD && remote.isAvailable() -> remote
            else -> AndroidSpeechToText(this)
        }
    }

    private fun clearEnrollment() {
        lifecycleScope.launch(Dispatchers.IO) {
            templates.clear()
            reloadDetectorNow()
        }
    }

    private fun startEnrollment(append: Boolean) {
        commandJob?.cancel()
        enrollAppend = append
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
                        templates.save(if (enrollAppend) WakeTemplates.merge(templates.load(), built) else built)
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
        const val ACTION_READ_ALOUD = "dev.marionette.voice.READ_ALOUD"
        const val EXTRA_TEXT = "text"
        const val EXTRA_FOLLOW_UP = "follow_up"
        const val ACTION_CANCEL = "dev.marionette.voice.CANCEL"
        const val ACTION_ENROLL_START = "dev.marionette.voice.ENROLL_START"
        const val EXTRA_APPEND = "append"
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
        private const val HOLD_MILLIS = 2_000L
        private const val PULSE_MILLIS = 1_600L
        private const val CONFIRM_TIMEOUT_MILLIS = 5_000L
        private const val MAX_COMMAND_MILLIS = 15_000L
        private const val TURN_WAKE_LOCK_MILLIS = 120_000L
        private const val SPEECH_PROBABILITY = 0.55f
        private const val SILENCE_PROBABILITY = 0.35f
        private const val CALL_POLL_MILLIS = 1_000L
        private val CALL_MODES = setOf(AudioManager.MODE_RINGTONE, AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_CALL_SCREENING)

        @Volatile
        var running = false
            private set

        @Volatile
        private var wakeTests = 0
        private val injected = ConcurrentLinkedQueue<ShortArray>()

        fun inject(pcm: ShortArray) {
            if (!BuildConfig.DEBUG) return
            for (start in pcm.indices step AudioSpec.CHUNK_SAMPLES) {
                injected += ShortArray(AudioSpec.CHUNK_SAMPLES).also { pcm.copyInto(it, 0, start, minOf(start + AudioSpec.CHUNK_SAMPLES, pcm.size)) }
            }
        }

        fun intent(context: Context, action: String): Intent = Intent(context, VoiceService::class.java).setAction(action)

        fun readAloud(context: Context, text: String, followUp: Boolean) =
            start(context, intent(context, ACTION_READ_ALOUD).putExtra(EXTRA_TEXT, text).putExtra(EXTRA_FOLLOW_UP, followUp))

        fun enroll(context: Context, append: Boolean) = start(context, intent(context, ACTION_ENROLL_START).putExtra(EXTRA_APPEND, append))

        fun send(context: Context, action: String, source: TriggerSource? = null) {
            val intent = intent(context, action)
            source?.let { intent.putExtra(EXTRA_SOURCE, it.ordinal) }
            start(context, intent)
        }

        private fun start(context: Context, intent: Intent) {
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Logger.w("Cannot start voice service for ${intent.action}", it) }
        }
    }
}
