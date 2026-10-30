package dev.marionette.core

import android.os.SystemClock
import android.util.Log
import dev.marionette.BuildConfig

object Logger {
    private const val TAG = "Marionette"
    private const val UX_TAG = "MarionetteUX"

    fun i(message: String) {
        Log.i(TAG, message)
    }

    fun content(label: String, text: String?) {
        Log.i(TAG, if (BuildConfig.DEBUG) "$label: $text" else "$label (${text?.length ?: 0} chars)")
    }

    fun ux(event: String, detail: Any? = null) {
        if (BuildConfig.DEBUG) Log.i(UX_TAG, "UXE ${SystemClock.elapsedRealtime()} $event ${detail ?: ""}".trimEnd())
    }

    fun w(message: String, error: Throwable? = null) {
        Log.w(TAG, message, error)
    }

    fun e(message: String, error: Throwable? = null) {
        Log.e(TAG, message, error)
    }
}
