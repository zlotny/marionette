package dev.marionette.agent

import dev.marionette.core.LinkSpeed
import dev.marionette.core.Logger
import dev.marionette.llm.LlmException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.SocketTimeoutException

enum class Delivery { IDLE, SENDING, RETRYING, ACCEPTED }

class NotAccepted : IOException("The agent backend did not answer")

class NotDelivered(cause: IOException) : IOException(cause.message, cause)

interface Upload {
    fun accepted()

    fun progress(written: Long, total: Long)
}

class Courier(
    private val acceptMillis: Long = 15_000L,
    private val stallMillis: Long = 20_000L,
    private val retries: Int = 2,
    private val retryMillis: Long = 1_500L,
    private val uploadMillis: (Long) -> Long? = LinkSpeed::uploadMillis,
) {
    private val _state = MutableStateFlow(Delivery.IDLE)
    val state: StateFlow<Delivery> = _state.asStateFlow()

    fun idle() {
        _state.value = Delivery.IDLE
    }

    suspend fun <T> send(call: suspend (Upload) -> T): T {
        var attempt = 0
        while (true) {
            _state.value = if (attempt == 0) Delivery.SENDING else Delivery.RETRYING
            val accepted = CompletableDeferred<Unit>()
            val upload = object : Upload {
                @Volatile var lastProgress = System.currentTimeMillis()
                @Volatile var sentAt = 0L
                @Volatile var wait = acceptMillis

                override fun accepted() {
                    accepted.complete(Unit)
                    _state.value = Delivery.ACCEPTED
                }

                override fun progress(written: Long, total: Long) {
                    lastProgress = System.currentTimeMillis()
                    if (written >= total && sentAt == 0L) {
                        sentAt = lastProgress
                        wait = acceptMillis + 2 * (uploadMillis(total) ?: 0L)
                    }
                }

                fun stalled(now: Long) = if (sentAt > 0) now - sentAt > wait else now - lastProgress > stallMillis
            }
            try {
                return coroutineScope {
                    val reply = async { call(upload) }
                    while (!accepted.isCompleted && !reply.isCompleted) {
                        withTimeoutOrNull(WATCH_MILLIS) {
                            select<Unit> {
                                accepted.onAwait {}
                                reply.onAwait {}
                            }
                        }
                        if (!accepted.isCompleted && !reply.isCompleted && upload.stalled(System.currentTimeMillis())) {
                            reply.cancel()
                            throw NotAccepted()
                        }
                    }
                    reply.await()
                }
            } catch (e: IOException) {
                if (accepted.isCompleted) throw e
                if (++attempt > retries || connectTimedOut(e)) throw NotDelivered(e)
                Logger.w("Agent request not delivered, attempt $attempt", e)
                delay(retryMillis * attempt)
            } catch (e: LlmException) {
                val wait = retryWait(e)
                if (accepted.isCompleted || wait == null || ++attempt > retries) throw e
                Logger.w("Agent backend busy (${e.status}), attempt $attempt", e)
                delay(maxOf(wait, retryMillis * attempt))
            }
        }
    }

    companion object {
        private const val WATCH_MILLIS = 250L
        private const val MAX_RETRY_AFTER_SECONDS = 5L
        private val RETRY_STATUSES = setOf(502, 503, 520, 521, 522, 523, 529)

        fun connectTimedOut(e: IOException): Boolean =
            e is SocketTimeoutException && e.message.orEmpty().contains("connect", ignoreCase = true)

        fun retryWait(e: LlmException): Long? = when (e.status) {
            in RETRY_STATUSES -> 0L
            429 -> e.retryAfterSeconds?.takeIf { it <= MAX_RETRY_AFTER_SECONDS }?.let { it * 1000 }
            else -> null
        }
    }
}
