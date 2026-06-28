package io.github.maxlyth.hapaneld.logship

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LogShipper.redact] is the last line of defence before a log line leaves the device, so the secret
 * shapes it strips are pinned here. Capture is the app's own logcat, which can carry MQTT creds, HA
 * tokens, and URLs with query secrets — none of which should reach the central aggregator verbatim.
 */
class LogShipperTest {
    private fun redact(s: String) = LogShipper.redact(s)

    @Test fun stripsBearerAuthorizationHeader() {
        val out = redact("06-28 10:15:30.123  900  950 D ha-paneld/http: Authorization: Bearer abc123XYZ.token-value")
        assertFalse("token must not survive", out.contains("abc123XYZ.token-value"))
        assertTrue(out.contains("***"))
    }

    @Test fun stripsPasswordAndTokenKeyValues() {
        assertFalse(redact("mqtt password=hunter2secret").contains("hunter2secret"))
        assertFalse(redact("access_token: aaaaaaaabbbbbbbb").contains("aaaaaaaabbbbbbbb"))
        assertFalse(redact("api_key = ZZZZ9999ZZZZ").contains("ZZZZ9999ZZZZ"))
        // The key label is preserved so the line stays readable.
        assertTrue(redact("password=hunter2secret").startsWith("password"))
    }

    @Test fun stripsJwtLikeTokens() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"
        val out = redact("06-28 10:15:30.123  900  950 I ha-paneld/mqtt: connecting with $jwt")
        assertFalse(out.contains(jwt))
        assertTrue(out.contains("***jwt***"))
    }

    @Test fun stripsUrlQuerySecrets() {
        val out = redact("GET http://ha.local:8123/api/stream?access_token=SUPERSECRETTOKEN&foo=bar")
        assertFalse(out.contains("SUPERSECRETTOKEN"))
        // Non-secret query params and the rest of the URL are untouched.
        assertTrue(out.contains("foo=bar"))
        assertTrue(out.contains("http://ha.local:8123/api/stream"))
    }

    @Test fun leavesOrdinaryLinesUnchanged() {
        val line = "06-28 10:15:30.123  900  950 I ha-paneld/svc: foreground service started"
        assertEquals(line, redact(line))
    }
}
