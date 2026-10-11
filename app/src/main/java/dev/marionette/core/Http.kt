package dev.marionette.core

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
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
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
        }
        .build()

    fun warm(urls: List<String>) {
        urls.mapNotNull { it.toHttpUrlOrNull()?.resolve("/") }.distinctBy { it.host to it.port }.forEach { url ->
            client.newCall(Request.Builder().url(url).head().build()).enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) = response.close()
                override fun onFailure(call: Call, e: IOException) = Unit
            })
        }
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
