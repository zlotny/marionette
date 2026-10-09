package dev.marionette.spike

import android.content.Context
import androidx.core.content.edit

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("spike", Context.MODE_PRIVATE)

    var variant: Int
        get() = sp.getInt("variant", 1)
        set(v) = sp.edit { putInt("variant", v) }

    var delaySeconds: Int
        get() = sp.getInt("delay", 10)
        set(v) = sp.edit { putInt("delay", v) }

    var destination: String
        get() = sp.getString("destination", "40.4199,-3.6887") ?: ""
        set(v) = sp.edit { putString("destination", v) }

    var fallbackOnUnlock: Boolean
        get() = sp.getBoolean("fallback", true)
        set(v) = sp.edit { putBoolean("fallback", v) }

    var phrase: String
        get() = sp.getString("phrase", "HEY MARIONETTE") ?: ""
        set(v) = sp.edit { putString("phrase", v) }

    var threshold: Float
        get() = sp.getFloat("threshold", 0.25f)
        set(v) = sp.edit { putFloat("threshold", v) }

    var boost: Float
        get() = sp.getFloat("boost", 1.5f)
        set(v) = sp.edit { putFloat("boost", v) }

    var enrolled: List<String>
        get() = sp.getString("enrolled", "")!!.split('\n').filter { it.isNotBlank() }
        set(v) = sp.edit { putString("enrolled", v.joinToString("\n")) }

    var detector: String
        get() = sp.getString("detector", DETECTOR_KWS) ?: DETECTOR_KWS
        set(v) = sp.edit { putString("detector", v) }

    var fuzzyRatio: Float
        get() = sp.getFloat("fuzzyRatio", 0.25f)
        set(v) = sp.edit { putFloat("fuzzyRatio", v) }

    var acousticThreshold: Float
        get() = sp.getFloat("acousticThreshold", 0.14f)
        set(v) = sp.edit { putFloat("acousticThreshold", v) }

    val wakeVariants: List<String>
        get() = (listOf(phrase) + enrolled).distinct()

    var bluetoothMic: Boolean
        get() = sp.getBoolean("bluetoothMic", false)
        set(v) = sp.edit { putBoolean("bluetoothMic", v) }

    var launchOnDetection: Boolean
        get() = sp.getBoolean("launchOnDetection", false)
        set(v) = sp.edit { putBoolean("launchOnDetection", v) }

    companion object {
        const val DETECTOR_KWS = "A"
        const val DETECTOR_ASR = "B"
        const val DETECTOR_ACOUSTIC = "C"
    }
}
