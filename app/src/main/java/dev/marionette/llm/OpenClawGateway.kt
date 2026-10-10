package dev.marionette.llm

import dev.marionette.access.LocalNetwork
import dev.marionette.core.Http
import dev.marionette.core.arr
import dev.marionette.core.await
import dev.marionette.core.obj
import dev.marionette.core.parseObject
import dev.marionette.core.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface GatewayCheck {
    data class Ready(val agents: List<String>) : GatewayCheck
    data object NeedsSecret : GatewayCheck
    data object Rejected : GatewayCheck
    data object EndpointDisabled : GatewayCheck
    data object NotGateway : GatewayCheck
    data object RateLimited : GatewayCheck
    data class Unreachable(val message: String) : GatewayCheck
    data class Failed(val message: String) : GatewayCheck
}

object OpenClawGateway {
    const val DEFAULT_AGENT = "openclaw/default"
    const val ENABLE_COMMAND = "openclaw config set gateway.http.endpoints.chatCompletions.enabled true"
    private const val ALIAS = "openclaw"
    private const val AGENT_PREFIX = "openclaw/"

    fun normalize(input: String): String {
        var url = input.trim().trimEnd('/')
        if (url.isEmpty()) return ""
        if ("://" !in url) url = (if (LocalNetwork.isLocal(url)) "http://" else "https://") + url
        listOf("/chat/completions", "/models").forEach { url = url.removeSuffix(it) }
        url = url.trimEnd('/')
        return if (url.endsWith("/v1")) url else "$url/v1"
    }

    fun root(baseUrl: String): String = baseUrl.trimEnd('/').removeSuffix("/v1")

    fun agents(ids: List<String>): List<String> {
        val named = ids.filter { it.startsWith(AGENT_PREFIX) && it != DEFAULT_AGENT }.distinct().sorted()
        return if (ids.any { it == DEFAULT_AGENT || it == ALIAS } || named.isEmpty()) listOf(DEFAULT_AGENT) + named else named
    }

    fun agentName(model: String): String? = model.removePrefix(AGENT_PREFIX).takeIf { model.startsWith(AGENT_PREFIX) && model != DEFAULT_AGENT }

    fun isGatewayHealth(status: Int, body: String): Boolean = status == 200 && parseObject(body).let { it["ok"] != null || it["status"] != null }

    fun classifyModels(status: Int, contentType: String, body: String, sentSecret: Boolean): GatewayCheck = when {
        status == 401 || status == 403 -> if (sentSecret) GatewayCheck.Rejected else GatewayCheck.NeedsSecret
        status == 404 || status == 405 -> GatewayCheck.EndpointDisabled
        status == 429 -> GatewayCheck.RateLimited
        status == 200 && !contentType.contains("json") -> GatewayCheck.EndpointDisabled
        status == 200 -> parseObject(body)["data"].arr()
            ?.let { data -> GatewayCheck.Ready(agents(data.mapNotNull { it.obj()?.string("id") })) }
            ?: GatewayCheck.EndpointDisabled
        else -> GatewayCheck.Failed(errorMessage(body).ifBlank { "HTTP $status" })
    }

    suspend fun check(baseUrl: String, secret: String, client: OkHttpClient = Http.client): GatewayCheck = withContext(Dispatchers.IO) {
        runCatching {
            val health = client.newCall(Request.Builder().url("${root(baseUrl)}/health").get().build()).await().use {
                isGatewayHealth(it.code, it.body.string())
            }
            if (!health) return@runCatching GatewayCheck.NotGateway
            val request = Request.Builder().url("${baseUrl.trimEnd('/')}/models").get()
            if (secret.isNotBlank()) request.header("Authorization", "Bearer $secret")
            client.newCall(request.build()).await().use {
                classifyModels(it.code, it.header("Content-Type").orEmpty(), it.body.string(), secret.isNotBlank())
            }
        }.getOrElse { error ->
            when (error) {
                is java.io.IOException -> GatewayCheck.Unreachable(error.message ?: error.javaClass.simpleName)
                is IllegalArgumentException -> GatewayCheck.Unreachable(error.message ?: "invalid address")
                else -> GatewayCheck.Failed(error.message ?: error.javaClass.simpleName)
            }
        }
    }

    private fun errorMessage(body: String): String = parseObject(body)["error"].obj()?.string("message") ?: body.take(200)
}
