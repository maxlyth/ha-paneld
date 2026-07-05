package io.github.maxlyth.hapaneld

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure mDNS peer mapping/dedupe that backs the header panel switcher — the logic that's testable without
 * a JmDNS `ServiceInfo` or a device. [toPeer] turns record fields into a [Peer]; [dedupePeers] collapses
 * duplicate records and orders the roster.
 */
class MdnsPeerTest {

    private fun peer(id: String, name: String? = null, ip: String? = "10.0.0.9", ver: String? = "1.2.3", self: Boolean = false) =
        toPeer(id, name, ver, ip, 8888, selfId = if (self) id else "someone_else", selfIp = null)

    // --- toPeer ---
    @Test fun nameFallsBackToInstanceWhenTxtMissingOrBlank() {
        assertEquals("kitchen_ha_paneld", toPeer("kitchen_ha_paneld", null, "9", "10.0.0.1", 8888, "x", null).name)
        assertEquals("kitchen_ha_paneld", toPeer("kitchen_ha_paneld", "  ", "9", "10.0.0.1", 8888, "x", null).name)
        assertEquals("Kitchen Panel", toPeer("kitchen_ha_paneld", "Kitchen Panel", "9", "10.0.0.1", 8888, "x", null).name)
    }

    @Test fun versionFallsBackToEmpty() {
        assertEquals("", toPeer("p", "P", null, "10.0.0.1", 8888, "x", null).version)
        assertEquals("0.8.6", toPeer("p", "P", "0.8.6", "10.0.0.1", 8888, "x", null).version)
    }

    @Test fun selfMatchesById() =
        assertTrue(toPeer("office_ha_paneld", "Office", "9", "10.0.0.5", 8888, selfId = "office_ha_paneld", selfIp = "10.0.0.99").self)

    @Test fun selfMatchesByIp() =
        assertTrue(toPeer("office_ha_paneld", "Office", "9", "10.0.0.5", 8888, selfId = "other", selfIp = "10.0.0.5").self)

    @Test fun notSelfWhenNeitherMatches() =
        assertFalse(toPeer("hall_ha_paneld", "Hall", "9", "10.0.0.5", 8888, selfId = "office_ha_paneld", selfIp = "10.0.0.99").self)

    @Test fun unresolvedIpIsNull() =
        assertNull(toPeer("p", "P", "9", null, 8888, "x", null).ip)

    @Test fun selfIpNullNeverFalseMatchesUnresolvedPeer() =
        assertFalse("null selfIp must not match a null peer ip", toPeer("hall", "Hall", "9", null, 8888, "office", null).self)

    // --- dedupePeers ---
    @Test fun dedupesByPanelIdPreferringResolvedIp() {
        val out = dedupePeers(listOf(peer("hall_ha_paneld", ip = null), peer("hall_ha_paneld", ip = "10.0.0.7")))
        assertEquals(1, out.size)
        assertEquals("10.0.0.7", out[0].ip)
    }

    @Test fun dropsBlankPanelIds() =
        assertTrue(dedupePeers(listOf(peer("", ip = "10.0.0.1"))).isEmpty())

    @Test fun ordersPurelyByNameSoEveryPanelMatches() {
        // Self is flagged, NOT pulled to the top — the roster must be identical on every panel so the
        // switcher menu looks the same everywhere (user, 2026-07-05).
        val out = dedupePeers(
            listOf(
                peer("z", name = "Zzz Panel"),
                peer("a", name = "Aaa Panel"),
                peer("me", name = "My Panel", self = true),
            ),
        )
        assertEquals(listOf("Aaa Panel", "My Panel", "Zzz Panel"), out.map { it.name })
    }
}
