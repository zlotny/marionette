package dev.marionette.llm

import com.google.crypto.tink.subtle.Ed25519Verify
import dev.marionette.core.obj
import dev.marionette.core.parseObject
import dev.marionette.core.string
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class OpenClawLinkTest {
    @Test
    fun socketUrlFollowsScheme() {
        assertEquals("wss://claw.example.com", OpenClawLink.socketUrl("https://claw.example.com/v1"))
        assertEquals("ws://192.168.1.20:18789", OpenClawLink.socketUrl("http://192.168.1.20:18789/v1"))
    }

    @Test
    fun sessionKeyMatchesHttpUser() {
        assertEquals("openai-user:marionette-abc", OpenClawLink.sessionKey("marionette-abc"))
    }

    @Test
    fun signaturePayloadIsV3() {
        assertEquals(
            "v3|dev|cli|cli|operator|operator.read|42|tok|nonce|android|",
            OpenClawLink.signaturePayload("dev", 42, "tok", "nonce"),
        )
    }

    @Test
    fun signaturePayloadJoinsScopesWithComma() {
        assertEquals(
            "v3|dev|cli|cli|operator|operator.read,operator.write|42|tok|nonce|android|",
            OpenClawLink.signaturePayload("dev", 42, "tok", "nonce", OpenClawLink.PAIR_SCOPES),
        )
    }

    @Test
    fun modelsParseRefsAndThinking() {
        val frame = parseObject(
            """{"type":"res","ok":true,"payload":{"models":[
            {"id":"claude-haiku-5-5","name":"Claude Haiku 5.5","provider":"anthropic","reasoning":true,"available":true,"thinkingLevels":[{"id":"off"},{"id":"minimal"},{"id":"low"},{"id":"high"}]},
            {"id":"nova-lite-v1","name":"Nova Lite","provider":"openrouter","reasoning":false,"available":true},
            {"id":"gone","provider":"x","available":false}]}}""",
        )
        val models = OpenClawLink.models(frame)
        assertEquals(listOf("anthropic/claude-haiku-5-5", "openrouter/nova-lite-v1"), models.map { it.ref })
        assertEquals(listOf(Thinking.OFF, Thinking.LOW, Thinking.HIGH), models[0].thinking)
        assertEquals(listOf(Thinking.OFF), models[1].thinking)
    }

    @Test
    fun thinkingPatchTargetsSessionAndClears() {
        val set = OpenClawLink.thinkingPatch("marionette-abc", "marionette", Thinking.LOW)
        assertEquals("openai-user:marionette-abc", set.string("key"))
        assertEquals("marionette", set.string("agentId"))
        assertEquals("low", set.string("thinkingLevel"))
        assertEquals("null", OpenClawLink.thinkingPatch("u", null, null)["thinkingLevel"].toString())
    }

    @Test
    fun identityRoundTripsAndSigns() {
        val identity = DeviceIdentity.create()
        val restored = DeviceIdentity.parse(identity.serialize())!!
        assertEquals(identity.id, restored.id)
        assertEquals(64, identity.id.length)
        val signature = Base64.getUrlDecoder().decode(restored.sign("hello"))
        Ed25519Verify(Base64.getUrlDecoder().decode(identity.publicKeyText)).verify(signature, "hello".toByteArray())
    }

    @Test
    fun connectSendsPasswordUntilPaired() {
        val identity = DeviceIdentity.create()
        val first = OpenClawLink.connectParams(identity, "n", 1, "secret", "")
        assertEquals("secret", first["auth"].obj()?.string("password"))
        assertNull(first["auth"].obj()?.string("token"))
        val later = OpenClawLink.connectParams(identity, "n", 1, "secret", "device-token")
        assertEquals("device-token", later["auth"].obj()?.string("token"))
        assertNull(later["auth"].obj()?.string("password"))
        assertEquals(identity.id, later["device"].obj()?.string("id"))
    }

    @Test
    fun connectResultClassifies() {
        val paired = OpenClawLink.connectResult(parseObject("""{"type":"res","id":"1","ok":true,"payload":{"type":"hello-ok","auth":{"deviceToken":"new"}}}"""), "old")
        assertEquals(LinkResult.Paired("new"), paired)
        val kept = OpenClawLink.connectResult(parseObject("""{"type":"res","id":"1","ok":true,"payload":{"type":"hello-ok","auth":{}}}"""), "old")
        assertEquals(LinkResult.Paired("old"), kept)
        val pending = OpenClawLink.connectResult(
            parseObject("""{"type":"res","id":"1","ok":false,"error":{"code":"NOT_PAIRED","message":"pairing required","details":{"code":"PAIRING_REQUIRED","requestId":"req-1"}}}"""),
            "",
        )
        assertEquals(LinkResult.PairingRequired("req-1"), pending)
        val failed = OpenClawLink.connectResult(parseObject("""{"type":"res","id":"1","ok":false,"error":{"code":"UNAUTHORIZED","message":"bad secret"}}"""), "")
        assertEquals(LinkResult.Failed("bad secret"), failed)
    }

    @Test
    fun onlyFinishedPreamblesAreProgress() {
        fun frame(stream: String, kind: String, phase: String) = parseObject(
            """{"type":"event","event":"agent","payload":{"stream":"$stream","data":{"kind":"$kind","phase":"$phase","progressText":" Busco gasolineras. "}}}""",
        )
        assertEquals("Busco gasolineras.", (OpenClawLink.event(frame("item", "preamble", "end")) as LinkEvent.Progress).text)
        assertNull(OpenClawLink.event(frame("item", "preamble", "update")))
        assertNull(OpenClawLink.event(frame("item", "tool", "end")))
        assertNull(OpenClawLink.event(frame("assistant", "preamble", "end")))
    }

    @Test
    fun agentToolItemsBecomeToolEvents() {
        val start = parseObject(
            """{"type":"event","event":"agent","payload":{"stream":"item","data":{"toolCallId":"t1","name":"web_search","kind":"tool","phase":"start","title":"Web Search for \"GP\"","meta":"for \"GP\"","status":"running"}}}""",
        )
        assertEquals(LinkEvent.Tool("t1", "web_search", "GP", done = false, failed = false), OpenClawLink.event(start))
        val end = parseObject(
            """{"type":"event","event":"agent","payload":{"stream":"item","data":{"toolCallId":"t1","name":"web_search","kind":"tool","phase":"end","title":"Web Search","status":"failed"}}}""",
        )
        assertEquals(LinkEvent.Tool("t1", "web_search", "Web Search", done = true, failed = true), OpenClawLink.event(end))
    }
}
