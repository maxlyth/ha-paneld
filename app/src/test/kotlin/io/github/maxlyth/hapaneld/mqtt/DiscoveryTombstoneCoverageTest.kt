package io.github.maxlyth.hapaneld.mqtt

import io.github.maxlyth.hapaneld.mqttKnownConfigTopics
import io.github.maxlyth.hapaneld.mqttReconfigurePublishesOffline
import io.github.maxlyth.hapaneld.mqttStalePanelCleanup
import io.github.maxlyth.hapaneld.mqttRetiredStateTopics
import io.github.maxlyth.hapaneld.hiddenReadOnlyStateTopic
import io.github.maxlyth.hapaneld.diagnosticObservation
import io.github.maxlyth.hapaneld.config.SettingsRegistry
import io.github.maxlyth.hapaneld.mqtt.StateConverger.Observation
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryTombstoneCoverageTest {
    @Test fun everyRegistryEntityHasAHistoricalTombstone() {
        val panel = "test"
        val known = mqttKnownConfigTopics(panel)
        val missing = SettingsRegistry.SPECS.mapNotNull { spec ->
            spec.ha?.let { "homeassistant/${it.component}/${panel}_${it.objectSuffix}/config" }
        }.filterNot { it in known }

        assertTrue("missing discovery tombstones: ${missing.joinToString()}", missing.isEmpty())
        assertTrue(
            "retired config-store diagnostic must remain tombstoned for existing panels",
            "homeassistant/sensor/${panel}_diag_schema_reconcile/config" in known,
        )
    }

    @Test fun replacementConnectionCleansRenamedPanelAndMarksItOffline() {
        val cleanup = mqttStalePanelCleanup("old", "new")

        assertTrue(cleanup.any { it.topic == "homeassistant/sensor/old_diag_cpu/config" && it.payload.isEmpty() && it.retain })
        assertTrue(cleanup.any { it.topic == "homeassistant/sensor/old_diag_schema_reconcile/config" && it.payload.isEmpty() && it.retain })
        assertTrue(cleanup.any { it.topic == "ha-paneld/old/diag_schema_reconcile/state" && it.payload.isEmpty() && it.retain })
        assertTrue(cleanup.any { it.topic == "ha-paneld/old/availability" && it.payload == "offline" && it.retain })
        assertTrue(cleanup.none { "/new_" in it.topic || "/new/" in it.topic })
    }

    @Test fun retiredDiagnosticsKeepHistoricalStateTombstones() {
        assertTrue("ha-paneld/test/diag_schema_reconcile/state" in mqttRetiredStateTopics("test"))
    }

    @Test fun hidingReadOnlySensorsClearsTheirRetainedStateIncludingSensitiveValues() {
        assertTrue(hiddenReadOnlyStateTopic("diag_wifi_ssid", "test") == "ha-paneld/test/diag_wifi_ssid/state")
        assertTrue(hiddenReadOnlyStateTopic("diag_wifi_rssi", "test") == "ha-paneld/test/diag_wifi_rssi/state")
        assertTrue(hiddenReadOnlyStateTopic("proximity", "test") == "ha-paneld/test/proximity/state")
        assertTrue(hiddenReadOnlyStateTopic("auto_sleep_activity", "test") == "ha-paneld/test/auto_sleep_activity/state")
        assertTrue(hiddenReadOnlyStateTopic("screen", "test") == "ha-paneld/test/screen/state")
        assertTrue(hiddenReadOnlyStateTopic("volume", "test") == "ha-paneld/test/volume/state")
        assertTrue(hiddenReadOnlyStateTopic("watchdog_enabled", "test") == null)
    }

    @Test fun unavailableWifiDoesNotRecreateStateBehindDiscoveryTombstones() {
        assertTrue(diagnosticObservation("diag_wifi_ssid", exposed = true, value = null) == Observation.Unavailable)
        assertTrue(diagnosticObservation("diag_wifi_rssi", exposed = true, value = null) == Observation.Unavailable)
        assertTrue(diagnosticObservation("diag_wifi_ssid", exposed = false, value = "Private") == Observation.Unavailable)
        assertTrue(diagnosticObservation("diag_wifi_rssi", exposed = true, value = "-63") == Observation.Known("-63"))
        assertTrue(diagnosticObservation("diag_cpu", exposed = true, value = null) == Observation.Known("unknown"))
    }

    @Test fun unchangedPanelIdNeedsNoReplacementCleanup() {
        assertTrue(mqttStalePanelCleanup("same", "same").isEmpty())
        assertTrue(mqttStalePanelCleanup(null, "same").isEmpty())
    }

    @Test fun inPlaceReconfigureDoesNotRaceReplacementAvailability() {
        assertTrue(!mqttReconfigurePublishesOffline("same", "same", "tcp://broker:1883", "tcp://broker:1883"))
        assertTrue(!mqttReconfigurePublishesOffline("same", "same", "broker", "tcp://BROKER:1883"))
        assertTrue(mqttReconfigurePublishesOffline("old", "new", "tcp://broker:1883", "tcp://broker:1883"))
        assertTrue(mqttReconfigurePublishesOffline("same", "same", "tcp://old:1883", "tcp://new:1883"))
        // Equivalent TLS schemes are one broker identity, so a scheme-only edit must not retire availability.
        assertTrue(!mqttReconfigurePublishesOffline("same", "same", "mqtts://BROKER", "ssl://broker:8883"))
    }
}
