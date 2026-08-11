package io.github.maxlyth.hapaneld.util

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import okhttp3.Dns
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * One shared construction path for every panel→Home Assistant WebSocket client.
 *
 * Exists because the previous per-site CIO construction dialed exactly one resolved address: a host
 * whose leading AAAA is black-holed (SYN dropped, nothing rejects) could never fall back to a
 * working A record, permanently stranding the renderer on a panel whose MQTT — which plans address
 * families — stayed healthy. The OkHttp engine walks every address the resolver returns and races
 * families (fast fallback), and the per-route connect timeout bounds each dead attempt so the walk
 * completes inside every caller's 15 s outer deadline.
 *
 * The request URL keeps the configured hostname — no literal-address dialing — so TLS SNI,
 * certificate hostname verification and the Host header remain the platform defaults throughout.
 */
internal object HaWebSocketClients {

    /**
     * Bound on one route attempt. Worst case before a live sibling family is reached under
     * family-interleaved ordering: one dead route at this bound plus the live connect, which fits
     * the smallest caller deadline (15 s) with room for a second dead route.
     */
    internal const val ROUTE_CONNECT_TIMEOUT_MS = 5_000L

    /**
     * Explicit trust material (for example a private CA). Never a verification bypass: certificate
     * chain checks and hostname verification still run against whatever is supplied here.
     */
    internal class TlsTrust(val socketFactory: SSLSocketFactory, val trustManager: X509TrustManager)

    fun client(
        preferIpv4: Boolean = false,
        ipv4Only: Boolean = false,
        routeConnectTimeoutMs: Long = ROUTE_CONNECT_TIMEOUT_MS,
        tls: TlsTrust? = null,
        resolver: ((String) -> List<InetAddress>)? = null,
    ): HttpClient = HttpClient(OkHttp) {
        // No maxFrameSize: the OkHttp engine REJECTS any custom value at session start ("Max frame
        // size switch is not supported"), so the previous per-site 2-32 MB bounds are structurally
        // unavailable here. Pinned by HaWebSocketClientsFailoverTest; incoming message size is
        // bounded only by what the trusted Home Assistant endpoint sends.
        install(WebSockets)
        engine {
            config {
                connectTimeout(routeConnectTimeoutMs, TimeUnit.MILLISECONDS)
                // Default-on in OkHttp 5; pinned so a future engine bump cannot silently drop the
                // concurrent-family race this lane exists to provide.
                fastFallback(true)
                dns(
                    if (resolver == null) {
                        FamilyPlannedDns(preferIpv4 = preferIpv4, ipv4Only = ipv4Only)
                    } else {
                        FamilyPlannedDns(preferIpv4 = preferIpv4, ipv4Only = ipv4Only, systemLookup = resolver)
                    },
                )
                if (tls != null) sslSocketFactory(tls.socketFactory, tls.trustManager)
            }
        }
    }
}

/**
 * Resolver wrapper implementing the user address-family policy for HA WebSocket connections, with
 * the same vocabulary as the MQTT route planner: Automatic keeps the resolver's leading family,
 * Prefer IPv4 leads with A records, Force IPv4 never emits an IPv6 address.
 *
 * Families are interleaved (RFC 8305 §4) rather than concatenated so a host publishing several
 * addresses of a dead family costs one bounded route attempt — not one per dead address — before
 * the first address of the live family is tried.
 */
internal class FamilyPlannedDns(
    private val preferIpv4: Boolean,
    private val ipv4Only: Boolean,
    private val systemLookup: (String) -> List<InetAddress> = { Dns.SYSTEM.lookup(it) },
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val all = systemLookup(hostname)
        val v4 = all.filterIsInstance<Inet4Address>()
        val v6 = all.filterIsInstance<Inet6Address>()
        if (ipv4Only) {
            if (v4.isEmpty()) {
                throw UnknownHostException(
                    "$hostname resolves to no IPv4 address and the address-family policy is Force IPv4",
                )
            }
            return v4
        }
        val ipv4Leads = preferIpv4 || all.firstOrNull() is Inet4Address
        val lead = if (ipv4Leads) v4 else v6
        val trail = if (ipv4Leads) v6 else v4
        val interleaved = ArrayList<InetAddress>(all.size)
        val leadIterator = lead.iterator()
        val trailIterator = trail.iterator()
        while (leadIterator.hasNext() || trailIterator.hasNext()) {
            if (leadIterator.hasNext()) interleaved.add(leadIterator.next())
            if (trailIterator.hasNext()) interleaved.add(trailIterator.next())
        }
        return interleaved
    }
}
