package dev.marionette.ui.components

import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

@Immutable
data class Motion(val reduced: Boolean = false, val calm: Boolean = false) {
    val ambient: Boolean get() = !reduced && !calm
}

val LocalMotion = staticCompositionLocalOf { Motion() }

@Composable
fun rememberMotion(): Motion {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val power = remember { context.getSystemService(PowerManager::class.java) }
    var reduced by remember { mutableStateOf(animationsOff(resolver)) }
    var calm by remember { mutableStateOf(power?.isPowerSaveMode == true) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduced = animationsOff(resolver)
            }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                calm = power?.isPowerSaveMode == true
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose {
            resolver.unregisterContentObserver(observer)
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
    return Motion(reduced, calm)
}

private fun animationsOff(resolver: ContentResolver) =
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
