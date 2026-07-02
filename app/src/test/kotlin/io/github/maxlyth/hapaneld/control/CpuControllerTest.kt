package io.github.maxlyth.hapaneld.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CpuController's pure logic — governor parsing, tier reverse-mapping, and tier→governor resolution —
 * over the RootShell seam, with no device and no su. Uses the shared controller test fakes (Fakes.kt).
 */
class CpuControllerTest {

    private val avail = "/sys/devices/system/cpu/cpu0/cpufreq/scaling_available_governors"
    private val gov0 = "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor"

    private fun controller(
        nodes: Map<String, String>,
        cpuGovernors: Map<String, String>? = null,
    ): Pair<CpuController, FakeRootShell> {
        val root = FakeRootShell(nodes)
        return CpuController(fakeProfile(appCanSu = true, cpuGovernors = cpuGovernors), root, FakeDaemon(available = false)) to root
    }

    @Test fun governorsParsesTheAvailableList() {
        val (cpu, _) = controller(mapOf(avail to "powersave performance schedutil\n"))
        assertEquals(listOf("powersave", "performance", "schedutil"), cpu.governors())
        assertTrue(cpu.available())
    }

    @Test fun currentTierReverseMapsGovernors() {
        assertEquals(CpuController.PERFORMANCE, controller(mapOf(gov0 to "performance")).first.currentTier())
        assertEquals(CpuController.EFFICIENCY, controller(mapOf(gov0 to "powersave")).first.currentTier())
        // any dynamic governor reads back as Auto
        assertEquals(CpuController.AUTO, controller(mapOf(gov0 to "schedutil")).first.currentTier())
    }

    @Test fun currentTierNullWhenUnreadable() {
        assertNull(controller(emptyMap()).first.currentTier())
    }

    @Test fun setTierResolvesAutoToADynamicGovernorAndWritesIt() {
        val (cpu, root) = controller(mapOf(avail to "powersave performance schedutil"))
        assertTrue(cpu.setTier(CpuController.AUTO))
        assertTrue("expected a schedutil write, got ${root.ran}", root.ran.any { it.contains("echo schedutil") })
    }

    @Test fun setTierPerformancePicksThePerformanceGovernor() {
        val (cpu, root) = controller(mapOf(avail to "powersave performance schedutil"))
        assertTrue(cpu.setTier(CpuController.PERFORMANCE))
        assertTrue(root.ran.any { it.contains("echo performance") })
    }

    @Test fun profileGovernorOverrideHonouredWhenAvailable() {
        // PX30 maps Auto -> interactive; the profile mapping wins when the SoC offers that governor.
        val (cpu, root) = controller(
            nodes = mapOf(avail to "powersave performance interactive schedutil"),
            cpuGovernors = mapOf(CpuController.AUTO to "interactive"),
        )
        assertTrue(cpu.setTier(CpuController.AUTO))
        assertTrue(root.ran.any { it.contains("echo interactive") })
    }

    @Test fun setTierFalseWhenNoGovernorsAvailable() {
        val (cpu, _) = controller(emptyMap())
        assertFalse(cpu.setTier(CpuController.AUTO))
    }
}
