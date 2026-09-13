package io.github.maxlyth.hapaneld.panelassistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PanelAssistantTransportProtocolTest {

    @Test fun `hello offers protocol 1, no capabilities, no channels and a contract digest`() {
        val hello = JSONObject(PanelAssistantTransportProtocol.hello(1L, IDENTITY))
        assertEquals(1, hello.getJSONObject("protocol").getInt("min"))
        assertEquals(1, hello.getJSONObject("protocol").getInt("max"))
        assertTrue(Regex("^[0-9a-f]{64}$").matches(hello.getString("contract_digest")))
        assertEquals(0, hello.getJSONArray("capabilities").length())
        assertEquals(0, hello.getJSONArray("channels").length())
    }

    @Test fun `the contract digest is pinned to the canonical handshake text`() {
        // Pinned as a literal: a digest derived from JSON serialisation could differ between the
        // device's org.json and the JVM's, and the integration records whatever the panel sends.
        assertEquals(
            "ddacd8f9971532ece71687883b01f00e404ac3b381865c921e4132e3434a6164",
            PanelAssistantTransportProtocol.CONTRACT_DIGEST,
        )
    }

    @Test fun `a panel without identity sends a null did rather than omitting it`() {
        val hello = JSONObject(PanelAssistantTransportProtocol.hello(1L, IDENTITY.copy(did = null)))
        assertTrue(hello.has("did"))
        assertTrue(hello.isNull("did"))
    }

    @Test fun `an accepted result yields the session and integration version`() {
        val outcome = PanelAssistantTransportProtocol.helloOutcome(accepted(), 1L)
        assertEquals(
            PanelAssistantHelloOutcome.Accepted(
                PanelAssistantSession(1, "opaque", "shadow", emptyList(), "0.3.0"),
            ),
            outcome,
        )
    }

    @Test fun `a refusal yields its code and an unusable code reads as invalid_format`() {
        assertEquals(
            PanelAssistantHelloOutcome.Refused("unknown_panel"),
            PanelAssistantTransportProtocol.helloOutcome(refused("unknown_panel"), 1L),
        )
        assertEquals(
            PanelAssistantHelloOutcome.Refused("invalid_format"),
            PanelAssistantTransportProtocol.helloOutcome(refused("Not A Code"), 1L),
        )
    }

    @Test fun `replies are correlated by parsed id, so id 10 never answers hello 1`() {
        assertNull(PanelAssistantTransportProtocol.helloOutcome(accepted(id = 10), 1L))
        assertNull(PanelAssistantTransportProtocol.sessionEvent(closed(id = 10), 1L))
        assertEquals(
            PanelAssistantSessionEvent.Closed("superseded"),
            PanelAssistantTransportProtocol.sessionEvent(closed(id = 1), 1L),
        )
    }

    @Test fun `a success result that breaks the contract is a protocol failure`() {
        listOf<(JSONObject) -> Unit>(
            { it.put("protocol", 2) },
            { it.put("session", "") },
            { it.remove("authority") },
            { it.put("capabilities", JSONArray().put("commands")) },
            { it.remove("integration") },
            { it.put("integration", JSONObject().put("version", "not a version")) },
        ).forEachIndexed { index, breakResult ->
            val frame = accepted()
            breakResult(frame.getJSONObject("result"))
            try {
                PanelAssistantTransportProtocol.helloOutcome(frame, 1L)
                fail("case $index was accepted")
            } catch (expected: PanelAssistantProtocolException) {
            }
        }
    }

    private fun accepted(id: Int = 1): JSONObject = JSONObject()
        .put("id", id)
        .put("type", "result")
        .put("success", true)
        .put(
            "result",
            JSONObject()
                .put("protocol", 1)
                .put("session", "opaque")
                .put("authority", "shadow")
                .put("capabilities", JSONArray())
                .put("integration", JSONObject().put("version", "0.3.0")),
        )

    private fun refused(code: String): JSONObject = JSONObject()
        .put("id", 1)
        .put("type", "result")
        .put("success", false)
        .put("error", JSONObject().put("code", code).put("message", "x"))

    private fun closed(id: Int): JSONObject = JSONObject()
        .put("id", id)
        .put("type", "event")
        .put("event", JSONObject().put("kind", "session_closed").put("reason", "superseded"))

    private companion object {
        val IDENTITY = PanelAssistantHelloIdentity("a".repeat(64), "0.9.7-rc5", 774)
    }
}
