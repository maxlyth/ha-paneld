package io.github.maxlyth.hapaneld

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import io.github.maxlyth.hapaneld.util.localIpv4
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * Advertises `_ha-paneld._tcp.local.` via JmDNS so HA's zeroconf discovery can auto-pair the
 * panel. JmDNS is used in preference to Android's NsdManager because NsdManager's TXT-record
 * handling is unreliable across the API 26→30 range the fleet spans. A WifiManager.MulticastLock
 * is held for the lifetime of the advertisement (panels otherwise drop multicast in doze).
 */
class MdnsAdvertiser(private val context: Context, private val config: Config) {
    private var jmdns: JmDNS? = null
    private var lock: WifiManager.MulticastLock? = null

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
            Log.i(TAG, "advertising ${Config.MDNS_SERVICE_TYPE} as ${config.panelId} @ $addr:${config.httpPort}")
        } catch (e: Exception) {
            Log.w(TAG, "mDNS advertise failed", e)
        }
    }

    fun stop() {
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
    }
}
