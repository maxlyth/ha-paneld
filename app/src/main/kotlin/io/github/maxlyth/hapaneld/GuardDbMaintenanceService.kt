package io.github.maxlyth.hapaneld

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Process
import androidx.core.app.NotificationCompat
import io.github.maxlyth.hapaneld.http.GuardDbMaintenanceServer
import io.github.maxlyth.hapaneld.http.GuardDbMaintenanceSecurityAuthority
import io.github.maxlyth.hapaneld.http.GuardDbMaintenanceSecurityResult
import io.github.maxlyth.hapaneld.control.AdbController
import io.github.maxlyth.hapaneld.control.CdpRelay
import io.github.maxlyth.hapaneld.control.RemoteDebugAuthorityResult
import io.github.maxlyth.hapaneld.control.RemoteDebugSecurityTransitionGate
import io.github.maxlyth.hapaneld.util.GuardDbMaintenance
import io.github.maxlyth.hapaneld.util.GuardDbProcessAdmission
import io.github.maxlyth.hapaneld.util.GuardDbSentinelLoad
import io.github.maxlyth.hapaneld.util.guardDbAppStaging
import io.github.maxlyth.hapaneld.util.guardDbPreparedArmStore
import io.github.maxlyth.hapaneld.util.guardDbSentinelStore

/** Foreground, writer-free successor used only while root owns the Guard DB transaction. */
class GuardDbMaintenanceService : Service() {
    private var server: GuardDbMaintenanceServer? = null

    override fun onCreate() {
        super.onCreate()
        foreground()
        val sentinel = (GuardDbProcessAdmission.current() as? GuardDbSentinelLoad.Valid)?.sentinel
        if (sentinel == null) {
            stopSelf()
            return
        }
        fun securityReady(): Boolean = guardDbMaintenanceSecurityReady(
            expectedEpoch = sentinel.securityAuthorityEpoch,
            durableHardenedEpoch = RemoteDebugSecurityTransitionGate::hardenedAuthorityEpoch,
            relayRunning = { CdpRelay.running },
            remoteDebugOff = { AdbController.proveMaintenanceRemoteDebugOff(applicationContext) },
        )
        val security = object : GuardDbMaintenanceSecurityAuthority {
            override fun readyEpoch(): Long? = RemoteDebugSecurityTransitionGate.withLock {
                sentinel.securityAuthorityEpoch.takeIf { securityReady() }
            }

            override fun <T> commit(
                expectedEpoch: Long,
                action: () -> T,
            ): GuardDbMaintenanceSecurityResult<T> =
                when (val result = RemoteDebugSecurityTransitionGate.withEpoch(expectedEpoch) {
                    if (securityReady()) GuardDbMaintenanceSecurityResult.Value(action())
                    else GuardDbMaintenanceSecurityResult.Refused
                }) {
                    RemoteDebugAuthorityResult.Changed -> GuardDbMaintenanceSecurityResult.Changed
                    is RemoteDebugAuthorityResult.Value -> result.value
                }
        }
        server = GuardDbMaintenanceServer(
            context = applicationContext,
            sentinel = sentinel,
            client = GuardDbMaintenance.client,
            staging = guardDbAppStaging(applicationContext),
            preparedStore = guardDbPreparedArmStore(applicationContext),
            sentinelStore = guardDbSentinelStore(applicationContext),
            security = security,
        ) {
            // Let the accepted HTTP response flush before crossing the process boundary. The explicit
            // PaneldService intent survives this process; Application startup clears exact FINALIZED.
            Thread {
                Thread.sleep(500L)
                val intent = Intent(applicationContext, PaneldService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
                Process.killProcess(Process.myPid())
            }.start()
        }.also { it.start() }
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    private fun foreground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Database recovery", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val activity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, GuardDbMaintenanceActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Database recovery maintenance")
            .setContentText("Same-boot recovery is active; normal panel services are paused")
            .setOngoing(true)
            .setContentIntent(activity)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL = "guard-db-maintenance"
        private const val NOTIFICATION_ID = 0x48414744

        fun start(context: Context) {
            val intent = Intent(context, GuardDbMaintenanceService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }
}

/** Pure ordering seam used by the maintenance service: no Config/AppState/SQLite owner is permitted. */
internal fun guardDbMaintenanceSecurityReady(
    expectedEpoch: Long,
    durableHardenedEpoch: () -> Long?,
    relayRunning: () -> Boolean,
    remoteDebugOff: () -> Boolean,
): Boolean {
    if (expectedEpoch <= 0L || durableHardenedEpoch() != expectedEpoch || relayRunning()) return false
    if (!remoteDebugOff()) return false
    return durableHardenedEpoch() == expectedEpoch && !relayRunning()
}
