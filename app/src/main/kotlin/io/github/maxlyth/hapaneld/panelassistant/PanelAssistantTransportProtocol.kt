package io.github.maxlyth.hapaneld.panelassistant

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** What this panel says about itself in `panel_assistant/hello`. */
internal data class PanelAssistantHelloIdentity(
    /** Lowercase hex discovery identity; null when Android supplies no Android ID. */
    val did: String?,
    val appVersion: String,
    val appVersionCode: Int,
)

/** The integration's accepted reply: what this session may use and whom it talks to. */
internal data class PanelAssistantSession(
    val protocol: Int,
    val token: String,
    val authority: String,
    val capabilities: List<String>,
    val integrationVersion: String,
) {
    /** The session token is a bearer for this session's requests; keep it out of logs. */
    override fun toString(): String =
        "PanelAssistantSession(protocol=$protocol, authority=$authority, capabilities=$capabilities, " +
            "integrationVersion=$integrationVersion)"
}

internal sealed interface PanelAssistantHelloOutcome {
    data class Accepted(val session: PanelAssistantSession) : PanelAssistantHelloOutcome

    /** An error result; [code] is Core's or the integration's closed code, never display text. */
    data class Refused(val code: String) : PanelAssistantHelloOutcome
}

/** An event delivered on the `hello` subscription. */
internal sealed interface PanelAssistantSessionEvent {
    data class Closed(val reason: String) : PanelAssistantSessionEvent

    /**
     * A command for [channel]. [session] is the token the integration sent it for, compared with the live
     * session before anything runs; [value] is the typed wire value, JSON null for a button press.
     * [deadlineMs] is null when the event carried a malformed or out-of-range deadline.
     */
    data class Command(
        val commandId: String,
        val session: String?,
        val channel: String?,
        val value: Any?,
        val deadlineMs: Long?,
    ) : PanelAssistantSessionEvent

    /** A command event with no usable `command_id`: there is nothing to answer it with. */
    data object MalformedCommand : PanelAssistantSessionEvent

    /** Any other kind, which this build does not act on. */
    data class Ignored(val kind: String) : PanelAssistantSessionEvent
}

/** The result answering one `report_state` request, correlated by its parsed message id. */
internal sealed interface PanelAssistantReportResult {
    val id: Long

    /** Every observation in the batch was applied, except those [rejected] by channel with a code. */
    data class Acknowledged(override val id: Long, val rejected: Map<String, String>) : PanelAssistantReportResult

    data class Failed(override val id: Long, val code: String) : PanelAssistantReportResult
}

/**
 * Wire vocabulary for the native transport's handshake, bound by the protocol specification's
 * sections 5 and 6. Pure: every function here is a translation between JSON text and typed values,
 * so the owner's lifecycle can be tested without a socket.
 */
internal object PanelAssistantTransportProtocol {
    const val PROTOCOL_MIN = 1
    const val PROTOCOL_MAX = 1
    const val COMMAND_HELLO = "panel_assistant/hello"
    const val COMMAND_REPORT_STATE = "panel_assistant/report_state"
    const val COMMAND_COMMAND_RESULT = "panel_assistant/command_result"

    const val AUTHORITY_MQTT = "mqtt"
    const val AUTHORITY_SHADOW = "shadow"
    const val AUTHORITY_NATIVE = "native"
    val AUTHORITIES: Set<String> = setOf(AUTHORITY_MQTT, AUTHORITY_SHADOW, AUTHORITY_NATIVE)

    const val CAPABILITY_STATE = "state"
    const val CAPABILITY_COMMANDS = "commands"
    const val CAPABILITY_APPROVAL = "approval"

    const val OUTCOME_APPLIED = "applied"
    const val OUTCOME_SUPERSEDED = "superseded"
    const val OUTCOME_PENDING_APPROVAL = "pending_approval"
    const val OUTCOME_REFUSED = "refused"
    const val OUTCOME_FAILED = "failed"

    const val DEFAULT_DEADLINE_MS = 10_000L
    const val MAX_DEADLINE_MS = 60_000L

    const val SYNC_FULL_BEGIN = "full_begin"
    const val SYNC_DELTA = "delta"
    const val SYNC_FULL_END = "full_end"
    const val STATE_KNOWN = "known"
    const val STATE_UNAVAILABLE = "unavailable"

    const val CODE_UNKNOWN_COMMAND = "unknown_command"
    const val CODE_UNKNOWN_PANEL = "unknown_panel"
    const val CODE_PROTOCOL_UNSUPPORTED = "protocol_unsupported"
    const val CODE_PANEL_USER_MISMATCH = "panel_user_mismatch"
    const val CODE_PANEL_IDENTITY_UNAVAILABLE = "panel_identity_unavailable"
    const val CODE_INVALID_FORMAT = "invalid_format"
    const val CODE_SESSION_UNKNOWN = "session_unknown"

    const val REASON_SUPERSEDED = "superseded"

    /**
     * Capabilities a fully wired build serves: state reporting, and commands with panel-side approval. It
     * reports no events, and advertising a capability it cannot serve would leave the integration waiting.
     */
    val CAPABILITIES: List<String> = listOf(CAPABILITY_STATE, CAPABILITY_COMMANDS, CAPABILITY_APPROVAL)

    /**
     * The handshake contract this build implements, in canonical form. The specification's shared
     * contract file does not exist yet; until it does, the digest the panel sends covers exactly this
     * text, so a change to the handshake vocabulary changes the digest the integration records.
     */
    internal const val CANONICAL_CONTRACT: String =
        """{"protocol":{"min":1,"max":1},"commands":["panel_assistant/hello","panel_assistant/report_state","panel_assistant/command_result"],"capabilities":["state","commands","approval"]}"""

    val CONTRACT_DIGEST: String = MessageDigest.getInstance("SHA-256")
        .digest(CANONICAL_CONTRACT.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private val CODE = Regex("^[a-z][a-z0-9_]{0,63}$")
    private val COMMAND_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val VERSION = Regex("^[0-9A-Za-z][0-9A-Za-z.+-]{0,63}$")
    private const val MAX_SESSION_TOKEN_CHARS = 64
    private const val MAX_CAPABILITIES = 16

    fun hello(
        id: Long,
        identity: PanelAssistantHelloIdentity,
        capabilities: List<String> = emptyList(),
        channels: List<PanelAssistantChannelDescriptor> = emptyList(),
    ): String = JSONObject()
        .put("id", id)
        .put("type", COMMAND_HELLO)
        .put("protocol", JSONObject().put("min", PROTOCOL_MIN).put("max", PROTOCOL_MAX))
        .put("did", identity.did ?: JSONObject.NULL)
        .put(
            "app",
            JSONObject().put("version", identity.appVersion).put("version_code", identity.appVersionCode),
        )
        .put("contract_digest", CONTRACT_DIGEST)
        .put("capabilities", JSONArray(capabilities))
        .put("channels", JSONArray(channels.map { it.toJson() }))
        .toString()

    fun reportState(id: Long, session: String, sync: String, observations: JSONArray): String = JSONObject()
        .put("id", id)
        .put("type", COMMAND_REPORT_STATE)
        .put("session", session)
        .put("sync", sync)
        .put("observations", observations)
        .toString()

    /**
     * Interpret a result frame as a `report_state` answer; null for any frame that is not a result. The
     * caller decides whether its id names an outstanding request. A per-observation rejection whose
     * channel or code is malformed is dropped: it cannot name a channel this panel sent.
     */
    fun reportResult(frame: JSONObject): PanelAssistantReportResult? {
        if (frame.optString("type") != "result") return null
        val id = messageId(frame) ?: return null
        if (frame.opt("success") != true) {
            val code = frame.optJSONObject("error")?.opt("code") as? String
            return PanelAssistantReportResult.Failed(id, code?.takeIf(CODE::matches) ?: CODE_INVALID_FORMAT)
        }
        val rejected = LinkedHashMap<String, String>()
        val list = frame.optJSONObject("result")?.optJSONArray("rejected")
        if (list != null) {
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                val channel = (item.opt("channel") as? String)?.takeIf(CODE::matches) ?: continue
                rejected[channel] = (item.opt("code") as? String)?.takeIf(CODE::matches) ?: CODE_INVALID_FORMAT
            }
        }
        return PanelAssistantReportResult.Acknowledged(id, rejected)
    }

    /** The one final (or `pending_approval` interim) answer to a delivered command. */
    fun commandResult(id: Long, session: String, commandId: String, outcome: String, code: String?): String =
        JSONObject()
            .put("id", id)
            .put("type", COMMAND_COMMAND_RESULT)
            .put("session", session)
            .put("command_id", commandId)
            .put("outcome", outcome)
            .apply { if (code != null) put("code", code) }
            .toString()

    fun ping(id: Long): String = JSONObject().put("id", id).put("type", "ping").toString()

    /**
     * The message id a frame answers, parsed rather than matched as text: a substring test for
     * `"id":1` also matches 10 and above. Null for a frame with no integral id.
     */
    fun messageId(frame: JSONObject): Long? = when (val id = frame.opt("id")) {
        is Int -> id.toLong()
        is Long -> id
        else -> null
    }

    /**
     * Interpret the result frame answering the `hello` sent as [helloId]. Null means the frame is not
     * that result. A success result whose body breaks the contract is a protocol failure, reported
     * as an exception so the caller retries rather than holding a session it cannot describe.
     */
    fun helloOutcome(
        frame: JSONObject,
        helloId: Long,
        offered: List<String> = CAPABILITIES,
    ): PanelAssistantHelloOutcome? {
        if (frame.optString("type") != "result" || messageId(frame) != helloId) return null
        if (frame.opt("success") != true) {
            val code = frame.optJSONObject("error")?.opt("code") as? String
            return PanelAssistantHelloOutcome.Refused(code?.takeIf(CODE::matches) ?: CODE_INVALID_FORMAT)
        }
        val result = frame.optJSONObject("result") ?: throw PanelAssistantProtocolException("hello result has no body")
        val protocol = result.opt("protocol") as? Int
        if (protocol == null || protocol !in PROTOCOL_MIN..PROTOCOL_MAX) {
            throw PanelAssistantProtocolException("hello result names an unoffered protocol")
        }
        val token = result.opt("session") as? String
        if (token.isNullOrEmpty() || token.length > MAX_SESSION_TOKEN_CHARS) {
            throw PanelAssistantProtocolException("hello result has no usable session")
        }
        val authority = (result.opt("authority") as? String)?.takeIf(CODE::matches)
            ?: throw PanelAssistantProtocolException("hello result has no authority")
        val capabilityArray = result.optJSONArray("capabilities")
            ?: throw PanelAssistantProtocolException("hello result has no capabilities")
        if (capabilityArray.length() > MAX_CAPABILITIES) {
            throw PanelAssistantProtocolException("hello result lists too many capabilities")
        }
        val capabilities = (0 until capabilityArray.length()).map { index ->
            (capabilityArray.opt(index) as? String)?.takeIf(CODE::matches)
                ?: throw PanelAssistantProtocolException("hello result has a malformed capability")
        }
        if (!offered.containsAll(capabilities)) {
            throw PanelAssistantProtocolException("hello result grants a capability the panel did not offer")
        }
        val integrationVersion = (result.optJSONObject("integration")?.opt("version") as? String)
            ?.takeIf(VERSION::matches)
            ?: throw PanelAssistantProtocolException("hello result has no integration version")
        return PanelAssistantHelloOutcome.Accepted(
            PanelAssistantSession(protocol, token, authority, capabilities, integrationVersion),
        )
    }

    /** Interpret an event on the `hello` subscription [helloId]; null for any other frame. */
    fun sessionEvent(frame: JSONObject, helloId: Long): PanelAssistantSessionEvent? {
        if (frame.optString("type") != "event" || messageId(frame) != helloId) return null
        val event = frame.optJSONObject("event") ?: return PanelAssistantSessionEvent.Ignored("")
        val kind = (event.opt("kind") as? String)?.takeIf(CODE::matches).orEmpty()
        if (kind == "command") return command(event)
        if (kind != "session_closed") return PanelAssistantSessionEvent.Ignored(kind)
        val reason = (event.opt("reason") as? String)?.takeIf(CODE::matches).orEmpty()
        return PanelAssistantSessionEvent.Closed(reason)
    }

    private fun command(event: JSONObject): PanelAssistantSessionEvent {
        val commandId = (event.opt("command_id") as? String)?.takeIf(COMMAND_ID::matches)
            ?: return PanelAssistantSessionEvent.MalformedCommand
        val deadline = when (val raw = event.opt("deadline_ms")) {
            null -> DEFAULT_DEADLINE_MS
            is Int -> raw.toLong().takeIf { it in 1..MAX_DEADLINE_MS }
            is Long -> raw.takeIf { it in 1..MAX_DEADLINE_MS }
            else -> null
        }
        return PanelAssistantSessionEvent.Command(
            commandId = commandId,
            session = event.opt("session") as? String,
            channel = (event.opt("channel") as? String)?.takeIf(CODE::matches),
            value = if (event.has("value")) event.opt("value") else null,
            deadlineMs = deadline,
        )
    }
}

internal class PanelAssistantProtocolException(message: String) : RuntimeException(message)
