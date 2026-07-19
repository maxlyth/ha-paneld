package io.github.maxlyth.hapaneld.control

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock

internal data class WifiDiagnosticSnapshot(
    val ssid: String? = null,
    val rssiDbm: Int? = null,
)

internal data class WifiDiagnosticDemand(
    val ssid: Boolean = false,
    val rssi: Boolean = false,
    /** True only when the caller's current capability observation already admitted direct su. */
    val privilegedRoute: Boolean = false,
)

internal fun needsPrivilegedWifiStatus(
    direct: WifiDiagnosticSnapshot,
    demand: WifiDiagnosticDemand,
): Boolean = demand.privilegedRoute &&
    (demand.ssid && direct.ssid == null || demand.rssi && direct.rssiDbm == null)

internal fun normalizedWifiSsid(raw: String?): String? {
    val value = raw?.trim()?.removeSurrounding("\"")?.trim().orEmpty()
    return value.takeIf {
        it.isNotEmpty() &&
            !it.equals("<unknown ssid>", ignoreCase = true) &&
            !it.equals("unknown ssid", ignoreCase = true)
    }
}

internal fun normalizedWifiRssi(raw: Int): Int? = raw.takeIf { it in -126..0 }

internal fun parseWifiShellSnapshot(raw: String?): WifiDiagnosticSnapshot {
    if (raw.isNullOrBlank()) return WifiDiagnosticSnapshot()
    val connectedSsid = Regex("""(?im)Wi-?Fi\s+is\s+connected\s+to\s+"([^"]+)"""")
        .find(raw)?.groupValues?.getOrNull(1)
    val wifiInfoSsid = Regex("""(?im)\bSSID:\s*(.*?),\s*BSSID:""")
        .find(raw)?.groupValues?.getOrNull(1)
    val rssi = Regex("""(?im)\bRSSI\s*[:=]\s*(-?\d+)""")
        .find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
    return WifiDiagnosticSnapshot(
        ssid = normalizedWifiSsid(connectedSsid ?: wifiInfoSsid),
        rssiDbm = rssi?.let(::normalizedWifiRssi),
    )
}

/** Reads only the current Wi-Fi connection; it never scans or collects BSSID/MAC identifiers. */
internal class AndroidWifiDiagnostics(
    context: Context,
    private val privilegedStatus: () -> String? = { null },
) {
    private val app = context.applicationContext
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val cacheLock = Any()
    private var directCachedAtMs = Long.MIN_VALUE
    private var directCached = WifiDiagnosticSnapshot()
    private var shellCachedAtMs = Long.MIN_VALUE
    private var shellCached = WifiDiagnosticSnapshot()

    @Suppress("DEPRECATION")
    private fun currentInfo(): WifiInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = connectivity ?: return null
        val active = manager.activeNetwork?.let(manager::getNetworkCapabilities)
        (active?.takeIf { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }?.transportInfo as? WifiInfo)
            ?: manager.allNetworks.asSequence()
                .mapNotNull(manager::getNetworkCapabilities)
                .firstOrNull { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
                ?.transportInfo as? WifiInfo
    } else {
        wifi?.connectionInfo
    }

    fun ssidRouteAvailable(privilegedAvailable: Boolean): Boolean = privilegedAvailable ||
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1 ||
        app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun snapshot(demand: WifiDiagnosticDemand): WifiDiagnosticSnapshot = synchronized(cacheLock) {
        val now = SystemClock.elapsedRealtime()
        val direct = if (
            directCachedAtMs != Long.MIN_VALUE && now - directCachedAtMs in 0 until CACHE_MS
        ) {
            directCached
        } else {
            (runCatching {
                currentInfo()?.let { info ->
                    WifiDiagnosticSnapshot(
                        ssid = normalizedWifiSsid(info.ssid),
                        rssiDbm = normalizedWifiRssi(info.rssi),
                    )
                }
            }.getOrNull() ?: WifiDiagnosticSnapshot()).also {
                directCached = it
                directCachedAtMs = now
            }
        }
        val shell = if (needsPrivilegedWifiStatus(direct, demand)) {
            if (shellCachedAtMs != Long.MIN_VALUE && now - shellCachedAtMs in 0 until CACHE_MS) {
                shellCached
            } else {
                parseWifiShellSnapshot(runCatching(privilegedStatus).getOrNull()).also {
                    shellCached = it
                    shellCachedAtMs = now
                }
            }
        } else {
            WifiDiagnosticSnapshot()
        }
        WifiDiagnosticSnapshot(
            ssid = if (demand.ssid) direct.ssid ?: shell.ssid else null,
            rssiDbm = if (demand.rssi) direct.rssiDbm ?: shell.rssiDbm else null,
        )
    }

    private companion object {
        const val CACHE_MS = 15_000L
    }
}
