package io.github.maxlyth.hapaneld.shizuku

import org.junit.Assert.assertEquals
import org.junit.Test

class ShizukuManagerUpdaterTest {
    @Test fun dependencyUpdatesOnlyForManagedReadyOptInsBelowPin() {
        val target = ShizukuManagerUpdater.TARGET_VERSION_CODE
        assertEquals(
            ShizukuManagerUpdater.Decision.DISABLED,
            ShizukuManagerUpdater.decide(managed = false, autoUpdate = true, ready = true, currentVersionCode = target - 1),
        )
        assertEquals(
            ShizukuManagerUpdater.Decision.DISABLED,
            ShizukuManagerUpdater.decide(managed = true, autoUpdate = false, ready = true, currentVersionCode = target - 1),
        )
        assertEquals(
            ShizukuManagerUpdater.Decision.NOT_READY,
            ShizukuManagerUpdater.decide(managed = true, autoUpdate = true, ready = false, currentVersionCode = target - 1),
        )
        assertEquals(
            ShizukuManagerUpdater.Decision.UPDATE,
            ShizukuManagerUpdater.decide(managed = true, autoUpdate = true, ready = true, currentVersionCode = target - 1),
        )
        assertEquals(
            ShizukuManagerUpdater.Decision.UP_TO_DATE,
            ShizukuManagerUpdater.decide(managed = true, autoUpdate = true, ready = true, currentVersionCode = target),
        )
        assertEquals(
            ShizukuManagerUpdater.Decision.UP_TO_DATE,
            ShizukuManagerUpdater.decide(managed = true, autoUpdate = true, ready = true, currentVersionCode = target + 1),
        )
    }

    @Test fun curatedReleasePinsPackageSignerAndBlob() {
        assertEquals("moe.shizuku.privileged.api", ShizukuManagerIdentity.PACKAGE)
        assertEquals(64, ShizukuManagerIdentity.CERT_SHA256.length)
        assertEquals(64, ShizukuManagerUpdater.TARGET_APK_SHA256.length)
        assertEquals("https", java.net.URL(ShizukuManagerUpdater.TARGET_URL).protocol)
    }
}
