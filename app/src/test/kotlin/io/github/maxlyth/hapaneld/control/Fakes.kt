package io.github.maxlyth.hapaneld.control

import io.github.maxlyth.hapaneld.device.DeviceProfile
import io.github.maxlyth.hapaneld.device.EvdevButton
import io.github.maxlyth.hapaneld.device.LedMechanism
import io.github.maxlyth.hapaneld.device.ScreenOff
import io.github.maxlyth.hapaneld.device.SuForm
import io.github.maxlyth.hapaneld.platform.Daemon
import io.github.maxlyth.hapaneld.platform.RootShell

/** Minimal [DeviceProfile] for controller tests — only the fields a controller reads need setting. */
fun fakeProfile(
    appCanSu: Boolean = false,
    cpuGovernors: Map<String, String>? = null,
    relayBase: String? = null,
    relayBaseFallbacks: List<String> = emptyList(),
    buttonLedGpioBase: Int? = null,
    zigbeeGatewayDir: String? = null,
): DeviceProfile = object : DeviceProfile {
    override val id = "test"
    override val displayName = "Test"
    override val socClass = "test"
    override val suForm = if (appCanSu) SuForm.TOOLBOX else SuForm.NONE
    override val appCanSu = appCanSu
    override val ledMechanism = LedMechanism.NONE
    override val screenOff = ScreenOff.BRIGHTNESS_ZERO
    override val zigbeeGatewayDir = zigbeeGatewayDir
    override val relayBase = relayBase
    override val relayBaseFallbacks = relayBaseFallbacks
    override val buttonLedGpioBase = buttonLedGpioBase
    override val manufacturer: String? = null
    override val model: String? = null
    override val evdevButtons = emptyList<EvdevButton>()
    override val cpuGovernors = cpuGovernors
    override val recommendedDensity: Int? = null
    override val recommendedFontScale: Float? = null
}

/**
 * Fake [RootShell]. [outputs] maps a command substring to the stdout [runOutput] returns (the longest
 * matching key wins, so a specific path beats a generic one); [run]/[fireAndForget] record the command
 * in [ran] and report [runResult]. No real process is ever spawned.
 */
class FakeRootShell(
    private val outputs: Map<String, String> = emptyMap(),
    private val available: Boolean = true,
    private val runResult: Boolean = true,
) : RootShell {
    val ran = mutableListOf<String>()
    override fun available() = available
    override fun run(cmd: String): Boolean { ran += cmd; return runResult }
    override fun runOutput(cmd: String): String? =
        outputs.entries.sortedByDescending { it.key.length }.firstOrNull { cmd.contains(it.key) }?.value
    override fun runBytes(cmd: String): ByteArray? = null
    override fun fireAndForget(cmd: String) { ran += cmd }
}

/** Fake [Daemon]. [replies] maps an exact command line to its reply; sends are recorded in [sent]. */
class FakeDaemon(
    private val replies: Map<String, String> = emptyMap(),
    private val available: Boolean = true,
) : Daemon {
    val sent = mutableListOf<String>()
    override fun available() = available
    override fun send(cmd: String): String? { sent += cmd; return replies[cmd] }
    override fun sendBytes(cmd: String): ByteArray? = null
}
