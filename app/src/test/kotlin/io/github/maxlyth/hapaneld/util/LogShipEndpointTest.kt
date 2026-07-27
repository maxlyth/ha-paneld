package io.github.maxlyth.hapaneld.util

import io.github.maxlyth.hapaneld.util.LogShipEndpoint.HTTP
import io.github.maxlyth.hapaneld.util.LogShipEndpoint.SYSLOG_TCP
import io.github.maxlyth.hapaneld.util.LogShipEndpoint.SYSLOG_UDP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LogShipEndpointTest {

    private fun resolve(host: String, port: Int = 514, protocol: String = SYSLOG_TCP) =
        LogShipEndpoint.resolve(host, port, protocol)

    // ---- protocol normalization -------------------------------------------------------------------

    @Test fun retiredSyslogSpellingStaysTcp() {
        // `syslog` predates the UDP transport and meant TCP. A panel that was deliberately shipping
        // over TCP must not silently change transport because it was upgraded.
        assertEquals(SYSLOG_TCP, LogShipEndpoint.protocol("syslog"))
        assertEquals(SYSLOG_TCP, LogShipEndpoint.protocol("SYSLOG"))
        assertEquals(SYSLOG_TCP, LogShipEndpoint.protocol("tcp"))
    }

    @Test fun canonicalAndShorthandProtocolsResolve() {
        assertEquals(SYSLOG_UDP, LogShipEndpoint.protocol("syslog-udp"))
        assertEquals(SYSLOG_UDP, LogShipEndpoint.protocol("udp"))
        assertEquals(SYSLOG_TCP, LogShipEndpoint.protocol("syslog-tcp"))
        assertEquals(HTTP, LogShipEndpoint.protocol("http"))
    }

    @Test fun blankAndUnknownProtocolsTakeTheTcpDefault() {
        // TCP is the default because its failures are visible: refused against a UDP-only collector
        // is recoverable, whereas UDP against a TCP-only collector vanishes with nothing to notice.
        assertEquals(SYSLOG_TCP, LogShipEndpoint.protocol(""))
        assertEquals(SYSLOG_TCP, LogShipEndpoint.protocol("   "))
        assertEquals(SYSLOG_TCP, LogShipEndpoint.protocol("carrier-pigeon"))
        assertEquals(SYSLOG_TCP, LogShipEndpoint.DEFAULT_PROTOCOL)
    }

    @Test fun schemeWordIsShortenedForDisplay() {
        assertEquals("udp", LogShipEndpoint.scheme(SYSLOG_UDP))
        assertEquals("tcp", LogShipEndpoint.scheme(SYSLOG_TCP))
        assertEquals("http", LogShipEndpoint.scheme(HTTP))
    }

    @Test fun statusHostAndFailureRenderingNeverExposeAuthorityCredentials() {
        assertEquals("collector.lan", LogShipEndpoint.displayHost("operator:super-secret@collector.lan"))
        assertEquals("collector.lan", LogShipEndpoint.displayHost("operator:super-secret@collector.lan?token=also-secret"))
        val rendered = LogShipEndpoint.displayFailure(
            "operator:super-secret@collector.lan refused connection",
            "operator:super-secret@collector.lan",
        )
        assertEquals("collector.lan refused connection", rendered)
        assertFalse(rendered.contains("super-secret"))
    }

    // ---- host resolution --------------------------------------------------------------------------

    @Test fun aPlainHostKeepsTheStoredPortAndProtocol() {
        assertEquals(
            LogShipEndpoint.Endpoint("collector.lan", 514, SYSLOG_TCP),
            resolve("collector.lan"),
        )
    }

    /**
     * The reported workaround. Typing a scheme into the host box used to make the whole string a
     * hostname, and the resulting `UnknownHostException` message *was* that string — a warning that
     * quoted the destination and named no fault.
     */
    @Test fun aTypedSchemeSelectsTheTransportInsteadOfBecomingTheHostname() {
        assertEquals(
            LogShipEndpoint.Endpoint("vector.lan", 514, SYSLOG_UDP),
            resolve("udp://vector.lan", protocol = SYSLOG_TCP),
        )
        assertEquals(
            LogShipEndpoint.Endpoint("vector.lan", 514, SYSLOG_TCP),
            resolve("tcp://vector.lan", protocol = SYSLOG_UDP),
        )
        assertEquals(
            LogShipEndpoint.Endpoint("vector.lan", 514, HTTP),
            resolve("http://vector.lan", protocol = SYSLOG_UDP),
        )
    }

    @Test fun anUnrecognisedSchemeLeavesTheStoredProtocolAlone() {
        // Falling back to the default here would silently retarget the transport the user chose.
        assertEquals(
            LogShipEndpoint.Endpoint("vector.lan", 514, SYSLOG_TCP),
            resolve("gopher://vector.lan", protocol = SYSLOG_TCP),
        )
    }

    @Test fun aTrailingPortOverridesTheStoredPort() {
        assertEquals(
            LogShipEndpoint.Endpoint("collector.lan", 1514, SYSLOG_TCP),
            resolve("collector.lan:1514"),
        )
        assertEquals(
            LogShipEndpoint.Endpoint("collector.lan", 1514, SYSLOG_TCP),
            resolve("tcp://collector.lan:1514"),
        )
    }

    @Test fun ipv6LiteralsKeepTheirColonsAndLoseTheirBrackets() {
        assertEquals(LogShipEndpoint.Endpoint("::1", 514, SYSLOG_TCP), resolve("[::1]"))
        assertEquals(LogShipEndpoint.Endpoint("::1", 1514, SYSLOG_TCP), resolve("[::1]:1514"))
        assertEquals(
            LogShipEndpoint.Endpoint("fd31::118:1", 514, SYSLOG_UDP),
            resolve("udp://[fd31::118:1]"),
        )
    }

    @Test fun ipv6LiteralsAreRebracketedForUrlUse() {
        // resolve() strips the brackets because InetAddress and Socket want the bare address, but a
        // URL needs them back — "http://::1:514/" cannot be parsed into an address and a port.
        assertEquals("[::1]", LogShipEndpoint.urlHost("::1"))
        assertEquals("[fd31::118:1]", LogShipEndpoint.urlHost("fd31::118:1"))
        // Already-bracketed input must not be double-wrapped, and a name or IPv4 is left alone.
        assertEquals("[::1]", LogShipEndpoint.urlHost("[::1]"))
        assertEquals("collector.lan", LogShipEndpoint.urlHost("collector.lan"))
        assertEquals("172.31.0.118", LogShipEndpoint.urlHost("172.31.0.118"))
    }

    @Test fun anIpv6HttpSinkRoundTripsIntoAParseableUrl() {
        val ep = resolve("http://[::1]:8080", protocol = HTTP)
        assertEquals("::1", ep.host)
        assertEquals(8080, ep.port)
        val url = java.net.URL("http://${LogShipEndpoint.urlHost(ep.host)}:${ep.port}/")
        assertEquals("::1", url.host.trim('[', ']'))
        assertEquals(8080, url.port)
    }

    @Test fun ipv4LiteralsAreUntouched() {
        assertEquals(LogShipEndpoint.Endpoint("172.31.0.118", 514, SYSLOG_TCP), resolve("172.31.0.118"))
        assertEquals(
            LogShipEndpoint.Endpoint("172.31.0.118", 1514, SYSLOG_TCP),
            resolve("172.31.0.118:1514"),
        )
    }

    @Test fun surroundingWhitespaceAndATrailingSlashAreNoise() {
        assertEquals(LogShipEndpoint.Endpoint("collector.lan", 514, HTTP), resolve("  http://collector.lan/  "))
    }

    @Test fun aBlankHostStaysBlankSoShippingRemainsInert() {
        assertEquals(LogShipEndpoint.Endpoint("", 514, SYSLOG_TCP), resolve(""))
        assertEquals(LogShipEndpoint.Endpoint("", 514, SYSLOG_TCP), resolve("   ", protocol = "syslog"))
        assertEquals(LogShipEndpoint.Endpoint("", 514, SYSLOG_UDP), resolve("udp://"))
    }

    @Test fun anUnparseableValueIsPassedThroughRatherThanRewritten() {
        // An underscore is not a legal URI hostname but resolves fine, and a path cannot be honoured
        // by a transport that only ever posts to "/". Both are kept verbatim so the failure names
        // exactly what was typed instead of a silently rewritten guess.
        assertEquals(
            LogShipEndpoint.Endpoint("log_collector", 514, SYSLOG_TCP),
            resolve("log_collector"),
        )
        assertEquals(
            LogShipEndpoint.Endpoint("collector.lan/ingest", 8080, HTTP),
            resolve("http://collector.lan/ingest", port = 8080),
        )
    }

    @Test fun anOutOfRangePortFallsBackToTheStoredPort() {
        assertEquals(
            LogShipEndpoint.Endpoint("collector.lan", 514, SYSLOG_TCP),
            resolve("collector.lan:70000"),
        )
    }
}
