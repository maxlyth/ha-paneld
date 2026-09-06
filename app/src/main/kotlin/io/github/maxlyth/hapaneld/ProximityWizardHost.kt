package io.github.maxlyth.hapaneld

import org.json.JSONObject

internal fun proximityWizardMayRebind(stage: String): Boolean =
    stage in setOf("saved", "cancelled", "timed_out", "failed", "unavailable")

internal fun proximityWizardMustCancelOnStop(stage: String, changingConfigurations: Boolean): Boolean =
    !changingConfigurations && !proximityWizardMayRebind(stage)

/** Process-local bridge: only the service owns calibration; Activities only present a live session. */
object ProximityWizardHost {
    private data class Binding(val owner: Any, val status: () -> String, val action: (String) -> Boolean)
    private var binding: Binding? = null

    @Synchronized
    fun attach(owner: Any, status: () -> String, action: (String) -> Boolean) {
        binding = Binding(owner, status, action)
    }

    @Synchronized
    fun detach(owner: Any) {
        if (binding?.owner === owner) binding = null
    }

    @Synchronized
    fun status(): String? = binding?.let { runCatching(it.status).getOrNull() }

    /** Never let a restored or dismissed Activity operate on a later session. */
    @Synchronized
    fun action(sessionId: String, action: String): Boolean {
        if (sessionId.isBlank() || action !in ACTIONS) return false
        val current = binding ?: return false
        return runCatching {
            if (JSONObject(current.status()).optString("sessionId") != sessionId) false
            else current.action(action)
        }.getOrDefault(false)
    }

    private val ACTIONS = setOf("visible", "begin", "retry", "save", "cancel")
}
