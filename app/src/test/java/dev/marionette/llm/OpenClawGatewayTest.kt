package dev.marionette.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenClawGatewayTest {
    @Test
    fun bareHostGetsHttpsAndVersion() {
        assertEquals("https://claw.example.com/v1", OpenClawGateway.normalize("claw.example.com"))
        assertEquals("https://claw.example.com/v1", OpenClawGateway.normalize(" claw.example.com/ "))
    }

    @Test
    fun localHostGetsHttp() {
        assertEquals("http://192.168.1.20:18789/v1", OpenClawGateway.normalize("192.168.1.20:18789"))
        assertEquals("http://localhost:18789/v1", OpenClawGateway.normalize("localhost:18789"))
    }

    @Test
    fun pastedEndpointsAreTrimmed() {
        assertEquals("https://claw.example.com/v1", OpenClawGateway.normalize("https://claw.example.com/v1/chat/completions"))
        assertEquals("https://claw.example.com/v1", OpenClawGateway.normalize("https://claw.example.com/v1/models"))
        assertEquals("https://claw.example.com/v1", OpenClawGateway.normalize("https://claw.example.com/v1/"))
        assertEquals("http://claw.example.com/v1", OpenClawGateway.normalize("http://claw.example.com"))
    }

    @Test
    fun emptyStaysEmpty() {
        assertEquals("", OpenClawGateway.normalize("  "))
    }

    @Test
    fun rootDropsVersion() {
        assertEquals("https://claw.example.com", OpenClawGateway.root("https://claw.example.com/v1"))
    }

    @Test
    fun agentsPutDefaultFirstAndDropAlias() {
        val ids = listOf("openclaw", "openclaw/shaddar", "openclaw/default", "openclaw/marionette")
        assertEquals(listOf("openclaw/default", "openclaw/marionette", "openclaw/shaddar"), OpenClawGateway.agents(ids))
        assertEquals(listOf("openclaw/default"), OpenClawGateway.agents(emptyList()))
    }

    @Test
    fun agentNames() {
        assertEquals("shaddar", OpenClawGateway.agentName("openclaw/shaddar"))
        assertEquals(null, OpenClawGateway.agentName("openclaw/default"))
    }

    @Test
    fun healthRecognizesGateway() {
        assertTrue(OpenClawGateway.isGatewayHealth(200, """{"ok":true,"status":"live"}"""))
        assertFalse(OpenClawGateway.isGatewayHealth(200, "<html></html>"))
        assertFalse(OpenClawGateway.isGatewayHealth(404, """{"ok":true}"""))
    }

    @Test
    fun modelsClassification() {
        val json = "application/json; charset=utf-8"
        assertEquals(GatewayCheck.NeedsSecret, OpenClawGateway.classifyModels(401, json, "", sentSecret = false))
        assertEquals(GatewayCheck.Rejected, OpenClawGateway.classifyModels(401, json, "", sentSecret = true))
        assertEquals(GatewayCheck.EndpointDisabled, OpenClawGateway.classifyModels(404, "text/plain", "Not Found", sentSecret = true))
        assertEquals(GatewayCheck.EndpointDisabled, OpenClawGateway.classifyModels(200, "text/html", "<html></html>", sentSecret = false))
        assertEquals(GatewayCheck.RateLimited, OpenClawGateway.classifyModels(429, json, "", sentSecret = true))
        assertEquals(
            GatewayCheck.Ready(listOf("openclaw/default", "openclaw/main")),
            OpenClawGateway.classifyModels(200, json, """{"data":[{"id":"openclaw"},{"id":"openclaw/main"}]}""", sentSecret = false),
        )
        assertEquals(GatewayCheck.Failed("boom"), OpenClawGateway.classifyModels(500, json, """{"error":{"message":"boom"}}""", sentSecret = true))
    }
}
