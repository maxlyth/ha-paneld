package io.github.maxlyth.hapaneld

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.maxlyth.hapaneld.control.AdbController
import io.github.maxlyth.hapaneld.device.DeviceProfile
import io.github.maxlyth.hapaneld.input.EvdevButtonClient
import io.github.maxlyth.hapaneld.control.AutoBrightnessController
import io.github.maxlyth.hapaneld.control.BrightnessController
import io.github.maxlyth.hapaneld.control.CpuController
import io.github.maxlyth.hapaneld.control.NavbarController
import io.github.maxlyth.hapaneld.control.NavigateController
import io.github.maxlyth.hapaneld.control.RelayController
import io.github.maxlyth.hapaneld.control.ScreenController
import io.github.maxlyth.hapaneld.control.SystemController
import io.github.maxlyth.hapaneld.control.TouchSoundController
import io.github.maxlyth.hapaneld.control.VolumeController
import io.github.maxlyth.hapaneld.control.ZigbeeController
import io.github.maxlyth.hapaneld.hardware.LedController
import io.github.maxlyth.hapaneld.hardware.LedFactory
import io.github.maxlyth.hapaneld.hardware.Rk3576LedController
import io.github.maxlyth.hapaneld.hardware.SocketLedController
import io.github.maxlyth.hapaneld.http.PaneldServer
import io.github.maxlyth.hapaneld.http.PanelInfo
import io.github.maxlyth.hapaneld.sensors.SensorReporter
import io.github.maxlyth.hapaneld.util.localIpv4
import io.github.maxlyth.hapaneld.util.localIpv6
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import io.github.maxlyth.hapaneld.util.Serializer
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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
    private lateinit var sensors: SensorReporter

    // Controllers are fields so the MQTT bridge can be rebuilt on a panel_id change.
    private lateinit var brightness: BrightnessController
    private lateinit var autoBright: AutoBrightnessController
    private lateinit var screen: ScreenController
    private lateinit var led: LedController
    private lateinit var navigate: NavigateController
    private lateinit var volume: VolumeController
    private lateinit var system: SystemController
    private lateinit var navbar: NavbarController
    private lateinit var touchSound: TouchSoundController
    private lateinit var zigbee: ZigbeeController
    private lateinit var relay: RelayController
    private lateinit var cpu: CpuController
    private lateinit var adb: AdbController
    private lateinit var profile: DeviceProfile
    private var configUrl: String? = null
    // One-time-start guard for onStartCommand (see there for why). Reset in onDestroy.
    @Volatile private var started = false

    override fun onCreate() {
        super.onCreate()
        config = Config(this)
        sensors = SensorReporter(this, config)
        // Detect the device profile once and hand it to the hardware-specific controllers (instead of
        // each re-detecting). The canonical per-platform silo for paths/quirks; see device/.
        profile = DeviceProfile.detect()
        config.attachProfile(profile)   // supplies per-panel manufacturer/model defaults

        brightness = BrightnessController(this)
        brightness.applyPreventIdleDim(config.preventIdleDim, config)
        autoBright = AutoBrightnessController(brightness, config)
        screen = ScreenController(this, brightness)
        led = LedFactory.detect(profile)
        navigate = NavigateController(this)
        volume = VolumeController(this)
        system = SystemController(this)
        navbar = NavbarController(
            this, system, volume, brightness, { config.launcherPackage },
            profile.appCanSu, profile.hasRecents,
        )
        touchSound = TouchSoundController(this)
        zigbee = ZigbeeController(profile)
        relay = RelayController(profile)
        cpu = CpuController(profile)
        adb = AdbController()
        configUrl = localIpv4()?.let { "http://$it:${config.httpPort}/" }

        mqtt = buildMqtt()
        mdns = MdnsAdvertiser(this, config)
        server = PaneldServer(
            config, cacheDir, scope, this, sensors, system, volume, ::reconfigure, ::panelInfo,
            profile.recommendedDensity, profile.recommendedFontScale,
        )
        // Stream daemon-instrumented hardware buttons (e.g. WF1589T power key) into the same event
        // entity as the a11y key capture. No-op on panels with no evdev buttons.
        EvdevButtonClient.start(profile.evdevButtons)
    }

    private fun buildMqtt(): MqttBridge = MqttBridge(
        config, brightness, screen, led, navigate, volume, system, navbar, touchSound, zigbee, relay, cpu, adb,
        accessibilityEnabled(), profile.evdevButtons.isNotEmpty(),
        sensors.hasLight(), sensors.hasProximity(),
        sensors.hasTemperature(), sensors.hasHumidity(),
        // Button backlight lives on the sysfs/daemon LED panels (TPA10), reached via the daemon's BTN.
        led is SocketLedController,
        profile.appCanSu, profile.hasRecents,
        autoBright, configUrl,
        // When no broker is configured, find HA on the LAN via mDNS and default to its :1883.
        discoverHaIp = { mdns.discoverHaIp() },
        // HA's advertised base URL (from zeroconf) for the "Open in HA" device link.
        discoverHaUrl = { mdns.discoverHaBaseUrl() },
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
            "auth-failed" -> "$host · reachable, auth rejected — check username/password"
            "unreachable" -> "$host · unreachable"
            "connecting" -> "$host · connecting…"
            else -> "disabled"
        }
        // Variant + firmware from ro.product.version (e.g. "NSPanel120P_3.7.1" -> "NSPanel 120P · fw 3.7.1").
        // This is the authoritative model/generation key — distinguishes 86P / 120P / 86P-Gen2 and the
        // firmware that drives the proximity-reporting + zigbee-layout quirks.
        val pv = sysProp("ro.product.version")
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
            "mDNS" to "${config.panelId} ${Config.MDNS_SERVICE_TYPE}",
            "Platform" to "${profile.displayName} · ${profile.socClass}",
            "Model" to modelRow,
            "LED" to ledLabel(),
            "Light sensor" to sensorRow(sensors.hasLight(), profile.lightTech, sensors.lightDesc()),
            "Proximity" to sensorRow(sensors.hasProximity(), profile.proximityTech, sensors.proximityDesc()),
            // a11y service = software back/recents nav, NOT physical buttons (NSPanel Pro has none).
            "Accessibility nav" to yesNo(accessibilityEnabled()),
            // Soft navbar overlay mode + whether the overlay can actually be drawn (SYSTEM_ALERT_WINDOW).
            "Navbar" to (config.navbarMode + if (config.navbarMode != "Off" && !canDrawOverlays()) " · no overlay permission" else ""),
            // Zigbee driver presence + state (NSPanel Pro only; "none" elsewhere). status() shells
            // out via su — fine here because the info page is served off the main thread.
            "Zigbee" to zigbee.status(),
            "Relays" to relay.count().let { if (it > 0) it.toString() else "none" },
            "CPU profile" to (cpu.currentTier() ?: "n/a"),
            "Network ADB" to when {
                adb.isPersisted() -> "persistent (5555) · survives reboot"
                adb.isActive() -> "active (5555) · not persistent (off after reboot)"
                else -> "off"
            },
        )
        return PanelInfo.collect(this, extras)
    }

    /** Read an Android system property (e.g. ro.product.version) via SystemProperties reflection. */
    private fun sysProp(key: String): String = runCatching {
        @Suppress("PrivateApi")
        val m = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        (m.invoke(null, key) as? String).orEmpty()
    }.getOrDefault("")

    private fun ledLabel(): String = when {
        !led.available() -> "none"
        led is Rk3576LedController -> "rk3576 /dev/ledjni (RGB)"
        led is SocketLedController -> "sysfs helper daemon (RGB)"
        led.colorCapable() -> "RGB"
        else -> "brightness"
    }

    private fun yesNo(b: Boolean) = if (b) "yes" else "no"

    /** "no", or "yes" with any declared technology + runtime value-type/range appended ("yes · Infrared ·
     *  Binary · near/far (0 / 5 cm)"). */
    private fun sensorRow(present: Boolean, tech: String?, desc: String?): String =
        if (!present) "no" else "yes" + listOfNotNull(tech, desc).joinToString("") { " · $it" }

    /** Smoothness-metrics target: the configured override, else the installed HA Companion app
     *  (this is an HA project — the dashboard is the Companion app, so no config needed normally). */
    private fun dashboardTarget(): String {
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
        scope.launch {
            io.github.maxlyth.hapaneld.http.PerfReader.dashboardPkg = dashboardTarget()
            io.github.maxlyth.hapaneld.http.PerfReader.enabled = config.instrumentationEnabled
            io.github.maxlyth.hapaneld.sensors.SensorTrace.enabled = config.instrumentationEnabled
            io.github.maxlyth.hapaneld.http.PerfReader.start(scope)
            server.start()
            mdns.start()
            mqtt.start()
            // Restore the soft navbar to its persisted mode (no-op when Off / no overlay permission).
            navbar.apply(config.navbarMode)
            sensors.start(
                onLux = { lux -> mqtt.publishLight(lux) },
                onLuxRaw = { lux -> autoBright.submitLux(lux) },
                onProximity = { near ->
                    mqtt.publishProximity(near)
                    // Wake-on-wave: local, instant, wake-only. onProximity fires only on far->near
                    // transitions (natural debounce); sleep stays HA's job. Publish the ON state so the
                    // HA screen entity tracks the local wake (GitHub #6 — was staying OFF in HA).
                    if (near && config.wakeOnWave) { screen.wake(); mqtt.publishScreenOn() }
                },
                onTemperature = { c -> mqtt.publishTemperature(c) },
                onHumidity = { h -> mqtt.publishHumidity(h) },
            )
        }
        return START_STICKY
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
        runCatching { sensors.stop() }
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
