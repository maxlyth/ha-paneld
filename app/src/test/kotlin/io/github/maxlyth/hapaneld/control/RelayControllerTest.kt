package io.github.maxlyth.hapaneld.control

import io.github.maxlyth.hapaneld.platform.RootShell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RelayController over the RootShell seam — base resolution (primary vs firmware-renamed fallback),
 * relay read/write, inert-when-unprofiled, and button-LED gpio counting — with no device.
 */
class RelayControllerTest {

    private val base = "/sys/class/strelay"

    private fun relay(
        outputs: Map<String, String>,
        relayBase: String? = base,
        fallbacks: List<String> = emptyList(),
        ledBase: Int? = null,
    ): Pair<RelayController, FakeRootShell> {
        val root = FakeRootShell(outputs)
        return RelayController(
            fakeProfile(relayBase = relayBase, relayBaseFallbacks = fallbacks, buttonLedGpioBase = ledBase),
            root,
        ) to root
    }

    @Test fun countsRelayNodesUnderTheBase() {
        val (r, _) = relay(mapOf("ls $base" to "relay1 relay2 power"))
        assertEquals(2, r.count())
        assertTrue(r.available())
    }

    @Test fun inertWithNoRelayBase() {
        val (r, root) = relay(emptyMap(), relayBase = null)
        assertEquals(0, r.count())
        assertFalse(r.available())
        assertFalse(r.set(1, true))
        assertTrue(root.ran.isEmpty())
    }

    @Test fun setWritesRelayNode() {
        val (r, root) = relay(mapOf("ls $base" to "relay1 relay2"))
        assertTrue(r.set(1, true))
        assertTrue(root.ran.contains("printf '%s' '1' > $base/relay1"))
        assertTrue(r.set(2, false))
        assertTrue(root.ran.contains("printf '%s' '0' > $base/relay2"))
    }

    @Test fun getReadsRelayState() {
        val (r, _) = relay(mapOf("ls $base" to "relay1", "cat $base/relay1" to "1"))
        assertTrue(r.get(1))
    }

    @Test fun stateAuditsReuseOneRelayTopologyProbe() {
        val (r, root) = relay(mapOf("ls $base" to "relay1 relay2", "cat $base/relay1" to "1"))
        assertEquals(2, r.count())
        repeat(5) { assertTrue(r.read(1) == true) }
        assertEquals(1, root.outputRan.count { it.contains("ls $base") })
        assertEquals(5, root.outputRan.count { it.contains("cat $base/relay1") })
    }

    @Test fun unavailableRelayProbeIsRetriedThenSuccessfulTopologyIsCached() {
        val root = SequencedRootShell(mapOf("ls $base" to listOf(null, "relay1 relay2")))
        val r = RelayController(fakeProfile(relayBase = base), root)

        assertEquals(0, r.count())
        assertEquals(2, r.count())
        assertEquals(2, r.count())
        assertEquals(2, root.outputRan.count { it.contains("ls $base") })
    }

    @Test fun capturedUnavailableRootRouteSkipsDiscoveryWithoutCachingAbsence() {
        val (r, root) = relay(mapOf("ls $base" to "relay1 relay2"))

        assertEquals(0, r.count(allowRootProbe = false))
        assertTrue(root.outputRan.isEmpty())
        assertEquals(2, r.count(allowRootProbe = true))
        assertEquals(1, root.outputRan.count { it.contains("ls $base") })
        assertEquals(2, r.count(allowRootProbe = false))
        assertEquals(1, root.outputRan.count { it.contains("ls $base") })
    }

    @Test fun confirmedRelayAbsenceIsCached() {
        val root = SequencedRootShell(mapOf("ls $base" to listOf("power")))
        val r = RelayController(fakeProfile(relayBase = base), root)

        assertEquals(0, r.count())
        assertEquals(0, r.count())
        assertEquals(1, root.outputRan.count { it.contains("ls $base") })
    }

    @Test fun fallbackBaseUsedWhenPrimaryHasNoNodes() {
        val alt = "/sys/class/st_relay"
        val (r, _) = relay(
            outputs = mapOf("ls $base" to "", "ls $alt" to "relay1"),
            fallbacks = listOf(alt),
        )
        assertEquals(1, r.count())
    }

    @Test fun relayDiscoveryStopsAtTheFirstMissingIndex() {
        val (r, _) = relay(mapOf("ls $base" to "relay1 relay3 relay99"))

        assertEquals(1, r.count())
    }

    @Test fun relayDiscoveryRequiresCanonicalNodeNames() {
        val (r, _) = relay(mapOf("ls $base" to "relay01 relay-1 relayx"))

        assertEquals(0, r.count())
    }

    @Test fun outOfRangeRelayNeverReachesTheRootWriter() {
        val (r, root) = relay(mapOf("ls $base" to "relay1 relay2"))

        assertFalse(r.set(0, true))
        assertFalse(r.set(3, true))
        assertEquals(null, r.read(3))
        assertFalse(root.ran.any { it.contains("relay0") || it.contains("relay3") })
        assertFalse(root.outputRan.any { it.contains("cat $base/relay3") })
    }

    @Test fun ledCountUsesDeclaredTopologyWithoutMutatingGpio() {
        val ledBase = 147
        val (r, root) = relay(emptyMap(), ledBase = ledBase)
        assertEquals(4, r.ledCount())
        assertTrue(root.outputRan.isEmpty())
    }

    @Test fun stateAuditsDoNotExportOrChangeButtonLedTopology() {
        val ledBase = 147
        val outputs = mapOf("cat /sys/class/gpio/gpio147/value" to "1")
        val (r, root) = relay(outputs, ledBase = ledBase)
        assertEquals(4, r.ledCount())
        repeat(3) { assertTrue(r.ledRead(0) == true) }
        assertTrue(root.ran.isEmpty())
        assertEquals(0, root.outputRan.count { it.contains("/sys/class/gpio/export") })
        assertEquals(3, root.outputRan.count { it.contains("cat /sys/class/gpio/gpio147/value") })
    }

    @Test fun gpioIsPreparedOnlyWhenAnLedWriteIsRequested() {
        val ledBase = 147
        val root = SequencedRootShell(mapOf("gpio147" to listOf("ready")))
        val r = RelayController(fakeProfile(buttonLedGpioBase = ledBase), root)

        assertEquals(4, r.ledCount())
        assertTrue(root.outputRan.isEmpty())
        assertTrue(r.ledSet(0, true))
        assertEquals(1, root.outputRan.count { it.contains("gpio147") })
        assertTrue(root.ran.contains("printf '%s' '1' > /sys/class/gpio/gpio147/value"))
    }

    @Test fun directionFailureDoesNotExposeInputPinAsUsable() {
        val ledBase = 147
        val root = SequencedRootShell(mapOf("gpio147" to listOf("input")))
        val r = RelayController(fakeProfile(buttonLedGpioBase = ledBase), root)

        assertEquals(4, r.ledCount())
        assertFalse(r.ledSet(0, true))
        assertTrue(root.ran.isEmpty())
        assertEquals(1, root.outputRan.size)
        root.outputRan.forEach { command ->
            assertTrue(command.contains("cat /sys/class/gpio/gpio147/direction"))
            assertTrue(command.contains("[ \"\$direction\" = out ]"))
            assertTrue(command.contains("[ -w /sys/class/gpio/gpio147/value ]"))
        }
    }

    @Test fun outOfRangeButtonLedNeverAddressesAnotherGpio() {
        val ledBase = 147
        val outputs = (0 until 4).associate { "gpio${ledBase + it}" to "ready" }
        val (r, root) = relay(outputs, ledBase = ledBase)

        assertFalse(r.ledSet(-1, true))
        assertFalse(r.ledSet(4, true))
        assertEquals(null, r.ledRead(4))
        assertFalse(root.ran.any { it.contains("gpio146") || it.contains("gpio151") })
        assertFalse(root.outputRan.any { it.contains("gpio146") || it.contains("gpio151") })
    }

    private class SequencedRootShell(
        outputs: Map<String, List<String?>>,
    ) : RootShell {
        private val outputs = outputs.mapValues { (_, values) -> values.toMutableList() }
        val ran = mutableListOf<String>()
        val outputRan = mutableListOf<String>()

        override fun available(): Boolean = true

        override fun run(cmd: String): Boolean {
            ran += cmd
            return true
        }

        override fun runOutput(cmd: String): String? {
            outputRan += cmd
            val responses = outputs.entries
                .sortedByDescending { it.key.length }
                .firstOrNull { cmd.contains(it.key) }
                ?.value
                ?: return null
            return if (responses.size > 1) responses.removeAt(0) else responses.firstOrNull()
        }

        override fun runBytes(cmd: String): ByteArray? = null

        override fun fireAndForget(cmd: String): Boolean {
            ran += cmd
            return true
        }
    }
}
