package dev.marionette.core

import android.util.Log

object Logger {
    private const val TAG = "Marionette"

    fun i(message: String) {
        Log.i(TAG, message)
    }

    fun w(message: String, error: Throwable? = null) {
        Log.w(TAG, message, error)
    }

    fun e(message: String, error: Throwable? = null) {
        Log.e(TAG, message, error)
    }
}
