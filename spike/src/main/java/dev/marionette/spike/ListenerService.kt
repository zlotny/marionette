package dev.marionette.spike

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.PowerManager
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.marionette.spike.audio.AcousticWakeDetector
import dev.marionette.spike.audio.FuzzyMatcher
import dev.marionette.spike.audio.PhraseRecognizer
import dev.marionette.spike.audio.SpeechEmbedder
import dev.marionette.spike.audio.UtteranceCounter
import dev.marionette.spike.audio.WakeWordEngine
import dev.marionette.spike.audio.WavReader
import dev.marionette.spike.launch.LaunchVariant
import dev.marionette.spike.launch.NavLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.sqrt

enum class Mode(val label: String) {
    WAKE_KWS("wake-A"),
    WAKE_ASR("wake-B"),
    WAKE_ACOUSTIC("wake-C"),
    ENROLL("enroll"),
}

class ListenerService : LifecycleService() {

    private val audioDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val prefs by lazy { Prefs(this) }
    private val tone by lazy { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90) }
    private var engine: WakeWordEngine? = null
    private var recognizer: PhraseRecognizer? = null
    private var embedder: SpeechEmbedder? = null
    private val templatesFile by lazy { File(filesDir, "templates.bin") }
    private var listenJob: Job? = null
    private var armJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            SpikeLog.log("USER_PRESENT pendingLaunch=${SpikeState.pendingUnlockLaunch.value}")
            if (SpikeState.pendingUnlockLaunch.value) {
                SpikeState.pendingUnlockLaunch.value = false
                NavLauncher.startMaps(this@ListenerService, "unlock fallback")
            }
        }
    }

    private val debugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_DEBUG_AUDIO -> intent.getStringExtra("path")?.let(::detectInFile)
                ACTION_DEBUG_FIRE -> fire(LaunchVariant.of(intent.getIntExtra("variant", prefs.variant)))
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        SpikeState.serviceRunning.value = true
        lifecycleScope.launch(Dispatchers.IO) { SpikeState.templateCount.value = AcousticWakeDetector.load(templatesFile).size }
        ContextCompat.registerReceiver(
            this, unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_EXPORTED,
        )
        if (BuildConfig.DEBUG) {
            ContextCompat.registerReceiver(
                this,
                debugReceiver,
                IntentFilter().apply {
                    addAction(ACTION_DEBUG_AUDIO)
                    addAction(ACTION_DEBUG_FIRE)
                },
                ContextCompat.RECEIVER_EXPORTED,
            )
        }
        SpikeLog.log("ListenerService started | ${DeviceState.summary(this)}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_LISTEN -> startListening(
                when (prefs.detector) {
                    Prefs.DETECTOR_ASR -> Mode.WAKE_ASR
                    Prefs.DETECTOR_ACOUSTIC -> Mode.WAKE_ACOUSTIC
                    else -> Mode.WAKE_KWS
                }
            )
            ACTION_ENROLL -> startListening(Mode.ENROLL)
            ACTION_UNLISTEN -> stopListening()
            ACTION_UPDATE_KEYWORD -> lifecycleScope.launch(audioDispatcher) { applyKeyword() }
            ACTION_ARM -> arm()
            ACTION_DISARM -> disarm()
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopListening()
        disarm()
        unregisterReceiver(unlockReceiver)
        if (BuildConfig.DEBUG) unregisterReceiver(debugReceiver)
        lifecycleScope.launch(audioDispatcher) {
            engine?.release()
            recognizer?.release()
            embedder?.close()
        }
        SpikeState.serviceRunning.value = false
        SpikeLog.log("ListenerService stopped")
        super.onDestroy()
    }

    private suspend fun ensureEngine(): WakeWordEngine? = engine ?: withContext(audioDispatcher) {
        val started = System.currentTimeMillis()
        loadSherpa("KWS") { WakeWordEngine(assets) }?.also {
            engine = it
            SpikeLog.log("KWS model loaded in ${System.currentTimeMillis() - started} ms")
            applyKeyword()
        }
    }

    private suspend fun ensureRecognizer(): PhraseRecognizer? = recognizer ?: withContext(audioDispatcher) {
        val started = System.currentTimeMillis()
        loadSherpa("ASR") { PhraseRecognizer(assets) }?.also {
            recognizer = it
            SpikeLog.log("ASR model loaded in ${System.currentTimeMillis() - started} ms")
        }
    }

    private fun <T> loadSherpa(name: String, create: () -> T): T? = try {
        create()
    } catch (e: LinkageError) {
        SpikeLog.log("$name unavailable: ${e.message}")
        null
    }

    private suspend fun ensureEmbedder(): SpeechEmbedder = embedder ?: withContext(audioDispatcher) {
        val started = System.currentTimeMillis()
        SpeechEmbedder(assets).also {
            embedder = it
            SpikeLog.log("Embedding models loaded in ${System.currentTimeMillis() - started} ms")
        }
    }

    private fun buildTemplates(pcm: ShortArray) {
        lifecycleScope.launch {
            val e = ensureEmbedder()
            withContext(Dispatchers.Default) {
                val started = System.currentTimeMillis()
                val templates = AcousticWakeDetector.buildTemplates(e, pcm)
                AcousticWakeDetector.save(templatesFile, templates)
                SpikeState.templateCount.value = templates.size
                SpikeLog.log("S2 C built ${templates.size} templates in ${System.currentTimeMillis() - started} ms")
            }
        }
    }

    private fun applyKeyword() {
        SpikeState.enrolled.value = prefs.enrolled
        val e = engine ?: return
        val variants = prefs.wakeVariants
        e.setKeywords(variants, prefs.boost, prefs.threshold)
            .onSuccess {
                SpikeState.keywordTokens.value = "${it.size} variants: ${it.joinToString(" / ")}"
                SpikeLog.log("S2 keywords ${it.size} variants from ${variants.size} phrases boost=${prefs.boost} threshold=${prefs.threshold}: $it")
            }
            .onFailure {
                SpikeState.keywordTokens.value = "error: ${it.message}"
                SpikeLog.log("S2 keywords rejected: ${it.message}")
            }
    }

    private fun startListening(mode: Mode) {
        if (listenJob?.isActive == true) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PERMISSION_GRANTED) {
            SpikeLog.log("S2 cannot listen: RECORD_AUDIO not granted")
            return
        }
        listenJob = lifecycleScope.launch {
            val consumer = consumerFor(mode)
            SpikeState.mode.value = mode.label
            try {
                withContext(audioDispatcher) { captureLoop(mode, consumer) }
            } finally {
                SpikeState.mode.value = null
                SpikeState.partialTranscript.value = ""
            }
        }
    }

    private suspend fun consumerFor(mode: Mode): (FloatArray) -> Unit = when (mode) {
        Mode.WAKE_KWS -> {
            val e = ensureEngine() ?: return {}
            val consume: (FloatArray) -> Unit = { s -> e.acceptMic(s)?.let { onDetected(it, "kws") } }
            consume
        }
        Mode.WAKE_ASR -> {
            val r = ensureRecognizer() ?: return {}
            val variants = prefs.wakeVariants
            val ratio = prefs.fuzzyRatio
            SpikeLog.log("S2 ASR detector: ${variants.size} variants, max distance ratio $ratio")
            val consume: (FloatArray) -> Unit = { s ->
                val update = r.accept(s)
                SpikeState.partialTranscript.value = update.partial
                update.final?.let { SpikeLog.log("S2 heard '$it'") }
                FuzzyMatcher.find(update.final ?: update.partial, variants, ratio)?.let {
                    r.reset()
                    onDetected("${it.variant} d=${it.distance}", "asr")
                }
            }
            consume
        }
        Mode.WAKE_ACOUSTIC -> {
            val templates = withContext(Dispatchers.IO) { AcousticWakeDetector.load(templatesFile) }
            val detector = AcousticWakeDetector(ensureEmbedder(), templates)
            val threshold = prefs.acousticThreshold
            SpikeLog.log("S2 C detector: ${detector.templateCount} templates, threshold $threshold")
            var windowBest = Float.POSITIVE_INFINITY
            var windowStart = System.currentTimeMillis()
            val consume: (FloatArray) -> Unit = { s ->
                detector.accept(s, threshold)?.let { (score, fired) ->
                    windowBest = minOf(windowBest, score)
                    if (fired) onDetected("acoustic score ${"%.3f".format(java.util.Locale.US, score)}", "acoustic")
                }
                if (System.currentTimeMillis() - windowStart >= LEVEL_LOG_MILLIS) {
                    SpikeLog.log("S2 C best score ${"%.3f".format(java.util.Locale.US, windowBest)}")
                    windowBest = Float.POSITIVE_INFINITY
                    windowStart = System.currentTimeMillis()
                }
            }
            consume
        }
        Mode.ENROLL -> {
            val counter = UtteranceCounter(WakeWordEngine.SAMPLE_RATE)
            SpikeState.enrollHeard.value = 0
            val consume: (FloatArray) -> Unit = { s ->
                if (counter.accept(s)) {
                    SpikeState.enrollHeard.value += 1
                    tone.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
                }
            }
            consume
        }
    }

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private suspend fun captureLoop(mode: Mode, consume: (FloatArray) -> Unit) {
        val rate = WakeWordEngine.SAMPLE_RATE
        val chunk = rate / 10
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val useBluetooth = prefs.bluetoothMic && android.os.Build.VERSION.SDK_INT >= 31
        val audio = getSystemService(AudioManager::class.java)
        val previousMode = audio.mode
        if (useBluetooth) audio.mode = AudioManager.MODE_IN_COMMUNICATION
        val record = AudioRecord(
            if (useBluetooth) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION,
            rate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, chunk * 2 * 4),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            SpikeLog.log("S2 AudioRecord init FAILED")
            record.release()
            audio.mode = previousMode
            return
        }
        val bluetooth = useBluetooth && routeToBluetooth(record)
        record.startRecording()
        delay(300)
        val device = record.routedDevice
        SpikeState.inputDevice.value = device?.let { "${DeviceState.deviceTypeName(it.type)} (${it.productName})" } ?: "unknown"
        SpikeState.listening.value = true
        SpikeState.batteryStart.value = DeviceState.battery(this)
        SpikeLog.log("S2 listening on ${SpikeState.inputDevice.value} battery=${SpikeState.batteryStart.value}")
        val pcm = ShortArray(chunk)
        val samples = FloatArray(chunk)
        val capture = ShortArray(rate * CAPTURE_SECONDS)
        var captured = 0
        var windowPeak = 0f
        var windowStart = System.currentTimeMillis()
        try {
            while (kotlin.coroutines.coroutineContext.isActive) {
                val n = record.read(pcm, 0, chunk)
                if (n <= 0) continue
                if (captured < capture.size) {
                    val copy = minOf(n, capture.size - captured)
                    pcm.copyInto(capture, captured, 0, copy)
                    captured += copy
                }
                var sum = 0.0
                for (i in 0 until n) {
                    val s = pcm[i] / 32768f
                    samples[i] = s
                    sum += s * s
                }
                val rms = sqrt(sum / n).toFloat()
                SpikeState.micLevel.value = rms
                windowPeak = maxOf(windowPeak, rms)
                if (System.currentTimeMillis() - windowStart >= LEVEL_LOG_MILLIS) {
                    SpikeLog.log("S2 level peak ${"%.1f".format(20 * kotlin.math.log10(windowPeak + 1e-9f))} dBFS")
                    windowPeak = 0f
                    windowStart = System.currentTimeMillis()
                }
                consume(if (n == chunk) samples else samples.copyOf(n))
            }
        } finally {
            record.stop()
            record.release()
            if (bluetooth) releaseBluetooth()
            audio.mode = previousMode
            if (captured > 0) saveCapture(capture.copyOf(captured), mode, device?.type)
            if (mode == Mode.ENROLL && captured > 0) buildTemplates(capture.copyOf(captured))
            SpikeState.listening.value = false
            SpikeState.micLevel.value = 0f
            val start = SpikeState.batteryStart.value
            val end = DeviceState.battery(this)
            SpikeLog.log("S2 stopped listening. battery start=$start end=$end")
        }
    }

    private fun saveCapture(pcm: ShortArray, mode: Mode, deviceType: Int?) {
        val device = deviceType?.let(DeviceState::deviceTypeName) ?: "unknown"
        val name = "capture-${System.currentTimeMillis()}-${mode.label}-$device.wav"
        val file = File(getExternalFilesDir(null), name)
        runCatching { WavReader.writePcm16Mono16k(file, pcm) }
            .onSuccess { SpikeLog.log("S2 saved ${pcm.size / WakeWordEngine.SAMPLE_RATE}s to $name") }
            .onFailure { SpikeLog.log("S2 capture save FAILED: $it") }
    }

    private fun routeToBluetooth(record: AudioRecord): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 31) return false
        val audio = getSystemService(AudioManager::class.java)
        val comm = audio.availableCommunicationDevices.firstOrNull { it.type in BLUETOOTH_MIC_TYPES }
        if (comm == null) {
            SpikeLog.log("S2 no Bluetooth communication device available")
            return false
        }
        val ok = audio.setCommunicationDevice(comm)
        val input = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == comm.type }
        val preferred = input?.let { record.setPreferredDevice(it) } ?: false
        SpikeLog.log(
            "S2 Bluetooth mic: setCommunicationDevice(${DeviceState.deviceTypeName(comm.type)} ${comm.productName})=$ok " +
                "preferredInput=$preferred mode=${audio.mode}"
        )
        return ok
    }

    private fun releaseBluetooth() {
        if (android.os.Build.VERSION.SDK_INT < 31) return
        getSystemService(AudioManager::class.java).clearCommunicationDevice()
        SpikeLog.log("S2 Bluetooth mic released")
    }

    private fun stopListening() {
        listenJob?.cancel()
        listenJob = null
    }

    private fun onDetected(keyword: String, source: String) {
        tone.startTone(ToneGenerator.TONE_PROP_ACK, 200)
        SpikeState.detections.update { listOf(Detection(System.currentTimeMillis(), keyword, source)) + it }
        SpikeLog.log("S2 DETECTED '$keyword' via $source | ${DeviceState.summary(this)}")
        if (prefs.launchOnDetection && source != "file") fire(LaunchVariant.of(prefs.variant))
    }

    private fun detectInFile(path: String) {
        lifecycleScope.launch {
            val e = ensureEngine() ?: return@launch
            withContext(Dispatchers.Default) {
                val file = File(path).takeIf { it.isAbsolute } ?: File(getExternalFilesDir(null), path)
                runCatching { WavReader.readPcm16Mono16k(file) }
                    .onFailure { SpikeLog.log("S2 file ${file.path} unreadable: ${it.message}") }
                    .onSuccess { samples ->
                        val hits = e.detectInFile(samples)
                        SpikeLog.log("S2 file ${file.name} (${samples.size / WakeWordEngine.SAMPLE_RATE}s): ${hits.size} hits $hits")
                        hits.forEach { onDetected(it.second, "file") }
                    }
            }
        }
    }

    private fun arm() {
        disarm()
        val variant = LaunchVariant.of(prefs.variant)
        val seconds = prefs.delaySeconds
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "marionette:arm")
            .apply { acquire((seconds + 30) * 1000L) }
        SpikeLog.log("S1 armed V${variant.number} in ${seconds}s")
        armJob = lifecycleScope.launch {
            for (left in seconds downTo 1) {
                SpikeState.armedSecondsLeft.value = left
                delay(1000)
            }
            SpikeState.armedSecondsLeft.value = null
            fire(variant)
            wakeLock?.takeIf { it.isHeld }?.release()
        }
    }

    private fun disarm() {
        armJob?.cancel()
        armJob = null
        SpikeState.armedSecondsLeft.value = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun fire(variant: LaunchVariant) {
        if (prefs.fallbackOnUnlock) SpikeState.pendingUnlockLaunch.value = true
        NavLauncher.fire(this, variant)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ListenerService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, SpikeApp.CHANNEL_LISTENER)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Marionette spike")
            .setContentText("Listener service running")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CAPTURE_SECONDS = 180
        private const val LEVEL_LOG_MILLIS = 5000L
        private val BLUETOOTH_MIC_TYPES = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)
        private const val PERMISSION_GRANTED = android.content.pm.PackageManager.PERMISSION_GRANTED
        const val ACTION_LISTEN = "dev.marionette.spike.LISTEN"
        const val ACTION_ENROLL = "dev.marionette.spike.ENROLL"
        const val ACTION_UNLISTEN = "dev.marionette.spike.UNLISTEN"
        const val ACTION_UPDATE_KEYWORD = "dev.marionette.spike.UPDATE_KEYWORD"
        const val ACTION_ARM = "dev.marionette.spike.ARM"
        const val ACTION_DISARM = "dev.marionette.spike.DISARM"
        const val ACTION_STOP = "dev.marionette.spike.STOP"
        const val ACTION_DEBUG_AUDIO = "dev.marionette.DEBUG_AUDIO"
        const val ACTION_DEBUG_FIRE = "dev.marionette.DEBUG_FIRE"

        fun send(context: Context, action: String) {
            ContextCompat.startForegroundService(context, Intent(context, ListenerService::class.java).setAction(action))
        }
    }
}
