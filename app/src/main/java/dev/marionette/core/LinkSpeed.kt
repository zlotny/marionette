package dev.marionette.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock

object LinkSpeed {
    private const val WEIGHT = 0.5
    private const val SLOW_KBPS = 400.0
    private const val MIN_BYTES = 16_000
    private const val MAX_AGE_MILLIS = 5 * 60_000L

    @Volatile private var estimate: Double? = null
    @Volatile private var recordedAt = 0L

    val uploadKbps: Double?
        get() = estimate?.takeIf { SystemClock.elapsedRealtime() - recordedAt < MAX_AGE_MILLIS }

    fun record(bytes: Int, millis: Long) {
        if (bytes < MIN_BYTES || millis <= 0) return
        val kbps = bytes * 8.0 / millis
        estimate = uploadKbps?.let { it + WEIGHT * (kbps - it) } ?: kbps
        recordedAt = SystemClock.elapsedRealtime()
    }

    fun reset() {
        estimate = null
    }

    fun uploadMillis(bytes: Long): Long? = uploadKbps?.let { (bytes * 8 / it).toLong() }

    fun measuredSlow(): Boolean = uploadKbps?.let { it < SLOW_KBPS } == true

    fun slow(context: Context): Boolean {
        uploadKbps?.let { if (it < SLOW_KBPS) return true }
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return true
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return true
        val up = caps.linkUpstreamBandwidthKbps
        return up in 1 until SLOW_KBPS.toInt()
    }
}
