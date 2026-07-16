package io.github.maxlyth.hapaneld.provisioning

import kotlinx.coroutines.sync.Mutex

/**
 * Service-owned read facade. It binds observations to one immutable startup profile and caches a
 * short-lived snapshot so the JSON and text endpoints do not independently hammer device readers.
 */
internal class ProvisioningCoordinator(
    private val core: ProvisioningCoreIdentity,
    private val profile: ProvisioningProfile,
    private val collector: ProvisioningObservationCollector,
    private val monotonicMs: () -> Long,
    private val cacheTtlMs: Long = DEFAULT_CACHE_TTL_MS,
) : ProvisioningReader {
    override val expectedProfileRef = profile.ref

    private val refreshMutex = Mutex()

    @Volatile
    private var cached: CachedSnapshot? = null

    init {
        require(cacheTtlMs >= 0) { "cacheTtlMs must be non-negative" }
    }

    override suspend fun plan(
        activation: ProvisioningActivationSnapshot,
        forceRefresh: Boolean,
    ): ProvisioningReadResult {
        if (!activation.isStableFor(profile.ref)) {
            return ProvisioningReadResult.Unavailable("profile_activation_unstable")
        }
        val observations = try {
            observations(forceRefresh)
        } catch (_: Throwable) {
            return ProvisioningReadResult.Unavailable("observation_snapshot_unavailable")
        }
        return ProvisioningReadResult.Ready(
            ProvisioningPlanner.plan(core, profile, activation, observations),
        )
    }

    private suspend fun observations(forceRefresh: Boolean): ProvisioningObservationSnapshot {
        val now = monotonicMs()
        cached?.takeIf { !forceRefresh && now - it.collectedAtMs <= cacheTtlMs }?.let { return it.snapshot }

        refreshMutex.lock()
        try {
            val lockedNow = monotonicMs()
            cached?.takeIf { !forceRefresh && lockedNow - it.collectedAtMs <= cacheTtlMs }?.let {
                return it.snapshot
            }
            return collector.collect().also {
                cached = CachedSnapshot(monotonicMs(), it)
            }
        } finally {
            refreshMutex.unlock()
        }
    }

    private data class CachedSnapshot(
        val collectedAtMs: Long,
        val snapshot: ProvisioningObservationSnapshot,
    )

    private companion object {
        const val DEFAULT_CACHE_TTL_MS = 5_000L
    }
}
