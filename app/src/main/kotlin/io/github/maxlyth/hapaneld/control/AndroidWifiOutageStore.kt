package io.github.maxlyth.hapaneld.control

import android.content.Context
import io.github.maxlyth.hapaneld.persistence.AppState

/** Durable home for the Wi-Fi outage counter — classified DEVICE_LOCAL in [io.github.maxlyth.hapaneld.persistence.StateBackupPolicy]. */
internal class AndroidWifiOutageStore(context: Context) : WifiOutageStore {
    private val preferences = AppState.preferences(
        context,
        namespace = "wifi-stability",
        legacyName = "ha-paneld-wifi-stability",
    )

    override fun load(): WifiOutageRecord? = parseWifiOutageRecord(preferences.getString(KEY, null))

    override fun save(record: WifiOutageRecord) {
        preferences.edit().putString(KEY, encodeWifiOutageRecord(record)).apply()
    }

    private companion object {
        const val KEY = "outages_v1"
    }
}
