package io.github.maxlyth.hapaneld

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.maxlyth.hapaneld.control.AdbController
import io.github.maxlyth.hapaneld.device.DeviceProfile
import io.github.maxlyth.hapaneld.input.EvdevButtonClient
import io.github.maxlyth.hapaneld.control.AutoBrightnessController
import io.github.maxlyth.hapaneld.control.BootChimeController
import io.github.maxlyth.hapaneld.control.BrightnessController
import io.github.maxlyth.hapaneld.control.CpuController
import io.github.maxlyth.hapaneld.control.NavbarController
import io.github.maxlyth.hapaneld.control.PowerController
import io.github.maxlyth.hapaneld.control.NavigateController
import io.github.maxlyth.hapaneld.control.RelayController
import io.github.maxlyth.hapaneld.control.OverlayWakeTap
import io.github.maxlyth.hapaneld.control.ScreenController
import io.github.maxlyth.hapaneld.control.SystemController
import io.github.maxlyth.hapaneld.control.TameController
import io.github.maxlyth.hapaneld.control.KioskController
import io.github.maxlyth.hapaneld.control.LedEffectController
import io.github.maxlyth.hapaneld.control.WatchdogController
import io.github.maxlyth.hapaneld.control.TouchSoundController
import io.github.maxlyth.hapaneld.control.VolumeController
import io.github.maxlyth.hapaneld.control.ZigbeeController
import io.github.maxlyth.hapaneld.hardware.LedController
import io.github.maxlyth.hapaneld.hardware.LedFactory
import io.github.maxlyth.hapaneld.hardware.Rk3576LedController
import io.github.maxlyth.hapaneld.hardware.SocketLedController
import io.github.maxlyth.hapaneld.config.Capabilities
import io.github.maxlyth.hapaneld.http.PaneldServer
import io.github.maxlyth.hapaneld.http.PanelInfo
import io.github.maxlyth.hapaneld.logship.LogCapture
import io.github.maxlyth.hapaneld.logship.LogShipper
import io.github.maxlyth.hapaneld.sensors.SensorReporter
import io.github.maxlyth.hapaneld.util.localIpv4
import io.github.maxlyth.hapaneld.util.localIpv6
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import io.github.maxlyth.hapaneld.util.Serializer
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import io.github.maxlyth.hapaneld.util.UpdateChecker
import io.github.maxlyth.hapaneld.util.CompanionInstaller
import io.github.maxlyth.hapaneld.util.InstallProgress
import io.github.maxlyth.hapaneld.util.SelfUpdater
import io.github.maxlyth.hapaneld.util.WebViewInstaller
import io.github.maxlyth.hapaneld.mqtt.ConnectionSupervisor
import io.github.maxlyth.hapaneld.platform.AndroidScreenPower
import io.github.maxlyth.hapaneld.platform.AndroidSystemEnv
import io.github.maxlyth.hapaneld.util.periodic
import io.github.maxlyth.hapaneld.util.SystemProps

/**
 * Persistent foreground service. Hosts the Ktor HTTP listener, the JmDNS advertiser, the MQTT
 * control bridge and the hardware controllers for the panel's lifetime. Declared
 * `foregroundServiceType=specialUse` because a wall-panel on-LAN agent has no analogue among the
 * predefined FGS types.
 *
 * Critically: this service draws no UI and never takes HOME foreground — the HA Companion app's
 * WebView stays the visible launcher throughout, matching the bash reference behaviour.
 */
class PaneldService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Serializes reconfigure() — it stops + rebuilds + restarts the MQTT/mDNS stack, and two overlapping
    // runs (e.g. a quick resubmit of the config form) would interleave stop/build/start and leave the
    // bridge stopped or half-built. Each reconfigure runs atomically; queued ones re-apply the (now
    // identical) latest config harmlessly. No-interleave property is regression-tested in SerializerTest.
    private val reconfigurer = Serializer(scope)
    private lateinit var config: Config
    private lateinit var server: PaneldServer
    private lateinit var mdns: MdnsAdvertiser
    private lateinit var mqtt: MqttBridge
    // Default-network callback that nudges an MQTT reconnect when the network returns (see registerNetworkCallback).
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    // MQTT watchdog runs on a DEDICATED thread (not Dispatchers.IO), so slow/contended su can't starve it.
    @Volatile private var mqttWatchdogAlive = false
    private lateinit var sensors: SensorReporter
    private lateinit var logShipper: LogShipper
    private lateinit var logCaptureApp: LogCapture
    private lateinit var logCaptureSystem: LogCapture

    // Controllers are fields so the MQTT bridge can be rebuilt on a panel_id change.
    private lateinit var brightness: BrightnessController
    private lateinit var autoBright: AutoBrightnessController
    private lateinit var screen: ScreenController
    private lateinit var led: LedController
    // Effect loop for the LED — owned here (not the MQTT bridge) so a bridge rebuild never orphans it.
    private lateinit var ledEffect: LedEffectController
    private lateinit var navigate: NavigateController
    private lateinit var volume: VolumeController
    private lateinit var system: SystemController
    private lateinit var tame: TameController
    private lateinit var navbar: NavbarController
    private lateinit var watchdog: WatchdogController
    private lateinit var kiosk: KioskController
    private lateinit var touchSound: TouchSoundController
    private lateinit var bootChime: BootChimeController
    private lateinit var zigbee: ZigbeeController
    private lateinit var relay: RelayController
    private lateinit var cpu: CpuController
    private lateinit var adb: AdbController
    private lateinit var power: PowerController
    private lateinit var profile: DeviceProfile
    private var configUrl: String? = null
    // One-time-start guard for onStartCommand (see there for why). Reset in onDestroy.
    @Volatile private var started = false

    override fun onCreate() {
        super.onCreate()
        config = Config(this)
        config.migrateLiveStore()   // carry persisted settings across a schema bump before anything reads them
        sensors = SensorReporter(this, config)
        // Detect the device profile once and hand it to the hardware-specific controllers (instead of
        // each re-detecting). The canonical per-platform silo for paths/quirks; see device/.
        profile = DeviceProfile.detect()
        config.attachProfile(profile)   // supplies per-panel manufacturer/model defaults
        // Shared demand-driven logcat captures (one subprocess + one redaction pass each): the app
        // source feeds both remote shipping and the :8888 live log viewer; the system source (su)
        // only the viewer. Idle-stopped — no subprocess runs until something subscribes.
        logCaptureApp = LogCapture.app(scope)
        logCaptureSystem = LogCapture.system(scope)
        // Optional remote log shipping (off + inert unless a sink host is configured). Started in
        // onStartCommand alongside the other network subsystems; restarted on a /config change.
        logShipper = LogShipper(config, scope, logCaptureApp)

        brightness = BrightnessController(this)
        brightness.applyPreventIdleDim(config.preventIdleDim, config)
        autoBright = AutoBrightnessController(brightness, config)
        screen = ScreenController(brightness, AndroidScreenPower(this), wakeTap = OverlayWakeTap(this))
        // After a LOCAL touch-wake, tell HA the screen is on so `light.<panel>_screen` tracks reality.
        screen.onWakeByTap = { runCatching { mqtt.publishScreenOn() } }
        led = LedFactory.detect(profile)
        ledEffect = LedEffectController(led)
        navigate = NavigateController(this)
        volume = VolumeController(this)
        system = SystemController(AndroidSystemEnv(this))
        watchdog = WatchdogController(system, config)
        kiosk = KioskController(this, system, config)
        // On-device unlock gesture (7 corner taps): persist OFF + clear the lock + tell HA — off the main
        // thread (the gesture fires on the overlay's touch listener; root + HiveMQ must not run there).
        kiosk.onUnlockRequested = {
            Thread {
                config.setKioskLock(false)
                runCatching { kiosk.apply(false) }
                runCatching { mqtt.publishKioskState(false) }
            }.apply { isDaemon = true; name = "kiosk-unlock" }.start()
        }
        // When taming disables a vendor home launcher (e.g. eWeLink), immediately re-assert the dashboard
        // as home and foreground it — otherwise the panel sits on our admin launcher until the 300s
        // watchdog returns to the dashboard (a ~5-minute gap on boot). Off-main is already guaranteed:
        // tame() only ever runs inside a scope.launch / worker thread.
        tame = TameController(this) {
            system.ensureDashboardHome(config.dashboardPackage, config.haUrl.isNotBlank())
            system.launchHome(config.dashboardPackage)
        }
        // Tame opt-in: neutralise the vendor packages the user listed (force-stop + disable boot-relaunch
        // + strip the overlay permission). No-op when the blocklist is empty (the default — a stock panel
        // is never touched); run off-main since pm/am are slow and this is a boot-time one-shot. Critical
        // packages are refused by TameController and the daemon backstop.
        config.tameVendorPackages.takeIf { it.isNotEmpty() }?.let { pkgs ->
            scope.launch { tame.applyBlocklist(pkgs) }
        }
        // Re-assert the dashboard (HA Companion) as the default home. Our admin launcher declares
        // CATEGORY_HOME, and Android clears the default-home association on each install/update of a
        // HOME app — so without this, Home would pop a chooser instead of booting to the dashboard.
        // No-op unless home was cleared (or is us); off-main as it may call su. See ensureDashboardHome.
        scope.launch { system.ensureDashboardHome(config.dashboardPackage, config.haUrl.isNotBlank()) }
        // The navbar gets the CONFIGURED dashboard value (may be the "builtin" sentinel), never
        // dashboardTarget(): that resolves builtin to our own package name (for perf attribution), and
        // reloadDashboard(ownPackage) would take the foreign-app path — `am force-stop` on ourselves.
        navbar = NavbarController(
            this, system, volume, brightness, { config.launcherPackage }, { config.dashboardPackage },
            profile.appCanSu, profile.hasRecents,
            onBrightnessChanged = { mqtt.publishScreenOn() },
            onVolumeChanged = { mqtt.publishVolume() },
        )
        touchSound = TouchSoundController(this)
        // Re-apply at boot: the switch raises the system-stream volume only when toggled, so a panel that
        // booted with touch-sound already on would otherwise stay silent (volume left at 0).
        if (touchSound.isEnabled()) touchSound.set(true)
        bootChime = BootChimeController(this, config)
        bootChime.applyPersisted()
        zigbee = ZigbeeController(profile)
        relay = RelayController(profile)
        cpu = CpuController(profile)
        adb = AdbController(config)
        power = PowerController(this)
        configUrl = localIpv4()?.let { "http://$it:${config.httpPort}/" }

        mqtt = buildMqtt()
        mdns = MdnsAdvertiser(this, config)
        server = PaneldServer(
            config, cacheDir, scope, this, sensors, system, volume, ::reconfigure,
            // Capture the field (not the current instance) so it always targets the live bridge,
            // which reconfigure() rebuilds on a panel_id / MQTT change.
            { k, v -> mqtt.applySetting(k, v) },
            // Controller-sourced setting values (their state isn't in SharedPreferences) so the
            // config form/schema/dashboard show live truth. Called on Ktor IO threads (su-safe).
            liveValues = {
                mapOf(
                    "touch_sound" to touchSound.isEnabled().toString(),
                    "cpu_governor" to (cpu.currentTier() ?: "Auto"),
                    "network_adb" to adb.isPersisted().toString(),
                    "zigbee_router" to config.zigbeeRouterEnabled.toString(),
                )
            },
            capabilities = ::capabilitiesSnapshot,
            info = ::panelInfo,
            recommendedDensity = profile.recommendedDensity,
            recommendedFontScale = profile.recommendedFontScale,
            // Vendor taming: the controller and this panel's curated recommendations (picker group 1).
            tame = tame, tameProfileCandidates = profile.tameVendorCandidates,
            // Live log viewer sources (Logs tab). System is gated on Su.available() per request.
            logApp = logCaptureApp, logSystem = logCaptureSystem,
            effectiveBrightness = { brightness.getBrightness() },
            onHealWebView = { healWebView() },
            onRepairCompanionUrl = { repairCompanionUrl() },
            onInstallComponent = { name, action, version -> installComponent(name, action, version) },
            // One-line EFR32 radio status for the Install-tab Radio card; null when this panel has no radio.
            radioStatus = { if (profile.zigbeeGatewayDir != null) zigbee.status() else null },
            // LAN ha-paneld peers over mDNS for the header panel switcher. Captures the `mdns` FIELD (not a
            // snapshot) so it follows reconfigure()'s reassignment; browsePeers null-guards the swap window.
            peers = { mdns.browsePeers() },
        )
        // Stream daemon-instrumented hardware buttons (e.g. WF1589T power key) into the same event
        // entity as the a11y key capture. No-op on panels with no evdev buttons.
        EvdevButtonClient.start(profile.evdevButtons)
    }

    private fun buildMqtt(): MqttBridge = MqttBridge(
        config, brightness, screen, led, ledEffect, navigate, volume, system, navbar, watchdog, kiosk, touchSound, bootChime, zigbee, relay, cpu, adb,
        accessibilityEnabled(), profile.evdevButtons.isNotEmpty(),
        { capabilitiesSnapshot() },
        sensors.hasLight(), sensors.hasProximity(),
        sensors.hasTemperature(), sensors.hasHumidity(),
        profile.hasCht8305,
        // Button backlight lives on the sysfs/daemon LED panels (TPA10), reached via the daemon's BTN.
        led is SocketLedController,
        profile.appCanSu, profile.hasRecents,
        autoBright, configUrl,
        // When no broker is configured, find HA on the LAN via mDNS and default to its :1883.
        discoverHaIp = { mdns.discoverHaIp() },
        // HA's advertised base URL (from zeroconf) for the "Open in HA" device link.
        discoverHaUrl = { mdns.discoverHaBaseUrl() },
        // Companion install/update button → run off-thread (network + su).
        onUpdateCompanion = {
            scope.launch {
                val r = CompanionInstaller.installOrUpdate(this@PaneldService, force = true, channel = config.companionUpdateChannel, maxVersion = profile.companionMaxVersion)
                Log.i(TAG, "Companion manual update: $r")
            }
        },
        // ha-paneld self-update (off-thread): force=true from the update_paneld button + a pre-release→
        // stable channel switch; force=false lets isNewer gate (no auto-downgrade off an rc).
        onSelfUpdate = { force ->
            scope.launch {
                val r = SelfUpdater.checkAndUpdate(this@PaneldService, config.updateChannel, force)
                Log.i(TAG, "self-update (force=$force): $r")
            }
        },
    )

    /**
     * Apply config the HTTP page has already written to [config]: clear the OLD discovery (the live
     * MQTT bridge still holds the old panel id), then rebuild MQTT + mDNS from the new config.
     */
    private fun reconfigure() {
        // Atomic: never let a second reconfigure interleave with this one's stop/build/start (see Serializer).
        reconfigurer.launch {
            runCatching { mqtt.clearDiscovery() } // bridge was built with the previous panel id
            runCatching { mqtt.stop() }
            runCatching { mdns.stop() }
            mqtt = buildMqtt()
            mdns = MdnsAdvertiser(this@PaneldService, config)
            mdns.start()
            mqtt.start()
            // Re-read the log sink (host/port/protocol/enabled); restarts only if it changed.
            runCatching { logShipper.reconfigure() }
            runCatching { power.apply(config.keepAwake) }   // apply a keep_awake toggle live
            io.github.maxlyth.hapaneld.http.PerfReader.dashboardPkg = dashboardTarget()
            Log.i(TAG, "reconfigured: panel=${config.panelId} broker=${config.mqttBroker.ifEmpty { "(disabled)" }}")
        }
    }

    /** Ordered facts for the info page (`GET /`). */
    private fun panelInfo(): Map<String, String> {
        // activeBroker reflects auto-discovery (tcp://<ha-ip>:1883) when no broker is configured.
        val broker = mqtt.activeBroker.ifBlank { config.mqttBroker }
        val host = broker.substringAfter("://").substringBefore(":").ifBlank { "?" }
        val auto = config.mqttBroker.isBlank() && mqtt.activeBroker.isNotBlank()
        val mqttStatus = when (mqtt.state) {
            "connected" -> "$host · connected" + (if (auto) " (auto)" else "")
            // Only claim "credentials rejected" when the rejection is PERSISTENT (no broker-ACKed
            // activity for a while). A transient NOT_AUTHORIZED during a broker/HA restart — or a
            // reconnect racing its predecessor — self-heals within a watchdog cycle, and flashing a
            // scary "check username/password" banner for that erodes trust in a working setup.
            "auth-failed" ->
                if (mqtt.lastOkMs != 0L && mqtt.msSinceLastOk() < AUTH_PERSIST_MS)
                    "$host · reconnecting…"
                else "$host · reachable, auth rejected — check username/password"
            "unreachable" -> "$host · unreachable"
            "connecting" -> "$host · connecting…"
            else -> "disabled"
        }
        // Variant + firmware from ro.product.version (e.g. "NSPanel120P_3.7.1" -> "NSPanel 120P · fw 3.7.1").
        // This is the authoritative model/generation key — distinguishes 86P / 120P / 86P-Gen2 and the
        // firmware that drives the proximity-reporting + zigbee-layout quirks.
        val pv = SystemProps.get("ro.product.version")
        val modelRow = if (pv.isNotEmpty()) {
            val m = pv.substringBefore('_').replace("NSPanel", "NSPanel ").trim()
            val fw = pv.substringAfter('_', "")
            m + (if (fw.isNotEmpty()) " · fw $fw" else "")
        } else "${profile.displayName}"
        val extras = linkedMapOf(
            "panel_id" to config.panelId,
            "Friendly name" to config.friendlyName,
            "HTTP port" to config.httpPort.toString(),
            "Local IP" to (localIpv4() ?: "?"),
            "Local IPv6" to (localIpv6() ?: "—"),
            "MQTT" to mqttStatus,
            // Host-free state + liveness age + family preference. Deliberately separate from "MQTT"
            // (which carries the broker host and is omitted from /diag): this row IS included in a
            // /diag dump, so a pasted report finally answers "is this panel broker-connected?".
            "MQTT state" to mqtt.statusPublic(),
            // Wakelock/Wi-Fi-lock intent vs reality — a panel that should be keep-awake but isn't
            // holding the lock is a strong hint for stalled-idle-connection reports.
            "Keep awake" to if (config.keepAwake) (if (power.isHeld()) "on · wakelock held" else "on · wakelock NOT held") else "off",
            "mDNS" to "${config.panelId} ${Config.MDNS_SERVICE_TYPE}",
            "Platform" to "${profile.displayName} · ${profile.socClass}",
            "Model" to modelRow,
            "LED" to ledLabel(),
            "Light sensor" to sensorRow(sensors.hasLight(), profile.lightTech, sensors.lightDesc()),
            "Proximity" to sensorRow(sensors.hasProximity(), profile.proximityTech, sensors.proximityDesc()),
            // a11y service = software back/recents nav, NOT physical buttons (NSPanel Pro has none).
            "Nav actions (a11y)" to yesNo(accessibilityEnabled()),
            // Soft navbar overlay mode + whether the overlay can actually be drawn (SYSTEM_ALERT_WINDOW).
            "Navbar" to (config.navbarMode + if (config.navbarMode != "Off" && !canDrawOverlays()) " · no overlay permission" else ""),
            // Zigbee EFR32 state (NSPanel Pro only; "none" elsewhere). Calls su — fine here because
            // the info page is served off the main thread.
            "Zigbee" to zigbee.status(),
            "Relays" to relay.count().let { if (it > 0) it.toString() else "none" },
            "CPU profile" to (cpu.currentTier() ?: "n/a"),
            "Network ADB" to when {
                adb.isPersisted() -> "persistent (5555) · re-asserted by ha-paneld at boot"
                adb.isActive() -> "active (5555) · external — not persisted by ha-paneld"
                else -> "off"
            },
            "Log shipping" to logShipper.statusText(),
        )
        // Recent "changed outside MQTT" events (brightness/volume/backlight/governor) — shown only when
        // something has actually synced, so it doesn't clutter a steady panel. Flows to /diag too.
        mqtt.recentSyncEvents().takeIf { it.isNotEmpty() }?.let { extras["Local-state sync"] = it.joinToString(" · ") }
        return PanelInfo.collect(this, extras)
    }

    private fun ledLabel(): String = when {
        !led.available() -> "none"
        led is Rk3576LedController -> "Rockchip /dev/ledjni (RGB)"
        led is SocketLedController -> "sysfs helper daemon (RGB)"
        led.colorCapable() -> "RGB"
        else -> "brightness"
    }

    private fun yesNo(b: Boolean) = if (b) "yes" else "no"

    /** "no", or "yes" with any declared technology + runtime value-type/range appended ("yes · Infrared ·
     *  Binary · near/far (0 / 5 cm)"). */
    private fun sensorRow(present: Boolean, tech: String?, desc: String?): String =
        if (!present) "no" else "yes" + listOfNotNull(tech, desc).joinToString("") { " · $it" }

    // zigbee.present() costs a su exec, so probe it once (lazily, off the main thread — first caller
    // is an HTTP handler or MQTT connect, both on background threads) and reuse the answer.
    private val zigbeePresent: Boolean by lazy { zigbee.present() }

    /** This panel's capability snapshot for the settings registry's availableWhen gates
     *  (the Configure form/schema + the dashboard's read-only values card). */
    private fun capabilitiesSnapshot(): Capabilities = Capabilities(
        hasProximity = sensors.hasProximity(),
        hasLight = sensors.hasLight(),
        hasTemperature = sensors.hasTemperature(),
        hasHumidity = sensors.hasHumidity(),
        hasCht8305 = profile.hasCht8305,
        appCanSu = profile.appCanSu,
        hasRecents = profile.hasRecents,
        cpuGovernors = cpu.available(),
        networkAdb = adb.available(),
        zigbeePresent = zigbeePresent,
        hasSystemDarkMode = Build.VERSION.SDK_INT >= 29,   // Android 10+ has the system dark/light setting
        companionInstalled = UpdateChecker.COMPANION_PKGS.any {
            runCatching { packageManager.getPackageInfo(it, 0) }.isSuccess
        },
    )

    /** WebView auto-heal (Install-tab "Update WebView now" button): download + install the profile's
     *  recommended System WebView, then reload the dashboard so the new provider takes effect. force=true
     *  (the user asked); off-thread (large download + su). */
    private fun healWebView() {
        scope.launch {
            val r = io.github.maxlyth.hapaneld.util.WebViewInstaller.heal(this@PaneldService, profile, engineMajor = null, force = true)
            Log.i(TAG, "WebView heal: $r")
            if (r.startsWith("OK")) {
                kotlinx.coroutines.delay(2_000)
                if (config.dashboardPackage == io.github.maxlyth.hapaneld.control.SystemController.BUILTIN_DASHBOARD) {
                    // A WebView provider binds per-process at first use, so a page reload can't swap it
                    // for OUR OWN renderer — only a process restart can. Exit cleanly: START_STICKY
                    // brings the service back and Android relaunches the (HOME) dashboard activity,
                    // both on the freshly-installed provider. (Foreign renderers get the same effect
                    // from reloadDashboard's force-stop below.)
                    Log.i(TAG, "WebView healed — restarting process so the built-in renderer binds the new provider")
                    kotlinx.coroutines.delay(1_000) // let the HTTP response + this log flush
                    kotlin.system.exitProcess(0)
                }
                // A foreign renderer (Companion) picks up the new provider on ITS process restart — reload it.
                system.reloadDashboard(config.dashboardPackage)
            }
        }
    }

    /** Companion internal_url repair (Install-tab button): copy each server's external_url into a blank
     *  internal_url so HA 2026.7 stops rejecting the dashboard with "Missing 'Host' header". Off-thread
     *  (su force-stops + relaunches the Companion). */
    private fun repairCompanionUrl() {
        scope.launch {
            val r = io.github.maxlyth.hapaneld.control.CompanionDb.repairInternalUrl(
                this@PaneldService,
                io.github.maxlyth.hapaneld.control.Su,
            )
            Log.i(TAG, "Companion internal_url repair: $r")
        }
    }

    /** Install/update a managed component from the Install tab (POST /api/v1/install/component). Runs
     *  off-thread; progress is reported via InstallProgress so the web UI can poll. action="reinstall"
     *  forces even when the installed build is already current. Single-slot (InstallProgress.start gates). */
    private fun installComponent(name: String, action: String, version: String) {
        val label = when (name) {
            "paneld" -> "ha-paneld"; "companion" -> "HA Companion"; "webview" -> "System WebView"; else -> name
        }
        if (!InstallProgress.start(label)) return // another install is already running
        val force = action == "reinstall"
        val tag = version.takeIf { it.isNotBlank() }
        scope.launch {
            val r = try {
                when (name) {
                    // A specific picked version installs that exact tag; otherwise the channel's newest.
                    "paneld" -> if (tag != null) SelfUpdater.installVersion(this@PaneldService, tag)
                        else SelfUpdater.checkAndUpdate(this@PaneldService, config.updateChannel, force = force)
                    "companion" -> if (tag != null) CompanionInstaller.installVersion(this@PaneldService, tag)
                        else CompanionInstaller.installOrUpdate(this@PaneldService, force = force, channel = config.companionUpdateChannel)
                    "webview" -> WebViewInstaller.heal(this@PaneldService, profile, engineMajor = null, force = true).also {
                        // The Companion picks up a new WebView provider only on process restart — reload it.
                        if (it.startsWith("OK")) { kotlinx.coroutines.delay(2_000); system.reloadDashboard(config.dashboardPackage) }
                    }
                    else -> "unknown component"
                }
            } catch (e: Exception) {
                Log.w(TAG, "install $name failed", e); "error: ${e.message}"
            }
            Log.i(TAG, "install $name: $r")
            InstallProgress.finish(r)
            // Refresh the available-update list so the banner + Install tab reflect the new state.
            runCatching { UpdateChecker.check(this@PaneldService, config.updateChannel) }
        }
    }

    /** Smoothness-metrics target: the configured override, else the installed HA Companion app
     *  (this is an HA project — the dashboard is the Companion app, so no config needed normally). */
    private fun dashboardTarget(): String {
        // Built-in renderer: the WebView runs in our own process, so smoothness metrics target us.
        if (config.dashboardPackage == SystemController.BUILTIN_DASHBOARD) return packageName
        config.dashboardPackage.takeIf { it.isNotBlank() }?.let { return it }
        for (p in listOf("io.homeassistant.companion.android.minimal", "io.homeassistant.companion.android")) {
            if (runCatching { packageManager.getPackageInfo(p, 0) }.isSuccess) return p
        }
        return ""
    }

    /** Advertise the button-event entity only if our a11y service is actually enabled. */
    private fun accessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
        return enabled?.contains(packageName) == true
    }

    /** Whether SYSTEM_ALERT_WINDOW is held — required to draw the soft-navbar overlay. */
    private fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(this)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        // Start subsystems once. Android re-delivers onStartCommand on every startForegroundService()
        // re-issue and on START_STICKY re-create; re-running this block would call server.start() again,
        // binding a second Ktor server on :8888 -> BindException crashes the process (and would also
        // double-start mqtt/mdns/sensors). started is reset in onDestroy so a genuine restart re-inits.
        if (started) return START_STICKY
        started = true
        // Keep the SoC + network awake (screen still free to sleep) so Doze/suspend can't freeze the
        // MQTT reactor + keepalive into a half-open, unreachable connection. On by default; see keep_awake.
        runCatching { power.apply(config.keepAwake) }
        // Cache HA's frontend URL from the Companion (its internal/external_url) so the header "Open in HA"
        // button always has a target — even when the panel's own device-page URL hasn't resolved (e.g. a
        // remote panel over a tunnel). Root sqlite read, off the main thread; best-effort.
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                io.github.maxlyth.hapaneld.control.CompanionDb.serverUrl(this@PaneldService, io.github.maxlyth.hapaneld.control.Su)
            }.getOrNull()?.let { config.setHaBaseUrl(it) }
        }
        scope.launch {
            io.github.maxlyth.hapaneld.http.PerfReader.dashboardPkg = dashboardTarget()
            io.github.maxlyth.hapaneld.http.PerfReader.start(scope)
            server.start()
            mdns.start()
            mqtt.start()
            // Forward our own logcat to the configured aggregator (no-op unless a sink host is set).
            logShipper.start()
            // Restore the soft navbar to its persisted mode (no-op when Off / no overlay permission).
            navbar.apply(config.navbarMode)
            // Start the app watchdog if enabled (off by default; self-heals a dead/abandoned dashboard).
            watchdog.apply(config.watchdogEnabled)
            // Experimental kiosk lock: a reboot CLEARS the runtime lock (by design — the anti-brick net), so
            // re-assert it after a delay if it was enabled. The delay leaves an unlocked window each boot so
            // an admin is never stranded; skipped if it was turned off (corner gesture / :8888 / HA) meanwhile.
            if (config.kioskLock) Thread {
                try { Thread.sleep(KIOSK_REASSERT_MS) } catch (e: InterruptedException) { return@Thread }
                if (config.kioskLock) runCatching { kiosk.apply(true) }
            }.apply { isDaemon = true; name = "kiosk-reassert" }.start()
            // Boot re-assert of network adb — some firmwares strip persist.adb.tcp.port at boot, so
            // re-apply it when ha-paneld is persisting it (no-op otherwise). See AdbController.reassert.
            runCatching { adb.reassert() }
            sensors.start(
                onLux = { lux -> mqtt.publishLight(lux) },
                onLuxRaw = { lux -> autoBright.submitLux(lux) },
                onProximity = { near ->
                    mqtt.publishProximity(near)
                    // Wake-on-wave: local, instant, wake-only. onProximity fires only on far->near
                    // transitions (natural debounce); sleep stays HA's job. Publish the ON state so the
                    // HA screen entity tracks the local wake (GitHub #6 — was staying OFF in HA).
                    // screen.wake() calls Su.run() — must NOT run on the main thread (ANR risk when
                    // su is under load during the proximity callback, which delivers on the main looper).
                    if (near && config.wakeOnWave) Thread { screen.wake(); mqtt.publishScreenOn() }.start()
                },
                onTemperature = { c -> mqtt.publishTemperature(c) },
                onHumidity = { h -> mqtt.publishHumidity(h) },
            )
            periodic(
                intervalMs = 24 * 3_600 * 1_000L,
                initialDelayMs = 30_000L, // let startup settle before hitting the network
                tag = TAG,
                name = "update-check",
            ) {
                // Each sub-step is isolated so one failing doesn't skip the others; the periodic boundary
                // is the outer net that keeps the loop alive across an unexpected throw.
                runCatching { UpdateChecker.check(this@PaneldService, config.updateChannel) }
                // Companion self-heal: when enabled, install a missing Companion / update an out-of-date one.
                if (config.companionAutoUpdate) {
                    runCatching {
                        val r = CompanionInstaller.installOrUpdate(this@PaneldService, channel = config.companionUpdateChannel, maxVersion = profile.companionMaxVersion)
                        Log.i(TAG, "Companion auto: $r")
                    }
                }
                // ha-paneld self-update LAST — a successful install restarts this process (and this loop).
                if (config.selfUpdate) {
                    runCatching {
                        val r = SelfUpdater.checkAndUpdate(this@PaneldService, config.updateChannel)
                        Log.i(TAG, "self-update auto: $r")
                    }
                }
            }
            startMqttWatchdog()
            // Never-blank-screen watchdog. A screen-off kills the backlight but leaves the device
            // interactive, and nothing re-lights it — so a stray/stale screen-off (e.g. the retained-command
            // strand) or a firmware idle-dim can leave the panel dark and apparently bricked. If the screen
            // is dark but ha-paneld did NOT deliberately turn it off, re-light it; a user-intended
            // "screen off" (isIntendedOff) is left alone.
            periodic(
                intervalMs = SCREEN_WATCHDOG_MS,
                initialDelayMs = 15_000L, // let boot settle before the first check
                tag = TAG,
                name = "never-blank",
            ) {
                if (!screen.isIntendedOff() && screen.looksDark()) {
                    Log.w(TAG, "screen dark with no intent — re-lighting (never-blank guard)")
                    screen.wake()
                    mqtt.publishScreenOn()
                }
            }
        }
        registerNetworkCallback()
        return START_STICKY
    }

    /**
     * MQTT reconnect watchdog on a DEDICATED thread — deliberately NOT a coroutine on Dispatchers.IO.
     * On a panel with slow/contended `su` (toolbox su under load), blocking su calls exhaust the IO
     * thread pool, so a coroutine watchdog's post-delay continuation never gets scheduled and it silently
     * stops ticking (observed in the field: MQTT "connected" but zero watchdog ticks, so a
     * half-open connection never self-healed). A plain thread ticks regardless of dispatcher pressure.
     *
     * Two stall modes, both healed by a full client rebuild:
     *   (1) STATE-stuck — HiveMQ's auto-reconnect stalls (transient auth reject on an HA/broker restart,
     *       or its reconnect thread is power-management-deferred); rebuild after 2 non-connected checks.
     *   (2) LIVENESS-stale — the broker dropped the link but HiveMQ never noticed the half-open
     *       (CLOSE-WAIT) socket, so it still reports "connected" while publishing into the void.
     *       isConnected() lies, so key on TRUE liveness: each tick send a heartbeat (a QoS-1 publish the
     *       broker must ACK) and, if nothing has been ACKed for MQTT_STALE_MS, force a rebuild.
     *
     * CRITICAL INVARIANT: the loop thread makes NO potentially-blocking MQTT call. A HiveMQ publish —
     * and even disconnect/rebuild — can block on an internal client monitor exactly when the connection
     * is wedged (the same trap as the sensor-callback ANR), which is precisely when the watchdog is
     * needed. rc10 called heartbeat() inline and the watchdog froze inside its own probe (observed in
     * the field: thread parked on a futex, liveness 44 min stale, no rebuild). So heartbeat and rebuild
     * each run on their own guarded side-thread: if the previous one is still in flight, skip — an
     * in-flight heartbeat can't refresh lastOkMs, so staleness still trips, and the rebuild guard
     * re-arms via a timeout so a hung rebuild can't permanently disable healing. The rebuild DECISION
     * (which stall mode, whether to rebuild, and the wedged-rebuild abandon) lives in [ConnectionSupervisor];
     * this thread owns only the tick cadence and the off-thread heartbeat/rebuild execution.
     */
    private fun startMqttWatchdog() {
        if (mqttWatchdogAlive) return
        mqttWatchdogAlive = true
        val supervisor = ConnectionSupervisor(MQTT_STALE_MS, REBUILD_ABANDON_MS)
        Thread {
            var heartbeat: Thread? = null
            var rebuild: Thread? = null
            while (mqttWatchdogAlive) {
                try { Thread.sleep(MQTT_WATCHDOG_MS) } catch (e: InterruptedException) { break }
                if (!mqttWatchdogAlive) break
                val sinceOk = mqtt.msSinceLastOk()
                val now = android.os.SystemClock.elapsedRealtime()
                Log.i(TAG, "mqtt watchdog tick: state=${mqtt.state} sinceOk=${sinceOk}ms hb=${heartbeat?.isAlive == true} rebuild=${rebuild?.isAlive == true}")
                when (val action = supervisor.tick(mqtt.state, mqtt.lastOkMs, sinceOk, now, rebuild?.isAlive == true)) {
                    is ConnectionSupervisor.Action.Rebuild -> {
                        Log.w(TAG, "MQTT ${action.reason} stall (${sinceOk}ms, state=${mqtt.state}) — forcing reconnect (flip family)")
                        rebuild = Thread({ runCatching { mqtt.reconnect(flipFamily = true) } }, "mqtt-rebuild")
                            .apply { isDaemon = true; start() }
                    }
                    is ConnectionSupervisor.Action.SkipRebuild ->
                        Log.w(TAG, "mqtt ${action.reason} rebuild wanted but one in flight ${action.inFlightMs}ms — skipping")
                    ConnectionSupervisor.Action.None -> {}
                }
                // Heartbeat LAST and OFF-THREAD: a wedged client blocks the publish, but only this
                // sacrificial thread — the loop keeps ticking and the un-refreshed lastOkMs is itself
                // the failure signal. Skip while one is still in flight (its ACK never came).
                if (heartbeat?.isAlive != true) {
                    heartbeat = Thread({ runCatching { mqtt.heartbeat() } }, "mqtt-heartbeat")
                        .apply { isDaemon = true; start() }
                }
            }
        }.apply { isDaemon = true; name = "mqtt-watchdog" }.start()
    }

    /**
     * Nudge MQTT to reconnect the moment the default network returns (Wi-Fi / router flap), instead of
     * waiting out the auto-reconnect backoff. Best-effort: no-op if MQTT is already connected or disabled.
     */
    private fun registerNetworkCallback() {
        if (netCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (mqtt.state != "connected" && mqtt.state != "disabled") {
                    Log.i(TAG, "network available — nudging MQTT reconnect")
                    Thread { runCatching { mqtt.reconnect() } }.start()
                }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }.onSuccess { netCallback = cb }
    }

    private fun startForegroundCompat() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "ha-paneld", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Panel hardware agent for Home Assistant"
                },
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ha-paneld")
            .setContentText("Listening on :${config.httpPort}")
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        Log.i(TAG, "foreground service started")
    }

    override fun onDestroy() {
        started = false
        netCallback?.let { cb ->
            runCatching { (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.unregisterNetworkCallback(cb) }
            netCallback = null
        }
        mqttWatchdogAlive = false            // stop the dedicated watchdog thread
        runCatching { power.apply(false) }   // release the wakelock so we don't leak it on teardown
        runCatching { navbar.cleanup() }
        runCatching { watchdog.stop() }
        runCatching { ledEffect.stop() }     // kill any running LED effect loop with the service
        runCatching { sensors.stop() }
        runCatching { logShipper.stop() }
        runCatching { server.stop() }
        runCatching { mdns.stop() }
        runCatching { mqtt.stop() }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "ha-paneld/svc"
        private const val CHANNEL_ID = "ha-paneld"
        private const val NOTIF_ID = 1
        // MQTT reconnect-watchdog poll interval; a stuck bridge self-heals after ~2 of these.
        private const val MQTT_WATCHDOG_MS = 60_000L
        // A rebuild thread wedged inside the old client for this long is abandoned (it stays parked as
        // a leaked daemon thread) and a fresh rebuild is attempted — healing must never stay disabled.
        private const val REBUILD_ABANDON_MS = 300_000L
        // An auth-rejected state younger than this (vs the last broker-ACKed activity) renders as
        // "reconnecting…" — only a PERSISTENT rejection surfaces the check-your-credentials warning.
        private const val AUTH_PERSIST_MS = 300_000L
        // No broker-ACKed publish for this long (with a heartbeat sent every tick) ⇒ the link is dead
        // even if HiveMQ still claims "connected" (half-open socket) ⇒ force a rebuild. ~2.5 missed ticks.
        private const val MQTT_STALE_MS = 150_000L
        // Never-blank-screen watchdog poll interval; re-lights an unintentionally-dark panel within one tick.
        private const val SCREEN_WATCHDOG_MS = 60_000L
        private const val KIOSK_REASSERT_MS = 60_000L // post-boot delay before re-locking (admin escape window)

        fun start(context: Context) {
            val intent = Intent(context, PaneldService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
