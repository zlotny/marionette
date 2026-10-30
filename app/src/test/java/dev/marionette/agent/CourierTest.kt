package dev.marionette.agent

import dev.marionette.llm.ChatMessage
import dev.marionette.llm.LlmException
import dev.marionette.llm.OpenAiChatBackend
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class CourierTest {
    private val server = MockWebServer().apply { start() }
    private val client = OkHttpClient.Builder().connectTimeout(1, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build()
    private val backend = OpenAiChatBackend(server.url("/v1").toString(), "key", "openclaw/marionette", client = client)
    private val courier = Courier(acceptMillis = 600, stallMillis = 600, retries = 2, retryMillis = 10, uploadMillis = { null })
    private val seen = mutableListOf<Delivery>()

    @After
    fun stop() = server.shutdown()

    private fun stream(text: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"$text\"}}]}\n\ndata: [DONE]\n\n")

    private fun ask(): String = runBlocking {
        courier.send { upload ->
            backend.complete("system", listOf(ChatMessage.User("hola")), emptyList(), onAccepted = {
                upload.accepted()
                seen += courier.state.value
            }, onUpload = upload::progress)
        }.text
    }

    @Test
    fun dropsBeforeTheServerAnswersAreRetried() {
        repeat(2) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)) }
        server.enqueue(stream("ok"))
        assertEquals("ok", ask())
        assertEquals(3, server.requestCount)
        assertEquals(listOf(Delivery.ACCEPTED), seen)
    }

    @Test
    fun noConnectionReportsNotDelivered() {
        repeat(3) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)) }
        val error = runCatching { ask() }.exceptionOrNull()
        assertTrue(error is NotDelivered)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun silentServerTimesOutAndRetries() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(stream("ok"))
        assertEquals("ok", ask())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun busyBackendIsRetriedBeforeAcceptance() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
        server.enqueue(stream("ok"))
        assertEquals("ok", ask())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun longRateLimitIsNotRetried() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30").setBody("{}"))
        server.enqueue(stream("ok"))
        assertTrue(runCatching { ask() }.exceptionOrNull() is LlmException)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun dropAfterAcceptIsNotResent() {
        server.enqueue(stream("ok").setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        server.enqueue(stream("duplicate"))
        val error = runCatching { ask() }.exceptionOrNull()
        assertTrue(error is IOException)
        assertFalse(error is NotDelivered)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun silenceAfterHeadersIsAToolRunNotAFailure() {
        server.enqueue(stream("lento").setBodyDelay(1_500, TimeUnit.MILLISECONDS))
        assertEquals("lento", ask())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun serverErrorsAreNotRetried() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":{\"message\":\"bad key\"}}"))
        val error = runCatching { ask() }.exceptionOrNull()
        assertFalse(error is IOException)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun slowUploadThatKeepsMovingIsNotCut() {
        val slow = Courier(acceptMillis = 600, stallMillis = 400, retries = 0, uploadMillis = { null })
        val result = runBlocking {
            slow.send { upload ->
                repeat(10) {
                    kotlinx.coroutines.delay(150)
                    upload.progress(it.toLong(), 10)
                }
                upload.progress(10, 10)
                upload.accepted()
                "ok"
            }
        }
        assertEquals("ok", result)
    }

    @Test
    fun stalledUploadIsRetried() {
        val stalled = Courier(acceptMillis = 600, stallMillis = 300, retries = 1, retryMillis = 10, uploadMillis = { null })
        var calls = 0
        val result = runBlocking {
            stalled.send { upload ->
                calls++
                if (calls == 1) kotlinx.coroutines.delay(5_000)
                upload.accepted()
                "ok"
            }
        }
        assertEquals("ok", result)
        assertEquals(2, calls)
    }

    @Test
    fun slowLinkGetsMoreTimeAfterTheBodyIsBuffered() {
        val adaptive = Courier(acceptMillis = 300, stallMillis = 300, retries = 0, uploadMillis = { 400L })
        val result = runBlocking {
            adaptive.send { upload ->
                upload.progress(100, 100)
                kotlinx.coroutines.delay(900)
                upload.accepted()
                "ok"
            }
        }
        assertEquals("ok", result)
    }
}
