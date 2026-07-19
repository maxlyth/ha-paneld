package io.github.maxlyth.hapaneld

internal enum class ServiceTeardownDisposition { RELEASE, EXIT }

/** Only an ordinary, completely proved teardown may open a same-process successor. */
internal fun serviceTeardownDisposition(
    completed: Boolean,
    explicitProcessBoundary: Boolean,
): ServiceTeardownDisposition =
    if (completed && !explicitProcessBoundary) ServiceTeardownDisposition.RELEASE
    else ServiceTeardownDisposition.EXIT

/**
 * One terminal decision for an Android service generation.
 *
 * Completion is sticky-false, explicit process-boundary requests are sticky, and exactly one caller
 * owns external-state recovery. Keeping those decisions under this one lock avoids composing a second
 * atomic owner in [PaneldService].
 */
internal class ServiceTeardownBoundary {
    private var explicitProcessBoundary = false
    private var recoveryClaimed = false
    private var finalized = false
    private var completed = true

    @Synchronized
    fun requestExplicitBoundary(): Boolean {
        if (finalized || explicitProcessBoundary) return false
        explicitProcessBoundary = true
        return true
    }

    /** Record this caller's proof and atomically select the sole external-state recovery runner. */
    @Synchronized
    fun recordCompletionAndClaimRecovery(completed: Boolean): Boolean {
        if (finalized) return false
        this.completed = this.completed && completed
        if (recoveryClaimed) return false
        recoveryClaimed = true
        return true
    }

    @Synchronized
    fun claim(): ServiceTeardownDisposition? {
        if (finalized) return null
        check(recoveryClaimed)
        finalized = true
        return serviceTeardownDisposition(completed, explicitProcessBoundary)
    }
}

/** One external-state observation. Forced exit is permitted only by the service's durable safety rule. */
internal data class ServiceBoundaryProof(
    val externalStateSafe: Boolean,
    val forceFreshProcess: Boolean,
)

/**
 * Stateless composition of teardown proof, external-state recovery and the terminal release/exit action.
 * The boundary above owns the only mutable decision; this runner adds no thread, queue, timer or policy
 * state and is synchronously testable with hostile caller interleavings.
 */
internal fun runServiceBoundary(
    boundary: ServiceTeardownBoundary,
    completed: Boolean,
    prepare: () -> Unit,
    prove: (attempt: Int) -> ServiceBoundaryProof,
    pauseBeforeRetry: () -> Unit,
    finish: (ServiceTeardownDisposition) -> Unit,
) {
    if (!boundary.recordCompletionAndClaimRecovery(completed)) return
    prepare()
    var attempt = 0
    while (true) {
        attempt += 1
        val proof = prove(attempt)
        if (proof.externalStateSafe || proof.forceFreshProcess) {
            val claimed = boundary.claim() ?: return
            finish(if (proof.forceFreshProcess) ServiceTeardownDisposition.EXIT else claimed)
            return
        }
        pauseBeforeRetry()
    }
}
