package io.github.maxlyth.hapaneld.panelassistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PanelAssistantTransportProtocolTest {

    @Test fun `hello offers protocol 1, the given capabilities and channels and a contract digest`() {
        val bare = JSONObject(PanelAssistantTransportProtocol.hello(1L, IDENTITY))
        assertEquals(0, bare.getJSONArray("capabilities").length())
        assertEquals(0, bare.getJSONArray("channels").length())
        val hello = JSONObject(
            PanelAssistantTransportProtocol.hello(
                1L, IDENTITY, PanelAssistantTransportProtocol.CAPABILITIES,
                listOfNotNull(PanelAssistantChannelCatalog.describe("relay3")),
            ),
        )
        assertEquals(1, hello.getJSONObject("protocol").getInt("min"))
        assertEquals(1, hello.getJSONObject("protocol").getInt("max"))
        assertTrue(Regex("^[0-9a-f]{64}$").matches(hello.getString("contract_digest")))
        assertEquals("state", hello.getJSONArray("capabilities").getString(0))
        assertEquals(1, hello.getJSONArray("capabilities").length())
        val relay = hello.getJSONArray("channels").getJSONObject(0)
        assertEquals(listOf("relay3", "switch", "relay", "relay3", "relay", "3"), listOf("channel", "platform", "translation_key", "unique_suffix", "family", "index").map { relay.get(it).toString() })
    }

    @Test fun `the contract digest is pinned to the canonical handshake text`() {
        // Pinned as a literal: a digest derived from JSON serialisation could differ between the
        // device's org.json and the JVM's, and the integration records whatever the panel sends.
        assertEquals(
            "93d4e8501c53d53a9933bda5785f3ac811cf143f2cf2bc5f3979a9b2a5fe99a3",
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

    @Test fun `a report_state result acknowledges by parsed id and lists rejections by channel`() {
        val acknowledged = JSONObject().put("id", 12).put("type", "result").put("success", true)
            .put("result", JSONObject().put("rejected", JSONArray()
                .put(JSONObject().put("channel", "diag_ip").put("code", "invalid_value"))
                .put(JSONObject().put("channel", "Not A Channel").put("code", "unknown_channel"))))
        assertEquals(
            PanelAssistantReportResult.Acknowledged(12L, mapOf("diag_ip" to "invalid_value")),
            PanelAssistantTransportProtocol.reportResult(acknowledged),
        )
        assertEquals(
            PanelAssistantReportResult.Failed(13L, "session_unknown"),
            PanelAssistantTransportProtocol.reportResult(refused("session_unknown").put("id", 13)),
        )
        assertNull(PanelAssistantTransportProtocol.reportResult(acknowledged.put("id", "12")))
        assertNull(PanelAssistantTransportProtocol.reportResult(JSONObject().put("id", 12).put("type", "pong")))
    }

    @Test fun `a granted capability the panel did not offer is a protocol failure`() {
        val frame = accepted()
        frame.getJSONObject("result").put("capabilities", JSONArray().put("state"))
        try {
            PanelAssistantTransportProtocol.helloOutcome(frame, 1L, offered = emptyList())
            fail("state was granted without being offered")
        } catch (expected: PanelAssistantProtocolException) {
        }
        assertEquals(
            listOf("state"),
            (PanelAssistantTransportProtocol.helloOutcome(frame, 1L) as PanelAssistantHelloOutcome.Accepted).session.capabilities,
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
