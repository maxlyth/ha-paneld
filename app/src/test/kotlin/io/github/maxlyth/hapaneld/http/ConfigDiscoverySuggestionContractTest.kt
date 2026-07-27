package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.mqttBrokerSuggestionFromHaUrl
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigDiscoverySuggestionContractTest {
    private val server = File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt").readText()
    private val service = File("src/main/kotlin/io/github/maxlyth/hapaneld/PaneldService.kt").readText()
    private val configure = File("src/main/assets/configure.js").readText()

    @Test fun discoverySuggestionsAreReadOnlyAndRunOffTheRequestLane() {
        assertTrue(server.contains("get(\"/config/discovery\")"))
        assertTrue(server.contains("withContext(Dispatchers.IO) { configDiscoverySuggestions() }"))
        assertTrue(server.contains("found.mqttBroker.takeIf { needsMqtt && config.mqttBroker.isBlank() }"))
        assertTrue(server.contains("found.haUrl.takeIf { needsHa && config.haUrl.isBlank() }"))
        assertTrue(service.contains("val active = runtime.current()"))
        // The detailed variant is used so a failed discovery carries its reason to the UI; the plain
        // String? form cannot distinguish "nothing found" from "this network cannot discover at all".
        assertTrue(service.contains("val haDiscovery = active.mdns.discoverHaBaseUrlDetailed(existingOrActiveBroker)"))
        assertTrue(service.contains("val discoveredHa = haDiscovery.value"))
        assertTrue(service.contains("mqttBrokerSuggestionFromHaUrl(discoveredHa)"))
        assertTrue(service.contains("haUrl = discoveredHa"))
        assertTrue(service.contains("haDiscovery = haDiscovery"))
    }

    @Test fun delayedSuggestionsFillOnlyAnUntouchedBlankFormAndBecomeUnsavedChanges() {
        assertTrue(configure.contains("fetch(\"/api/v1/config/discovery\", { cache: \"no-store\" })"))
        assertTrue(configure.contains("if (request !== configDiscoveryRequest || dirty) return;"))
        assertTrue(configure.contains("configDiscoveryRequest++;"))
        assertTrue(configure.contains("if (!suggestion || values[key] || savedValues[key] || dirtyValues[key]) return;"))
        assertTrue(configure.contains("recomputeDirty();"))
        assertTrue(configure.contains("render();"))
    }

    @Test fun mqttBrokerSuggestionsPreferHomeAssistantHostnameWhenAvailable() {
        assertEquals("tcp://homeassistant.local:1883", mqttBrokerSuggestionFromHaUrl("https://homeassistant.local:8123"))
        assertEquals("tcp://[fd00::1234]:1883", mqttBrokerSuggestionFromHaUrl("http://[fd00::1234]:8123"))
        assertNull(mqttBrokerSuggestionFromHaUrl(""))
        assertNull(mqttBrokerSuggestionFromHaUrl("not a url"))
    }
}
