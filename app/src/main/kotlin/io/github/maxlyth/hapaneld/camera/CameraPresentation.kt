package io.github.maxlyth.hapaneld.camera

import org.json.JSONObject

/** The capped output sizes a stream or snapshot may ask for; the profile cap clamps every request. */
enum class CameraResolution(val wire: String, val width: Int, val height: Int) {
    P480("480p", 640, 480),
    P720("720p", 1280, 720),
    P1080("1080p", 1920, 1080);

    companion object {
        fun parse(raw: String?): CameraResolution? = entries.firstOrNull { it.wire == raw }

        /** Never more than [cap]: a URL may ask for less than the ceiling and never for more. */
        fun clamp(requested: CameraResolution, cap: CameraResolution): CameraResolution =
            if (requested.ordinal > cap.ordinal) cap else requested
    }
}

/** Why a consumer was refused a frame. Each token is safe to put in an HTTP body verbatim. */
enum class CameraRefusal(val token: String) {
    /** This board has no camera. */
    ABSENT("camera-unavailable"),
    /** The master switch is off. */
    DISABLED("camera-disabled"),
    /** The switch is on but Android has not granted the app the camera permission. */
    PERMISSION("camera-permission-needed"),
    /** The camera-in-use indicator could not be shown, so the camera did not open (contract §2). */
    INDICATION("camera-indication-unavailable"),
    /** Android refused the camera-typed foreground service, which happens when no activity is visible. */
    FOREGROUND("camera-foreground-refused"),
    /** The session exists but produced no frame within the bounded wait. */
    STARVED("camera-starved"),
    /** The device could not be opened or configured; see the status object for the classified fault. */
    FAILED("camera-unavailable"),
    /** The service is tearing down. */
    STOPPING("camera-stopping"),
}

sealed interface SnapshotResult {
    class Jpeg(val bytes: ByteArray) : SnapshotResult
    data class Refused(val reason: CameraRefusal) : SnapshotResult
}

/**
 * What the HTTP surface needs from the camera owner: the status projection and a blocking snapshot.
 * The owner behind it opens the camera only for the duration of the request and closes it when no
 * other subscriber remains, so a caller must expect the open cost on every snapshot.
 */
interface CameraSurface {
    fun presentation(): CameraPresentation

    /** Blocking; call from an IO dispatcher. A null [requested] takes the profile default. */
    fun snapshot(requested: CameraResolution?): SnapshotResult
}

/** The projection for a board with no camera at all — the status object is emitted unconditionally. */
object AbsentCameraSurface : CameraSurface {
    override fun presentation(): CameraPresentation = CameraPresentation.absent()
    override fun snapshot(requested: CameraResolution?): SnapshotResult =
        SnapshotResult.Refused(CameraRefusal.ABSENT)
}

enum class CameraState(val wire: String) {
    /** No camera on this board. Emitted rather than omitted, so a consumer never infers from absence. */
    ABSENT("absent"),
    DISABLED("disabled"),
    PERMISSION_NEEDED("permission_needed"),
    /** Enabled, permitted, and closed because nobody is watching. Idle cost is zero here. */
    IDLE("idle"),
    OPENING("opening"),
    LIVE("live"),
    /** The retry ceiling was reached; the session stays visibly here until a subscriber reattaches. */
    DEGRADED("degraded"),
    STOPPING("stopping"),
}

/** Closed vocabulary. A raw exception message never becomes one of these; only its class name does. */
enum class CameraFault(val wire: String) {
    NONE("none"),
    INDICATION("indication"),
    FOREGROUND("foreground"),
    PERMISSION("permission"),
    OPEN("open"),
    CONFIGURE("configure"),
    DEVICE_ERROR("device_error"),
    DISCONNECTED("disconnected"),
    STARVED("starved"),
}

/** Which route is telling the room the camera is on. `none` is only legal when the camera is closed. */
enum class CameraIndication(val wire: String) { NONE("none"), OVERLAY("overlay"), LED("led") }

/**
 * The one public-safe projection of camera session health, rendered identically by `GET /api/v1/status`
 * and the `/api/v1/diag` dump — so severity cannot drift between the two.
 *
 * Modelled on [io.github.maxlyth.hapaneld.RendererAdmissionPresentation], including its boundary:
 * device paths, client addresses, credentials and raw exception text never enter this type. The
 * fault arrives already classified as [CameraFault]; [faultDetail] is a sanitized class name.
 *
 * [lastFrameAgeMs] is named for what it measures: the age of a real captured frame, never the age of a
 * verdict or a state change. The renderer's `observed_age_ms` was misread once because it was not.
 */
data class CameraPresentation(
    val state: CameraState,
    /** Wire form of the last outcome: `ok`, or the refusal token that last blocked a consumer. */
    val outcome: String,
    val fault: CameraFault,
    val faultDetail: String?,
    /** How a degraded state may recover on its own, or `none` when nothing is degraded. */
    val recovery: String,
    /** Subscribers currently holding the session open. */
    val clients: Int,
    val lastFrameAgeMs: Long?,
    val consecutiveFailures: Int,
    val indication: CameraIndication,
    val summary: String,
    val action: String,
) {
    /** Stable flat JSON for `GET /api/v1/status`; `state` stays first for shell clients. */
    fun statusJson(): String = buildString {
        fun field(name: String, value: Any?) {
            if (length > 1) append(',')
            append(JSONObject.quote(name)).append(':')
            when (value) {
                null -> append("null")
                is Number -> append(value)
                is Boolean -> append(value)
                else -> append(JSONObject.quote(value.toString()))
            }
        }
        append('{')
        field("state", state.wire)
        field("outcome", outcome)
        field("fault", fault.wire)
        field("fault_detail", faultDetail)
        field("recovery", recovery)
        field("clients", clients)
        field("last_frame_age_ms", lastFrameAgeMs)
        field("consecutive_failures", consecutiveFailures)
        field("indication", indication.wire)
        field("live", state == CameraState.LIVE)
        field("summary", summary)
        field("action", action)
        append('}')
    }

    /** One terminal-safe line for the copy-paste support dump. */
    fun diagnosticLine(): String = buildString {
        append("[camera] state=").append(state.wire)
        append(" outcome=").append(outcome)
        append(" fault=").append(fault.wire)
        append(" detail=").append(faultDetail ?: "none")
        append(" recovery=").append(recovery)
        append(" clients=").append(clients)
        append(" last_frame=").append(lastFrameAgeMs?.let { fmtAge(it) } ?: "never")
        append(" failures=").append(consecutiveFailures)
        append(" indication=").append(indication.wire)
    }

    companion object {
        fun absent(): CameraPresentation = CameraPresentation(
            state = CameraState.ABSENT, outcome = CameraRefusal.ABSENT.token, fault = CameraFault.NONE,
            faultDetail = null, recovery = "none", clients = 0, lastFrameAgeMs = null,
            consecutiveFailures = 0, indication = CameraIndication.NONE,
            summary = "no camera on this panel", action = "none",
        )

        fun disabled(): CameraPresentation = CameraPresentation(
            state = CameraState.DISABLED, outcome = CameraRefusal.DISABLED.token, fault = CameraFault.NONE,
            faultDetail = null, recovery = "none", clients = 0, lastFrameAgeMs = null,
            consecutiveFailures = 0, indication = CameraIndication.NONE,
            summary = "camera off", action = "turn on the camera setting to serve snapshots",
        )

        fun permissionNeeded(): CameraPresentation = CameraPresentation(
            state = CameraState.PERMISSION_NEEDED, outcome = CameraRefusal.PERMISSION.token,
            fault = CameraFault.PERMISSION, faultDetail = null, recovery = "none", clients = 0,
            lastFrameAgeMs = null, consecutiveFailures = 0, indication = CameraIndication.NONE,
            summary = "camera on, but Android has not granted the permission",
            action = "grant the camera permission on the panel when prompted",
        )

        internal fun fmtAge(ms: Long): String {
            val s = ms / 1000
            return when {
                s < 60 -> "${s}s"
                s < 3600 -> "${s / 60}m${s % 60}s"
                else -> "${s / 3600}h${(s % 3600) / 60}m"
            }
        }
    }
}
