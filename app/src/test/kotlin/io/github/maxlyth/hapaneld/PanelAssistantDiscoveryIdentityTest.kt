package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.http.panelAssistantDiscoveryHealthToken
import io.github.maxlyth.hapaneld.testsupport.TestSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelAssistantDiscoveryIdentityTest {
    private val androidId = "0123456789abcdef"

    @Test fun tokenIsStableLowercaseSha256AndDomainSeparated() {
        val token = requireNotNull(panelAssistantDiscoveryId(androidId))

        assertEquals(64, token.length)
        assertTrue(token.matches(Regex("[0-9a-f]{64}")))
        assertEquals(token, panelAssistantDiscoveryId("  $androidId  "))
        assertFalse(token == panelAssistantDiscoveryId("fedcba9876543210"))
        assertFalse(token == androidId)
    }

    @Test fun missingAndroidIdProducesNoLanIdentity() {
        assertNull(panelAssistantDiscoveryId(""))
        assertNull(panelAssistantDiscoveryId(" \t "))
        assertEquals("", panelAssistantDiscoveryHealthToken(""))
    }

    @Test fun healthTokenContainsOnlyThePseudonym() {
        val token = requireNotNull(panelAssistantDiscoveryId(androidId))
        val healthToken = panelAssistantDiscoveryHealthToken(androidId)

        assertEquals(" did=$token", healthToken)
        assertFalse(healthToken.contains(androidId))
    }

    @Test fun bothHealthRoutesAndMdnsAdvertisementUseTheSameToken() {
        val server = TestSources.kotlin("http/PaneldServer.kt").readText()
        val advertiser = TestSources.kotlin("MdnsAdvertiser.kt").readText()

        assertEquals(2, Regex("panelAssistantDiscoveryHealthToken\\(config\\.androidId\\)").findAll(server).count())
        assertTrue(advertiser.contains("panelAssistantDiscoveryId(config.androidId)?.let { put(\"did\", it) }"))
    }
}
