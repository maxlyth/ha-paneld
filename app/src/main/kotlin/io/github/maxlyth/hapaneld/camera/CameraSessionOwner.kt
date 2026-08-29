package io.github.maxlyth.hapaneld.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import io.github.maxlyth.hapaneld.util.HaTransportFault
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The one owner of the camera device (contract §5). Stream, snapshot and the later motion detector are
 * subscribers holding a [Lease]; the device opens on the first lease and closes on the last, and no
 * subscriber can open or close the hardware on another's behalf.
 *
 * Gate order before the device opens, each classified when it refuses: the board has a camera, the
 * master switch is on, Android has granted the permission, the camera-in-use light is confirmed
 * showing, and Android has confirmed camera-typed foreground standing. Only then `openCamera`. On
 * Android 11 that standing is granted silently and refused only at `openCamera`, so a `SecurityException`
 * there is classified as a foreground refusal when the permission is otherwise held.
 *
 * A session is one repeating YUV capture into an [ImageReader]. A snapshot waits for the next frame and
 * encodes it; a later stream subscriber adds its own target to the same session. Nothing is kept warm:
 * when the last lease closes, the reader, session, device, handler thread and foreground standing are
 * all released, so idle cost is zero rather than small (§4).
 *
 * Every state mutation happens under [lock]; camera callbacks arrive on the owned handler thread and
 * callers wait on futures, never on that thread.
 */
class CameraSessionOwner(
    private val context: Context,
    private val hasCamera: Boolean,
    private val enabled: () -> Boolean,
    private val maxResolution: () -> CameraResolution,
    private val maxFps: () -> Int,
    private val permissionGranted: () -> Boolean,
    private val indicator: CameraIndicator,
    private val foreground: CameraForegroundGate,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
) : CameraSurface {

    private val lock = Any()
    private var state = CameraState.IDLE
    private var outcome = "ok"
    private var fault = CameraFault.NONE
    private var faultDetail: String? = null
    private var recovery = "none"
    private var consecutiveFailures = 0
    private var lastFrameAtMs: Long? = null
    private var openedAtMs = 0L
    private var leases = 0
    private var pendingFault: CameraFault? = null
    private var admissionClosed = false

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var boundSize: Size? = null
    private var policy = CameraSessionPolicy(frameIntervalMs = 1_000L / 15)
    private var snapshotWaiter: CompletableFuture<ByteArray>? = null
    private var opening: CompletableFuture<CameraRefusal?>? = null
    private var generation = 0L

    /** A subscriber's claim on the open session. Close exactly once; closing the last one closes the device. */
    inner class Lease internal constructor(private val token: Long) : AutoCloseable {
        private var open = true
        override fun close() {
            val last: Boolean
            synchronized(lock) {
                if (!open) return
                open = false
                leases--
                last = leases == 0 && generation == token
            }
            if (last) post { closeSession(CameraState.IDLE, "ok") }
        }
    }

    // ---- CameraSurface ---------------------------------------------------------------------------

    override fun presentation(): CameraPresentation = synchronized(lock) {
        when {
            !hasCamera -> CameraPresentation.absent()
            state == CameraState.STOPPING -> current()
            !enabled() -> CameraPresentation.disabled()
            !permissionGranted() -> CameraPresentation.permissionNeeded()
            else -> current()
        }
    }

    override fun snapshot(requested: CameraResolution?): SnapshotResult {
        val lease = acquire(requested) ?: return SnapshotResult.Refused(lastRefusal())
        try {
            val waiter = CompletableFuture<ByteArray>()
            synchronized(lock) { snapshotWaiter = waiter }
            val bytes = runCatching { waiter.get(SNAPSHOT_WAIT_MS, TimeUnit.MILLISECONDS) }
                .getOrElse { if (it is TimeoutException) null else null }
            synchronized(lock) { if (snapshotWaiter === waiter) snapshotWaiter = null }
            return if (bytes != null) SnapshotResult.Jpeg(bytes) else SnapshotResult.Refused(CameraRefusal.STARVED)
        } finally {
            lease.close()
        }
    }

    // ---- lifecycle used by the service --------------------------------------------------------------

    /** The master switch moved; a live session closes within this call's posted work, not a tick later. */
    fun onEnabledChanged() {
        if (!enabled()) post { if (isOpen()) closeSession(CameraState.IDLE, CameraRefusal.DISABLED.token) }
    }

    /** Refuse new leases; existing ones drain through [stop]. */
    fun closeAdmission() {
        synchronized(lock) { admissionClosed = true }
    }

    /** Close everything now and quit the thread. Returns once the device is released. */
    fun stop(): CompletableFuture<Unit> {
        closeAdmission()
        val done = CompletableFuture<Unit>()
        val h = synchronized(lock) { handler }
        if (h == null) {
            indicator.forceHide()
            synchronized(lock) { state = CameraState.STOPPING }
            done.complete(Unit)
            return done
        }
        h.post {
            closeSession(CameraState.STOPPING, CameraRefusal.STOPPING.token)
            done.complete(Unit)
        }
        return done
    }

    // ---- leases -------------------------------------------------------------------------------------

    /** Blocking: returns a lease on an open session, or null after recording the classified refusal. */
    fun acquire(requested: CameraResolution?): Lease? {
        val refusal = synchronized(lock) {
            when {
                !hasCamera -> CameraRefusal.ABSENT
                admissionClosed || state == CameraState.STOPPING -> CameraRefusal.STOPPING
                !enabled() -> CameraRefusal.DISABLED
                !permissionGranted() -> CameraRefusal.PERMISSION
                else -> null
            }
        }
        if (refusal != null) {
            synchronized(lock) { outcome = refusal.token }
            return null
        }
        val pending: CompletableFuture<CameraRefusal?>?
        val token: Long
        synchronized(lock) {
            leases++
            token = generation
            // A second subscriber arriving mid-open waits on the same outcome as the first, so a refusal
            // is reported as what it was rather than as the starvation its snapshot would otherwise hit.
            pending = when {
                isOpenLocked() -> null
                state == CameraState.OPENING && opening != null -> opening
                else -> beginOpenLocked(requested)
            }
        }
        val result = pending?.let { runCatching { it.get(OPEN_WAIT_MS, TimeUnit.MILLISECONDS) }.getOrDefault(CameraRefusal.FAILED) }
        if (result != null) {
            synchronized(lock) { leases-- }
            return null
        }
        return Lease(token)
    }

    private fun lastRefusal(): CameraRefusal = synchronized(lock) {
        CameraRefusal.entries.firstOrNull { it.token == outcome } ?: CameraRefusal.FAILED
    }

    // ---- open ---------------------------------------------------------------------------------------

    /** Under [lock]: start the thread if needed and post the open sequence; the future settles on the handler. */
    private fun beginOpenLocked(requested: CameraResolution?): CompletableFuture<CameraRefusal?> {
        val future = CompletableFuture<CameraRefusal?>()
        val h = handler ?: HandlerThread("ha-paneld-camera").let { t ->
            t.start()
            thread = t
            Handler(t.looper).also { handler = it }
        }
        state = CameraState.OPENING
        val cap = maxResolution()
        val target = CameraResolution.clamp(requested ?: cap, cap)
        val fps = maxFps().coerceIn(1, 30)
        policy = CameraSessionPolicy(frameIntervalMs = 1_000L / fps)
        val myGeneration = ++generation
        opening = future
        future.whenComplete { _, _ -> synchronized(lock) { if (opening === future) opening = null } }
        h.post { open(target, fps, myGeneration, future) }
        return future
    }

    private fun open(target: CameraResolution, fps: Int, myGeneration: Long, future: CompletableFuture<CameraRefusal?>) {
        fun refuse(refusal: CameraRefusal, f: CameraFault, detail: String?) {
            synchronized(lock) {
                if (generation != myGeneration) return
                outcome = refusal.token
                fault = f
                faultDetail = HaTransportFault.sanitize(detail)
            }
            // The reader may already exist when openCamera itself throws; nothing survives a refusal.
            releaseHardware()
            foreground.demote()
            indicator.hide()
            failure(f, myGeneration)
            future.complete(refusal)
        }
        if (!indicator.show()) return refuse(CameraRefusal.INDICATION, CameraFault.INDICATION, null)
        if (!foreground.promote(FOREGROUND_WAIT_MS)) return refuse(CameraRefusal.FOREGROUND, CameraFault.FOREGROUND, null)
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val h = synchronized(lock) { handler } ?: return refuse(CameraRefusal.STOPPING, CameraFault.NONE, null)
        val chosen = runCatching { chooseCamera(manager) }.getOrNull()
            ?: return refuse(CameraRefusal.FAILED, CameraFault.OPEN, "no_camera_id")
        val size = chooseSize(chosen.second, target)
            ?: return refuse(CameraRefusal.FAILED, CameraFault.CONFIGURE, "no_yuv_size")
        val fpsRange = chooseFpsRange(chosen.second, fps)
        val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        r.setOnImageAvailableListener({ reader -> reader.acquireLatestImage()?.let { onFrame(it, myGeneration) } }, h)
        synchronized(lock) {
            reader = r
            boundSize = size
            openedAtMs = nowMs()
            lastFrameAtMs = null
            pendingFault = null
        }
        try {
            @Suppress("MissingPermission")
            manager.openCamera(chosen.first, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    synchronized(lock) {
                        if (generation != myGeneration) { camera.close(); return }
                        device = camera
                    }
                    configure(camera, r, fpsRange, myGeneration, future)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    noteFault(CameraFault.DISCONNECTED, null, myGeneration, future)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    noteFault(CameraFault.DEVICE_ERROR, "error_$error", myGeneration, future)
                }
            }, h)
        } catch (e: SecurityException) {
            // Android 11 grants the foreground type silently and refuses here instead.
            val permitted = permissionGranted()
            refuse(
                if (permitted) CameraRefusal.FOREGROUND else CameraRefusal.PERMISSION,
                if (permitted) CameraFault.FOREGROUND else CameraFault.PERMISSION,
                e.javaClass.simpleName,
            )
        } catch (e: CameraAccessException) {
            refuse(CameraRefusal.FAILED, CameraFault.OPEN, "cae_${e.reason}")
        } catch (e: RuntimeException) {
            refuse(CameraRefusal.FAILED, CameraFault.OPEN, e.javaClass.simpleName)
        }
    }

    private fun configure(
        camera: CameraDevice,
        r: ImageReader,
        fpsRange: Range<Int>?,
        myGeneration: Long,
        future: CompletableFuture<CameraRefusal?>,
    ) {
        val h = synchronized(lock) { handler } ?: return
        try {
            @Suppress("DEPRECATION")
            camera.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    synchronized(lock) {
                        if (generation != myGeneration) { s.close(); return }
                        session = s
                    }
                    try {
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(r.surface)
                            fpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                        }.build()
                        s.setRepeatingRequest(request, null, h)
                    } catch (e: Exception) {
                        noteFault(CameraFault.CONFIGURE, e.javaClass.simpleName, myGeneration, future)
                        return
                    }
                    synchronized(lock) {
                        if (generation != myGeneration) return
                        state = CameraState.LIVE
                        outcome = "ok"
                        fault = CameraFault.NONE
                        faultDetail = null
                        recovery = "none"
                    }
                    future.complete(null)
                    scheduleTick(myGeneration)
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    noteFault(CameraFault.CONFIGURE, "configure_failed", myGeneration, future)
                }
            }, h)
        } catch (e: Exception) {
            noteFault(CameraFault.CONFIGURE, e.javaClass.simpleName, myGeneration, future)
        }
    }

    // ---- frames -------------------------------------------------------------------------------------

    private fun onFrame(image: Image, myGeneration: Long) {
        image.use { img ->
            val waiter: CompletableFuture<ByteArray>?
            synchronized(lock) {
                if (generation != myGeneration) return
                lastFrameAtMs = nowMs()
                consecutiveFailures = 0
                waiter = snapshotWaiter
                snapshotWaiter = null
            }
            if (waiter != null) {
                val jpeg = runCatching { toJpeg(img) }.getOrNull()
                if (jpeg != null) waiter.complete(jpeg) else waiter.complete(null)
            }
        }
    }

    // ---- watchdog -----------------------------------------------------------------------------------

    private fun scheduleTick(myGeneration: Long) {
        val h = synchronized(lock) { handler } ?: return
        h.postDelayed({ tick(myGeneration) }, policy.starvationMs / 2)
    }

    private fun tick(myGeneration: Long) {
        val decision: CameraSessionPolicy.Decision
        synchronized(lock) {
            if (generation != myGeneration || !isOpenLocked()) return
            val t = CameraSessionPolicy.Tick(
                nowMs = nowMs(), openedAtMs = openedAtMs, lastFrameAtMs = lastFrameAtMs, clients = leases,
                enabled = enabled(), stopping = admissionClosed, deviceFault = pendingFault,
                consecutiveFailures = consecutiveFailures,
            )
            pendingFault = null
            decision = policy.onTick(t)
        }
        indicator.refresh()
        when (decision) {
            CameraSessionPolicy.Decision.Continue -> scheduleTick(myGeneration)
            is CameraSessionPolicy.Decision.Close -> closeSession(
                if (decision.reason == CameraSessionPolicy.CloseReason.STOPPING) CameraState.STOPPING else CameraState.IDLE,
                when (decision.reason) {
                    CameraSessionPolicy.CloseReason.DISABLED -> CameraRefusal.DISABLED.token
                    CameraSessionPolicy.CloseReason.STOPPING -> CameraRefusal.STOPPING.token
                    CameraSessionPolicy.CloseReason.IDLE -> "ok"
                },
            )
            is CameraSessionPolicy.Decision.Reopen -> reopen(decision, myGeneration)
            is CameraSessionPolicy.Decision.Degrade -> degrade(decision.fault, decision.attempt)
        }
    }

    /** A device callback or the tick found a fault mid-session; the ladder decides what happens next. */
    private fun noteFault(f: CameraFault, detail: String?, myGeneration: Long, future: CompletableFuture<CameraRefusal?>?) {
        synchronized(lock) {
            if (generation != myGeneration) return
            fault = f
            faultDetail = HaTransportFault.sanitize(detail)
            pendingFault = f
        }
        if (future != null && !future.isDone) {
            // The open itself failed: count it and settle the caller.
            val attempt = synchronized(lock) { ++consecutiveFailures }
            when (val d = policy.onFailure(f, attempt)) {
                is CameraSessionPolicy.Decision.Degrade -> { degrade(f, d.attempt); future.complete(CameraRefusal.FAILED) }
                else -> { releaseHardware(); synchronized(lock) { state = CameraState.IDLE; outcome = CameraRefusal.FAILED.token }
                    foreground.demote(); indicator.hide(); future.complete(CameraRefusal.FAILED) }
            }
        }
    }

    private fun failure(f: CameraFault, myGeneration: Long) {
        synchronized(lock) {
            if (generation != myGeneration) return
            consecutiveFailures++
            state = if (consecutiveFailures >= policy.maxConsecutiveFailures) CameraState.DEGRADED else CameraState.IDLE
            recovery = if (state == CameraState.DEGRADED) "reattach a client or toggle the camera setting" else "next open retries"
            fault = f
        }
    }

    private fun reopen(decision: CameraSessionPolicy.Decision.Reopen, myGeneration: Long) {
        releaseHardware()
        val h = synchronized(lock) {
            if (generation != myGeneration) return
            consecutiveFailures = decision.attempt
            state = CameraState.OPENING
            recovery = "reopening after ${decision.afterMs}ms"
            handler
        } ?: return
        val size = synchronized(lock) { boundSize }
        val target = size?.let { s -> CameraResolution.entries.firstOrNull { it.width == s.width } } ?: maxResolution()
        val fps = (1_000L / policy.frameIntervalMs).toInt().coerceIn(1, 30)
        h.postDelayed({
            val future = CompletableFuture<CameraRefusal?>()
            open(target, fps, myGeneration, future)
        }, decision.afterMs)
    }

    private fun degrade(f: CameraFault, attempt: Int) {
        releaseHardware()
        foreground.demote()
        indicator.hide()
        synchronized(lock) {
            state = CameraState.DEGRADED
            fault = f
            consecutiveFailures = attempt
            outcome = CameraRefusal.FAILED.token
            recovery = "reattach a client or toggle the camera setting"
        }
        Log.w(TAG, "camera session degraded after $attempt failures: ${f.wire}")
    }

    // ---- close --------------------------------------------------------------------------------------

    private fun closeSession(next: CameraState, finalOutcome: String) {
        releaseHardware()
        foreground.demote()
        if (next == CameraState.STOPPING) indicator.forceHide() else indicator.hide()
        val t: HandlerThread?
        synchronized(lock) {
            generation++
            state = next
            outcome = finalOutcome
            snapshotWaiter?.complete(null)
            snapshotWaiter = null
            t = thread
            thread = null
            handler = null
        }
        t?.quitSafely()
    }

    private fun releaseHardware() {
        val s: CameraCaptureSession?
        val d: CameraDevice?
        val r: ImageReader?
        synchronized(lock) {
            s = session; session = null
            d = device; device = null
            r = reader; reader = null
        }
        runCatching { s?.close() }
        runCatching { d?.close() }
        runCatching { r?.close() }
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private fun post(action: () -> Unit) {
        val h = synchronized(lock) { handler }
        if (h != null) h.post(action) else action()
    }

    private fun isOpen(): Boolean = synchronized(lock) { isOpenLocked() }
    private fun isOpenLocked(): Boolean = state == CameraState.LIVE

    private fun current(): CameraPresentation {
        val last = lastFrameAtMs
        return CameraPresentation(
            state = state, outcome = outcome, fault = fault, faultDetail = faultDetail, recovery = recovery,
            clients = leases, lastFrameAgeMs = last?.let { (nowMs() - it).coerceAtLeast(0) },
            consecutiveFailures = consecutiveFailures, indication = indicator.route(),
            summary = when (state) {
                CameraState.LIVE -> "camera open for $leases client${if (leases == 1) "" else "s"}"
                CameraState.OPENING -> "camera opening"
                CameraState.DEGRADED -> "camera gave up after $consecutiveFailures failures (${fault.wire})"
                CameraState.STOPPING -> "camera stopping"
                else -> "camera closed; nobody is watching"
            },
            action = when (state) {
                CameraState.DEGRADED -> "check the camera hardware; a new client or a setting toggle retries"
                else -> "none"
            },
        )
    }

    private fun chooseCamera(manager: CameraManager): Pair<String, CameraCharacteristics>? {
        val ids = manager.cameraIdList.takeIf { it.isNotEmpty() } ?: return null
        val described = ids.map { it to manager.getCameraCharacteristics(it) }
        // Prefer a front-facing lens: the firmware's own facing flag has been observed to lie, so
        // this is a preference among the cameras present, never a filter.
        return described.firstOrNull { it.second.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT }
            ?: described.first()
    }

    private fun chooseSize(characteristics: CameraCharacteristics, target: CameraResolution): Size? {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val sizes = map.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
        if (sizes.isEmpty()) return null
        val targetArea = target.width.toLong() * target.height
        val targetAspect = target.width.toDouble() / target.height
        return sizes
            .filter { it.width.toLong() * it.height <= targetArea }
            .maxWithOrNull(compareBy<Size> { kotlin.math.abs(it.width.toDouble() / it.height - targetAspect) < 0.2 }.thenBy { it.width.toLong() * it.height })
            ?: sizes.minByOrNull { it.width.toLong() * it.height }
    }

    private fun chooseFpsRange(characteristics: CameraCharacteristics, maxFps: Int): Range<Int>? {
        val ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty()
        if (ranges.isEmpty()) return null
        // The panels offer only [15,30] and [30,30]: pick the range whose ceiling is closest to the cap
        // without exceeding it, else the one with the lowest floor.
        return ranges.filter { it.upper <= maxFps }.maxByOrNull { it.upper }
            ?: ranges.minByOrNull { it.lower }
    }

    private fun toJpeg(image: Image): ByteArray {
        val nv21 = yuv420ToNv21(image)
        val out = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            .compressToJpeg(Rect(0, 0, image.width, image.height), JPEG_QUALITY, out)
        return out.toByteArray()
    }

    companion object {
        private const val TAG = "ha-paneld/camera"
        private const val OPEN_WAIT_MS = 8_000L
        private const val FOREGROUND_WAIT_MS = 3_000L
        private const val SNAPSHOT_WAIT_MS = 5_000L
        private const val JPEG_QUALITY = 85

        /** Pack a YUV_420_888 image as NV21 honouring row and pixel strides. */
        internal fun yuv420ToNv21(image: Image): ByteArray {
            val width = image.width
            val height = image.height
            val ySize = width * height
            val out = ByteArray(ySize + ySize / 2)
            val planes = image.planes
            val y = planes[0]
            val yBuffer = y.buffer
            val yRowStride = y.rowStride
            var pos = 0
            if (yRowStride == width && y.pixelStride == 1) {
                yBuffer.get(out, 0, ySize)
                pos = ySize
            } else {
                val row = ByteArray(yRowStride)
                for (r in 0 until height) {
                    yBuffer.position(r * yRowStride)
                    yBuffer.get(row, 0, minOf(yRowStride, yBuffer.remaining()))
                    for (c in 0 until width) out[pos++] = row[c * y.pixelStride]
                }
            }
            val u = planes[1]
            val v = planes[2]
            val uBuffer = u.buffer
            val vBuffer = v.buffer
            val chromaHeight = height / 2
            val chromaWidth = width / 2
            for (r in 0 until chromaHeight) {
                for (c in 0 until chromaWidth) {
                    val vi = r * v.rowStride + c * v.pixelStride
                    val ui = r * u.rowStride + c * u.pixelStride
                    out[pos++] = vBuffer.get(vi)
                    out[pos++] = uBuffer.get(ui)
                }
            }
            return out
        }
    }
}
