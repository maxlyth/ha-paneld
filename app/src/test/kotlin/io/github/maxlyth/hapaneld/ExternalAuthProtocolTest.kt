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
    fun `auth reply carries the token and its real expiry`() {
        val js = ExternalAuthProtocol.authReply("""{"callback":"externalAuthSetToken"}""", "tok123", 1800L)!!
        assertTrue(js.startsWith("externalAuthSetToken(true, "))
        val json = JSONObject(js.removePrefix("externalAuthSetToken(true, ").removeSuffix(")"))
        assertEquals("tok123", json.getString("access_token"))
        assertEquals(1800L, json.getLong("expires_in"))
    }

    @Test
    fun `auth reply fails closed with no token`() {
        assertEquals(
            "externalAuthSetToken(false)",
            ExternalAuthProtocol.authReply("""{"callback":"externalAuthSetToken","force":true}""", "", 0L),
        )
        assertEquals(
            "externalAuthSetToken(false)",
            ExternalAuthProtocol.authReply("""{"callback":"externalAuthSetToken"}""", null, 0L),
        )
    }

    @Test
    fun `unexpected callback name is never evaluated`() {
        // The callback name becomes a JS function call — anything but the frontend's fixed constant
        // must be dropped, or a hostile page could execute an arbitrary function with our token.
        assertNull(ExternalAuthProtocol.authReply("""{"callback":"alert"}""", "tok", 1800L))
        assertNull(ExternalAuthProtocol.authReply("not json", "tok", 1800L))
        assertNull(ExternalAuthProtocol.authReply("{}", "tok", 1800L))
    }

    @Test
    fun `token with quotes is JSON-escaped not injected`() {
        val js = ExternalAuthProtocol.authReply("""{"callback":"externalAuthSetToken"}""", """t"),alert("x""", 1800L)!!
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

    // --- dashboardUrl -----------------------------------------------------------------------------

    @Test
    fun `dashboard url appends path and external_auth`() {
        assertEquals("https://ha/bmp-panel/dash?external_auth=1",
            ExternalAuthProtocol.dashboardUrl("https://ha", "bmp-panel/dash"))
        // trailing/leading slashes on either side are normalised, not doubled
        assertEquals("https://ha/lovelace/0?external_auth=1",
            ExternalAuthProtocol.dashboardUrl("https://ha/", "/lovelace/0/"))
        // blank path → the HA root
        assertEquals("https://ha/?external_auth=1", ExternalAuthProtocol.dashboardUrl("https://ha", ""))
    }

    @Test
    fun `other bus messages are swallowed silently`() {
        assertNull(ExternalAuthProtocol.busReply("""{"type":"connection-status","payload":{"event":"connected"}}""", "v"))
        assertNull(ExternalAuthProtocol.busReply("""{"type":"theme-update"}""", "v"))
        assertNull(ExternalAuthProtocol.busReply("""{"type":"some/future/message"}""", "v"))
        assertNull(ExternalAuthProtocol.busReply("not json", "v"))
    }

    // --- connectionEvent (frontend-handshake watchdog signal) ---

    @Test
    fun `connection-status event extracted from payload or top level`() {
        assertEquals("connected",
            ExternalAuthProtocol.connectionEvent("""{"type":"connection-status","payload":{"event":"connected"}}"""))
        assertEquals("disconnected",
            ExternalAuthProtocol.connectionEvent("""{"type":"connection-status","event":"disconnected"}"""))
        assertEquals("auth-invalid",
            ExternalAuthProtocol.connectionEvent("""{"type":"connection-status","payload":{"event":"auth-invalid"}}"""))
    }

    @Test
    fun `non connection-status messages yield null`() {
        assertNull(ExternalAuthProtocol.connectionEvent("""{"type":"config/get"}"""))
        assertNull(ExternalAuthProtocol.connectionEvent("""{"type":"theme-update"}"""))
        assertNull(ExternalAuthProtocol.connectionEvent("""{"type":"connection-status"}""")) // no event field
        assertNull(ExternalAuthProtocol.connectionEvent("not json"))
    }

    // --- navigateCommand (light refresh / idle return-to-home) ---

    @Test
    fun `navigate command carries the frontend's exact shape with replace`() {
        val js = ExternalAuthProtocol.navigateCommand(7, "lovelace/0")
        assertTrue(js.startsWith("externalBus(") && js.endsWith(");"))
        val msg = JSONObject(js.removePrefix("externalBus(").removeSuffix(");"))
        assertEquals(7, msg.getInt("id"))
        assertEquals("command", msg.getString("type"))
        assertEquals("navigate", msg.getString("command"))
        val payload = msg.getJSONObject("payload")
        assertEquals("/lovelace/0", payload.getString("path"))
        assertTrue(payload.getJSONObject("options").getBoolean("replace"))
    }

    @Test
    fun `navigate path slashes are normalised`() {
        fun pathOf(js: String) = JSONObject(js.removePrefix("externalBus(").removeSuffix(");"))
            .getJSONObject("payload").getString("path")
        assertEquals("/lovelace/0", pathOf(ExternalAuthProtocol.navigateCommand(1, "/lovelace/0/")))
        assertEquals("/", pathOf(ExternalAuthProtocol.navigateCommand(1, "")))
        assertEquals("/", pathOf(ExternalAuthProtocol.navigateCommand(1, "/")))
    }

    // --- resultOf (bus command replies) ---

    @Test
    fun `command result replies are parsed`() {
        assertEquals(7 to true, ExternalAuthProtocol.resultOf("""{"id":7,"type":"result","success":true,"result":null}"""))
        assertEquals(9 to false, ExternalAuthProtocol.resultOf("""{"id":9,"type":"result","success":false}"""))
    }

    @Test
    fun `non-result messages yield null`() {
        assertNull(ExternalAuthProtocol.resultOf("""{"type":"connection-status","payload":{"event":"connected"}}"""))
        assertNull(ExternalAuthProtocol.resultOf("""{"type":"result"}""")) // no id
        assertNull(ExternalAuthProtocol.resultOf("not json"))
    }
}
