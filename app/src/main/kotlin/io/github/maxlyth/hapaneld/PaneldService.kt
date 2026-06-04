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
import io.github.maxlyth.hapaneld.control.BrightnessController
import io.github.maxlyth.hapaneld.control.NavigateController
import io.github.maxlyth.hapaneld.control.ScreenController
import io.github.maxlyth.hapaneld.control.SystemController
import io.github.maxlyth.hapaneld.control.VolumeController
import io.github.maxlyth.hapaneld.hardware.LedController
import io.github.maxlyth.hapaneld.hardware.LedFactory
import io.github.maxlyth.hapaneld.hardware.Rk3576LedController
import io.github.maxlyth.hapaneld.hardware.SocketLedController
import io.github.maxlyth.hapaneld.http.PaneldServer
import io.github.maxlyth.hapaneld.http.PanelInfo
import io.github.maxlyth.hapaneld.sensors.SensorReporter
import io.github.maxlyth.hapaneld.util.localIpv4
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
    private lateinit var screen: ScreenController
    private lateinit var led: LedController
    private lateinit var navigate: NavigateController
    private lateinit var volume: VolumeController
    private lateinit var system: SystemController
    private var configUrl: String? = null

    override fun onCreate() {
        super.onCreate()
        config = Config(this)
        sensors = SensorReporter(this)

        brightness = BrightnessController(this)
        screen = ScreenController(this, brightness)
        led = LedFactory.detect()
        navigate = NavigateController(this)
        volume = VolumeController(this)
        system = SystemController(this)
        configUrl = localIpv4()?.let { "http://$it:${config.httpPort}/" }

        mqtt = buildMqtt()
        mdns = MdnsAdvertiser(this, config)
        server = PaneldServer(config, cacheDir, scope, ::reconfigure, ::panelInfo)
    }

    private fun buildMqtt(): MqttBridge = MqttBridge(
        config, brightness, screen, led, navigate, volume, system,
        accessibilityEnabled(), sensors.hasLight(), sensors.hasProximity(), configUrl,
    )

    /**
     * Apply config from the HTTP page: persist panel id + MQTT settings, clear the old discovery if
     * the panel id changed, and restart MQTT + mDNS under the new settings. A null password keeps
     * the stored one.
     */
    private fun reconfigure(newPanel: String, broker: String, user: String, password: String?) {
        if (newPanel.isEmpty()) return
        scope.launch {
            val oldPanel = config.panelId
            if (newPanel != oldPanel) runCatching { mqtt.clearDiscovery() }
            runCatching { mqtt.stop() }
            config.setPanelId(newPanel)
            config.setMqtt(broker, user, password)
            runCatching { mdns.stop() }
            mqtt = buildMqtt()
            mdns = MdnsAdvertiser(this@PaneldService, config)
            mdns.start()
            mqtt.start()
            Log.i(TAG, "reconfigured: panel=$newPanel broker=${broker.ifEmpty { "(disabled)" }}")
        }
    }

    /** Ordered facts for the info page (`GET /`). */
    private fun panelInfo(): Map<String, String> {
        val broker = config.mqttBroker
        val mqttStatus = if (broker.isBlank()) "disabled"
        else "${broker.substringAfter("://").substringBefore(":")} · " +
            if (mqtt.isConnected()) "connected" else "disconnected"
        val extras = linkedMapOf(
            "panel_id" to config.panelId,
            "HTTP port" to config.httpPort.toString(),
            "Local IP" to (localIpv4() ?: "?"),
            "MQTT" to mqttStatus,
            "mDNS" to "${config.panelId} ${Config.MDNS_SERVICE_TYPE}",
            "LED" to ledLabel(),
            "Light sensor" to yesNo(sensors.hasLight()),
            "Proximity" to yesNo(sensors.hasProximity()),
            "Buttons (a11y)" to yesNo(accessibilityEnabled()),
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
            server.start()
            mdns.start()
            mqtt.start()
            sensors.start(
                onLux = { lux -> mqtt.publishLight(lux) },
                onProximity = { near -> mqtt.publishProximity(near) },
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
