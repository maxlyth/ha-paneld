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
    private lateinit var touchSound: TouchSoundController
    private lateinit var zigbee: ZigbeeController
    private lateinit var relay: RelayController
    private lateinit var cpu: CpuController
    private lateinit var adb: AdbController
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

        mqtt = buildMqtt()
        mdns = MdnsAdvertiser(this, config)
        server = PaneldServer(
            config, cacheDir, scope, this, sensors, ::reconfigure, ::panelInfo,
            profile.recommendedDensity, profile.recommendedFontScale,
        )
        // Stream daemon-instrumented hardware buttons (e.g. WF1589T power key) into the same event
        // entity as the a11y key capture. No-op on panels with no evdev buttons.
        EvdevButtonClient.start(profile.evdevButtons)
    }

    private fun buildMqtt(): MqttBridge = MqttBridge(
        config, brightness, screen, led, navigate, volume, system, touchSound, zigbee, relay, cpu, adb,
        accessibilityEnabled(), profile.evdevButtons.isNotEmpty(),
        sensors.hasLight(), sensors.hasProximity(),
        sensors.hasTemperature(), sensors.hasHumidity(),
        // Button backlight lives on the sysfs/daemon LED panels (TPA10), reached via the daemon's BTN.
        led is SocketLedController, autoBright, configUrl,
        // When no broker is configured, find HA on the LAN via mDNS and default to its :1883.
        discoverHaIp = { mdns.discoverHaIp() },
    )

    /**
     * Apply config the HTTP page has already written to [config]: clear the OLD discovery (the live
     * MQTT bridge still holds the old panel id), then rebuild MQTT + mDNS from the new config.
     */
    private fun reconfigure() {
        scope.launch {
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
        val extras = linkedMapOf(
            "panel_id" to config.panelId,
            "Friendly name" to config.friendlyName,
            "HTTP port" to config.httpPort.toString(),
            "Local IP" to (localIpv4() ?: "?"),
            "Local IPv6" to (localIpv6() ?: "—"),
            "MQTT" to mqttStatus,
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
            "Network ADB" to if (adb.isPersisted()) "persistent (5555)" else "not persistent",
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
            mqtt.start()
            sensors.start(
                onLux = { lux -> mqtt.publishLight(lux) },
                onLuxRaw = { lux -> autoBright.submitLux(lux) },
                onProximity = { near ->
                    mqtt.publishProximity(near)
                    // Wake-on-wave: local, instant, wake-only. onProximity fires only on far->near
                    // transitions (natural debounce); sleep stays HA's job.
                    if (near && config.wakeOnWave) screen.wake()
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
