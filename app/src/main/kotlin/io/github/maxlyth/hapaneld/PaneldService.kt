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
import io.github.maxlyth.hapaneld.control.VolumeController
import io.github.maxlyth.hapaneld.hardware.LedController
import io.github.maxlyth.hapaneld.hardware.Rk3576LedController
import io.github.maxlyth.hapaneld.http.PaneldServer
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

    override fun onCreate() {
        super.onCreate()
        config = Config(this)
        server = PaneldServer(config, cacheDir, scope)
        mdns = MdnsAdvertiser(this, config)

        val brightness = BrightnessController(this)
        val screen = ScreenController(this)
        val led: LedController = Rk3576LedController()
        val navigate = NavigateController(this)
        val volume = VolumeController(this)
        mqtt = MqttBridge(config, brightness, screen, led, navigate, volume, accessibilityEnabled())
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
            server.start()
            mdns.start()
            mqtt.start()
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
