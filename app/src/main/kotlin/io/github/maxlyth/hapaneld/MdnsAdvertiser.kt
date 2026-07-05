package io.github.maxlyth.hapaneld

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import io.github.maxlyth.hapaneld.util.localIpv4
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/**
 * Advertises `_ha-paneld._tcp.local.` via JmDNS so HA's zeroconf discovery can auto-pair the
 * panel. JmDNS is used in preference to Android's NsdManager because NsdManager's TXT-record
 * handling is unreliable across the API 26→30 range the fleet spans. A WifiManager.MulticastLock
 * is held for the lifetime of the advertisement (panels otherwise drop multicast in doze).
 */
class MdnsAdvertiser(private val context: Context, private val config: Config) {
    private var jmdns: JmDNS? = null
    private var lock: WifiManager.MulticastLock? = null
    @Volatile private var browsing = false

    // Live roster of discovered ha-paneld panels (keyed by mDNS instance name = panel_id), maintained by a
    // persistent [peerListener]. Reads are cheap + non-blocking and — unlike a one-shot dns.list, which
    // returns only what's in this JmDNS's cache within a short window (observed to miss panels a peer sees) —
    // this converges to the full fleet over the listener's periodic queries and self-prunes on service-removed.
    private val peerMap = ConcurrentHashMap<String, Peer>()

    private val peerListener = object : ServiceListener {
        override fun serviceAdded(event: ServiceEvent) {
            // Resolve OFF the JmDNS packet thread (getServiceInfo blocks) so we never stall mDNS processing.
            val dns = jmdns ?: return
            val type = event.type
            val name = event.name
            Thread {
                runCatching { dns.getServiceInfo(type, name, RESOLVE_MS) }.getOrNull()?.let { record(it, name) }
            }.apply { isDaemon = true; this.name = "mdns-resolve" }.start()
        }

        override fun serviceResolved(event: ServiceEvent) {
            event.info?.let { record(it, event.name) } // some JmDNS paths deliver the resolved info directly
        }

        override fun serviceRemoved(event: ServiceEvent) {
            peerMap.remove(event.name)
        }
    }

    /** Fold a resolved mDNS record into the roster (needs a resolved IPv4 to be navigable). */
    private fun record(info: ServiceInfo, fallbackName: String) {
        val p = toPeer(
            instanceName = info.name ?: fallbackName,
            txtName = info.getPropertyString("name"),
            txtVer = info.getPropertyString("ver"),
            ipv4 = info.inet4Addresses?.firstOrNull()?.hostAddress,
            port = info.port,
            selfId = config.panelId,
            selfIp = localIpv4(),
        )
        if (p.panelId.isBlank() || p.ip == null) return
        // Don't DOWNGRADE a resolved friendly name back to the panel_id fallback: a later TXT-less resolve
        // (common right after a whole-fleet restart floods mDNS) must not clobber a good name. Otherwise store.
        val existing = peerMap[p.panelId]
        val newHasName = p.name != p.panelId
        val oldHasName = existing != null && existing.name != existing.panelId
        if (existing == null || newHasName || !oldHasName) peerMap[p.panelId] = p
    }

    /** Blocking network setup — call off the main thread. */
    fun start() {
        try {
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            lock = wifi.createMulticastLock("ha-paneld-mdns").apply {
                setReferenceCounted(true)
                acquire()
            }
            val addr = localAddress()
            val dns = JmDNS.create(addr, config.panelId)
            val props = mapOf(
                "ver" to Config.VERSION,
                "caps" to "tts",
                "path" to "/play",
                // Friendly name so a peer's fleet switcher can label this panel nicely (falls back to the
                // instance name = panel_id on older panels that don't advertise it). Additive TXT key.
                "name" to config.friendlyName.ifBlank { config.panelId },
            )
            val info = ServiceInfo.create(
                Config.MDNS_SERVICE_TYPE,
                config.panelId,
                config.httpPort,
                0,
                0,
                props,
            )
            dns.registerService(info)
            jmdns = dns
            // Start the persistent peer browse (powers the header switcher) — begins querying immediately
            // and keeps the roster fresh in the background, so a UI read is instant + complete.
            runCatching { dns.addServiceListener(Config.MDNS_SERVICE_TYPE, peerListener) }
            // Periodic re-browse: dns.list resolves each service fully (incl. TXT), so this refreshes names
            // and fills any TXT missed during a congested (whole-fleet-restart) window. Merges with the
            // don't-downgrade rule in record(), so it only closes gaps and never flaps a good name.
            browsing = true
            Thread {
                while (browsing) {
                    try { Thread.sleep(REFRESH_MS) } catch (e: InterruptedException) { break }
                    jmdns?.let { d -> runCatching { d.list(Config.MDNS_SERVICE_TYPE, LIST_MS)?.forEach { record(it, it.name ?: "") } } }
                }
            }.apply { isDaemon = true; name = "mdns-peer-refresh" }.start()
            Log.i(TAG, "advertising ${Config.MDNS_SERVICE_TYPE} as ${config.panelId} @ $addr:${config.httpPort}")
        } catch (e: Exception) {
            Log.w(TAG, "mDNS advertise failed", e)
        }
    }

    fun stop() {
        browsing = false
        runCatching { jmdns?.removeServiceListener(Config.MDNS_SERVICE_TYPE, peerListener) }
        peerMap.clear()
        runCatching { jmdns?.unregisterAllServices() }
        runCatching { jmdns?.close() }
        jmdns = null
        runCatching { lock?.release() }
        lock = null
    }

    /**
     * Browse for Home Assistant's own zeroconf advertisement (`_home-assistant._tcp.local.`) and
     * return the IPv4 of one that actually has the MQTT port (1883) open — so on a LAN with more than
     * one HA instance we pick the one running a broker, and skip an HA whose broker lives elsewhere.
     * Falls back to the first HA host if none probe open. Used to default the MQTT broker to
     * `tcp://<ha-ip>:1883` when none is set. Blocking up to [timeoutMs]; call off the main thread.
     */
    fun discoverHaIp(timeoutMs: Long = 4000): String? {
        val dns = jmdns ?: return null
        val ips = runCatching {
            dns.list("_home-assistant._tcp.local.", timeoutMs)
                ?.mapNotNull { it.inet4Addresses?.firstOrNull()?.hostAddress }?.distinct() ?: emptyList()
        }.getOrDefault(emptyList())
        val chosen = ips.firstOrNull { mqttPortOpen(it) } ?: ips.firstOrNull()
        Log.i(TAG, "HA mDNS discovery: hosts=$ips -> ${chosen ?: "none found"}")
        return chosen
    }

    /**
     * HA's own advertised base URL (scheme+host+port) from its zeroconf TXT — `internal_url` preferred,
     * then `base_url`, then `external_url`. Authoritative for setups that don't use the default http :8123
     * (e.g. HA Core terminating TLS on :443, or behind a reverse proxy), so callers never have to guess a
     * port/scheme. Null if no HA service is found or it advertises no URL. Blocking; call off the main thread.
     */
    fun discoverHaBaseUrl(timeoutMs: Long = 4000): String? {
        val dns = jmdns ?: return null
        val brokerIps = brokerHostIps() // all of the broker host's addresses (v4 AND v6) — see below
        val url = runCatching {
            val svcs = dns.list("_home-assistant._tcp.local.", timeoutMs)?.toList() ?: emptyList()
            // The panel's HA is the one hosting its MQTT broker. Match the configured broker by IP — against
            // ALL of the service's addresses (v4 + v6), since the broker host commonly resolves to a global
            // IPv6 first — so on a LAN with several HA instances (a primary + a secondary) we never pick the
            // wrong one. Else fall back to whichever HA has :1883 open; else give up rather than guess.
            val pick = svcs.firstOrNull { s ->
                s.inetAddresses?.any { (it.hostAddress?.substringBefore('%')) in brokerIps } == true
            } ?: if (config.mqttBroker.isBlank()) {
                // No explicit broker → auto-discover: the local HA that runs the broker (:1883 open).
                svcs.firstOrNull { s -> s.inet4Addresses?.firstOrNull()?.hostAddress?.let { mqttPortOpen(it) } == true }
            } else {
                // Explicit broker that matched no local HA (e.g. its HA is across a tunnel) → return null so
                // the caller's broker-HOST fallback handles it, rather than picking an unrelated local HA.
                null
            }
            pick?.let {
                (it.getPropertyString("internal_url") ?: it.getPropertyString("base_url") ?: it.getPropertyString("external_url"))
                    ?.takeIf { u -> u.isNotBlank() }?.trimEnd('/')
            }
        }.getOrNull()
        Log.i(TAG, "HA mDNS base url (broker=${brokerIps.joinToString(",").ifEmpty { "?" }}): ${url ?: "none"}")
        return url
    }

    /**
     * The LAN ha-paneld roster (`_ha-paneld._tcp.local.`) for the header panel switcher — a cheap,
     * non-blocking snapshot of [peerMap], which [peerListener] keeps converged + fresh in the background.
     * This panel's own advertised service is included and [Peer.self]-marked; self is re-evaluated against
     * the CURRENT identity so a reconfigure between resolve and read can't mis-mark it. Never throws.
     */
    fun browsePeers(): List<Peer> {
        val selfId = config.panelId
        val selfIp = localIpv4()
        val selfName = config.friendlyName.ifBlank { selfId }
        return dedupePeers(
            peerMap.values.map {
                val self = it.panelId == selfId || (it.ip != null && it.ip == selfIp)
                // mDNS self-resolution can miss our OWN TXT (name), so a panel would show ITSELF as its
                // panel_id while peers show its friendly name — making the alphabetical menu order differ
                // between panels. Force this panel's authoritative friendly name for the self entry.
                if (self) it.copy(self = true, name = selfName) else it.copy(self = false)
            },
        )
    }

    /** All of the configured MQTT broker host's IP addresses (v4 + v6), to match against HA mDNS records. */
    private fun brokerHostIps(): Set<String> = runCatching {
        val host = config.mqttBroker.substringAfter("://").substringBefore(":").substringBefore("/").trim()
        if (host.isBlank()) emptySet()
        else java.net.InetAddress.getAllByName(host).mapNotNull { it.hostAddress?.substringBefore('%') }.toSet()
    }.getOrDefault(emptySet())

    /** True if TCP 1883 accepts a connection on [ip] within [timeoutMs] (the MQTT broker is there). */
    private fun mqttPortOpen(ip: String, port: Int = 1883, timeoutMs: Int = 600): Boolean =
        runCatching {
            java.net.Socket().use { it.connect(java.net.InetSocketAddress(ip, port), timeoutMs) }; true
        }.getOrDefault(false)

    // Bind JmDNS to the panel's real LAN address. The old WifiManager.connectionInfo path returns
    // 0 on Ethernet panels (→ 127.0.0.1, which HA zeroconf can't reach), so enumerate interfaces.
    private fun localAddress(): InetAddress =
        localIpv4()?.let { InetAddress.getByName(it) } ?: InetAddress.getLocalHost()

    companion object {
        private const val TAG = "ha-paneld/mdns"
        private const val RESOLVE_MS = 2500L // per-peer mDNS resolve budget (off the JmDNS thread)
        private const val REFRESH_MS = 60_000L // periodic re-browse interval (name refresh / gap fill)
        private const val LIST_MS = 3000L // dns.list resolve budget per refresh sweep
    }
}

/** One ha-paneld panel discovered over mDNS (or this panel itself, [self]=true). [ip] is null when the
 *  record didn't resolve to an IPv4 (IPv6-only / unresolved) — such peers aren't navigable in the switcher. */
data class Peer(
    val panelId: String,
    val name: String,
    val ip: String?,
    val port: Int,
    val version: String,
    val self: Boolean,
)

/** Pure: dedupe by panel id (a panel can resolve to several records/addresses), preferring the entry with
 *  a resolved IPv4; sort purely by friendly name so the roster is IDENTICAL on every panel (self is flagged,
 *  not reordered — the switcher must look the same everywhere). Blank panel ids are dropped. Unit-tested. */
internal fun dedupePeers(peers: List<Peer>): List<Peer> {
    val byId = LinkedHashMap<String, Peer>()
    for (p in peers) {
        if (p.panelId.isBlank()) continue
        val existing = byId[p.panelId]
        if (existing == null || (existing.ip == null && p.ip != null)) byId[p.panelId] = p
    }
    return byId.values.sortedBy { it.name.lowercase() }
}

/** Pure mDNS-record → [Peer] mapper (plain strings, no ServiceInfo) so it's JVM-unit-testable. */
internal fun toPeer(
    instanceName: String,
    txtName: String?,
    txtVer: String?,
    ipv4: String?,
    port: Int,
    selfId: String,
    selfIp: String?,
): Peer = Peer(
    panelId = instanceName,
    name = txtName?.takeIf { it.isNotBlank() } ?: instanceName,
    ip = ipv4,
    port = port,
    version = txtVer ?: "",
    self = instanceName == selfId || (ipv4 != null && ipv4 == selfIp),
)
