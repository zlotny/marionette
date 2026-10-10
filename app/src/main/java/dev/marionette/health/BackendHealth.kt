package dev.marionette.health

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dev.marionette.access.LocalNetwork
import dev.marionette.agent.Turn
import dev.marionette.agent.TurnStatus
import dev.marionette.llm.BackendFactory
import dev.marionette.llm.LlmException
import dev.marionette.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.IOException

sealed interface BackendHealth {
    data object Checking : BackendHealth
    data object Ok : BackendHealth
    data object Offline : BackendHealth
    data object Unreachable : BackendHealth
    data object Rejected : BackendHealth
    data class Failing(val message: String) : BackendHealth

    companion object {
        fun classify(error: Throwable?, online: Boolean): BackendHealth = when {
            error == null -> Ok
            error is LlmException && (error.status == 401 || error.status == 403) -> Rejected
            error is LlmException && (error.status ?: 0) >= 500 -> Failing(error.message.orEmpty())
            error is LlmException -> Ok
            !online -> Offline
            error is IOException || error is TimeoutCancellationException -> Unreachable
            else -> Failing(error.message ?: error.javaClass.simpleName)
        }
    }
}

class HealthMonitor(context: Context, private val settings: SettingsStore, turns: StateFlow<List<Turn>>, private val scope: CoroutineScope) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val _backend = MutableStateFlow<BackendHealth>(BackendHealth.Checking)
    val backend: StateFlow<BackendHealth> = _backend.asStateFlow()
    private var job: Job? = null

    init {
        runCatching {
            connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) && _backend.value != BackendHealth.Ok) check()
                }

                override fun onLost(network: Network) = check()
            })
        }
        settings.settings.map { it.backend }.distinctUntilChanged().drop(1).onEach { check() }.launchIn(scope)
        turns.map { it.lastOrNull() }
            .filter { it?.status == TurnStatus.FAILED }
            .map { it?.id }
            .distinctUntilChanged()
            .onEach { check() }
            .launchIn(scope)
    }

    fun check() {
        scope.launch {
            job?.cancel()
            _backend.value = BackendHealth.Checking
            job = launch { _backend.value = probe() }
        }
    }

    private suspend fun probe(): BackendHealth {
        val config = settings.current.backend
        if (config.baseUrl.isBlank()) return BackendHealth.Unreachable
        val local = LocalNetwork.isLocal(config.baseUrl)
        if (!local && !isOnline()) return BackendHealth.Offline
        val error = try {
            withTimeout(PROBE_TIMEOUT_MILLIS) { BackendFactory.create(config).listModels() }
            null
        } catch (e: LlmException) {
            e
        } catch (e: IOException) {
            e
        } catch (e: TimeoutCancellationException) {
            e
        } catch (e: IllegalArgumentException) {
            e
        }
        return BackendHealth.classify(error, local || isOnline())
    }

    private fun isOnline(): Boolean {
        val caps = connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    companion object {
        private const val PROBE_TIMEOUT_MILLIS = 12_000L
    }
}
