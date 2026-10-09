package dev.marionette.spike.launch

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import dev.marionette.spike.DeviceState
import dev.marionette.spike.Prefs
import dev.marionette.spike.SpikeApp
import dev.marionette.spike.SpikeLog
import dev.marionette.spike.assist.MarionetteVoiceInteractionService

enum class LaunchVariant(val number: Int, val label: String) {
    ASSISTANT_ACTIVITY(1, "Session startAssistantActivity, show-when-locked trampoline"),
    SESSION_DIRECT(2, "Session starts Maps directly"),
    FULL_SCREEN_INTENT(3, "Full-screen-intent notification"),
    OVERLAY(4, "Overlay window, then start Maps"),
    SERVICE_DIRECT(5, "Plain startActivity from the service"),
    DISMISS_KEYGUARD(6, "Session trampoline requests keyguard dismiss (Smart Lock), then Maps");

    companion object {
        fun of(number: Int) = entries.first { it.number == number }
    }
}

object NavLauncher {
    const val EXTRA_VARIANT = "variant"
    private const val MAPS_PACKAGE = "com.google.android.apps.maps"
    private const val FSI_NOTIFICATION_ID = 42
    private val main = Handler(Looper.getMainLooper())

    fun mapsIntent(context: Context): Intent {
        val destination = Prefs(context).destination.replace(" ", "")
        return Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$destination&mode=d"))
            .setPackage(MAPS_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun fire(context: Context, variant: LaunchVariant) {
        SpikeLog.log("S1 fire V${variant.number} ${variant.label} | ${DeviceState.summary(context)}")
        when (variant) {
            LaunchVariant.ASSISTANT_ACTIVITY, LaunchVariant.SESSION_DIRECT, LaunchVariant.DISMISS_KEYGUARD -> showSession(variant)
            LaunchVariant.FULL_SCREEN_INTENT -> postFullScreenIntent(context)
            LaunchVariant.OVERLAY -> overlayThenStart(context)
            LaunchVariant.SERVICE_DIRECT -> startMaps(context, "service")
        }
    }

    fun startMaps(context: Context, from: String) {
        runCatching { context.startActivity(mapsIntent(context)) }
            .onSuccess { SpikeLog.log("S1 startActivity(maps) from $from returned OK") }
            .onFailure { SpikeLog.log("S1 startActivity(maps) from $from FAILED: $it") }
    }

    private fun showSession(variant: LaunchVariant) {
        val vis = MarionetteVoiceInteractionService.instance
        if (vis == null) {
            SpikeLog.log("S1 V${variant.number} aborted: voice interaction service not running (assistant role?)")
            return
        }
        main.post {
            runCatching { vis.showSession(Bundle().apply { putInt(EXTRA_VARIANT, variant.number) }, 0) }
                .onSuccess { SpikeLog.log("S1 showSession requested") }
                .onFailure { SpikeLog.log("S1 showSession FAILED: $it") }
        }
    }

    private fun postFullScreenIntent(context: Context) {
        if (!DeviceState.canFullScreen(context)) SpikeLog.log("S1 V3 warning: full-screen intent not permitted")
        val pending = PendingIntent.getActivity(
            context,
            0,
            Intent(context, NavTrampolineActivity::class.java)
                .putExtra(NavTrampolineActivity.EXTRA_SOURCE, "full-screen intent")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, SpikeApp.CHANNEL_LAUNCH)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("Starting navigation")
            .setCategory(Notification.CATEGORY_ALARM)
            .setFullScreenIntent(pending, true)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(FSI_NOTIFICATION_ID, notification)
        SpikeLog.log("S1 V3 full-screen notification posted")
    }

    private fun overlayThenStart(context: Context) {
        if (!DeviceState.canOverlay(context)) {
            SpikeLog.log("S1 V4 aborted: overlay permission missing")
            return
        }
        main.post {
            val wm = context.getSystemService(WindowManager::class.java)
            val view = View(context).apply { setBackgroundColor(0xCC6750A4.toInt()) }
            val size = (48 * context.resources.displayMetrics.density).toInt()
            val params = WindowManager.LayoutParams(
                size,
                size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.END }
            runCatching { wm.addView(view, params) }
                .onFailure { SpikeLog.log("S1 V4 addView FAILED: $it") }
                .onSuccess {
                    SpikeLog.log("S1 V4 overlay shown")
                    main.postDelayed({ startMaps(context, "overlay") }, 300)
                    main.postDelayed({ runCatching { wm.removeView(view) } }, 5000)
                }
        }
    }
}
