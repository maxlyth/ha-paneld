package io.github.maxlyth.hapaneld.camera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * How the camera owner asks Android for camera-typed foreground standing before it opens the device.
 * Injectable so the owner's lifecycle can be exercised without a real service.
 */
interface CameraForegroundGate {
    /** True only once Android has confirmed the camera-typed foreground service. Blocking. */
    fun promote(timeoutMs: Long): Boolean
    fun demote()
}

/**
 * A foreground service that exists only to carry the `camera` foreground-service type while a camera
 * session is open. It owns no hardware: the session owner lives in `PaneldService`, subject to that
 * service's drain order and process boundary, and starts this one on demand.
 *
 * Why a second service. `PaneldService` fixes its foreground type once, at `onCreate`, and is
 * legitimately started from the background by the boot receiver — which is exactly the case in which
 * Android 11+ refuses camera access to a foreground service for its whole lifetime. A fresh
 * `startForegroundService` issued while an activity is visible is the only shape whose while-in-use
 * eligibility is unambiguous on every supported Android version.
 *
 * `START_NOT_STICKY`: Android must never resurrect a camera session on its own; idle cost is zero.
 */
class CameraForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val outcome = runCatching {
            val notification = notification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        }
        val promoted = outcome.isSuccess
        outcome.onFailure {
            // Android 12+ throws here when no activity is visible; Android 11 succeeds silently and
            // refuses at openCamera instead, which the owner classifies separately.
            Log.w(TAG, "camera foreground promotion refused: ${it.javaClass.simpleName}")
            stopSelf()
        }
        promotion.getAndSet(null)?.complete(promoted)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        promotion.getAndSet(null)?.complete(false)
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Camera", NotificationManager.IMPORTANCE_MIN),
            )
        }
        // The notification is a platform requirement, not the indication: a kiosk panel has no
        // reachable shade, so the room is told by the overlay and the LED.
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ha-paneld")
            .setContentText("Camera in use")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val TAG = "ha-paneld/camera-fgs"
        private const val CHANNEL_ID = "ha-paneld-camera"
        private const val NOTIF_ID = 7

        /** The pending promotion the next `onStartCommand` completes; one at a time by construction. */
        private val promotion = AtomicReference<CompletableFuture<Boolean>?>(null)

        internal fun request(): CompletableFuture<Boolean> {
            val future = CompletableFuture<Boolean>()
            promotion.getAndSet(future)?.complete(false)
            return future
        }
    }
}

class AndroidCameraForegroundGate(private val context: Context) : CameraForegroundGate {
    override fun promote(timeoutMs: Long): Boolean {
        val future = CameraForegroundService.request()
        val intent = Intent(context, CameraForegroundService::class.java)
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }.onFailure {
            Log.w(TAG, "camera foreground start refused: ${it.javaClass.simpleName}")
        }.isSuccess
        if (!started) return false
        return runCatching { future.get(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
    }

    override fun demote() {
        runCatching { context.stopService(Intent(context, CameraForegroundService::class.java)) }
    }

    private companion object {
        const val TAG = "ha-paneld/camera-fgs"
    }
}
