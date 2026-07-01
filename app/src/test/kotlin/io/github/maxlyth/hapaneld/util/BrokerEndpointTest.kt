package io.github.maxlyth.hapaneld.util

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression guard for the IPv6-first-class broker-address handling (parse + family selection). */
class BrokerEndpointTest {

    @Test fun parsesBareHostAndPort() {
        assertEquals("broker.example" to 1883, BrokerEndpoint.parse("broker.example"))
        assertEquals("broker.example" to 1884, BrokerEndpoint.parse("broker.example:1884"))
        assertEquals("192.0.2.8" to 1883, BrokerEndpoint.parse("192.0.2.8:1883"))
    }

    @Test fun parsesSchemes() {
        assertEquals("broker.example" to 1883, BrokerEndpoint.parse("tcp://broker.example:1883"))
        assertEquals("host" to 1883, BrokerEndpoint.parse("mqtts://host"))
        assertEquals("192.0.2.8" to 8883, BrokerEndpoint.parse("ssl://192.0.2.8:8883"))
    }

    @Test fun stripsIpv6Brackets() {
        // java.net.URI.getHost() returns the bracketed form for an IPv6 literal; HiveMQ wants it bare.
        assertEquals("2001:db8::8:1" to 1883, BrokerEndpoint.parse("tcp://[2001:db8::8:1]:1883"))
        assertEquals("::1" to 8883, BrokerEndpoint.parse("ssl://[::1]:8883"))
        assertEquals("::1" to 1883, BrokerEndpoint.parse("[::1]"))
    }

    @Test fun rejectsEmpty() {
        assertNull(BrokerEndpoint.parse(""))
        assertNull(BrokerEndpoint.parse("   "))
    }

    @Test fun defaultsPortWhenAbsentOrBad() {
        assertEquals(1883, BrokerEndpoint.parse("host")!!.second)
        assertEquals(1883, BrokerEndpoint.parse("tcp://host")!!.second)
    }

    // --- happy-eyeballs family selection ---------------------------------------------------------
    private val v4: Inet4Address = InetAddress.getByName("192.0.2.8") as Inet4Address
    private val v6: Inet6Address = InetAddress.getByName("2001:db8::1") as Inet6Address

    @Test fun selectPrefersRequestedFamily() {
        assertEquals(v4, BrokerEndpoint.select(listOf(v6, v4), preferIpv4 = true))
        assertEquals(v6, BrokerEndpoint.select(listOf(v4, v6), preferIpv4 = false))
    }

    @Test fun selectFallsBackToOtherFamily() {
        // preferIpv4 but only IPv6 present -> take IPv6 (fallback), and vice-versa.
        assertEquals(v6, BrokerEndpoint.select(listOf(v6), preferIpv4 = true))
        assertEquals(v4, BrokerEndpoint.select(listOf(v4), preferIpv4 = false))
    }

    @Test fun selectEmptyIsNull() {
        assertNull(BrokerEndpoint.select(emptyList(), preferIpv4 = true))
    }

    @Test fun hostStringIsBareAddress() {
        assertEquals("192.0.2.8", BrokerEndpoint.hostString(v4))
        val h6 = BrokerEndpoint.hostString(v6)
        assertTrue("no brackets: $h6", !h6.startsWith("[") && !h6.endsWith("]"))
        assertTrue("no leading slash: $h6", !h6.startsWith("/"))
        assertTrue("no zone id: $h6", !h6.contains('%'))
        assertTrue("round-trips to the same address", InetAddress.getByName(h6) == v6)
    }
}
