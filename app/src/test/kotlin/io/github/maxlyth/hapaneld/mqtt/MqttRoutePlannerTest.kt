package io.github.maxlyth.hapaneld.mqtt

import io.github.maxlyth.hapaneld.MqttAddressFamily
import io.github.maxlyth.hapaneld.config.Scope
import io.github.maxlyth.hapaneld.config.SettingsRegistry
import io.github.maxlyth.hapaneld.config.Tier
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

class MqttRoutePlannerTest {
    private val direct = Executor { it.run() }
    private val ipv6 = InetAddress.getByName("2001:db8::10")
    private val ipv4 = InetAddress.getByName("192.0.2.10")

    @Test fun `address family setting is bounded advanced device policy`() {
        val spec = assertNotNull(SettingsRegistry.spec("mqtt_address_family"))
        assertEquals("Automatic", spec.default)
        assertEquals(Tier.ADVANCED, spec.tier)
        assertEquals(Scope.DEVICE, spec.scope)
        assertEquals(listOf("Automatic", "Prefer IPv4", "Force IPv4"), spec.options)
        assertEquals(MqttAddressFamilyPolicy.AUTOMATIC, MqttAddressFamilyPolicy.fromConfig("unknown"))
        assertEquals(MqttAddressFamilyPolicy.PREFER_IPV4, MqttAddressFamilyPolicy.fromConfig("prefer ipv4"))
        assertEquals(MqttAddressFamilyPolicy.FORCE_IPV4, MqttAddressFamilyPolicy.fromConfig("FORCE IPV4"))
    }

    @Test fun `automatic suppresses failed advertised IPv6 once and re-resolves before IPv4`() {
        val resolutions = AtomicInteger()
        val planner = planner(MqttAddressFamilyPolicy.AUTOMATIC) {
            resolutions.incrementAndGet()
            listOf(ipv6, ipv4)
        }

        assertEquals(MqttAddressFamily.IPV6, planner.resolveInitial()?.family)
        assertEquals(
            MqttAddressFamily.IPV4,
            planner.resolveReconnect(preConnackFailure = true, networkFailure = true).join()?.family,
        )
        // The rapid-family allowance is bounded. A second pre-CONNACK failure keeps the selected
        // family and ordinary HiveMQ backoff instead of ping-ponging to the failed AAAA.
        assertEquals(
            MqttAddressFamily.IPV4,
            planner.resolveReconnect(preConnackFailure = true, networkFailure = true).join()?.family,
        )
        assertEquals(3, resolutions.get())
    }

    @Test fun `initial alternate budget survives a DNS answer without the sibling family`() {
        var addresses = listOf(ipv6)
        val planner = planner(MqttAddressFamilyPolicy.AUTOMATIC) { addresses }

        assertEquals(MqttAddressFamily.IPV6, planner.resolveInitial()?.family)
        assertEquals(
            MqttAddressFamily.IPV6,
            planner.resolveReconnect(preConnackFailure = true, networkFailure = true).join()?.family,
        )
        addresses = listOf(ipv6, ipv4)
        assertEquals(
            MqttAddressFamily.IPV4,
            planner.resolveReconnect(preConnackFailure = true, networkFailure = true).join()?.family,
        )
        assertEquals(
            MqttAddressFamily.IPV4,
            planner.resolveReconnect(preConnackFailure = true, networkFailure = true).join()?.family,
        )
    }

    @Test fun `successful route remains preferred while every reconnect gets fresh DNS`() {
        var addresses = listOf(ipv6, ipv4)
        val planner = planner(MqttAddressFamilyPolicy.AUTOMATIC) { addresses }
        val first = assertNotNull(planner.resolveInitial())
        planner.markConnected(first)

        addresses = listOf(InetAddress.getByName("192.0.2.11"))
        val refreshed = assertNotNull(
            planner.resolveReconnect(preConnackFailure = false, networkFailure = true).join(),
        )

        assertEquals(MqttAddressFamily.IPV4, refreshed.family)
        assertEquals("192.0.2.11", refreshed.address?.hostAddress)
    }

    @Test fun `steady reconnect refreshes established family before one bounded sibling fallback`() {
        val planner = planner(MqttAddressFamilyPolicy.AUTOMATIC) { listOf(ipv6, ipv4) }
        val established = assertNotNull(planner.resolveInitial())
        planner.markConnected(established)

        // The disconnect that ended a healthy session first keeps its established family, so an
        // ordinary broker restart or address rotation does not destabilize steady state.
        assertEquals(
            MqttAddressFamily.IPV6,
            planner.resolveReconnect(preConnackFailure = false, networkFailure = true).join()?.family,
        )
        // If that freshly-resolved family cannot establish a new session, suppress it once.
        assertEquals(
            MqttAddressFamily.IPV4,
            planner.resolveReconnect(preConnackFailure = true, networkFailure = true).join()?.family,
        )
        assertEquals(
            MqttAddressFamily.IPV4,
            planner.resolveReconnect(preConnackFailure = true, networkFailure = true).join()?.family,
        )
    }

    @Test fun `steady alternate budget survives a failed resolver before the sibling appears`() {
        var fail = false
        var addresses = listOf(ipv6, ipv4)
        val planner = planner(MqttAddressFamilyPolicy.AUTOMATIC) {
            if (fail) error("temporary DNS failure") else addresses
        }
        val established = assertNotNull(planner.resolveInitial())
        planner.markConnected(established)

        planner.resolveReconnect(preConnackFailure = false, networkFailure = true).join()
        fail = true
        assertSame(
            planner.currentRoute,
            planner.resolveReconnect(preConnackFailure = true, networkFailure = true).join(),
        )
        fail = false
        addresses = listOf(ipv6, ipv4)
        assertEquals(
            MqttAddressFamily.IPV4,
            planner.resolveReconnect(preConnackFailure = true, networkFailure = true).join()?.family,
        )
    }

    @Test fun `prefer IPv4 falls back but force IPv4 fails closed`() {
        val preferred = planner(MqttAddressFamilyPolicy.PREFER_IPV4) { listOf(ipv6, ipv4) }
        assertEquals(MqttAddressFamily.IPV4, preferred.resolveInitial()?.family)

        var preferAddresses = listOf(ipv6)
        val preferFallback = planner(MqttAddressFamilyPolicy.PREFER_IPV4) { preferAddresses }
        val establishedV6 = assertNotNull(preferFallback.resolveInitial())
        assertEquals(MqttAddressFamily.IPV6, establishedV6.family)
        preferFallback.markConnected(establishedV6)
        preferAddresses = listOf(ipv6, ipv4)
        assertEquals(
            MqttAddressFamily.IPV4,
            preferFallback.resolveReconnect(preConnackFailure = false, networkFailure = true).join()?.family,
        )

        val forced = planner(MqttAddressFamilyPolicy.FORCE_IPV4) { listOf(ipv6) }
        assertNull(forced.resolveInitial())
    }

    @Test fun `numeric dial address retains logical TLS host`() {
        val route = assertNotNull(
            planner(MqttAddressFamilyPolicy.PREFER_IPV4) { listOf(ipv4) }.resolveInitial(),
        )
        val socket = assertNotNull(route.socketAddress())

        assertEquals("mqtt.example.test", socket.hostString)
        assertTrue(socket.address is java.net.Inet4Address)
        assertEquals(ipv4.hostAddress, socket.address.hostAddress)
    }

    @Test fun `logical TLS host retains scoped IPv6 interface identity`() {
        val scoped = Inet6Address.getByAddress(null, InetAddress.getByName("fe80::10").address, 7)
        val route = MqttDialRoute("mqtt.example.test", 8883, scoped)
        val socket = assertNotNull(route.socketAddress())
        val dialAddress = socket.address as Inet6Address

        assertEquals("mqtt.example.test", socket.hostString)
        assertTrue(scoped.address.contentEquals(dialAddress.address))
        assertEquals(7, dialAddress.scopeId)
    }

    @Test fun `resolution failure retains established route without raw-host family escape`() {
        var fail = false
        val planner = planner(MqttAddressFamilyPolicy.AUTOMATIC) {
            if (fail) error("resolver unavailable") else listOf(ipv4)
        }
        val established = assertNotNull(planner.resolveInitial())
        planner.markConnected(established)
        fail = true

        assertSame(
            established,
            planner.resolveReconnect(preConnackFailure = false, networkFailure = true).join(),
        )
    }

    @Test fun `busy bounded resolver keeps the route without stranding reconnect`() {
        val planner = MqttRoutePlanner(
            logicalHost = "mqtt.example.test",
            port = 8883,
            policy = MqttAddressFamilyPolicy.AUTOMATIC,
            initialPreferIpv4 = false,
            rapidInitialFallbackAllowed = true,
            resolver = MqttBrokerResolver { listOf(ipv4) },
            resolverExecutor = Executor { throw RejectedExecutionException("busy") },
        )
        val established = assertNotNull(planner.resolveInitial())

        assertSame(
            established,
            planner.resolveReconnect(preConnackFailure = true, networkFailure = true).join(),
        )
    }

    private fun planner(
        policy: MqttAddressFamilyPolicy,
        resolve: () -> List<InetAddress>,
    ) = MqttRoutePlanner(
        logicalHost = "mqtt.example.test",
        port = 8883,
        policy = policy,
        initialPreferIpv4 = false,
        rapidInitialFallbackAllowed = true,
        resolver = MqttBrokerResolver { resolve() },
        resolverExecutor = direct,
    )
}
