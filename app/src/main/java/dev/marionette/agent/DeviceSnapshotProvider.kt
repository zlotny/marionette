package dev.marionette.agent

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.UiModeManager
import kotlin.math.roundToInt
import dev.marionette.core.LinkSpeed
import android.net.wifi.WifiManager
import android.net.NetworkCapabilities
import android.net.ConnectivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.Geocoder
import android.location.Location
import android.os.SystemClock
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import dev.marionette.core.Logger
import dev.marionette.settings.ContextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.coroutines.resume

class DeviceSnapshotProvider(
    private val context: Context,
    private val carConnected: () -> Boolean = { false },
    private val audioDevices: () -> String? = { null },
    private val mediaHeld: (String?) -> Boolean = { false },
) {
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(context) }
    @Volatile private var cachedLocation: Location? = null
    @Volatile private var cachedAt = 0L
    @Volatile private var cachedPlace: Pair<String, String?>? = null
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val locating = Mutex()
    private val geocoding = Mutex()

    val isLocked: Boolean get() = context.getSystemService(KeyguardManager::class.java).isDeviceLocked

    val isKeyguardShowing: Boolean get() = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    val inCar: Boolean get() = carConnected() || context.getSystemService(UiModeManager::class.java).currentModeType == Configuration.UI_MODE_TYPE_CAR

    val isScreenOn: Boolean get() = context.getSystemService(PowerManager::class.java).isInteractive

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    suspend fun prefetch(fields: Set<ContextField>, language: String) {
        if (ContextField.LOCATION !in fields && ContextField.MOTION !in fields) return
        val location = location() ?: return
        if (ContextField.LOCATION in fields) placeName(location, language.ifBlank { Locale.getDefault().toLanguageTag() })
    }

    suspend fun location(): Location? = locating.withLock { freshLocation() }

    @SuppressLint("MissingPermission")
    private suspend fun freshLocation(): Location? {
        if (!hasLocationPermission()) return null
        cachedLocation?.takeIf { SystemClock.elapsedRealtime() - cachedAt < LOCATION_MAX_AGE_MILLIS }?.let { return it }
        runCatching { fused.lastLocation.awaitOrNull() }.getOrNull()?.takeIf(::isRecent)?.let { return remember(it) }
        val fresh = runCatching {
            withTimeoutOrNull(LOCATION_TIMEOUT_MILLIS) {
                val token = CancellationTokenSource()
                try {
                    val moving = inCar || (cachedLocation?.let { it.hasSpeed() && it.speed > MOVING_MPS } == true)
                    fused.getCurrentLocation(if (moving) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY, token.token).awaitOrNull()
                } finally {
                    token.cancel()
                }
            }
        }.onFailure { Logger.w("Location failed", it) }.getOrNull()
        val result = fresh ?: runCatching { fused.lastLocation.awaitOrNull() }.getOrNull()
        return result?.let(::remember)
    }

    private fun remember(location: Location): Location {
        cachedLocation = location
        cachedAt = SystemClock.elapsedRealtime()
        return location
    }

    @SuppressLint("MissingPermission")
    private suspend fun quickLocation(): Location? {
        val pending = background.async { location() }
        val known = cachedLocation ?: if (hasLocationPermission()) withTimeoutOrNull(SNAPSHOT_LOCATION_MILLIS) { runCatching { fused.lastLocation.awaitOrNull() }.getOrNull() } else null
        known?.takeIf { ageMillis(it) < SNAPSHOT_MAX_AGE_MILLIS }?.let { return it }
        return withTimeoutOrNull(SNAPSHOT_LOCATION_MILLIS) { pending.await() } ?: known
    }

    private fun ageMillis(location: Location): Long = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000

    private suspend fun quickPlace(location: Location, locale: String): String? = background.async { placeName(location, locale) }.let { pending -> withTimeoutOrNull(SNAPSHOT_PLACE_MILLIS) { pending.await() } }

    private fun isRecent(location: Location): Boolean = ageMillis(location) < LOCATION_MAX_AGE_MILLIS

    suspend fun snapshot(fields: Set<ContextField>, language: String): DeviceSnapshot {
        val activity = ContextField.ACTIVITY in fields
        fun <T> read(block: () -> T) = if (activity) background.async(Dispatchers.IO) { runCatching(block).getOrNull() } else null
        val clock = read { PhoneActivity.clock(context) }
        val messages = read { PhoneActivity.messages(context) }
        val agenda = read { PhoneActivity.agenda(context) }
        val missedCalls = read { PhoneActivity.missedCalls(context) }
        val location = if (ContextField.LOCATION in fields || ContextField.MOTION in fields) quickLocation() else null
        val battery = context.getSystemService(BatteryManager::class.java)
        val locale = language.ifBlank { Locale.getDefault().toLanguageTag() }
        return DeviceSnapshot(
            time = ZonedDateTime.now(),
            locale = locale,
            latitude = location?.latitude,
            longitude = location?.longitude,
            accuracyMeters = location?.takeIf { it.hasAccuracy() }?.accuracy,
            locationAgeSeconds = location?.let { ageMillis(it) / 1000 },
            place = if (ContextField.LOCATION in fields) location?.let { quickPlace(it, locale) } else null,
            speedKmh = location?.takeIf { it.hasSpeed() && isRecent(it) }?.speed?.times(3.6f),
            headingDegrees = location?.takeIf { it.hasBearing() && it.speed > 1f && isRecent(it) }?.bearing,
            batteryPercent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 },
            charging = battery.isCharging,
            audioOutput = audioOutput(),
            androidAuto = inCar,
            audioDevices = if (ContextField.AUDIO in fields) audioDevices() else null,
            network = if (ContextField.NETWORK in fields) network() else null,
            screenOn = isScreenOn,
            locked = isLocked,
            media = if (ContextField.ACTIVITY in fields) runCatching { PhoneActivity.media(context, mediaHeld) }.getOrNull() else null,
            clock = clock?.await(),
            navigation = if (ContextField.ACTIVITY in fields) runCatching { PhoneActivity.navigation() }.getOrNull() else null,
            messages = messages?.await(),
            agenda = agenda?.await(),
            missedCalls = missedCalls?.await(),
        )
    }

    @Suppress("DEPRECATION")
    fun network(): String {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return "offline"
        val kind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                val ssid = runCatching { context.applicationContext.getSystemService(WifiManager::class.java).connectionInfo.ssid }.getOrNull()
                    ?.trim('"')?.takeIf { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID.trim('<', '>') && !it.startsWith("<") }
                ssid?.let { "wifi \"$it\"" } ?: "wifi"
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        val internet = if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "internet ok" else "no internet"
        val speed = LinkSpeed.uploadKbps?.let { ", measured upload ${it.roundToInt()} kbit/s" }.orEmpty()
        val slow = if (LinkSpeed.slow(context)) ", slow link" else ""
        return "$kind, $internet$speed$slow"
    }

    fun audioOutput(): String {
        val audio = context.getSystemService(AudioManager::class.java)
        val outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        outputs.firstOrNull { it.type in BLUETOOTH_TYPES }?.let { return "Bluetooth \"${it.productName}\"" }
        outputs.firstOrNull { it.type in WIRED_TYPES }?.let { return "Wired headphones" }
        return "Phone speaker"
    }

    private suspend fun placeName(location: Location, locale: String): String? = geocoding.withLock { lookUpPlace(location, locale) }

    private suspend fun lookUpPlace(location: Location, locale: String): String? {
        val key = "%.3f,%.3f".format(Locale.US, location.latitude, location.longitude)
        cachedPlace?.takeIf { it.first == key }?.let { return it.second }
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.forLanguageTag(locale))
        val lookup = withTimeoutOrNull(GEOCODER_TIMEOUT_MILLIS) {
            listOf(if (Build.VERSION.SDK_INT >= 33) {
                suspendCancellableCoroutine { continuation ->
                    geocoder.getFromLocation(location.latitude, location.longitude, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<android.location.Address>) {
                            continuation.resume(addresses.firstOrNull())
                        }

                        override fun onError(errorMessage: String?) {
                            continuation.resume(null)
                        }
                    })
                }
            } else {
                background.async(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    runCatching { geocoder.getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull() }.getOrNull()
                }.await()
            })
        } ?: return null
        val name = lookup.first()?.let { a ->
            listOfNotNull(a.locality ?: a.subAdminArea, a.thoroughfare?.let { t -> listOfNotNull(t, a.subThoroughfare).joinToString(" ") })
                .joinToString(", ")
                .ifBlank { null }
        }
        cachedPlace = key to name
        return name
    }

    private suspend fun <T> Task<T>.awaitOrNull(): T? = suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { task -> continuation.resume(if (task.isSuccessful) task.result else null) }
    }

    companion object {
        private const val LOCATION_MAX_AGE_MILLIS = 15_000L
        private const val MOVING_MPS = 2f
        private const val LOCATION_TIMEOUT_MILLIS = 4_000L
        private const val GEOCODER_TIMEOUT_MILLIS = 2_500L
        private const val SNAPSHOT_LOCATION_MILLIS = 800L
        private const val SNAPSHOT_MAX_AGE_MILLIS = 120_000L
        private const val SNAPSHOT_PLACE_MILLIS = 600L
        private val BLUETOOTH_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
        )
        private val WIRED_TYPES = setOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET)
    }
}
