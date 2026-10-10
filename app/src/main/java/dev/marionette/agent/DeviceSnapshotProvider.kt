package dev.marionette.agent

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.UiModeManager
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.coroutines.resume

class DeviceSnapshotProvider(private val context: Context) {
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(context) }
    private var cachedLocation: Location? = null
    private var cachedPlace: Pair<String, String?>? = null
    private val locating = Mutex()
    private val geocoding = Mutex()

    val isLocked: Boolean get() = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked

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
        cachedLocation?.takeIf(::isRecent)?.let { return it }
        runCatching { fused.lastLocation.awaitOrNull() }.getOrNull()?.takeIf(::isRecent)?.let { return it.also { cachedLocation = it } }
        val fresh = runCatching {
            withTimeoutOrNull(LOCATION_TIMEOUT_MILLIS) {
                val token = CancellationTokenSource()
                try {
                    fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token.token).awaitOrNull()
                } finally {
                    token.cancel()
                }
            }
        }.onFailure { Logger.w("Location failed", it) }.getOrNull()
        val result = fresh ?: runCatching { fused.lastLocation.awaitOrNull() }.getOrNull()
        if (result != null) cachedLocation = result
        return result
    }

    private fun isRecent(location: Location): Boolean =
        (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000 < LOCATION_MAX_AGE_MILLIS

    suspend fun snapshot(fields: Set<ContextField>, language: String): DeviceSnapshot {
        val location = if (ContextField.LOCATION in fields || ContextField.MOTION in fields) location() else null
        val battery = context.getSystemService(BatteryManager::class.java)
        val uiMode = context.getSystemService(UiModeManager::class.java)
        val locale = language.ifBlank { Locale.getDefault().toLanguageTag() }
        return DeviceSnapshot(
            time = ZonedDateTime.now(),
            locale = locale,
            latitude = location?.latitude,
            longitude = location?.longitude,
            accuracyMeters = location?.takeIf { it.hasAccuracy() }?.accuracy,
            place = if (ContextField.LOCATION in fields) location?.let { placeName(it, locale) } else null,
            speedKmh = location?.takeIf { it.hasSpeed() }?.speed?.times(3.6f),
            headingDegrees = location?.takeIf { it.hasBearing() && it.speed > 1f }?.bearing,
            batteryPercent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 },
            charging = battery.isCharging,
            audioOutput = audioOutput(),
            androidAuto = uiMode.currentModeType == Configuration.UI_MODE_TYPE_CAR,
            screenOn = isScreenOn,
            locked = isLocked,
        )
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
        val address = withTimeoutOrNull(GEOCODER_TIMEOUT_MILLIS) {
            if (Build.VERSION.SDK_INT >= 33) {
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
                withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    runCatching { geocoder.getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull() }.getOrNull()
                }
            }
        }
        val name = address?.let { a ->
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
        private const val LOCATION_TIMEOUT_MILLIS = 4_000L
        private const val GEOCODER_TIMEOUT_MILLIS = 2_500L
        private val BLUETOOTH_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
        )
        private val WIRED_TYPES = setOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET)
    }
}
