package io.github.maxlyth.hapaneld.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZigbeeController over the RootShell seam — presence detection, driver-label parsing, running state,
 * and the idempotent reconcile decision — with no device. reconcile is the reliability-critical bit:
 * it must never spawn a second guard over an already-running gateway.
 */
class ZigbeeControllerTest {

    private val dir = "/vendor/bin/siliconlabs_host"

    private fun zb(outputs: Map<String, String> = emptyMap()): Pair<ZigbeeController, FakeRootShell> {
        val root = FakeRootShell(outputs)
        return ZigbeeController(fakeProfile(zigbeeGatewayDir = dir), root) to root
    }

    @Test fun noProfileDirIsInert() {
        val z = ZigbeeController(fakeProfile(zigbeeGatewayDir = null), FakeRootShell())
        assertFalse(z.present())
        assertNull(z.driver())
        assertEquals("none", z.status())
    }

    @Test fun presentWhenZgatewayBinaryExists() {
        assertTrue(zb(mapOf("siliconlabs_host/zgateway" to "zgateway")).first.present())
    }

    @Test fun driverParsesPackageVersionMarker() {
        assertEquals("sonoff 3.5.0", zb(mapOf("package_version" to "sonoff-v3.5.4:sonoff-3.5.0")).first.driver())
    }

    @Test fun driverVendorNativeWhenPresentButNoMarker() {
        assertEquals("vendor-native", zb(mapOf("siliconlabs_host/zgateway" to "zgateway")).first.driver())
    }

    @Test fun runningReflectsPidof() {
        assertTrue(zb(mapOf("pidof zgateway" to "1234")).first.running())
        assertFalse(zb(mapOf("pidof zgateway" to "")).first.running())
    }

    @Test fun reconcileStartsWhenDesiredOnAndDown() {
        val (z, root) = zb(mapOf("siliconlabs_host/zgateway" to "zgateway")) // present, nothing running
        assertTrue(z.reconcile(true))
        assertTrue("expected a guard start, got ${root.ran}", root.ran.any { it.contains("guard_process.sh") })
    }

    @Test fun reconcileStopsWhenDesiredOffAndUp() {
        val (z, root) = zb(mapOf("siliconlabs_host/zgateway" to "zgateway", "pidof zgateway" to "1234"))
        assertTrue(z.reconcile(false))
        assertTrue(root.ran.any { it.contains("killall") })
    }

    @Test fun reconcileNeverSpawnsASecondGuardWhenAlreadyRunning() {
        val (z, root) = zb(mapOf("siliconlabs_host/zgateway" to "zgateway", "pidof zgateway" to "1234"))
        assertTrue(z.reconcile(true)) // desired on, already up -> no-op
        assertTrue(root.ran.none { it.contains("guard_process.sh") })
    }
}
