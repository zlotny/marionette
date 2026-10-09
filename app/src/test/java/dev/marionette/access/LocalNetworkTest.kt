package dev.marionette.access

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkTest {
    @Test
    fun detectsPrivateHosts() {
        listOf(
            "http://localhost:11434/v1",
            "http://127.0.0.1:8080",
            "http://10.0.2.2:18789/v1",
            "https://192.168.1.20/v1",
            "http://172.16.0.5",
            "http://172.31.255.1",
            "http://169.254.10.10",
            "http://nas.local:8080",
            "http://server.lan/v1",
            "http://[::1]:8080",
            "http://[fd00::12]/v1",
            "192.168.0.10:1234",
        ).forEach { assertTrue(it, LocalNetwork.isLocal(it)) }
    }

    @Test
    fun rejectsPublicHosts() {
        listOf(
            "https://api.openai.com/v1",
            "https://marionette.ndrs.es/v1",
            "http://172.32.0.1",
            "http://8.8.8.8",
            "http://fdroid.org",
            "",
            "not a url",
        ).forEach { assertFalse(it, LocalNetwork.isLocal(it)) }
    }
}
