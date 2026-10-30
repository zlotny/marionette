package dev.marionette.voice.stt

import dev.marionette.core.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

class FallbackSpeechToText(
    private val primary: SpeechToText,
    private val fallback: SpeechToText,
    private val raceAfterMillis: Long = RACE_AFTER_MILLIS,
    private val maxWaitMillis: Long = MAX_WAIT_MILLIS,
) : SpeechToText {
    override val name = primary.name

    override fun isAvailable(): Boolean = primary.isAvailable()

    override suspend fun start(language: String, extraLanguages: List<String>, onPartial: (String) -> Unit): SttSession {
        val session = primary.start(language, extraLanguages, onPartial)
        return object : SttSession {
            private val audio = ArrayList<ShortArray>()

            override fun accept(pcm: ShortArray, count: Int) {
                session.accept(pcm, count)
                synchronized(audio) { audio += pcm.copyOf(count) }
            }

            override fun speculate() = session.speculate()

            override fun dropSpeculation() = session.dropSpeculation()

            override fun cancel() = session.cancel()

            override val resumable: Boolean get() = session.resumable

            override fun recorded(): ShortArray = synchronized(audio) {
                ShortArray(audio.sumOf { it.size }).also { out ->
                    var offset = 0
                    audio.forEach { chunk ->
                        chunk.copyInto(out, offset)
                        offset += chunk.size
                    }
                }
            }

            override suspend fun finish(): String = coroutineScope {
                val remote = async { attempt { session.finish() } }
                val early = withTimeoutOrNull(raceAfterMillis) { remote.await() }
                if (early != null) {
                    early.getOrNull()?.let { return@coroutineScope it }
                    Logger.w("${primary.name} STT failed, using ${fallback.name}", early.exceptionOrNull())
                    return@coroutineScope local() ?: throw early.exceptionOrNull()!!
                }
                Logger.i("${primary.name} STT slow, racing ${fallback.name}")
                val device = async { attempt { local() ?: throw SttException("${fallback.name} STT failed") } }
                var failure: Throwable? = null
                val text = withTimeoutOrNull(maxWaitMillis) {
                    var winner: String? = null
                    val consumed = mutableSetOf<String>()
                    repeat(2) {
                        if (winner != null) return@repeat
                        val (from, result) = select<Pair<String, Result<String>>> {
                            if (primary.name !in consumed) remote.onAwait { primary.name to it }
                            if (fallback.name !in consumed) device.onAwait { fallback.name to it }
                        }
                        consumed += from
                        result.getOrNull()?.let {
                            Logger.i("STT result from $from")
                            winner = it
                        }
                        if (winner == null) failure = failure?.takeIf { it is IOException } ?: result.exceptionOrNull()
                    }
                    winner ?: throw failure!!
                }
                remote.cancel()
                device.cancel()
                text ?: throw IOException("No transcript after $maxWaitMillis ms")
            }

            private suspend fun local(): String? {
                if (!fallback.isAvailable()) return null
                val chunks = synchronized(audio) { audio.toList() }
                return attempt {
                    val local = fallback.start(language, extraLanguages) {}
                    try {
                        chunks.forEach { local.accept(it, it.size) }
                        local.finish()
                    } catch (e: Throwable) {
                        local.cancel()
                        throw e
                    }
                }.onFailure { Logger.w("${fallback.name} STT failed", it) }.getOrNull()?.takeIf { it.isNotBlank() }
            }
        }
    }

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    companion object {
        const val RACE_AFTER_MILLIS = 3_500L
        const val MAX_WAIT_MILLIS = 40_000L
    }
}
