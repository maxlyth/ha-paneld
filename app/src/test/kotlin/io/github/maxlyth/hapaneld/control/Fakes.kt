package io.github.maxlyth.hapaneld.control

import io.github.maxlyth.hapaneld.device.DeviceProfile
import io.github.maxlyth.hapaneld.device.EvdevButton
import io.github.maxlyth.hapaneld.device.LedMechanism
import io.github.maxlyth.hapaneld.device.ScreenOff
import io.github.maxlyth.hapaneld.device.SuForm
import io.github.maxlyth.hapaneld.metrics.MetricSource
import io.github.maxlyth.hapaneld.platform.ActivityRef
import io.github.maxlyth.hapaneld.platform.Daemon
import io.github.maxlyth.hapaneld.platform.DaemonLongResult
import io.github.maxlyth.hapaneld.platform.RootShell
import io.github.maxlyth.hapaneld.platform.ScreenPower
import io.github.maxlyth.hapaneld.platform.SystemEnv

/** Minimal [DeviceProfile] for controller tests — only the fields a controller reads need setting. */
fun fakeProfile(
    appCanSu: Boolean = false,
    cpuGovernors: Map<String, String>? = null,
    relayBase: String? = null,
    relayBaseFallbacks: List<String> = emptyList(),
    buttonLedGpioBase: Int? = null,
    zigbeeGatewayDir: String? = null,
    evdevButtons: List<EvdevButton> = emptyList(),
    id: String = "test",
    revision: String = "test-revision",
    displayName: String = "Test",
    hasButtonBacklight: Boolean = false,
    recommendedDensity: Int? = null,
    recommendedFontScale: Float? = null,
): DeviceProfile = object : DeviceProfile {
    override val id = id
    override val revision = revision
    override val displayName = displayName
    override val socClass = "test"
    override val suForm = if (appCanSu) SuForm.TOOLBOX else SuForm.NONE
    override val appCanSu = appCanSu
    override val ledMechanism = LedMechanism.NONE
    override val screenOff = ScreenOff.BRIGHTNESS_ZERO
    override val zigbeeGatewayDir = zigbeeGatewayDir
    override val relayBase = relayBase
    override val relayBaseFallbacks = relayBaseFallbacks
    override val buttonLedGpioBase = buttonLedGpioBase
    override val hasButtonBacklight = hasButtonBacklight
    override val manufacturer: String? = null
    override val model: String? = null
    override val evdevButtons = evdevButtons
    override val cpuGovernors = cpuGovernors
    override val recommendedDensity = recommendedDensity
    override val recommendedFontScale = recommendedFontScale
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
    val outputRan = mutableListOf<String>()
    val isolatedOutputRan = mutableListOf<String>()
    override fun available() = available
    override fun run(cmd: String): Boolean { ran += cmd; return runResult }
    override fun runOutput(cmd: String): String? {
        outputRan += cmd
        return outputs.entries.sortedByDescending { it.key.length }.firstOrNull { cmd.contains(it.key) }?.value
    }
    override fun runOutputIsolatedBounded(cmd: String, maxBytes: Long, timeoutMs: Long): String? {
        isolatedOutputRan += cmd
        return outputs.entries.sortedByDescending { it.key.length }.firstOrNull { cmd.contains(it.key) }
            ?.value?.takeIf { it.toByteArray().size.toLong() <= maxBytes }
    }
    override fun runBytes(cmd: String): ByteArray? = null
    override fun fireAndForget(cmd: String): Boolean { ran += cmd; return runResult }
}

/** Fake [MetricSource] for controllers that read through [io.github.maxlyth.hapaneld.metrics.PanelMetrics]
 *  (e.g. [CpuController]'s governor reads). Only the fields a test needs are set; everything else is the
 *  "unavailable" default. */
class FakeMetricSource(
    private val governor: String? = null,
    private val availableGovernors: String? = null,
) : MetricSource {
    val governorRootFallbacks = mutableListOf<Boolean>()
    val availableGovernorRootFallbacks = mutableListOf<Boolean>()
    override fun perfDump(): String? = null
    override fun statText(): String? = null
    override fun meminfoText(): String? = null
    override fun loadavgText(): String? = null
    override fun thermalMilliValues(): List<Long> = emptyList()
    override fun gpuLoadRaw(): String? = null
    override fun cpuFreqCurMhz(): List<Long> = emptyList()
    override fun cpuFreqMaxMhz(): Long = 0
    override fun localIp(): String? = null
    override fun selinuxEnforce(): String? = null
    override fun cpuGovernor(allowRootFallback: Boolean): String? {
        governorRootFallbacks += allowRootFallback
        return governor
    }
    override fun cpuAvailableGovernors(allowRootFallback: Boolean): String? {
        availableGovernorRootFallbacks += allowRootFallback
        return availableGovernors
    }
    override fun roomClimateDaemon(): String? = null
    override fun roomClimateShell(): String? = null
}

/** Fake [Daemon]. [replies] maps an exact command line to its reply; sends are recorded in [sent]. */
class FakeDaemon(
    private val replies: Map<String, String> = emptyMap(),
    private val available: Boolean = true,
) : Daemon {
    val sent = mutableListOf<String>()
    override fun available() = available
    override fun send(cmd: String): String? { sent += cmd; return replies[cmd] }
    override fun sendLong(cmd: String, timeoutMs: Long): DaemonLongResult =
        send(cmd)?.let(DaemonLongResult::Reply) ?: DaemonLongResult.NotSubmitted
    override fun sendBytes(cmd: String): ByteArray? = null
}

/** Fake [Backlight]: serves a settable [level] for getBrightness; records set calls. */
class FakeBacklight(var level: Int = 160) : Backlight {
    val calls = mutableListOf<String>()
    override fun getBrightness() = level
    override fun setBrightness(level: Int) { calls += "set:$level"; this.level = level }
    override fun setBrightnessRaw(level: Int) { calls += "raw:$level"; this.level = level }
}

/** Fake [ScreenPower]: settable interactivity; counts wake pulses. */
class FakeScreenPower(var interactive: Boolean = true) : ScreenPower {
    var pulses = 0
    override fun isInteractive() = interactive
    override fun pulseWake() { pulses++ }
}

/**
 * Fake [WakeTap]: [canArm] is settable (models overlay-permission held/not); records arm/disarm and
 * captures the arm callback so a test can [fireTap] to simulate a touch on the dark screen.
 */
class FakeWakeTap(
    var canArm: Boolean = true,
    var armSucceeds: Boolean = true,
) : io.github.maxlyth.hapaneld.platform.WakeTap {
    var armed = false
    private var onTap: (() -> Unit)? = null
    override fun canArm() = canArm
    override fun arm(onTap: () -> Unit): Boolean {
        if (!canArm || !armSucceeds) return false
        armed = true
        this.onTap = onTap
        return true
    }
    override fun disarm() { armed = false; onTap = null }
    /** Simulate a tap while armed — invokes the wake callback ScreenController registered. */
    fun fireTap() { onTap?.invoke() }
}

/**
 * Fake [SystemEnv]: an in-memory package/activity environment. [installed] gates [isInstalled];
 * [launchers] maps a package to its launch component; [homes] is the CATEGORY_HOME activity list;
 * [default] is the current default HOME. [directStarts] records best-effort direct starts.
 */
class FakeSystemEnv(
    override val ownPackage: String = "io.github.maxlyth.hapaneld",
    var installed: Set<String> = emptySet(),
    var launchers: Map<String, String> = emptyMap(),
    var homes: List<ActivityRef> = emptyList(),
    var default: ActivityRef? = null,
) : SystemEnv {
    val directStarts = mutableListOf<String>()
    override fun isInstalled(pkg: String) = pkg in installed
    override fun launchComponent(pkg: String) = launchers[pkg]
    override fun homeActivities() = homes
    override fun defaultHome() = default
    override fun directStart(component: String) { directStarts += component }
}
