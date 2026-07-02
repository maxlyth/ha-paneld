package io.github.maxlyth.hapaneld.control

import io.github.maxlyth.hapaneld.device.DeviceProfile
import io.github.maxlyth.hapaneld.device.EvdevButton
import io.github.maxlyth.hapaneld.device.LedMechanism
import io.github.maxlyth.hapaneld.device.ScreenOff
import io.github.maxlyth.hapaneld.device.SuForm
import io.github.maxlyth.hapaneld.platform.Daemon
import io.github.maxlyth.hapaneld.platform.RootShell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises CpuController's pure logic — governor parsing, tier reverse-mapping, and tier→governor
 * resolution — over the [RootShell] seam, with no device and no su. This is the first controller test
 * the platform seams make possible: previously the class reached the concrete Su object directly.
 */
class CpuControllerTest {

    private val avail = "/sys/devices/system/cpu/cpu0/cpufreq/scaling_available_governors"
    private val gov0 = "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor"

    /** RootShell fake: serves canned sysfs contents for `cat <path>` reads and records governor writes. */
    private class FakeRoot(private val nodes: Map<String, String>) : RootShell {
        val ran = mutableListOf<String>()
        override fun available() = true
        override fun run(cmd: String): Boolean { ran += cmd; return true }
        override fun runOutput(cmd: String): String? =
            Regex("""cat (\S+)""").find(cmd)?.groupValues?.get(1)?.let { nodes[it] }
        override fun runBytes(cmd: String): ByteArray? = null
        override fun fireAndForget(cmd: String) {}
    }

    private object DeadDaemon : Daemon {
        override fun available() = false
        override fun send(cmd: String): String? = null
        override fun sendBytes(cmd: String): ByteArray? = null
    }

    /** Minimal su-capable profile; only appCanSu and cpuGovernors matter to CpuController. */
    private fun suProfile(cpuGovernors: Map<String, String>? = null) = object : DeviceProfile {
        override val id = "test"
        override val displayName = "Test"
        override val socClass = "test"
        override val suForm = SuForm.TOOLBOX
        override val appCanSu = true
        override val ledMechanism = LedMechanism.NONE
        override val screenOff = ScreenOff.BRIGHTNESS_ZERO
        override val zigbeeGatewayDir: String? = null
        override val relayBase: String? = null
        override val buttonLedGpioBase: Int? = null
        override val manufacturer: String? = null
        override val model: String? = null
        override val evdevButtons = emptyList<EvdevButton>()
        override val cpuGovernors = cpuGovernors
        override val recommendedDensity: Int? = null
        override val recommendedFontScale: Float? = null
    }

    private fun controller(nodes: Map<String, String>, cpuGovernors: Map<String, String>? = null): Pair<CpuController, FakeRoot> {
        val root = FakeRoot(nodes)
        return CpuController(suProfile(cpuGovernors), root, DeadDaemon) to root
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
