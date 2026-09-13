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
)

internal sealed interface PanelAssistantHelloOutcome {
    data class Accepted(val session: PanelAssistantSession) : PanelAssistantHelloOutcome

    /** An error result; [code] is Core's or the integration's closed code, never display text. */
    data class Refused(val code: String) : PanelAssistantHelloOutcome
}

/** An event delivered on the `hello` subscription. */
internal sealed interface PanelAssistantSessionEvent {
    data class Closed(val reason: String) : PanelAssistantSessionEvent

    /** Any other kind. Commands are never granted in this protocol slice, so nothing is actionable. */
    data class Ignored(val kind: String) : PanelAssistantSessionEvent
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

    const val CODE_UNKNOWN_COMMAND = "unknown_command"
    const val CODE_UNKNOWN_PANEL = "unknown_panel"
    const val CODE_PROTOCOL_UNSUPPORTED = "protocol_unsupported"
    const val CODE_PANEL_USER_MISMATCH = "panel_user_mismatch"
    const val CODE_PANEL_IDENTITY_UNAVAILABLE = "panel_identity_unavailable"
    const val CODE_INVALID_FORMAT = "invalid_format"

    const val REASON_ENTRY_UNLOADED = "entry_unloaded"

    /**
     * Capabilities this build serves. Empty on purpose: this build reports no state, no events and
     * accepts no commands, and advertising a capability it cannot serve would leave the integration
     * waiting for a full sync that never comes.
     */
    val CAPABILITIES: List<String> = emptyList()

    /**
     * The handshake contract this build implements, in canonical form. The specification's shared
     * contract file does not exist yet; until it does, the digest the panel sends covers exactly this
     * text, so a change to the handshake vocabulary changes the digest the integration records.
     */
    private val CANONICAL_CONTRACT: String = JSONObject()
        .put("protocol", JSONObject().put("min", PROTOCOL_MIN).put("max", PROTOCOL_MAX))
        .put("commands", JSONArray().put(COMMAND_HELLO))
        .put("capabilities", JSONArray(CAPABILITIES))
        .toString()

    val CONTRACT_DIGEST: String = MessageDigest.getInstance("SHA-256")
        .digest(CANONICAL_CONTRACT.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private val CODE = Regex("^[a-z][a-z0-9_]{0,63}$")
    private val VERSION = Regex("^[0-9A-Za-z][0-9A-Za-z.+-]{0,63}$")
    private const val MAX_SESSION_TOKEN_CHARS = 64
    private const val MAX_CAPABILITIES = 16

    fun hello(id: Long, identity: PanelAssistantHelloIdentity): String = JSONObject()
        .put("id", id)
        .put("type", COMMAND_HELLO)
        .put("protocol", JSONObject().put("min", PROTOCOL_MIN).put("max", PROTOCOL_MAX))
        .put("did", identity.did ?: JSONObject.NULL)
        .put(
            "app",
            JSONObject().put("version", identity.appVersion).put("version_code", identity.appVersionCode),
        )
        .put("contract_digest", CONTRACT_DIGEST)
        .put("capabilities", JSONArray(CAPABILITIES))
        .put("channels", JSONArray())
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
    fun helloOutcome(frame: JSONObject, helloId: Long): PanelAssistantHelloOutcome? {
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
        if (!CAPABILITIES.containsAll(capabilities)) {
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
        if (kind != "session_closed") return PanelAssistantSessionEvent.Ignored(kind)
        val reason = (event.opt("reason") as? String)?.takeIf(CODE::matches).orEmpty()
        return PanelAssistantSessionEvent.Closed(reason)
    }
}

internal class PanelAssistantProtocolException(message: String) : RuntimeException(message)
