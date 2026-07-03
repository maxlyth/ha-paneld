package io.github.maxlyth.hapaneld.util

import io.github.maxlyth.hapaneld.util.ReleaseCatalog.Raw
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseCatalogTest {
    // newest-first, as the GitHub releases list returns them
    private val raw = listOf(
        Raw("v0.8.6", false, "n6", "u6"),
        Raw("v0.8.6-rc4", true, "n5", "u5"),
        Raw("v0.8.5", false, "n4", "u4"),
        Raw("v0.8.5-rc1", true, "n3", null), // no APK asset → not installable
    )
    private val strip: (String) -> String = { it.removePrefix("v") }

    @Test fun stableChannelDropsPrereleases() {
        val v = ReleaseCatalog.select(raw, "stable", 10, strip)
        assertEquals(listOf("0.8.6", "0.8.5"), v.map { it.version })
        assertTrue(v.all { it.installable })
    }

    @Test fun prereleaseChannelKeepsEverythingNewestFirst() {
        val v = ReleaseCatalog.select(raw, "prerelease", 10, strip)
        assertEquals(listOf("0.8.6", "0.8.6-rc4", "0.8.5", "0.8.5-rc1"), v.map { it.version })
    }

    @Test fun limitCapsTheList() {
        assertEquals(2, ReleaseCatalog.select(raw, "prerelease", 2, strip).size)
    }

    @Test fun missingApkAssetIsNotInstallable() {
        val v = ReleaseCatalog.select(raw, "prerelease", 10, strip)
        assertFalse(v.first { it.tag == "v0.8.5-rc1" }.installable)
    }

    @Test fun tagValidationRejectsPathTricks() {
        assertTrue(ReleaseCatalog.validTag("v0.8.6-rc4"))
        assertTrue(ReleaseCatalog.validTag("2026.6.5-minimal"))
        assertFalse(ReleaseCatalog.validTag("../../etc"))
        assertFalse(ReleaseCatalog.validTag("v1 0"))
        assertFalse(ReleaseCatalog.validTag(""))
    }
}
