package dev.marionette.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.car.app.connection.CarConnection
import dev.marionette.BuildConfig
import dev.marionette.core.AppJson
import dev.marionette.core.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

class AudioRoutes(private val context: Context, private val scope: CoroutineScope, private val file: File) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val serializer = ListSerializer(KnownAudioDevice.serializer())
    private val _devices = MutableStateFlow(load())
    val devices: StateFlow<List<KnownAudioDevice>> = _devices.asStateFlow()
    private val _connected = MutableStateFlow(emptySet<String>())
    val connected: StateFlow<Set<String>> = _connected.asStateFlow()
    @Volatile private var carConnected = false
    @Volatile private var debugCar: Boolean? = null
    @Volatile private var debugPorts: List<Port> = emptyList()
    private val car: Boolean get() = debugCar ?: carConnected

    fun start() {
        audio.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = refresh()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = refresh()
        }, null)
        scope.launch(Dispatchers.Main) {
            runCatching {
                CarConnection(context).type.observeForever { type ->
                    carConnected = type == CarConnection.CONNECTION_TYPE_PROJECTION
                    refresh()
                }
            }.onFailure { Logger.w("Car connection unavailable", it) }
        }
        refresh()
    }

    fun refresh() {
        val seen = AudioRouting.seen(ports(), car)
        _connected.value = seen.map { it.id }.toSet()
        val merged = AudioRouting.merge(_devices.value, seen)
        if (merged != _devices.value) save(merged)
    }

    fun route(): AudioRoute = AudioRouting.route(_devices.value, ports(), car)

    fun describe(): String? {
        val connected = _connected.value
        val known = _devices.value.filter { it.id in connected }
        if (known.isEmpty()) return null
        val active = route().device
        return known.joinToString("; ") { d ->
            val name = if (d.kind == AudioKind.ANDROID_AUTO) "Android Auto" else "${d.kind.name.lowercase().replace('_', ' ')} \"${d.name}\""
            val use = when {
                !d.enabled -> "ignored"
                d.id == active?.id -> "in use: mic ${if (d.usesDeviceMic) "device" else "phone"}, speaker ${if (d.deviceSpeaker) "device" else "phone"}"
                else -> "connected"
            }
            "$name ($use)"
        }
    }

    fun debugSimulate(car: Boolean?, ports: List<Port>, known: List<KnownAudioDevice>?) {
        if (!BuildConfig.DEBUG) return
        debugCar = car
        debugPorts = ports
        known?.let(::save)
        refresh()
    }

    fun update(id: String, change: (KnownAudioDevice) -> KnownAudioDevice) = save(_devices.value.map { if (it.id == id) change(it) else it })

    fun move(id: String, offset: Int) {
        val list = _devices.value.toMutableList()
        val from = list.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
        val to = (from + offset).coerceIn(0, list.lastIndex)
        list.add(to, list.removeAt(from))
        save(list)
    }

    fun forget(id: String) = save(_devices.value.filterNot { it.id == id })

    fun info(port: Port?): AudioDeviceInfo? = port?.let { p ->
        audio.getDevices(if (p.input) AudioManager.GET_DEVICES_INPUTS else AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == p.id }
    }

    fun systemPlaysOn(route: AudioRoute, attributes: AudioAttributes): Boolean {
        val output = route.output ?: return true
        val fallback = output.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || _connected.value.isEmpty()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return fallback
        return runCatching { audio.getAudioDevicesForAttributes(attributes).any { it.id == output.id } }.getOrDefault(fallback)
    }

    suspend fun openRequestMic(route: AudioRoute): (() -> Unit)? {
        val input = route.requestInput?.takeIf { it.needsCommunication } ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val target = audio.availableCommunicationDevices.firstOrNull { it.type in Port.COMMUNICATION_TYPES && it.address == input.address } ?: return null
        if (!audio.setCommunicationDevice(target)) {
            Logger.w("Communication device ${target.productName} refused")
            return null
        }
        val ready = try {
            withTimeoutOrNull(COMMUNICATION_WAIT_MILLIS) {
                while (audio.communicationDevice?.id != target.id) delay(POLL_MILLIS)
            } != null
        } catch (e: CancellationException) {
            audio.clearCommunicationDevice()
            throw e
        }
        Logger.i("Request mic ${target.productName} ready=$ready")
        return { audio.clearCommunicationDevice() }
    }

    private fun ports(): List<Port> =
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { port(it, input = false) } +
            audio.getDevices(AudioManager.GET_DEVICES_INPUTS).map { port(it, input = true) } + debugPorts

    private fun port(info: AudioDeviceInfo, input: Boolean) =
        Port(info.id, info.type, runCatching { info.address }.getOrDefault(""), info.productName?.toString().orEmpty(), input)

    private fun load(): List<KnownAudioDevice> = runCatching {
        file.takeIf { it.exists() }?.let { AppJson.decodeFromString(serializer, it.readText()) }
    }.getOrNull().orEmpty()

    private val writer = Dispatchers.IO.limitedParallelism(1)

    private fun save(list: List<KnownAudioDevice>) {
        _devices.update { list }
        scope.launch(writer) {
            runCatching {
                val temp = File(file.parentFile, file.name + ".tmp")
                temp.writeText(AppJson.encodeToString(serializer, _devices.value))
                temp.renameTo(file)
            }
        }
    }

    companion object {
        private const val COMMUNICATION_WAIT_MILLIS = 2_000L
        private const val POLL_MILLIS = 50L
    }
}
