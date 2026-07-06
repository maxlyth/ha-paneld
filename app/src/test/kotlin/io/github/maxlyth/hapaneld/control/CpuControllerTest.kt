package io.github.maxlyth.hapaneld.control

import io.github.maxlyth.hapaneld.metrics.PanelMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CpuController's pure logic — governor parsing, tier reverse-mapping, and tier→governor resolution.
 * Governors are now READ through the shared [PanelMetrics] reader (fed a [FakeMetricSource]); the *write*
 * still goes through the [RootShell] seam, so writes are asserted via `root.ran`. No device, no su.
 */
class CpuControllerTest {

    private fun controller(
        avail: String? = null,
        gov: String? = null,
        cpuGovernors: Map<String, String>? = null,
    ): Pair<CpuController, FakeRootShell> {
        val root = FakeRootShell()   // records writes
        val metrics = PanelMetrics(FakeMetricSource(governor = gov, availableGovernors = avail))
        return CpuController(fakeProfile(appCanSu = true, cpuGovernors = cpuGovernors), root, FakeDaemon(available = false), metrics) to root
    }

    @Test fun governorsParsesTheAvailableList() {
        val (cpu, _) = controller(avail = "powersave performance schedutil\n")
        assertEquals(listOf("powersave", "performance", "schedutil"), cpu.governors())
        assertTrue(cpu.available())
    }

    @Test fun currentTierReverseMapsGovernors() {
        assertEquals(CpuController.PERFORMANCE, controller(gov = "performance").first.currentTier())
        assertEquals(CpuController.EFFICIENCY, controller(gov = "powersave").first.currentTier())
        // any dynamic governor reads back as Auto
        assertEquals(CpuController.AUTO, controller(gov = "schedutil").first.currentTier())
    }

    @Test fun currentTierNullWhenUnreadable() {
        assertNull(controller().first.currentTier())
    }

    @Test fun setTierResolvesAutoToADynamicGovernorAndWritesIt() {
        val (cpu, root) = controller(avail = "powersave performance schedutil")
        assertTrue(cpu.setTier(CpuController.AUTO))
        assertTrue("expected a schedutil write, got ${root.ran}", root.ran.any { it.contains("echo schedutil") })
    }

    @Test fun setTierPerformancePicksThePerformanceGovernor() {
        val (cpu, root) = controller(avail = "powersave performance schedutil")
        assertTrue(cpu.setTier(CpuController.PERFORMANCE))
        assertTrue(root.ran.any { it.contains("echo performance") })
    }

    @Test fun profileGovernorOverrideHonouredWhenAvailable() {
        // PX30 maps Auto -> interactive; the profile mapping wins when the SoC offers that governor.
        val (cpu, root) = controller(
            avail = "powersave performance interactive schedutil",
            cpuGovernors = mapOf(CpuController.AUTO to "interactive"),
        )
        assertTrue(cpu.setTier(CpuController.AUTO))
        assertTrue(root.ran.any { it.contains("echo interactive") })
    }

    @Test fun setTierFalseWhenNoGovernorsAvailable() {
        val (cpu, _) = controller()
        assertFalse(cpu.setTier(CpuController.AUTO))
    }
}
