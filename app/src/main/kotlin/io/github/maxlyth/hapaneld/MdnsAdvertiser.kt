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

    // Bind JmDNS to the panel's real LAN address. The old WifiManager.connectionInfo path returns
    // 0 on Ethernet panels (→ 127.0.0.1, which HA zeroconf can't reach), so enumerate interfaces.
    private fun localAddress(): InetAddress =
        localIpv4()?.let { InetAddress.getByName(it) } ?: InetAddress.getLocalHost()

    companion object {
        private const val TAG = "ha-paneld/mdns"
    }
}
