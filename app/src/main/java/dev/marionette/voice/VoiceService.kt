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
import android.net.NetworkCapabilities
import android.net.Network
import android.net.ConnectivityManager
import dev.marionette.agent.Delivery
import dev.marionette.audio.AudioRoute
import dev.marionette.audio.Port
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
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
import dev.marionette.assist.AssistantSession
import dev.marionette.assist.MarionetteVoiceInteractionService
import dev.marionette.core.Http
import dev.marionette.core.LinkSpeed
import dev.marionette.core.Logger
import dev.marionette.settings.Credential
import dev.marionette.settings.SttEngine
import dev.marionette.ui.MainActivity
import dev.marionette.voice.stt.AndroidSpeechToText
import dev.marionette.voice.stt.FallbackSpeechToText
import dev.marionette.voice.stt.JunkTranscript
import dev.marionette.voice.stt.OpenAiSpeechToText
import dev.marionette.voice.stt.SpeechToText
import dev.marionette.voice.stt.SttException
import dev.marionette.voice.stt.SttSession
import dev.marionette.voice.wake.AcousticWakeDetector
import dev.marionette.voice.wake.FalseWakes
import dev.marionette.voice.wake.SpeechEmbedder
import dev.marionette.voice.wake.StopPhrases
import dev.marionette.voice.wake.StopText
import dev.marionette.voice.wake.UtteranceCounter
import dev.marionette.voice.wake.WakeHitRecorder
import dev.marionette.voice.wake.WakeSpeech
import dev.marionette.voice.wake.WakeTemplates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalTime
import java.util.concurrent.ConcurrentLinkedQueue
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.math.sqrt

class VoiceService : LifecycleService() {

    private enum class Mode { WAKE, COMMAND, ENROLL, BUSY }

    private enum class Endpoint { SPEECH_END, NO_SPEECH, MAX_LENGTH, CANCELLED }

    private val graph get() = MarionetteApp.graph
    private val templates by lazy { WakeTemplates(this) }
    private val userSpeech by lazy { WakeSpeech(this) }
    private val hits by lazy { WakeHitRecorder(WakeHitRecorder.dir(this)) }
    private val falseWakes by lazy { FalseWakes(this) }
    private val stopPhrases by lazy { StopPhrases(this) }
    private val speaker by lazy { Speaker(this, graph.audioRoutes) }
    private val cues by lazy { CueChannel(this, graph.scope) }
    private val audio by lazy { getSystemService(AudioManager::class.java) }

    private val micExecutor = Executors.newSingleThreadExecutor { Thread(it, "mic") }
    private val micDispatcher = micExecutor.asCoroutineDispatcher()
    private val pace by lazy { MicPace(this, AudioSpec.CHUNK_SAMPLES * 1_000_000_000L / AudioSpec.SAMPLE_RATE) }
    private val detectorLock = Mutex()
    private val speech = Mutex()
    private val micDemand = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var quietSkipUntil = 0L
    private var quietCheckedAt = Long.MIN_VALUE / 2
    private var quietCached = false
    private var quietKey = 0 to 0
    private val marking = Mutex()
    private val leveler = VoiceLeveler()

    @Volatile private var mode = Mode.WAKE
    @Volatile private var turnEndedAt = 0L
    @Volatile private var detector: AcousticWakeDetector? = null
    private var pendingWake: Job? = null
    @Volatile private var commandSink: CommandSink? = null
    @Volatile private var record: AudioRecord? = null
    @Volatile private var requestMic: (() -> Unit)? = null
    @Volatile private var slowNoticeGiven = false
    private class Resend(val text: String?, val audio: ShortArray?, val at: Long, val attempts: Int = 0)
    @Volatile private var resending = false
    @Volatile private var failedAudio: ShortArray? = null
    @Volatile private var warmOnSpeech = false
    @Volatile private var latestPartial = ""
    @Volatile private var confirming = false
    @Volatile private var resend: Resend? = null
    private var resendJob: Job? = null
    private val resendSignal = Channel<Unit>(Channel.CONFLATED)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        private var validated = true
        private var current: Network? = null

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val now = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (now && current != null && network != current) Http.dropConnections()
            if (now) current = network
            if (now && !validated && resend != null) resendSignal.trySend(Unit)
            validated = now
        }

        override fun onLost(network: Network) {
            validated = false
            if (network == current) Http.cancelAgentCalls()
            Http.dropConnections()
        }
    }
    private var embedder: SpeechEmbedder? = null
    @Volatile private var vad: Vad? = null
    private var captureJob: Job? = null
    private var commandJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val enrollBuffer = ShortArray(AudioSpec.SAMPLE_RATE * MAX_ENROLL_SECONDS)
    private var enrollSamples = 0
    private var enrollAppend = false
    private var enrollStop: Pair<String, String>? = null
    private var enrollSpeech = false
    private var lastHoldPhrase: String? = null
    private var enrollCounter: UtteranceCounter? = null
    private var enrollMutedUntil = 0L

    private class CommandSink(
        val session: SttSession,
        val noSpeechMillis: Long,
        val beepMillis: Long = 0,
        val quickAnswer: Boolean = false,
        val endpoint: CompletableDeferred<Endpoint> = CompletableDeferred(),
        val resumed: CompletableDeferred<Unit> = CompletableDeferred(),
        @Volatile var inGrace: Boolean = false,
        var resumedSpeechMillis: Long = 0,
        var speechStarted: Boolean = false,
        var silenceMillis: Long = 0,
        var elapsedMillis: Long = 0,
    )

    override fun onCreate() {
        super.onCreate()
        running = true
        speaker.warmUp()
        graph.confirmations.voiceHandler = ::confirmByVoice
        lifecycleScope.launch(guard) {
            VoiceBus.status.map { it.phase }.distinctUntilChanged().collect { updateNotification() }
        }
        lifecycleScope.launch(guard) {
            graph.settings.settings.map { listOf(it.listeningPaused, it.notificationTalk, it.quietHours, it.quietStart, it.quietEnd) }.distinctUntilChanged().collect {
                micDemand.trySend(Unit)
                updateNotification()
            }
        }
        lifecycleScope.launch(guard) {
            graph.settings.settings.map { Triple(it.language, it.sttEngine, it.sttCloud) }.distinctUntilChanged().collect { checkSpeechLanguage() }
        }
        runCatching { getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback) }
        lifecycleScope.launch(guard) {
            combine(graph.audioRoutes.devices, graph.audioRoutes.connected) { _, _ -> }.collect { if (mode == Mode.WAKE) useMic(graph.audioRoutes.route().wakeInput) }
        }
    }

    private fun useMic(port: Port?) {
        val current = record ?: return
        current.setPreferredDevice(graph.audioRoutes.info(port))
        VoiceBus.update { it.copy(inputDevice = (current.preferredDevice ?: current.routedDevice)?.productName?.toString()) }
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
            ACTION_PAUSE -> if (SystemClock.elapsedRealtime() - turnEndedAt < PAUSE_GRACE_MILLIS) Logger.i("Pause ignored right after a turn") else setPaused(true)
            ACTION_RESUME -> {
                skipQuietHours()
                setPaused(false)
            }
            ACTION_READ_ALOUD -> intent.getStringExtra(EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let {
                trigger(TriggerSource.BUTTON, readAloud = it, followUp = intent.getBooleanExtra(EXTRA_FOLLOW_UP, false))
            }
            ACTION_CANCEL -> cancelCommand()
            ACTION_ENROLL_START -> startEnrollment(
                append = intent.getBooleanExtra(EXTRA_APPEND, false),
                stop = intent.getStringExtra(EXTRA_STOP_ID)?.let { it to intent.getStringExtra(EXTRA_STOP_LABEL).orEmpty() },
                speech = intent.getBooleanExtra(EXTRA_SPEECH, false),
            )
            ACTION_STOP_PHRASE_DELETE -> intent.getStringExtra(EXTRA_STOP_ID)?.let(::deleteStopPhrase)
            ACTION_STOP_TEST_START -> stopTests++
            ACTION_STOP_TEST_STOP -> stopTests = (stopTests - 1).coerceAtLeast(0)
            ACTION_ENROLL_FINISH -> finishEnrollment(save = true)
            ACTION_ENROLL_CANCEL -> finishEnrollment(save = false)
            ACTION_RELOAD -> reloadDetector()
            ACTION_WAKE_TEST_START -> wakeTests++
            ACTION_WAKE_TEST_STOP -> wakeTests = (wakeTests - 1).coerceAtLeast(0)
            ACTION_ENROLL_CLEAR -> clearEnrollment()
            ACTION_MARK_FALSE -> markWake(intent.getStringExtra(EXTRA_HIT), intent.getBooleanExtra(EXTRA_FALSE, true))
        }
        ensureCapture()
        micDemand.trySend(Unit)
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        wakeTests = 0
        stopTests = 0
        graph.confirmations.voiceHandler = null
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
        resendJob?.cancel()
        commandJob?.cancel()
        captureJob?.cancel()
        commandSink?.session?.cancel()
        cues.closeNow()
        speaker.shutdown()
        val sessions = listOfNotNull(embedder, vad)
        micExecutor.execute {
            sessions.forEach { runCatching { it.close() } }
            pace.close()
        }
        micExecutor.shutdown()
        hits.close()
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
        captureJob = lifecycleScope.launch(micDispatcher + guard) {
            pace.attach()
            reloadDetectorNow()
            VoiceBus.update { it.copy(lastError = null) }
            while (isActive) {
                if (inCall()) {
                    delay(CALL_POLL_MILLIS)
                    continue
                }
                if (idlePaused()) {
                    VoiceBus.level(0f)
                    VoiceBus.update { it.copy(phase = VoicePhase.PAUSED) }
                    Logger.i("Listening paused, microphone released")
                    while (idlePaused()) withTimeoutOrNull(PAUSE_POLL_MILLIS) { micDemand.receive() }
                    continue
                }
                VoiceBus.update {
                    val idle = it.phase == VoicePhase.OFF || it.phase == VoicePhase.PAUSED
                    it.copy(phase = if (mode == Mode.ENROLL) VoicePhase.ENROLLING else if (idle) VoicePhase.WAITING else it.phase)
                }
                val captured = try {
                    capture()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.e("Microphone capture failed", e)
                    false
                }
                if (captured) {
                    micRetryMillis = MIC_RETRY_MIN_MILLIS
                } else {
                    Logger.w("Microphone unavailable, retrying in $micRetryMillis ms")
                    delay(micRetryMillis)
                    micRetryMillis = (micRetryMillis * 2).coerceAtMost(MIC_RETRY_MAX_MILLIS)
                }
            }
        }
    }

    private var micRetryMillis = MIC_RETRY_MIN_MILLIS
    private val guard = CoroutineExceptionHandler { _, e -> Logger.e("Voice service task failed", e) }

    private fun idlePaused(): Boolean =
        (graph.settings.current.listeningPaused || quietNow()) && mode == Mode.WAKE && commandJob?.isActive != true && wakeTests == 0 && stopTests == 0

    private fun quietNow(): Boolean {
        val s = graph.settings.current
        if (!s.quietHours) return false
        val now = SystemClock.elapsedRealtime()
        if (now < quietSkipUntil) return false
        if (now - quietCheckedAt >= QUIET_CHECK_MILLIS || quietKey != s.quietStart to s.quietEnd) {
            val time = LocalTime.now()
            quietCached = QuietHours.contains(time.hour * 60 + time.minute, s.quietStart, s.quietEnd)
            quietCheckedAt = now
            quietKey = s.quietStart to s.quietEnd
        }
        return quietCached
    }

    private fun skipQuietHours() {
        val s = graph.settings.current
        val time = LocalTime.now()
        val minute = time.hour * 60 + time.minute
        if (!s.quietHours || !QuietHours.contains(minute, s.quietStart, s.quietEnd)) return
        quietSkipUntil = SystemClock.elapsedRealtime() + QuietHours.minutesUntilEnd(minute, s.quietEnd) * 60_000L
        quietCheckedAt = Long.MIN_VALUE / 2
        Logger.i("Quiet hours skipped until ${QuietHours.label(s.quietEnd)}")
    }

    private fun setPaused(paused: Boolean) {
        Logger.i("Listening ${if (paused) "paused" else "resumed"} from the notification")
        lifecycleScope.launch(guard) { graph.settings.update { it.copy(listeningPaused = paused) } }
    }

    private fun reloadDetector() {
        lifecycleScope.launch(Dispatchers.IO + guard) { reloadDetectorNow() }
    }

    private suspend fun reloadDetectorNow() = detectorLock.withLock {
        val settings = graph.settings.current
        val loaded = templates.load()
        if (vad == null) vad = runCatching { Vad(assets) }.onFailure { Logger.e("VAD load failed", it) }.getOrNull()
        val embedder = embedder ?: runCatching { SpeechEmbedder(assets) }.onFailure { Logger.e("Embedder load failed", it) }.getOrNull()
        this.embedder = embedder
        val wake = if (settings.wakeEnabled) loaded else emptyList()
        val stops = stopPhrases.loadAll()
        val speech = userSpeech.load()
        val talk = speech + falseWakes.load()
        val minLevel = templates.minPhraseDbfs()
        detector = if ((wake.isNotEmpty() || stops.isNotEmpty()) && embedder != null) AcousticWakeDetector(embedder, wake, stops, talk, minLevel) else null
        val armed = detector?.wakeArmed == true
        val usable = detector?.wakeUsable ?: 0
        VoiceBus.update {
            it.copy(templateCount = loaded.size, usableTemplates = usable, speechRecordings = speech.size, falseWakes = falseWakes.count(), wakeArmed = armed, stopPhrases = stopPhrases.list())
        }
        Logger.i("Wake detector: ${loaded.size} templates, $usable usable, speech ${talk.size}, min level $minLevel dBFS, armed=$armed, stop templates ${stops.size}")
    }

    private fun inCall(): Boolean = audio.mode in RINGING_MODES || (requestMic == null && audio.mode in CALL_MODES)

    @SuppressLint("MissingPermission")
    private suspend fun capture(): Boolean {
        val minBuffer = AudioRecord.getMinBufferSize(AudioSpec.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            AudioSpec.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, AudioSpec.SAMPLE_RATE * 2 * RECORD_BUFFER_SECONDS),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            VoiceBus.update { it.copy(lastError = getString(R.string.error_mic_unavailable)) }
            return false
        }
        val pcm = ShortArray(AudioSpec.CHUNK_SAMPLES)
        val samples = FloatArray(AudioSpec.CHUNK_SAMPLES)
        val leveledPcm = ShortArray(AudioSpec.CHUNK_SAMPLES)
        val leveled = FloatArray(AudioSpec.CHUNK_SAMPLES)
        var lastLevelPublish = 0L
        var lastCallCheck = 0L
        val echo = if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(record.audioSessionId) }.getOrNull() else null
        Logger.i("Echo canceller: available=${AcousticEchoCanceler.isAvailable()}, attached=${echo != null}")
        var echoOn = false
        record.startRecording()
        detector?.reset()
        this.record = record
        useMic(graph.audioRoutes.route().wakeInput)
        try {
            while (kotlin.coroutines.coroutineContext.isActive) {
                val n = record.read(pcm, 0, pcm.size)
                if (n < 0) {
                    Logger.w("Microphone read failed ($n), reopening")
                    return false
                }
                if (n == 0) continue
                if (idlePaused()) return true
                val workStart = System.nanoTime()
                if (BuildConfig.DEBUG) {
                    injected.poll()?.let { it.copyInto(pcm, 0, 0, minOf(it.size, n)) }
                    tap?.invoke(pcm, n)
                }
                var sum = 0.0
                for (i in 0 until n) {
                    val s = pcm[i] / 32768f
                    samples[i] = s
                    sum += s * s
                }
                val chunk = if (n == samples.size) samples else samples.copyOf(n)
                val levelVoice = graph.settings.current.levelVoice
                val leveling = mode == Mode.COMMAND && levelVoice
                if (levelVoice && mode == Mode.WAKE || leveling) leveler.process(chunk, n, if (leveling) leveled else null)
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
                val busy = when (mode) {
                    Mode.WAKE -> graph.agent.busy.value
                    Mode.ENROLL -> enrollStop != null
                    else -> true
                }
                if (echo != null && busy != echoOn) {
                    echoOn = busy
                    runCatching { echo.enabled = busy }.onFailure { Logger.w("Echo canceller toggle failed", it) }
                }
                if (now - lastLevelPublish > 60) {
                    VoiceBus.level(sqrt(sum / n).toFloat())
                    lastLevelPublish = now
                }
                when (mode) {
                    Mode.WAKE, Mode.BUSY -> detector?.let { d ->
                        val working = mode == Mode.BUSY || graph.agent.busy.value
                        hits.push(pcm, n)
                        d.accept(chunk, graph.settings.current.wakeThreshold, listenForStop = working || stopTests > 0)?.let { hit ->
                            val echo = hit.stop && agentSaidStop()
                            hits.record(hit, now, idle = !working, echo = echo)
                            if (hit.stop && !echo) onStopPhrase(now, working)
                            if (hit.wake) onWakeWord(hit.score, now, deferred = working && d.stopArmed)
                        }
                    }
                    Mode.COMMAND -> {
                        commandSink?.let { sink ->
                            if (leveling) {
                                for (i in 0 until n) leveledPcm[i] = (leveled[i] * 32768f).coerceIn(-32768f, 32767f).toInt().toShort()
                                feedCommand(sink, leveledPcm, n, if (n == leveled.size) leveled else leveled.copyOf(n))
                            } else {
                                feedCommand(sink, pcm, n, chunk)
                            }
                        }
                        hits.push(pcm, n)
                        val hit = detector?.accept(chunk, graph.settings.current.wakeThreshold, listenForStop = true)
                        val echo = hit?.stop == true && agentSaidStop()
                        if (hit?.stop == true) hits.record(hit, now, echo = echo)
                        if (hit?.stop == true && !echo) onStopPhrase(now, working = true)
                    }
                    Mode.ENROLL -> feedEnrollment(pcm, n, chunk)
                }
                pace.report(System.nanoTime() - workStart, n)
            }
        } finally {
            this.record = null
            echo?.release()
            runCatching { record.stop() }
            record.release()
        }
        return false
    }

    private fun onWakeWord(score: Float, now: Long, deferred: Boolean) {
        Logger.i("Wake word detected (score $score)")
        if (stopTests > 0) return
        if (speaker.spokePhrase(StopText.words(graph.settings.current.wakePhrase).toSet())) {
            Logger.i("Wake ignored: the agent just said the wake phrase")
            return
        }
        VoiceBus.update { it.copy(lastWakeAt = now) }
        if (wakeTests > 0) {
            lifecycleScope.launch(guard) { playEarcon(Earcon.DONE) }
            return
        }
        lifecycleScope.launch(guard) {
            pendingWake?.cancel()
            pendingWake = lifecycleScope.launch(guard) {
                if (deferred) delay(STOP_GRACE_MILLIS)
                trigger(TriggerSource.WAKE_WORD)
            }
        }
    }

    private fun onStopPhrase(now: Long, working: Boolean) {
        Logger.i("Stop phrase detected, working=$working")
        VoiceBus.update { it.copy(lastStopAt = now) }
        lifecycleScope.launch(guard) {
            pendingWake?.cancel()
            dropResend()
            if (working) {
                cancelCommand()
                AssistantSession.hideShown()
            }
            playEarcon(if (working) Earcon.ERROR else Earcon.DONE)
        }
    }

    private fun feedCommand(sink: CommandSink, pcm: ShortArray, n: Int, chunk: FloatArray) {
        if (sink.endpoint.isCompleted && !sink.inGrace) return
        sink.session.accept(pcm, n)
        val vad = vad ?: runCatching { Vad(assets) }.onFailure { Logger.e("VAD load failed", it) }.getOrNull()?.also { vad = it }
        val detected = vad?.accept(chunk) ?: energyProbability(chunk)
        val chunkMillis = n * 1000L / AudioSpec.SAMPLE_RATE
        val probability = if (sink.elapsedMillis < sink.beepMillis) (SILENCE_PROBABILITY + SPEECH_PROBABILITY) / 2 else detected
        if (sink.endpoint.isCompleted) {
            sink.resumedSpeechMillis = if (probability >= SPEECH_PROBABILITY) sink.resumedSpeechMillis + chunkMillis else 0
            if (sink.resumedSpeechMillis >= RESUME_SPEECH_MILLIS) sink.resumed.complete(Unit)
            return
        }
        sink.elapsedMillis += chunkMillis
        val silenceLimit = graph.settings.current.silenceMillis.toLong()
        if (probability >= SILENCE_PROBABILITY && sink.silenceMillis >= SPECULATE_MILLIS) sink.session.dropSpeculation()
        if (probability >= SPEECH_PROBABILITY) {
            if (warmOnSpeech) {
                warmOnSpeech = false
                lifecycleScope.launch(guard) { graph.agent.warmUp() }
            }
            sink.speechStarted = true
            sink.silenceMillis = 0
        } else if (sink.speechStarted && probability < SILENCE_PROBABILITY) {
            sink.silenceMillis += chunkMillis
            if (sink.silenceMillis >= SPECULATE_MILLIS && silenceLimit > SPECULATE_MILLIS) sink.session.speculate()
        }
        when {
            sink.speechStarted && sink.silenceMillis >= silenceLimit -> sink.endpoint.complete(Endpoint.SPEECH_END)
            sink.quickAnswer && sink.speechStarted && sink.silenceMillis >= QUICK_ANSWER_SILENCE_MILLIS && isBareAnswer(latestPartial) ->
                sink.endpoint.complete(Endpoint.SPEECH_END)
            !sink.speechStarted && sink.elapsedMillis >= sink.noSpeechMillis -> sink.endpoint.complete(Endpoint.NO_SPEECH)
            sink.elapsedMillis >= MAX_COMMAND_MILLIS -> sink.endpoint.complete(Endpoint.MAX_LENGTH)
        }
    }

    private fun isBareAnswer(text: String): Boolean = YesNo.parse(text) == true && YesNo.isBare(text)

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
        VoiceBus.update { it.copy(lastTrigger = source) }
        graph.agent.cancel()
        speaker.stop()
        commandJob = lifecycleScope.launch(guard) {
            previous?.cancelAndJoin()
            runCommand(source, readAloud, followUp)
        }
        micDemand.trySend(Unit)
        val settings = graph.settings.current
        if (settings.listeningPaused) lifecycleScope.launch(guard) { graph.settings.update { it.copy(listeningPaused = false) } }
        skipQuietHours()
        if (settings.wakeOverlay && source in OVERLAY_SOURCES) showOverlay(screenOn = source == TriggerSource.WAKE_WORD && settings.wakeScreenOn)
    }

    private fun showOverlay(screenOn: Boolean, followUp: Boolean = false) {
        val service = MarionetteVoiceInteractionService.instance ?: return
        val args = Bundle().apply {
            putBoolean(AssistantSession.EXTRA_ATTACH, true)
            putBoolean(AssistantSession.EXTRA_SCREEN_ON, screenOn)
            putBoolean(AssistantSession.EXTRA_FOLLOW_UP, followUp)
        }
        runCatching { service.showSession(args, 0) }.onFailure { Logger.w("Overlay showSession failed", it) }
    }

    private fun cancelCommand() {
        Logger.i("Turn cancelled")
        Logger.ux("cancel")
        VoiceBus.update { it.copy(lastCancelAt = SystemClock.elapsedRealtime()) }
        dropResend()
        commandJob?.cancel()
        graph.agent.cancel()
        speaker.stop()
    }

    private suspend fun runCommand(source: TriggerSource, readAloud: String?, followUp: Boolean) {
        acquireWakeLock()
        try {
            Logger.i("Voice turn from $source")
            Logger.ux("turn_start", source)
            slowNoticeGiven = false
            graph.settings.current.let { s ->
                if (s.sendContext) lifecycleScope.launch(guard) { graph.device.prefetch(s.contextFields, s.language) }
                Http.warm(listOfNotNull(s.sttCloud.baseUrl.takeIf { s.sttEngine == SttEngine.CLOUD }, s.baseUrl, s.ttsCloud.baseUrl.takeIf { s.cloudSpeech }))
                warmOnSpeech = true
            }
            graph.playback.hold()
            graph.audioRoutes.route().let { route -> cues.open(route, graph.audioRoutes.info(route.output)) }
            var text = if (readAloud == null) {
                listen(NO_SPEECH_MILLIS, earcon = true)
            } else {
                VoiceBus.update { it.copy(phase = VoicePhase.SPEAKING) }
                speak(readAloud)
                val seconds = graph.settings.current.followUpSeconds
                if (!followUp || seconds <= 0) return
                listen(seconds * 1000L, earcon = true).also { if (it.isBlank() && failedAudio == null) return }
            }
            val settings = graph.settings.current
            if (source == TriggerSource.WAKE_WORD && readAloud == null && text.isNotBlank() &&
                JunkTranscript.isJunk(text, listOf(settings.language) + settings.extraLanguages)
            ) {
                Logger.content("Wake turn dropped, no request in", text)
                playEarcon(Earcon.ERROR)
                return
            }
            while (true) {
                acquireWakeLock()
                val unsent = failedAudio
                failedAudio = null
                if (text.isBlank() && unsent != null) {
                    queueResend(Resend(null, unsent, SystemClock.elapsedRealtime()))
                    speak(spoken(R.string.voice_resend_queued))
                    break
                }
                if (text.isBlank()) {
                    if (!inCall()) playEarcon(Earcon.ERROR)
                    break
                }
                if (isStopText(text)) {
                    Logger.content("Stop phrase in transcript", text)
                    dropResend()
                    playEarcon(Earcon.ERROR)
                    break
                }
                VoiceBus.update { it.copy(phase = VoicePhase.THINKING, partial = text) }
                val turn = runWithHold(text)
                if (turn.quiet) break
                VoiceBus.update { it.copy(phase = VoicePhase.SPEAKING) }
                if (!turn.undelivered) resend = null
                if (turn.undelivered) {
                    queueResend(Resend(text, null, SystemClock.elapsedRealtime()))
                    speak(spoken(R.string.voice_resend_queued))
                    break
                }
                if (!turn.replySpoken) speak(if (turn.status == TurnStatus.FAILED) turn.reply.ifBlank { spoken(R.string.voice_error_check_chat) } else turn.reply)
                val followUp = graph.settings.current.followUpSeconds
                if (turn.status != TurnStatus.ANSWERED || followUp <= 0) break
                if (graph.settings.current.wakeOverlay && !AssistantSession.isShown && getSystemService(PowerManager::class.java).isInteractive) showOverlay(screenOn = false, followUp = true)
                Logger.ux("follow_up", followUp)
                text = listen(followUp * 1000L, earcon = true)
                if (text.isBlank()) {
                    playEarcon(Earcon.DONE)
                    break
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w("Voice turn failed", e)
            VoiceBus.update { it.copy(lastError = e.message) }
            playEarcon(Earcon.ERROR)
            runCatching { speak(spoken(R.string.voice_error_spoken)) }
        } finally {
            Logger.i("Voice turn done")
            Logger.ux("turn_end")
            cues.close()
            mode = Mode.WAKE
            turnEndedAt = SystemClock.elapsedRealtime()
            commandSink?.session?.cancel()
            commandSink = null
            VoiceBus.update { it.copy(phase = if (running) VoicePhase.WAITING else VoicePhase.OFF, partial = "") }
            micDemand.trySend(Unit)
            withContext(NonCancellable) { graph.playback.release() }
            releaseWakeLock()
        }
    }

    private suspend fun runWithHold(text: String): Turn = coroutineScope {
        val lines = Channel<String>(Channel.UNLIMITED)
        var spoken = false
        var talking = false
        var holdLine: String? = null
        var current: String? = null
        val voice = launch {
            for (line in lines) {
                if (confirming) continue
                spoken = true
                talking = true
                current = line
                speak(line)
                current = null
                talking = false
            }
        }
        val pulse = launch {
            waitingCues(onlyWhen = { !talking && VoiceBus.status.value.phase == VoicePhase.THINKING && (!resending || graph.agent.delivery.value == Delivery.ACCEPTED) })
        }
        val hold = launch {
            delay(graph.settings.current.holdSeconds * 1000L)
            if (!spoken && VoiceBus.status.value.phase == VoicePhase.THINKING && (!resending || graph.agent.delivery.value == Delivery.ACCEPTED)) {
                holdPhrase()?.let {
                    holdLine = it
                    lines.trySend(it)
                }
            }
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
        if (current != null && current == holdLine) speaker.stop()
        voice.join()
        turn
    }

    private suspend fun listen(noSpeechMillis: Long, earcon: Boolean, quickAnswer: Boolean = false): String {
        VoiceBus.update { it.copy(phase = VoicePhase.LISTENING, partial = "", lastError = null) }
        mode = Mode.BUSY
        val route = graph.audioRoutes.route()
        requestMic = graph.audioRoutes.openRequestMic(route)
        useMic(route.requestInput)
        try {
            val beepMillis = if (earcon && graph.settings.current.earcons) Earcons.durationMillis(Earcon.WAKE, route, graph.audioRoutes.info(route.output)) else 0L
            return transcribe(noSpeechMillis, beepMillis, quickAnswer) { if (earcon) lifecycleScope.launch(guard) { playEarcon(Earcon.WAKE, route) } }
        } finally {
            useMic(graph.audioRoutes.route().wakeInput)
            requestMic?.invoke()
            requestMic = null
        }
    }

    private suspend fun transcribe(noSpeechMillis: Long, beepMillis: Long, quickAnswer: Boolean, onListening: () -> Unit): String {
        val settings = graph.settings.current
        val engine = sttEngine()
        latestPartial = ""
        val session = try {
            engine.start(settings.language, settings.extraLanguages) { partial ->
                latestPartial = partial
                VoiceBus.update { it.copy(partial = partial) }
            }
        } catch (e: Exception) {
            Logger.w("STT start failed", e)
            VoiceBus.update { it.copy(lastError = e.message) }
            return ""
        }
        vad?.reset()
        var sink = CommandSink(session, noSpeechMillis, beepMillis = beepMillis, quickAnswer = quickAnswer)
        var early: Deferred<Result<String>>? = null
        var grace: Deferred<Result<String>>? = null
        try {
            commandSink = sink
            mode = Mode.COMMAND
            onListening()
            while (true) {
                commandSink = sink
                mode = Mode.COMMAND
                val endpoint = sink.endpoint.await()
                Logger.i("Endpoint $endpoint after ${sink.elapsedMillis} ms")
                if (endpoint == Endpoint.NO_SPEECH || endpoint == Endpoint.CANCELLED) {
                    session.cancel()
                    return ""
                }
                if (endpoint != Endpoint.SPEECH_END || !session.resumable) break
                sink.inGrace = true
                val pending = lifecycleScope.async { runCatching { session.finish() } }
                grace = pending
                val graceStart = SystemClock.elapsedRealtime()
                val resumed = withTimeoutOrNull(RESUME_GRACE_MILLIS) {
                    while (!sink.resumed.isCompleted && !(pending.isCompleted && SystemClock.elapsedRealtime() - graceStart >= MIN_GRACE_MILLIS)) delay(GRACE_POLL_MILLIS)
                    sink.resumed.isCompleted
                } == true
                if (!resumed) {
                    sink.inGrace = false
                    early = pending
                    break
                }
                pending.cancel()
                session.dropSpeculation()
                Logger.i("Speech resumed after the endpoint")
                sink = CommandSink(session, noSpeechMillis, quickAnswer = quickAnswer, speechStarted = true, elapsedMillis = sink.elapsedMillis)
            }
        } catch (e: CancellationException) {
            grace?.cancel()
            session.cancel()
            throw e
        } finally {
            mode = Mode.BUSY
            commandSink = null
        }
        val cues = lifecycleScope.launch(guard) {
            playEarcon(Earcon.WORKING)
            waitingCues { true }
        }
        VoiceBus.update { it.copy(phase = VoicePhase.TRANSCRIBING) }
        val transcribing = System.currentTimeMillis()
        return runCatching {
            try {
                early?.await()?.getOrThrow() ?: session.finish()
            } catch (e: CancellationException) {
                early?.cancel()
                session.cancel()
                throw e
            } finally {
                cues.cancel()
            }
        }
            .onFailure { if (it is CancellationException) throw it }
            .also { Logger.i("Transcript ready ${System.currentTimeMillis() - transcribing} ms after endpoint") }
            .onSuccess { VoiceBus.update { s -> s.copy(speechLanguageMissing = false) } }
            .onFailure {
                Logger.w("STT failed", it)
                val missing = (it as? SttException)?.languageMissing == true
                VoiceBus.update { s -> s.copy(lastError = it.message, speechLanguageMissing = missing || s.speechLanguageMissing) }
                failedAudio = session.recorded()?.takeIf { _ -> !missing && it is IOException }
                if (failedAudio == null) speak(spoken(if (missing) R.string.voice_language_missing else R.string.voice_stt_failed))
            }
            .getOrDefault("")
            .let { text -> if (JunkTranscript.isHallucination(text)) "".also { Logger.i("Dropped a known STT hallucination") } else text }
            .also { text -> Logger.content("Heard", text) }
    }

    private fun agentSaidStop(): Boolean {
        val s = graph.settings.current
        val ignored = (StopText.words(s.agentName) + StopText.words(s.wakePhrase)).toSet()
        val words = VoiceBus.status.value.stopPhrases.flatMap { StopText.words(it.label) }.filter { it !in ignored }.toSet()
        return speaker.spokeRecently(words).also { if (it) Logger.i("Stop phrase ignored: the agent just said it") }
    }

    private fun isStopText(text: String): Boolean {
        val s = graph.settings.current
        return StopText.matches(text, VoiceBus.status.value.stopPhrases.map { it.label }, listOf(s.agentName, s.wakePhrase))
    }

    private suspend fun confirmByVoice(request: ConfirmationRequest) {
        confirming = true
        try {
            askByVoice(request)
        } finally {
            confirming = false
        }
    }

    private suspend fun askByVoice(request: ConfirmationRequest) {
        val previous = VoiceBus.status.value.phase
        VoiceBus.update { it.copy(phase = VoicePhase.SPEAKING) }
        speak(request.prompt)
        repeat(2) {
            val heard = listen(CONFIRM_TIMEOUT_MILLIS, earcon = true, quickAnswer = true)
            if (failedAudio != null) {
                failedAudio = null
                speak(spoken(R.string.voice_stt_failed))
                return@repeat
            }
            val answer = YesNo.parse(heard)
            if (answer == true && YesNo.isBare(heard)) {
                request.answer.complete(true)
                VoiceBus.update { it.copy(phase = previous) }
                return
            }
            if (answer != null || YesNo.isCorrection(heard)) {
                val correction = when (answer) {
                    true -> true
                    false -> YesNo.changesRequest(heard)
                    null -> true
                }
                if (correction && !YesNo.isBare(heard)) request.remark = heard.trim()
                request.answer.complete(false)
                VoiceBus.update { it.copy(phase = previous) }
                return
            }
        }
        request.answer.complete(false)
        VoiceBus.update { it.copy(phase = previous) }
    }

    private suspend fun speak(raw: String) {
        val ticket = speaker.ticket()
        val text = SpeechText.clean(raw)
        if (text.isBlank()) return
        val settings = graph.settings.current
        val language = if (settings.extraLanguages.isEmpty() || text.length < SHORT_REPLY_CHARS) settings.language else {
            ReplyLanguage.detect(this, text, listOf(settings.language) + settings.extraLanguages)
                .takeIf { it == settings.language || speaker.speaks(it) } ?: settings.language
        }
        val voice = settings.ttsVoice.takeIf { language == settings.language }.orEmpty()
        val cloud = CloudVoice.from(settings)?.takeUnless { LinkSpeed.slow(this) }
        speech.withLock {
            Logger.ux("speak_start", text.length)
            try {
                graph.audioRoutes.route().let { route -> speaker.speak(text, language, voice, settings.speechRate, cloud, route, ticket, route.speechGain(settings.phoneVolume)) }
            } finally {
                Logger.ux("speak_end")
            }
        }
    }

    private suspend fun waitingCues(onlyWhen: () -> Boolean) {
        val started = SystemClock.elapsedRealtime()
        while (true) {
            delay(PULSE_MILLIS)
            if (!onlyWhen()) continue
            val delivery = graph.agent.delivery.value
            val waiting = VoiceBus.status.value.phase == VoicePhase.TRANSCRIBING || delivery == Delivery.SENDING || delivery == Delivery.RETRYING
            playEarcon(if (waiting) Earcon.WAITING else Earcon.PULSE)
            val slow = delivery == Delivery.RETRYING || SystemClock.elapsedRealtime() - started > SLOW_NETWORK_MILLIS
            if (waiting && slow && !slowNoticeGiven) {
                slowNoticeGiven = true
                speak(spoken(R.string.voice_network_slow))
            }
        }
    }

    private fun dropResend() {
        resend?.let { Logger.content("Queued resend dropped", it.text ?: "audio") }
        resend = null
    }

    private fun queueResend(pending: Resend, now: Boolean = true) {
        resend = pending
        if (now) resendSignal.trySend(Unit)
        if (resendJob?.isActive == true) return
        resendJob = lifecycleScope.launch(guard) {
            while (true) {
                val waiting = resend ?: break
                if (SystemClock.elapsedRealtime() - waiting.at > RESEND_WINDOW_MILLIS) {
                    Logger.i("Resend window over, dropping: ${waiting.text ?: "audio"}")
                    resend = null
                    break
                }
                withTimeoutOrNull(RESEND_BACKOFF_MILLIS[minOf(waiting.attempts, RESEND_BACKOFF_MILLIS.lastIndex)]) { resendSignal.receive() }
                val next = resend ?: break
                if (commandJob?.isActive == true || !networkOnline()) continue
                resend = null
                commandJob = lifecycleScope.launch(guard) { runResend(next) }
                commandJob?.join()
            }
        }
    }

    private suspend fun runResend(pending: Resend) {
        acquireWakeLock()
        resending = true
        try {
            Logger.content("Resending (attempt ${pending.attempts + 1})", pending.text ?: "${pending.audio?.size} samples")
            val text = pending.text ?: pending.audio?.let { audio ->
                transcribeLater(audio) ?: return queueResend(Resend(null, audio, pending.at, pending.attempts + 1), now = false)
            } ?: return
            if (text.isBlank()) return
            graph.playback.hold()
            val turn = coroutineScope {
                val announce = launch {
                    if (pending.text == null) speak(spoken(R.string.voice_resend_now))
                    else graph.agent.delivery.first { it == Delivery.ACCEPTED }.also { speak(spoken(R.string.voice_resend_now)) }
                }
                VoiceBus.update { it.copy(phase = VoicePhase.THINKING, partial = text) }
                runWithHold(text).also { announce.cancel() }
            }
            if (turn.undelivered) return queueResend(Resend(text, null, pending.at, pending.attempts + 1), now = false)
            if (turn.quiet) return
            VoiceBus.update { it.copy(phase = VoicePhase.SPEAKING) }
            if (!turn.replySpoken) speak(turn.reply)
        } finally {
            resending = false
            mode = Mode.WAKE
            VoiceBus.update { it.copy(phase = if (running) VoicePhase.WAITING else VoicePhase.OFF, partial = "") }
            withContext(NonCancellable) { graph.playback.release() }
            releaseWakeLock()
        }
    }

    private suspend fun transcribeLater(audio: ShortArray): String? {
        val settings = graph.settings.current
        return try {
            val session = sttEngine().start(settings.language, settings.extraLanguages) {}
            var offset = 0
            while (offset < audio.size) {
                val size = minOf(AudioSpec.CHUNK_SAMPLES, audio.size - offset)
                session.accept(audio.copyOfRange(offset, offset + size), size)
                offset += size
            }
            session.finish().also { Logger.content("Heard later", it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Logger.i("Late transcription still offline")
            null
        } catch (e: Exception) {
            Logger.w("Late transcription failed", e)
            ""
        }
    }

    private fun networkOnline(): Boolean {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun holdPhrase(): String? {
        val phrases = graph.settings.current.holdPhrases ?: localizedResources().getStringArray(R.array.voice_hold_phrases).toList()
        return HoldPhrases.pick(phrases, lastHoldPhrase)?.also { lastHoldPhrase = it }
    }

    private fun localizedResources(): Resources = Languages.resources(this, graph.settings.current.language)

    private fun spoken(id: Int): String = localizedResources().getString(id)

    private suspend fun playEarcon(earcon: Earcon, route: AudioRoute = graph.audioRoutes.route()) {
        if (graph.settings.current.earcons) runCatching { Earcons.play(earcon, route, graph.audioRoutes.info(route.output), graph.settings.current.let { route.cueGain(it.phoneVolume, it.earconVolume) }) }
            .onFailure { if (it !is CancellationException) Logger.w("Earcon $earcon failed", it) }
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
            baseUrl = sttUrlOverride?.takeIf { BuildConfig.DEBUG } ?: cloud.baseUrl,
            model = settings.sttModel.ifBlank { cloud.models.first() },
            cacheDir = cacheDir,
        )
        return when {
            settings.sttEngine == SttEngine.CLOUD && remote.isAvailable() -> FallbackSpeechToText(remote, AndroidSpeechToText(this))
            else -> AndroidSpeechToText(this)
        }
    }

    private fun clearEnrollment() {
        lifecycleScope.launch(Dispatchers.IO + guard) {
            templates.clear()
            userSpeech.clear()
            falseWakes.clear()
            reloadDetectorNow()
        }
    }

    private fun markWake(key: String?, falseWake: Boolean) {
        lifecycleScope.launch(Dispatchers.IO + guard) { marking.withLock { markWakeNow(key, falseWake) } }
    }

    private suspend fun markWakeNow(key: String?, falseWake: Boolean) {
        val dir = WakeHitRecorder.dir(this) ?: return
        val hit = (if (key == null) WakeHitRecorder.lastWake(dir) else WakeHitRecorder.find(dir, key)) ?: return
        val marked = WakeHitRecorder.mark(hit, falseWake) ?: return
        if (falseWake) {
            val frames = withContext(Dispatchers.Default) {
                val e = detectorLock.withLock { embedder ?: SpeechEmbedder(assets).also { embedder = it } }
                e.embeddings(WakeHitRecorder.samples(marked.file))
            }
            falseWakes.add(marked.key, frames)
        } else {
            falseWakes.remove(marked.key)
        }
        Logger.i("Wake ${marked.key} marked ${if (falseWake) "false" else "true"}")
        reloadDetectorNow()
        VoiceBus.update { it.copy(wakeMarks = it.wakeMarks + 1) }
    }

    private fun deleteStopPhrase(id: String) {
        lifecycleScope.launch(Dispatchers.IO + guard) {
            stopPhrases.delete(id)
            reloadDetectorNow()
        }
    }

    private fun startEnrollment(append: Boolean, stop: Pair<String, String>?, speech: Boolean) {
        val turn = commandJob
        if (turn?.isActive == true) {
            lifecycleScope.launch(guard) {
                turn.cancelAndJoin()
                startEnrollment(append, stop, speech)
            }
            return
        }
        enrollAppend = append
        enrollStop = stop
        enrollSpeech = speech
        enrollSamples = 0
        enrollCounter = UtteranceCounter(AudioSpec.SAMPLE_RATE)
        mode = Mode.ENROLL
        VoiceBus.update { it.copy(phase = VoicePhase.ENROLLING, enrolledPhrases = 0, enrollRejected = false, enrollingSpeech = speech) }
    }

    private fun feedEnrollment(pcm: ShortArray, n: Int, chunk: FloatArray) {
        if (SystemClock.elapsedRealtime() < enrollMutedUntil) return
        val copy = minOf(n, enrollBuffer.size - enrollSamples)
        if (copy > 0) {
            pcm.copyInto(enrollBuffer, enrollSamples, 0, copy)
            enrollSamples += copy
        }
        if (!enrollSpeech && enrollCounter?.accept(chunk) == true) {
            VoiceBus.update { it.copy(enrolledPhrases = it.enrolledPhrases + 1) }
            if (graph.settings.current.earcons) {
                enrollMutedUntil = SystemClock.elapsedRealtime() + EARCON_MUTE_MILLIS
                lifecycleScope.launch(guard) { runCatching { Earcons.play(Earcon.DONE) } }
            }
        }
        if (enrollSamples >= enrollBuffer.size) lifecycleScope.launch(guard) { finishEnrollment(save = true) }
    }

    private fun finishEnrollment(save: Boolean) {
        if (mode != Mode.ENROLL) return
        mode = Mode.BUSY
        val audio = enrollBuffer.copyOf(enrollSamples)
        enrollCounter = null
        lifecycleScope.launch(guard) {
            if (save && audio.isNotEmpty() && enrollSpeech) {
                val frames = withContext(Dispatchers.Default) {
                    val e = detectorLock.withLock { embedder ?: SpeechEmbedder(assets).also { embedder = it } }
                    e.embeddings(FloatArray(audio.size) { audio[it].toFloat() })
                }
                withContext(Dispatchers.IO) {
                    userSpeech.add(frames)
                    saveSample(audio, "speech")
                }
                Logger.i("Speech recording: ${frames.size} frames")
            } else if (save && audio.isNotEmpty()) {
                val built = withContext(Dispatchers.Default) {
                    val e = detectorLock.withLock { embedder ?: SpeechEmbedder(assets).also { embedder = it } }
                    AcousticWakeDetector.buildTemplates(e, audio)
                }
                if (built.isNotEmpty()) {
                    val stop = enrollStop
                    withContext(Dispatchers.IO) {
                        if (stop != null) {
                            stopPhrases.save(stop.first, stop.second, built, enrollAppend)
                        } else {
                            if (!enrollAppend) templates.clearSamples()
                            templates.save(if (enrollAppend) WakeTemplates.merge(templates.load(), built) else built)
                            saveSample(audio, "enroll")
                        }
                    }
                }
                if (built.isEmpty()) VoiceBus.update { it.copy(enrollRejected = true) }
                Logger.i("Enrollment built ${built.size} templates")
            }
            withContext(Dispatchers.IO) { reloadDetectorNow() }
            mode = Mode.WAKE
            VoiceBus.update { it.copy(phase = VoicePhase.WAITING) }
        }
    }

    private fun saveSample(pcm: ShortArray, kind: String) {
        val bytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { bytes.putShort(it) }
        val name = "$kind-${System.currentTimeMillis()}.wav"
        val temp = File(templates.samplesDir, "$name.tmp")
        temp.writeBytes(OpenAiSpeechToText.wav(bytes.array()))
        temp.renameTo(File(templates.samplesDir, name))
    }

    private fun acquireWakeLock() {
        val lock = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "marionette:turn")
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        lock.acquire(TURN_WAKE_LOCK_MILLIS)
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
        val text = when (status.phase) {
            VoicePhase.LISTENING -> getString(R.string.notification_listening)
            VoicePhase.TRANSCRIBING, VoicePhase.THINKING -> getString(R.string.notification_thinking)
            VoicePhase.SPEAKING -> getString(R.string.notification_speaking)
            VoicePhase.ENROLLING -> getString(R.string.notification_enrolling)
            VoicePhase.PAUSED -> getString(R.string.notification_paused)
            else -> if (status.wakeArmed) getString(R.string.notification_waiting_wake) else getString(R.string.notification_waiting)
        }
        val builder = NotificationCompat.Builder(this, MarionetteApp.CHANNEL_LISTENER)
            .setSmallIcon(if (status.phase == VoicePhase.PAUSED) R.drawable.ic_notification_paused else R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        when (status.phase) {
            VoicePhase.LISTENING, VoicePhase.TRANSCRIBING, VoicePhase.THINKING, VoicePhase.SPEAKING ->
                builder.addAction(0, getString(R.string.action_cancel), serviceIntent(5, ACTION_CANCEL))
            VoicePhase.PAUSED -> builder.addAction(0, getString(R.string.action_listen), serviceIntent(4, ACTION_RESUME))
            VoicePhase.ENROLLING -> Unit
            else -> builder.addAction(0, getString(R.string.action_pause), serviceIntent(3, ACTION_PAUSE))
        }
        if (graph.settings.current.notificationTalk) {
            val talk = intent(this, ACTION_TALK).putExtra(EXTRA_SOURCE, TriggerSource.NOTIFICATION.ordinal)
            builder.addAction(0, getString(R.string.action_talk), PendingIntent.getForegroundService(this, 1, talk, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        builder.addAction(0, getString(R.string.action_turn_off), serviceIntent(2, ACTION_STOP))
        return builder.build()
    }

    private fun serviceIntent(code: Int, action: String): PendingIntent =
        PendingIntent.getForegroundService(this, code, intent(this, action), PendingIntent.FLAG_IMMUTABLE)

    companion object {
        private const val PAUSE_POLL_MILLIS = 60_000L
        private const val QUIET_CHECK_MILLIS = 30_000L
        const val ACTION_START = "dev.marionette.voice.START"
        const val ACTION_STOP = "dev.marionette.voice.STOP"
        const val ACTION_TALK = "dev.marionette.voice.TALK"
        const val ACTION_PAUSE = "dev.marionette.voice.PAUSE"
        const val ACTION_RESUME = "dev.marionette.voice.RESUME"
        const val ACTION_READ_ALOUD = "dev.marionette.voice.READ_ALOUD"
        const val EXTRA_TEXT = "text"
        const val EXTRA_HIT = "hit"
        const val EXTRA_FALSE = "false_wake"
        const val EXTRA_FOLLOW_UP = "follow_up"
        const val ACTION_CANCEL = "dev.marionette.voice.CANCEL"
        const val ACTION_ENROLL_START = "dev.marionette.voice.ENROLL_START"
        const val EXTRA_APPEND = "append"
        const val ACTION_ENROLL_FINISH = "dev.marionette.voice.ENROLL_FINISH"
        const val ACTION_ENROLL_CANCEL = "dev.marionette.voice.ENROLL_CANCEL"
        const val ACTION_RELOAD = "dev.marionette.voice.RELOAD"
        const val ACTION_ENROLL_CLEAR = "dev.marionette.voice.ENROLL_CLEAR"
        const val ACTION_MARK_FALSE = "dev.marionette.voice.MARK_FALSE"
        const val ACTION_WAKE_TEST_START = "dev.marionette.voice.WAKE_TEST_START"
        const val ACTION_WAKE_TEST_STOP = "dev.marionette.voice.WAKE_TEST_STOP"
        const val ACTION_STOP_TEST_START = "dev.marionette.voice.STOP_TEST_START"
        const val ACTION_STOP_TEST_STOP = "dev.marionette.voice.STOP_TEST_STOP"
        const val ACTION_STOP_PHRASE_DELETE = "dev.marionette.voice.STOP_PHRASE_DELETE"
        const val EXTRA_STOP_ID = "stop_id"
        const val EXTRA_STOP_LABEL = "stop_label"
        const val EXTRA_SPEECH = "speech"
        const val EXTRA_SOURCE = "source"

        private const val NOTIFICATION_ID = 7
        private const val MAX_ENROLL_SECONDS = 90
        private const val EARCON_MUTE_MILLIS = 700L
        private const val NO_SPEECH_MILLIS = 6_000L
        private const val PULSE_MILLIS = 1_600L
        private const val SLOW_NETWORK_MILLIS = 5_000L
        private const val RESUME_GRACE_MILLIS = 700L
        private const val MIC_RETRY_MIN_MILLIS = 500L
        private const val MIC_RETRY_MAX_MILLIS = 30_000L
        private const val MIN_GRACE_MILLIS = 350L
        private const val GRACE_POLL_MILLIS = 25L
        private const val RESEND_WINDOW_MILLIS = 5 * 60_000L
        private val RESEND_BACKOFF_MILLIS = longArrayOf(1_000L, 15_000L, 30_000L, 60_000L)
        private const val RESUME_SPEECH_MILLIS = 160L
        private const val CONFIRM_TIMEOUT_MILLIS = 5_000L
        private const val MAX_COMMAND_MILLIS = 120_000L
        private const val TURN_WAKE_LOCK_MILLIS = 5 * 60_000L
        private val IDLE_ONLY_ACTIONS = setOf(ACTION_STOP, ACTION_CANCEL)
        private const val SPEECH_PROBABILITY = 0.55f
        private const val SILENCE_PROBABILITY = 0.35f
        private const val SPECULATE_MILLIS = 320L
        private const val QUICK_ANSWER_SILENCE_MILLIS = 250L
        private const val CALL_POLL_MILLIS = 1_000L
        private const val STOP_GRACE_MILLIS = 600L
        private const val PAUSE_GRACE_MILLIS = 1_000L
        private val OVERLAY_SOURCES = setOf(TriggerSource.WAKE_WORD, TriggerSource.HEADSET, TriggerSource.NOTIFICATION)
        private const val SHORT_REPLY_CHARS = 20
        private const val RECORD_BUFFER_SECONDS = 3
        private val RINGING_MODES = setOf(AudioManager.MODE_RINGTONE, AudioManager.MODE_IN_CALL, AudioManager.MODE_CALL_SCREENING)
        private val CALL_MODES = setOf(AudioManager.MODE_RINGTONE, AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_CALL_SCREENING)

        @Volatile
        var running = false
            private set

        @Volatile
        private var wakeTests = 0

        @Volatile
        private var stopTests = 0
        private val injected = ConcurrentLinkedQueue<ShortArray>()

        @Volatile
        var tap: ((ShortArray, Int) -> Unit)? = null

        @Volatile
        var sttUrlOverride: String? = null

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

        fun enrollSpeech(context: Context) = start(context, intent(context, ACTION_ENROLL_START).putExtra(EXTRA_SPEECH, true))

        fun enrollStopPhrase(context: Context, id: String, label: String, append: Boolean) = start(
            context,
            intent(context, ACTION_ENROLL_START).putExtra(EXTRA_APPEND, append).putExtra(EXTRA_STOP_ID, id).putExtra(EXTRA_STOP_LABEL, label),
        )

        fun markWake(context: Context, hit: String? = null, falseWake: Boolean = true) =
            start(context, intent(context, ACTION_MARK_FALSE).putExtra(EXTRA_HIT, hit).putExtra(EXTRA_FALSE, falseWake))

        fun deleteStopPhrase(context: Context, id: String) = start(context, intent(context, ACTION_STOP_PHRASE_DELETE).putExtra(EXTRA_STOP_ID, id))

        fun send(context: Context, action: String, source: TriggerSource? = null) {
            val intent = intent(context, action)
            source?.let { intent.putExtra(EXTRA_SOURCE, it.ordinal) }
            start(context, intent)
        }

        private fun start(context: Context, intent: Intent) {
            if (!running && intent.action in IDLE_ONLY_ACTIONS) return
            runCatching { if (running) context.startService(intent) else ContextCompat.startForegroundService(context, intent) }
                .onFailure { Logger.w("Cannot start voice service for ${intent.action}", it) }
        }
    }
}
