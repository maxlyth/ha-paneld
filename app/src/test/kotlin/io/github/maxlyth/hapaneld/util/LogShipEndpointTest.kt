package io.github.maxlyth.hapaneld.util

import io.github.maxlyth.hapaneld.util.LogShipEndpoint.HTTP
import io.github.maxlyth.hapaneld.util.LogShipEndpoint.SYSLOG_TCP
import io.github.maxlyth.hapaneld.util.LogShipEndpoint.SYSLOG_UDP
import org.junit.Assert.assertEquals
import org.junit.Test

class LogShipEndpointTest {

    private fun resolve(host: String, port: Int = 514, protocol: String = SYSLOG_UDP) =
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

    @Test fun blankAndUnknownProtocolsTakeTheUdpDefault() {
        // 514 is the port default and it is a UDP port on every stock collector.
        assertEquals(SYSLOG_UDP, LogShipEndpoint.protocol(""))
        assertEquals(SYSLOG_UDP, LogShipEndpoint.protocol("   "))
        assertEquals(SYSLOG_UDP, LogShipEndpoint.protocol("carrier-pigeon"))
        assertEquals(SYSLOG_UDP, LogShipEndpoint.DEFAULT_PROTOCOL)
    }

    @Test fun schemeWordIsShortenedForDisplay() {
        assertEquals("udp", LogShipEndpoint.scheme(SYSLOG_UDP))
        assertEquals("tcp", LogShipEndpoint.scheme(SYSLOG_TCP))
        assertEquals("http", LogShipEndpoint.scheme(HTTP))
    }

    // ---- host resolution --------------------------------------------------------------------------

    @Test fun aPlainHostKeepsTheStoredPortAndProtocol() {
        assertEquals(
            LogShipEndpoint.Endpoint("collector.lan", 514, SYSLOG_UDP),
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
            LogShipEndpoint.Endpoint("collector.lan", 1514, SYSLOG_UDP),
            resolve("collector.lan:1514"),
        )
        assertEquals(
            LogShipEndpoint.Endpoint("collector.lan", 1514, SYSLOG_TCP),
            resolve("tcp://collector.lan:1514"),
        )
    }

    @Test fun ipv6LiteralsKeepTheirColonsAndLoseTheirBrackets() {
        assertEquals(LogShipEndpoint.Endpoint("::1", 514, SYSLOG_UDP), resolve("[::1]"))
        assertEquals(LogShipEndpoint.Endpoint("::1", 1514, SYSLOG_UDP), resolve("[::1]:1514"))
        assertEquals(
            LogShipEndpoint.Endpoint("fd31::118:1", 514, SYSLOG_UDP),
            resolve("udp://[fd31::118:1]"),
        )
    }

    @Test fun ipv4LiteralsAreUntouched() {
        assertEquals(LogShipEndpoint.Endpoint("172.31.0.118", 514, SYSLOG_UDP), resolve("172.31.0.118"))
        assertEquals(
            LogShipEndpoint.Endpoint("172.31.0.118", 1514, SYSLOG_UDP),
            resolve("172.31.0.118:1514"),
        )
    }

    @Test fun surroundingWhitespaceAndATrailingSlashAreNoise() {
        assertEquals(LogShipEndpoint.Endpoint("collector.lan", 514, HTTP), resolve("  http://collector.lan/  "))
    }

    @Test fun aBlankHostStaysBlankSoShippingRemainsInert() {
        assertEquals(LogShipEndpoint.Endpoint("", 514, SYSLOG_UDP), resolve(""))
        assertEquals(LogShipEndpoint.Endpoint("", 514, SYSLOG_TCP), resolve("   ", protocol = "syslog"))
        assertEquals(LogShipEndpoint.Endpoint("", 514, SYSLOG_UDP), resolve("udp://"))
    }

    @Test fun anUnparseableValueIsPassedThroughRatherThanRewritten() {
        // An underscore is not a legal URI hostname but resolves fine, and a path cannot be honoured
        // by a transport that only ever posts to "/". Both are kept verbatim so the failure names
        // exactly what was typed instead of a silently rewritten guess.
        assertEquals(
            LogShipEndpoint.Endpoint("log_collector", 514, SYSLOG_UDP),
            resolve("log_collector"),
        )
        assertEquals(
            LogShipEndpoint.Endpoint("collector.lan/ingest", 8080, HTTP),
            resolve("http://collector.lan/ingest", port = 8080),
        )
    }

    @Test fun anOutOfRangePortFallsBackToTheStoredPort() {
        assertEquals(
            LogShipEndpoint.Endpoint("collector.lan", 514, SYSLOG_UDP),
            resolve("collector.lan:70000"),
        )
    }
}
