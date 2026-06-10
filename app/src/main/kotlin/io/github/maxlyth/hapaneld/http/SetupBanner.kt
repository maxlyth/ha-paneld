package io.github.maxlyth.hapaneld.http

/**
 * Pure decision for the info-page setup banner: what the panel still needs configured, derived from the
 * live MQTT status string + whether a broker is configured + whether the panel id is still the default.
 *
 * Extracted from the HTML builder so it's unit-testable — the key regression it guards is the
 * "needs the MQTT broker" false-positive: while the bridge is mid-(re)connect after a config change its
 * status is transient/blank, and a configured broker must NOT be reported as missing then.
 */
object SetupBanner {
    fun needs(mqttStatus: String, brokerConfigured: Boolean, panelIdDefault: Boolean): List<String> {
        val needs = mutableListOf<String>()
        if (panelIdDefault) needs.add("a panel id")
        when {
            mqttStatus.contains("connected") || mqttStatus.contains("connecting") -> {} // connected / transient — fine
            mqttStatus.contains("auth rejected") -> needs.add("valid MQTT credentials (the broker rejected them)")
            mqttStatus.contains("unreachable") -> needs.add("a reachable MQTT broker")
            !brokerConfigured -> needs.add("the MQTT broker") // genuinely unconfigured
            else -> {} // a broker IS configured but the bridge is mid-(re)connect / initialising — transient
        }
        return needs
    }
}
