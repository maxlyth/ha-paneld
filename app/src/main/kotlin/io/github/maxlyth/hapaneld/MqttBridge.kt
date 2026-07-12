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
import io.github.maxlyth.hapaneld.hardware.LedEffects
import io.github.maxlyth.hapaneld.util.BrokerEndpoint
import io.github.maxlyth.hapaneld.util.HaLink
import io.github.maxlyth.hapaneld.control.NavbarController
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
import io.github.maxlyth.hapaneld.config.SettingValue
import io.github.maxlyth.hapaneld.config.SettingsRegistry
import io.github.maxlyth.hapaneld.hardware.LedController
import io.github.maxlyth.hapaneld.input.ButtonBus
import io.github.maxlyth.hapaneld.mqtt.HiveMqTransport
import io.github.maxlyth.hapaneld.mqtt.MqttCallbacks
import io.github.maxlyth.hapaneld.mqtt.MqttConnectConfig
import io.github.maxlyth.hapaneld.control.Diagnostics
import io.github.maxlyth.hapaneld.mqtt.MqttTransport
import io.github.maxlyth.hapaneld.mqtt.classifyDisconnect
import io.github.maxlyth.hapaneld.util.HelperClient
import io.github.maxlyth.hapaneld.util.Json
import kotlin.math.roundToInt
import org.json.JSONObject

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
    private val capabilities: (() -> io.github.maxlyth.hapaneld.config.Capabilities)? = null,
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
    private val configUrl: String? = null,
    // Resolves HA's LAN IP via mDNS to default the broker when none is configured (injected by the
    // service, wired to MdnsAdvertiser). Returns null if HA isn't found / mDNS unavailable.
    private val discoverHaIp: () -> String? = { null },
    // HA's advertised base URL (scheme+host+port) from zeroconf TXT — for the "Open in HA" device link,
    // so we never guess a port/scheme. Null if HA isn't found / advertises no URL.
    private val discoverHaUrl: () -> String? = { null },
    // Trigger an HA Companion app install/update. Injected by the service (needs Context + a
    // coroutine); runs off the MQTT thread. Fired by the update_companion button.
    private val onUpdateCompanion: () -> Unit = {},
    // Trigger a ha-paneld self-update on the configured channel. force=true installs the channel's newest
    // regardless of the version check (the update_paneld button + a pre-release→stable channel switch).
    private val onSelfUpdate: (force: Boolean) -> Unit = {},
) {
    private val transport: MqttTransport = HiveMqTransport()

    /** Broker actually in use — configured, or auto-discovered as `tcp://<ha-ip>:1883`; "" if none. */
    var activeBroker: String = ""
        private set

    /** Whether the active connection uses TLS (a ssl:///mqtts:// broker URL). Surfaced on the info page
     *  + /diag so a TLS setup is visible. */
    @Volatile var tlsActive: Boolean = false
        private set

    /** Live connection state for the UI, so an auth failure reads differently from "unreachable":
     *  connected | auth-failed | unreachable | connecting | disabled. */
    @Volatile var state: String = "disabled"
        private set

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
        return "$state · $transport · last-ok $age · prefer ${if (preferIpv4) "IPv4" else "IPv6"}"
    }

    private val panel = config.panelId
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
    private val cmdAutoBright = "ha-paneld/$panel/auto_brightness/set"
    private val stateAutoBright = "ha-paneld/$panel/auto_brightness/state"
    private val cmdBrightnessBias = "ha-paneld/$panel/brightness_bias/set"
    private val stateBrightnessBias = "ha-paneld/$panel/brightness_bias/state"
    private val cmdAmbientLux = "ha-paneld/$panel/ambient_lux/set"
    private val stateAmbientLux = "ha-paneld/$panel/ambient_lux/state"
    @Volatile private var lastAmbientLux: Int? = null
    @Volatile private var lastIlluminance: Int? = null
    @Volatile private var lastProximity: Boolean? = null
    @Volatile private var lastTemperature: Float? = null
    @Volatile private var lastHumidity: Float? = null

    private val stateConverger by lazy { createStateConverger() }
    private val zigbeeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "zigbee-state").apply { isDaemon = true }
    }

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
                true -> known("""{"state":"OFF"}""")
                false -> known("""{"state":"ON","brightness":${lastScreenBrightness.takeIf { it >= 0 } ?: brightness.getCommanded().coerceAtLeast(1)}}""")
                null -> unknown
            }
        }
        channel("led", stateLed) {
            val p = config.lastLed.split(",").mapNotNull { it.toIntOrNull() }
            if (p.size == 5 && p[0] == 1) {
                val effect = LedEffects.Effect.from(config.lastLedEffect)?.effectName ?: "none"
                known(ledStateJson(p[1], p[2], p[3], p[4], effect))
            } else known("""{"state":"OFF"}""")
        }
        channel("navigate", stateNavigate) { known(config.lastNavigate.ifEmpty { "/" }) }
        channel("home_dashboard", stateHomeDashboard) { known(config.homeDashboard) }
        channel("volume", stateVolume) { known(volume.getPercent().toString()) }
        channel("buttons", stateButtons) {
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
        if (adb.available()) channel("network_adb", stateNetAdb) { known(if (adb.isPersisted()) "ON" else "OFF") }
        if (zigbee.present()) channel("zigbee_router", stateZigbee) { known(if (zigbee.running()) "ON" else "OFF") }
        if (cpu.available()) channel("cpu_governor", stateCpuGov) { cpu.currentTier()?.let(known) ?: unknown }

        val relays = relay.count()
        for (n in 1..relays) channel("relay$n", "ha-paneld/$panel/relay$n/state") {
            relay.read(n)?.let { known(if (it) "ON" else "OFF") } ?: unknown
        }
        val buttonLeds = relay.ledCount()
        for (n in 1..buttonLeds) channel("button_led$n", "ha-paneld/$panel/button_led$n/state") {
            relay.ledRead(n - 1)?.let { known(if (it) "ON" else "OFF") } ?: unknown
        }

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

    @Synchronized
    fun start() {
        var broker = config.mqttBroker.trim()
        if (broker.isEmpty()) {
            // No explicit broker — try to find HA on the LAN (mDNS) and default to its :1883.
            discoverHaIp()?.let {
                broker = "tcp://$it:1883"
                Log.i(TAG, "MQTT broker auto-discovered via mDNS (HA at $it): $broker")
            }
        }
        if (broker.isEmpty()) {
            Log.i(TAG, "no broker configured and none discovered — MQTT disabled")
            activeBroker = ""
            state = "disabled"
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
            ButtonBus.listener = { event -> publishButton(event) }
            transport.connect(
                MqttConnectConfig(
                    host = connectHost,
                    port = port,
                    tls = ep.tls,
                    clientId = "ha-paneld-$panel",
                    user = config.mqttUser.ifEmpty { null },
                    password = config.mqttPassword,
                    keepAliveSeconds = KEEPALIVE_SEC,
                    willTopic = availabilityTopic,
                    willPayload = "offline",
                ),
                object : MqttCallbacks {
                    override fun onConnected() = this@MqttBridge.onConnected()
                    override fun onDisconnected(causeMessage: String?) {
                        // Classify so the UI can say "auth rejected" vs "unreachable" rather than "down".
                        state = classifyDisconnect(causeMessage)
                        PanelStatus.mqttConnected = false
                        Log.w(TAG, "MQTT disconnected ($state) — auto-reconnecting: $causeMessage")
                        stateConverger.markAllDirty()
                    }
                    override fun onPublishAck() = markOk()
                },
            )
            Log.i(TAG, "MQTT connecting to $connectHost:$port (host=$host, prefer=${if (preferIpv4) "IPv4" else "IPv6"}) for $panel")
        } catch (e: Exception) {
            Log.w(TAG, "MQTT connect failed", e)
        }
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
        if (state == "disabled") return // no broker configured/discovered — nothing to reconnect to
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
        state = "connected"
        markOk()   // reset the liveness clock; the subscribe/discovery publishes below keep it fresh
        PanelStatus.mqttConnected = true
        transport.subscribe("ha-paneld/$panel/+/set") { topic, payload, retained -> onCommand(topic, payload, retained) }
        // Re-announce discovery when HA (re)starts — its birth message on homeassistant/status. With
        // non-retained discovery this is what rebuilds our entities after an HA restart. (payload may be
        // "online"/"offline"; act only on online. The retained "online" delivered on subscribe just
        // re-runs the announce we do below — harmless.)
        transport.subscribe("homeassistant/status") { _, payload, _ ->
            if (String(payload).trim().equals("online", ignoreCase = true)) reAnnounce()
        }
        publishDiscovery()
        // On an upgrade (running version differs from the one that last announced), actively clear any
        // entity a prior version published but this one no longer does — so a refactored-away entity is
        // removed from HA, not left as a zombie. Runs once per upgrade; publishDiscovery above populated
        // publishedConfigTopics for the current set.
        if (config.lastDiscoveryVersion != Config.VERSION) {
            pruneStaleDiscovery()
            config.setLastDiscoveryVersion(Config.VERSION)
        }
        publish(availabilityTopic, "online", retain = true)
        restoreAndPublishStates()
        stateConverger.reconcileAll()
        reconcileZigbeeOnConnect() // boot-restore: start the gateway if left ON and nothing else has
        Thread { runCatching { adb.reassert() } }.start() // re-assert network-adb if ha-paneld persists it (firmware may strip the prop)
        maybeResolveHaLink() // best-effort "Open in HA" link via the MQTT creds; off-thread, silent on failure
        Log.i(TAG, "MQTT connected — (re)subscribed + discovery for $panel")
    }

    /** Re-publish discovery + current states — on HA's `online` birth (non-retained discovery must be
     *  re-sent when HA restarts). No-op if not connected. */
    private fun reAnnounce() {
        if (!isConnected()) return
        publishDiscovery()
        restoreAndPublishStates()
        Log.i(TAG, "HA online — re-announced discovery for $panel")
    }

    /**
     * Resolve this panel's HA device-settings URL using the MQTT username/password (when those are also a
     * valid HA user — typical with the built-in Mosquitto add-on) and cache it for the info page's
     * "Open in Home Assistant" link. Off the MQTT thread; anonymous brokers and any failure no-op silently.
     * Cached until stale (re-resolved at most every [HA_LINK_TTL_MS] so a device delete+recreate self-heals)
     * and cleared on a panel_id change. A failed re-resolve keeps the existing link (never clobbers).
     */
    private fun maybeResolveHaLink() {
        if (config.mqttUser.isBlank() || config.mqttPassword.isBlank()) return
        // Re-resolve if never done OR the cached link is stale: HA's device id changes on a re-provision or
        // a device delete+recreate (with no panel_id change), which left "Open in HA" pointing at a deleted
        // device forever. TTL-gated so a flapping connection can't hammer HA's login.
        if (config.haDeviceUrl.isNotBlank() &&
            System.currentTimeMillis() - config.haLinkResolvedAt < HA_LINK_TTL_MS
        ) {
            return
        }
        Thread {
            // Prefer mDNS (broker-matched). Else derive HA from the broker HOST: a working broker is very
            // likely the HA server too, and reaching it by hostname works even across a tunnel where mDNS
            // can't (e.g. a remote panel). HaLink then reads /api/config for the canonical link URL.
            val base = discoverHaUrl() ?: brokerHttpsUrl() ?: return@Thread
            HaLink.resolve(base, config.mqttUser, config.mqttPassword, config.friendlyName)
                ?.let { config.setHaDeviceUrl(it) }
        }.start()
    }

    /** `https://<broker-host>` when the broker is a hostname (wildcard/SAN certs make HTTPS verifiable);
     *  null for a raw IP broker (cert would mismatch) or no broker. */
    private fun brokerHttpsUrl(): String? {
        val host = config.mqttBroker.substringAfter("://").substringBefore(":").substringBefore("/").trim()
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
        val led = config.lastLed.split(",").mapNotNull { it.toIntOrNull() }
        if (led.size == 5 && led[0] == 1) {
            val (_, br, r, g, b) = led
            val effect = LedEffects.Effect.from(config.lastLedEffect)
            if (effect != null) {
                ledEffect.start(effect, r, g, b, br)
            } else {
                this.led.setRgb(r * br / 255, g * br / 255, b * br / 255)
            }
        } else {
            // Force the hardware off too — the LED can power up to a default on reboot, so publishing
            // OFF without driving it leaves HA and the physical LED disagreeing (seen on rk3576).
            this.led.off()
        }
        config.lastButtonBacklight.takeIf { it >= 0 }?.let { HelperClient.send("BTN $it") }
    }

    // ---- command dispatch ----

    private fun onCommand(topic: String, payloadBytes: ByteArray, retained: Boolean) {
        val payload = String(payloadBytes)
        // NEVER act on a RETAINED command. Command topics are fire-and-forget; a retained payload is
        // always stale — e.g. a broker- or automation-retained screen-off replayed on every (re)subscribe,
        // which is exactly what stranded a panel dark after a reconnect. Our own state/discovery stays
        // retained; inbound commands must be fresh.
        if (retained) {
            Log.w(TAG, "ignoring RETAINED command on $topic (stale — not acted): $payload")
            return
        }
        try {
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
                cmdButtons -> handleButtons(payload)
                cmdBack -> NavActions.back(appCanSu)        // root keyevent or a11y; on the MQTT thread (ok to block)
                cmdRecents -> NavActions.recents(appCanSu)
                cmdNavbar -> handleNavbar(payload)
                cmdWakeOnWave -> handleWakeOnWave(payload)
                cmdTouchSound -> handleTouchSound(payload)
                cmdWatchdog -> handleWatchdog(payload)
                cmdKiosk -> handleKiosk(payload)
                cmdUpdateCompanion -> onUpdateCompanion() // install/update the Companion; runs off-thread in the service
                cmdCompanionAuto -> handleCompanionAuto(payload)
                cmdUpdatePaneld -> onSelfUpdate(true)      // force self-update to the channel's newest (off-thread)
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
        } catch (e: Exception) {
            Log.w(TAG, "command failed on $topic: $payload", e)
        }
    }

    /** Publish screen=ON to HA after a LOCAL wake (e.g. wake-on-wave), so `light.<panel>_screen` tracks
     *  reality instead of staying OFF. No-op if the broker isn't connected yet. */
    fun publishScreenOn() {
        publishScreenBrightness(brightness.getCommanded().coerceAtLeast(1))
    }

    /** Publish the current volume to HA after a LOCAL change (e.g. navbar Volume ±), so
     *  `number.<panel>_volume` tracks reality. No-op if the broker isn't connected yet. */
    fun publishVolume() {
        stateConverger.reconcile("volume", force = true)
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
        val json = JSONObject(payload)
        val on = json.optString("state", "ON").equals("ON", ignoreCase = true)
        if (!on) {
            ledEffect.stop()
            led.off()
            config.lastLed = "0,0,0,0,0"
            config.lastLedEffect = ""
            stateConverger.reconcile("led", force = true)
            return
        }
        // HA's built-in light `effect`; null = "none"/blank/absent = a plain solid colour.
        val effect = LedEffects.Effect.from(json.optString("effect"))
        // Fall back to the last solid colour/brightness when the command omits them — HA sends
        // {"state":"ON","effect":"strobe"} with no colour when you just pick an effect in the light card.
        val base = config.lastLed.split(",").mapNotNull { it.toIntOrNull() }.takeIf { it.size == 5 && it[0] == 1 }
        val br = if (json.has("brightness")) json.getInt("brightness") else base?.get(1) ?: 255
        val color = json.optJSONObject("color")
        val cr = color?.optInt("r", 255) ?: base?.get(2) ?: 255
        val cg = color?.optInt("g", 255) ?: base?.get(3) ?: 255
        val cb = color?.optInt("b", 255) ?: base?.get(4) ?: 255
        config.lastLed = "1,$br,$cr,$cg,$cb" // base colour, for restore + as the effect's colour
        if (effect != null) {
            ledEffect.start(effect, cr, cg, cb, br)
            config.lastLedEffect = effect.effectName
            stateConverger.reconcile("led", force = true)
        } else {
            ledEffect.stop()
            // Apply HA brightness as a scalar over the colour (json light sends them separately).
            led.setRgb(cr * br / 255, cg * br / 255, cb * br / 255)
            config.lastLedEffect = ""
            stateConverger.reconcile("led", force = true)
        }
    }

    private fun ledStateJson(br: Int, r: Int, g: Int, b: Int, effect: String = "none") =
        """{"state":"ON","color_mode":"rgb","brightness":$br,"color":{"r":$r,"g":$g,"b":$b},"effect":"$effect"}"""

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
        touchSound.set(on)
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
    fun publishKioskState(on: Boolean) = stateConverger.reconcile("kiosk_lock", force = true)

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

    private fun handleUpdateChannel(payload: String) {
        val was = config.updateChannel
        config.setUpdateChannel(payload.trim().trim('"'))
        val now = config.updateChannel
        stateConverger.reconcile("update_channel", force = true)
        // Apply the new channel now (when self-update is on). Switching pre-release → stable FORCES the
        // move onto stable even if that's a downgrade off the current rc (the deliberate exception to the
        // no-auto-downgrade rule); any other switch just takes the new channel's newest if it's newer.
        if (config.selfUpdate && now != was) onSelfUpdate(was == "prerelease" && now == "stable")
    }

    private fun handleCompanionChannel(payload: String) {
        val was = config.companionUpdateChannel
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
        bootChime.set(on)
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
        config.setZigbeeRouterEnabled(on) // persist desired state so it survives a reboot (boot-restore)
        zigbeeExecutor.execute {
            try {
                if (on) {
                    zigbee.enable()
                    for (i in 0 until 18) { if (zigbee.running()) break; Thread.sleep(5_000) }
                } else {
                    zigbee.disable() // blocks until the stack is down
                }
                stateConverger.reconcile("zigbee_router", force = true)
            } catch (e: Exception) {
                Log.w(TAG, "zigbee toggle failed", e)
            }
        }
    }

    // Boot/connect RECONCILE for the Zigbee router. Vendor firmware boot-starts the NSPanel Pro gateway
    // independently of us (and on 120P/3.7.1 the vendor guard CPU-spins), so we drive it to the user's
    // explicit choice on every connect: start it if they left it ON and nothing has; STOP it if they
    // turned it OFF (otherwise the vendor-started gateway returns each reboot). Gated on the switch having
    // been CONFIGURED — we never disable a stock vendor gateway by our default. Slow lifecycle off-thread.
    private fun reconcileZigbeeOnConnect() {
        if (!zigbee.present() || !config.zigbeeRouterConfigured) return
        val want = config.zigbeeRouterEnabled
        if (want == zigbee.running()) return // already in the desired state
        zigbeeExecutor.execute {
            try {
                zigbee.reconcile(want)
                if (want) for (i in 0 until 18) { if (zigbee.running()) break; Thread.sleep(5_000) }
                stateConverger.reconcile("zigbee_router", force = true)
                Log.i(TAG, "zigbee reconcile -> ${if (want) "on" else "off"}; running=${zigbee.running()}")
            } catch (e: Exception) {
                Log.w(TAG, "zigbee reconcile failed", e)
            }
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
        if (cpu.setTier(tier)) stateConverger.reconcile("cpu_governor", force = true)
    }

    // Persistent network adb (switch). Restarts adbd to apply; that only affects adb, not MQTT.
    private fun handleNetAdb(payload: String) {
        val on = payload.trim().equals("ON", ignoreCase = true)
        adb.set(on)
        stateConverger.reconcile("network_adb", force = true)
    }

    // Soft navbar mode (select). Persist + apply the overlay; publish the normalised mode back.
    private fun handleNavbar(payload: String) {
        navbar.apply(payload)
        config.setNavbarMode(navbar.mode)
        stateConverger.reconcile("navbar", force = true)
    }

    private fun handleHomeDashboard(payload: String) {
        val path = toLocalPath(payload) // normalise to a leading-slash local path (or "" to clear)
        config.setHomeDashboard(if (path == "/") "" else path)
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
            Thread {
                Thread.sleep(RELOAD_NAV_DELAY_MS)
                navigate.navigate("homeassistant://navigate$home")
                config.lastNavigate = home
                stateConverger.reconcile("navigate", force = true)
                Log.i(TAG, "reload -> re-navigated to intended dashboard $home")
            }.start()
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
     * Returns true if [key] is a recognised live setting (so the HTTP layer knows it was handled here
     * rather than via the static config setters + reconfigure()).
     */
    fun applySetting(key: String, value: String): Boolean {
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
            "companion_update_channel" -> handleCompanionChannel(value)
            "self_update" -> handleSelfUpdate(onOff)
            "webview_auto_update" -> handleWebViewAuto(onOff)
            "update_channel" -> handleUpdateChannel(value)
            "home_dashboard" -> handleHomeDashboard(value)
            else -> return false
        }
        return true
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
        publishState: () -> Unit,
    ) {
        // Honour the spec's per-setting default so a setting declared haExposedByDefault=false is
        // local-only (HTTP UI) until the user opts in via the expose pip — matching what the Configure
        // UI/schema shows. Falls back to true for keys with no registry spec (e.g. relay/button_led).
        // An UNAVAILABLE setting (availableWhen false — e.g. Companion auto-update with no Companion
        // installed) publishes the empty config, removing any stale HA entity, regardless of the pip.
        val spec = SettingsRegistry.spec(key)
        val default = spec?.haExposedByDefault ?: true
        val available = capabilities?.let { c -> spec?.availableWhen?.invoke(c()) } ?: true
        if (available && config.haExposed(key, default)) {
            publishConfig(component, objectId, payload())
            publishState()
        } else {
            publishConfig(component, objectId, "")
        }
    }

    private fun publishDiscovery() {
        // device.name = the configurable friendly name; entity names are the capability ONLY, so HA
        // composes a clean `<domain>.<panel>_<cap>` entity_id without doubling the panel id.
        // configuration_url -> HA renders a "Visit" link on the device page (the panel's info UI).
        val cu = if (!configUrl.isNullOrBlank()) ""","configuration_url":"$configUrl"""" else ""
        val name = jsonEsc(config.friendlyName)
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
            publishConfig(
                "event", "${panel}_button",
                """{"name":"Button","object_id":"${panel}_button","unique_id":"${panel}_button","state_topic":"$eventButton","event_types":["KEYCODE_POWER","KEYCODE_MUTE","KEYCODE_F","KEYCODE_F1","KEYCODE_F2","KEYCODE_F3","KEYCODE_F4","KEYCODE_BACK","KEYCODE_HOME","KEYCODE_DPAD_CENTER","KEYCODE_VOLUME_UP","KEYCODE_VOLUME_DOWN"],$avail,$device}""",
            )
        }
        // Nav actions work via root `input keyevent` (appCanSu) OR an enabled a11y service. Recents is
        // additionally gated on the firmware actually having an overview screen.
        if (appCanSu || buttonsEnabled) {
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
        if (zigbee.present()) {
            exposable("zigbee_router", "switch", "${panel}_zigbee_router", {
                """{"name":"Zigbee router","object_id":"${panel}_zigbee_router","unique_id":"${panel}_zigbee_router","command_topic":"$cmdZigbee","state_topic":"$stateZigbee","icon":"mdi:zigbee","entity_category":"config",$avail,$device}"""
            }) { stateConverger.reconcile("zigbee_router", force = true) }
        }

        // On-board relays (Smatek S9E `st_relay`). count() probes sysfs via su — off-main-thread here.
        val relays = relay.count()
        for (n in 1..relays) {
            publishConfig(
                "switch", "${panel}_relay$n",
                """{"name":"Relay $n","object_id":"${panel}_relay$n","unique_id":"${panel}_relay$n","command_topic":"ha-paneld/$panel/relay$n/set","state_topic":"ha-paneld/$panel/relay$n/state","icon":"mdi:electric-switch",$avail,$device}""",
            )
            stateConverger.reconcile("relay$n", force = true)
        }

        // S9E button LEDs (gpio147-150) — on/off lights, gated on the gpio nodes being present.
        val leds = relay.ledCount()
        for (n in 1..leds) {
            publishConfig(
                "light", "${panel}_button_led$n",
                """{"name":"Button LED $n","object_id":"${panel}_button_led$n","unique_id":"${panel}_button_led$n","command_topic":"ha-paneld/$panel/button_led$n/set","state_topic":"ha-paneld/$panel/button_led$n/state","icon":"mdi:led-on",$avail,$device}""",
            )
            stateConverger.reconcile("button_led$n", force = true)
        }

        // CPU governor (select) — su panels with cpufreq. Three intent-based tiers (Performance /
        // Efficiency / Auto) rather than raw kernel governor names; CpuController maps each to this
        // SoC's governor (Auto = its dynamic governor — ramps up on interaction, idles low).
        if (cpu.available()) {
            val opts = CpuController.TIERS.joinToString(",") { "\"${jsonEsc(it)}\"" }
            exposable("cpu_governor", "select", "${panel}_cpu_governor", {
                """{"name":"CPU profile","object_id":"${panel}_cpu_governor","unique_id":"${panel}_cpu_governor","command_topic":"$cmdCpuGov","state_topic":"$stateCpuGov","options":[$opts],"icon":"mdi:speedometer","entity_category":"config",$avail,$device}"""
            }) { cpu.currentTier()?.let { lastPublishedGovTier = it }; stateConverger.reconcile("cpu_governor", force = true) }
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
        if (adb.available()) {
            exposable("network_adb", "switch", "${panel}_network_adb", {
                """{"name":"Network ADB","object_id":"${panel}_network_adb","unique_id":"${panel}_network_adb","command_topic":"$cmdNetAdb","state_topic":"$stateNetAdb","icon":"mdi:adb","entity_category":"config",$avail,$device}"""
            }) { stateConverger.reconcile("network_adb", force = true) }
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
        publish(topic, withDefaultEntityId(component, objectId, payload), retain = false)
    }

    /**
     * The historical SUPERSET of every entity ha-paneld has ever published for a panel — the tombstone
     * list. KEEP entities here even after they're removed from [publishDiscovery], so an upgrade can
     * actively clear a now-refactored-away entity (see [pruneStaleDiscovery]) instead of zombie-ing it.
     */
    private fun knownConfigTopics(): List<String> = listOf(
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
        "button" to "${panel}_reload", "button" to "${panel}_reboot",
        "button" to "${panel}_launcher", "button" to "${panel}_home",
        "button" to "${panel}_admin_launcher",
    ).map { (comp, obj) -> "homeassistant/$comp/$obj/config" }

    /**
     * Clear this panel's discovery (empty retained payload per topic) so a panel_id change doesn't leave
     * an orphan device in HA. Clears the full known superset for the (old) id.
     */
    fun clearDiscovery() {
        knownConfigTopics().forEach { publish(it, "", retain = true) }
    }

    /**
     * Active upgrade migration: clear any KNOWN entity we did NOT publish this session — i.e. one a prior
     * version announced but this version refactored away (or a now-absent capability) — so it's removed
     * from HA instead of lingering as a zombie. Called once after an upgrade (version change). Empty
     * retained payload also clears configs an older, retain=true version left on the broker.
     */
    private fun pruneStaleDiscovery() {
        val published = publishedConfigTopics.toSet()
        var n = 0
        knownConfigTopics().forEach { if (it !in published) { publish(it, "", retain = true); n++ } }
        Log.i(TAG, "discovery prune: cleared $n refactored-away/absent entities for $panel")
    }

    private fun publish(
        topic: String,
        payload: String,
        retain: Boolean = false,
        onComplete: ((Boolean) -> Unit)? = null,
    ) {
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
        if (state == "disabled") return
        runCatching { publish("ha-paneld/$panel/last_seen", (System.currentTimeMillis() / 1000).toString()) }
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
            if (cpu.available()) {
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
        "${it.channels} channels · ${it.dirty} dirty · ${it.inFlight} in-flight · ${it.unknown} unknown"
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

    @Synchronized
    fun stop() {
        ButtonBus.listener = null
        PanelStatus.mqttConnected = false
        zigbeeExecutor.shutdownNow()
        runCatching {
            publish(availabilityTopic, "offline", retain = true)
            transport.disconnectDetached()
        }
    }

    companion object {
        private const val TAG = "ha-paneld/mqtt"
        private const val HA_LINK_TTL_MS = 6 * 3_600_000L // re-resolve the "Open in HA" link at most every 6h

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
    }
}
