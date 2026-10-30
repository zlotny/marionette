package dev.marionette.llm

import com.google.crypto.tink.subtle.Ed25519Sign
import dev.marionette.core.AppJson
import dev.marionette.core.Http
import dev.marionette.core.arr
import dev.marionette.core.boolean
import dev.marionette.core.obj
import dev.marionette.core.parseObject
import dev.marionette.core.string
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64

class DeviceIdentity(private val publicKey: ByteArray, private val privateKey: ByteArray) {
    val id: String = MessageDigest.getInstance("SHA-256").digest(publicKey).joinToString("") { "%02x".format(it) }
    val publicKeyText: String = encode(publicKey)

    fun sign(payload: String): String = encode(Ed25519Sign(privateKey).sign(payload.toByteArray()))

    fun serialize(): String = "${encode(privateKey)}.${encode(publicKey)}"

    companion object {
        fun create(): DeviceIdentity = Ed25519Sign.KeyPair.newKeyPair().let { DeviceIdentity(it.publicKey, it.privateKey) }

        fun parse(text: String): DeviceIdentity? = runCatching {
            val (private, public) = text.split('.').also { require(it.size == 2) }
            DeviceIdentity(decode(public), decode(private))
        }.getOrNull()

        private fun encode(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        private fun decode(text: String) = Base64.getUrlDecoder().decode(text)
    }
}

sealed interface LinkResult {
    data class Paired(val token: String) : LinkResult
    data class PairingRequired(val requestId: String) : LinkResult
    data class Failed(val message: String) : LinkResult
    data object TokenOutdated : LinkResult
}

sealed interface LinkEvent {
    data class Progress(val text: String) : LinkEvent
    data class Tool(val id: String, val name: String, val summary: String, val done: Boolean, val failed: Boolean) : LinkEvent
}

class LinkException(val result: LinkResult) : IOException(result.toString())

data class GatewayModel(val ref: String, val name: String, val thinking: List<Thinking>)

object OpenClawLink {
    const val READ = "operator.read"
    const val WRITE = "operator.write"
    val PAIR_SCOPES = listOf(READ, WRITE)
    private const val CLIENT_ID = "cli"
    private const val CLIENT_MODE = "cli"
    private const val ROLE = "operator"
    private const val PLATFORM = "android"
    private const val PROTOCOL = 4
    private const val HANDSHAKE_MILLIS = 10_000L
    private val OUTDATED_TOKEN_CODES = setOf("AUTH_SCOPE_MISMATCH", "AUTH_DEVICE_TOKEN_MISMATCH")

    fun socketUrl(baseUrl: String): String = OpenClawGateway.root(baseUrl).let {
        when {
            it.startsWith("https://") -> "wss://" + it.removePrefix("https://")
            it.startsWith("http://") -> "ws://" + it.removePrefix("http://")
            else -> it
        }
    }

    fun sessionKey(user: String): String = "openai-user:$user"

    fun signaturePayload(deviceId: String, signedAt: Long, token: String, nonce: String, scopes: List<String> = listOf(READ)): String =
        listOf("v3", deviceId, CLIENT_ID, CLIENT_MODE, ROLE, scopes.joinToString(","), signedAt.toString(), token, nonce, PLATFORM, "").joinToString("|")

    fun connectParams(identity: DeviceIdentity, nonce: String, signedAt: Long, secret: String, token: String, scopes: List<String> = listOf(READ)): JsonObject = buildJsonObject {
        put("minProtocol", PROTOCOL)
        put("maxProtocol", PROTOCOL)
        putJsonObject("client") {
            put("id", CLIENT_ID)
            put("version", "0.1")
            put("platform", PLATFORM)
            put("mode", CLIENT_MODE)
            put("displayName", "Marionette")
        }
        put("role", ROLE)
        putJsonArray("scopes") { scopes.forEach { add(JsonPrimitive(it)) } }
        putJsonArray("caps") {}
        putJsonObject("auth") { if (token.isNotBlank()) put("token", token) else put("password", secret) }
        putJsonObject("device") {
            put("id", identity.id)
            put("publicKey", identity.publicKeyText)
            put("signature", identity.sign(signaturePayload(identity.id, signedAt, token, nonce, scopes)))
            put("signedAt", signedAt)
            put("nonce", nonce)
        }
    }

    fun connectResult(frame: JsonObject, token: String): LinkResult {
        if (frame.boolean("ok") == true) {
            val issued = frame["payload"].obj()?.get("auth").obj()?.string("deviceToken")
            return LinkResult.Paired(issued ?: token)
        }
        val error = frame["error"].obj()
        val details = error?.get("details").obj()
        if (error?.string("code") == "NOT_PAIRED" || details?.string("code") == "PAIRING_REQUIRED") {
            details?.string("requestId")?.let { return LinkResult.PairingRequired(it) }
        }
        if (details?.string("code") in OUTDATED_TOKEN_CODES) return LinkResult.TokenOutdated
        return LinkResult.Failed(error?.string("message") ?: "connect failed")
    }

    fun event(frame: JsonObject, key: String? = null): LinkEvent? {
        if (frame.string("type") != "event" || frame.string("event") != "agent") return null
        val payload = frame["payload"].obj() ?: return null
        val from = payload.string("sessionKey")
        if (key != null && from != null && !from.endsWith(key)) return null
        if (payload.string("stream") != "item") return null
        val data = payload["data"].obj() ?: return null
        val phase = data.string("phase")
        return when (data.string("kind")) {
            "preamble" -> data.string("progressText")?.trim()?.takeIf { it.isNotEmpty() && phase == "end" }?.let { LinkEvent.Progress(it) }
            "tool" -> {
                val id = data.string("toolCallId") ?: return null
                val name = data.string("name") ?: return null
                val summary = data.string("meta")?.removePrefix("for ")?.trim('"', ' ') ?: data.string("title").orEmpty()
                val status = data.string("status")
                LinkEvent.Tool(id, name, summary, done = phase == "end", failed = status == "failed" || status == "error")
            }
            else -> null
        }
    }

    suspend fun pair(baseUrl: String, secret: String, identity: DeviceIdentity, token: String, client: OkHttpClient = Http.client): LinkResult =
        runCatching { Connection.open(socketUrl(baseUrl), client).use { it.handshake(identity, secret, token, PAIR_SCOPES) } }
            .getOrElse { LinkResult.Failed(it.message ?: it.javaClass.simpleName) }

    fun models(frame: JsonObject): List<GatewayModel> = frame["payload"].obj()?.get("models").arr().orEmpty().mapNotNull { element ->
        val model = element.obj() ?: return@mapNotNull null
        if (model.boolean("available") == false) return@mapNotNull null
        val id = model.string("id") ?: return@mapNotNull null
        val provider = model.string("provider") ?: return@mapNotNull null
        val levels = model["thinkingLevels"].arr()?.mapNotNull { Thinking.of(it.obj()?.string("id")) }
        val thinking = if (model.boolean("reasoning") == false) listOf(Thinking.OFF) else levels ?: Thinking.entries
        GatewayModel("$provider/$id", model.string("name") ?: id, thinking)
    }

    fun error(frame: JsonObject): String? = frame["error"].obj()?.let { it.string("message") ?: it.toString() }

    fun thinkingPatch(user: String, agentId: String?, thinking: Thinking?): JsonObject = buildJsonObject {
        put("key", sessionKey(user))
        agentId?.let { put("agentId", it) }
        put("thinkingLevel", thinking?.let { JsonPrimitive(it.id) } ?: JsonNull)
    }

    suspend fun listModels(baseUrl: String, identity: DeviceIdentity, token: String, client: OkHttpClient = Http.client): List<GatewayModel> {
        val frame = call(baseUrl, identity, token, "models.list", buildJsonObject {}, listOf(READ), client)
        error(frame)?.let { throw IOException(it) }
        return models(frame)
    }

    suspend fun setThinking(
        baseUrl: String,
        identity: DeviceIdentity,
        token: String,
        user: String,
        agentId: String?,
        thinking: Thinking?,
        client: OkHttpClient = Http.client,
    ): LinkResult = runCatching {
        val frame = call(baseUrl, identity, token, "sessions.patch", thinkingPatch(user, agentId, thinking), PAIR_SCOPES, client)
        error(frame)?.let { LinkResult.Failed(it) } ?: LinkResult.Paired(token)
    }.getOrElse { (it as? LinkException)?.result ?: LinkResult.Failed(it.message ?: it.javaClass.simpleName) }

    private suspend fun call(
        baseUrl: String,
        identity: DeviceIdentity,
        token: String,
        method: String,
        params: JsonObject,
        scopes: List<String>,
        client: OkHttpClient,
    ): JsonObject = Connection.open(socketUrl(baseUrl), client).use { connection ->
        val result = connection.handshake(identity, "", token, scopes)
        if (result !is LinkResult.Paired) throw LinkException(result)
        withTimeout(HANDSHAKE_MILLIS) { connection.request(method, params) }
    }

    suspend fun watch(
        baseUrl: String,
        identity: DeviceIdentity,
        token: String,
        user: String,
        agentId: String?,
        onReady: (LinkResult.Paired) -> Unit,
        onEvent: (LinkEvent) -> Unit,
        client: OkHttpClient = Http.client,
    ) {
        Connection.open(socketUrl(baseUrl), client).use { connection ->
            val result = connection.handshake(identity, "", token)
            if (result !is LinkResult.Paired) throw LinkException(result)
            val subscribed = connection.request("sessions.messages.subscribe", buildJsonObject {
                put("key", sessionKey(user))
                agentId?.let { put("agentId", it) }
            })
            if (subscribed.boolean("ok") != true) throw LinkException(LinkResult.Failed(subscribed["error"].obj()?.string("message") ?: "subscribe failed"))
            onReady(result)
            val key = sessionKey(user)
            while (true) event(connection.next(), key)?.let(onEvent)
        }
    }

    private class Connection(private val frames: Channel<JsonObject>) : AutoCloseable {
        lateinit var socket: WebSocket
        private var ids = 0

        suspend fun next(): JsonObject = frames.receive()

        suspend fun handshake(identity: DeviceIdentity, secret: String, token: String, scopes: List<String> = listOf(READ)): LinkResult = withTimeout(HANDSHAKE_MILLIS) {
            var challenge: JsonObject
            do challenge = next() while (challenge.string("event") != "connect.challenge")
            val payload = challenge["payload"].obj()
            val nonce = payload?.string("nonce").orEmpty()
            val signedAt = payload?.get("ts")?.toString()?.trim('"')?.toLongOrNull() ?: System.currentTimeMillis()
            connectResult(request("connect", connectParams(identity, nonce, signedAt, secret, token, scopes)), token)
        }

        suspend fun request(method: String, params: JsonObject): JsonObject {
            val id = (++ids).toString()
            val frame = buildJsonObject {
                put("type", "req")
                put("id", id)
                put("method", method)
                put("params", params)
            }
            socket.send(AppJson.encodeToString(JsonObject.serializer(), frame))
            while (true) {
                val next = next()
                if (next.string("type") == "res" && next.string("id") == id) return next
            }
        }

        override fun close() {
            socket.close(1000, null)
            frames.close()
        }

        companion object {
            suspend fun open(url: String, client: OkHttpClient): Connection {
                val frames = Channel<JsonObject>(Channel.UNLIMITED)
                val opened = CompletableDeferred<Unit>()
                val connection = Connection(frames)
                connection.socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        opened.complete(Unit)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        frames.trySend(parseObject(text))
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        frames.close(IOException("closed $code $reason"))
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        val error = IOException(response?.let { "HTTP ${it.code}" } ?: t.message, t)
                        opened.completeExceptionally(error)
                        frames.close(error)
                    }
                })
                try {
                    withTimeout(HANDSHAKE_MILLIS) { opened.await() }
                } catch (e: Exception) {
                    connection.socket.cancel()
                    throw e
                }
                return connection
            }
        }
    }
}
