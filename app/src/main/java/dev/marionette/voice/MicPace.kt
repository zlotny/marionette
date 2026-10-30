package dev.marionette.voice

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import dev.marionette.core.Logger

class MicPace(private val context: Context, private val chunkNanos: Long) {
    private var session: Any? = null
    private var busyNanos = 0L
    private var audioNanos = 0L

    fun attach() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || session != null) return
        session = runCatching {
            context.getSystemService(PerformanceHintManager::class.java)?.createHintSession(intArrayOf(Process.myTid()), chunkNanos)?.also {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) it.setPreferPowerEfficiency(true)
            }
        }.onFailure { Logger.w("Performance hint unavailable", it) }.getOrNull()
    }

    fun report(workNanos: Long, samples: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (session as? PerformanceHintManager.Session)?.let { s -> runCatching { s.reportActualWorkDuration(workNanos.coerceAtLeast(1)) } }
        }
        busyNanos += workNanos
        audioNanos += samples * 1_000_000_000L / AudioSpec.SAMPLE_RATE
        if (audioNanos < WINDOW_NANOS) return
        val load = busyNanos.toFloat() / audioNanos
        if (load > SLOW_LOAD) Logger.w("Microphone processing at ${(load * 100).toInt()}% of real time")
        busyNanos = 0
        audioNanos = 0
    }

    fun close() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) (session as? PerformanceHintManager.Session)?.let { runCatching { it.close() } }
        session = null
    }

    companion object {
        private const val WINDOW_NANOS = 10_000_000_000L
        private const val SLOW_LOAD = 0.8f
    }
}
