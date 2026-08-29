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
import io.github.maxlyth.hapaneld.camera.CameraSessionState.Admission
import io.github.maxlyth.hapaneld.camera.CameraSessionState.Failure
import io.github.maxlyth.hapaneld.camera.CameraSessionState.Phase
import io.github.maxlyth.hapaneld.util.HaTransportFault
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The Android adapter around [CameraSessionState]: it drives the device, the light and the foreground
 * standing, and every ownership decision is the state machine's. Stream, snapshot and the later motion
 * detector are subscribers holding a [Lease]; the device opens on the first lease and closes on the
 * last (contract §5), and no subscriber can open or close the hardware on another's behalf.
 *
 * Gate order before the device opens, each classified when it refuses: the board has a camera, the
 * master switch is on, Android has granted the permission, the camera-in-use light is confirmed
 * positive for the current screen state, and Android has confirmed camera-typed foreground standing.
 * Only then `openCamera`. The light stays a prerequisite for as long as the session runs: every
 * watchdog tick re-checks it and closes the session when it is negative (§2).
 *
 * Foreground standing can only be started while an activity is visible. On the backlight routes the
 * dashboard activity stays resumed through screen-off, so a session can start and continue there; on
 * the keyevent route the panel is genuinely asleep and a cold start is refused, classified as a
 * foreground refusal whose action says to wake the panel. A session already running survives either.
 *
 * A session is one repeating YUV capture into an [ImageReader]. Frames are delivered to subscribers no
 * faster than the configured frame-rate cap — the cap bounds what leaves the session, and the sensor
 * may run at the lowest rate it offers above that. Nothing is kept warm: when the last lease closes,
 * the reader, session, device, handler thread and foreground standing are all released (§4).
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
    private var policy = CameraSessionPolicy(frameIntervalMs = 1_000L / 15)
    private val state = CameraSessionState { policy }
    private var outcome = "ok"
    private var fault = CameraFault.NONE
    private var faultDetail: String? = null
    private var recovery = "none"
    private var admissionClosed = false

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var boundTarget: CameraResolution? = null
    private var boundFps = 15
    private var lastDeliveredAtMs = 0L
    /** The outcome of the open attempt for a generation; joiners wait on it too. */
    private val openOutcome = HashMap<Long, CompletableFuture<CameraRefusal?>>()

    init {
        publishPermissionPrompt(freshEnable = false)
    }

    /** A subscriber's claim on the open session. Close exactly once; closing the last one closes the device. */
    inner class Lease internal constructor(private val id: Long) : AutoCloseable {
        private var open = true
        override fun close() {
            val closeNow: Boolean
            synchronized(lock) {
                if (!open) return
                open = false
                closeNow = state.release(id)
            }
            if (closeNow) post { closeSession(Phase.IDLE, "ok") }
        }
    }

    // ---- CameraSurface ---------------------------------------------------------------------------

    override fun presentation(): CameraPresentation = synchronized(lock) {
        when {
            !hasCamera -> CameraPresentation.absent()
            state.phase == Phase.STOPPING -> current()
            !enabled() -> CameraPresentation.disabled()
            !permissionGranted() -> CameraPresentation.permissionNeeded()
            else -> current()
        }
    }

    override fun snapshot(requested: CameraResolution?): SnapshotResult {
        val lease = acquire(requested) ?: return SnapshotResult.Refused(lastRefusal())
        val waiter = CompletableFuture<ByteArray?>()
        try {
            synchronized(lock) { state.addWaiter(waiter) }
            val bytes = runCatching { waiter.get(SNAPSHOT_WAIT_MS, TimeUnit.MILLISECONDS) }.getOrNull()
            return if (bytes != null) SnapshotResult.Jpeg(bytes) else SnapshotResult.Refused(CameraRefusal.STARVED)
        } finally {
            synchronized(lock) { state.removeWaiter(waiter) }
            lease.close()
        }
    }

    // ---- lifecycle used by the service --------------------------------------------------------------

    /** The master switch moved. Off closes a live or opening session now; on republishes the prompt. */
    fun onEnabledChanged() {
        if (enabled()) {
            publishPermissionPrompt(freshEnable = true)
            return
        }
        publishPermissionPrompt(freshEnable = false)
        val closeNow = synchronized(lock) { state.disable() }
        if (closeNow) post { closeSession(Phase.IDLE, CameraRefusal.DISABLED.token) }
    }

    /** Refuse new leases; existing ones drain through [stop]. */
    fun closeAdmission() {
        synchronized(lock) { admissionClosed = true }
    }

    /** Close everything now and quit the thread. Returns once the device is released. */
    fun stop(): CompletableFuture<Unit> {
        closeAdmission()
        val done = CompletableFuture<Unit>()
        val held: Boolean
        val h: Handler?
        synchronized(lock) {
            held = state.stopping()
            h = handler
        }
        if (!held || h == null) {
            indicator.forceHide()
            synchronized(lock) { state.closed(Phase.STOPPING) }
            done.complete(Unit)
            return done
        }
        h.post {
            closeSession(Phase.STOPPING, CameraRefusal.STOPPING.token)
            done.complete(Unit)
        }
        return done
    }

    // ---- leases -------------------------------------------------------------------------------------

    /** Blocking: returns a lease on an open session, or null after recording the classified refusal. */
    fun acquire(requested: CameraResolution?): Lease? {
        val gate = synchronized(lock) {
            when {
                !hasCamera -> CameraRefusal.ABSENT
                admissionClosed -> CameraRefusal.STOPPING
                !enabled() -> CameraRefusal.DISABLED
                !permissionGranted() -> CameraRefusal.PERMISSION
                else -> null
            }
        }
        val admission: Admission
        val pending: CompletableFuture<CameraRefusal?>?
        synchronized(lock) {
            admission = state.acquire(gate, nowMs())
            pending = when (admission) {
                is Admission.Refused -> {
                    outcome = admission.reason.token
                    if (admission.reason == CameraRefusal.PERMISSION) publishPermissionPrompt(freshEnable = false)
                    null
                }
                is Admission.Open -> beginOpenLocked(admission.generation, requested)
                is Admission.Join -> openOutcome[admission.generation]?.takeIf { !it.isDone }
            }
        }
        val leaseId = when (admission) {
            is Admission.Refused -> return null
            is Admission.Open -> admission.lease
            is Admission.Join -> admission.lease
        }
        val refusal = pending?.let {
            runCatching { it.get(OPEN_WAIT_MS, TimeUnit.MILLISECONDS) }.getOrDefault(CameraRefusal.FAILED)
        }
        if (refusal != null) {
            val closeNow = synchronized(lock) { state.release(leaseId) }
            if (closeNow) post { closeSession(Phase.IDLE, refusal.token) }
            return null
        }
        return Lease(leaseId)
    }

    private fun lastRefusal(): CameraRefusal = synchronized(lock) {
        CameraRefusal.entries.firstOrNull { it.token == outcome } ?: CameraRefusal.FAILED
    }

    // ---- open ---------------------------------------------------------------------------------------

    /** Under [lock]: the first lease binds the parameters and starts attempt [gen] on the owned thread. */
    private fun beginOpenLocked(gen: Long, requested: CameraResolution?): CompletableFuture<CameraRefusal?> {
        val future = CompletableFuture<CameraRefusal?>()
        openOutcome[gen] = future
        future.whenComplete { _, _ -> synchronized(lock) { openOutcome.remove(gen) } }
        val h = handler ?: HandlerThread("ha-paneld-camera").let { t ->
            t.start()
            thread = t
            Handler(t.looper).also { handler = it }
        }
        val cap = maxResolution()
        boundTarget = CameraResolution.clamp(requested ?: cap, cap)
        boundFps = maxFps().coerceIn(1, 30)
        policy = CameraSessionPolicy(frameIntervalMs = 1_000L / boundFps)
        h.post { open(gen) }
        return future
    }

    private fun open(gen: Long) {
        val target: CameraResolution
        val fps: Int
        synchronized(lock) {
            // A reopen posted before a close or a disable is a no-op: the generation moved on.
            if (state.generation != gen || state.phase != Phase.OPENING) return
            target = boundTarget ?: maxResolution()
            fps = boundFps
        }
        if (!indicator.show()) return openFailed(gen, CameraFault.INDICATION, CameraRefusal.INDICATION, null)
        if (!foreground.promote(FOREGROUND_WAIT_MS)) {
            return openFailed(gen, CameraFault.FOREGROUND, CameraRefusal.FOREGROUND, null)
        }
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val h = synchronized(lock) { handler } ?: return openFailed(gen, CameraFault.NONE, CameraRefusal.STOPPING, null)
        val chosen = runCatching { chooseCamera(manager) }.getOrNull()
            ?: return openFailed(gen, CameraFault.OPEN, CameraRefusal.FAILED, "no_camera_id")
        // Both caps are ceilings. A board that cannot stay under the resolution cap is refused rather
        // than exceeded; the frame-rate cap is enforced on delivery, so the sensor range only needs to
        // be the lowest one on offer.
        val size = chooseSize(chosen.second, target)
            ?: return openFailed(gen, CameraFault.CONFIGURE, CameraRefusal.FAILED, "no_size_within_cap")
        val fpsRange = chooseFpsRange(chosen.second, fps)
        val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        r.setOnImageAvailableListener({ rd -> rd.acquireLatestImage()?.let { onFrame(it, gen) } }, h)
        synchronized(lock) { reader = r }
        try {
            @Suppress("MissingPermission")
            manager.openCamera(chosen.first, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    val live = synchronized(lock) {
                        if (state.generation == gen && state.phase == Phase.OPENING) { device = camera; true } else false
                    }
                    if (!live) { camera.close(); return }
                    configure(camera, r, fpsRange, gen)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    deviceFault(gen, CameraFault.DISCONNECTED, null)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    deviceFault(gen, CameraFault.DEVICE_ERROR, "error_$error")
                }
            }, h)
        } catch (e: SecurityException) {
            // Android 11 grants the foreground type silently and refuses here instead.
            val permitted = permissionGranted()
            openFailed(
                gen,
                if (permitted) CameraFault.FOREGROUND else CameraFault.PERMISSION,
                if (permitted) CameraRefusal.FOREGROUND else CameraRefusal.PERMISSION,
                e.javaClass.simpleName,
            )
        } catch (e: CameraAccessException) {
            openFailed(gen, CameraFault.OPEN, CameraRefusal.FAILED, "cae_${e.reason}")
        } catch (e: RuntimeException) {
            openFailed(gen, CameraFault.OPEN, CameraRefusal.FAILED, e.javaClass.simpleName)
        }
    }

    private fun configure(camera: CameraDevice, r: ImageReader, fpsRange: Range<Int>?, gen: Long) {
        val h = synchronized(lock) { handler } ?: return
        try {
            @Suppress("DEPRECATION")
            camera.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    val live = synchronized(lock) {
                        if (state.generation == gen && state.phase == Phase.OPENING) { session = s; true } else false
                    }
                    if (!live) { s.close(); return }
                    try {
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(r.surface)
                            fpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                        }.build()
                        s.setRepeatingRequest(request, null, h)
                    } catch (e: Exception) {
                        openFailed(gen, CameraFault.CONFIGURE, CameraRefusal.FAILED, e.javaClass.simpleName)
                        return
                    }
                    val became = synchronized(lock) {
                        val ok = state.openSucceeded(gen)
                        if (ok) {
                            outcome = "ok"; fault = CameraFault.NONE; faultDetail = null; recovery = "none"
                        }
                        ok
                    }
                    if (became) {
                        openOutcomeFor(gen)?.complete(null)
                        scheduleTick(gen)
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    openFailed(gen, CameraFault.CONFIGURE, CameraRefusal.FAILED, "configure_failed")
                }
            }, h)
        } catch (e: Exception) {
            openFailed(gen, CameraFault.CONFIGURE, CameraRefusal.FAILED, e.javaClass.simpleName)
        }
    }

    /**
     * An open attempt failed. The first caller learns the refusal at once; the ladder keeps climbing in
     * the background for as long as subscribers remain, and stops the moment none do.
     */
    private fun openFailed(gen: Long, f: CameraFault, refusal: CameraRefusal, detail: String?) {
        releaseHardware()
        val decision: Failure
        synchronized(lock) {
            decision = state.openFailed(gen, f)
            if (decision != Failure.Ignored) {
                outcome = refusal.token
                fault = f
                faultDetail = HaTransportFault.sanitize(detail)
            }
        }
        openOutcomeFor(gen)?.complete(refusal)
        when (decision) {
            Failure.Ignored -> Unit
            Failure.Close -> closeSession(Phase.IDLE, refusal.token)
            is Failure.Reopen -> {
                foreground.demote()
                indicator.hide()
                synchronized(lock) { recovery = "reopening in ${decision.afterMs}ms (attempt ${decision.attempt})" }
                val h = synchronized(lock) { handler } ?: return
                h.postDelayed({ open(gen) }, decision.afterMs)
            }
            is Failure.Degrade -> degrade(f, decision.attempt)
        }
    }

    private fun openOutcomeFor(gen: Long): CompletableFuture<CameraRefusal?>? = synchronized(lock) { openOutcome[gen] }

    // ---- frames -------------------------------------------------------------------------------------

    private fun onFrame(image: Image, gen: Long) {
        image.use { img ->
            val now = nowMs()
            val ready: List<CompletableFuture<ByteArray?>>
            synchronized(lock) {
                ready = state.frame(gen, now)
                if (ready.isEmpty()) return
                // The frame-rate cap bounds delivery: a frame arriving sooner than the cap allows is
                // observed for liveness but not handed on.
                val interval = 1_000L / boundFps
                if (now - lastDeliveredAtMs < interval) {
                    ready.forEach { state.addWaiter(it) }
                    return
                }
                lastDeliveredAtMs = now
            }
            val jpeg = runCatching { toJpeg(img) }.getOrNull()
            ready.forEach { it.complete(jpeg) }
        }
    }

    // ---- watchdog -----------------------------------------------------------------------------------

    private fun scheduleTick(gen: Long) {
        val h = synchronized(lock) { handler } ?: return
        h.postDelayed({ tick(gen) }, policy.starvationMs / 2)
    }

    private fun tick(gen: Long) {
        synchronized(lock) { if (state.generation != gen || state.phase != Phase.LIVE) return }
        // The light is a continuing prerequisite: a session the room is not being told about closes.
        val indicated = indicator.refresh()
        val decision = synchronized(lock) {
            if (state.generation != gen || state.phase != Phase.LIVE) return
            state.tick(nowMs(), enabled(), indicated)
        }
        when (decision) {
            CameraSessionPolicy.Decision.Continue -> scheduleTick(gen)
            is CameraSessionPolicy.Decision.Close -> closeSession(
                if (decision.reason == CameraSessionPolicy.CloseReason.STOPPING) Phase.STOPPING else Phase.IDLE,
                when (decision.reason) {
                    CameraSessionPolicy.CloseReason.DISABLED -> CameraRefusal.DISABLED.token
                    CameraSessionPolicy.CloseReason.STOPPING -> CameraRefusal.STOPPING.token
                    CameraSessionPolicy.CloseReason.IDLE -> "ok"
                },
            )
            is CameraSessionPolicy.Decision.Reopen -> {
                releaseHardware()
                foreground.demote()
                indicator.hide()
                val h = synchronized(lock) {
                    fault = decision.fault
                    outcome = if (decision.fault == CameraFault.INDICATION) CameraRefusal.INDICATION.token else CameraRefusal.FAILED.token
                    recovery = "reopening in ${decision.afterMs}ms (attempt ${decision.attempt})"
                    state.reopening(decision.attempt)
                    handler
                } ?: return
                h.postDelayed({ open(gen) }, decision.afterMs)
            }
            is CameraSessionPolicy.Decision.Degrade -> degrade(decision.fault, decision.attempt)
        }
    }

    private fun deviceFault(gen: Long, f: CameraFault, detail: String?) {
        synchronized(lock) {
            if (!state.noteDeviceFault(gen, f)) return
            fault = f
            faultDetail = HaTransportFault.sanitize(detail)
        }
        // A device that dropped out mid-open never reaches onConfigured; settle the caller now.
        if (synchronized(lock) { state.phase == Phase.OPENING }) openFailed(gen, f, CameraRefusal.FAILED, detail)
    }

    private fun degrade(f: CameraFault, attempt: Int) {
        releaseHardware()
        foreground.demote()
        indicator.hide()
        val t: HandlerThread?
        synchronized(lock) {
            state.degraded(attempt)
            fault = f
            outcome = CameraRefusal.FAILED.token
            recovery = "reattach a client or toggle the camera setting"
            t = thread; thread = null; handler = null
        }
        t?.quitSafely()
        Log.w(TAG, "camera session degraded after $attempt failures: ${f.wire}")
    }

    // ---- close --------------------------------------------------------------------------------------

    private fun closeSession(next: Phase, finalOutcome: String) {
        releaseHardware()
        foreground.demote()
        if (next == Phase.STOPPING) indicator.forceHide() else indicator.hide()
        val t: HandlerThread?
        synchronized(lock) {
            state.closed(next)
            outcome = finalOutcome
            t = thread; thread = null; handler = null
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

    private fun publishPermissionPrompt(freshEnable: Boolean) {
        CameraPermissionPrompt.publish(
            wantsPermission = hasCamera && enabled() && !permissionGranted(),
            freshEnable = freshEnable && hasCamera,
        )
    }

    private fun current(): CameraPresentation {
        val last = state.lastFrameAtMs
        val phase = state.phase
        val clients = state.clients
        val failures = state.consecutiveFailures
        val presented = when (phase) {
            Phase.IDLE -> CameraState.IDLE
            Phase.OPENING -> CameraState.OPENING
            Phase.LIVE -> CameraState.LIVE
            Phase.DEGRADED -> CameraState.DEGRADED
            Phase.STOPPING -> CameraState.STOPPING
        }
        return CameraPresentation(
            state = presented, outcome = outcome, fault = fault, faultDetail = faultDetail, recovery = recovery,
            clients = clients, lastFrameAgeMs = last?.let { (nowMs() - it).coerceAtLeast(0) },
            consecutiveFailures = failures, indication = indicator.route(),
            summary = when (phase) {
                Phase.LIVE -> "camera open for $clients client${if (clients == 1) "" else "s"}"
                Phase.OPENING -> "camera opening"
                Phase.DEGRADED -> "camera gave up after $failures failures (${fault.wire})"
                Phase.STOPPING -> "camera stopping"
                Phase.IDLE -> "camera closed; nobody is watching"
            },
            action = when {
                phase == Phase.DEGRADED -> "check the camera hardware; a new client or a setting toggle retries"
                fault == CameraFault.FOREGROUND -> "wake the panel: a camera session can only start while the dashboard is visible"
                fault == CameraFault.INDICATION -> "the camera-in-use light could not be shown; check overlay permission and the LED"
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

    /** The largest YUV size within the cap, preferring the cap's aspect; null when nothing fits. */
    private fun chooseSize(characteristics: CameraCharacteristics, target: CameraResolution): Size? {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val sizes = map.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
        val targetArea = target.width.toLong() * target.height
        val targetAspect = target.width.toDouble() / target.height
        return sizes
            .filter { it.width <= target.width && it.height <= target.height }
            .maxWithOrNull(
                compareBy<Size> { kotlin.math.abs(it.width.toDouble() / it.height - targetAspect) < 0.2 }
                    .thenBy { it.width.toLong() * it.height }
                    .thenBy { -(kotlin.math.abs(it.width.toLong() * it.height - targetArea)) },
            )
    }

    /** The lowest sensor rate on offer at or above the cap; delivery is paced to the cap regardless. */
    private fun chooseFpsRange(characteristics: CameraCharacteristics, maxFps: Int): Range<Int>? {
        val ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty()
        if (ranges.isEmpty()) return null
        return ranges.filter { it.upper <= maxFps }.maxByOrNull { it.upper }
            ?: ranges.minWithOrNull(compareBy<Range<Int>> { it.upper }.thenBy { it.lower })
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
