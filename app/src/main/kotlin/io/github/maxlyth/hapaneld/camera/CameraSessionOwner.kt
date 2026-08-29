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
 * last, and no subscriber can open or close the hardware on another's behalf.
 *
 * Hardware belongs to an [Attempt], never to the owner. Each `openCamera` — the first and every reopen
 * — creates its own attempt object holding its own reader, device and session, and every callback
 * carries that object. A callback from a superseded attempt can therefore release only what that
 * attempt owned; it is recognised by identity and never reaches a newer attempt's hardware or the
 * state machine.
 *
 * Gate order before the device opens, each classified when it refuses: the board has a camera, the
 * master switch is on, Android has granted the permission, the camera-in-use light is confirmed
 * positive for the current screen state, and Android has confirmed camera-typed foreground standing.
 * Only then `openCamera`. The light stays a prerequisite for as long as the session runs: every
 * watchdog tick re-checks it and closes the session when it is negative.
 *
 * Foreground standing can only be started while an activity is visible. Both camera-bearing profiles
 * keep the dashboard activity resumed through screen-off, so a session can start at any time there; on
 * a panel whose screen-off route sleeps Android, a cold start is refused as a foreground refusal whose
 * action says to wake the panel. A session already running survives either.
 *
 * A session is one repeating YUV capture into an [ImageReader]. Frames are handed to subscribers no
 * faster than the configured frame-rate cap: the sensor may capture faster, and frames arriving sooner
 * than the cap allows are observed for liveness but not delivered. Nothing is kept warm: when the last
 * lease closes, the attempt's hardware, the handler thread and the foreground standing are released.
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
    /** The attempt whose hardware is current, or null. Only ever replaced under [lock]. */
    private var current: Attempt? = null
    private var boundTarget: CameraResolution? = null
    private var boundFps = 15
    private var lastDeliveredAtMs = 0L

    /** One open attempt and the hardware it alone owns. */
    private inner class Attempt(val id: Long) {
        var reader: ImageReader? = null
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null

        fun release() {
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
    }

    init {
        publishPermissionPrompt(freshEnable = false)
    }

    /** A subscriber's claim on the open session. Close exactly once; closing the last one closes the device. */
    inner class Lease internal constructor(private val id: Long) : AutoCloseable {
        private var open = true
        override fun close() {
            val ended: Attempt?
            synchronized(lock) {
                if (!open) return
                open = false
                ended = if (state.release(id)) takeCurrentLocked() else null
                if (ended != null) outcome = "ok"
            }
            if (ended != null) post { finishAttempt(ended, stopping = false) }
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
            if (bytes != null) return SnapshotResult.Jpeg(bytes)
            // A frame that arrived but would not encode is the encoder's fault, not the camera's.
            val encodeFailed = synchronized(lock) { fault == CameraFault.ENCODE }
            return SnapshotResult.Refused(if (encodeFailed) CameraRefusal.ENCODE else CameraRefusal.STARVED)
        } finally {
            synchronized(lock) { state.removeWaiter(waiter) }
            lease.close()
        }
    }

    // ---- lifecycle used by the service --------------------------------------------------------------

    /** The master switch moved. Off ends a live or opening session at once; on republishes the prompt. */
    fun onEnabledChanged() {
        if (enabled()) {
            publishPermissionPrompt(freshEnable = true)
            return
        }
        publishPermissionPrompt(freshEnable = false)
        val ended = synchronized(lock) {
            if (state.disable()) {
                outcome = CameraRefusal.DISABLED.token
                takeCurrentLocked()
            } else null
        }
        if (ended != null) post { finishAttempt(ended, stopping = false) }
    }

    /** Refuse new leases; existing ones drain through [stop]. */
    fun closeAdmission() {
        synchronized(lock) { admissionClosed = true }
    }

    /** Close everything now and quit the thread. Returns once the device is released. */
    fun stop(): CompletableFuture<Unit> {
        closeAdmission()
        val done = CompletableFuture<Unit>()
        val ended: Attempt?
        val h: Handler?
        synchronized(lock) {
            state.stopping()
            outcome = CameraRefusal.STOPPING.token
            ended = takeCurrentLocked()
            h = handler
        }
        if (ended == null || h == null) {
            indicator.forceHide()
            quitThread()
            done.complete(Unit)
            return done
        }
        h.post {
            finishAttempt(ended, stopping = true)
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
        val leaseId: Long
        val pending: CompletableFuture<CameraRefusal?>?
        synchronized(lock) {
            when (val admission = state.acquire(gate, nowMs())) {
                is Admission.Refused -> {
                    outcome = admission.reason.token
                    if (admission.reason == CameraRefusal.PERMISSION) publishPermissionPrompt(freshEnable = false)
                    return null
                }
                is Admission.Open -> {
                    leaseId = admission.lease
                    beginOpenLocked(admission.attempt, requested)
                    pending = state.awaitOpen()
                }
                is Admission.Join -> {
                    leaseId = admission.lease
                    pending = state.awaitOpen()
                }
            }
        }
        val refusal = pending?.let {
            runCatching { it.get(OPEN_WAIT_MS, TimeUnit.MILLISECONDS) }.getOrDefault(CameraRefusal.FAILED)
        }
        if (refusal != null) {
            val ended = synchronized(lock) { if (state.release(leaseId)) takeCurrentLocked() else null }
            if (ended != null) post { finishAttempt(ended, stopping = false) }
            return null
        }
        return Lease(leaseId)
    }

    private fun lastRefusal(): CameraRefusal = synchronized(lock) {
        CameraRefusal.entries.firstOrNull { it.token == outcome } ?: CameraRefusal.FAILED
    }

    // ---- open ---------------------------------------------------------------------------------------

    /** Under [lock]: the first lease binds the parameters and starts attempt [attemptId] on the owned thread. */
    private fun beginOpenLocked(attemptId: Long, requested: CameraResolution?) {
        val h = handler ?: HandlerThread("ha-paneld-camera").let { t ->
            t.start()
            thread = t
            Handler(t.looper).also { handler = it }
        }
        val cap = maxResolution()
        boundTarget = CameraResolution.clamp(requested ?: cap, cap)
        boundFps = maxFps().coerceIn(1, 30)
        policy = CameraSessionPolicy(frameIntervalMs = 1_000L / boundFps)
        h.post { open(attemptId) }
    }

    private fun open(attemptId: Long) {
        val attempt: Attempt
        val target: CameraResolution
        val fps: Int
        synchronized(lock) {
            // A reopen posted before a close, a disable or a stop is a no-op: the attempt is not current.
            if (!state.isCurrent(attemptId) || state.phase != Phase.OPENING) return
            attempt = Attempt(attemptId)
            current = attempt
            target = boundTarget ?: maxResolution()
            fps = boundFps
        }
        if (!indicator.show()) return openFailed(attempt, CameraFault.INDICATION, CameraRefusal.INDICATION, null)
        if (!foreground.promote(FOREGROUND_WAIT_MS)) {
            return openFailed(attempt, CameraFault.FOREGROUND, CameraRefusal.FOREGROUND, null)
        }
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val h = synchronized(lock) { handler } ?: return openFailed(attempt, CameraFault.NONE, CameraRefusal.STOPPING, null)
        val chosen = runCatching { chooseCamera(manager) }.getOrNull()
            ?: return openFailed(attempt, CameraFault.OPEN, CameraRefusal.FAILED, "no_camera_id")
        // Both caps are ceilings. A board that cannot stay under the resolution cap is refused rather
        // than exceeded; the frame-rate cap is enforced on delivery, so the sensor range only needs to
        // be the lowest one on offer.
        val size = chooseSize(chosen.second, target)
            ?: return openFailed(attempt, CameraFault.CONFIGURE, CameraRefusal.FAILED, "no_size_within_cap")
        val fpsRange = chooseFpsRange(chosen.second, fps)
        val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        r.setOnImageAvailableListener({ rd -> rd.acquireLatestImage()?.let { onFrame(it, attempt) } }, h)
        synchronized(lock) { attempt.reader = r }
        try {
            @Suppress("MissingPermission")
            manager.openCamera(chosen.first, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    val live = synchronized(lock) {
                        if (state.isCurrent(attempt.id) && state.phase == Phase.OPENING) { attempt.device = camera; true } else false
                    }
                    if (!live) { camera.close(); return }
                    configure(attempt, camera, r, fpsRange)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    deviceFault(attempt, CameraFault.DISCONNECTED, null)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    deviceFault(attempt, CameraFault.DEVICE_ERROR, "error_$error")
                }
            }, h)
        } catch (e: SecurityException) {
            // Android 11 grants the foreground type silently and refuses here instead.
            val permitted = permissionGranted()
            openFailed(
                attempt,
                if (permitted) CameraFault.FOREGROUND else CameraFault.PERMISSION,
                if (permitted) CameraRefusal.FOREGROUND else CameraRefusal.PERMISSION,
                e.javaClass.simpleName,
            )
        } catch (e: CameraAccessException) {
            openFailed(attempt, CameraFault.OPEN, CameraRefusal.FAILED, "cae_${e.reason}")
        } catch (e: RuntimeException) {
            openFailed(attempt, CameraFault.OPEN, CameraRefusal.FAILED, e.javaClass.simpleName)
        }
    }

    private fun configure(attempt: Attempt, camera: CameraDevice, r: ImageReader, fpsRange: Range<Int>?) {
        val h = synchronized(lock) { handler } ?: return
        try {
            @Suppress("DEPRECATION")
            camera.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    val live = synchronized(lock) {
                        if (state.isCurrent(attempt.id) && state.phase == Phase.OPENING) { attempt.session = s; true } else false
                    }
                    if (!live) { s.close(); return }
                    try {
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(r.surface)
                            fpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                        }.build()
                        s.setRepeatingRequest(request, null, h)
                    } catch (e: Exception) {
                        openFailed(attempt, CameraFault.CONFIGURE, CameraRefusal.FAILED, e.javaClass.simpleName)
                        return
                    }
                    val became = synchronized(lock) {
                        state.openSucceeded(attempt.id).also { ok ->
                            if (ok) { outcome = "ok"; fault = CameraFault.NONE; faultDetail = null; recovery = "none" }
                        }
                    }
                    if (became) scheduleTick(attempt)
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    openFailed(attempt, CameraFault.CONFIGURE, CameraRefusal.FAILED, "configure_failed")
                }
            }, h)
        } catch (e: Exception) {
            openFailed(attempt, CameraFault.CONFIGURE, CameraRefusal.FAILED, e.javaClass.simpleName)
        }
    }

    /**
     * Attempt [attempt] failed. Its own hardware is always released; the state machine is consulted only
     * if the attempt is still current, so a superseded attempt's late failure can never act on a newer one.
     */
    private fun openFailed(attempt: Attempt, f: CameraFault, refusal: CameraRefusal, detail: String?) {
        attempt.release()
        val decision: Failure
        synchronized(lock) {
            decision = state.openFailed(attempt.id, f, refusal, nowMs())
            if (decision != Failure.Ignored) {
                outcome = refusal.token
                fault = f
                faultDetail = HaTransportFault.sanitize(detail)
                if (current === attempt) current = null
            }
        }
        when (decision) {
            Failure.Ignored -> Unit
            Failure.Close -> {
                foreground.demote()
                indicator.hide()
                synchronized(lock) {
                    recovery = if (state.phase == Phase.DEGRADED) "reattach a client after the hold or toggle the camera setting"
                    else "next open retries after ${state.retryNotBeforeMs - nowMs()}ms"
                }
                quitThread()
            }
            is Failure.Reopen -> {
                foreground.demote()
                indicator.hide()
                synchronized(lock) { recovery = "reopening in ${decision.afterMs}ms (attempt ${decision.attempt})" }
                scheduleReopen(decision.afterMs)
            }
            is Failure.Degrade -> degrade(f, decision.attempt)
        }
    }

    /** Fire a reopen decided earlier: a fresh attempt identity with a fresh first-frame grace. */
    private fun scheduleReopen(afterMs: Long) {
        val h = synchronized(lock) { handler } ?: return
        h.postDelayed({
            val next = synchronized(lock) { state.reopenAttempt(nowMs()) } ?: return@postDelayed
            open(next)
        }, afterMs)
    }

    // ---- frames -------------------------------------------------------------------------------------

    private fun onFrame(image: Image, attempt: Attempt) {
        image.use { img ->
            val now = nowMs()
            val ready: List<CompletableFuture<ByteArray?>>
            synchronized(lock) {
                ready = state.frame(attempt.id, now)
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
            val jpeg = toJpegOrNull(img)
            synchronized(lock) {
                if (jpeg == null) {
                    fault = CameraFault.ENCODE
                    faultDetail = null
                    outcome = CameraRefusal.ENCODE.token
                } else if (fault == CameraFault.ENCODE) {
                    fault = CameraFault.NONE
                    outcome = "ok"
                }
            }
            ready.forEach { it.complete(jpeg) }
        }
    }

    // ---- watchdog -----------------------------------------------------------------------------------

    private fun scheduleTick(attempt: Attempt) {
        val h = synchronized(lock) { handler } ?: return
        h.postDelayed({ tick(attempt) }, policy.starvationMs / 2)
    }

    private fun tick(attempt: Attempt) {
        synchronized(lock) { if (!state.isCurrent(attempt.id) || state.phase != Phase.LIVE) return }
        // The light is a continuing prerequisite: a session the room is not being told about closes.
        val indicated = indicator.refresh()
        val decision = synchronized(lock) { state.tick(attempt.id, nowMs(), enabled(), indicated) } ?: return
        when (decision) {
            CameraSessionPolicy.Decision.Continue -> scheduleTick(attempt)
            is CameraSessionPolicy.Decision.Close -> {
                val ended = synchronized(lock) {
                    val token = when (decision.reason) {
                        CameraSessionPolicy.CloseReason.DISABLED -> CameraRefusal.DISABLED.token
                        CameraSessionPolicy.CloseReason.STOPPING -> CameraRefusal.STOPPING.token
                        CameraSessionPolicy.CloseReason.IDLE -> "ok"
                    }
                    outcome = token
                    if (decision.reason == CameraSessionPolicy.CloseReason.DISABLED) state.disable() else state.release(-1L)
                    takeCurrentLocked()
                }
                // The state machine already ended the session (disable) or the leases are gone; either way
                // this attempt's hardware comes down now.
                finishAttempt(ended ?: attempt, stopping = decision.reason == CameraSessionPolicy.CloseReason.STOPPING)
            }
            is CameraSessionPolicy.Decision.Reopen -> {
                synchronized(lock) {
                    fault = decision.fault
                    outcome = if (decision.fault == CameraFault.INDICATION) CameraRefusal.INDICATION.token else CameraRefusal.FAILED.token
                    recovery = "reopening in ${decision.afterMs}ms (attempt ${decision.attempt})"
                    state.reopening(decision.attempt)
                    if (current === attempt) current = null
                }
                attempt.release()
                foreground.demote()
                indicator.hide()
                scheduleReopen(decision.afterMs)
            }
            is CameraSessionPolicy.Decision.Degrade -> {
                synchronized(lock) { if (current === attempt) current = null }
                attempt.release()
                degrade(decision.fault, decision.attempt)
            }
        }
    }

    private fun deviceFault(attempt: Attempt, f: CameraFault, detail: String?) {
        val opening: Boolean
        synchronized(lock) {
            if (!state.noteDeviceFault(attempt.id, f)) {
                // A superseded attempt reporting late: only its own hardware is touched.
                opening = false
            } else {
                fault = f
                faultDetail = HaTransportFault.sanitize(detail)
                opening = state.phase == Phase.OPENING
            }
        }
        if (opening) openFailed(attempt, f, CameraRefusal.FAILED, detail) else if (!synchronized(lock) { state.isCurrent(attempt.id) }) attempt.release()
        // A live attempt's fault is picked up by the next tick, which decides the ladder.
    }

    private fun degrade(f: CameraFault, attempt: Int) {
        foreground.demote()
        indicator.hide()
        synchronized(lock) {
            state.degraded(attempt, nowMs())
            fault = f
            outcome = CameraRefusal.FAILED.token
            recovery = "reattach a client after the hold or toggle the camera setting"
            current = null
        }
        quitThread()
        Log.w(TAG, "camera session degraded after $attempt failures: ${f.wire}")
    }

    // ---- close --------------------------------------------------------------------------------------

    /** Under [lock]: detach the current attempt so its hardware can come down outside the lock. */
    private fun takeCurrentLocked(): Attempt? = current.also { current = null }

    /** Bring one attempt's hardware down; the state machine has already ended the session. */
    private fun finishAttempt(attempt: Attempt, stopping: Boolean) {
        attempt.release()
        foreground.demote()
        if (stopping) indicator.forceHide() else indicator.hide()
        quitThread()
    }

    private fun quitThread() {
        val t: HandlerThread?
        synchronized(lock) {
            // Keep the thread while a reopen or another attempt still needs it.
            if (state.phase == Phase.OPENING || state.phase == Phase.LIVE) return
            t = thread; thread = null; handler = null
        }
        t?.quitSafely()
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
                phase == Phase.DEGRADED -> "check the camera hardware; a new client after the hold or a setting toggle retries"
                fault == CameraFault.FOREGROUND -> "wake the panel: a camera session can only start while the dashboard is visible"
                fault == CameraFault.INDICATION -> "the camera-in-use light could not be shown; check overlay permission and the LED"
                fault == CameraFault.ENCODE -> "the camera delivers frames but they could not be encoded; report this with the panel's diagnostics"
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
        val targetAspect = target.width.toDouble() / target.height
        return sizes
            .filter { it.width <= target.width && it.height <= target.height }
            .maxWithOrNull(
                compareBy<Size> { kotlin.math.abs(it.width.toDouble() / it.height - targetAspect) < 0.2 }
                    .thenBy { it.width.toLong() * it.height },
            )
    }

    /** The lowest sensor rate on offer at or above the cap; delivery is paced to the cap regardless. */
    private fun chooseFpsRange(characteristics: CameraCharacteristics, maxFps: Int): Range<Int>? {
        val ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty()
        if (ranges.isEmpty()) return null
        return ranges.filter { it.upper <= maxFps }.maxByOrNull { it.upper }
            ?: ranges.minWithOrNull(compareBy<Range<Int>> { it.upper }.thenBy { it.lower })
    }

    /** JPEG bytes, or null when the compressor refused or threw; the caller classifies that as an encode fault. */
    private fun toJpegOrNull(image: Image): ByteArray? = runCatching {
        val nv21 = yuv420ToNv21(image)
        val out = ByteArrayOutputStream()
        val ok = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            .compressToJpeg(Rect(0, 0, image.width, image.height), JPEG_QUALITY, out)
        if (ok && out.size() > 0) out.toByteArray() else null
    }.onFailure { Log.w(TAG, "JPEG encode failed: ${it.javaClass.simpleName}") }.getOrNull()

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
