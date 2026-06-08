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
import io.github.maxlyth.hapaneld.esphome.Esp
import io.github.maxlyth.hapaneld.esphome.EspEntity
import io.github.maxlyth.hapaneld.esphome.EspHomeServer
import io.github.maxlyth.hapaneld.input.ButtonBus
import io.github.maxlyth.hapaneld.input.PanelAccessibilityService
import io.github.maxlyth.hapaneld.util.HelperClient
import io.github.maxlyth.hapaneld.control.BrightnessController
import io.github.maxlyth.hapaneld.control.CpuController
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
    private lateinit var config: Config
    private lateinit var server: PaneldServer
    private lateinit var mdns: MdnsAdvertiser
    private lateinit var sensors: SensorReporter

    // Controllers are fields so the MQTT bridge can be rebuilt on a panel_id change.
    private lateinit var brightness: BrightnessController
    private lateinit var autoBright: AutoBrightnessController
    private lateinit var screen: ScreenController
    private lateinit var led: LedController
    private lateinit var navigate: NavigateController
    private lateinit var volume: VolumeController
    private lateinit var system: SystemController
    private lateinit var touchSound: TouchSoundController
    private lateinit var zigbee: ZigbeeController
    private lateinit var relay: RelayController
    private lateinit var cpu: CpuController
    private lateinit var adb: AdbController
    private lateinit var esphome: EspHomeServer   // ESPHome native-API server (sole transport on this branch)
    // Cached state for the ESPHome read-only / push entities (sensors fed from SensorReporter callbacks).
    @Volatile private var lastLux = 0f
    @Volatile private var lastNear = false
    @Volatile private var lastTemp = 0f
    @Volatile private var lastHumid = 0f
    @Volatile private var lastAmbientLux = 0f     // HA-fed ambient lux number (echo)
    @Volatile private var lastButtonsBri = 0      // button-backlight light (write-only HAL → cache last set)
    @Volatile private var lastNavigate = "/"      // navigate text (write-only → cache last set)
    private lateinit var profile: DeviceProfile
    private var configUrl: String? = null

    override fun onCreate() {
        super.onCreate()
        config = Config(this)
        sensors = SensorReporter(this, config)
        // Detect the device profile once and hand it to the hardware-specific controllers (instead of
        // each re-detecting). The canonical per-platform silo for paths/quirks; see device/.
        profile = DeviceProfile.detect()
        config.attachProfile(profile)   // supplies per-panel manufacturer/model defaults

        brightness = BrightnessController(this)
        autoBright = AutoBrightnessController(brightness, config)
        screen = ScreenController(this, brightness)
        led = LedFactory.detect(profile)
        navigate = NavigateController(this)
        volume = VolumeController(this)
        system = SystemController(this)
        touchSound = TouchSoundController(this)
        zigbee = ZigbeeController(profile)
        relay = RelayController(profile)
        cpu = CpuController(profile)
        adb = AdbController()
        configUrl = localIpv4()?.let { "http://$it:${config.httpPort}/" }
        esphome = buildEsphome()
        mdns = MdnsAdvertiser(this, config)
        server = PaneldServer(
            config, cacheDir, scope, this, sensors, ::reconfigure, ::panelInfo,
            profile.recommendedDensity, profile.recommendedFontScale,
        )
        // Stream daemon-instrumented hardware buttons (e.g. WF1589T power key) into the same event
        // entity as the a11y key capture. No-op on panels with no evdev buttons.
        EvdevButtonClient.start(profile.evdevButtons)
    }

    private fun buildEsphome(): EspHomeServer {
        val mac = config.androidId.padEnd(12, '0').take(12).chunked(2).joinToString(":").uppercase()
        return EspHomeServer(
            deviceName = config.panelId, macAddress = mac, version = Config.VERSION,
            friendlyName = config.friendlyName, model = config.model, manufacturer = config.manufacturer,
            webserverPort = config.httpPort,          // -> HA "Visit" link to the info UI
            entitiesProvider = { buildEsphomeEntities() },
        )
    }

    /**
     * The full HA entity surface for ESPHome, mirroring the (now-removed) MQTT discovery gating exactly.
     * Built lazily on an ESPHome connection thread — the capability probes (led/zigbee/cpu/adb/relay) shell
     * out via su, so this must never run on the main thread (EspHomeServer caches the result).
     */
    private fun buildEsphomeEntities(): List<EspEntity> {
        val e = mutableListOf<EspEntity>()
        var k = 0
        fun key() = ++k

        e += Esp.brightnessLight(key(), "screen", "Screen",
            getOn = { brightness.getBrightness() > 0 }, getBri = { brightness.getBrightness() },
            set = { on, bri -> if (!on) screen.sleep() else { screen.wake(); bri?.let { brightness.setBrightness(it); screen.noteLevel(it) } } })

        if (led.available()) {
            if (led.colorCapable()) e += Esp.rgbLight(key(), "led", "LED",
                getState = {
                    val p = config.lastLed.split(",")
                    Triple(p.getOrNull(0) == "1", p.getOrNull(1)?.toIntOrNull() ?: 255,
                        intArrayOf(p.getOrNull(2)?.toIntOrNull() ?: 255, p.getOrNull(3)?.toIntOrNull() ?: 255, p.getOrNull(4)?.toIntOrNull() ?: 255))
                },
                set = { on, bri, r, g, b ->
                    if (!on) { led.off(); config.lastLed = "0,0,0,0,0" }
                    else { led.setRgb(r * bri / 255, g * bri / 255, b * bri / 255); config.lastLed = "1,$bri,$r,$g,$b" }
                })
            else e += Esp.brightnessLight(key(), "led", "LED",
                getOn = { config.lastLed.startsWith("1") }, getBri = { config.lastLed.split(",").getOrNull(1)?.toIntOrNull() ?: 255 },
                set = { on, bri -> if (!on) { led.off(); config.lastLed = "0,0,0,0,0" } else { val v = bri ?: 255; led.setRgb(v, v, v); config.lastLed = "1,$v,$v,$v,$v" } })
        }

        e += Esp.text(key(), "navigate", "Navigate", icon = "mdi:monitor-dashboard",
            get = { lastNavigate }, set = { lastNavigate = it; navigate.navigate(it) })

        val eventTypes = listOf(
            "KEYCODE_POWER", "KEYCODE_MUTE", "KEYCODE_F", "KEYCODE_F1", "KEYCODE_F2", "KEYCODE_F3",
            "KEYCODE_F4", "KEYCODE_BACK", "KEYCODE_HOME", "KEYCODE_DPAD_CENTER", "KEYCODE_VOLUME_UP", "KEYCODE_VOLUME_DOWN")
        if (accessibilityEnabled() || profile.evdevButtons.isNotEmpty())
            e += Esp.event(key(), "button", "Button", eventTypes)
        if (accessibilityEnabled()) {
            e += Esp.button(key(), "back", "Back", icon = "mdi:arrow-left") { PanelAccessibilityService.navBack() }
            e += Esp.button(key(), "recents", "Recents", icon = "mdi:view-agenda") { PanelAccessibilityService.navRecents() }
        }

        e += Esp.number(key(), "volume", "Volume", 0f, 100f, 1f, unit = "%", icon = "mdi:volume-high",
            get = { volume.getPercent().toFloat() }, set = { volume.setPercent(it.toInt()) })

        if (sensors.hasLight()) e += Esp.sensor(key(), "illuminance", "Illuminance", "lx", "illuminance") { lastLux }
        if (sensors.hasProximity()) e += Esp.binarySensor(key(), "proximity", "Proximity", "occupancy") { lastNear }
        if (sensors.hasTemperature()) e += Esp.sensor(key(), "temperature", "Temperature", "°C", "temperature", decimals = 1) { lastTemp }
        if (sensors.hasHumidity()) e += Esp.sensor(key(), "humidity", "Humidity", "%", "humidity") { lastHumid }

        if (led is SocketLedController) e += Esp.brightnessLight(key(), "buttons", "Button backlight", icon = "mdi:gesture-tap-button",
            getOn = { lastButtonsBri > 0 }, getBri = { lastButtonsBri },
            set = { on, bri -> val lvl = if (!on) 0 else bri ?: 255; HelperClient.send("BTN $lvl"); lastButtonsBri = lvl })

        if (sensors.hasProximity()) e += Esp.switchE(key(), "wake_on_wave", "Wake on wave", icon = "mdi:gesture-tap",
            get = { config.wakeOnWave }, set = { config.setWakeOnWave(it) })
        e += Esp.switchE(key(), "touch_sound", "Touch sound", icon = "mdi:volume-high",
            get = { touchSound.isEnabled() }, set = { touchSound.set(it) })
        e += Esp.switchE(key(), "auto_brightness", "Auto-brightness", icon = "mdi:brightness-auto",
            get = { config.autoBrightness }, set = { config.setAutoBrightness(it) })
        e += Esp.number(key(), "brightness_bias", "Brightness bias", -100f, 100f, 5f, config = true, icon = "mdi:brightness-6",
            get = { config.brightnessBias.toFloat() }, set = { config.setBrightnessBias(it.toInt()) })
        e += Esp.number(key(), "ambient_lux", "Ambient lux (HA-fed)", 0f, 100000f, 1f, unit = "lx", slider = false, config = true, icon = "mdi:brightness-5",
            get = { lastAmbientLux }, set = { lastAmbientLux = it; autoBright.submitLux(it) })

        if (zigbee.present()) e += Esp.switchE(key(), "zigbee_router", "Zigbee router", icon = "mdi:zigbee",
            get = { zigbee.running() }, set = { config.setZigbeeRouterEnabled(it); if (it) zigbee.enable() else zigbee.disable() })

        for (n in 1..relay.count()) { val nn = n; e += Esp.switchE(key(), "relay$nn", "Relay $nn", config = false, icon = "mdi:electric-switch",
            get = { relay.get(nn) }, set = { relay.set(nn, it) }) }
        for (n in 1..relay.ledCount()) { val nn = n; e += Esp.onOffLight(key(), "button_led$nn", "Button LED $nn", icon = "mdi:led-on",
            get = { relay.ledGet(nn - 1) }, set = { relay.ledSet(nn - 1, it) }) }

        if (cpu.available()) e += Esp.select(key(), "cpu_governor", "CPU profile", CpuController.TIERS, icon = "mdi:speedometer",
            get = { cpu.currentTier() ?: "Auto" }, set = { cpu.setTier(it) })
        if (adb.available()) e += Esp.switchE(key(), "network_adb", "Network ADB", icon = "mdi:adb",
            get = { adb.isPersisted() }, set = { adb.set(it) })

        e += Esp.button(key(), "reload", "Reload dashboard", icon = "mdi:web-refresh") { system.reloadDashboard(config.dashboardPackage) }
        e += Esp.button(key(), "reboot", "Reboot", deviceClass = "restart", icon = "mdi:restart") { system.reboot() }
        e += Esp.button(key(), "launcher", "Launcher", icon = "mdi:apps") { system.launchLauncher(config.launcherPackage) }
        e += Esp.button(key(), "home", "Home Assistant", icon = "mdi:home-assistant") { system.launchHome(config.dashboardPackage) }
        return e
    }

    /**
     * Apply config the HTTP page has already written to [config]: clear the OLD discovery (the live
     * MQTT bridge still holds the old panel id), then rebuild MQTT + mDNS from the new config.
     */
    private fun reconfigure() {
        scope.launch {
            runCatching { esphome.stop() }    // rebuild on a panel_id / friendly-name change
            runCatching { mdns.stop() }
            esphome = buildEsphome()
            mdns = MdnsAdvertiser(this@PaneldService, config)
            mdns.start()
            esphome.start()
            ButtonBus.listener = { event -> esphome.publishEvent("button", event) }
            io.github.maxlyth.hapaneld.http.PerfReader.dashboardPkg = dashboardTarget()
            Log.i(TAG, "reconfigured: panel=${config.panelId} (ESPHome :${EspHomeServer.PORT})")
        }
    }

    /** Ordered facts for the info page (`GET /`). */
    private fun panelInfo(): Map<String, String> {
        val extras = linkedMapOf(
            "panel_id" to config.panelId,
            "Friendly name" to config.friendlyName,
            "HTTP port" to config.httpPort.toString(),
            "Local IP" to (localIpv4() ?: "?"),
            "Local IPv6" to (localIpv6() ?: "—"),
            "ESPHome API" to ":${EspHomeServer.PORT} · plaintext (native API)",
            "mDNS" to "${config.panelId} ${Config.MDNS_SERVICE_TYPE}",
            "Platform" to "${profile.displayName} · ${profile.socClass}",
            "LED" to ledLabel(),
            "Light sensor" to yesNo(sensors.hasLight()),
            "Proximity" to yesNo(sensors.hasProximity()),
            "Buttons (a11y)" to yesNo(accessibilityEnabled()),
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

    private fun ledLabel(): String = when {
        !led.available() -> "none"
        led is Rk3576LedController -> "rk3576 /dev/ledjni (RGB)"
        led is SocketLedController -> "sysfs helper daemon (RGB)"
        led.colorCapable() -> "RGB"
        else -> "brightness"
    }

    private fun yesNo(b: Boolean) = if (b) "yes" else "no"

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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        scope.launch {
            io.github.maxlyth.hapaneld.http.PerfReader.dashboardPkg = dashboardTarget()
            io.github.maxlyth.hapaneld.http.PerfReader.enabled = config.instrumentationEnabled
            io.github.maxlyth.hapaneld.sensors.SensorTrace.enabled = config.instrumentationEnabled
            io.github.maxlyth.hapaneld.http.PerfReader.start(scope)
            server.start()
            mdns.start()
            esphome.start()   // ESPHome native-API server on :6053 (plaintext) — sole transport
            ButtonBus.listener = { event -> esphome.publishEvent("button", event) }
            sensors.start(
                onLux = { lux -> lastLux = lux.toFloat(); esphome.publishStates() },
                onLuxRaw = { lux -> autoBright.submitLux(lux) },
                onProximity = { near ->
                    lastNear = near; esphome.publishStates()
                    // Wake-on-wave: local, instant, wake-only. onProximity fires only on far->near
                    // transitions (natural debounce); sleep stays HA's job.
                    if (near && config.wakeOnWave) screen.wake()
                },
                onTemperature = { c -> lastTemp = c.toFloat(); esphome.publishStates() },
                onHumidity = { h -> lastHumid = h.toFloat(); esphome.publishStates() },
            )
            // Zigbee router boot-restore (previously done on MQTT connect): bring the gateway up if it's
            // the desired persisted state and nothing else started it. su + slow (~30s) — fine on IO.
            runCatching { if (zigbee.present() && config.zigbeeRouterEnabled && !zigbee.running()) zigbee.enable() }
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
        runCatching { sensors.stop() }
        runCatching { server.stop() }
        runCatching { mdns.stop() }
        runCatching { ButtonBus.listener = null }
        runCatching { esphome.stop() }
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
