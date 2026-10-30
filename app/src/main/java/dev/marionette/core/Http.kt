package dev.marionette.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object Http {
    const val USER_AGENT = "Marionette/0.1 (Android voice assistant; https://github.com/zlotny/marionette)"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(5, 60, TimeUnit.SECONDS))
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
        }
        .build()

    val agent: OkHttpClient = client.newBuilder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build()

    val model: OkHttpClient = client.newBuilder().readTimeout(90, TimeUnit.SECONDS).build()

    val speech: OkHttpClient = client.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val tts: OkHttpClient = client.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    val tools: OkHttpClient = client.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    fun cancelAgentCalls() {
        agent.dispatcher.cancelAll()
    }

    fun dropConnections() {
        LinkSpeed.reset()
        client.dispatcher.executorService.execute { client.connectionPool.evictAll() }
    }

    fun warm(urls: List<String>) {
        urls.mapNotNull { it.toHttpUrlOrNull()?.resolve("/") }.distinctBy { it.host to it.port }.forEach { url ->
            client.newCall(Request.Builder().url(url).head().build()).enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) = response.close()
                override fun onFailure(call: Call, e: IOException) = Unit
            })
        }
    }
}

suspend fun <T> Call.readCancellable(block: suspend (Response) -> T): T = coroutineScope {
    val guard = launch(Dispatchers.IO) {
        try {
            awaitCancellation()
        } finally {
            cancel()
        }
    }
    try {
        await().use { block(it) }
    } finally {
        guard.cancel()
    }
}

suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }
    })
    continuation.invokeOnCancellation { runCatching { cancel() } }
}
