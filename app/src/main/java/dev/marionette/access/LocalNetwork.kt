package dev.marionette.access

import java.net.URI

object LocalNetwork {
    fun isLocal(url: String): Boolean {
        val host = hostOf(url)?.lowercase()?.removePrefix("[")?.removeSuffix("]") ?: return false
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home.arpa")) return true
        if (host.contains(':')) return host == "::1" || host.startsWith("fe80:") || host.startsWith("fc") || host.startsWith("fd")
        val octets = host.split('.').map { it.toIntOrNull() ?: return false }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        val (a, b) = octets
        return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 169 && b == 254)
    }

    private fun hostOf(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
        return runCatching { URI(withScheme).host }.getOrNull()?.takeIf { it.isNotEmpty() }
    }
}
