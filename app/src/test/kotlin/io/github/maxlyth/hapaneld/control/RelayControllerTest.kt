package io.github.maxlyth.hapaneld.control

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
        assertTrue(root.ran.contains("echo 1 > $base/relay1"))
        assertTrue(r.set(2, false))
        assertTrue(root.ran.contains("echo 0 > $base/relay2"))
    }

    @Test fun getReadsRelayState() {
        val (r, _) = relay(mapOf("ls $base" to "relay1", "cat $base/relay1" to "1"))
        assertTrue(r.get(1))
    }

    @Test fun fallbackBaseUsedWhenPrimaryHasNoNodes() {
        val alt = "/sys/class/st_relay"
        val (r, _) = relay(
            outputs = mapOf("ls $base" to "", "ls $alt" to "relay1"),
            fallbacks = listOf(alt),
        )
        assertEquals(1, r.count())
    }

    @Test fun ledCountCountsPresentValueNodes() {
        val ledBase = 147
        val outputs = mapOf("ls $base" to "relay1") +
            (0 until 4).associate { "gpio${ledBase + it}/value" to "x" }
        val (r, _) = relay(outputs, ledBase = ledBase)
        assertEquals(4, r.ledCount())
    }
}
