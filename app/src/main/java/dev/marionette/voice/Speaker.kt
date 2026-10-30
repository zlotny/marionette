package dev.marionette.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import dev.marionette.audio.AudioRoute
import dev.marionette.audio.AudioRoutes
import dev.marionette.core.Logger
import dev.marionette.voice.wake.StopText
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class Speaker(private val context: Context, private val routes: AudioRoutes? = null) {
    private val voiceCache = java.util.concurrent.ConcurrentHashMap<String, android.speech.tts.Voice>()

    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ready = CompletableDeferred<Boolean>()
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    @Volatile private var stopSignal = CompletableDeferred<Unit>()
    @Volatile private var systemText = ""
    @Volatile private var cloudChunk = ""
    @Volatile private var epoch = 0
    @Volatile private var rangeBase = -1
    private val spoken = ArrayDeque<Pair<Long, String>>()
    private val audio = context.getSystemService(AudioManager::class.java)
    private val defaultAttributes = attributes(AudioAttributes.USAGE_ASSISTANT)

    fun warmUp() {
        if (tts != null) return
        val init = CompletableDeferred<Boolean>().also { ready = it }
        tts = TextToSpeech(context) { status ->
            init.complete(status == TextToSpeech.SUCCESS)
        }.apply {
            setAudioAttributes(defaultAttributes)
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                    systemText.takeIf { end <= it.length }?.let { remember(it.substring(start, end)) }
                    val base = rangeBase
                    if (base >= 0) {
                        VoiceBus.speech { it.copy(spokenTo = base + start, aheadTo = base + end) }
                        VoiceBus.speechLevel(0.55f + 0.45f * ((end - start) / 8f).coerceAtMost(1f))
                    }
                }
                override fun onDone(utteranceId: String?) {
                    utteranceId?.let { waiting.remove(it)?.complete(Unit) }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    utteranceId?.let { waiting.remove(it)?.complete(Unit) }
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    utteranceId?.let { waiting.remove(it)?.complete(Unit) }
                }
            })
        }
    }

    fun spokePhrase(words: Set<String>): Boolean {
        if (words.isEmpty()) return false
        val since = System.currentTimeMillis() - ECHO_MILLIS
        val heard = StopText.words(cloudChunk).toMutableSet()
        synchronized(spoken) { spoken.filter { it.first >= since }.forEach { heard += StopText.words(it.second) } }
        return heard.containsAll(words)
    }

    fun spokeRecently(words: Set<String>): Boolean {
        if (words.isEmpty()) return false
        if (StopText.words(cloudChunk).any { it in words }) return true
        val since = System.currentTimeMillis() - ECHO_MILLIS
        return synchronized(spoken) { spoken.any { (at, text) -> at >= since && StopText.words(text).any { it in words } } }
    }

    private fun remember(text: String) = synchronized(spoken) {
        val now = System.currentTimeMillis()
        spoken.addLast(now to text)
        while (spoken.isNotEmpty() && spoken.first().first < now - ECHO_MILLIS) spoken.removeFirst()
    }

    suspend fun voices(): List<Voice> {
        warmUp()
        if (!ready.await()) return emptyList()
        return runCatching { tts?.voices?.filterNot { it.isNetworkConnectionRequired }?.sortedBy { it.name } }.getOrNull().orEmpty()
    }

    suspend fun speaks(language: String): Boolean {
        warmUp()
        if (!ready.await()) return false
        val locale = language.takeIf { it.isNotBlank() }?.let(Locale::forLanguageTag) ?: Locale.getDefault()
        return (tts?.isLanguageAvailable(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED) >= TextToSpeech.LANG_AVAILABLE
    }

    fun ticket(): Int = epoch

    suspend fun speak(text: String, language: String, voiceName: String, rate: Float, cloud: CloudVoice? = null, route: AudioRoute = AudioRoute.PHONE, ticket: Int = epoch, gain: Float = 1f) {
        if (text.isBlank()) return
        val stop = CompletableDeferred<Unit>().also { stopSignal = it }
        if (ticket != epoch || audio.mode in CALL_MODES) return
        val output = Output(route, attributes(route.speechUsage), routes?.info(route.output), gain) { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop.complete(Unit)
        }
        VoiceBus.speech { SpeechProgress(text, rate = rate) }
        Logger.i("Speech route: ${route.device?.id ?: "phone"}, usage ${route.speechUsage}, output ${route.output?.type}")
        Logger.ux("route", "${route.device?.id ?: "phone"} ${route.speechUsage} ${route.output?.type}")
        try {
            if (cloud != null) {
                audio.requestAudioFocus(output.focus)
                val rest = try {
                    speakCloud(text, cloud, rate, stop, output)
                } finally {
                    audio.abandonAudioFocusRequest(output.focus)
                }
                if (rest.isEmpty() || stop.isCompleted) return
                speakSystem(rest.joinToString(" "), language, voiceName, rate, output, stop, base = text.indexOf(rest.first().take(12)).coerceAtLeast(0))
                return
            }
            speakSystem(text, language, voiceName, rate, output, stop)
        } finally {
            rangeBase = -1
            VoiceBus.speechLevel(0f)
            VoiceBus.speech { SpeechProgress() }
        }
    }

    private class Output(val route: AudioRoute, val attributes: AudioAttributes, val device: AudioDeviceInfo?, val gain: Float, onFocus: (Int) -> Unit = {}) {
        val focus: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(onFocus)
            .build()
        val tailMillis: Long get() = Earcons.latencyMillis(route, device).toLong()
    }

    private suspend fun speakCloud(text: String, cloud: CloudVoice, rate: Float, stop: CompletableDeferred<Unit>, output: Output): List<String> = coroutineScope {
        val chunks = CloudSpeech.chunks(text)
        val offsets = chunkOffsets(text, chunks)
        val started = System.currentTimeMillis()
        val streams = Channel<SpeechStream>(capacity = 1)
        val producer = launch {
            chunks.forEach { chunk ->
                val stream = SpeechStream()
                streams.send(stream)
                if (runCatching { CloudSpeech.stream(cloud, chunk, stream) }.isFailure) return@launch
            }
            streams.close()
        }
        var played = 0
        val player = Player(output, rate)
        val meter = launch(Dispatchers.Default) { meter(player) }
        try {
            while (!stop.isCompleted) {
                val stream = select<SpeechStream?> {
                    streams.onReceiveCatching { it.getOrNull() }
                    stop.onAwait { null }
                } ?: break
                cloudChunk = chunks.getOrElse(played) { "" }
                val ok = try {
                    play(stream, player, stop, firstWaitMillis = if (played == 0) FIRST_AUDIO_MILLIS else null) {
                        if (played == 0) Logger.i("Cloud speech first audio after ${System.currentTimeMillis() - started} ms")
                        val at = offsets.getOrElse(played) { 0 }
                        VoiceBus.speech { it.copy(spokenTo = at, aheadTo = at + chunks.getOrElse(played) { "" }.length) }
                    }
                } finally {
                    remember(cloudChunk)
                    cloudChunk = ""
                }
                if (!ok) {
                    withContext(Dispatchers.IO) { player.finish(stop) }
                    return@coroutineScope chunks.drop(played)
                }
                played++
            }
            withContext(Dispatchers.IO) { player.finish(stop) }
        } finally {
            producer.cancel()
            meter.cancel()
            player.release()
        }
        if (stop.isCompleted) emptyList() else chunks.drop(played)
    }

    private class Player(private val output: Output, private val rate: Float) {
        @Volatile private var track: AudioTrack? = null
        private val levels = ArrayDeque<Pair<Long, Float>>()
        private var boost: SpeechBoost? = null

        fun boost(bytes: ByteArray, size: Int) {
            boost?.process(bytes, size)
        }

        fun measure(bytes: ByteArray, size: Int) {
            val window = (sampleRate / 50).coerceAtLeast(1)
            val samples = size / 2
            synchronized(levels) {
                var start = 0
                while (start < samples) {
                    val end = minOf(start + window, samples)
                    var sum = 0.0
                    for (i in start until end) {
                        val v = ((bytes[2 * i + 1].toInt() shl 8) or (bytes[2 * i].toInt() and 0xFF)).toShort() / 32768.0
                        sum += v * v
                    }
                    levels.addLast((frames + end) to kotlin.math.sqrt(sum / (end - start)).toFloat())
                    start = end
                }
            }
        }

        fun level(): Float? {
            val head = (track ?: return null).playbackHeadPosition.toLong() and 0xFFFFFFFFL
            var last: Float? = null
            synchronized(levels) { while (levels.isNotEmpty() && levels.first().first <= head) last = levels.removeFirst().second }
            return last
        }
        private var sampleRate = 0
        private var frames = 0L

        fun track(rate: Int): AudioTrack {
            track?.takeIf { sampleRate == rate }?.let { return it }
            release()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val minBuffer = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            return AudioTrack.Builder()
                .setAudioAttributes(output.attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuffer * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .also { created ->
                    output.device?.let(created::setPreferredDevice)
                    runCatching { created.playbackParams = created.playbackParams.setSpeed(this.rate) }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching { created.setStartThresholdInFrames(minBuffer / 2 / 2) }
                    created.play()
                    track = created
                    sampleRate = rate
                    if (output.gain != 1f) boost = boost ?: SpeechBoost(output.gain)
                    frames = 0L
                    synchronized(levels) { levels.clear() }
                }
        }

        fun wrote(count: Int) {
            frames += count
        }

        suspend fun finish(stop: CompletableDeferred<Unit>) {
            val current = track ?: return
            if (!stop.isCompleted && frames > 0) {
                val drainMillis = (frames * 1000 / sampleRate / rate).toLong() + DRAIN_MARGIN_MILLIS
                withTimeoutOrNull(drainMillis) {
                    while (current.playbackHeadPosition < frames && !stop.isCompleted) delay(POLL_MILLIS)
                }
                if (!stop.isCompleted && output.tailMillis > 0) withTimeoutOrNull(output.tailMillis) { stop.await() }
            }
            release()
        }

        fun release() {
            boost?.let {
                Logger.i("Speech level: ${it.summary()}")
                Logger.ux("speech_level", it.summary())
            }
            boost = null
            synchronized(levels) { levels.clear() }
            val current = track ?: return
            track = null
            runCatching { current.pause(); current.flush() }
            current.release()
        }
    }

    private suspend fun play(
        stream: SpeechStream,
        player: Player,
        stop: CompletableDeferred<Unit>,
        firstWaitMillis: Long? = null,
        onFirstAudio: () -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        val sampleRate = try {
            val waited = withTimeoutOrNull(firstWaitMillis ?: Long.MAX_VALUE) {
                select<Int?> {
                    stream.rate.onAwait { it }
                    stop.onAwait { null }
                } ?: return@withTimeoutOrNull null
            }
            if (waited == null && !stop.isCompleted && firstWaitMillis != null) {
                Logger.w("Cloud speech gave no audio in $firstWaitMillis ms, using the phone voice")
                return@withContext false
            }
            waited ?: return@withContext true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Logger.w("Cloud speech failed", e)
            return@withContext false
        }
        val track = player.track(sampleRate)
        var written = 0L
        var carry: Byte? = null
        while (!stop.isCompleted) {
            val received = select<ChannelResult<ByteArray>?> {
                stream.data.onReceiveCatching { it }
                stop.onAwait { null }
            } ?: break
            if (received.isClosed) {
                received.exceptionOrNull()?.let {
                    Logger.w("Cloud speech failed", it)
                    return@withContext false
                }
                break
            }
            var bytes = received.getOrThrow()
            carry?.let { bytes = byteArrayOf(it) + bytes }
            carry = if (bytes.size % 2 == 1) bytes.last() else null
            val size = bytes.size - bytes.size % 2
            if (written == 0L && size > 0) onFirstAudio()
            player.boost(bytes, size)
            player.measure(bytes, size)
            var offset = 0
            while (offset < size && !stop.isCompleted) {
                val count = track.write(bytes, offset, size - offset)
                if (count <= 0) break
                offset += count
            }
            written += size / 2
            player.wrote(size / 2)
        }
        true
    }

    private suspend fun speakSystem(text: String, language: String, voiceName: String, rate: Float, output: Output, stop: CompletableDeferred<Unit>, retry: Boolean = true, base: Int = 0) {
        warmUp()
        if (!ready.await()) {
            Logger.w("Speech engine unavailable")
            reconnect()
            if (retry) {
                delay(RECONNECT_DELAY_MILLIS)
                speakSystem(text, language, voiceName, rate, output, stop, retry = false, base = base)
            }
            return
        }
        val engine = tts ?: return
        val locale = language.takeIf { it.isNotBlank() }?.let(Locale::forLanguageTag) ?: Locale.getDefault()
        val voice = voiceName.takeIf { it.isNotBlank() }?.let { name ->
            voiceCache[name] ?: runCatching { engine.voices?.firstOrNull { it.name == name } }.getOrNull()?.also { voiceCache[name] = it }
        }
        if (voice != null) engine.voice = voice else engine.language = locale
        engine.setSpeechRate(rate)
        engine.setAudioAttributes(output.attributes)
        val own = output.gain != 1f || (output.device != null && (output.route.car || routes?.systemPlaysOn(output.route, output.attributes) == false))
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        waiting[id] = done
        audio.requestAudioFocus(output.focus)
        systemText = text
        val accepted = try {
            if (own) speakToDevice(engine, text, id, done, output, stop, base)
            else {
                rangeBase = base
                VoiceBus.speech { it.copy(spokenTo = base, aheadTo = base + text.length) }
                (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.SUCCESS).also { accepted ->
                    if (accepted) withTimeoutOrNull(speechLimit(text, rate)) { select<Unit> { done.onAwait {}; stop.onAwait {} } }
                }
            }
        } catch (e: CancellationException) {
            runCatching { engine.stop() }
            throw e
        } finally {
            rangeBase = -1
            waiting.remove(id)
            audio.abandonAudioFocusRequest(output.focus)
        }
        if (!accepted) {
            Logger.w("Speech engine rejected the text, reconnecting")
            reconnect()
            if (retry) speakSystem(text, language, voiceName, rate, output, stop, retry = false, base = base)
        }
    }

    private suspend fun speakToDevice(engine: TextToSpeech, text: String, id: String, done: CompletableDeferred<Unit>, output: Output, stop: CompletableDeferred<Unit>, base: Int): Boolean {
        val file = File(context.cacheDir, "speech-$id.wav")
        try {
            if (engine.synthesizeToFile(text, null, file, id) != TextToSpeech.SUCCESS) return false
            withTimeoutOrNull(speechLimit(text, 1f)) { done.await() } ?: return false
            val (sampleRate, pcm) = Wav.read(file) ?: return false
            val stream = SpeechStream().apply {
                rate.complete(sampleRate)
                data.trySend(pcm)
                data.close()
            }
            remember(text)
            VoiceBus.speech { it.copy(spokenTo = base, aheadTo = base + text.length) }
            val player = Player(output, 1f)
            coroutineScope {
                val meter = launch(Dispatchers.Default) { meter(player) }
                try {
                    play(stream, player, stop)
                    withContext(Dispatchers.IO) { player.finish(stop) }
                } finally {
                    meter.cancel()
                    player.release()
                }
            }
            return true
        } finally {
            file.delete()
        }
    }

    private fun reconnect() {
        runCatching { tts?.shutdown() }
        tts = null
    }

    private suspend fun meter(player: Player) {
        while (true) {
            player.level()?.let { VoiceBus.speechLevel((it * SPEECH_GAIN).coerceIn(0f, 1f)) }
            delay(METER_MILLIS)
        }
    }

    private fun chunkOffsets(text: String, chunks: List<String>): IntArray {
        var cursor = 0
        return IntArray(chunks.size) { i ->
            val at = text.indexOf(chunks[i].take(12), cursor).takeIf { it >= 0 } ?: cursor
            cursor = at + chunks[i].length
            at
        }
    }

    private fun speechLimit(text: String, rate: Float): Long = MIN_SPEECH_MILLIS + (text.length * MILLIS_PER_CHAR / rate.coerceAtLeast(0.5f)).toLong()

    fun stop() {
        epoch++
        stopSignal.complete(Unit)
        runCatching { tts?.stop() }
        waiting.values.forEach { it.complete(Unit) }
        waiting.clear()
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
    }

    companion object {
        fun attributes(usage: Int): AudioAttributes = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        private const val MIN_SPEECH_MILLIS = 10_000L
        private const val METER_MILLIS = 33L
        private const val SPEECH_GAIN = 5f
        private const val MILLIS_PER_CHAR = 120f
        private val CALL_MODES = setOf(AudioManager.MODE_RINGTONE, AudioManager.MODE_IN_CALL, AudioManager.MODE_CALL_SCREENING)
        private const val FIRST_AUDIO_MILLIS = 2_000L
        private const val RECONNECT_DELAY_MILLIS = 1_000L
        private const val ECHO_MILLIS = 1_500L
        private const val DRAIN_MARGIN_MILLIS = 500L
        private const val POLL_MILLIS = 20L
    }
}

