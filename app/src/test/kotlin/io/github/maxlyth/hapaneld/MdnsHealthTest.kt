package io.github.maxlyth.hapaneld

import java.io.File
import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MdnsHealthTest {
    private fun health(
        advertising: Boolean = true,
        boundIp: String? = "10.0.4.109",
        lanIp: String? = "10.0.4.109",
    ) = MdnsHealth(advertising, boundIp, lanIp)

    @Test fun healthyAdvertiserWarnsAboutNothing() {
        assertNull(mdnsHealthWarning(health()))
    }

    @Test fun noLanAddressDoesNotAddASecondNetworkWarning() {
        assertNull(mdnsHealthWarning(health(advertising = false, boundIp = null, lanIp = null)))
    }

    @Test fun stoppedAdvertiserWarnsTheOperator() {
        val warning = mdnsHealthWarning(health(advertising = false, boundIp = null))
        assertNotNull(warning)
        assertTrue(warning!!.contains("not running"))
    }

    @Test fun staleBindReportsBothAddresses() {
        val warning = mdnsHealthWarning(health(boundIp = "127.0.0.1", lanIp = "10.0.4.109"))
        assertNotNull(warning)
        assertTrue(warning!!.contains("127.0.0.1"))
        assertTrue(warning.contains("10.0.4.109"))
    }

    @Test fun existingAdvertiserStaysWhenItsLanBindIsCurrent() {
        assertFalse(mdnsRebindRequired("10.0.4.109", "10.0.4.109", browsing = true))
    }

    @Test fun existingAdvertiserRebindsForDhcpAddressChangeOrStoppedBrowse() {
        assertTrue(mdnsRebindRequired("10.0.4.109", "10.0.4.110", browsing = true))
        assertTrue(mdnsRebindRequired("10.0.4.109", "10.0.4.109", browsing = false))
    }

    @Test fun defaultNetworkAddressIgnoresLoopbackAndOtherAddressFamilies() {
        assertTrue(
            defaultNetworkIpv4(
                listOf(
                    InetAddress.getByName("::1"),
                    InetAddress.getByName("127.0.0.1"),
                    InetAddress.getByName("10.0.4.110"),
                ),
            ) == "10.0.4.110",
        )
    }

    @Test fun networkCallbacksRevalidateMdnsIndependentlyOfMqttState() {
        val service = File("src/main/kotlin/io/github/maxlyth/hapaneld/PaneldService.kt").readText()
        val available = service.substring(
            service.indexOf("override fun onAvailable"),
            service.indexOf("override fun onLinkPropertiesChanged"),
        )
        val linkChange = service.substring(
            service.indexOf("override fun onLinkPropertiesChanged"),
            service.indexOf("override fun onCapabilitiesChanged"),
        )

        assertTrue(available.contains("revalidateMdns(observed, defaultNetworkIpv4"))
        assertTrue(linkChange.contains("if (network != defaultNetwork) return"))
        assertTrue(linkChange.contains("revalidateMdns(observed, defaultNetworkIpv4"))
        assertTrue(service.contains("LatestDispatcher.singleSlot<MdnsRevalidation>"))
        assertFalse(service.contains("it.mdns.start()\n                            it.mqtt.reconnect()"))
    }

    @Test fun statusEndpointIncludesLiveMdnsWarning() {
        val server = File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt").readText()

        assertTrue(server.contains("private val mdnsWarning: () -> String? = { null }"))
        assertTrue(server.contains("runCatching(mdnsWarning).getOrNull()?.let(warns::add)"))
    }
}
