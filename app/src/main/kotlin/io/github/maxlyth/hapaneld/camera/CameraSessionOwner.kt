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
import io.github.maxlyth.hapaneld.camera.CameraSessionState.Release
import io.github.maxlyth.hapaneld.util.HaTransportFault
import io.github.maxlyth.hapaneld.util.localIpv4
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** How the owner opens an encoder; a seam so the `MediaCodec` adapter is not welded into the lifecycle. */
fun interface EncoderFactory {
    fun open(width: Int, height: Int, fps: Int, kbps: Int, handler: Handler, listener: VideoEncoder.Listener): EncoderOpen
}

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
 * than the cap allows are observed for liveness but not delivered. The encoder is one more consumer of
 * those paced frames: it runs exactly while a stream lease exists, bound by the first stream client's
 * parameters within the caps, fed by buffer copy at its own paced rate, and its output fans out through
 * the [transport]. Nothing is kept warm: when the last lease closes, the encoder, the attempt's
 * hardware, the handler thread and the foreground standing are released.
 */
class CameraSessionOwner(
    private val context: Context,
    private val hasCamera: Boolean,
    private val enabled: () -> Boolean,
    private val maxResolution: () -> CameraResolution,
    private val maxFps: () -> Int,
    private val maxKbps: () -> Int,
    private val permissionGranted: () -> Boolean,
    private val indicator: CameraIndicator,
    private val foreground: CameraForegroundGate,
    private val transport: CameraStreamTransport = AbsentStreamTransport,
    private val encoderFactory: EncoderFactory = EncoderFactory { w, h, fps, kbps, handler, listener ->
        MediaCodecH264Encoder.open(w, h, fps, kbps, handler, listener)
    },
    private val localAddress: () -> String? = ::localIpv4,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
) : CameraSurface, CameraStreamSource {

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
        /** The capture size this attempt configured; the encoder, if wanted, encodes exactly this. */
        var size: Size? = null

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

    private var encoder: VideoEncoder? = null
    private var encoderPacer: FramePacer? = null
    private var streamParams: StreamParams? = null
    /** Settled by the running encoder attempt: its parameter sets, or why there is none. */
    private var streamReady = CompletableFuture<StreamOutcome>()
    private val stats = StreamStats()

    private sealed interface StreamOutcome {
        data class Ready(val params: StreamParams) : StreamOutcome
        data class Refused(val reason: CameraRefusal) : StreamOutcome
    }

    init {
        publishPermissionPrompt(freshEnable = false)
        transport.setListening(hasCamera && enabled())
    }

    /** A subscriber's claim on the open session. Close exactly once; closing the last one closes the device. */
    inner class Lease internal constructor(private val id: Long) : AutoCloseable {
        private var open = true
        override fun close() {
            val release: Release
            val ended: Attempt?
            val generation: Long
            synchronized(lock) {
                if (!open) return
                open = false
                release = state.release(id)
                ended = if (release == Release.Close) takeCurrentLocked() else null
                if (release == Release.Close) outcome = "ok"
                generation = state.generation
            }
            when (release) {
                Release.Close -> post { finishAttempt(ended, stopping = false, endedGeneration = generation) }
                Release.StopEncoder -> post { stopEncoderIfUnwanted() }
                Release.None -> Unit
            }
        }
    }

    // ---- CameraSurface ---------------------------------------------------------------------------

    override fun presentation(): CameraPresentation {
        // Both the transport's facts and the interface walk stay outside the owner lock.
        val facts = transport.facts()
        val address = facts.port?.let { localAddress() }
        return synchronized(lock) {
            when {
                !hasCamera -> CameraPresentation.absent()
                state.phase == Phase.STOPPING -> current(facts, address)
                !enabled() -> CameraPresentation.disabled()
                !permissionGranted() -> CameraPresentation.permissionNeeded(streamPort = facts.port)
                else -> current(facts, address)
            }
        }
    }

    override fun snapshot(requested: CameraResolution?): SnapshotResult {
        val lease = acquire(requested) ?: return SnapshotResult.Refused(lastRefusal())
        val waiter = CompletableFuture<ByteArray?>()
        try {
            synchronized(lock) { state.addWaiter(waiter) }
            val bytes = runCatching { waiter.get(SNAPSHOT_WAIT_MS, TimeUnit.MILLISECONDS) }.getOrNull()
            if (bytes != null) return SnapshotResult.Jpeg(bytes)
            // Null means one of three things, told apart by what the session did meanwhile: a teardown
            // that drained the waiter (report the teardown's own refusal), a frame that arrived but would
            // not encode (the encoder's fault, not the camera's), or genuinely no frame in time.
            val refusal = synchronized(lock) {
                when {
                    state.phase == Phase.STOPPING -> CameraRefusal.STOPPING
                    !enabled() -> CameraRefusal.DISABLED
                    state.phase == Phase.DEGRADED -> CameraRefusal.FAILED
                    fault == CameraFault.ENCODE -> CameraRefusal.ENCODE
                    else -> CameraRefusal.STARVED
                }
            }
            return SnapshotResult.Refused(refusal)
        } finally {
            synchronized(lock) { state.removeWaiter(waiter) }
            lease.close()
        }
    }

    // ---- CameraStreamSource ----------------------------------------------------------------------

    override fun acquireStream(request: StreamRequest): StreamAdmission {
        val bound = request.bind(maxResolution(), maxFps(), maxKbps())
        val lease = acquireLease(bound.resolution, LeaseKind.STREAM, bound.binding)
            ?: return StreamAdmission.Refused(lastRefusal())
        val ready = synchronized(lock) {
            val params = streamParams
            if (encoder != null && params != null) CompletableFuture.completedFuture<StreamOutcome>(StreamOutcome.Ready(params)) else streamReady
        }
        return when (val outcome = runCatching { ready.get(ENCODER_WAIT_MS, TimeUnit.MILLISECONDS) }.getOrNull()) {
            is StreamOutcome.Ready -> StreamAdmission.Granted(lease, outcome.params)
            is StreamOutcome.Refused -> {
                lease.close()
                StreamAdmission.Refused(outcome.reason)
            }
            null -> {
                lease.close()
                StreamAdmission.Refused(CameraRefusal.STARVED)
            }
        }
    }

    override fun requestKeyFrame() {
        val enc = synchronized(lock) { encoder } ?: return
        post { enc.requestKeyFrame() }
    }

    // ---- lifecycle used by the service --------------------------------------------------------------

    /** The master switch moved. Off ends a live or opening session at once; on republishes the prompt and listens. */
    fun onEnabledChanged() {
        if (enabled()) {
            publishPermissionPrompt(freshEnable = true)
            transport.setListening(hasCamera)
            return
        }
        publishPermissionPrompt(freshEnable = false)
        transport.setListening(false)
        val disabled: Boolean
        val ended: Attempt?
        val generation: Long
        synchronized(lock) {
            disabled = state.disable()
            if (disabled) outcome = CameraRefusal.DISABLED.token
            ended = if (disabled) takeCurrentLocked() else null
            generation = state.generation
        }
        // Decide on the ending, not on whether an attempt was current: inside a reopen window there is
        // no attempt, but the session still ended and its waiters, encoder and thread must be settled.
        if (disabled) post { finishAttempt(ended, stopping = false, endedGeneration = generation) }
    }

    /** Refuse new leases; existing ones drain through [stop]. */
    fun closeAdmission() {
        synchronized(lock) { admissionClosed = true }
    }

    /** Close everything now and quit the thread. Returns once the device is released. */
    fun stop(): CompletableFuture<Unit> {
        closeAdmission()
        transport.setListening(false)
        val done = CompletableFuture<Unit>()
        val ended: Attempt?
        val h: Handler?
        val generation: Long
        synchronized(lock) {
            state.stopping()
            outcome = CameraRefusal.STOPPING.token
            ended = takeCurrentLocked()
            h = handler
            generation = state.generation
        }
        if (ended == null || h == null) {
            finishAttempt(ended, stopping = true, endedGeneration = generation)
            done.complete(Unit)
            return done
        }
        val posted = h.post {
            finishAttempt(ended, stopping = true, endedGeneration = generation)
            done.complete(Unit)
        }
        if (!posted) {
            // The looper quit between the read and the post: close inline rather than never.
            finishAttempt(ended, stopping = true, endedGeneration = generation)
            done.complete(Unit)
        }
        return done
    }

    // ---- leases -------------------------------------------------------------------------------------

    /** Blocking: returns a snapshot lease on an open session, or null after recording the classified refusal. */
    fun acquire(requested: CameraResolution?): Lease? = acquireLease(requested, LeaseKind.SNAPSHOT, null)

    private fun acquireLease(requested: CameraResolution?, kind: LeaseKind, binding: StreamBinding?): Lease? {
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
        var startEncoderFor: Long? = null
        synchronized(lock) {
            when (val admission = state.acquire(gate, nowMs(), kind, binding)) {
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
                    // A first stream lease on a live session: the attempt is current by construction
                    // (only openSucceeded reaches LIVE, and every LIVE exit nulls it under this lock), and
                    // streamReady is an unsettled future (every encoder ending replaces a done one).
                    if (admission.startEncoder) startEncoderFor = requireNotNull(state.currentAttempt) { "a live session has a current attempt" }
                    pending = state.awaitOpen()
                }
            }
        }
        startEncoderFor?.let { attemptId -> post { startEncoder(attemptId) } }
        val refusal = pending?.let {
            runCatching { it.get(OPEN_WAIT_MS, TimeUnit.MILLISECONDS) }.getOrDefault(CameraRefusal.FAILED)
        }
        if (refusal != null) {
            val release: Release
            val ended: Attempt?
            val generation: Long
            synchronized(lock) {
                release = state.release(leaseId)
                ended = if (release == Release.Close) takeCurrentLocked() else null
                generation = state.generation
            }
            when (release) {
                Release.Close -> post { finishAttempt(ended, stopping = false, endedGeneration = generation) }
                Release.StopEncoder -> post { stopEncoderIfUnwanted() }
                Release.None -> Unit
            }
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
        synchronized(lock) {
            attempt.reader = r
            attempt.size = size
        }
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
                    val became: Boolean
                    val encoderWanted: Boolean
                    synchronized(lock) {
                        became = state.openSucceeded(attempt.id)
                        if (became) { outcome = "ok"; fault = CameraFault.NONE; faultDetail = null; recovery = "none" }
                        encoderWanted = became && state.encoderWanted
                    }
                    if (became) {
                        scheduleTick(attempt)
                        // A stream lease taken before or during the open starts its encoder with the session.
                        if (encoderWanted) startEncoder(attempt.id)
                    }
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
        val generation: Long
        synchronized(lock) {
            decision = state.openFailed(attempt.id, f, refusal, nowMs())
            if (decision != Failure.Ignored) {
                outcome = refusal.token
                fault = f
                faultDetail = HaTransportFault.sanitize(detail)
                if (current === attempt) current = null
            }
            generation = state.generation
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
                endStreamIfStillEnded(refusal, generation)
            }
            is Failure.Reopen -> {
                foreground.demote()
                indicator.hide()
                synchronized(lock) { recovery = "reopening in ${decision.afterMs}ms (attempt ${decision.attempt})" }
                scheduleReopen(decision.afterMs)
            }
            is Failure.Degrade -> degrade(attempt, f, decision.attempt)
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

    // ---- encoder ------------------------------------------------------------------------------------

    private val encoderListener = object : VideoEncoder.Listener {
        override fun onParameterSets(sets: ParameterSets) {
            synchronized(lock) {
                val enc = encoder ?: return
                val facts = enc.facts
                val params = StreamParams(facts.width, facts.height, facts.fps, facts.kbps, facts.name, sets)
                streamParams = params
                streamReady.complete(StreamOutcome.Ready(params))
            }
            transport.onParameterSets(sets)
        }

        override fun onAccessUnit(nals: List<ByteArray>, keyFrame: Boolean, ptsUs: Long, bytes: Int) {
            stats.onFrame(nowMs(), bytes)
            transport.onAccessUnit(nals, keyFrame, ptsUs)
        }

        override fun onEncoderError(detail: String) {
            // The encoder failed, not the camera: stop the encoder alone, hold stream leases off for
            // the policy's backoff so a reconnecting client cannot set the retry rate, and drop the
            // stream clients so they reconnect after it. Snapshot subscribers never notice.
            if (synchronized(lock) { encoder == null }) return
            Log.w(TAG, "encoder failed while streaming ($detail); stopping the stream, snapshots unaffected")
            stopEncoder()
            synchronized(lock) {
                fault = CameraFault.STREAM_ENCODER
                faultDetail = HaTransportFault.sanitize(detail)
                outcome = CameraRefusal.STREAM_ENCODER.token
                state.encoderFailed(nowMs())
            }
            settleStreamWaiters(CameraRefusal.STREAM_ENCODER)
            transport.onStreamEnded()
        }
    }

    /** On the camera thread: open the encoder for the live attempt if a stream lease still wants one. */
    private fun startEncoder(attemptId: Long) {
        val h: Handler
        val size: Size?
        val binding: StreamBinding?
        synchronized(lock) {
            if (!state.isCurrent(attemptId) || state.phase != Phase.LIVE || !state.encoderWanted || encoder != null) return
            h = handler ?: return
            size = current?.takeIf { it.id == attemptId }?.size
            binding = state.streamBinding
        }
        if (size == null || binding == null) return refuseEncoder("no_capture_size")
        // Never above the caps: the binding is already clamped, and the session's own pace bounds the feed.
        val fps = minOf(binding.fps, boundFps)
        when (val opened = encoderFactory.open(size.width, size.height, fps, binding.kbps, h, encoderListener)) {
            is EncoderOpen.Refused -> refuseEncoder(opened.detail)
            is EncoderOpen.Ready -> synchronized(lock) {
                encoder = opened.encoder
                encoderPacer = FramePacer(fps)
                streamParams = null
                stats.reset()
            }
        }
    }

    /**
     * No encoder for this session's stream clients — including the ones a reopen carried over, who would
     * otherwise hold the camera open behind a lit light with nothing ever restarting the encoder.
     * They are dropped like any other stream ending; the hold keeps their reconnects off the codec.
     */
    private fun refuseEncoder(detail: String) {
        synchronized(lock) {
            fault = CameraFault.STREAM_ENCODER
            faultDetail = HaTransportFault.sanitize(detail)
            outcome = CameraRefusal.STREAM_ENCODER.token
            state.encoderFailed(nowMs())
        }
        settleStreamWaiters(CameraRefusal.STREAM_ENCODER)
        transport.onStreamEnded()
        Log.w(TAG, "stream refused: no usable encoder ($detail); snapshots are unaffected")
    }

    /**
     * The last stream lease left; unless another arrived in the meantime, the encoder goes with it. The
     * check and the swap of the waiters' future are one critical section, so a stream lease acquired
     * between them cannot be handed a future that is then refused under it.
     */
    private fun stopEncoderIfUnwanted() {
        val settled: CompletableFuture<StreamOutcome>
        synchronized(lock) {
            if (state.encoderWanted) return
            settled = streamReady
            streamReady = CompletableFuture()
        }
        stopEncoder()
        settled.complete(StreamOutcome.Refused(CameraRefusal.FAILED))
    }

    /**
     * Stop the codec without refusing anyone waiting for parameter sets: across a bounded reopen the
     * pending future is completed by the restarted encoder, so a joiner rides the reopen out instead of
     * being refused a session that is about to come back. A future that was already settled by the
     * stopped encoder is replaced, so a joiner during the reopen waits for the new parameter sets rather
     * than being granted the old encoder's. Terminal paths settle explicitly.
     */
    private fun stopEncoder() {
        val enc: VideoEncoder?
        synchronized(lock) {
            enc = encoder
            encoder = null
            encoderPacer = null
            streamParams = null
            if (streamReady.isDone) streamReady = CompletableFuture()
        }
        if (enc == null) return
        runCatching { enc.close() }
        stats.reset()
        transport.onEncoderStopped()
    }

    /** Everyone waiting for parameter sets learns [refusal]; the next encoder attempt gets a fresh future. */
    private fun settleStreamWaiters(refusal: CameraRefusal) {
        val settled: CompletableFuture<StreamOutcome>
        synchronized(lock) {
            settled = streamReady
            streamReady = CompletableFuture()
        }
        settled.complete(StreamOutcome.Refused(refusal))
    }

    /**
     * The stream side of a session ending, applied only if that session is still the one that ended:
     * the ending is posted, and a new session may have started — with its own stream clients and
     * waiters — before it runs. Those belong to the new session and must not be dropped by the old one.
     */
    private fun endStreamIfStillEnded(refusal: CameraRefusal, endedGeneration: Long) {
        if (synchronized(lock) { state.generation != endedGeneration }) return
        settleStreamWaiters(refusal)
        transport.onStreamEnded()
    }

    // ---- frames -------------------------------------------------------------------------------------

    private fun onFrame(image: Image, attempt: Attempt) {
        image.use { img ->
            val now = nowMs()
            val ready: List<CompletableFuture<ByteArray?>>
            val enc: VideoEncoder?
            synchronized(lock) {
                ready = state.frame(attempt.id, now) ?: return
                // The frame-rate cap bounds delivery: a frame arriving sooner than the cap allows is
                // observed for liveness but not handed on to anyone.
                val interval = 1_000L / boundFps
                if (now - lastDeliveredAtMs < interval) {
                    ready.forEach { state.addWaiter(it) }
                    return
                }
                lastDeliveredAtMs = now
                enc = encoder?.takeIf { encoderPacer?.admit(now) == true }
            }
            enc?.feed(img, now * 1_000L)
            if (ready.isEmpty()) return
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

    /** What the tick decided, applied to hardware outside the lock once the state machine has moved. */
    private sealed interface TickAction {
        data object Continue : TickAction
        data class Finish(val ended: Attempt?, val stopping: Boolean, val generation: Long) : TickAction
        data class Reopen(val afterMs: Long) : TickAction
        data class Degrade(val fault: CameraFault, val attempt: Int, val generation: Long) : TickAction
    }

    private fun tick(attempt: Attempt) {
        synchronized(lock) { if (!state.isCurrent(attempt.id) || state.phase != Phase.LIVE) return }
        // The light is a continuing prerequisite: a session the room is not being told about closes.
        // Blocking LED work, so it runs outside the lock; the decision below re-checks the attempt.
        val indicated = indicator.refresh()
        // Decide AND move the state machine in one critical section, so a disable, a last-lease release
        // or a stop that lands after the decision cannot be overtaken by it: every transition below is
        // guarded on the attempt still being current, and a refused transition is a no-op here.
        val action: TickAction = synchronized(lock) {
            val decision = state.tick(attempt.id, nowMs(), enabled(), indicated) ?: return
            when (decision) {
                CameraSessionPolicy.Decision.Continue -> TickAction.Continue
                is CameraSessionPolicy.Decision.Close -> {
                    // Every close goes through a real state-machine ending, never a sentinel. IDLE here
                    // means the policy saw no clients, which cannot happen on a live session because the
                    // last lease ends it synchronously; endNow() is the honest no-op if it ever does.
                    outcome = when (decision.reason) {
                        CameraSessionPolicy.CloseReason.DISABLED -> CameraRefusal.DISABLED.token
                        CameraSessionPolicy.CloseReason.STOPPING -> CameraRefusal.STOPPING.token
                        CameraSessionPolicy.CloseReason.IDLE -> "ok"
                    }
                    val stopping = decision.reason == CameraSessionPolicy.CloseReason.STOPPING
                    if (stopping) state.stopping() else state.endNow()
                    TickAction.Finish(takeCurrentLocked(), stopping, state.generation)
                }
                is CameraSessionPolicy.Decision.Reopen -> {
                    if (!state.reopening(attempt.id, decision.attempt)) return
                    fault = decision.fault
                    outcome = if (decision.fault == CameraFault.INDICATION) CameraRefusal.INDICATION.token else CameraRefusal.FAILED.token
                    recovery = "reopening in ${decision.afterMs}ms (attempt ${decision.attempt})"
                    if (current === attempt) current = null
                    TickAction.Reopen(decision.afterMs)
                }
                is CameraSessionPolicy.Decision.Degrade -> {
                    if (!state.degraded(attempt.id, decision.attempt, nowMs())) return
                    fault = decision.fault
                    outcome = CameraRefusal.FAILED.token
                    recovery = "reattach a client after the hold or toggle the camera setting"
                    current = null
                    TickAction.Degrade(decision.fault, decision.attempt, state.generation)
                }
            }
        }
        when (action) {
            TickAction.Continue -> scheduleTick(attempt)
            is TickAction.Finish -> finishAttempt(action.ended ?: attempt, stopping = action.stopping, endedGeneration = action.generation)
            is TickAction.Reopen -> {
                // The encoder comes down with the capture beneath it and restarts with the reopened
                // session; stream clients keep their place and joiners ride the reopen out.
                stopEncoder()
                attempt.release()
                foreground.demote()
                indicator.hide()
                scheduleReopen(action.afterMs)
            }
            is TickAction.Degrade -> {
                stopEncoder()
                attempt.release()
                foreground.demote()
                indicator.hide()
                quitThread()
                endStreamIfStillEnded(CameraRefusal.FAILED, action.generation)
                Log.w(TAG, "camera session degraded after ${action.attempt} failures: ${action.fault.wire}")
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

    private fun degrade(attempt: Attempt, f: CameraFault, count: Int) {
        // openFailed() has already moved the state machine to DEGRADED for this attempt; this only
        // brings the hardware side down and records the presentation.
        foreground.demote()
        indicator.hide()
        val generation: Long
        synchronized(lock) {
            fault = f
            outcome = CameraRefusal.FAILED.token
            recovery = "reattach a client after the hold or toggle the camera setting"
            if (current === attempt) current = null
            generation = state.generation
        }
        quitThread()
        endStreamIfStillEnded(CameraRefusal.FAILED, generation)
        Log.w(TAG, "camera session degraded after $count failures: ${f.wire}")
    }

    // ---- close --------------------------------------------------------------------------------------

    /** Under [lock]: detach the current attempt so its hardware can come down outside the lock. */
    private fun takeCurrentLocked(): Attempt? = current.also { current = null }

    /**
     * Bring the session down: the state machine has already ended it, as generation [endedGeneration].
     * The encoder stops first so nothing is produced while the capture beneath it is torn down; then
     * the attempt's hardware, if any; then — only if no newer session has started in the meantime —
     * every stream client is dropped so it reconnects and pays the open cost.
     */
    private fun finishAttempt(attempt: Attempt?, stopping: Boolean, endedGeneration: Long) {
        stopEncoder()
        attempt?.release()
        foreground.demote()
        if (stopping) indicator.forceHide() else indicator.hide()
        quitThread()
        endStreamIfStillEnded(if (stopping) CameraRefusal.STOPPING else lastRefusal(), endedGeneration)
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
        // A looper that quit between the read and the post refuses the post; run inline rather than drop it.
        if (h == null || !h.post(action)) action()
    }

    private fun publishPermissionPrompt(freshEnable: Boolean) {
        CameraPermissionPrompt.publish(
            wantsPermission = hasCamera && enabled() && !permissionGranted(),
            freshEnable = freshEnable && hasCamera,
        )
    }

    private fun current(facts: StreamTransportFacts, address: String?): CameraPresentation {
        val now = nowMs()
        val last = state.lastFrameAtMs
        val phase = state.phase
        val clients = state.clients
        val streaming = state.streamClients
        val failures = state.consecutiveFailures
        val encoderFacts = encoder?.facts
        val presented = when (phase) {
            Phase.IDLE -> CameraState.IDLE
            Phase.OPENING -> CameraState.OPENING
            Phase.LIVE -> CameraState.LIVE
            Phase.DEGRADED -> CameraState.DEGRADED
            Phase.STOPPING -> CameraState.STOPPING
        }
        // The warning travels with the URL: the place a person copies it from is the place they are
        // about to paste it into a card on this very panel, which is the loop the panel cannot afford.
        val stream = when {
            facts.port == null -> "stream not listening"
            address != null -> "stream at rtsp://$address:${facts.port}$STREAM_PATH (not for this panel's own dashboard)"
            else -> "stream listening on port ${facts.port}"
        }
        return CameraPresentation(
            state = presented, outcome = outcome, fault = fault, faultDetail = faultDetail, recovery = recovery,
            clients = clients, lastFrameAgeMs = last?.let { (now - it).coerceAtLeast(0) },
            consecutiveFailures = failures, indication = indicator.route(),
            summary = when (phase) {
                Phase.LIVE -> "camera open for $clients client${if (clients == 1) "" else "s"}" +
                    (if (streaming > 0) " ($streaming streaming)" else "") + "; $stream"
                Phase.OPENING -> "camera opening; $stream"
                Phase.DEGRADED -> "camera gave up after $failures failures (${fault.wire}); $stream"
                Phase.STOPPING -> "camera stopping"
                Phase.IDLE -> "camera closed; nobody is watching; $stream"
            },
            action = when {
                phase == Phase.DEGRADED -> "check the camera hardware; a new client after the hold or a setting toggle retries"
                fault == CameraFault.FOREGROUND -> "wake the panel: a camera session can only start while the dashboard is visible"
                fault == CameraFault.INDICATION -> "the camera-in-use light could not be shown; check overlay permission and the LED"
                fault == CameraFault.ENCODE -> "the camera delivers frames but they could not be encoded; report this with the panel's diagnostics"
                fault == CameraFault.STREAM_ENCODER -> "no hardware H.264 encoder fits the camera bitrate cap; snapshots still work, streaming does not"
                else -> "none"
            },
            streamClients = streaming,
            streamPort = facts.port,
            encoder = encoderFacts?.name,
            encodeWidth = encoderFacts?.width,
            encodeHeight = encoderFacts?.height,
            encodeFps = encoderFacts?.fps,
            encodeKbps = encoderFacts?.kbps,
            deliveredFps = if (encoderFacts != null) stats.fps(now) else null,
            deliveredKbps = if (encoderFacts != null) stats.kbps(now) else null,
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
        /** Codec start plus its first config buffer is well under a second; a bound, not a budget. */
        private const val ENCODER_WAIT_MS = 6_000L
        private const val JPEG_QUALITY = 85
        const val STREAM_PATH = RtspSession.MOUNT_PATH

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
