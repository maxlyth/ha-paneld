package io.github.maxlyth.hapaneld.mqtt

import io.github.maxlyth.hapaneld.mqttKnownConfigTopics
import io.github.maxlyth.hapaneld.mqttBrokerIdentity
import io.github.maxlyth.hapaneld.mqttReconfigurePublishesOffline
import io.github.maxlyth.hapaneld.mqttStalePanelCleanup
import io.github.maxlyth.hapaneld.hiddenReadOnlyStateTopic
import io.github.maxlyth.hapaneld.config.SettingsRegistry
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
    }

    @Test fun replacementConnectionCleansRenamedPanelAndMarksItOffline() {
        val cleanup = mqttStalePanelCleanup("old", "new")

        assertTrue(cleanup.any { it.topic == "homeassistant/sensor/old_diag_cpu/config" && it.payload.isEmpty() && it.retain })
        assertTrue(cleanup.any { it.topic == "ha-paneld/old/availability" && it.payload == "offline" && it.retain })
        assertTrue(cleanup.none { "/new_" in it.topic || "/new/" in it.topic })
    }

    @Test fun hidingReadOnlySensorsClearsTheirRetainedStateIncludingSensitiveValues() {
        assertTrue(hiddenReadOnlyStateTopic("diag_wifi_ssid", "test") == "ha-paneld/test/diag_wifi_ssid/state")
        assertTrue(hiddenReadOnlyStateTopic("proximity", "test") == "ha-paneld/test/proximity/state")
        assertTrue(hiddenReadOnlyStateTopic("screen", "test") == "ha-paneld/test/screen/state")
        assertTrue(hiddenReadOnlyStateTopic("volume", "test") == "ha-paneld/test/volume/state")
        assertTrue(hiddenReadOnlyStateTopic("watchdog_enabled", "test") == null)
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
        assertTrue(mqttBrokerIdentity("mqtts://BROKER") == mqttBrokerIdentity("ssl://broker:8883"))
    }
}
