package io.github.maxlyth.hapaneld

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the built-in renderer's side of the HA frontend external-auth contract: the frontend loads
 * `?external_auth=1`, calls `window.externalApp.getExternalAuth({"callback":"externalAuthSetToken"})`,
 * and expects `externalAuthSetToken(true, {"access_token":…,"expires_in":<seconds>})` evaluated back.
 * `config/get` on the external bus is the one message the frontend blocks on during startup.
 */
class ExternalAuthProtocolTest {

    // --- getExternalAuth ---------------------------------------------------------------------------

    @Test
    fun `auth reply carries the token and a seconds expiry`() {
        val js = ExternalAuthProtocol.authReply("""{"callback":"externalAuthSetToken"}""", "tok123")!!
        assertTrue(js.startsWith("externalAuthSetToken(true, "))
        val json = JSONObject(js.removePrefix("externalAuthSetToken(true, ").removeSuffix(")"))
        assertEquals("tok123", json.getString("access_token"))
        assertTrue(json.getLong("expires_in") > 86_400)
    }

    @Test
    fun `auth reply fails closed with no token`() {
        assertEquals(
            "externalAuthSetToken(false)",
            ExternalAuthProtocol.authReply("""{"callback":"externalAuthSetToken","force":true}""", ""),
        )
    }

    @Test
    fun `unexpected callback name is never evaluated`() {
        // The callback name becomes a JS function call — anything but the frontend's fixed constant
        // must be dropped, or a hostile page could execute an arbitrary function with our token.
        assertNull(ExternalAuthProtocol.authReply("""{"callback":"alert"}""", "tok"))
        assertNull(ExternalAuthProtocol.authReply("not json", "tok"))
        assertNull(ExternalAuthProtocol.authReply("{}", "tok"))
    }

    @Test
    fun `token with quotes is JSON-escaped not injected`() {
        val js = ExternalAuthProtocol.authReply("""{"callback":"externalAuthSetToken"}""", """t"),alert("x""")!!
        val json = JSONObject(js.removePrefix("externalAuthSetToken(true, ").removeSuffix(")"))
        assertEquals("""t"),alert("x""", json.getString("access_token"))
    }

    // --- revokeExternalAuth ------------------------------------------------------------------------

    @Test
    fun `revoke acks only the expected callback`() {
        assertEquals("externalAuthRevokeToken(true)", ExternalAuthProtocol.revokeReply("""{"callback":"externalAuthRevokeToken"}"""))
        assertNull(ExternalAuthProtocol.revokeReply("""{"callback":"externalAuthSetToken"}"""))
    }

    // --- externalBus -------------------------------------------------------------------------------

    @Test
    fun `config-get is answered with every capability off`() {
        val js = ExternalAuthProtocol.busReply("""{"id":7,"type":"config/get"}""", "0.9.0-test")!!
        assertTrue(js.startsWith("externalBus(") && js.endsWith(");"))
        val reply = JSONObject(js.removePrefix("externalBus(").removeSuffix(");"))
        assertEquals(7, reply.getInt("id"))
        assertEquals("result", reply.getString("type"))
        assertTrue(reply.getBoolean("success"))
        val result = reply.getJSONObject("result")
        assertEquals("0.9.0-test", result.getString("appVersion"))
        assertEquals(0, result.getInt("hasBarCodeScanner"))
        for (key in result.keys()) {
            if (key == "appVersion" || key == "hasBarCodeScanner") continue
            assertFalse("capability $key must be off on a panel", result.getBoolean(key))
        }
    }

    @Test
    fun `other bus messages are swallowed silently`() {
        assertNull(ExternalAuthProtocol.busReply("""{"type":"connection-status","payload":{"event":"connected"}}""", "v"))
        assertNull(ExternalAuthProtocol.busReply("""{"type":"theme-update"}""", "v"))
        assertNull(ExternalAuthProtocol.busReply("""{"type":"some/future/message"}""", "v"))
        assertNull(ExternalAuthProtocol.busReply("not json", "v"))
    }
}
