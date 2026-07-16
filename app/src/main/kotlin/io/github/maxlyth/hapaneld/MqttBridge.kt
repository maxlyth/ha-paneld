package io.github.maxlyth.hapaneld

import android.os.Build
import android.os.SystemClock
import android.util.Log
import io.github.maxlyth.hapaneld.control.AdbController
import io.github.maxlyth.hapaneld.control.AutoBrightnessController
import io.github.maxlyth.hapaneld.control.BootChimeController
import io.github.maxlyth.hapaneld.control.BrightnessController
import io.github.maxlyth.hapaneld.control.CpuController
import io.github.maxlyth.hapaneld.control.LedEffectController
import io.github.maxlyth.hapaneld.control.LedCommandPolicy
import io.github.maxlyth.hapaneld.hardware.LedEffects
import io.github.maxlyth.hapaneld.util.BrokerEndpoint
import io.github.maxlyth.hapaneld.util.ConflatedWorker
import io.github.maxlyth.hapaneld.util.HaLink
import io.github.maxlyth.hapaneld.control.NavbarController
import io.github.maxlyth.hapaneld.control.NavbarModeApplyOutcome
import io.github.maxlyth.hapaneld.control.NavbarModeApplyStatus
import io.github.maxlyth.hapaneld.control.NavActions
import io.github.maxlyth.hapaneld.control.NavigateController
import io.github.maxlyth.hapaneld.control.RelayController
import io.github.maxlyth.hapaneld.control.ScreenController
import io.github.maxlyth.hapaneld.control.BuiltinDashboard
import io.github.maxlyth.hapaneld.control.SystemController
import io.github.maxlyth.hapaneld.control.KioskController
import io.github.maxlyth.hapaneld.control.WatchdogController
import io.github.maxlyth.hapaneld.control.TouchSoundController
import io.github.maxlyth.hapaneld.control.VolumeController
import io.github.maxlyth.hapaneld.control.ZigbeeController
import io.github.maxlyth.hapaneld.control.ZigbeeHealthSnapshot
import io.github.maxlyth.hapaneld.config.Capabilities
import io.github.maxlyth.hapaneld.config.SettingValue
import io.github.maxlyth.hapaneld.config.SettingsRegistry
import io.github.maxlyth.hapaneld.hardware.LedController
import io.github.maxlyth.hapaneld.input.ButtonBus
import io.github.maxlyth.hapaneld.metrics.FeatureCostOperation
import io.github.maxlyth.hapaneld.metrics.FeatureCostOutcome
import io.github.maxlyth.hapaneld.metrics.FeatureCosts
import io.github.maxlyth.hapaneld.mqtt.HiveMqTransport
import io.github.maxlyth.hapaneld.mqtt.MqttCallbacks
import io.github.maxlyth.hapaneld.mqtt.MqttConnectConfig
import io.github.maxlyth.hapaneld.mqtt.MqttFinalPublish
import io.github.maxlyth.hapaneld.control.Diagnostics
import io.github.maxlyth.hapaneld.mqtt.MqttTransport
import io.github.maxlyth.hapaneld.mqtt.classifyDisconnect
import io.github.maxlyth.hapaneld.mqtt.AuthRecovery
import io.github.maxlyth.hapaneld.mqtt.isAuthRecoveryState
import io.github.maxlyth.hapaneld.util.HelperClient
import io.github.maxlyth.hapaneld.util.Json
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.RejectedExecutionException
import java.util.WeakHashMap
import kotlin.math.roundToInt
import org.json.JSONObject

internal fun liveSettingApplyResult(
    result: MqttCommandDispatcher.RunResult,
): LiveSettingApplyResult = when {
    result.admission == MqttCommandDispatcher.Admission.CLOSED ||
        result.admission == MqttCommandDispatcher.Admission.REJECTED ->
        LiveSettingApplyResult.DEFERRED
    result.execution == MqttCommandDispatcher.Execution.SUCCEEDED ->
        LiveSettingApplyResult.APPLIED
    else -> LiveSettingApplyResult.FAILED
}

internal fun applyAcknowledgedNavbarMode(
    payload: String,
    previousMode: String,
    actuate: (String) -> CompletableFuture<NavbarModeApplyOutcome>,
    rollback: (expectedMode: String, previousMode: String) -> CompletableFuture<NavbarModeApplyOutcome>,
    persist: (String) -> Boolean,
    reconcile: () -> Unit,
    timeoutMs: Long = NAVBAR_ACK_TIMEOUT_MS,
) {
    fun await(
        future: CompletableFuture<NavbarModeApplyOutcome>,
        label: String,
    ): NavbarModeApplyOutcome = try {
        future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    } catch (failure: Exception) {
        throw IllegalStateException("navbar $label acknowledgement failed", failure)
    }

    val outcome = try {
        await(actuate(payload), "actuation")
    } catch (failure: IllegalStateException) {
        // Conditional rollback supersedes this request only if it is still the controller's latest
        // desired mode. A newer HTTP/MQTT request remains authoritative and returns SUPERSEDED here.
        runCatching { await(rollback(payload, previousMode), "timeout rollback") }
        throw failure
    }
    check(outcome.status == NavbarModeApplyStatus.APPLIED) {
        "navbar transition ${outcome.status.name.lowercase()}"
    }
    val persisted = runCatching { persist(outcome.mode) }.getOrDefault(false)
    if (!persisted) {
        val restored = await(rollback(outcome.mode, previousMode), "persistence rollback")
        check(restored.status == NavbarModeApplyStatus.APPLIED ||
            restored.status == NavbarModeApplyStatus.SUPERSEDED
        ) { "navbar persistence failed and rollback ${restored.status.name.lowercase()}" }
        error("navbar persistence failed")
    }
    reconcile()
}

private const val NAVBAR_ACK_TIMEOUT_MS = 20_000L

/**
 * MQTT bridge — the single uniform control API across the fleet. Publishes Home Assistant
 * MQTT-discovery configs so every panel exposes identical entities (the per-hardware HAL is hidden
 * behind them), subscribes to the command topics, and dispatches to the controllers. Best-effort:
 * disabled silently when no broker is configured (the HTTP /play surface works standalone).
 *
 * Entities published (per panel):
 * - `light.<panel>_screen`  — brightness + on/off (on=wake, off=sleep). HA-driven; no on-device loop.
 * - `light.<panel>_led`     — RGB (only if [led].available()).
 * - `text.<panel>_navigate` — URL navigate.
 * - `event.<panel>_button`  — hardware button events (only if [buttonsEnabled]).
 * - `media_player.<panel>_paneld` — TTS/announce (HTTP /play does the work).
 */
class MqttBridge(
    private val config: Config,
    private val brightness: BrightnessController,
    private val screen: ScreenController,
    private val led: LedController,
    // Drives strobe/blink/pulse on the LED (HA's built-in light `effect`). Service-owned + injected so a
    // bridge rebuild (reconfigure) can never orphan a running effect loop — there is only ever one.
    private val ledEffect: LedEffectController,
    private val navigate: NavigateController,
    private val volume: VolumeController,
    private val system: SystemController,
    // Soft on-screen navbar overlay (select: Off / Always on / Swipe reveal).
    private val navbar: NavbarController,
    // App watchdog (switch): self-heals a dead/abandoned dashboard. Toggling restarts its poll loop.
    private val watchdog: WatchdogController,
    // Experimental kiosk lock (switch): suppress/disable the system nav so a non-admin can't leave the dashboard.
    private val kiosk: KioskController,
    private val touchSound: TouchSoundController,
    private val bootChime: BootChimeController,
    // Zigbee gateway control (Sonoff NSPanel Pro only). Presence is detected lazily on the MQTT
    // thread in publishDiscovery — it costs a su exec, so it must not run on the main thread.
    private val zigbee: ZigbeeController,
    // On-board relays + button LEDs (Smatek S9E). Probed lazily on the MQTT thread.
    private val relay: RelayController,
    // CPU governor + persistent network adb (root/su panels). Probed lazily on the MQTT thread.
    private val cpu: CpuController,
    private val adb: AdbController,
    private val buttonsEnabled: Boolean,
    // Panel has hardware buttons instrumented via the daemon (evdev) — publish the event entity even
    // when the accessibility key capture is off (e.g. the WF1589T power button).
    private val hasEvdevButtons: Boolean,
    // Capability snapshot supplier for availableWhen gating of registry entities (null = no gating,
    // used by tests). Called on the MQTT thread at discovery time, so probes stay off the main thread.
    private val capabilities: (() -> Capabilities)? = null,
    private val hasLight: Boolean,
    private val hasProximity: Boolean,
    private val hasTemperature: Boolean,
    private val hasHumidity: Boolean,
    // Panel carries a CHT8305 room temp/humidity chip (daemon-read) — gates the opt-in Room sensors.
    private val hasCht8305: Boolean,
    private val hasButtonBacklight: Boolean,
    // Back/Recents route (root keyevent vs accessibility) + whether the firmware has an overview screen.
    private val appCanSu: Boolean,
    private val hasRecents: Boolean,
    // Optional on-panel auto-brightness engine; HA-fed lux is routed to it, switch/bias persist in Config.
    private val autoBright: AutoBrightnessController,
    // Evaluated for every discovery announcement so DHCP/address changes never leave HA's device-page
    // Visit link pinned to the address captured when the service process started.
    private val configUrl: () -> String? = { null },
    // Resolves HA's LAN IP via mDNS to default the broker when none is configured (injected by the
    // service, wired to MdnsAdvertiser). Returns null if HA isn't found / mDNS unavailable.
    private val discoverHaIp: () -> String? = { null },
    // HA's advertised base URL (scheme+host+port) from zeroconf TXT — for the "Open in HA" device link,
    // so we never guess a port/scheme. Null if HA isn't found / advertises no URL.
    private val discoverHaUrl: (configuredBroker: String) -> String? = { null },
    // Trigger an HA Companion app install/update. Injected by the service (needs Context + a
    // coroutine); runs off the MQTT thread. Fired by the update_companion button.
    private val onUpdateCompanion: () -> Unit = {},
    // Trigger a ha-paneld self-update on the configured channel. force=true installs the channel's newest
    // regardless of the version check (the update_paneld button + a pre-release→stable channel switch).
    private val onSelfUpdate: (force: Boolean) -> Unit = {},
    // Home-dashboard commands change the automatic entity-learning ownership target. Notify only after
    // the normalized path is durably visible so the manager can hide/rebuild the correct subscription.
    private val onDashboardTargetChanged: () -> Unit = {},
    private val zigbeeHealth: () -> ZigbeeHealthSnapshot = { ZigbeeHealthSnapshot() },
    private val onZigbeeExplicitRetry: () -> Unit = {},
    // A panel-id replaced by reconfiguration. Its discovery and availability are cleared by the NEW
    // connection, so cleanup cannot be lost when the old client is detached or the broker was offline.
    private val stalePanelId: String? = null,
    // Immutable id of the active profile revision (for example "panel.example@<sha256>"). A profile
    // switch can remove capabilities without changing the app version, so it must trigger the same
    // stale-discovery pruning as a core upgrade. Empty preserves the pre-profile marker for callers
    // that have not opted into runtime profiles.
    private val profileIdentity: String = "",
    // Event types declared by the active runtime profile. Discovery must advertise every value this
    // bridge can publish, not only the historical built-in key list.
    private val profileButtonEventTypes: Set<String> = emptySet(),
    // Immutable connection/advertisement identity for this concrete bridge generation. Config remains
    // live for settings that can be re-projected without replacing the broker connection.
    private val runtimePanelId: String = config.panelId,
    private val runtimeFriendlyName: String = config.friendlyName,
    private val runtimeBroker: String = config.mqttBroker,
    private val runtimeMqttUser: String = config.mqttUser,
    private val runtimeMqttPassword: String = config.mqttPassword,
) {
    private enum class CommandKind { LATEST, ACTION }

    private val haLinkResolutionInFlight = AtomicBoolean(false)
    private val adbReassertInFlight = AtomicBoolean(false)
    @Volatile private var reloadNavigationFuture: ScheduledFuture<*>? = null
    private val transport: MqttTransport = HiveMqTransport()
    private val discoveryCapabilities = MqttDiscoveryCapabilitySource(
        supplier = capabilities,
        onFailure = { error ->
            Log.w(TAG, "capability snapshot failed; publishing conservative discovery", error)
        },
    )
    // Channel shape grows monotonically after a live capability is confirmed. A transient negative
    // snapshot therefore publishes unknown/unavailable state instead of deleting the channel forever.
    private val capabilityShape = MqttCapabilityShape()
    private val capabilityChannelLock = Any()

    /** Broker actually in use — configured, or auto-discovered as `tcp://<ha-ip>:1883`; "" if none. */
    var activeBroker: String = ""
        private set
    // The configured broker string at construction time. Config is a live mutable store, so the service
    // needs this snapshot to decide whether reconfigure is retiring a genuinely different broker.
    internal val configuredBroker: String = runtimeBroker.trim()

    /** Whether the active connection uses TLS (a ssl:///mqtts:// broker URL). Surfaced on the info page
     *  + /diag so a TLS setup is visible. */
    @Volatile var tlsActive: Boolean = false
        private set

    /** Live connection state for the UI, so an auth failure reads differently from "unreachable":
     *  connected | auth-failed | unreachable | connecting | discovering | disabled. */
    @Volatile var state: String = "disabled"
        private set
    @Volatile private var stopped = false
    private val stopLock = Any()
    private val lifecycleGeneration = AtomicLong()
    private val commandDispatcher = MqttCommandDispatcher()
    private val discoveryAnnouncementLock = Any()
    private var buttonSubscription: ButtonBus.Subscription? = null

    fun isConnected(): Boolean = state == "connected"

    /** Monotonic timestamp (elapsedRealtime) of the last publish that the broker actually ACKed, plus
     *  every (re)connect. This is a TRUE liveness signal — unlike [state]/[isConnected], which reflect
     *  only HiveMQ's own connect/disconnect callbacks and stay "connected" on a half-open (CLOSE-WAIT)
     *  socket the broker already dropped. The service watchdog reconnects when this goes stale. 0 until
     *  the first successful connect. */
    @Volatile var lastOkMs: Long = 0L
        private set

    /** Happy-eyeballs family preference for the NEXT connect. Starts IPv6-first (first-class); the
     *  liveness watchdog flips it via [reconnect] when a family won't hold, so the bridge lands on
     *  whichever family actually works and stays there. */
    @Volatile private var preferIpv4: Boolean = false
    @Volatile private var lastPublishedConfigUrl: String? = null

    private val authRecovery = AuthRecovery(jitter = { base, _ ->
        // Bounded ±20% jitter prevents a fleet-wide broker restart becoming a synchronized retry storm.
        (base * (80 + java.util.concurrent.ThreadLocalRandom.current().nextInt(41)) / 100)
    })
    private val authScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "mqtt-auth-recovery").apply { isDaemon = true }
    }
    @Volatile private var authRetryGeneration = 0L

    private fun markOk() { lastOkMs = SystemClock.elapsedRealtime() }

    /** Milliseconds since the last broker-ACKed activity, or 0 if never connected (so a not-yet-started
     *  bridge never looks "stale" to the watchdog — the state-based check covers startup). */
    fun msSinceLastOk(): Long = if (lastOkMs == 0L) 0L else SystemClock.elapsedRealtime() - lastOkMs

    /** Public-paste-safe MQTT status — state + broker-ACKed liveness age + address-family preference,
     *  deliberately WITHOUT the broker host (the host stays in the info page's "MQTT" row, which /diag
     *  omits). This is the row a /diag dump needs to answer "is this panel broker-connected?". */
    fun statusPublic(): String {
        if (state == "disabled") return "disabled"
        val age = if (lastOkMs == 0L) "never" else "${msSinceLastOk() / 1000}s ago"
        val transport = if (tlsActive) "TLS" else "TCP"
        val a = authRecovery.snapshot(SystemClock.elapsedRealtime())
        val auth = if (a.consecutiveRejects == 0) "auth-ok" else
            "${a.state} · rejects ${a.consecutiveRejects} · attempt ${a.retryAttempt} · next ${a.nextRetryMs?.let { ((it - SystemClock.elapsedRealtime()).coerceAtLeast(0) / 1000).toString() + "s" } ?: "none"}"
        val lastAuth = a.lastSuccessMs?.let { "${((SystemClock.elapsedRealtime() - it).coerceAtLeast(0) / 1000)}s ago" } ?: "never"
        return "$state · $transport · last-ok $age · last-auth $lastAuth · $auth · prefer ${if (preferIpv4) "IPv4" else "IPv6"}"
    }

    private val panel = runtimePanelId
    internal val panelId: String get() = panel
    private val availabilityTopic = "ha-paneld/$panel/availability"
    private val cmdScreen = "ha-paneld/$panel/screen/set"
    private val cmdLed = "ha-paneld/$panel/led/set"
    private val cmdNavigate = "ha-paneld/$panel/navigate/set"
    private val cmdVolume = "ha-paneld/$panel/volume/set"
    private val cmdReload = "ha-paneld/$panel/reload/set"
    private val cmdHomeDashboard = "ha-paneld/$panel/home_dashboard/set"
    private val stateHomeDashboard = "ha-paneld/$panel/home_dashboard/state"
    private val cmdReboot = "ha-paneld/$panel/reboot/set"
    private val cmdLauncher = "ha-paneld/$panel/launcher/set"
    private val cmdHome = "ha-paneld/$panel/home/set"
    private val cmdAdminLauncher = "ha-paneld/$panel/admin_launcher/set"
    private val cmdButtons = "ha-paneld/$panel/buttons/set"
    private val stateButtons = "ha-paneld/$panel/buttons/state"
    private val cmdBack = "ha-paneld/$panel/back/set"
    private val cmdRecents = "ha-paneld/$panel/recents/set"
    private val cmdNavbar = "ha-paneld/$panel/navbar/set"
    private val stateNavbar = "ha-paneld/$panel/navbar/state"
    private val cmdWakeOnWave = "ha-paneld/$panel/wake_on_wave/set"
    private val stateWakeOnWave = "ha-paneld/$panel/wake_on_wave/state"
    private val cmdTouchSound = "ha-paneld/$panel/touch_sound/set"
    private val stateTouchSound = "ha-paneld/$panel/touch_sound/state"
    private val cmdWatchdog = "ha-paneld/$panel/watchdog/set"
    private val stateWatchdog = "ha-paneld/$panel/watchdog/state"
    private val cmdKiosk = "ha-paneld/$panel/kiosk_lock/set"
    private val stateKiosk = "ha-paneld/$panel/kiosk_lock/state"
    private val cmdUpdateCompanion = "ha-paneld/$panel/update_companion/set"
    private val cmdCompanionAuto = "ha-paneld/$panel/companion_auto_update/set"
    private val stateCompanionAuto = "ha-paneld/$panel/companion_auto_update/state"
    private val cmdUpdatePaneld = "ha-paneld/$panel/update_paneld/set"
    private val cmdCompanionChannel = "ha-paneld/$panel/companion_update_channel/set"
    private val stateCompanionChannel = "ha-paneld/$panel/companion_update_channel/state"
    private val cmdSelfUpdate = "ha-paneld/$panel/self_update/set"
    private val stateSelfUpdate = "ha-paneld/$panel/self_update/state"
    private val cmdWebViewAuto = "ha-paneld/$panel/webview_auto_update/set"
    private val stateWebViewAuto = "ha-paneld/$panel/webview_auto_update/state"
    private val cmdUpdateChannel = "ha-paneld/$panel/update_channel/set"
    private val stateUpdateChannel = "ha-paneld/$panel/update_channel/state"
    private val cmdSilenceBootChime = "ha-paneld/$panel/silence_boot_chime/set"
    private val stateSilenceBootChime = "ha-paneld/$panel/silence_boot_chime/state"
    private val cmdPreventIdleDim = "ha-paneld/$panel/prevent_idle_dim/set"
    private val statePreventIdleDim = "ha-paneld/$panel/prevent_idle_dim/state"
    private val cmdZigbee = "ha-paneld/$panel/zigbee_router/set"
    private val stateZigbee = "ha-paneld/$panel/zigbee_router/state"
    private val cmdCpuGov = "ha-paneld/$panel/cpu_governor/set"
    private val stateCpuGov = "ha-paneld/$panel/cpu_governor/state"
    private val cmdNetAdb = "ha-paneld/$panel/network_adb/set"
    private val stateNetAdb = "ha-paneld/$panel/network_adb/state"
    private val stateScreen = "ha-paneld/$panel/screen/state"
    // Last brightness reported to HA on stateScreen (-1 = screen reported OFF); heartbeat reconciles
    // the effective hardware backlight against this so firmware dims reach HA between commands.
    @Volatile private var lastScreenBrightness = -1
    // LED hardware has no readback on every backend. Publish desired state only after the current bridge
    // has a confirmed write; a failed command/effect frame remains Unknown instead of fabricating success.
    @Volatile private var ledActuationKnown = false
    // Effective (node-scale) level the last command settled at; -1 = capture on next heartbeat tick.
    @Volatile private var screenEffectiveBaseline = -1
    // Per-channel sync state: previous tick's read (settle detection) + last published volume.
    @Volatile private var prevTickBrightness = -1
    @Volatile private var prevTickVolume = -1
    @Volatile private var lastPublishedVolume = -1
    // Last CPU-tier reported to HA — baselined at announce / on command, so the sync channel only fires
    // when the live sysfs governor is changed by something else (a thermal daemon, another app).
    @Volatile private var lastPublishedGovTier: String? = null
    // Recent "changed outside MQTT" events, surfaced on the info page + /diag for debugging.
    private val syncLog = io.github.maxlyth.hapaneld.mqtt.SyncLog()
    private val stateLed = "ha-paneld/$panel/led/state"
    private val stateNavigate = "ha-paneld/$panel/navigate/state"
    private val stateVolume = "ha-paneld/$panel/volume/state"
    private val eventButton = "ha-paneld/$panel/button/event"
    private val stateIlluminance = "ha-paneld/$panel/illuminance/state"
    private val stateProximity = "ha-paneld/$panel/proximity/state"
    private val stateTemperature = "ha-paneld/$panel/temperature/state"
    private val stateHumidity = "ha-paneld/$panel/humidity/state"
    private val stateZigbeeHealth = "ha-paneld/$panel/zigbee_gateway_health/state"
    private val attrZigbeeHealth = "ha-paneld/$panel/zigbee_gateway_health/attributes"
    private val cmdAutoBright = "ha-paneld/$panel/auto_brightness/set"
    private val stateAutoBright = "ha-paneld/$panel/auto_brightness/state"
    private val cmdBrightnessBias = "ha-paneld/$panel/brightness_bias/set"
    private val stateBrightnessBias = "ha-paneld/$panel/brightness_bias/state"
    private val cmdAmbientLux = "ha-paneld/$panel/ambient_lux/set"
    private val stateAmbientLux = "ha-paneld/$panel/ambient_lux/state"
    private val stateCommandTopics by lazy {
        setOf(
            cmdCpuGov, cmdNetAdb, cmdScreen, cmdLed, cmdNavigate, cmdVolume, cmdHomeDashboard,
            cmdButtons, cmdNavbar, cmdWakeOnWave, cmdTouchSound, cmdWatchdog, cmdKiosk,
            cmdCompanionAuto, cmdCompanionChannel, cmdSelfUpdate, cmdWebViewAuto, cmdUpdateChannel,
            cmdSilenceBootChime, cmdPreventIdleDim, cmdZigbee, cmdAutoBright, cmdBrightnessBias,
            cmdAmbientLux,
        )
    }
    private val actionCommandTopics by lazy {
        setOf(
            cmdReload, cmdReboot, cmdLauncher, cmdHome, cmdAdminLauncher, cmdBack, cmdRecents,
            cmdUpdateCompanion, cmdUpdatePaneld,
        )
    }
    @Volatile private var lastAmbientLux: Int? = null
    @Volatile private var lastIlluminance: Int? = null
    @Volatile private var lastProximity: Boolean? = null
    @Volatile private var lastTemperature: Float? = null
    @Volatile private var lastHumidity: Float? = null

    private val stateConvergerOwner = lazy { createStateConverger() }
    private val stateConverger by stateConvergerOwner
    private val zigbeeActuation = MqttZigbeeActuationCoordinators.forController(zigbee)
    private val zigbeeLease = zigbeeActuation.activate()
    private val zigbeeWorker = ConflatedWorker<Boolean>("zigbee-state", ::reconcileZigbeeDesired)
    private val reannounceDispatcher: MqttReannounceDispatcher = MqttReannounceDispatcher(
        debounceMs = REANNOUNCE_DEBOUNCE_MS,
        perform = ::performReAnnounce,
    )

    private fun createStateConverger(): io.github.maxlyth.hapaneld.mqtt.StateConverger {
        val known = { payload: String -> io.github.maxlyth.hapaneld.mqtt.StateConverger.Observation.Known(payload) }
        val unknown = io.github.maxlyth.hapaneld.mqtt.StateConverger.Observation.Unknown
        val c = io.github.maxlyth.hapaneld.mqtt.StateConverger(
            sender = { topic, payload, retain, done -> publish(topic, payload, retain, done) },
        )
        fun channel(
            key: String,
            topic: String,
            retain: Boolean = true,
            equivalent: (String, String) -> Boolean = String::equals,
            observe: () -> io.github.maxlyth.hapaneld.mqtt.StateConverger.Observation,
        ) = c.register(io.github.maxlyth.hapaneld.mqtt.StateConverger.Channel(key, topic, retain, observe, equivalent))

        channel("screen", stateScreen) {
            when (screen.observedDark()) {
                true -> {
                    lastScreenBrightness = -1
                    screenEffectiveBaseline = -1
                    known("""{"state":"OFF"}""")
                }
                false -> {
                    val level = lastScreenBrightness.takeIf { it >= 0 } ?: brightness.getCommanded().coerceAtLeast(1)
                    // Baseline the first authoritative observation too. Otherwise the first heartbeat
                    // misclassifies an already-lit startup as a local wake and emits a duplicate state.
                    lastScreenBrightness = level
                    known("""{"state":"ON","brightness":$level}""")
                }
                null -> unknown
            }
        }
        channel("led", stateLed) {
            LedCommandPolicy.statePayload(
                LedCommandPolicy.stored(config.lastLed, config.lastLedEffect),
                ledActuationKnown,
                ledEffect.status(),
            )?.let(known) ?: unknown
        }
        channel("navigate", stateNavigate) { known(config.lastNavigate.ifEmpty { "/" }) }
        channel("home_dashboard", stateHomeDashboard) { known(config.homeDashboard) }
        channel("volume", stateVolume) { known(volume.getPercent().toString()) }
        if (hasButtonBacklight) channel("buttons", stateButtons) {
            config.lastButtonBacklight.takeIf { it >= 0 }?.let {
                known(if (it == 0) """{"state":"OFF"}""" else """{"state":"ON","brightness":$it}""")
            } ?: unknown
        }
        channel("wake_on_wave", stateWakeOnWave) { known(if (config.wakeOnWave) "ON" else "OFF") }
        channel("touch_sound", stateTouchSound) { known(if (touchSound.isEnabled()) "ON" else "OFF") }
        channel("watchdog", stateWatchdog) { known(if (config.watchdogEnabled) "ON" else "OFF") }
        channel("kiosk_lock", stateKiosk) { known(if (config.kioskLock) "ON" else "OFF") }
        channel("companion_auto_update", stateCompanionAuto) { known(if (config.companionAutoUpdate) "ON" else "OFF") }
        channel("companion_update_channel", stateCompanionChannel) { known(companionChannelLabel()) }
        channel("self_update", stateSelfUpdate) { known(if (config.selfUpdate) "ON" else "OFF") }
        channel("webview_auto_update", stateWebViewAuto) { known(if (config.webViewAutoUpdate) "ON" else "OFF") }
        channel("update_channel", stateUpdateChannel) { known(updateChannelLabel()) }
        channel("silence_boot_chime", stateSilenceBootChime) { known(if (bootChime.isEnabled()) "ON" else "OFF") }
        channel("prevent_idle_dim", statePreventIdleDim) { known(if (config.preventIdleDim) "ON" else "OFF") }
        channel("auto_brightness", stateAutoBright) { known(if (config.autoBrightness) "ON" else "OFF") }
        channel("brightness_bias", stateBrightnessBias) { known(config.brightnessBias.toString()) }
        channel("ambient_lux", stateAmbientLux) { lastAmbientLux?.let { known(it.toString()) } ?: unknown }
        channel("navbar", stateNavbar) { known(config.navbarMode) }

        channel("illuminance", stateIlluminance, retain = false) { lastIlluminance?.let { known(it.toString()) } ?: unknown }
        channel("proximity", stateProximity) { lastProximity?.let { known(if (it) "ON" else "OFF") } ?: unknown }
        channel("temperature", stateTemperature, retain = false,
            equivalent = io.github.maxlyth.hapaneld.mqtt.StateConverger.numericDeadband(0.1)) {
            lastTemperature?.let { known(String.format(java.util.Locale.US, "%.1f", it)) } ?: unknown
        }
        channel("humidity", stateHumidity, retain = false,
            equivalent = io.github.maxlyth.hapaneld.mqtt.StateConverger.numericDeadband(1.0)) {
            lastHumidity?.let { known(Math.round(it).toString()) } ?: unknown
        }

        val diagDeadband = mapOf(
            "diag_cpu" to 5.0, "diag_memory" to 3.0, "diag_soc_temp" to 0.5,
            "room_temp" to 0.2, "room_humidity" to 1.0,
        )
        val diagKeys = if (hasCht8305) DIAG_KEYS + ROOM_KEYS else DIAG_KEYS
        for (key in diagKeys) channel(
            key,
            SettingsRegistry.spec(key)!!.ha!!.stateTopic(panel),
            equivalent = diagDeadband[key]?.let(io.github.maxlyth.hapaneld.mqtt.StateConverger::numericDeadband)
                ?: String::equals,
        ) {
            if (!config.haExposed(key, false)) unknown else known(diagValue(key) ?: "unknown")
        }
        return c
    }

    /**
     * Add newly confirmed hardware channels without replacing the bridge. The possible shape never
     * shrinks, while each observer checks the latest live snapshot and reports unavailable rather than
     * presenting a transiently unreachable controller as healthy.
     */
    private fun ensureCapabilityChannels(snapshot: Capabilities?): MqttCapabilityObservation =
        synchronized(capabilityChannelLock) {
            val live = snapshot ?: Capabilities(
                zigbeePresent = zigbee.present(),
                cpuGovernors = cpu.available(),
                networkAdb = adb.available(),
                relays = relay.count(),
                buttonLeds = relay.ledCount(),
            )
            val observation = capabilityShape.observe(live)
            val converger = stateConverger
            // capabilityChannelLock serializes this compound keys/register operation; StateConverger
            // independently synchronizes each registry mutation and observation snapshot.
            val registered = converger.keys()
            val unknown = io.github.maxlyth.hapaneld.mqtt.StateConverger.Observation.Unknown
            fun known(payload: String) = io.github.maxlyth.hapaneld.mqtt.StateConverger.Observation.Known(payload)
            fun register(
                key: String,
                topic: String,
                observe: () -> io.github.maxlyth.hapaneld.mqtt.StateConverger.Observation,
            ) {
                if (key !in registered) {
                    converger.register(io.github.maxlyth.hapaneld.mqtt.StateConverger.Channel(key, topic, observe = observe))
                }
            }

            if (observation.possible.networkAdb) register("network_adb", stateNetAdb) {
                if (!capabilityShape.current().live.networkAdb) known("unknown")
                else known(if (adb.isPersisted()) "ON" else "OFF")
            }
            if (observation.possible.zigbee) register("zigbee_router", stateZigbee) {
                if (!capabilityShape.current().live.zigbee) known("unknown")
                else if (config.zigbeeRouterConfigured && !config.zigbeeRouterEnabled) known("OFF")
                else known(if (zigbee.running()) "ON" else "OFF")
            }
            if (observation.possible.cpu) register("cpu_governor", stateCpuGov) {
                if (!capabilityShape.current().live.cpu) known("unknown")
                else cpu.currentTier()?.let(::known) ?: known("unknown")
            }
            for (n in 1..observation.possible.relays) {
                register("relay$n", "ha-paneld/$panel/relay$n/state") {
                    if (capabilityShape.current().live.relays < n) known("unknown")
                    else relay.read(n)?.let { known(if (it) "ON" else "OFF") } ?: known("unknown")
                }
            }
            for (n in 1..observation.possible.buttonLeds) {
                register("button_led$n", "ha-paneld/$panel/button_led$n/state") {
                    if (capabilityShape.current().live.buttonLeds < n) known("unknown")
                    else relay.ledRead(n - 1)?.let { known(if (it) "ON" else "OFF") } ?: known("unknown")
                }
            }
            observation
        }

    @Synchronized
    fun start() {
        if (stopped) return
        val generation = lifecycleGeneration.get()
        val credentials = MqttCredentialsSnapshot(runtimeBroker, runtimeMqttUser, runtimeMqttPassword)
        authRecovery.configure("${credentials.broker}\u0000${credentials.user}\u0000${credentials.password}")
        var broker = credentials.broker.trim()
        if (broker.isEmpty()) {
            // No explicit broker — try to find HA on the LAN (mDNS) and default to its :1883.
            discoverHaIp()?.let {
                broker = "tcp://$it:1883"
                Log.i(TAG, "MQTT broker auto-discovered via mDNS (HA at $it): $broker")
            }
        }
        if (broker.isEmpty()) {
            Log.i(TAG, "no broker configured and none discovered — waiting for network discovery")
            activeBroker = ""
            state = "discovering"
            return
        }
        activeBroker = broker
        state = "connecting"
        try {
            val ep = BrokerEndpoint.endpoint(broker) ?: return
            val (host, port) = ep.host to ep.port
            tlsActive = ep.tls
            // Happy-eyeballs: resolve the host and connect to a chosen address family, so a flaky family
            // (e.g. the PX30 panels' idle-IPv6 stall) is survived by flipping [preferIpv4] on the next
            // reconnect and landing on the family that holds. Falls back to the raw host when it's a
            // literal, resolution fails, or nothing is returned (HiveMQ then resolves it itself).
            val connectHost = runCatching {
                BrokerEndpoint.select(java.net.InetAddress.getAllByName(host).toList(), preferIpv4)
                    ?.let { BrokerEndpoint.hostString(it) }
            }.getOrNull() ?: host

            // The client lifecycle — build, connect, the connected/disconnected listeners, and the
            // superseded-client generation guard — lives in the transport (see HiveMqTransport). This
            // bridge only supplies the connection config + callbacks and keeps the HA semantics.
            if (buttonSubscription == null) {
                buttonSubscription = ButtonBus.subscribe { event -> publishButton(event) }
            }
            if (stopped || lifecycleGeneration.get() != generation) {
                buttonSubscription?.close()
                buttonSubscription = null
                return
            }
            transport.connect(
                MqttConnectConfig(
                    host = connectHost,
                    port = port,
                    tls = ep.tls,
                    clientId = "ha-paneld-$panel",
                    user = credentials.user.ifEmpty { null },
                    password = credentials.password,
                    keepAliveSeconds = KEEPALIVE_SEC,
                    willTopic = availabilityTopic,
                    willPayload = "offline",
                ),
                object : MqttCallbacks {
                    override fun onConnected() = this@MqttBridge.onConnected()
                    override fun onDisconnected(causeMessage: String?): Boolean {
                        if (stopped) return false
                        // Classify so the UI can say "auth rejected" vs "unreachable" rather than "down".
                        val classified = classifyDisconnect(causeMessage)
                        if (classified == "auth-failed") {
                            val now = SystemClock.elapsedRealtime()
                            val retryAt = authRecovery.rejected(now)
                            state = authRecovery.snapshot(now).state
                            scheduleAuthRetry(retryAt)
                        } else state = classified
                        PanelStatus.mqttConnected = false
                        Log.w(TAG, "MQTT disconnected ($state): $causeMessage")
                        // A connection that failed before its first onConnected has no published state to
                        // invalidate. Do not construct the registry (and run capability/root probes) on the
                        // disconnected listener merely to mark an empty outbox.
                        if (stateConvergerOwner.isInitialized()) stateConverger.markAllDirty()
                        return classified != "auth-failed"
                    }
                    override fun onPublishAck() = markOk()
                },
            )
            if (stopped || lifecycleGeneration.get() != generation) {
                transport.disconnectDetached()
                buttonSubscription?.close()
                buttonSubscription = null
                return
            }
            Log.i(TAG, "MQTT connecting to $connectHost:$port (host=$host, prefer=${if (preferIpv4) "IPv4" else "IPv6"}) for $panel")
        } catch (e: Exception) {
            if (stopped || lifecycleGeneration.get() != generation) transport.disconnectDetached()
            Log.w(TAG, "MQTT connect failed", e)
        }
    }

    private fun scheduleAuthRetry(atMs: Long) {
        if (stopped) return
        val generation = ++authRetryGeneration
        val delay = (atMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        runCatching {
            authScheduler.schedule({
                if (generation == authRetryGeneration && isAuthRecoveryState(state)) {
                    Log.i(TAG, "MQTT auth retry — building fresh client with unchanged address family")
                    reconnect(flipFamily = false)
                }
            }, delay, java.util.concurrent.TimeUnit.MILLISECONDS)
        }.onFailure { if (!stopped) Log.w(TAG, "failed to schedule MQTT auth retry", it) }
    }

    /**
     * Force a fresh connection attempt, disposing any existing client first. Called by the service-level
     * reconnect watchdog and the connectivity-regained callback when HiveMQ's built-in auto-reconnect has
     * stalled — e.g. after a transient `NOT_AUTHORIZED` during an HA/broker restart (broker back up before
     * its auth backend is ready), or when the reconnect thread is deferred by Android power management.
     * Unlike [stop] it does NOT publish a retained "offline" — the availability LWT already covered the
     * drop and we're trying to come back, so we must not flap HA to offline on every retry.
     */
    @Synchronized
    fun reconnect(flipFamily: Boolean = false) {
        if (stopped) return
        if (state == "disabled") return // terminal service stop; a blank broker waits in "discovering"
        // A liveness-triggered reconnect flips the address family — if the current family (e.g. IPv6 on
        // the PX panels) won't hold, the next connect tries the other and lands on whatever works.
        if (flipFamily) preferIpv4 = !preferIpv4
        // Detach the old client FIRST and tear it down on a throwaway daemon thread: disconnect() on a
        // WEDGED client (half-open socket, frozen reactor — the very case that triggers a liveness
        // rebuild) can block on an internal client monitor, and that must never delay the replacement
        // connection. In the wedged case the old reactor is frozen anyway, so it won't fight the new
        // client's session; in the healthy case the background disconnect completes normally.
        transport.disconnectDetached()
        start()
    }

    /** Runs on every (re)connect: (re)subscribe to commands and (re)publish discovery + online. */
    private fun onConnected() {
        if (stopped) return
        authRetryGeneration++
        authRecovery.authenticated(SystemClock.elapsedRealtime())
        state = "connected"
        markOk() // arm liveness before capability/root observations can delay the rest of onConnected
        // Capture before command subscriptions can initialize the state-channel registry. Every entity
        // in this announcement sees the same immutable values; later bounded snapshots may add channels
        // when a transiently unavailable live probe recovers.
        val capabilitySnapshot = discoveryCapabilities.snapshot()
        // ACKs belong to one broker connection. Invalidate the previous connection even when a watchdog
        // rebuild detached it before its disconnected callback could run.
        stateConverger.markAllDirty()
        PanelStatus.mqttConnected = true
        synchronized(discoveryAnnouncementLock) {
            transport.subscribe("ha-paneld/$panel/+/set") { topic, payload, retained -> onCommand(topic, payload, retained) }
            // Re-announce discovery when HA (re)starts — its birth message on homeassistant/status. With
            // non-retained discovery this is what rebuilds our entities after an HA restart. The retained
            // online delivered while this connect announcement is still running is suppressed below.
            transport.subscribe("homeassistant/status") { _, payload, _ ->
                if (mqttIsHaOnline(payload)) requestReAnnounce()
            }
            mqttStalePanelCleanup(stalePanelId, panel).forEach {
                publish(it.topic, it.payload, retain = it.retain)
            }
            publishDiscovery(capabilitySnapshot)
            // On a core upgrade or active-profile revision switch, actively clear any entity the previous
            // runtime published but this one no longer does. A profile switch can remove hardware-backed
            // entities without changing Config.VERSION, so both immutable identities belong in the marker.
            // publishDiscovery above populated publishedConfigTopics for the current capability set.
            val discoveryMarker = mqttDiscoveryCleanupMarker(Config.VERSION, profileIdentity)
            if (config.lastDiscoveryVersion != discoveryMarker) {
                pruneStaleDiscovery { acknowledged ->
                    if (acknowledged && !stopped && state == "connected") {
                        config.setLastDiscoveryVersion(discoveryMarker)
                    } else {
                        Log.w(TAG, "discovery prune was not fully acknowledged; retrying after reconnect")
                    }
                }
            }
            publish(availabilityTopic, "online", retain = true)
            restoreAndPublishStates()
            stateConverger.reconcileAll()
            // A retained birth admitted during subscribe is already covered by this later announcement.
            // Invalidate that queued generation while still holding the shared announcement lock.
            reannounceDispatcher.suppressPending()
        }
        reconcileZigbeeOnConnect() // boot-restore: start the gateway if left ON and nothing else has
        // A reconnect storm must not create one blocked privileged thread per callback.
        if (adbReassertInFlight.compareAndSet(false, true)) {
            Thread {
                try {
                    runCatching { adb.reassert() }
                } finally {
                    adbReassertInFlight.set(false)
                }
            }.apply { isDaemon = true; name = "mqtt-adb-reassert"; start() }
        }
        maybeResolveHaLink() // native HA session first, MQTT credential fallback; off-thread, best-effort
        Log.i(TAG, "MQTT connected — (re)subscribed + discovery for $panel")
    }

    /** Re-publish discovery + current states — on HA's `online` birth (non-retained discovery must be
     *  re-sent when HA restarts). No-op if not connected. */
    private fun requestReAnnounce() {
        when (reannounceDispatcher.submit()) {
            ConflatedWorker.Admission.COALESCED ->
                FeatureCosts.registry.recordCoalesced(FeatureCostOperation.MQTT_DISCOVERY_REANNOUNCE)
            ConflatedWorker.Admission.CLOSED ->
                FeatureCosts.registry.recordDropped(FeatureCostOperation.MQTT_DISCOVERY_REANNOUNCE)
            ConflatedWorker.Admission.ACCEPTED -> Unit
        }
        FeatureCosts.registry.setBacklog(
            FeatureCostOperation.MQTT_DISCOVERY_REANNOUNCE,
            reannounceDispatcher.pendingCount(),
        )
    }

    private fun performReAnnounce(generation: Long) {
        synchronized(discoveryAnnouncementLock) {
            FeatureCosts.registry.setBacklog(FeatureCostOperation.MQTT_DISCOVERY_REANNOUNCE, 0)
            val cost = FeatureCosts.registry.span(FeatureCostOperation.MQTT_DISCOVERY_REANNOUNCE)
            try {
                if (stopped || !isConnected() || !reannounceDispatcher.isCurrent(generation)) {
                    cost.outcome(FeatureCostOutcome.CANCELLED)
                    return@synchronized
                }
                // HA frequently emits a retained birth immediately after our connect announcement and may
                // repeat births while its MQTT integration settles. Reuse one monotonic-TTL snapshot across
                // that bounded burst instead of repeating privileged probes for byte-identical discovery.
                publishDiscovery(discoveryCapabilities.snapshot(REANNOUNCE_CAPABILITY_TTL_MS))
                restoreAndPublishStates()
                cost.work(units = publishedConfigTopics.size.toLong())
                Log.i(TAG, "HA online — re-announced discovery for $panel")
            } catch (e: Exception) {
                cost.outcome(FeatureCostOutcome.FAILURE)
                Log.w(TAG, "HA discovery reannouncement failed", e)
            } finally {
                cost.close()
            }
        }
    }

    /** Republish discovery only when the live panel URL changed. Called by Android network callbacks;
     * reconnect and HA-birth paths already call [publishDiscovery] and evaluate the same supplier. */
    internal fun refreshDiscoveryAddress() {
        val current = configUrl()?.takeIf(String::isNotBlank)
        if (!shouldRepublishDiscoveryAddress(isConnected(), lastPublishedConfigUrl, current)) return
        requestReAnnounce()
    }

    /** Re-project live Config into discovery/state without replacing a healthy broker connection. */
    internal fun refreshConfiguration(reannounce: Boolean, resolveHaLink: Boolean) {
        if (reannounce) requestReAnnounce()
        if (resolveHaLink) maybeResolveHaLink()
    }

    /**
     * Resolve this panel's HA device-settings URL. A configured built-in renderer is authoritative: use
     * its exact endpoint and current access token, even when MQTT is disabled or its broker credentials
     * belong to another HA instance. MQTT username/password remain a compatibility fallback only when no
     * native renderer connection exists. Legacy cache entries have no [Config.haDeviceLinkTarget], so an
     * upgrade immediately repairs them rather than trusting their old random registry id for another 6h.
     */
    internal fun maybeResolveHaLink() {
        if (!haLinkResolutionInFlight.compareAndSet(false, true)) return
        Thread {
            try {
                val nativeBase = config.haUrl.trim().trimEnd('/')
                if (nativeBase.isNotBlank()) {
                    val target = HaLink.resolutionTarget(nativeBase, panel)
                    if (config.haDeviceLinkIsFresh(target, System.currentTimeMillis(), HA_LINK_TTL_MS)) return@Thread
                    val stillCurrent = {
                        config.haUrl.trim().trimEnd('/') == nativeBase && config.panelId == panel
                    }
                    val token = DashboardAuth.forConfig(config, stillCurrent = stillCurrent)
                        .session?.accessToken ?: return@Thread
                    val link = HaLink.resolveWithAccessToken(
                        nativeBase, token, listOf(panel, runtimeFriendlyName),
                    ) ?: return@Thread
                    if (stillCurrent()) config.setHaDeviceUrl(link, target)
                    return@Thread
                }

                val credentials = config.mqttCredentialsSnapshot()
                if (credentials.user.isBlank() || credentials.password.isBlank()) return@Thread
                // Prefer mDNS (broker-matched). Else derive HA from the broker HOST: a working broker is
                // likely the HA server too. HaLink reads /api/config for its canonical browser link.
                val base = discoverHaUrl(credentials.broker) ?: brokerHttpsUrl(credentials.broker) ?: return@Thread
                val target = HaLink.resolutionTarget(base, panel)
                if (config.haDeviceLinkIsFresh(target, System.currentTimeMillis(), HA_LINK_TTL_MS)) return@Thread
                val link = HaLink.resolve(
                    base, credentials.user, credentials.password, listOf(panel, runtimeFriendlyName),
                ) ?: return@Thread
                if (config.haUrl.isBlank() && config.panelId == panel &&
                    config.mqttCredentialsSnapshot() == credentials
                ) config.setHaDeviceUrl(link, target)
            } finally {
                haLinkResolutionInFlight.set(false)
            }
        }.apply { isDaemon = true; name = "ha-device-link" }.start()
    }

    /** `https://<broker-host>` when the broker is a hostname (wildcard/SAN certs make HTTPS verifiable);
     *  null for a raw IP broker (cert would mismatch) or no broker. */
    private fun brokerHttpsUrl(broker: String): String? {
        val host = broker.substringAfter("://").substringBefore(":").substringBefore("/").trim()
        if (host.isBlank() || host.contains(":") || host.matches(Regex("[0-9.]+"))) return null
        return "https://$host"
    }

    /**
     * Sync HA to the panel's actual state on (re)connect, so the UI isn't stale after a reboot
     * (hardware resets, but HA holds the last retained state). Re-applies the last LED colour and
     * publishes the current screen/volume/navigate states.
     */
    private fun restoreAndPublishStates() {
        volume.getPercent().let { lastPublishedVolume = it }
        // Reconnect is just another reconciliation trigger; the registry observes physical state.
        stateConverger.reconcile("screen", force = true)
        // Navigate: last pushed path, else default to the dashboard root "/" so the entity shows a
        // sensible local path instead of "unknown" before anything has been navigated.
        // LED: re-apply the last colour to the hardware (reset on reboot) and publish it.
        val desiredLed = LedCommandPolicy.stored(config.lastLed, config.lastLedEffect)
        ledActuationKnown = false
        ledActuationKnown = if (desiredLed.on && desiredLed.effect != null) {
            ledEffect.start(desiredLed.effect, desiredLed.red, desiredLed.green, desiredLed.blue, desiredLed.brightness)
        } else if (desiredLed.on) {
            ledEffect.setSolid(
                desiredLed.red * desiredLed.brightness / 255,
                desiredLed.green * desiredLed.brightness / 255,
                desiredLed.blue * desiredLed.brightness / 255,
            )
        } else {
            // Force the hardware off too — the LED can power up to a default on reboot, so publishing
            // OFF without driving it leaves HA and the physical LED disagreeing (seen on rk3576).
            ledEffect.setOff()
        }
        if (hasButtonBacklight) {
            config.lastButtonBacklight.takeIf { it >= 0 }?.let { HelperClient.send("BTN $it") }
        }
    }

    // ---- command dispatch ----

    private fun onCommand(topic: String, payloadBytes: ByteArray, retained: Boolean) {
        if (!mqttAcceptsCommand(stopped, retained)) {
            if (retained && !stopped) {
                FeatureCosts.registry.recordDropped(FeatureCostOperation.MQTT_COMMAND_DISPATCH)
                Log.w(TAG, "ignoring RETAINED command on $topic (${payloadBytes.size} bytes)")
            }
            return
        }
        val kind = commandKind(topic)
        if (kind == null || payloadBytes.size > MAX_COMMAND_PAYLOAD_BYTES) {
            FeatureCosts.registry.recordDropped(FeatureCostOperation.MQTT_COMMAND_DISPATCH)
            return
        }

        // HiveMQ reuses callback-owned values. Copy only after the cheap retained/topic/size gates, then
        // return immediately so a root call or slow controller can never pin its network callback.
        val payload = payloadBytes.copyOf()
        val command = { consumeCommand(topic, payload) }
        val admission = when (kind) {
            CommandKind.LATEST -> commandDispatcher.submitLatest(topic, command)
            CommandKind.ACTION -> commandDispatcher.submitAction(command)
        }
        recordCommandAdmission(admission)
    }

    private fun commandKind(topic: String): CommandKind? = when {
        topic in stateCommandTopics -> CommandKind.LATEST
        topic in actionCommandTopics -> CommandKind.ACTION
        indexedCommandTopic(topic, "ha-paneld/$panel/relay") -> CommandKind.LATEST
        indexedCommandTopic(topic, "ha-paneld/$panel/button_led") -> CommandKind.LATEST
        else -> null
    }

    private fun indexedCommandTopic(topic: String, prefix: String): Boolean {
        if (!topic.startsWith(prefix) || !topic.endsWith("/set")) return false
        return topic.substring(prefix.length, topic.length - 4).toIntOrNull() in 1..MAX_DYNAMIC_COMMAND_INDEX
    }

    private fun recordCommandAdmission(admission: MqttCommandDispatcher.Admission) {
        when (admission) {
            MqttCommandDispatcher.Admission.ACCEPTED -> Unit
            MqttCommandDispatcher.Admission.COALESCED ->
                FeatureCosts.registry.recordCoalesced(FeatureCostOperation.MQTT_COMMAND_DISPATCH)
            MqttCommandDispatcher.Admission.REJECTED,
            MqttCommandDispatcher.Admission.CLOSED ->
                FeatureCosts.registry.recordDropped(FeatureCostOperation.MQTT_COMMAND_DISPATCH)
        }
        FeatureCosts.registry.setBacklog(
            FeatureCostOperation.MQTT_COMMAND_DISPATCH,
            commandDispatcher.pendingCount(),
        )
    }

    private fun consumeCommand(topic: String, payloadBytes: ByteArray) {
        FeatureCosts.registry.setBacklog(
            FeatureCostOperation.MQTT_COMMAND_DISPATCH,
            commandDispatcher.pendingCount(),
        )
        val cost = FeatureCosts.registry.span(FeatureCostOperation.MQTT_COMMAND_DISPATCH)
            .work(units = 1, bytes = payloadBytes.size.toLong())
        try {
            dispatchCommand(topic, payloadBytes)
        } catch (e: Exception) {
            cost.outcome(FeatureCostOutcome.FAILURE)
            Log.w(TAG, "command failed on $topic (${payloadBytes.size} bytes)", e)
        } finally {
            cost.close()
        }
    }

    private fun dispatchCommand(topic: String, payloadBytes: ByteArray) {
        val payload = String(payloadBytes, Charsets.UTF_8)
        // NEVER act on a RETAINED command. Command topics are fire-and-forget; a retained payload is
        // always stale — e.g. a broker- or automation-retained screen-off replayed on every (re)subscribe,
        // which is exactly what stranded a panel dark after a reconnect. Our own state/discovery stays
        // retained; inbound commands must be fresh.
        // Relay + button-LED topics are dynamic (relay1/…, button_led1/…) — match before the fixed set.
        if (topic.startsWith("ha-paneld/$panel/relay") && topic.endsWith("/set")) {
            handleRelay(topic, payload); return
        }
        if (topic.startsWith("ha-paneld/$panel/button_led") && topic.endsWith("/set")) {
            handleButtonLed(topic, payload); return
        }
        when (topic) {
            cmdCpuGov -> handleCpuGov(payload)
            cmdNetAdb -> handleNetAdb(payload)
            cmdScreen -> handleScreen(payload)
            cmdLed -> handleLed(payload)
            cmdNavigate -> handleNavigate(payload)
            cmdVolume -> handleVolume(payload)
            cmdReload -> handleReload()
            cmdHomeDashboard -> handleHomeDashboard(payload)
            cmdReboot -> system.reboot()
            cmdLauncher -> system.launchLauncher(config.launcherPackage)
            cmdHome -> {
                // Built-in renderer: `home` means "show the home dashboard", not just foreground —
                // launchHome alone only brings the activity forward now (kiosk snap-backs must not
                // reload), so stage the home path as a navigate target for onNewIntent to act on.
                if (config.dashboardPackage == SystemController.BUILTIN_DASHBOARD && config.homeDashboard.isNotBlank()) {
                    BuiltinDashboard.navPath = config.homeDashboard
                }
                system.launchHome(config.dashboardPackage)
            }
            cmdAdminLauncher -> system.launchAdminLauncher()
            cmdButtons -> if (hasButtonBacklight) handleButtons(payload)
            cmdBack -> NavActions.back(appCanSu)
            cmdRecents -> NavActions.recents(appCanSu)
            cmdNavbar -> handleNavbar(payload)
            cmdWakeOnWave -> handleWakeOnWave(payload)
            cmdTouchSound -> handleTouchSound(payload)
            cmdWatchdog -> handleWatchdog(payload)
            cmdKiosk -> handleKiosk(payload)
            cmdUpdateCompanion -> onUpdateCompanion()
            cmdCompanionAuto -> handleCompanionAuto(payload)
            cmdUpdatePaneld -> onSelfUpdate(true)
            cmdCompanionChannel -> handleCompanionChannel(payload)
            cmdSelfUpdate -> handleSelfUpdate(payload)
            cmdWebViewAuto -> handleWebViewAuto(payload)
            cmdUpdateChannel -> handleUpdateChannel(payload)
            cmdSilenceBootChime -> handleSilenceBootChime(payload)
            cmdPreventIdleDim -> handlePreventIdleDim(payload)
            cmdZigbee -> handleZigbee(payload)
            cmdAutoBright -> handleAutoBright(payload)
            cmdBrightnessBias -> handleBrightnessBias(payload)
            cmdAmbientLux -> handleAmbientLux(payload)
            else -> Log.d(TAG, "unhandled command topic $topic")
        }
    }

    /** Publish screen=ON to HA after a LOCAL wake (e.g. wake-on-wave), so `light.<panel>_screen` tracks
     *  reality instead of staying OFF. No-op if the broker isn't connected yet. */
    fun publishScreenOn() {
        io.github.maxlyth.hapaneld.mqtt.StateConverger.dispatch {
            if (!stopped) publishScreenBrightness(brightness.getCommanded().coerceAtLeast(1))
        }
    }

    /** Publish the current volume to HA after a LOCAL change (e.g. navbar Volume ±), so
     *  `number.<panel>_volume` tracks reality. No-op if the broker isn't connected yet. */
    fun publishVolume() {
        io.github.maxlyth.hapaneld.mqtt.StateConverger.dispatch {
            if (!stopped) stateConverger.reconcile("volume", force = true)
        }
    }

    private fun handleScreen(payload: String) {
        val json = JSONObject(payload)
        val on = json.optString("state", "ON").equals("ON", ignoreCase = true)
        if (!on) {
            screen.sleep()
            publishScreenOff()
            return
        }
        screen.wake() // power the backlight on (daemon bl_power) or restore brightness (fallback)
        val level = if (json.has("brightness")) {
            json.getInt("brightness").also { brightness.setBrightness(it) }
        } else {
            brightness.getCommanded().coerceAtLeast(1)
        }
        screen.noteLevel(level)
        publishScreenBrightness(level)
    }

    private fun handleLed(payload: String) {
        val desired = LedCommandPolicy.command(
            payload,
            LedCommandPolicy.stored(config.lastLed, config.lastLedEffect),
        )
        // Persist user intent even when hardware is temporarily unreachable so reconnect can retry it,
        // but clear observation authority before exposing the new desired state.
        ledActuationKnown = false
        config.lastLed = desired.storedColor()
        config.lastLedEffect = desired.storedEffect()
        ledActuationKnown = if (!desired.on) {
            ledEffect.setOff()
        } else if (desired.effect != null) {
            ledEffect.start(desired.effect, desired.red, desired.green, desired.blue, desired.brightness)
        } else {
            ledEffect.setSolid(
                desired.red * desired.brightness / 255,
                desired.green * desired.brightness / 255,
                desired.blue * desired.brightness / 255,
            )
        }
        stateConverger.reconcile("led", force = true)
    }

    private fun handleWakeOnWave(payload: String) {
        val on = payload.trim().equals("ON", ignoreCase = true)
        config.setWakeOnWave(on)
        stateConverger.reconcile("wake_on_wave", force = true)
    }

    private fun handlePreventIdleDim(payload: String) {
        val on = payload.trim().equals("ON", ignoreCase = true)
        config.setPreventIdleDim(on)
        brightness.applyPreventIdleDim(on, config)
        stateConverger.reconcile("prevent_idle_dim", force = true)
    }

    private fun handleTouchSound(payload: String) {
        val on = payload.trim().equals("ON", ignoreCase = true)
        check(touchSound.set(on)) { "touch sound transition failed" }
        stateConverger.reconcile("touch_sound", force = true)
    }

    private fun handleWatchdog(payload: String) {
        val on = payload.trim().equals("ON", ignoreCase = true)
        config.setWatchdogEnabled(on)
        watchdog.apply(on)
        stateConverger.reconcile("watchdog", force = true)
    }

    private fun handleKiosk(payload: String) {
        val on = payload.trim().equals("ON", ignoreCase = true)
        config.setKioskLock(on)
        kiosk.apply(on)
        stateConverger.reconcile("kiosk_lock", force = true)
    }

    /** Publish the kiosk-lock state — used by the on-device unlock gesture, which turns it OFF outside the
     *  MQTT/HTTP command path and must still tell HA. */
    fun publishKioskState() {
        io.github.maxlyth.hapaneld.mqtt.StateConverger.dispatch {
            if (!stopped) stateConverger.reconcile("kiosk_lock", force = true)
        }
    }

    private fun handleCompanionAuto(payload: String) {
        val on = payload.trim().equals("ON", ignoreCase = true)
        config.setCompanionAutoUpdate(on)
        stateConverger.reconcile("companion_auto_update", force = true)
    }

    private fun handleSelfUpdate(payload: String) {
        val on = payload.trim().equals("ON", ignoreCase = true)
        config.setSelfUpdate(on)
        stateConverger.reconcile("self_update", force = true)
    }

    private fun handleWebViewAuto(payload: String) {
        val on = payload.trim().equals("ON", ignoreCase = true)
        config.setWebViewAutoUpdate(on)
        stateConverger.reconcile("webview_auto_update", force = true)
    }

    private fun handleUpdateChannel(payload: String, previousValue: String? = null) {
        val was = previousValue ?: config.updateChannel
        config.setUpdateChannel(payload.trim().trim('"'))
        val now = config.updateChannel
        stateConverger.reconcile("update_channel", force = true)
        // Apply the new channel now (when self-update is on). Switching pre-release → stable FORCES the
        // move onto stable even if that's a downgrade off the current rc (the deliberate exception to the
        // no-auto-downgrade rule); any other switch just takes the new channel's newest if it's newer.
        if (config.selfUpdate && now != was) onSelfUpdate(was == "prerelease" && now == "stable")
    }

    private fun handleCompanionChannel(payload: String, previousValue: String? = null) {
        val was = previousValue ?: config.companionUpdateChannel
        config.setCompanionUpdateChannel(payload.trim().trim('"'))
        val now = config.companionUpdateChannel
        stateConverger.reconcile("companion_update_channel", force = true)
        // Apply the new channel now when auto-update is on (a forced check via the existing callback).
        if (config.companionAutoUpdate && now != was) onUpdateCompanion()
    }

    // HA select uses the capitalised labels; Config stores "stable"/"prerelease".
    private fun updateChannelLabel(): String = if (config.updateChannel == "prerelease") "Pre-release" else "Stable"
    private fun companionChannelLabel(): String = if (config.companionUpdateChannel == "prerelease") "Pre-release" else "Stable"

    private fun handleSilenceBootChime(payload: String) {
        val on = payload.trim().equals("ON", ignoreCase = true)
        check(bootChime.set(on)) { "boot chime transition failed" }
        stateConverger.reconcile("silence_boot_chime", force = true)
    }

    private fun handleAutoBright(payload: String) {
        val on = payload.trim().equals("ON", ignoreCase = true)
        config.setAutoBrightness(on)
        stateConverger.reconcile("auto_brightness", force = true)
    }

    private fun handleBrightnessBias(payload: String) {
        val v = payload.trim().trim('"').toDoubleOrNull()?.roundToInt() ?: return
        config.setBrightnessBias(v)
        stateConverger.reconcile("brightness_bias", force = true)
    }

    // HA writes room lux here (the only auto-brightness source on sensor-less panels); feed engine + echo.
    private fun handleAmbientLux(payload: String) {
        val lux = payload.trim().trim('"').toFloatOrNull() ?: return
        autoBright.submitLux(lux)
        lastAmbientLux = lux.roundToInt()
        stateConverger.reconcile("ambient_lux", force = true)
    }

    // Button backlight (e.g. TPA10): a brightness-only light, driven via the root daemon's BTN command
    // (same daemon that owns the sysfs LED). Daemon calls are short blocking I/O — fine on this thread.
    private fun handleButtons(payload: String) {
        val json = JSONObject(payload)
        val on = json.optString("state", "ON").equals("ON", ignoreCase = true)
        val level = if (!on) 0 else if (json.has("brightness")) json.getInt("brightness") else 255
        if (HelperClient.send("BTN $level") == "OK") {
            config.lastButtonBacklight = level
            stateConverger.reconcile("buttons", force = true)
        }
    }

    // Zigbee router toggle (Sonoff NSPanel Pro). ON starts the guard supervisor (→ mosquitto +
    // zgateway, ensures Repeater role); OFF stops the guard + zstack, freeing the radio.
    //
    // The vendor lifecycle is slow — OFF blocks ~8s in the stop script, and ON's gateway spawns on the
    // guard's ~30s timer (tens of seconds before it answers) — so it must NOT run on the MQTT callback
    // thread. We publish the commanded state optimistically, then reconcile to the real running state
    // on a background thread (polling for the slow ON) so HA ends up correct without stalling MQTT.
    private fun handleZigbee(payload: String) {
        val on = payload.trim().equals("ON", ignoreCase = true)
        if (on) onZigbeeExplicitRetry()
        config.setZigbeeRouterEnabled(on) // persist desired state so it survives a reboot (boot-restore)
        admitZigbee(on)
    }

    fun publishZigbeeRouterState() {
        if (stateConvergerOwner.isInitialized()) stateConverger.reconcile("zigbee_router", force = true)
    }

    fun publishZigbeeHealth(snapshot: ZigbeeHealthSnapshot = zigbeeHealth()) {
        if (stopped || state != "connected") return
        publish(stateZigbeeHealth, snapshot.state.wireValue, retain = true)
        publish(attrZigbeeHealth, snapshot.mqttAttributes(), retain = true)
    }

    // Boot/connect RECONCILE for the Zigbee router. Vendor firmware boot-starts the NSPanel Pro gateway
    // independently of us (and on 120P/3.7.1 the vendor guard CPU-spins), so we drive it to the user's
    // explicit choice on every connect: start it if they left it ON and nothing has; STOP it if they
    // turned it OFF (otherwise the vendor-started gateway returns each reboot). Gated on the switch having
    // been CONFIGURED — we never disable a stock vendor gateway by our default. Slow lifecycle off-thread.
    private fun reconcileZigbeeOnConnect() {
        if (!capabilityShape.current().live.zigbee || !config.zigbeeRouterConfigured) return
        val want = config.zigbeeRouterEnabled
        if (want == zigbee.running()) return // already in the desired state
        admitZigbee(want)
    }

    private fun admitZigbee(desired: Boolean) {
        when (zigbeeWorker.submit(desired)) {
            ConflatedWorker.Admission.COALESCED ->
                FeatureCosts.registry.recordCoalesced(FeatureCostOperation.ZIGBEE_RECONCILE)
            ConflatedWorker.Admission.CLOSED ->
                FeatureCosts.registry.recordDropped(FeatureCostOperation.ZIGBEE_RECONCILE)
            ConflatedWorker.Admission.ACCEPTED -> Unit
        }
        FeatureCosts.registry.setBacklog(FeatureCostOperation.ZIGBEE_RECONCILE, zigbeeWorker.pendingCount())
    }

    private fun reconcileZigbeeDesired(desired: Boolean) {
        FeatureCosts.registry.setBacklog(FeatureCostOperation.ZIGBEE_RECONCILE, 0)
        val cost = FeatureCosts.registry.span(FeatureCostOperation.ZIGBEE_RECONCILE).work(units = 1)
        try {
            val execution = zigbeeActuation.executeIfCurrent(zigbeeLease) {
                val reconciled = zigbee.reconcile(desired)
                if (desired) for (i in 0 until 18) {
                    // A newer bridge generation or command is already authoritative. Stop waiting and
                    // release the shared actuator so its latest desired state can run after this one.
                    if (stopped || !zigbeeActuation.isCurrent(zigbeeLease) ||
                        config.zigbeeRouterEnabled != desired || zigbee.running()
                    ) break
                    Thread.sleep(5_000)
                }
                reconciled to zigbee.running()
            }
            if (!execution.executed || !execution.currentAfter) {
                cost.outcome(FeatureCostOutcome.CANCELLED)
                return
            }
            val (reconciled, running) = requireNotNull(execution.value)
            if (!reconciled) cost.outcome(FeatureCostOutcome.FAILURE)
            if (!stopped) stateConverger.reconcile("zigbee_router", force = true)
            Log.i(TAG, "zigbee reconcile -> ${if (desired) "on" else "off"}; running=$running")
        } catch (e: InterruptedException) {
            cost.outcome(FeatureCostOutcome.CANCELLED)
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            cost.outcome(FeatureCostOutcome.FAILURE)
            Log.w(TAG, "zigbee reconcile failed", e)
        } finally {
            cost.close()
        }
    }

    // On-board relay (Smatek S9E). topic = ha-paneld/<panel>/relay<N>/set; payload ON/OFF.
    private fun handleRelay(topic: String, payload: String) {
        val n = topic.substringAfter("/relay").substringBefore("/set").toIntOrNull() ?: return
        val on = payload.trim().let { it.equals("ON", ignoreCase = true) || it == "1" }
        relay.set(n, on)
        stateConverger.reconcile("relay$n", force = true)
    }

    // S9E button LED. topic = ha-paneld/<panel>/button_led<N>/set (N 1-based); payload ON/OFF.
    private fun handleButtonLed(topic: String, payload: String) {
        val n = topic.substringAfter("/button_led").substringBefore("/set").toIntOrNull() ?: return
        val on = payload.trim().let { it.equals("ON", ignoreCase = true) || it == "1" }
        relay.ledSet(n - 1, on)
        stateConverger.reconcile("button_led$n", force = true)
    }

    // CPU scaling governor (select). Quick su write; publishes the read-back governor.
    private fun handleCpuGov(payload: String) {
        val tier = payload.trim().trim('"')   // "Performance" | "Efficiency" | "Auto"
        check(cpu.setTier(tier)) { "CPU governor transition failed" }
        stateConverger.reconcile("cpu_governor", force = true)
    }

    // Persistent network adb (switch). Restarts adbd to apply; that only affects adb, not MQTT.
    private fun handleNetAdb(payload: String) {
        val on = payload.trim().equals("ON", ignoreCase = true)
        check(adb.set(on)) { "network adb transition failed" }
        stateConverger.reconcile("network_adb", force = true)
    }

    // Soft navbar mode (select). Persist and publish only after the asynchronous platform actuation
    // acknowledges both privileged display state and the main-thread overlay mutation.
    private fun handleNavbar(payload: String) {
        val previous = config.navbarMode
        applyAcknowledgedNavbarMode(
            payload = payload,
            previousMode = previous,
            actuate = navbar::applyAsync,
            rollback = navbar::rollbackAsync,
            persist = { applied ->
                config.commitRaw(requireNotNull(SettingsRegistry.spec("navbar_mode")), applied)
            },
            reconcile = { stateConverger.reconcile("navbar", force = true) },
        )
    }

    private fun handleHomeDashboard(payload: String, previousValue: String? = null) {
        val path = toLocalPath(payload) // normalise to a leading-slash local path (or "" to clear)
        val target = if (path == "/") "" else path
        val changed = target != (previousValue ?: config.homeDashboard)
        config.setHomeDashboard(target)
        if (changed) onDashboardTargetChanged()
        stateConverger.reconcile("home_dashboard", force = true)
    }

    // Reload: keep the hard restart (the right recovery for a wedged WebView), but if a per-panel home
    // dashboard is set, deep-link back to it once the frontend has cold-started — so reload lands on THIS
    // panel's dashboard, not the Companion's user-default. The delayed nav runs off the MQTT thread.
    private fun handleReload() {
        // Built-in renderer: reload returns to the configured home dashboard (clear any navigate path),
        // and the WebView reloads its own view — no Companion deep-link re-navigation is needed.
        if (config.dashboardPackage == SystemController.BUILTIN_DASHBOARD) {
            BuiltinDashboard.navPath = null
            system.reloadDashboard(config.dashboardPackage)
            return
        }
        system.reloadDashboard(config.dashboardPackage)
        val home = toLocalPath(config.homeDashboard)
        if (config.homeDashboard.isNotBlank() && home.isNotEmpty() && home != "/") {
            reloadNavigationFuture?.cancel(false)
            reloadNavigationFuture = try {
                authScheduler.schedule({
                    recordCommandAdmission(commandDispatcher.submitAction {
                        navigate.navigate("homeassistant://navigate$home")
                        config.lastNavigate = home
                        stateConverger.reconcile("navigate", force = true)
                        Log.i(TAG, "reload -> re-navigated to intended dashboard $home")
                    })
                }, RELOAD_NAV_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: RejectedExecutionException) {
                null
            }
        }
    }

    private fun handleNavigate(payload: String) {
        // Local navigation only: strip any scheme + host so an external URL can't be pushed (the HA
        // Companion opens a disorienting in-app WebView for those). We keep just the path.
        val path = toLocalPath(payload)
        if (path.isEmpty()) return
        if (config.dashboardPackage == SystemController.BUILTIN_DASHBOARD) {
            // Built-in renderer: set the target path and (re)launch DashboardActivity, which loads it —
            // the deep link targets the Companion and would no-op on a builtin-only panel.
            BuiltinDashboard.navPath = path
            system.launchHome(config.dashboardPackage)
            config.lastNavigate = path
            stateConverger.reconcile("navigate", force = true)
            return
        }
        if (path == config.lastNavigate) {
            // Already on this path — the deeplink is a no-op, so reload the dashboard instead.
            system.reloadDashboard(config.dashboardPackage)
        } else {
            navigate.navigate("homeassistant://navigate$path")
            config.lastNavigate = path
            stateConverger.reconcile("navigate", force = true)
        }
    }

    /** Reduce any posted value to a leading-slash local path: drop `scheme://` and the `host:port`
     *  authority, keep the path (+ query/fragment). `http://ha.local:8123/lovelace/0` → `/lovelace/0`;
     *  `lovelace/0` → `/lovelace/0`; `/lovelace/0` unchanged. */
    private fun toLocalPath(raw: String): String {
        var s = raw.trim().trim('"')
        if (s.isEmpty()) return ""
        val scheme = s.indexOf("://")
        if (scheme >= 0) {
            s = s.substring(scheme + 3)            // strip scheme://
            val slash = s.indexOf('/')             // drop the host[:port] authority
            s = if (slash >= 0) s.substring(slash) else "/"
        }
        if (!s.startsWith("/")) s = "/$s"
        return s
    }

    private fun handleVolume(payload: String) {
        // number entity sends a plain numeric string (0..100).
        val pct = payload.trim().trim('"').toDoubleOrNull()?.toInt() ?: return
        volume.setPercent(pct)
        volume.getPercent().let { lastPublishedVolume = it }
        stateConverger.reconcile("volume", force = true)
    }

    private fun publishButton(event: String) {
        publish(eventButton, """{"event_type":"$event"}""")
    }

    // Sensor readings are NOT retained: a fresh sample arrives shortly, so retaining only adds broker
    // clutter + a brief stale value on reconnect. (Occupancy/proximity IS retained — it has no periodic
    // sample between transitions, so the last state must survive a reconnect. Stateful controls too.)
    fun publishLight(lux: Int) {
        lastIlluminance = lux
        stateConverger.reconcile("illuminance")
    }

    fun publishProximity(near: Boolean) {
        lastProximity = near
        stateConverger.reconcile("proximity", force = true)
    }

    // Rounded at publish (1dp temp, integer humidity) so precision wobble can't create recorder rows.
    fun publishTemperature(celsius: Float) {
        lastTemperature = celsius
        stateConverger.reconcile("temperature")
    }

    fun publishHumidity(percent: Float) {
        lastHumidity = percent
        stateConverger.reconcile("humidity")
    }

    /**
     * Apply a setting from a NON-MQTT source (the HTTP `/api/v1/config` API) through the SAME
     * side-effect + state-publish path an MQTT command takes, so a value set over HTTP behaves
     * identically to one set from Home Assistant (persist → drive hardware → publish retained state).
     * [value] is the registry-normalized string ("true"/"false", a number, or an enum label).
     * Reports whether the setting applied, was not admitted because this bridge is draining, or failed
     * after admission. Only the admission failure is safe for the service to acknowledge as deferred.
     */
    internal fun applySetting(key: String, value: String, previousValue: String? = null): LiveSettingApplyResult {
        if (key !in APPLY_SETTING_KEYS) return LiveSettingApplyResult.FAILED
        if (stopped) return LiveSettingApplyResult.DEFERRED
        var started = false
        val result = commandDispatcher.runLatestResult(
            key = "http:$key",
            onAdmission = ::recordCommandAdmission,
        ) {
            started = true
            val cost = FeatureCosts.registry.span(FeatureCostOperation.MQTT_COMMAND_DISPATCH)
                .work(units = 1, bytes = value.toByteArray().size.toLong())
            try {
                dispatchSetting(key, value, previousValue)
            } catch (e: Exception) {
                cost.outcome(FeatureCostOutcome.FAILURE)
                throw e
            } finally {
                cost.close()
            }
        }
        if (!result.executed && !started &&
            (result.admission == MqttCommandDispatcher.Admission.REJECTED ||
                result.admission == MqttCommandDispatcher.Admission.CLOSED)
        ) {
            FeatureCosts.registry.span(FeatureCostOperation.MQTT_COMMAND_DISPATCH)
                .outcome(FeatureCostOutcome.REJECTED)
                .work(bytes = value.toByteArray().size.toLong())
                .close()
        }
        FeatureCosts.registry.setBacklog(
            FeatureCostOperation.MQTT_COMMAND_DISPATCH,
            commandDispatcher.pendingCount(),
        )
        return liveSettingApplyResult(result)
    }

    private fun dispatchSetting(key: String, value: String, previousValue: String? = null) {
        val onOff = if (SettingValue.parseBool(value) == true) "ON" else "OFF"
        when (key) {
            "wake_on_wave" -> handleWakeOnWave(onOff)
            "prevent_idle_dim" -> handlePreventIdleDim(onOff)
            "watchdog_enabled" -> handleWatchdog(onOff)
            "kiosk_lock" -> handleKiosk(onOff)
            "silence_boot_chime" -> handleSilenceBootChime(onOff)
            "auto_brightness" -> handleAutoBright(onOff)
            "touch_sound" -> handleTouchSound(onOff)
            "network_adb" -> handleNetAdb(onOff)
            "zigbee_router" -> handleZigbee(onOff)
            "brightness_bias" -> handleBrightnessBias(value)
            "ambient_lux" -> handleAmbientLux(value)
            "cpu_governor" -> handleCpuGov(value)
            "navbar_mode" -> handleNavbar(value)
            "companion_auto_update" -> handleCompanionAuto(onOff)
            "companion_update_channel" -> handleCompanionChannel(value, previousValue)
            "self_update" -> handleSelfUpdate(onOff)
            "webview_auto_update" -> handleWebViewAuto(onOff)
            "update_channel" -> handleUpdateChannel(value, previousValue)
            "home_dashboard" -> handleHomeDashboard(value, previousValue)
            else -> error("live setting declared without a dispatcher: $key")
        }
    }

    // ---- discovery ----

    /**
     * Publish — or, when the user has hidden it via the per-panel "expose to HA" toggle, CLEAR — one
     * discovery entity. Hiding publishes an empty payload to the config topic, which removes the
     * entity from HA entirely (zero recorder / state-machine cost); with the 0.8.5 un-retained
     * discovery model nothing on the broker can resurrect it, and [reAnnounce] re-evaluates this gate
     * on every HA birth. [publishState] runs only when the entity is exposed. This is what makes the
     * HA footprint configurable per panel.
     */
    private fun exposable(
        key: String,
        component: String,
        objectId: String,
        payload: () -> String,
        capabilitySnapshot: Capabilities?,
        availableOverride: Boolean? = null,
        publishState: () -> Unit,
    ) {
        // Honour the spec's per-setting default so a setting declared haExposedByDefault=false is
        // local-only (HTTP UI) until the user opts in via the expose pip — matching what the Configure
        // UI/schema shows. Falls back to true for keys with no registry spec (e.g. relay/button_led).
        // An UNAVAILABLE setting (availableWhen false — e.g. Companion auto-update with no Companion
        // installed) publishes the empty config, removing any stale HA entity, regardless of the pip.
        val spec = SettingsRegistry.spec(key)
        val default = spec?.haExposedByDefault ?: true
        val available = availableOverride
            ?: capabilitySnapshot?.let { spec?.availableWhen?.invoke(it) }
            ?: true
        if (available && config.haExposed(key, default)) {
            publishConfig(component, objectId, payload())
            publishState()
        } else {
            publishConfig(component, objectId, "")
        }
    }

    private fun publishDiscovery(capabilitySnapshot: Capabilities?) {
        val channelShape = ensureCapabilityChannels(capabilitySnapshot).possible
        fun exposable(
            key: String,
            component: String,
            objectId: String,
            payload: () -> String,
            availableOverride: Boolean? = null,
            publishState: () -> Unit,
        ) = this@MqttBridge.exposable(
            key, component, objectId, payload, capabilitySnapshot, availableOverride, publishState,
        )

        // device.name = the configurable friendly name; entity names are the capability ONLY, so HA
        // composes a clean `<domain>.<panel>_<cap>` entity_id without doubling the panel id.
        // configuration_url -> HA renders a "Visit" link on the device page (the panel's info UI).
        val currentConfigUrl = configUrl()?.takeIf(String::isNotBlank)
        lastPublishedConfigUrl = currentConfigUrl
        val cu = currentConfigUrl?.let { ""","configuration_url":"${jsonEsc(it)}"""" } ?: ""
        val name = jsonEsc(runtimeFriendlyName)
        val mfr = jsonEsc(config.manufacturer)
        val mdl = jsonEsc(config.model)
        // hw_version = panel firmware/build; surfaces in HA's device-info section (sw_version is
        // ha-paneld's own version). serial_number = stable Android id.
        val hw = jsonEsc("Android ${Build.VERSION.RELEASE} · ${Build.DISPLAY}")
        // Two device identifiers: the panel_id one (historical primary — existing registrations match
        // on it) plus the IMMUTABLE Android device id. HA merges a device on ANY matching identifier,
        // so a later panel_id change re-attaches to the SAME HA device instead of minting a duplicate.
        val aid = config.androidId
        val ids = if (aid.isNotBlank()) """["ha-paneld-$panel","ha-paneld-aid-$aid"]""" else """["ha-paneld-$panel"]"""
        val device = """"device":{"identifiers":$ids,"name":"$name","manufacturer":"$mfr","model":"$mdl","sw_version":"${Config.VERSION}","hw_version":"$hw","serial_number":"${config.androidId}"$cu}"""
        val avail = """"availability_topic":"$availabilityTopic","payload_available":"online","payload_not_available":"offline""""

        publishConfig(
            "light", "${panel}_screen",
            """{"name":"Screen","object_id":"${panel}_screen","unique_id":"${panel}_screen","schema":"json","brightness":true,"supported_color_modes":["brightness"],"command_topic":"$cmdScreen","state_topic":"$stateScreen",$avail,$device}""",
        )

        if (led.available()) {
            val modes = if (led.colorCapable()) """["rgb"]""" else """["brightness"]"""
            publishConfig(
                "light", "${panel}_led",
                """{"name":"LED","object_id":"${panel}_led","unique_id":"${panel}_led","schema":"json","brightness":true,"supported_color_modes":$modes,"effect":true,"effect_list":["none","strobe","blink","pulse"],"command_topic":"$cmdLed","state_topic":"$stateLed",$avail,$device}""",
            )
        }

        publishConfig(
            "text", "${panel}_navigate",
            """{"name":"Navigate","object_id":"${panel}_navigate","unique_id":"${panel}_navigate","command_topic":"$cmdNavigate","state_topic":"$stateNavigate","mode":"text","icon":"mdi:monitor-dashboard",$avail,$device}""",
        )

        // Per-panel intended "home" dashboard path (e.g. /lovelace/0) — reload re-navigates here once the
        // frontend is back up. Empty = keep the Companion default. Config category.
        publishConfig(
            "text", "${panel}_home_dashboard",
            """{"name":"Home dashboard","object_id":"${panel}_home_dashboard","unique_id":"${panel}_home_dashboard","command_topic":"$cmdHomeDashboard","state_topic":"$stateHomeDashboard","mode":"text","icon":"mdi:home-search","entity_category":"config",$avail,$device}""",
        )
        stateConverger.reconcile("home_dashboard", force = true)

        // The button event entity surfaces a11y key capture AND daemon-instrumented evdev buttons
        // (e.g. the WF1589T power key), so publish it whenever either source exists.
        if (buttonsEnabled || hasEvdevButtons) {
            val eventTypes = mqttButtonEventTypes(profileButtonEventTypes)
                .joinToString(",") { "\"${jsonEsc(it)}\"" }
            publishConfig(
                "event", "${panel}_button",
                """{"name":"Button","object_id":"${panel}_button","unique_id":"${panel}_button","state_topic":"$eventButton","event_types":[$eventTypes],$avail,$device}""",
            )
        }
        // Back is always advertised because profile metadata only orders the live root/accessibility
        // attempts; it does not prove either route unavailable. Recents still reflects firmware support.
        publishConfig(
            "button", "${panel}_back",
            """{"name":"Back","object_id":"${panel}_back","unique_id":"${panel}_back","command_topic":"$cmdBack","icon":"mdi:arrow-left",$avail,$device}""",
        )
        if (hasRecents) {
            publishConfig(
                "button", "${panel}_recents",
                """{"name":"Recents","object_id":"${panel}_recents","unique_id":"${panel}_recents","command_topic":"$cmdRecents","icon":"mdi:view-agenda",$avail,$device}""",
            )
        }

        // TTS/announce playback volume (STREAM_MUSIC). HA has no MQTT media_player platform, so
        // volume is a number entity rather than a media_player slider.
        publishConfig(
            "number", "${panel}_volume",
            """{"name":"Volume","object_id":"${panel}_volume","unique_id":"${panel}_volume","command_topic":"$cmdVolume","state_topic":"$stateVolume","min":0,"max":100,"step":1,"mode":"slider","unit_of_measurement":"%","icon":"mdi:volume-high",$avail,$device}""",
        )

        // Panel sensors — exposed as data only; room sensors stay the occupancy/lux authority.
        if (hasLight) {
            publishConfig(
                "sensor", "${panel}_illuminance",
                """{"name":"Illuminance","object_id":"${panel}_illuminance","unique_id":"${panel}_illuminance","state_topic":"$stateIlluminance","device_class":"illuminance","unit_of_measurement":"lx","state_class":"measurement",$avail,$device}""",
            )
        }
        if (hasProximity) {
            publishConfig(
                "binary_sensor", "${panel}_proximity",
                """{"name":"Proximity","object_id":"${panel}_proximity","unique_id":"${panel}_proximity","state_topic":"$stateProximity","device_class":"occupancy","payload_on":"ON","payload_off":"OFF",$avail,$device}""",
            )
        }
        // SensorManager climate. On CHT8305 panels (TPA10) these are suppressed in favour of the
        // daemon-read room_temp/room_humidity, so publish an EMPTY config (tombstone) instead of
        // skipping — a panel upgrading from a version that DID expose them then sheds the now-duplicate,
        // stale entity from HA (SensorManager never streams a value on that chip). Empty on a panel that
        // never had the sensor is a harmless no-op.
        publishConfig(
            "sensor", "${panel}_temperature",
            if (hasTemperature) """{"name":"Temperature","object_id":"${panel}_temperature","unique_id":"${panel}_temperature","state_topic":"$stateTemperature","device_class":"temperature","unit_of_measurement":"°C","state_class":"measurement",$avail,$device}""" else "",
        )
        publishConfig(
            "sensor", "${panel}_humidity",
            if (hasHumidity) """{"name":"Humidity","object_id":"${panel}_humidity","unique_id":"${panel}_humidity","state_topic":"$stateHumidity","device_class":"humidity","unit_of_measurement":"%","state_class":"measurement",$avail,$device}""" else "",
        )
        if (hasButtonBacklight) {
            publishConfig(
                "light", "${panel}_buttons",
                """{"name":"Button backlight","object_id":"${panel}_buttons","unique_id":"${panel}_buttons","schema":"json","brightness":true,"supported_color_modes":["brightness"],"command_topic":"$cmdButtons","state_topic":"$stateButtons","icon":"mdi:gesture-tap-button",$avail,$device}""",
            )
        }
        // Config switches/numbers — each honours its per-panel "expose to HA" toggle (hidden → the
        // retained discovery payload is cleared, so the entity leaves HA with zero recorder cost).
        // The six registry-backed entities are built from SettingsRegistry (the single source of
        // truth, golden-tested for byte-parity with these payloads); touch_sound + ambient_lux keep
        // literal payloads pending their move into the registry.
        fun reg(key: String) = SettingsRegistry.spec(key)!!.ha!!.buildDiscoveryJson(panel, avail, device)

        if (hasProximity) {
            exposable("wake_on_wave", "switch", "${panel}_wake_on_wave", { reg("wake_on_wave") }) {
                stateConverger.reconcile("wake_on_wave", force = true)
            }
        }
        exposable("touch_sound", "switch", "${panel}_touch_sound", {
            """{"name":"Touch sound","object_id":"${panel}_touch_sound","unique_id":"${panel}_touch_sound","command_topic":"$cmdTouchSound","state_topic":"$stateTouchSound","icon":"mdi:volume-high","entity_category":"config",$avail,$device}"""
        }) { stateConverger.reconcile("touch_sound", force = true) }

        // HA Companion app auto-update — installs/updates the minimal Companion over root (the
        // only update path on these no-Play panels). Off by default; the button forces it on demand.
        exposable("companion_auto_update", "switch", "${panel}_companion_auto_update", {
            """{"name":"Companion auto-update","object_id":"${panel}_companion_auto_update","unique_id":"${panel}_companion_auto_update","command_topic":"$cmdCompanionAuto","state_topic":"$stateCompanionAuto","icon":"mdi:cellphone-arrow-down","entity_category":"config",$avail,$device}"""
        }) { stateConverger.reconcile("companion_auto_update", force = true) }
        publishConfig(
            "button", "${panel}_update_companion",
            """{"name":"Update Companion app","object_id":"${panel}_update_companion","unique_id":"${panel}_update_companion","command_topic":"$cmdUpdateCompanion","icon":"mdi:home-assistant","entity_category":"config",$avail,$device}""",
        )

        // ha-paneld self-update — follows the update channel; installs a newer build of itself over root.
        // Off by default; the update_paneld button forces it on demand.
        exposable("companion_update_channel", "select", "${panel}_companion_update_channel", {
            """{"name":"Companion auto-update channel","object_id":"${panel}_companion_update_channel","unique_id":"${panel}_companion_update_channel","command_topic":"$cmdCompanionChannel","state_topic":"$stateCompanionChannel","options":["Stable","Pre-release"],"icon":"mdi:source-branch","entity_category":"config",$avail,$device}"""
        }) { stateConverger.reconcile("companion_update_channel", force = true) }
        exposable("self_update", "switch", "${panel}_self_update", {
            """{"name":"ha-paneld auto-update","object_id":"${panel}_self_update","unique_id":"${panel}_self_update","command_topic":"$cmdSelfUpdate","state_topic":"$stateSelfUpdate","icon":"mdi:package-up","entity_category":"config",$avail,$device}"""
        }) { stateConverger.reconcile("self_update", force = true) }
        exposable("update_channel", "select", "${panel}_update_channel", {
            """{"name":"ha-paneld auto-update channel","object_id":"${panel}_update_channel","unique_id":"${panel}_update_channel","command_topic":"$cmdUpdateChannel","state_topic":"$stateUpdateChannel","options":["Stable","Pre-release"],"icon":"mdi:source-branch","entity_category":"config",$avail,$device}"""
        }) { stateConverger.reconcile("update_channel", force = true) }
        publishConfig(
            "button", "${panel}_update_paneld",
            """{"name":"Update ha-paneld","object_id":"${panel}_update_paneld","unique_id":"${panel}_update_paneld","command_topic":"$cmdUpdatePaneld","icon":"mdi:package-up","entity_category":"config",$avail,$device}""",
        )
        // System WebView auto-update — advances to the profile's pinned build (webview-mirror) over root.
        // Auto-gated on webViewManaged (removed on Play-updated panels with no recommended pin).
        exposable("webview_auto_update", "switch", "${panel}_webview_auto_update", { reg("webview_auto_update") }) {
            stateConverger.reconcile("webview_auto_update", force = true)
        }

        exposable("watchdog_enabled", "switch", "${panel}_watchdog", { reg("watchdog_enabled") }) {
            stateConverger.reconcile("watchdog", force = true)
        }
        exposable("kiosk_lock", "switch", "${panel}_kiosk_lock", { reg("kiosk_lock") }) {
            stateConverger.reconcile("kiosk_lock", force = true)
        }
        exposable("silence_boot_chime", "switch", "${panel}_silence_boot_chime", { reg("silence_boot_chime") }) {
            stateConverger.reconcile("silence_boot_chime", force = true)
        }
        exposable("prevent_idle_dim", "switch", "${panel}_prevent_idle_dim", { reg("prevent_idle_dim") }) {
            stateConverger.reconcile("prevent_idle_dim", force = true)
        }
        // Auto-brightness — optional on-panel engine (off by default). When on, drives the screen
        // backlight from the panel's own light sensor where present, or the HA-fed ambient-lux number.
        exposable("auto_brightness", "switch", "${panel}_auto_brightness", { reg("auto_brightness") }) {
            stateConverger.reconcile("auto_brightness", force = true)
        }
        exposable("brightness_bias", "number", "${panel}_brightness_bias", { reg("brightness_bias") }) {
            stateConverger.reconcile("brightness_bias", force = true)
        }
        // HA-fed room lux → auto-brightness input. The only source on sensor-less panels (e.g. WF1589T);
        // an HA automation pushes room lux here and the engine applies the curve. No state on connect.
        exposable("ambient_lux", "number", "${panel}_ambient_lux", {
            """{"name":"Ambient lux (HA-fed)","object_id":"${panel}_ambient_lux","unique_id":"${panel}_ambient_lux","command_topic":"$cmdAmbientLux","state_topic":"$stateAmbientLux","min":0,"max":100000,"step":1,"mode":"box","unit_of_measurement":"lx","icon":"mdi:brightness-5","entity_category":"config",$avail,$device}"""
        }) { }

        // Zigbee router — only on panels with the Sonoff gateway package (NSPanel Pro). present()
        // costs a su exec; safe here because onConnected runs off the main thread.
        if (channelShape.zigbee) {
            exposable("zigbee_router", "switch", "${panel}_zigbee_router", {
                """{"name":"Zigbee router","object_id":"${panel}_zigbee_router","unique_id":"${panel}_zigbee_router","command_topic":"$cmdZigbee","state_topic":"$stateZigbee","icon":"mdi:zigbee","entity_category":"config",$avail,$device}"""
            }, availableOverride = true) { stateConverger.reconcile("zigbee_router", force = true) }
            publishConfig(
                "sensor", "${panel}_zigbee_gateway_health",
                """{"name":"Zigbee gateway health","object_id":"${panel}_zigbee_gateway_health","unique_id":"${panel}_zigbee_gateway_health","state_topic":"$stateZigbeeHealth","json_attributes_topic":"$attrZigbeeHealth","icon":"mdi:zigbee","entity_category":"diagnostic",$avail,$device}""",
            )
            publishZigbeeHealth()
        }

        // On-board relays (Smatek S9E `st_relay`). count() probes sysfs via su — off-main-thread here.
        for (n in 1..channelShape.relays) {
            publishConfig(
                "switch", "${panel}_relay$n",
                """{"name":"Relay $n","object_id":"${panel}_relay$n","unique_id":"${panel}_relay$n","command_topic":"ha-paneld/$panel/relay$n/set","state_topic":"ha-paneld/$panel/relay$n/state","icon":"mdi:electric-switch",$avail,$device}""",
            )
            stateConverger.reconcile("relay$n", force = true)
        }

        // S9E button LEDs (gpio147-150) — on/off lights, gated on the gpio nodes being present.
        for (n in 1..channelShape.buttonLeds) {
            publishConfig(
                "light", "${panel}_button_led$n",
                """{"name":"Button LED $n","object_id":"${panel}_button_led$n","unique_id":"${panel}_button_led$n","command_topic":"ha-paneld/$panel/button_led$n/set","state_topic":"ha-paneld/$panel/button_led$n/state","icon":"mdi:led-on",$avail,$device}""",
            )
            stateConverger.reconcile("button_led$n", force = true)
        }

        // CPU governor (select) — su panels with cpufreq. Three intent-based tiers (Performance /
        // Efficiency / Auto) rather than raw kernel governor names; CpuController maps each to this
        // SoC's governor (Auto = its dynamic governor — ramps up on interaction, idles low).
        if (channelShape.cpu) {
            val opts = CpuController.TIERS.joinToString(",") { "\"${jsonEsc(it)}\"" }
            exposable("cpu_governor", "select", "${panel}_cpu_governor", {
                """{"name":"CPU profile","object_id":"${panel}_cpu_governor","unique_id":"${panel}_cpu_governor","command_topic":"$cmdCpuGov","state_topic":"$stateCpuGov","options":[$opts],"icon":"mdi:speedometer","entity_category":"config",$avail,$device}"""
            }, availableOverride = true) { cpu.currentTier()?.let { lastPublishedGovTier = it }; stateConverger.reconcile("cpu_governor", force = true) }
        }

        // Soft navbar (select) — overlay Back/Home/Recents bar for panels whose firmware hides the
        // native navbar. Published on all panels; Off by default, the user opts a panel in. Drawing
        // needs SYSTEM_ALERT_WINDOW (root-granted by NavbarController); a no-op select otherwise.
        run {
            val opts = NavbarController.MODES.joinToString(",") { "\"${jsonEsc(it)}\"" }
            exposable("navbar_mode", "select", "${panel}_navbar", {
                """{"name":"Navbar","object_id":"${panel}_navbar","unique_id":"${panel}_navbar","command_topic":"$cmdNavbar","state_topic":"$stateNavbar","options":[$opts],"icon":"mdi:gesture-tap-button","entity_category":"config",$avail,$device}"""
            }) { stateConverger.reconcile("navbar", force = true) }
        }

        // Persistent network adb (switch) — opt-in; root panels only. Standing LAN adb port when ON.
        if (channelShape.networkAdb) {
            exposable("network_adb", "switch", "${panel}_network_adb", {
                """{"name":"Network ADB","object_id":"${panel}_network_adb","unique_id":"${panel}_network_adb","command_topic":"$cmdNetAdb","state_topic":"$stateNetAdb","icon":"mdi:adb","entity_category":"config",$avail,$device}"""
            }, availableOverride = true) { stateConverger.reconcile("network_adb", force = true) }
        }

        // Diagnostic sensors (read-only) — all OPT-IN (haExposedByDefault=false), so a panel is silent
        // in HA until a pip is enabled. Publish the current value on expose; syncLocalState() refreshes
        // them each heartbeat tick with a deadband. Boot time is constant, so it's published only here.
        for (key in DIAG_KEYS) {
            exposable(key, "sensor", "${panel}_$key", { reg(key) }) { publishDiag(key) }
        }

        // Room climate (CHT8305 panels only, e.g. TPA10) — real environmental sensors, opt-in like the
        // diagnostics. Same publish machinery (deadbanded heartbeat refresh via syncDiagnostics).
        if (hasCht8305) for (key in ROOM_KEYS) {
            exposable(key, "sensor", "${panel}_$key", { reg(key) }) { publishDiag(key) }
        }

        // Panel actions (root via su; graceful no-op without it).
        publishConfig(
            "button", "${panel}_reload",
            """{"name":"Reload dashboard","object_id":"${panel}_reload","unique_id":"${panel}_reload","command_topic":"$cmdReload","icon":"mdi:web-refresh",$avail,$device}""",
        )
        publishConfig(
            "button", "${panel}_reboot",
            """{"name":"Reboot","object_id":"${panel}_reboot","unique_id":"${panel}_reboot","command_topic":"$cmdReboot","device_class":"restart","icon":"mdi:restart",$avail,$device}""",
        )
        publishConfig(
            "button", "${panel}_launcher",
            """{"name":"Launcher","object_id":"${panel}_launcher","unique_id":"${panel}_launcher","command_topic":"$cmdLauncher","icon":"mdi:apps",$avail,$device}""",
        )
        publishConfig(
            "button", "${panel}_home",
            """{"name":"Home Assistant","object_id":"${panel}_home","unique_id":"${panel}_home","command_topic":"$cmdHome","icon":"mdi:home-assistant",$avail,$device}""",
        )
        publishConfig(
            "button", "${panel}_admin_launcher",
            """{"name":"Admin launcher","object_id":"${panel}_admin_launcher","unique_id":"${panel}_admin_launcher","command_topic":"$cmdAdminLauncher","icon":"mdi:cog-box",$avail,$device}""",
        )
    }

    private fun jsonEsc(s: String): String = Json.esc(s)

    // Discovery configs are published NON-retained. Entities still rebuild on an HA restart because we
    // re-announce on HA's `homeassistant/status` = online birth message (+ on our own every connect) —
    // but a deleted/renamed/decommissioned entity's config no longer lingers retained on the broker to
    // resurrect it. (State topics + the availability LWT stay retained.)
    // Every discovery config topic published THIS session — recorded as we publish, so teardown/prune act
    // on exactly what we announced (no hardcoded drift). Thread-safe: publishConfig runs on the MQTT
    // thread; prune/clear may run from reconfigure on another thread.
    private val publishedConfigTopics = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private fun publishConfig(component: String, objectId: String, payload: String) {
        val topic = "homeassistant/$component/$objectId/config"
        publishedConfigTopics.add(topic)
        // Live configs are deliberately non-retained. Empty payloads are different: retaining the
        // tombstone clears any config an older retain=true release left on the broker, so a hidden or
        // unavailable entity cannot resurrect while this panel is offline.
        publish(topic, withDefaultEntityId(component, objectId, payload), retain = mqttDiscoveryRetain(payload))
    }

    /**
     * The historical SUPERSET of every entity ha-paneld has ever published for a panel — the tombstone
     * list. KEEP entities here even after they're removed from [publishDiscovery], so an upgrade can
     * actively clear a now-refactored-away entity (see [pruneStaleDiscovery]) instead of zombie-ing it.
     */
    private fun knownConfigTopics(): Set<String> = mqttKnownConfigTopics(panel)

    /**
     * Active upgrade migration: clear any KNOWN entity we did NOT publish this session — i.e. one a prior
     * previous runtime announced but this runtime refactored away (or no longer has as a capability) —
     * so it is removed from HA instead of lingering as a zombie. Called once after a core-version or
     * profile-revision change. Empty retained payload also clears configs an older, retain=true version
     * left on the broker.
     */
    private fun pruneStaleDiscovery(onComplete: (Boolean) -> Unit) {
        val published = synchronized(publishedConfigTopics) { publishedConfigTopics.toSet() }
        val stale = knownConfigTopics().filter { it !in published }
        if (stale.isEmpty()) {
            onComplete(true)
            return
        }
        val remaining = java.util.concurrent.atomic.AtomicInteger(stale.size)
        val allAcknowledged = AtomicBoolean(true)
        stale.forEach { topic ->
            publish(topic, "", retain = true) { acknowledged ->
                if (!acknowledged) allAcknowledged.set(false)
                if (remaining.decrementAndGet() == 0) onComplete(allAcknowledged.get())
            }
        }
        Log.i(TAG, "discovery prune: submitted ${stale.size} refactored-away/absent entities for $panel")
    }

    private fun publish(
        topic: String,
        payload: String,
        retain: Boolean = false,
        onComplete: ((Boolean) -> Unit)? = null,
    ) {
        if (stopped) {
            onComplete?.invoke(false)
            return
        }
        // Routed through the transport, whose broker-ACK callback fires onPublishAck (markOk) — the QoS-1
        // liveness signal the watchdog trusts over HiveMQ's self-reported connected state.
        transport.publish(topic, payload.toByteArray(), retain, onComplete)
    }

    /**
     * Liveness probe: publish a monotonic-independent `last_seen` (epoch seconds) so a healthy link keeps
     * [lastOkMs] fresh even when nothing else is publishing, and a dead half-open link stops ACKing and
     * goes stale (→ watchdog reconnect). Non-retained (a stale retained heartbeat would be misleading).
     * No-op when there's no client / broker. Called each watchdog tick from the service.
     */
    fun heartbeat() {
        if (stopped) return
        if (state == "disabled") return
        runCatching { publish("ha-paneld/$panel/last_seen", (System.currentTimeMillis() / 1000).toString()) }
        if (stopped) return
        runCatching { syncLocalState() }
    }

    /**
     * Local-state → MQTT sync: panel values can change OUTSIDE ha-paneld's API (auto-brightness and
     * any local app writing the setting, hardware volume keys, vendor firmware dimming the backlight
     * node), and HA must track them without being flooded. One pass per heartbeat tick per channel:
     * publish only when the value differs from the LAST PUBLISHED beyond the channel's deadband AND
     * has settled (change since the previous tick within the settle band) — fast oscillation
     * publishes nothing until it stops, a slow ramp publishes at most once per tick, steady state
     * publishes zero messages. Runs on the watchdog thread (su-safe, off-main).
     */
    private fun syncLocalState() {
        runCatching {
            val capabilityObservation = ensureCapabilityChannels(
                discoveryCapabilities.snapshot(CAPABILITY_RECOVERY_PROBE_MS),
            )
            capabilityObservation.changedKeys().forEach {
                stateConverger.reconcile(it, force = true)
            }
            if (capabilityObservation.grew) requestReAnnounce()
            if (!capabilityObservation.previousLive.zigbee && capabilityObservation.live.zigbee) {
                reconcileZigbeeOnConnect()
            }
        }
        val physicallyDark = screen.observedDark()
        val becameOff = physicallyDark == true && lastScreenBrightness >= 0
        if (becameOff) {
            Log.i(TAG, "screen became physically dark outside MQTT — syncing OFF")
            syncLog.record(SystemClock.elapsedRealtime(), "screen →OFF (physical)")
            publishScreenOff()
        } else if (physicallyDark == false && lastScreenBrightness < 0) {
            val level = brightness.getCommanded().coerceAtLeast(1)
            Log.i(TAG, "screen became physically lit outside MQTT — syncing ON")
            syncLog.record(SystemClock.elapsedRealtime(), "screen →ON (physical)")
            publishScreenBrightness(level)
        }

        // Channel: commanded brightness (Android setting — the scale HA commands in). Catches
        // auto-brightness and any local actor. Skipped while the screen is deliberately off.
        if (!becameOff && !screen.isIntendedOff() && lastScreenBrightness >= 0
        ) {
            val cur = brightness.getCommanded()
            if (settled(cur, prevTickBrightness, lastScreenBrightness, deadband = 3)) {
                Log.i(TAG, "local brightness changed outside MQTT: $lastScreenBrightness -> $cur — syncing")
                syncLog.record(SystemClock.elapsedRealtime(), "brightness $lastScreenBrightness→$cur")
                publishScreenBrightness(cur.coerceAtLeast(1))
            }
            prevTickBrightness = cur

            // Channel: effective backlight vs its post-command baseline — ONLY when the commanded
            // setting hasn't moved (else the channel above owns it). Catches firmware node-dims. The
            // node scale differs from the setting scale on curve-mapped panels, so drift is reported
            // back-mapped proportionally into the commanded scale.
            if (cur == lastScreenBrightness || kotlin.math.abs(cur - lastScreenBrightness) <= 3) {
                val eff = brightness.getBrightness()
                if (eff >= 0) {
                    val base = screenEffectiveBaseline
                    if (base < 0) {
                        screenEffectiveBaseline = eff   // command settled — remember its hardware level
                    } else if (kotlin.math.abs(eff - base) > SCREEN_DRIFT) {
                        val reported = (lastScreenBrightness.toLong() * eff / base.coerceAtLeast(1))
                            .toInt().coerceIn(1, 255)
                        Log.i(TAG, "screen backlight moved externally: baseline $base -> $eff — reporting $reported")
                        syncLog.record(SystemClock.elapsedRealtime(), "backlight →$reported (firmware dim)")
                        publishScreenBrightness(reported)
                        screenEffectiveBaseline = eff
                    }
                }
            }
        }

        // Channel: volume — hardware keys / local apps change it outside MQTT.
        runCatching {
            val cur = volume.getPercent()
            if (settled(cur, prevTickVolume, lastPublishedVolume, deadband = 1)) {
                Log.i(TAG, "local volume changed outside MQTT: $lastPublishedVolume -> $cur — syncing")
                syncLog.record(SystemClock.elapsedRealtime(), "volume $lastPublishedVolume→$cur")
                lastPublishedVolume = cur
                stateConverger.reconcile("volume", force = true)
            }
            prevTickVolume = cur
        }

        // Channel: CPU governor — a thermal daemon or another app can change the scaling governor;
        // currentTier() reads the LIVE sysfs governor, so publish when the mapped tier no longer matches
        // what HA last saw. Categorical (not numeric) — publish on any change vs the baseline, no deadband.
        // The baseline is set at announce / on command, so this only fires on a genuinely external change.
        runCatching {
            if (capabilityShape.current().live.cpu) {
                val tier = cpu.currentTier()
                if (tier != null && lastPublishedGovTier != null && tier != lastPublishedGovTier) {
                    Log.i(TAG, "cpu governor changed outside MQTT: $lastPublishedGovTier -> $tier — syncing")
                    syncLog.record(SystemClock.elapsedRealtime(), "cpu_governor $lastPublishedGovTier→$tier")
                    lastPublishedGovTier = tier
                    stateConverger.reconcile("cpu_governor", force = true)
                }
            }
        }

        // Diagnostic sensors — refresh each exposed one, deadbanded so slow drift (temperature, memory,
        // CPU average) never floods the broker. Boot time is constant, so it's not re-published here.
        runCatching { syncDiagnostics() }

        // Architectural safety net: audit every registered state channel from its declared authority.
        // Stable acknowledged values cost no publish; failed sends stay dirty and retry next heartbeat.
        runCatching { stateConverger.reconcileAll() }
    }

    // Current string value for a diagnostic sensor, or null when unavailable on this panel.
    private fun diagValue(key: String): String? = when (key) {
        "diag_ip" -> Diagnostics.ipAddress()
        "diag_cpu" -> Diagnostics.cpuPercent()?.toString()
        "diag_memory" -> Diagnostics.memoryPercent()?.toString()
        "diag_soc_temp" -> Diagnostics.socTempC()?.let { String.format(java.util.Locale.US, "%.1f", it) }
        "diag_boot" -> Diagnostics.bootTime()
        // Room climate — apply the calibration offset to temperature; humidity is reported whole-percent.
        "room_temp" -> Diagnostics.roomTempC()?.let { String.format(java.util.Locale.US, "%.1f", it + config.roomTempOffsetC) }
        "room_humidity" -> Diagnostics.roomHumidity()?.let { String.format(java.util.Locale.US, "%.0f", it) }
        else -> null
    }

    /** Publish a diagnostic sensor's current value (unconditional — used at expose time). */
    private fun publishDiag(key: String) {
        stateConverger.reconcile(key, force = true)
    }

    /** Per-tick refresh of the numeric/IP diagnostic sensors, gated on expose + a per-metric deadband
     *  so steady state costs zero messages. Boot time (constant) is published only at expose. */
    private fun syncDiagnostics() {
        val keys = if (hasCht8305) DIAG_KEYS + ROOM_KEYS else DIAG_KEYS
        for (key in keys) {
            if (!config.haExposed(key, false)) continue
            stateConverger.reconcile(key)
        }
    }

    /** Sync predicate — delegates to the pure [io.github.maxlyth.hapaneld.mqtt.SyncGate] (unit-tested):
     *  [cur] differs from [published] beyond [deadband] AND isn't still moving fast (within SETTLE_BAND of
     *  the previous tick's read — a mid-flight value waits for the next tick). */
    private fun settled(cur: Int, prevTick: Int, published: Int, deadband: Int): Boolean =
        io.github.maxlyth.hapaneld.mqtt.SyncGate.settled(cur, prevTick, published, deadband, SETTLE_BAND)

    /** Recent local-state sync events (newest first) for the info page + /diag. Empty when nothing has
     *  changed outside MQTT since boot. */
    fun recentSyncEvents(): List<String> = syncLog.recent(SystemClock.elapsedRealtime())

    fun convergenceStatus(): String = stateConverger.status().let {
        "${it.channels} channels · ${it.dirty} dirty · ${it.inFlight} in-flight · ${it.unknown} unknown · " +
            "ack ${it.successes}/${it.failures} · pending ${it.pending.joinToString(",").ifEmpty { "none" }}"
    }

    /** Publish screen=OFF and reset the brightness baseline used by local-state reconciliation. */
    private fun publishScreenOff() {
        lastScreenBrightness = -1
        screenEffectiveBaseline = -1
        stateConverger.reconcile("screen", force = true)
    }

    /** Publish screen=ON at [level] and remember it as the last-reported brightness (the reconcile
     *  in [heartbeat] compares the effective backlight against this). */
    private fun publishScreenBrightness(level: Int) {
        lastScreenBrightness = level
        screenEffectiveBaseline = -1   // re-capture on the next tick, after the framework settles
        stateConverger.reconcile("screen", force = true)
    }

    fun stop(publishOffline: Boolean = true, clearDiscovery: Boolean = false) {
        synchronized(stopLock) {
            if (stopped) return
            stopped = true
            lifecycleGeneration.incrementAndGet()
        }
        // Invalidate this bridge before draining command work. An old privileged call may ignore
        // interruption, but the process-wide coordinator serializes it ahead of the replacement's
        // latest actuation, so it can never finish after that replacement has applied OFF.
        zigbeeActuation.retire(zigbeeLease)
        // Cancel queued work, then drain the one active MQTT/HTTP live-setting dispatch before any
        // service-owned hardware owner can be torn down.
        val cancelledCommands = commandDispatcher.closeAndDrain()
        if (cancelledCommands > 0) {
            FeatureCosts.registry.recordDropped(
                FeatureCostOperation.MQTT_COMMAND_DISPATCH,
                cancelledCommands.toLong(),
            )
        }
        FeatureCosts.registry.setBacklog(FeatureCostOperation.MQTT_COMMAND_DISPATCH, 0)
        authRetryGeneration++
        reloadNavigationFuture?.cancel(false)
        reloadNavigationFuture = null
        authScheduler.shutdownNow()
        buttonSubscription?.close()
        buttonSubscription = null
        PanelStatus.mqttConnected = false
        val queuedZigbee = zigbeeWorker.pendingCount()
        if (queuedZigbee > 0) {
            FeatureCosts.registry.recordDropped(FeatureCostOperation.ZIGBEE_RECONCILE, queuedZigbee.toLong())
        }
        if (!zigbeeWorker.closeAndJoin(ZIGBEE_DRAIN_TIMEOUT_MS)) {
            Log.w(TAG, "Zigbee worker did not drain before lifecycle timeout; replacement remains serialized")
        }
        FeatureCosts.registry.setBacklog(FeatureCostOperation.ZIGBEE_RECONCILE, 0)
        if (!reannounceDispatcher.closeAndJoin(REANNOUNCE_DRAIN_TIMEOUT_MS)) {
            Log.w(TAG, "MQTT reannouncement worker did not drain before lifecycle timeout")
        }
        FeatureCosts.registry.setBacklog(FeatureCostOperation.MQTT_DISCOVERY_REANNOUNCE, 0)
        if (stateConvergerOwner.isInitialized()) stateConverger.close()
        state = "disabled"
        runCatching {
            val finalPublications = buildList {
                if (clearDiscovery) knownConfigTopics().forEach {
                    add(MqttFinalPublish(it, byteArrayOf(), retain = true))
                }
                if (publishOffline) {
                    add(MqttFinalPublish(availabilityTopic, "offline".toByteArray(), retain = true))
                }
            }
            if (finalPublications.isNotEmpty()) {
                transport.publishThenDisconnect(
                    finalPublications,
                    timeoutMs = FINAL_PUBLISH_TIMEOUT_MS,
                )
            } else {
                transport.disconnectDetached()
            }
        }
    }

    companion object {
        private const val TAG = "ha-paneld/mqtt"
        private const val MAX_COMMAND_PAYLOAD_BYTES = 64 * 1024
        private const val MAX_DYNAMIC_COMMAND_INDEX = 64
        private const val HA_LINK_TTL_MS = 6 * 3_600_000L // re-resolve the "Open in HA" link at most every 6h

        /** Keys accepted by [applySetting]. Kept explicit so non-MQTT callers can verify their routing. */
        internal val APPLY_SETTING_KEYS = setOf(
            "wake_on_wave", "prevent_idle_dim", "watchdog_enabled", "kiosk_lock",
            "silence_boot_chime", "auto_brightness", "touch_sound", "network_adb",
            "zigbee_router", "brightness_bias", "ambient_lux", "cpu_governor", "navbar_mode",
            "companion_auto_update", "companion_update_channel", "self_update", "webview_auto_update",
            "update_channel", "home_dashboard",
        )

        /**
         * Inject `default_entity_id` = `<component>.<objectId>` as the FIRST key of a discovery payload —
         * the field HA actually honours to pin the entity_id. HA's `object_id` discovery key is IGNORED
         * (it isn't in the MQTT schema; verified against live HA 2026.7.1 source + registry): MQTT entities
         * are ALWAYS `has_entity_name=True`, so without this the entity_id derives from the mutable
         * device/friendly name (`slug(device.name + entity.name)`) and drifts when the friendly name is
         * renamed. `default_entity_id` anchors it to the stable panel_id instead, while `device.name` stays
         * the pretty friendly name. HA applies it at REGISTRATION only, so existing entities keep their id
         * (no churn). Empty payload = tombstone (returned unchanged). Idempotent. Pure — tested in
         * DefaultEntityIdTest.
         */
        fun withDefaultEntityId(component: String, objectId: String, payload: String): String =
            if (payload.isNotEmpty() && !payload.contains("\"default_entity_id\"")) {
                """{"default_entity_id":"$component.$objectId",""" + payload.substring(1)
            } else {
                payload
            }

        private const val SCREEN_DRIFT = 2   // reconcile threshold (0-255 scale) — ignore rounding jitter
        private const val SETTLE_BAND = 6    // "still moving" if the value shifted more than this since last tick
        // Diagnostic sensors published via the opt-in exposable() gate (all default local-only).
        private val DIAG_KEYS = listOf("diag_ip", "diag_cpu", "diag_memory", "diag_soc_temp", "diag_boot")
        // Room climate sensors — same opt-in machinery, but only on panels with a CHT8305 (see hasCht8305).
        private val ROOM_KEYS = listOf("room_temp", "room_humidity")
        // How long to wait after a reload before deep-linking to the intended dashboard — lets the WebView
        // cold-start + the HA frontend load so the navigate deeplink isn't swallowed.
        private const val RELOAD_NAV_DELAY_MS = 8_000L
        // MQTT keepalive: PINGREQ every this-many idle seconds. Short enough to detect a dead link within
        // ~1.5× this, well under the service liveness-watchdog's stale threshold.
        private const val KEEPALIVE_SEC = 30
        private const val FINAL_PUBLISH_TIMEOUT_MS = 2_000L
        private const val ZIGBEE_DRAIN_TIMEOUT_MS = 2_000L
        private const val REANNOUNCE_DEBOUNCE_MS = 250L
        private const val REANNOUNCE_DRAIN_TIMEOUT_MS = 1_000L
        private const val REANNOUNCE_CAPABILITY_TTL_MS = 5_000L
        private const val CAPABILITY_RECOVERY_PROBE_MS = 30_000L
    }
}

internal data class MqttCapabilityChannels(
    val zigbee: Boolean = false,
    val cpu: Boolean = false,
    val networkAdb: Boolean = false,
    val relays: Int = 0,
    val buttonLeds: Int = 0,
) {
    fun keys(): Set<String> = buildSet {
        if (zigbee) add("zigbee_router")
        if (cpu) add("cpu_governor")
        if (networkAdb) add("network_adb")
        for (n in 1..relays) add("relay$n")
        for (n in 1..buttonLeds) add("button_led$n")
    }
}

internal data class MqttCapabilityObservation(
    val possible: MqttCapabilityChannels,
    val live: MqttCapabilityChannels,
    val previousLive: MqttCapabilityChannels,
    val grew: Boolean,
) {
    fun changedKeys(): Set<String> = buildSet {
        if (live.zigbee != previousLive.zigbee && possible.zigbee) add("zigbee_router")
        if (live.cpu != previousLive.cpu && possible.cpu) add("cpu_governor")
        if (live.networkAdb != previousLive.networkAdb && possible.networkAdb) add("network_adb")
        if (live.relays != previousLive.relays) {
            for (n in minOf(live.relays, previousLive.relays) + 1..maxOf(live.relays, previousLive.relays)) {
                add("relay$n")
            }
        }
        if (live.buttonLeds != previousLive.buttonLeds) {
            for (n in minOf(live.buttonLeds, previousLive.buttonLeds) + 1..
                maxOf(live.buttonLeds, previousLive.buttonLeds)
            ) {
                add("button_led$n")
            }
        }
    }
}

/**
 * Separates monotonic channel shape from the latest live probe. Once hardware has been confirmed its
 * MQTT identity stays stable; a later unavailable snapshot gates its state observer instead of deleting
 * the entity. Newly confirmed channels can be registered and announced by the existing bridge.
 */
internal class MqttCapabilityShape(private val maxDynamicChannels: Int = 64) {
    init {
        require(maxDynamicChannels >= 0)
    }

    private var possible = MqttCapabilityChannels()
    private var live = MqttCapabilityChannels()

    @Synchronized
    fun observe(capabilities: Capabilities): MqttCapabilityObservation {
        val previousLive = live
        live = MqttCapabilityChannels(
            zigbee = capabilities.zigbeePresent,
            cpu = capabilities.cpuGovernors,
            networkAdb = capabilities.networkAdb,
            relays = capabilities.relays.coerceIn(0, maxDynamicChannels),
            buttonLeds = capabilities.buttonLeds.coerceIn(0, maxDynamicChannels),
        )
        val nextPossible = MqttCapabilityChannels(
            zigbee = possible.zigbee || live.zigbee,
            cpu = possible.cpu || live.cpu,
            networkAdb = possible.networkAdb || live.networkAdb,
            relays = maxOf(possible.relays, live.relays),
            buttonLeds = maxOf(possible.buttonLeds, live.buttonLeds),
        )
        val grew = nextPossible != possible
        possible = nextPossible
        return MqttCapabilityObservation(possible, live, previousLive, grew)
    }

    @Synchronized
    fun current(): MqttCapabilityObservation =
        MqttCapabilityObservation(possible, live, live, grew = false)
}

/** One immutable capability value per discovery announcement, with a short monotonic burst cache. */
internal class MqttDiscoveryCapabilitySource(
    private val supplier: (() -> Capabilities)?,
    private val onFailure: (Exception) -> Unit = {},
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val initial = AtomicReference<Capabilities?>()
    private var latest: Capabilities? = null
    private var latestAtMs = Long.MIN_VALUE

    @Synchronized
    fun snapshot(maxAgeMs: Long = 0L): Capabilities? {
        val source = supplier ?: return null
        val now = nowMs()
        if (maxAgeMs > 0L && latestAtMs != Long.MIN_VALUE) {
            val age = now - latestAtMs
            if (age in 0..maxAgeMs) return latest
        }
        val value = try {
            source()
        } catch (e: Exception) {
            onFailure(e)
            // A failed live probe must not abort the complete discovery announcement. All-false is the
            // fail-closed settings view; hardware entities driven by constructor/profile facts continue.
            Capabilities()
        }
        initial.compareAndSet(null, value)
        latest = value
        latestAtMs = nowMs()
        return value
    }

    fun initialSnapshot(): Capabilities? = initial.get()
}

/** Debounces a burst into one latest request while keeping callback admission constant-space. */
internal class MqttReannounceDispatcher(
    private val debounceMs: Long,
    private val pause: (Long) -> Unit = Thread::sleep,
    private val perform: (Long) -> Unit,
) {
    private val requestedGeneration = AtomicLong()
    private val closed = AtomicBoolean()
    private val worker = ConflatedWorker<Long>("mqtt-discovery-reannounce") worker@{ generation ->
        try {
            pause(debounceMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return@worker
        }
        if (isCurrent(generation)) perform(generation)
    }

    fun submit(): ConflatedWorker.Admission = worker.submit(requestedGeneration.incrementAndGet())

    fun pendingCount(): Int = worker.pendingCount()

    fun isCurrent(generation: Long): Boolean = !closed.get() && generation == requestedGeneration.get()

    /** Invalidate a retained birth that arrived while a newer connect announcement was in progress. */
    fun suppressPending() {
        requestedGeneration.incrementAndGet()
    }

    fun closeAndJoin(timeoutMs: Long): Boolean {
        closed.set(true)
        return worker.closeAndJoin(timeoutMs)
    }
}

/** Serializes Zigbee actuation across replaceable bridge generations. Invalidation is non-blocking;
 * a newer lease waits behind any old uninterruptible call and therefore always owns the final state. */
internal class MqttZigbeeActuationCoordinator {
    class Lease internal constructor()
    data class Execution<T>(val executed: Boolean, val currentAfter: Boolean, val value: T?)

    private val current = AtomicReference<Lease?>()
    private val actuationLock = Any()

    fun activate(): Lease = Lease().also { replacement -> current.getAndSet(replacement) }

    fun retire(lease: Lease) {
        current.compareAndSet(lease, null)
    }

    fun isCurrent(lease: Lease): Boolean = current.get() === lease

    fun <T> executeIfCurrent(lease: Lease, action: () -> T): Execution<T> = synchronized(actuationLock) {
        if (!isCurrent(lease)) return@synchronized Execution(false, false, null)
        val value = action()
        Execution(true, isCurrent(lease), value)
    }
}

private object MqttZigbeeActuationCoordinators {
    private val controllers = WeakHashMap<ZigbeeController, MqttZigbeeActuationCoordinator>()

    @Synchronized
    fun forController(controller: ZigbeeController): MqttZigbeeActuationCoordinator =
        controllers.getOrPut(controller) { MqttZigbeeActuationCoordinator() }
}

/**
 * The historical discovery superset is deliberately explicit: if a registry entity is later removed,
 * its topic must remain here so an upgrade or panel-id change can still delete the old HA entity. The
 * coverage test requires every current registry entity to be added before it can ship.
 */
internal fun mqttKnownConfigTopics(panel: String): Set<String> = listOf(
    "light" to "${panel}_screen", "light" to "${panel}_led",
    "text" to "${panel}_navigate", "text" to "${panel}_home_dashboard", "event" to "${panel}_button",
    "button" to "${panel}_back", "button" to "${panel}_recents",
    "number" to "${panel}_volume", "sensor" to "${panel}_illuminance",
    "binary_sensor" to "${panel}_proximity",
    "sensor" to "${panel}_temperature", "sensor" to "${panel}_humidity",
    "sensor" to "${panel}_room_temp", "sensor" to "${panel}_room_humidity",
    "light" to "${panel}_buttons", "switch" to "${panel}_wake_on_wave",
    "switch" to "${panel}_touch_sound", "switch" to "${panel}_watchdog", "switch" to "${panel}_kiosk_lock",
    "switch" to "${panel}_silence_boot_chime", "switch" to "${panel}_prevent_idle_dim",
    "switch" to "${panel}_companion_auto_update", "button" to "${panel}_update_companion",
    "select" to "${panel}_companion_update_channel",
    "switch" to "${panel}_self_update", "select" to "${panel}_update_channel",
    "button" to "${panel}_update_paneld",
    "switch" to "${panel}_webview_auto_update",
    "switch" to "${panel}_zigbee_router",
    "switch" to "${panel}_auto_brightness", "number" to "${panel}_brightness_bias",
    "number" to "${panel}_ambient_lux",
    "switch" to "${panel}_relay1", "switch" to "${panel}_relay2",
    "switch" to "${panel}_relay3", "switch" to "${panel}_relay4",
    "light" to "${panel}_button_led1", "light" to "${panel}_button_led2",
    "light" to "${panel}_button_led3", "light" to "${panel}_button_led4",
    "select" to "${panel}_cpu_governor", "select" to "${panel}_navbar",
    "switch" to "${panel}_network_adb",
    "sensor" to "${panel}_diag_ip", "sensor" to "${panel}_diag_cpu",
    "sensor" to "${panel}_diag_memory", "sensor" to "${panel}_diag_soc_temp",
    "sensor" to "${panel}_diag_boot",
    "button" to "${panel}_reload", "button" to "${panel}_reboot",
    "button" to "${panel}_launcher", "button" to "${panel}_home",
    "button" to "${panel}_admin_launcher",
).mapTo(linkedSetOf()) { (comp, obj) -> "homeassistant/$comp/$obj/config" }

internal fun mqttButtonEventTypes(profileEventTypes: Set<String>): List<String> =
    (setOf(
        "KEYCODE_POWER", "KEYCODE_MUTE", "KEYCODE_F", "KEYCODE_F1", "KEYCODE_F2", "KEYCODE_F3",
        "KEYCODE_F4", "KEYCODE_BACK", "KEYCODE_HOME", "KEYCODE_DPAD_CENTER", "KEYCODE_VOLUME_UP",
        "KEYCODE_VOLUME_DOWN",
    ) + profileEventTypes).sorted()

internal data class MqttCleanupPublication(val topic: String, val payload: String, val retain: Boolean)

/**
 * Durable identity for the discovery shape which last completed stale-topic pruning. A non-empty
 * profile identity is length-prefixed so arbitrary profile ids cannot collide with the core version.
 * Empty deliberately retains the historical version-only marker for callers without runtime profiles.
 */
internal fun mqttDiscoveryCleanupMarker(coreVersion: String, profileIdentity: String): String =
    if (profileIdentity.isEmpty()) coreVersion else "$coreVersion|${profileIdentity.length}:$profileIdentity"

/** Cleanup for a renamed panel is owned by the replacement connection and therefore retries with it. */
internal fun mqttStalePanelCleanup(stalePanel: String?, currentPanel: String): List<MqttCleanupPublication> {
    if (stalePanel == null || stalePanel == currentPanel) return emptyList()
    return buildList {
        mqttKnownConfigTopics(stalePanel).forEach { add(MqttCleanupPublication(it, "", retain = true)) }
        add(MqttCleanupPublication("ha-paneld/$stalePanel/availability", "offline", retain = true))
    }
}

/** Avoid a late offline/online race on an in-place rebuild; retire identities or old brokers explicitly. */
internal fun mqttReconfigurePublishesOffline(
    oldPanel: String,
    newPanel: String,
    oldBroker: String,
    newBroker: String,
): Boolean = oldPanel != newPanel || mqttBrokerIdentity(oldBroker) != mqttBrokerIdentity(newBroker)

internal fun mqttBrokerIdentity(raw: String): String = BrokerEndpoint.endpoint(raw)?.let {
    "${if (it.tls) "tls" else "tcp"}://${it.host.lowercase()}:${it.port}"
} ?: raw.trim()

internal fun mqttAcceptsCommand(stopped: Boolean, retained: Boolean): Boolean = !stopped && !retained

internal fun mqttIsHaOnline(payload: ByteArray): Boolean =
    String(payload, Charsets.UTF_8).trim().equals("online", ignoreCase = true)

internal fun mqttDiscoveryRetain(payload: String): Boolean = payload.isEmpty()

internal fun shouldRepublishDiscoveryAddress(
    connected: Boolean,
    lastPublishedUrl: String?,
    currentUrl: String?,
): Boolean = connected && lastPublishedUrl != currentUrl
