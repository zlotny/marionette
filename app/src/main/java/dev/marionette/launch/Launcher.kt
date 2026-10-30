package dev.marionette.launch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import dev.marionette.assist.AssistantSession
import dev.marionette.assist.MarionetteVoiceInteractionService
import dev.marionette.core.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

enum class LaunchStatus { STARTED, WAITING_FOR_UNLOCK, FAILED }

class Launcher(private val context: Context, private val isKeyguardShowing: () -> Boolean) {
    private val main = Handler(Looper.getMainLooper())
    private var pending: PendingLaunch? = null
    private var unlockReceiver: BroadcastReceiver? = null

    private class PendingLaunch(val intent: Intent?, val label: String, val result: CompletableDeferred<LaunchStatus>)

    suspend fun launch(intent: Intent, label: String, unlock: Boolean = true): LaunchStatus = withContext(Dispatchers.Main.immediate) {
        val target = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!unlock || !isKeyguardShowing()) start(target, label) else dismissKeyguardThen(target, label)
    }

    suspend fun dismissKeyguard(): Boolean = withContext(Dispatchers.Main.immediate) { dismissKeyguardOnMain() }

    private suspend fun dismissKeyguardOnMain(): Boolean {
        if (!isKeyguardShowing()) return true
        val status = dismissKeyguardThen(null, "keyguard dismiss")
        if (status != LaunchStatus.STARTED && pending?.intent == null) clearPending()
        return status == LaunchStatus.STARTED
    }

    private suspend fun dismissKeyguardThen(target: Intent?, label: String): LaunchStatus {
        pending?.takeIf { target == null && it.intent == null }?.let { running ->
            return withTimeoutOrNull(DISMISS_TIMEOUT_MILLIS) { running.result.await() } ?: LaunchStatus.WAITING_FOR_UNLOCK
        }
        val launch = PendingLaunch(target, label, CompletableDeferred())
        pending?.result?.complete(LaunchStatus.FAILED)
        pending = launch
        armUnlockFallback()
        main.removeCallbacks(expirePending)
        main.postDelayed(expirePending, PENDING_TTL_MILLIS)
        val vis = MarionetteVoiceInteractionService.instance
        if (vis == null) {
            Logger.w("Launch $label while locked: assistant service not bound, waiting for unlock")
            return LaunchStatus.WAITING_FOR_UNLOCK
        }
        main.post {
            runCatching { vis.showSession(Bundle().apply { putBoolean(EXTRA_LAUNCH, true) }, 0) }
                .onFailure { Logger.w("showSession for launch failed", it) }
        }
        return try {
            withTimeoutOrNull(DISMISS_TIMEOUT_MILLIS) { launch.result.await() } ?: LaunchStatus.WAITING_FOR_UNLOCK
        } catch (e: CancellationException) {
            if (pending === launch) clearPending()
            throw e
        }
    }

    fun onKeyguardDismissed(activity: Context) {
        val launch = pending ?: return
        clearPending()
        launch.result.complete(start(launch.intent, launch.label, activity))
    }

    fun onKeyguardDismissCancelled() {
        pending?.result?.complete(LaunchStatus.WAITING_FOR_UNLOCK)
    }

    val hasPending: Boolean get() = pending != null

    private fun start(intent: Intent?, label: String, from: Context = context): LaunchStatus = runCatching {
        if (intent != null) AssistantSession.hideShown()
        intent?.let(from::startActivity)
        Logger.i("Launched $label")
        Logger.ux("launch", label)
        LaunchStatus.STARTED
    }.getOrElse {
        Logger.w("Launch $label failed", it)
        LaunchStatus.FAILED
    }

    private fun armUnlockFallback() {
        if (unlockReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                main.postDelayed(runFallback, FALLBACK_GRACE_MILLIS)
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_EXPORTED)
        unlockReceiver = receiver
    }

    private val runFallback = Runnable {
        val launch = pending ?: return@Runnable disarmUnlockFallback()
        clearPending()
        Logger.i("Unlock fallback starts ${launch.label}")
        launch.result.complete(start(launch.intent, launch.label))
    }

    private val expirePending = Runnable {
        val launch = pending ?: return@Runnable
        Logger.i("Pending launch ${launch.label} expired")
        clearPending()
        launch.result.complete(LaunchStatus.FAILED)
    }

    private fun clearPending() {
        pending = null
        main.removeCallbacks(runFallback)
        main.removeCallbacks(expirePending)
        disarmUnlockFallback()
    }

    private fun disarmUnlockFallback() {
        unlockReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        unlockReceiver = null
    }

    companion object {
        const val EXTRA_LAUNCH = "dev.marionette.LAUNCH"
        private const val DISMISS_TIMEOUT_MILLIS = 6_000L
        private const val FALLBACK_GRACE_MILLIS = 600L
        private const val PENDING_TTL_MILLIS = 10 * 60_000L
    }
}
