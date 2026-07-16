package io.github.maxlyth.hapaneld.dashboard

import android.content.Context
import android.util.Log
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.DashboardEntityDefaultResolverMigration
import io.github.maxlyth.hapaneld.DashboardAuth
import io.github.maxlyth.hapaneld.control.BuiltinDashboard
import io.github.maxlyth.hapaneld.metrics.FeatureCostOperation
import io.github.maxlyth.hapaneld.metrics.FeatureCostOutcome
import io.github.maxlyth.hapaneld.metrics.FeatureCostRegistry
import io.github.maxlyth.hapaneld.metrics.FeatureCosts
import io.github.maxlyth.hapaneld.util.BoundedStreams
import io.github.maxlyth.hapaneld.util.ByteLimitExceeded
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.InterruptedIOException
import java.io.Reader
import java.util.concurrent.atomic.AtomicLong

/** Owns catalog synchronization, automatic set promotion and the HTTP/UI query surface. */
class EntityLearningManager(
    context: Context,
    private val config: Config,
    private val scope: CoroutineScope,
    private val resolveInstanceUuid: suspend (candidateUrls: Collection<String>) -> String? = { null },
    private val onFilterChanged: () -> Unit,
) {
    private val store = EntityCatalogStore(context.applicationContext)
    private val syncMutex = Mutex()
    private val effectGeneration = AtomicLong(0)
    private val telemetryWriteBarrier = EntityTelemetryWriteBarrier(effectGeneration::get)
    @Volatile private var syncJob: Job? = null
    @Volatile private var promotionJob: Job? = null
    @Volatile private var bootstrapBlockingIssues = 0
    @Volatile private var resetBootstrapPending = false
    @Volatile private var dynamicExpressionsJson = "[]"
    private var promotionWindowStart = 0L
    private var promotionsInWindow = 0
    private val telemetryPending = EntityTelemetryAccumulator(MAX_TELEMETRY_IDS)
    private val telemetryStoreGate = EntityTelemetryStoreGate()
    private val telemetryWorkerLock = Any()
    private var telemetryWorkerActive = false
    private var telemetryClosed = false
    @Volatile private var telemetryJob: Job? = null

    fun start() {
        prepareCurrentTarget()
        val resolverMigration = applyDefaultResolverMigration()
        refreshTargetDiagnostics()
        schedulePeriodicSync()
        val snapshot = store.snapshot(instance(), dashboardPath())
        if (shouldSyncEntityLearningOnStartup(
                config.dashboardEntityLearningEnabled,
                snapshot.lastSyncAt,
                resolverMigration,
                resetBootstrapPending,
            )) {
            syncNow(if (resolverMigration == DashboardEntityDefaultResolverMigration.REBOOTSTRAP) {
                "default-resolver-upgrade"
            } else {
                "startup"
            })
        }
    }

    private fun applyDefaultResolverMigration(): DashboardEntityDefaultResolverMigration {
        val result = config.migrateDashboardEntityDefaultResolver()
        if (result == DashboardEntityDefaultResolverMigration.REBOOTSTRAP ||
            config.dashboardEntityDefaultResolverRebootstrapPending) {
            // Preserve the old ids only as inactive evidence. The next scan must use bootstrap rules,
            // even when the old catalog has a non-zero lastSyncAt or the preserved list is non-empty.
            resetBootstrapPending = true
            onFilterChanged()
        } else if (result == DashboardEntityDefaultResolverMigration.PERSIST_FAILED) {
            Log.e(TAG, "default-dashboard resolver migration did not commit; filter remains fail-closed")
        }
        return result
    }

    private fun refreshTargetDiagnostics() {
        bootstrapBlockingIssues = runCatching {
            store.snapshot(instance(), dashboardPath()).blockingIssueCount
        }.getOrDefault(0)
        dynamicExpressionsJson = runCatching {
            encodeDynamicExpressions(EntityLearningProtocol.scanDashboard(
                store.dashboardConfigJson(instance(), dashboardPath()),
            ).dynamicExpressions)
        }.getOrDefault("[]")
    }

    private fun schedulePeriodicSync() {
        scope.launch {
            while (true) {
                delay(HOURLY_CHECK_MS)
                if (!config.dashboardEntityLearningEnabled || BuiltinDashboard.screenAwakeNow) continue
                val s = store.snapshot(instance(), dashboardPath())
                if (System.currentTimeMillis() - s.lastSyncAt >= DAILY_SYNC_MS) syncNow("daily")
            }
        }
    }

    fun close() {
        synchronized(this) {
            effectGeneration.incrementAndGet()
            promotionJob?.cancel()
            promotionJob = null
            syncJob?.cancel()
            syncJob = null
        }
        synchronized(telemetryWorkerLock) {
            telemetryClosed = true
            telemetryWorkerActive = false
            telemetryJob?.cancel()
            telemetryJob = null
            val dropped = telemetryPending.discard()
            if (dropped > 0) {
                FeatureCosts.registry.recordDropped(FeatureCostOperation.ENTITY_TELEMETRY_FLUSH, dropped.toLong())
            }
            FeatureCosts.registry.setBacklog(FeatureCostOperation.ENTITY_TELEMETRY_FLUSH, 0)
        }
        // Cancellation cannot interrupt a synchronous SQLite transaction. The generation check keeps
        // a not-yet-started flush out, while this gate waits for an already-started bounded flush.
        telemetryStoreGate.withStore { store.close() }
    }

    /** Rebind owner-scoped state before a renderer is relaunched for a new HA URL/dashboard. */
    fun onTargetConfigurationChanged() {
        synchronized(this) {
            invalidateEffects(cancelSync = true)
            runCatching { prepareCurrentTarget() }
                .onFailure { Log.w(TAG, "entity-learning target preparation failed: ${it.message}") }
            val resolverMigration = applyDefaultResolverMigration()
            refreshTargetDiagnostics()
            if (config.dashboardEntityLearningEnabled &&
                resolverMigration != DashboardEntityDefaultResolverMigration.PERSIST_FAILED) {
                syncNow(if (resolverMigration == DashboardEntityDefaultResolverMigration.REBOOTSTRAP) {
                    "default-resolver-target-change"
                } else {
                    "target-change"
                })
            }
        }
    }

    fun blockingIssueCount(): Int = bootstrapBlockingIssues

    /** Safe native-screen summary. Never return the stored exception text: it can contain target
     * details, while the renderer hold only needs to distinguish credential repair from retry. */
    fun bootstrapProblem(): EntityBootstrapProblem? = runCatching {
        store.snapshot(instance(), dashboardPath()).let { classifyEntityBootstrapProblem(it.state, it.error) }
    }.getOrNull()

    fun setEnabled(enabled: Boolean): Boolean {
        val wasEnabled = config.dashboardEntityLearningEnabled
        // A new opt-in clears a stale activation latch. Synchronization may bootstrap an empty
        // installation narrowly, but it never silently replaces a stored manual list. Disable mode
        // and interception together so a failed commit cannot expose a contradictory half-state.
        val committed = config.commitDashboardEntityLearningMode(
            enabled = enabled,
            clearApplied = !enabled || !wasEnabled,
        )
        if (!committed) return false
        invalidateEffects()
        if (enabled) {
            val resolverMigration = applyDefaultResolverMigration()
            if (resolverMigration != DashboardEntityDefaultResolverMigration.PERSIST_FAILED) {
                syncNow(if (resolverMigration == DashboardEntityDefaultResolverMigration.REBOOTSTRAP) {
                    "default-resolver-enable"
                } else {
                    "enable"
                })
            }
            // Always notify the renderer. A populated set rebuilds with its observer; an empty fresh
            // bootstrap tears down any pre-existing unfiltered WebView and waits on the native bootstrap
            // screen until synchronization commits the first narrow set.
            onFilterChanged()
        } else {
            resetBootstrapPending = false
            store.markStatus(instance(), dashboardPath(), "disabled")
            onFilterChanged()
        }
        return true
    }

    /** Explicitly promote the policy-selected evidence set, primarily for existing manual filters. */
    @Synchronized fun activate(confirm: Boolean): String {
        require(config.dashboardEntityLearningEnabled) { "automatic learning is not enabled" }
        require(canActivateLearnedEntitySet(config.dashboardEntityDefaultResolverRebootstrapPending)) {
            "a fresh default-dashboard scan must complete before activation"
        }
        require(store.snapshot(instance(), dashboardPath()).blockingIssueCount == 0) {
            "automatic activation is blocked by dashboard configuration issues"
        }
        if (!confirm) return JSONObject().put("ok", false).put("confirmation_required", true).toString()
        val active = desiredIds(System.currentTimeMillis())
        val preview = subscriptionPreview(active)
        check(config.commitDashboardEntitySubscription(true, active, applied = true)) {
            "failed to commit learned entity set"
        }
        invalidateEffects()
        if (preview.streamChange) onFilterChanged()
        resetBootstrapPending = false
        store.markStatus(instance(), dashboardPath(), "active")
        return JSONObject().put("ok", true).put("entity_count", active.size)
            .put("previous_count", preview.currentCount)
            .put("added_count", preview.additions).put("removed_count", preview.removals)
            .put("stream_changed", preview.streamChange)
            .put("automatic_updates_enabled", true)
            .put("filter_hash", EntityFilterProtocol.hash(active)).toString()
    }

    fun syncNow(reason: String = "manual"): Boolean = synchronized(this) {
        if (syncJob?.isActive == true) return@synchronized false
        val requestGeneration = effectGeneration.get()
        syncJob = scope.launch {
            syncMutex.withLock {
                val fallbackInstance = instance(); val fallbackPath = dashboardPath()
                runCatching { withEntityLearningDeadline(SYNC_TIMEOUT_MS) { synchronize() } }
                    .onFailure {
                        if (it is CancellationException && it !is TimeoutCancellationException) return@onFailure
                        Log.w(TAG, "entity catalog sync failed ($reason): ${it.message}")
                        // A superseded request must not stamp its failure onto the newly-selected target.
                        if (shouldPersistEntityLearningFailure(
                                requestGeneration,
                                effectGeneration.get(),
                                fallbackInstance,
                                fallbackPath,
                                instance(),
                                dashboardPath(),
                            )) {
                            store.markStatus(fallbackInstance, fallbackPath, "degraded", it.message ?: it.javaClass.simpleName)
                        }
                    }
            }
        }
        true
    }

    private suspend fun synchronize() = withContext(Dispatchers.IO) {
        val syncCost = FeatureCosts.registry.span(FeatureCostOperation.ENTITY_SYNC)
        var syncWork = 0L
        try {
        val target = captureAuthenticatedTarget()
        val states = fetchStates(target.baseUrl, target.authToken)
        syncWork += states.size
        require(states.isNotEmpty()) { "Home Assistant returned no visible states" }

        val identityCandidates = haInstanceCandidateUrls(
            target.baseUrl,
            runCatching { fetchHaConfig(target.baseUrl, target.authToken) }.getOrNull(),
        )
        val resolvedUuid = resolveInstanceUuid(identityCandidates)
        val adoptedTarget = if (resolvedUuid.isNullOrBlank()) target else adoptStableTarget(target, resolvedUuid)
        val snapshot = captureSyncSnapshot(adoptedTarget)
        require(snapshot.matchesCurrent(effectGeneration.get(), currentEffectState())) { "entity-learning target or policy changed" }

        val dashboardCost = FeatureCosts.registry.span(FeatureCostOperation.ENTITY_DASHBOARD_FETCH_PARSE)
        val ws = try {
            fetchDashboardAndRegistry(snapshot.baseUrl, snapshot.authToken, snapshot.dashboardPath).also {
                dashboardCost.work(
                    units = it.metadata.size.toLong(),
                    // String length is the allocation-free work-size proxy used by the other browser
                    // parsers. Encoding a dashboard admitted up to MAX_WS_FRAME solely for telemetry can
                    // otherwise create a second tens-of-megabytes buffer on memory-constrained panels.
                    bytes = it.configJson.length.toLong(),
                )
            }
        } catch (failure: Throwable) {
            dashboardCost.outcome(failure.featureCostOutcome())
            throw failure
        } finally {
            dashboardCost.close()
        }
        val scanCost = FeatureCosts.registry.span(FeatureCostOperation.ENTITY_STATIC_SCAN)
        val scan = try {
            EntityLearningProtocol.scanDashboard(ws.configJson).also {
                scanCost.work(
                    units = (it.entityIds.size + it.targets.size + it.unresolved.size).toLong(),
                    bytes = ws.configJson.length.toLong(),
                )
            }
        } catch (failure: Throwable) {
            scanCost.outcome(failure.featureCostOutcome())
            throw failure
        } finally {
            scanCost.close()
        }
        val expanded = expandTargets(snapshot.baseUrl, snapshot.authToken, scan.targets)
        val catalogIds = states.asSequence().map { it.entityId }.toHashSet()
        val friendlyNames = states.asSequence().filter { it.friendlyName.isNotBlank() }
            .associate { it.entityId.lowercase() to it.friendlyName }
        val lint = DashboardConfigurationLint.analyze(ws.configJson, catalogIds, ws.metadata, friendlyNames)
        val ignoredFingerprints = store.ignoredIssueFingerprints(snapshot.instanceKey, snapshot.dashboardPath)
        val ignoredSelectorBudget = lint.issues.any {
            it.type == DashboardConfigurationLint.IssueType.SELECTOR_BUDGET && it.fingerprint in ignoredFingerprints
        }
        val lintIds = if (ignoredSelectorBudget) emptySet() else lint.safeEntityIds
        val derived = (scan.entityIds + expanded + lintIds).filterTo(sortedSetOf()) { it in catalogIds }
        syncWork += derived.size
        val effectiveIssuesJson = EntityCatalogIssuePersistence.applyIgnores(
            JSONArray(lint.issues.map(DashboardConfigurationLint.Issue::toJson)), ignoredFingerprints,
        )
        val effectiveIssues = JSONArray(effectiveIssuesJson).let { array ->
            (0 until array.length()).mapNotNull(array::optJSONObject)
        }
        val effectiveBlocking = effectiveIssues.any { it.optBoolean("blocking", false) }
        val now = System.currentTimeMillis()
        val bootstrap = snapshot.forceBootstrap || shouldBootstrapEntityLearning(
            learningEnabled = snapshot.learningEnabled,
            applied = snapshot.applied,
            configuredIds = snapshot.filterIds,
        )
        val decision = automaticSyncDecision(
            learningEnabled = snapshot.learningEnabled,
            applied = snapshot.applied,
            configuredIds = snapshot.filterIds,
            blockingIssues = effectiveBlocking,
            forceBootstrap = snapshot.forceBootstrap,
        )
        synchronized(this@EntityLearningManager) {
            check(snapshot.matchesCurrent(effectGeneration.get(), currentEffectState())) {
                "entity-learning target or policy changed before commit"
            }
            store.commitSync(
                snapshot.instanceKey, snapshot.dashboardPath, states, ws.metadata, ws.configJson, derived, scan.unresolved,
                when (decision) {
                    AutomaticSyncDecision.BLOCKED -> "blocked"
                    AutomaticSyncDecision.APPLY -> "active"
                    AutomaticSyncDecision.BOOTSTRAP -> "learning"
                    AutomaticSyncDecision.OBSERVE -> "observing"
                },
                now,
                issues = effectiveIssues,
            )
            bootstrapBlockingIssues = effectiveIssues.count { it.optBoolean("blocking", false) }
            dynamicExpressionsJson = encodeDynamicExpressions(scan.dynamicExpressions)
        }
        applyStoredOverrides(snapshot.instanceKey, snapshot.dashboardPath, snapshot.overrides)
        // A blocking dashboard rule invalidates the proposed set as a whole. Keep an existing safe
        // filter byte-for-byte, or keep a fresh renderer on its native diagnostic screen. Never apply
        // the bounded fragments of a partially unsafe dashboard and never fall back to an unfiltered
        // WebSocket while automatic learning is enabled.
        if (effectiveBlocking) return@withContext
        val active = desiredIds(
            now, snapshot.instanceKey, snapshot.dashboardPath,
            includeStatic = snapshot.autoStatic,
            includeRuntime = snapshot.autoRuntime,
        )
        synchronized(this@EntityLearningManager) {
            check(snapshot.matchesCurrent(effectGeneration.get(), currentEffectState())) {
                "entity-learning target or policy changed before apply"
            }
            if (snapshot.applied || bootstrap) {
                val changed = active != snapshot.filterIds || !snapshot.filterEnabled
                check(config.commitDashboardEntitySubscription(true, active, applied = true)) {
                    "failed to commit automatic entity set"
                }
                if (snapshot.forceBootstrap) {
                    check(config.clearDashboardEntityDefaultResolverRebootstrapPending()) {
                        "failed to clear default-dashboard replacement latch"
                    }
                }
                if (changed) onFilterChanged()
            }
            if (snapshot.forceBootstrap) resetBootstrapPending = false
        }
        } catch (failure: Throwable) {
            syncCost.outcome(failure.featureCostOutcome())
            throw failure
        } finally {
            syncCost.work(units = syncWork).close()
        }
    }

    fun recordAccessBatch(text: String) {
        if (!config.dashboardEntityLearningEnabled) return
        val span = FeatureCosts.registry.span(FeatureCostOperation.ENTITY_ACCESS_PARSE)
        val parsed = runCatching { EntityLearningProtocol.parseAccessBatch(text) }.getOrElse {
            span.outcome(FeatureCostOutcome.FAILURE).work(bytes = text.length.toLong()).close()
            return
        }
        span.work(units = (parsed.first.size + parsed.second.size).toLong(), bytes = text.length.toLong()).close()
        admitTelemetry(accessed = parsed.first, missing = parsed.second)
    }

    fun recordMetricBatch(text: String) {
        if (!config.dashboardEntityLearningEnabled) return
        val span = FeatureCosts.registry.span(FeatureCostOperation.ENTITY_METRIC_PARSE)
        val envelope = runCatching { EntityLearningProtocol.parseMetricEnvelope(text) }.getOrElse {
            span.outcome(FeatureCostOutcome.FAILURE).work(bytes = text.length.toLong()).close()
            return
        }
        span.work(units = envelope.metrics.size.toLong(), bytes = text.length.toLong()).close()
        envelope.observer?.let { observer ->
            recordBrowserObserverCosts(FeatureCosts.registry, observer)
        }
        if (envelope.metrics.isNotEmpty()) admitTelemetry(metrics = envelope.metrics)
    }

    /** Merge renderer batches into one fixed-size generation-bound accumulator and own one IO worker. */
    private fun admitTelemetry(
        accessed: Map<String, Long> = emptyMap(),
        missing: Set<String> = emptySet(),
        metrics: Map<String, Pair<Long, Long>> = emptyMap(),
    ) {
        val target = EntityTelemetryTarget(effectGeneration.get(), instance(), dashboardPath())
        synchronized(telemetryWorkerLock) {
            if (telemetryClosed) {
                FeatureCosts.registry.recordDropped(
                    FeatureCostOperation.ENTITY_TELEMETRY_FLUSH,
                    (accessed.keys + missing + metrics.keys).size.toLong(),
                )
                return
            }
            val admission = telemetryPending.admit(target, accessed, missing, metrics)
            if (admission.coalesced > 0) {
                FeatureCosts.registry.recordCoalesced(FeatureCostOperation.ENTITY_TELEMETRY_FLUSH, admission.coalesced)
            }
            if (admission.dropped > 0) {
                FeatureCosts.registry.recordDropped(FeatureCostOperation.ENTITY_TELEMETRY_FLUSH, admission.dropped)
            }
            FeatureCosts.registry.setBacklog(FeatureCostOperation.ENTITY_TELEMETRY_FLUSH, admission.backlog)
            if (!telemetryWorkerActive) {
                telemetryWorkerActive = true
                telemetryJob = scope.launch(Dispatchers.IO) { drainTelemetry() }
            }
        }
    }

    private suspend fun drainTelemetry() {
        try {
            while (true) {
                val batch = telemetryPending.drain() ?: synchronized(telemetryWorkerLock) {
                    // Serialize the empty observation with admission. If a producer arrived after drain(),
                    // it sees the worker active; re-check before relinquishing ownership.
                    telemetryPending.drain()
                } ?: return
                FeatureCosts.registry.setBacklog(FeatureCostOperation.ENTITY_TELEMETRY_FLUSH, telemetryPending.size())
                val flush = FeatureCosts.registry.span(FeatureCostOperation.ENTITY_TELEMETRY_FLUSH)
                    .work(units = batch.uniqueIds.toLong())
                try {
                    flushTelemetry(batch)
                } catch (error: CancellationException) {
                    flush.outcome(FeatureCostOutcome.CANCELLED)
                    throw error
                } catch (error: Exception) {
                    flush.outcome(FeatureCostOutcome.FAILURE)
                    Log.w(TAG, "entity telemetry flush failed: ${error.message}")
                } finally {
                    flush.close()
                }
            }
        } finally {
            synchronized(telemetryWorkerLock) {
                telemetryWorkerActive = false
                telemetryJob = null
                val hasPending = !telemetryClosed && telemetryPending.size() > 0
                if (hasPending) {
                    telemetryWorkerActive = true
                    telemetryJob = scope.launch(Dispatchers.IO) { drainTelemetry() }
                }
            }
        }
    }

    private fun flushTelemetry(batch: EntityTelemetryBatch) = telemetryStoreGate.withStore {
        if (batch.target.generation != effectGeneration.get()) {
            FeatureCosts.registry.recordDropped(FeatureCostOperation.ENTITY_TELEMETRY_FLUSH, batch.uniqueIds.toLong())
            return@withStore
        }
        val accessIds = batch.accessed.keys + batch.missing
        val lookup = FeatureCosts.registry.span(FeatureCostOperation.ENTITY_MEMBERSHIP_LOOKUP)
            .work(units = accessIds.size.toLong())
        val known = try {
            store.existingEntityIds(batch.target.instance, accessIds)
        } catch (error: Exception) {
            lookup.outcome(FeatureCostOutcome.FAILURE)
            throw error
        } finally {
            lookup.close()
        }
        val knownAccessed = batch.accessed.filterKeys(known::contains)
        val knownMissing = batch.missing.filterTo(mutableSetOf(), known::contains)
        val admitted = telemetryWriteBarrier.writeIfCurrent(batch.target.generation) {
            if (knownAccessed.isNotEmpty() || knownMissing.isNotEmpty()) {
                val access = FeatureCosts.registry.span(FeatureCostOperation.ENTITY_ACCESS_WRITE)
                    .work(units = (knownAccessed.size + knownMissing.size).toLong())
                try {
                    val counts = LinkedHashMap(knownAccessed)
                    knownMissing.forEach { id -> counts.putIfAbsent(id, 1L) }
                    store.recordAccess(
                        batch.target.instance,
                        batch.target.path,
                        counts,
                        batch.now,
                    )
                } catch (error: Exception) {
                    access.outcome(FeatureCostOutcome.FAILURE)
                    throw error
                } finally {
                    access.close()
                }
                if (knownMissing.isNotEmpty() && config.dashboardEntityLearningApplied && config.dashboardEntityAutoRuntime) {
                    runCatching { capturePromotionSnapshot(batch.target.instance, batch.target.path) }
                        .getOrNull()?.let(::queuePromotion)
                }
            }
            if (batch.metrics.isNotEmpty()) {
                val metric = FeatureCosts.registry.span(FeatureCostOperation.ENTITY_METRIC_WRITE)
                    .work(
                        units = batch.metrics.values.sumOf { it.first.coerceAtLeast(0L) },
                        bytes = batch.metrics.values.sumOf { it.second.coerceAtLeast(0L) },
                    )
                try {
                    store.recordMetrics(batch.target.instance, batch.target.path, batch.metrics, batch.now)
                } catch (error: Exception) {
                    metric.outcome(FeatureCostOutcome.FAILURE)
                    throw error
                } finally {
                    metric.close()
                }
            }
        }
        if (!admitted) {
            FeatureCosts.registry.recordDropped(FeatureCostOperation.ENTITY_TELEMETRY_FLUSH, batch.uniqueIds.toLong())
        }
    }

    private fun queuePromotion(snapshot: EntityLearningPromotionSnapshot) = synchronized(this) {
        if (!snapshot.isEligible(effectGeneration.get(), currentEffectState(), bootstrapBlockingIssues)) return
        if (promotionJob?.isActive == true) return
        promotionJob = scope.launch {
            delay(PROMOTION_DEBOUNCE_MS)
            if (!snapshot.isEligible(effectGeneration.get(), currentEffectState(), bootstrapBlockingIssues)) return@launch
            val now = System.currentTimeMillis()
            if (now - promotionWindowStart > PROMOTION_WINDOW_MS) {
                promotionWindowStart = now; promotionsInWindow = 0
            }
            if (promotionsInWindow >= MAX_PROMOTIONS) {
                store.markStatus(
                    snapshot.instanceKey, snapshot.dashboardPath,
                    "degraded", "runtime dependencies queued; synchronize manually",
                )
                return@launch
            }
            val active = withContext(Dispatchers.IO) {
                desiredIds(
                    now, snapshot.instanceKey, snapshot.dashboardPath,
                    includeStatic = snapshot.autoStatic,
                    includeRuntime = snapshot.autoRuntime,
                )
            }
            synchronized(this@EntityLearningManager) {
                if (!snapshot.isEligible(effectGeneration.get(), currentEffectState(), bootstrapBlockingIssues)) return@synchronized
                if (active != snapshot.filterIds && active.isNotEmpty()) {
                    check(config.commitDashboardEntitySubscription(true, active, applied = true)) {
                        "failed to commit runtime entity promotion"
                    }
                    promotionsInWindow++
                    onFilterChanged()
                }
            }
        }
    }

    fun setOverride(entityId: String, override: String, force: Boolean): String {
        val id = EntityFilterProtocol.normalize(listOf(entityId)).single()
        if (override == "forced_exclude" && !force) {
            return JSONObject().put("ok", false).put("confirmation_required", true).put("entity_id", id).toString()
        }
        applyOverrides(listOf(id), override, force)
        return JSONObject().put("ok", true).put("entity_id", id).put("override", override).toString()
    }

    fun setOverrides(entityIds: List<String>, allCandidates: Boolean, override: String, force: Boolean): String {
        require(allCandidates || entityIds.isNotEmpty()) { "entity_ids or all_candidates required" }
        val ids = if (allCandidates) {
            val subscribed = config.dashboardEntityFilterIds.toSet()
            store.suggestedIds(instance(), dashboardPath()).filterNot(subscribed::contains)
        } else EntityFilterProtocol.normalize(entityIds)
        require(ids.isNotEmpty()) { "selected entity set is empty" }
        if (override == "forced_exclude" && !force) {
            return JSONObject().put("ok", false).put("confirmation_required", true).put("entity_count", ids.size).toString()
        }
        applyOverrides(ids, override, force)
        return JSONObject().put("ok", true).put("entity_count", ids.size).put("override", override).toString()
    }

    private fun applyOverrides(ids: List<String>, override: String, force: Boolean) = synchronized(this) {
        require(override in setOf("auto", "pinned", "forced_exclude")) { "invalid override" }
        require(override != "forced_exclude" || force) { "force confirmation required" }
        val previousOverrides = config.dashboardEntityOverrides
        val encoded = previousOverrides.toMutableMap().apply {
            for (id in ids) if (override == "auto") remove(id) else put(id, override)
        }
        invalidateEffects()
        val targetInstance = instance()
        val targetPath = dashboardPath()
        val applied = config.dashboardEntityLearningApplied
        val previousFilter = config.dashboardEntityFilterIds
        var active = previousFilter
        runEntityOverrideTransaction(
            commitOverridePreferences = { config.setDashboardEntityOverrides(encoded) },
            applyStoreOverride = { store.setOverrides(targetInstance, targetPath, ids, override) },
            commitActiveFilter = {
                active = desiredIds(System.currentTimeMillis(), targetInstance, targetPath)
                !applied || config.commitDashboardEntitySubscription(true, active, applied = true)
            },
            restoreStoreOverride = {
                ids.forEach { id -> store.setOverride(targetInstance, targetPath, id, previousOverrides[id] ?: "auto") }
            },
            restoreOverridePreferences = { config.setDashboardEntityOverrides(previousOverrides) },
        )
        if (applied && active != previousFilter) onFilterChanged()
    }

    /** Tester recovery/reset: discard derived dashboard evidence, never credentials or the HA catalog. */
    fun resetEvidence(confirm: Boolean, clearFilter: Boolean = false): String {
        if (!confirm) return JSONObject().put("ok", false).put("confirmation_required", true).toString()
        check(syncJob?.isActive != true) { "synchronization is running" }
        check(config.commitDashboardEntityEvidenceReset(clearFilter)) { "failed to reset entity-learning preferences" }
        telemetryWriteBarrier.invalidateAndWrite(
            invalidate = { invalidateEffects() },
        ) {
            if (config.dashboardEntityLearningEnabled) {
                // Preserve a known-good live set until the replacement scan succeeds. A blocking or failed
                // default reset must never turn into an unfiltered renderer or a partial set. The explicit
                // clean-slate path clears that set deliberately and holds the native bootstrap screen.
                resetBootstrapPending = true
            }
            store.resetEvidence(instance(), dashboardPath())
        }
        if (clearFilter) onFilterChanged()
        val started = if (config.dashboardEntityLearningEnabled) syncNow("reset") else false
        return JSONObject().put("ok", true).put("sync_started", started)
            .put("filter_cleared", clearFilter).toString()
    }

    private fun applyStoredOverrides(instance: String, path: String, overrides: Map<String, String>) {
        overrides.forEach { (id, override) -> store.setOverride(instance, path, id, override) }
    }

    /** Change which evidence sources may promote entities. Collection and review continue regardless. */
    @Synchronized fun setPromotionPolicy(staticRefs: Boolean, runtimeRefs: Boolean): String {
        val applied = config.dashboardEntityLearningApplied
        val active = desiredIds(
            System.currentTimeMillis(),
            includeStatic = staticRefs,
            includeRuntime = runtimeRefs,
        )
        val changed = applied && (active != config.dashboardEntityFilterIds || !config.dashboardEntityFilterEnabled)
        check(config.commitDashboardEntityPromotionPolicy(
            staticRefs = staticRefs,
            runtimeRefs = runtimeRefs,
            activeEntityIds = active.takeIf { changed },
            applied = applied,
        )) { "failed to commit promotion policy" }
        invalidateEffects()
        if (changed) onFilterChanged()
        return JSONObject().put("ok", true).put("auto_static", staticRefs)
            .put("auto_runtime", runtimeRefs).put("entity_count", active.size).toString()
    }

    fun statusJson(): String {
        val span = FeatureCosts.registry.span(FeatureCostOperation.ENTITY_STATUS_READ)
        try {
            val currentInstance = instance()
            val currentPath = dashboardPath()
            val s = store.snapshot(currentInstance, currentPath)
            bootstrapBlockingIssues = s.blockingIssueCount
            val suggestions = store.suggestedIds(currentInstance, currentPath)
            val filterIds = config.dashboardEntityFilterIds
            val filterSet = filterIds.toSet()
            val candidates = suggestions.count { it !in filterSet }
            val learningEnabled = config.dashboardEntityLearningEnabled
            val filtered = config.dashboardEntityFilterEnabled
            val applied = config.dashboardEntityLearningApplied
            val autoStatic = config.dashboardEntityAutoStatic
            val autoRuntime = config.dashboardEntityAutoRuntime
            val held = shouldHoldRendererForEntityBootstrap(learningEnabled, filtered)
            val desired = desiredIds(
                System.currentTimeMillis(), currentInstance, currentPath,
                includeStatic = autoStatic,
                includeRuntime = autoRuntime,
            )
            val preview = previewEntitySubscription(filtered, filterIds, s.catalogCount, desired)
            span.work(units = (s.catalogCount + suggestions.size).toLong())
            return JSONObject()
                .put("requested_enabled", learningEnabled)
                .put("mode", if (learningEnabled) "automatic" else "manual")
                .put("state", when {
                    !learningEnabled -> "disabled"
                    s.blockingIssueCount > 0 -> "blocked"
                    else -> s.state
                })
                .put("applied", applied)
                .put("auto_static", autoStatic)
                .put("auto_runtime", autoRuntime)
                .put("last_sync_at", s.lastSyncAt).put("catalog_count", s.catalogCount)
                .put("active_count", filterIds.size)
                .put("candidate_count", candidates).put("suggested_count", candidates)
                .put("desired_count", desired.size)
                .put("pending_additions", if (s.blockingIssueCount > 0) 0 else preview.additions)
                .put("pending_removals", if (s.blockingIssueCount > 0) 0 else preview.removals)
                .put("stream_change_required", s.blockingIssueCount == 0 && preview.streamChange)
                .put("activation_required", !applied)
                .put("apply_required", s.blockingIssueCount == 0 && (preview.streamChange || !applied))
                .put("dashboard_issue_count", s.issueCount)
                .put("blocking_issue_count", s.blockingIssueCount)
                .put("ignored_issue_count", s.ignoredIssueCount)
                .put("automatic_activation_blocked", s.blockingIssueCount > 0)
                .put("stream_mode", when { held -> "held"; filtered -> "filtered"; else -> "unfiltered" })
                .put("stream_entity_count", when { held -> 0; filtered -> filterIds.size; else -> s.catalogCount })
                .put("stream_filter_hash", if (filtered) EntityFilterProtocol.hash(filterIds) else "")
                .put("unresolved_count", s.unresolvedCount)
                .put("error", s.error).put("db_bytes", s.dbBytes).put("sync_running", syncJob?.isActive == true)
                .toString()
        } catch (error: Exception) {
            span.outcome(FeatureCostOutcome.FAILURE)
            throw error
        } finally {
            span.close()
        }
    }

    fun issuesJson(): String {
        val s = store.snapshot(instance(), dashboardPath())
        return JSONObject()
            .put("items", JSONArray(store.issuesJson(instance(), dashboardPath())))
            .put("dashboard_issue_count", s.issueCount)
            .put("blocking_issue_count", s.blockingIssueCount)
            .put("ignored_issue_count", s.ignoredIssueCount)
            .put("dynamic_expressions", JSONArray(dynamicExpressionsJson))
            .toString()
    }

    @Synchronized fun setIssueIgnored(fingerprint: String, ignored: Boolean): String {
        val targetInstance = instance()
        val targetPath = dashboardPath()
        invalidateEffects()
        require(store.setIssueIgnored(targetInstance, targetPath, fingerprint, ignored, System.currentTimeMillis())) {
            "dashboard issue is no longer present"
        }
        bootstrapBlockingIssues = store.snapshot(targetInstance, targetPath).blockingIssueCount
        val syncStarted = syncNow(if (ignored) "ignore-issue" else "restore-issue")
        return JSONObject().put("ok", true).put("fingerprint", fingerprint)
            .put("ignored", ignored).put("sync_started", syncStarted).toString()
    }

    @Synchronized fun ignoreAllBlockingIssues(): Boolean {
        val targetInstance = instance()
        val targetPath = dashboardPath()
        val issues = JSONArray(store.issuesJson(targetInstance, targetPath))
        val fingerprints = (0 until issues.length()).mapNotNull { index ->
            issues.optJSONObject(index)?.takeIf { it.optBoolean("blocking", false) }
                ?.optString("fingerprint")?.takeIf(String::isNotBlank)
        }
        if (fingerprints.isEmpty()) return false
        invalidateEffects()
        val now = System.currentTimeMillis()
        check(fingerprints.all { store.setIssueIgnored(targetInstance, targetPath, it, true, now) }) {
            "dashboard issues changed while applying overrides"
        }
        bootstrapBlockingIssues = store.snapshot(targetInstance, targetPath).blockingIssueCount
        return syncNow("ignore-blocking-issues")
    }

    private fun encodeDynamicExpressions(expressions: List<EntityLearningProtocol.DynamicExpression>): String =
        JSONArray(expressions.take(MAX_DYNAMIC_EXPRESSIONS).map(EntityLearningProtocol.DynamicExpression::toJson)).toString()

    fun entitiesJson(
        query: String,
        filter: String,
        limit: Int,
        offset: Int,
        sort: String = "entity_id",
        direction: String = "asc",
    ): String {
        val subscribed = filter == "subscribed"
        val review = filter == "review"
        val filtered = config.dashboardEntityFilterEnabled
        val held = shouldHoldRendererForEntityBootstrap(
            config.dashboardEntityLearningEnabled,
            config.dashboardEntityFilterEnabled,
        )
        val effectiveFilter = when {
            filter == "candidate" && query.isBlank() -> "candidate"
            filter == "candidate" -> "unpinned"
            subscribed -> "all"
            else -> filter
        }
        val currentIds = if (filtered) config.dashboardEntityFilterIds.toSet() else emptySet()
        val json = JSONObject(store.entitiesJson(
            instance(), dashboardPath(), query, effectiveFilter, limit, offset,
            sortKey = sort,
            sortDirection = direction,
            includeIds = when {
                (subscribed || review) && held -> emptySet()
                (subscribed || review) && filtered -> config.dashboardEntityFilterIds.toSet()
                else -> null
            },
            excludeIds = if (filter == "candidate") currentIds else emptySet(),
        ))
        if (subscribed) {
            val catalogCount = store.snapshot(instance(), dashboardPath()).catalogCount
            json.put("stream_mode", when { held -> "held"; filtered -> "filtered"; else -> "unfiltered" })
                .put("configured_count", if (filtered) config.dashboardEntityFilterIds.size else 0)
                .put("catalog_present_count", when { held -> 0; filtered -> json.optInt("total"); else -> catalogCount })
        }
        return json.toString()
    }

    fun writeExportJson(writer: java.io.Writer) {
        store.writeExportJson(instance(), dashboardPath(), writer)
    }

    private fun desiredIds(
        now: Long,
        instance: String = instance(),
        path: String = dashboardPath(),
        includeStatic: Boolean = config.dashboardEntityAutoStatic,
        includeRuntime: Boolean = config.dashboardEntityAutoRuntime,
    ): List<String> = store.activeIds(
        instance, path, now,
        includeStatic = includeStatic,
        includeRuntime = includeRuntime,
    )

    private fun subscriptionPreview(
        desired: List<String>,
        catalogCount: Int = store.snapshot(instance(), dashboardPath()).catalogCount,
    ): EntitySubscriptionPreview {
        val filtered = config.dashboardEntityFilterEnabled
        return previewEntitySubscription(
            filtered = filtered,
            currentIds = config.dashboardEntityFilterIds,
            catalogCount = catalogCount,
            desiredIds = desired,
        )
    }

    private fun instance(): String {
        val base = config.haUrl.takeIf(String::isNotBlank) ?: return UNCONFIGURED_INSTANCE
        return config.dashboardEntityInstanceKey.ifBlank { EntityLearningProtocol.hash(normalizedOrigin(base)) }
    }
    private fun dashboardPath(): String = config.homeDashboard.ifBlank { "/" }

    private fun prepareCurrentTarget(): String? {
        val base = config.haUrl.trim().trimEnd('/')
        if (base.isBlank()) return null
        val origin = normalizedOrigin(base)
        val path = dashboardPath()
        val legacyKey = EntityLearningProtocol.hash(origin)
        return config.prepareDashboardEntityInstance(origin, path, legacyKey)
            ?: error("failed to prepare entity-learning target")
    }

    private fun invalidateEffects(cancelSync: Boolean = true) {
        synchronized(this) {
            effectGeneration.incrementAndGet()
            promotionJob?.cancel()
            promotionJob = null
            if (cancelSync) {
                syncJob = supersedeEntityLearningSync(syncJob)
            }
        }
    }

    private fun credentialFingerprint(): String = EntityLearningProtocol.hash(
        listOf(
            config.haUrl,
            config.haToken,
            config.haRefreshToken,
            config.haTokenExpiry.toString(),
            config.haClientId,
        ).joinToString("\u0000"),
    )

    private fun currentEffectState(): EntityLearningEffectState = EntityLearningEffectState(
        origin = runCatching { normalizedOrigin(config.haUrl) }.getOrDefault(""),
        instanceKey = instance(),
        targetKey = config.dashboardEntityTargetKey,
        dashboardPath = dashboardPath(),
        credentialFingerprint = credentialFingerprint(),
        learningEnabled = config.dashboardEntityLearningEnabled,
        applied = config.dashboardEntityLearningApplied,
        filterEnabled = config.dashboardEntityFilterEnabled,
        filterIds = config.dashboardEntityFilterIds,
        autoStatic = config.dashboardEntityAutoStatic,
        autoRuntime = config.dashboardEntityAutoRuntime,
        overrides = config.dashboardEntityOverrides,
        forceBootstrap = resetBootstrapPending,
    )

    private suspend fun captureAuthenticatedTarget(): AuthenticatedTarget {
        val generation = effectGeneration.get()
        val base = config.haUrl.trim().trimEnd('/')
        require(base.isNotBlank()) { "Home Assistant URL is not configured" }
        val origin = normalizedOrigin(base)
        val path = dashboardPath()
        val legacyKey = EntityLearningProtocol.hash(origin)
        val selected = config.prepareDashboardEntityInstance(origin, path, legacyKey)
            ?: error("failed to prepare entity-learning target")
        val targetKey = config.dashboardEntityTargetKey
        val stillCurrent = {
            generation == effectGeneration.get() &&
                runCatching { normalizedOrigin(config.haUrl) }.getOrNull() == origin &&
                dashboardPath() == path && config.dashboardEntityInstanceKey == selected &&
                config.dashboardEntityTargetKey == targetKey
        }
        val auth = DashboardAuth.forConfig(config, stillCurrent = stillCurrent)
        val token = auth.session?.accessToken
            ?: error(if (auth.rejected) "Home Assistant credential rejected" else "Home Assistant token unavailable")
        check(stillCurrent()) { "entity-learning target changed during authentication" }
        return AuthenticatedTarget(
            generation, base, origin, path, legacyKey, selected, targetKey, token, credentialFingerprint(),
        )
    }

    private fun adoptStableTarget(target: AuthenticatedTarget, uuid: String): AuthenticatedTarget {
        check(target.generation == effectGeneration.get()) { "entity-learning target changed during discovery" }
        check(normalizedOrigin(config.haUrl) == target.origin && dashboardPath() == target.dashboardPath) {
            "entity-learning target changed during discovery"
        }
        check(credentialFingerprint() == target.credentialFingerprint) { "Home Assistant credential changed during discovery" }
        val stableKey = EntityLearningProtocol.hash("ha-instance:${uuid.lowercase()}")
        check(config.adoptDashboardEntityInstance(
            target.origin, target.dashboardPath, uuid, target.legacyKey, stableKey,
        )) { "Home Assistant identity changed during discovery" }
        return target.copy(
            instanceKey = config.dashboardEntityInstanceKey,
            targetKey = config.dashboardEntityTargetKey,
        )
    }

    private fun captureSyncSnapshot(target: AuthenticatedTarget): EntityLearningSyncSnapshot {
        check(target.generation == effectGeneration.get()) { "entity-learning target changed before scan" }
        val state = currentEffectState()
        check(state.origin == target.origin && state.instanceKey == target.instanceKey &&
            state.targetKey == target.targetKey && state.dashboardPath == target.dashboardPath &&
            state.credentialFingerprint == target.credentialFingerprint
        ) { "entity-learning target or credential changed before scan" }
        return EntityLearningSyncSnapshot(target.generation, target.baseUrl, target.authToken, state)
    }

    private fun capturePromotionSnapshot(instance: String, path: String): EntityLearningPromotionSnapshot {
        val state = currentEffectState()
        return EntityLearningPromotionSnapshot(effectGeneration.get(), state).also {
            check(state.instanceKey == instance && state.dashboardPath == path) { "entity-learning target changed" }
        }
    }

    private data class AuthenticatedTarget(
        val generation: Long,
        val baseUrl: String,
        val origin: String,
        val dashboardPath: String,
        val legacyKey: String,
        val instanceKey: String,
        val targetKey: String,
        val authToken: String,
        val credentialFingerprint: String,
    )

    private data class WsSnapshot(val configJson: String, val metadata: Map<String, String>)

    private suspend fun fetchStates(base: String, token: String): List<EntityCatalogStore.StateRow> =
        runInterruptible(Dispatchers.IO) {
            val cost = FeatureCosts.registry.span(FeatureCostOperation.ENTITY_STATES_FETCH_PARSE)
            val c = (URL(base.trimEnd('/') + "/api/states").openConnection() as HttpURLConnection).apply {
                connectTimeout = HTTP_TIMEOUT_MS; readTimeout = HTTP_TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer $token"); setRequestProperty("Accept", "application/json")
            }
            try {
                if (c.responseCode !in 200..299) error("states request failed: HTTP ${c.responseCode}")
                // /api/states includes every attribute payload. Stream over it without hydrating the
                // attributes, but reject a response that exceeds any resource or wall-clock bound.
                val limits = HaStatesReadLimits()
                if (c.contentLengthLong > limits.maxBytes) throw ByteLimitExceeded(limits.maxBytes)
                val counted = CountingInputStream(c.inputStream)
                readHaStates(counted, limits).also {
                    cost.work(units = it.size.toLong(), bytes = counted.bytesRead)
                }
            } catch (failure: Throwable) {
                cost.outcome(failure.featureCostOutcome())
                throw failure
            } finally {
                cost.close()
                c.disconnect()
            }
        }

    private fun fetchHaConfig(base: String, token: String): String {
        val c = (URL(base.trimEnd('/') + "/api/config").openConnection() as HttpURLConnection).apply {
            connectTimeout = HA_CONFIG_TIMEOUT_MS
            readTimeout = HA_CONFIG_TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (c.responseCode !in 200..299) error("config request failed: HTTP ${c.responseCode}")
            return c.inputStream.use {
                String(BoundedStreams.readBytes(it, MAX_HA_CONFIG_BYTES), Charsets.UTF_8)
            }
        } finally {
            c.disconnect()
        }
    }

    private suspend fun fetchDashboardAndRegistry(base: String, token: String, homeDashboard: String): WsSnapshot {
        var configJson: String? = null
        val metadata = mutableMapOf<String, String>()
        withHaSocket(base, token) { request ->
            val registry = request(JSONObject().put("type", "config/entity_registry/list_for_display"))
            val root = registry.optJSONObject("result")
            val entries = root?.optJSONArray("entities") ?: registry.optJSONArray("result") ?: JSONArray()
            for (i in 0 until entries.length()) entries.optJSONObject(i)?.let { e ->
                e.optString("ei").takeIf { it.isNotBlank() }?.let { metadata[it] = e.toString() }
            }
            // Floor and label selectors inherit through entity -> device -> area in Home Assistant.
            // list_for_display is intentionally compact and does not carry that complete ancestry, so
            // enrich its rows before linting; otherwise a floor selector could incorrectly look empty
            // and be accepted as a safe zero-entity subscription.
            val areas = runCatching { request(JSONObject().put("type", "config/area_registry/list")) }.getOrNull()
                ?.optJSONArray("result") ?: JSONArray()
            val devices = runCatching { request(JSONObject().put("type", "config/device_registry/list")) }.getOrNull()
                ?.optJSONArray("result") ?: JSONArray()
            val areaRows = mutableMapOf<String, JSONObject>()
            for (i in 0 until areas.length()) areas.optJSONObject(i)?.let { row ->
                row.optString("area_id").ifBlank { row.optString("id") }.takeIf(String::isNotBlank)?.let { areaRows[it] = row }
            }
            val deviceRows = mutableMapOf<String, JSONObject>()
            for (i in 0 until devices.length()) devices.optJSONObject(i)?.let { row ->
                row.optString("id").ifBlank { row.optString("device_id") }.takeIf(String::isNotBlank)?.let { deviceRows[it] = row }
            }
            fun labels(row: JSONObject?, vararg keys: String): Set<String> = keys.flatMapTo(sortedSetOf()) { key ->
                when (val value = row?.opt(key)) {
                    is JSONArray -> (0 until value.length()).mapNotNull { value.optString(it).takeIf(String::isNotBlank) }
                    is String -> listOf(value).filter(String::isNotBlank)
                    else -> emptyList()
                }
            }
            metadata.replaceAll { _, raw ->
                val entity = JSONObject(raw)
                val device = deviceRows[entity.optString("di").ifBlank { entity.optString("device_id") }]
                val areaId = entity.optString("ai").ifBlank { entity.optString("area_id") }
                    .ifBlank { device?.optString("area_id").orEmpty() }
                val area = areaRows[areaId]
                if (areaId.isNotBlank()) entity.put("ai", areaId)
                area?.optString("floor_id")?.takeIf(String::isNotBlank)?.let { entity.put("fi", it) }
                val inheritedLabels = labels(entity, "lb", "labels", "label_ids") +
                    labels(device, "labels", "label_ids") + labels(area, "labels", "label_ids")
                if (inheritedLabels.isNotEmpty()) entity.put("lb", JSONArray(inheritedLabels.sorted()))
                entity.toString()
            }
            val defaultPanel = if (EntityLearningProtocol.usesFrontendDefaultPanel(homeDashboard)) {
                val userDefault = runCatching {
                    request(JSONObject().put("type", "frontend/get_user_data").put("key", "core"))
                        .optJSONObject("result")?.optJSONObject("value")?.optString("default_panel")
                }.getOrNull()
                val systemDefault = if (userDefault.isNullOrBlank()) runCatching {
                    request(JSONObject().put("type", "frontend/get_system_data").put("key", "core"))
                        .optJSONObject("result")?.optJSONObject("value")?.optString("default_panel")
                }.getOrNull() else null
                EntityLearningProtocol.frontendDefaultPanel(userDefault, systemDefault)
            } else null
            val urlPath = EntityLearningProtocol.dashboardUrlPath(homeDashboard, defaultPanel)
            val command = JSONObject().put("type", "lovelace/config")
            if (urlPath.isNotBlank()) command.put("url_path", urlPath)
            val response = request(command)
            val result = response.opt("result")
            configJson = when (result) {
                is JSONObject -> result.toString()
                else -> null
            }
        }
        return WsSnapshot(configJson ?: error("dashboard configuration unavailable"), metadata)
    }

    private suspend fun expandTargets(base: String, token: String, targets: List<String>): Set<String> {
        if (targets.isEmpty()) return emptySet()
        val out = linkedSetOf<String>()
        withHaSocket(base, token) { request ->
            for (target in targets.take(MAX_TARGETS)) {
                // Dashboard configuration is extensible and third-party cards sometimes put selector
                // syntax in target-shaped fields. A single target rejected by HA must not discard the
                // complete catalogue/static scan; unresolved dependencies can still be learned at runtime.
                val response = runCatching {
                    request(JSONObject().put("type", "extract_from_target").put("target", JSONObject(target)).put("expand_group", true))
                }.getOrNull() ?: continue
                val entities = response.optJSONObject("result")?.optJSONArray("referenced_entities") ?: continue
                for (i in 0 until entities.length()) entities.optString(i).takeIf { it.isNotBlank() }?.let(out::add)
            }
        }
        return out
    }

    private suspend fun withHaSocket(
        base: String,
        token: String,
        block: suspend (suspend (JSONObject) -> JSONObject) -> Unit,
    ) {
        val ws = EntityFilterProtocol.upstreamWebSocketUrl(base)
        val client = HttpClient(CIO) { install(WebSockets) { maxFrameSize = MAX_WS_FRAME } }
        var session: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession? = null
        try {
            val activeSession = withEntityLearningDeadline(WS_CONNECT_TIMEOUT_MS) { client.webSocketSession(ws) }
            session = activeSession
            withEntityLearningDeadline(WS_SOCKET_TIMEOUT_MS) {
                with(activeSession) {
                    withEntityLearningDeadline(WS_AUTH_TIMEOUT_MS) {
                        (incoming.receive() as? Frame.Text)?.readText()
                        send(Frame.Text(JSONObject().put("type", "auth").put("access_token", token).toString()))
                        val auth = (incoming.receive() as? Frame.Text)?.readText().orEmpty()
                        require(auth.contains("\"type\":\"auth_ok\"")) { "Home Assistant WebSocket authentication failed" }
                    }
                    var id = 0
                    suspend fun request(command: JSONObject): JSONObject = withEntityLearningDeadline(WS_REQUEST_TIMEOUT_MS) {
                        val current = ++id; command.put("id", current); send(Frame.Text(command.toString()))
                        repeat(MAX_RESPONSE_FRAMES) {
                            val frame = incoming.receive() as? Frame.Text ?: return@repeat
                            val obj = JSONObject(frame.readText())
                            if (obj.optInt("id") == current && obj.optString("type") == "result") {
                                require(obj.optBoolean("success")) { obj.optJSONObject("error")?.optString("message") ?: "HA command failed" }
                                return@withEntityLearningDeadline obj
                            }
                        }
                        error("Home Assistant command exceeded response frame limit")
                    }
                    block(::request)
                }
            }
        } finally {
            runCatching { session?.close() }
            client.close()
        }
    }

    companion object {
        private const val TAG = "EntityLearning"
        private const val HTTP_TIMEOUT_MS = 30_000
        private const val HA_CONFIG_TIMEOUT_MS = 10_000
        private const val MAX_HA_CONFIG_BYTES = 256L * 1024
        private const val SYNC_TIMEOUT_MS = 120_000L
        private const val WS_CONNECT_TIMEOUT_MS = 15_000L
        private const val WS_AUTH_TIMEOUT_MS = 15_000L
        private const val WS_REQUEST_TIMEOUT_MS = 20_000L
        private const val WS_SOCKET_TIMEOUT_MS = 90_000L
        private const val MAX_RESPONSE_FRAMES = 32
        private const val MAX_TARGETS = 500
        private const val MAX_WS_FRAME = 32L * 1024 * 1024
        private const val HOURLY_CHECK_MS = 60L * 60_000
        private const val DAILY_SYNC_MS = 24L * HOURLY_CHECK_MS
        private const val PROMOTION_DEBOUNCE_MS = 2_000L
        private const val PROMOTION_WINDOW_MS = 10L * 60_000
        private const val MAX_PROMOTIONS = 2
        private const val MAX_DYNAMIC_EXPRESSIONS = 128
        private const val MAX_TELEMETRY_IDS = EntityFilterProtocol.MAX_ENTITY_IDS
        private const val UNCONFIGURED_INSTANCE = "unconfigured"

        private fun normalizedOrigin(raw: String): String = EntityFilterProtocol.origin(raw)
    }
}

internal data class HaStatesReadLimits(
    val maxBytes: Long = 64L * 1024 * 1024,
    val maxRows: Int = 100_000,
    val maxEntityIdChars: Int = 255,
    val maxStateChars: Int = 255,
    val maxFriendlyNameChars: Int = 1_024,
    val deadlineMs: Long = 45_000,
) {
    init {
        require(
            maxBytes > 0 && maxRows > 0 && maxEntityIdChars > 0 && maxStateChars > 0 &&
                maxFriendlyNameChars > 0 && deadlineMs > 0,
        )
    }
}

/** Counts bytes consumed by the streaming parser without retaining a second copy. */
internal class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    var bytesRead: Long = 0L
        private set

    override fun read(): Int = super.read().also { if (it >= 0) bytesRead++ }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        super.read(buffer, offset, length).also { if (it > 0) bytesRead += it }
}

private fun Throwable.featureCostOutcome(): FeatureCostOutcome =
    if (this is CancellationException) FeatureCostOutcome.CANCELLED else FeatureCostOutcome.FAILURE

/** Parse only the state projection needed by the learner while retaining hard transport-independent
 * bounds. The monotonic clock seam keeps elapsed-time enforcement deterministic in unit tests. */
internal fun readHaStates(
    input: InputStream,
    limits: HaStatesReadLimits = HaStatesReadLimits(),
    nanoTime: () -> Long = System::nanoTime,
): List<EntityCatalogStore.StateRow> {
    val bounded = DeadlineBoundedInputStream(input, limits.maxBytes, limits.deadlineMs, nanoTime)
    return StreamingJsonReader(InputStreamReader(bounded, Charsets.UTF_8)).use { reader ->
        buildList {
            var rows = 0
            reader.beginArray()
            while (reader.hasNext()) {
                check(rows < limits.maxRows) { "states response exceeds ${limits.maxRows} rows" }
                rows++
                var entityId = ""
                var state = ""
                var friendlyName = ""
                reader.beginObject()
                while (reader.hasNext()) when (reader.nextName()) {
                    "entity_id" -> entityId = reader.readBoundedScalar(limits.maxEntityIdChars, "entity_id")
                    "state" -> state = reader.readBoundedScalar(limits.maxStateChars, "state")
                    "attributes" -> friendlyName = reader.readFriendlyName(limits.maxFriendlyNameChars)
                    else -> reader.skipValue()
                }
                reader.endObject()
                validateHaStateRow(entityId, state, rows, limits, friendlyName)?.let(::add)
            }
            reader.endArray()
            reader.requireEndDocument()
        }
    }
}

internal fun validateHaStateRow(
    entityId: String,
    state: String,
    rowNumber: Int,
    limits: HaStatesReadLimits,
    friendlyName: String = "",
): EntityCatalogStore.StateRow? {
    check(rowNumber <= limits.maxRows) { "states response exceeds ${limits.maxRows} rows" }
    check(entityId.length <= limits.maxEntityIdChars) {
        "states entity_id exceeds ${limits.maxEntityIdChars} characters"
    }
    check(state.length <= limits.maxStateChars) { "states state exceeds ${limits.maxStateChars} characters" }
    check(friendlyName.length <= limits.maxFriendlyNameChars) {
        "states friendly_name exceeds ${limits.maxFriendlyNameChars} characters"
    }
    return entityId.takeIf(String::isNotBlank)?.let { EntityCatalogStore.StateRow(it, state, friendlyName) }
}

/** Selectively project the one state attribute used by static dashboard selectors. All other
 * attributes remain streaming skips and are neither hydrated nor persisted. */
private fun StreamingJsonReader.readFriendlyName(maxChars: Int): String {
    if (peek() != StreamJsonToken.BEGIN_OBJECT) {
        skipValue()
        return ""
    }
    var friendlyName = ""
    beginObject()
    while (hasNext()) when (nextName()) {
        "friendly_name" -> friendlyName = readBoundedScalar(maxChars, "friendly_name")
        else -> skipValue()
    }
    endObject()
    return friendlyName
}

private fun StreamingJsonReader.readBoundedScalar(maxChars: Int, field: String): String {
    val value = when (peek()) {
        StreamJsonToken.NULL -> { nextNull(); "" }
        StreamJsonToken.STRING, StreamJsonToken.NUMBER -> nextString(maxChars, field)
        StreamJsonToken.BOOLEAN -> nextBoolean().toString()
        else -> { skipValue(); "" }
    }
    check(value.length <= maxChars) { "states $field exceeds $maxChars characters" }
    return value
}

internal enum class StreamJsonToken { BEGIN_OBJECT, BEGIN_ARRAY, STRING, NUMBER, BOOLEAN, NULL, END_DOCUMENT }

/** Small dependency-free streaming JSON reader for the HA state projection. Android's JsonReader is
 * unavailable as real code in local JVM tests, while a tree parser would reintroduce the payload-copy
 * regression this path exists to avoid. */
internal class StreamingJsonReader(private val input: Reader) : AutoCloseable {
    private data class Scope(val closing: Char, var first: Boolean = true)
    private val scopes = ArrayDeque<Scope>()
    private var lookahead = UNSET

    fun beginArray() { expect('['); pushScope(']') }
    fun endArray() { expect(']'); popScope(']') }
    fun beginObject() { expect('{'); pushScope('}') }
    fun endObject() { expect('}'); popScope('}') }

    fun hasNext(): Boolean {
        val scope = scopes.lastOrNull() ?: error("JSON scope is empty")
        skipWhitespace()
        if (peekChar() == scope.closing.code) return false
        if (scope.first) {
            scope.first = false
        } else {
            expect(',')
            skipWhitespace()
            check(peekChar() != scope.closing.code) { "trailing comma in JSON" }
        }
        return true
    }

    fun nextName(): String {
        val name = readString(MAX_NAME_CHARS, "field name")
        skipWhitespace(); expect(':')
        return name
    }

    fun peek(): StreamJsonToken {
        skipWhitespace()
        return when (val char = peekChar()) {
            '{'.code -> StreamJsonToken.BEGIN_OBJECT
            '['.code -> StreamJsonToken.BEGIN_ARRAY
            '"'.code -> StreamJsonToken.STRING
            't'.code, 'f'.code -> StreamJsonToken.BOOLEAN
            'n'.code -> StreamJsonToken.NULL
            -1 -> StreamJsonToken.END_DOCUMENT
            else -> if (char == '-'.code || char in '0'.code..'9'.code) StreamJsonToken.NUMBER
                else error("invalid JSON value")
        }
    }

    fun nextNull() { expectLiteral("null") }
    fun nextBoolean(): Boolean = when (peekChar()) {
        't'.code -> { expectLiteral("true"); true }
        'f'.code -> { expectLiteral("false"); false }
        else -> error("expected JSON boolean")
    }

    fun nextString(maxChars: Int, field: String): String = when (peek()) {
        StreamJsonToken.STRING -> readString(maxChars, field)
        StreamJsonToken.NUMBER -> readNumber(maxChars, field)
        else -> error("expected JSON scalar")
    }

    fun skipValue(depth: Int = 0) {
        check(depth < MAX_DEPTH) { "JSON nesting exceeds $MAX_DEPTH" }
        when (peek()) {
            StreamJsonToken.BEGIN_OBJECT -> {
                beginObject()
                while (hasNext()) { nextName(); skipValue(depth + 1) }
                endObject()
            }
            StreamJsonToken.BEGIN_ARRAY -> {
                beginArray()
                while (hasNext()) skipValue(depth + 1)
                endArray()
            }
            StreamJsonToken.STRING -> { readString(null, "skipped value") }
            StreamJsonToken.NUMBER -> { readNumber(MAX_SKIPPED_NUMBER_CHARS, "skipped number") }
            StreamJsonToken.BOOLEAN -> { nextBoolean() }
            StreamJsonToken.NULL -> nextNull()
            StreamJsonToken.END_DOCUMENT -> error("unexpected end of JSON")
        }
    }

    fun requireEndDocument() {
        check(scopes.isEmpty()) { "JSON scope is still open" }
        skipWhitespace()
        check(peekChar() == -1) { "trailing data after JSON document" }
    }

    override fun close() = input.close()

    private fun readString(maxChars: Int?, field: String): String {
        skipWhitespace(); expect('"')
        val value = if (maxChars == null) null else StringBuilder(minOf(maxChars, 64))
        var count = 0
        while (true) {
            val char = takeChar()
            check(char >= 0) { "unterminated JSON string" }
            if (char == '"'.code) break
            val decoded = if (char == '\\'.code) readEscape() else {
                check(char >= 0x20) { "control character in JSON string" }
                char.toChar()
            }
            count++
            if (maxChars != null) check(count <= maxChars) { "states $field exceeds $maxChars characters" }
            value?.append(decoded)
        }
        return value?.toString().orEmpty()
    }

    private fun readEscape(): Char = when (val escaped = takeChar().toChar()) {
        '"', '\\', '/' -> escaped
        'b' -> '\b'; 'f' -> '\u000c'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
        'u' -> {
            var value = 0
            repeat(4) {
                val digit = takeChar()
                val hex = Character.digit(digit.toChar(), 16)
                check(hex >= 0) { "invalid JSON unicode escape" }
                value = value * 16 + hex
            }
            value.toChar()
        }
        else -> error("invalid JSON escape")
    }

    private fun readNumber(maxChars: Int, field: String): String {
        skipWhitespace()
        val value = StringBuilder()
        while (true) {
            val char = peekChar()
            if (char < 0 || char.toChar().isWhitespace() || char == ','.code || char == ']'.code || char == '}'.code) break
            value.append(takeChar().toChar())
            check(value.length <= maxChars) { "states $field exceeds $maxChars characters" }
        }
        return value.toString().also { check(JSON_NUMBER.matches(it)) { "invalid JSON number" } }
    }

    private fun expectLiteral(literal: String) = literal.forEach { expected ->
        check(takeChar() == expected.code) { "invalid JSON literal" }
    }

    private fun expect(expected: Char) {
        skipWhitespace()
        check(takeChar() == expected.code) { "expected '$expected' in JSON" }
    }

    private fun pushScope(closing: Char) {
        check(scopes.size < MAX_DEPTH) { "JSON nesting exceeds $MAX_DEPTH" }
        scopes.addLast(Scope(closing))
    }

    private fun popScope(closing: Char) {
        check(scopes.removeLastOrNull()?.closing == closing) { "mismatched JSON scope" }
    }

    private fun skipWhitespace() { while (peekChar() >= 0 && peekChar().toChar().isWhitespace()) takeChar() }
    private fun peekChar(): Int {
        if (lookahead == UNSET) lookahead = input.read()
        return lookahead
    }
    private fun takeChar(): Int = peekChar().also { lookahead = UNSET }

    private companion object {
        const val UNSET = -2
        const val MAX_DEPTH = 64
        const val MAX_NAME_CHARS = 1_024
        const val MAX_SKIPPED_NUMBER_CHARS = 1_024
        val JSON_NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
    }
}

/** Bounds bytes before the streaming reader can allocate field values. Cancellation interrupts the blocking read through
 * runInterruptible; this stream also notices interruption and elapsed time between transport reads. */
internal class DeadlineBoundedInputStream(
    input: InputStream,
    private val maxBytes: Long,
    deadlineMs: Long,
    private val nanoTime: () -> Long,
) : FilterInputStream(input) {
    private val startedAt = nanoTime()
    private val deadlineNanos = deadlineMs * 1_000_000L
    private var total = 0L

    override fun read(): Int {
        checkAbort()
        val value = super.read()
        checkAbort()
        if (value < 0) return value
        if (++total > maxBytes) throw ByteLimitExceeded(maxBytes)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        checkAbort()
        val wanted = minOf(length.toLong(), maxBytes - total + 1L).toInt()
        val count = super.read(buffer, offset, wanted)
        checkAbort()
        if (count < 0) return count
        total += count
        if (total > maxBytes) throw ByteLimitExceeded(maxBytes)
        return count
    }

    private fun checkAbort() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("states response read cancelled")
        if (nanoTime() - startedAt >= deadlineNanos) throw SocketTimeoutException("states response read deadline exceeded")
    }
}

internal data class EntityLearningEffectState(
    val origin: String,
    val instanceKey: String,
    val targetKey: String,
    val dashboardPath: String,
    val credentialFingerprint: String,
    val learningEnabled: Boolean,
    val applied: Boolean,
    val filterEnabled: Boolean,
    val filterIds: List<String>,
    val autoStatic: Boolean,
    val autoRuntime: Boolean,
    val overrides: Map<String, String>,
    val forceBootstrap: Boolean,
)

internal data class EntityLearningSyncSnapshot(
    val generation: Long,
    val baseUrl: String,
    val authToken: String,
    val state: EntityLearningEffectState,
) {
    val instanceKey get() = state.instanceKey
    val dashboardPath get() = state.dashboardPath
    val learningEnabled get() = state.learningEnabled
    val applied get() = state.applied
    val filterEnabled get() = state.filterEnabled
    val filterIds get() = state.filterIds
    val autoStatic get() = state.autoStatic
    val autoRuntime get() = state.autoRuntime
    val overrides get() = state.overrides
    val forceBootstrap get() = state.forceBootstrap

    fun matchesCurrent(currentGeneration: Long, currentState: EntityLearningEffectState): Boolean =
        generation == currentGeneration && state == currentState
}

internal data class EntityLearningPromotionSnapshot(
    val generation: Long,
    val state: EntityLearningEffectState,
) {
    val instanceKey get() = state.instanceKey
    val dashboardPath get() = state.dashboardPath
    val filterIds get() = state.filterIds
    val autoStatic get() = state.autoStatic
    val autoRuntime get() = state.autoRuntime

    fun isEligible(
        currentGeneration: Long,
        currentState: EntityLearningEffectState,
        blockingIssues: Int,
    ): Boolean = generation == currentGeneration && state == currentState &&
        state.learningEnabled && state.applied && state.autoRuntime && blockingIssues == 0
}

internal suspend fun <T> withEntityLearningDeadline(
    timeoutMs: Long,
    block: suspend CoroutineScope.() -> T,
): T = kotlinx.coroutines.withTimeout(timeoutMs, block)

/** Effect-changing operations own a replacement generation; the prior scan must release the mutex
 * before a replacement can run and must no longer occupy the admission slot. */
internal fun supersedeEntityLearningSync(current: Job?): Job? {
    current?.cancel()
    return null
}

/** Persist failures only for the generation and target that admitted the scan. */
internal fun shouldPersistEntityLearningFailure(
    requestGeneration: Long,
    currentGeneration: Long,
    requestInstance: String,
    requestPath: String,
    currentInstance: String,
    currentPath: String,
): Boolean = requestGeneration == currentGeneration &&
    requestInstance == currentInstance && requestPath == currentPath

/** Serializes the final generation check with evidence reset. A batch that entered first is cleared
 * by reset; a batch that reaches the barrier after reset observes the replacement generation. */
internal class EntityTelemetryWriteBarrier(
    private val currentGeneration: () -> Long,
) {
    private val lock = Any()

    fun writeIfCurrent(admittedGeneration: Long, write: () -> Unit): Boolean = synchronized(lock) {
        if (admittedGeneration != currentGeneration()) return@synchronized false
        write()
        true
    }

    fun invalidateAndWrite(invalidate: () -> Unit, write: () -> Unit) = synchronized(lock) {
        invalidate()
        write()
    }
}

internal fun runEntityOverrideTransaction(
    commitOverridePreferences: () -> Boolean,
    applyStoreOverride: () -> Unit,
    commitActiveFilter: () -> Boolean,
    restoreStoreOverride: () -> Unit,
    restoreOverridePreferences: () -> Boolean,
) {
    check(commitOverridePreferences()) { "override preference commit failed" }
    try {
        applyStoreOverride()
        check(commitActiveFilter()) { "active entity filter commit failed" }
    } catch (failure: Throwable) {
        runCatching { restoreStoreOverride() }.exceptionOrNull()?.let(failure::addSuppressed)
        runCatching { check(restoreOverridePreferences()) { "override preference rollback failed" } }
            .exceptionOrNull()?.let(failure::addSuppressed)
        throw failure
    }
}

/** Candidate aliases are accepted only from the authenticated, bounded `/api/config` response. */
internal fun haInstanceCandidateUrls(configuredBase: String, configJson: String?): List<String> = buildList {
    add(configuredBase)
    val config = configJson?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return@buildList
    for (key in listOf("internal_url", "external_url", "base_url")) {
        val candidate = config.optString(key).trim()
        if (candidate.length in 1..MAX_HA_CANDIDATE_URL_CHARS &&
            runCatching { EntityFilterProtocol.origin(candidate) }.isSuccess
        ) add(candidate)
    }
}.distinct()

private const val MAX_HA_CANDIDATE_URL_CHARS = 2_048

internal fun canActivateLearnedEntitySet(defaultResolverRebootstrapPending: Boolean): Boolean =
    !defaultResolverRebootstrapPending

internal fun recordBrowserObserverCosts(
    registry: FeatureCostRegistry,
    observer: EntityLearningProtocol.BrowserObserverCosts,
) {
    registry.recordExternal(
        FeatureCostOperation.ENTITY_BROWSER_OBSERVER,
        wallElapsedNanos = (observer.parseMicros + observer.stringifyMicros) * 1_000L,
        events = observer.frames,
        inputChars = observer.frameChars,
        workUnits = observer.entities,
    )
    registry.recordDropped(FeatureCostOperation.ENTITY_BROWSER_OBSERVER, observer.dropped)
    registry.recordCoalesced(FeatureCostOperation.ENTITY_BROWSER_OBSERVER, observer.coalesced)
}

internal data class EntityTelemetryTarget(val generation: Long, val instance: String, val path: String)

internal data class EntityTelemetryBatch(
    val target: EntityTelemetryTarget,
    val accessed: Map<String, Long>,
    val missing: Set<String>,
    val metrics: Map<String, Pair<Long, Long>>,
    val now: Long = System.currentTimeMillis(),
) {
    val uniqueIds: Int get() = (accessed.keys + missing + metrics.keys).size
}

internal data class EntityTelemetryAdmission(val backlog: Int, val coalesced: Long, val dropped: Long)

/** Serializes synchronous SQLite telemetry with close; cancellation alone cannot stop a transaction. */
internal class EntityTelemetryStoreGate {
    private val lock = Any()
    fun <T> withStore(block: () -> T): T = synchronized(lock) { block() }
}

/** Fixed-size accumulator used by the renderer bridge. It merges repeated batches without queuing jobs. */
internal class EntityTelemetryAccumulator(private val maxIds: Int) {
    init { require(maxIds > 0) }

    private var target: EntityTelemetryTarget? = null
    private val ids = linkedSetOf<String>()
    private val accessed = linkedMapOf<String, Long>()
    private val missing = linkedSetOf<String>()
    private val metrics = linkedMapOf<String, Pair<Long, Long>>()

    @Synchronized
    fun admit(
        admittedTarget: EntityTelemetryTarget,
        newAccessed: Map<String, Long>,
        newMissing: Set<String>,
        newMetrics: Map<String, Pair<Long, Long>>,
    ): EntityTelemetryAdmission {
        var dropped = 0L
        if (target != null && target != admittedTarget) {
            dropped += ids.size
            clear()
        }
        target = admittedTarget
        val coalesced = if (ids.isNotEmpty() &&
            (newAccessed.isNotEmpty() || newMissing.isNotEmpty() || newMetrics.isNotEmpty())) 1L else 0L

        fun admitId(id: String): Boolean {
            if (id in ids) return true
            if (ids.size >= maxIds) {
                dropped++
                return false
            }
            ids += id
            return true
        }
        newAccessed.forEach { (id, count) ->
            if (admitId(id)) accessed[id] = saturatingAdd(accessed[id] ?: 0L, count.coerceAtLeast(0L))
        }
        newMissing.forEach { id -> if (admitId(id)) missing += id }
        newMetrics.forEach { (id, value) ->
            if (admitId(id)) {
                val previous = metrics[id] ?: (0L to 0L)
                metrics[id] = saturatingAdd(previous.first, value.first.coerceAtLeast(0L)) to
                    saturatingAdd(previous.second, value.second.coerceAtLeast(0L))
            }
        }
        return EntityTelemetryAdmission(ids.size, coalesced, dropped)
    }

    @Synchronized fun drain(): EntityTelemetryBatch? {
        val admittedTarget = target ?: return null
        if (ids.isEmpty()) {
            target = null
            return null
        }
        return EntityTelemetryBatch(
            admittedTarget,
            LinkedHashMap(accessed),
            LinkedHashSet(missing),
            LinkedHashMap(metrics),
        ).also { clear() }
    }

    @Synchronized fun size(): Int = ids.size

    @Synchronized fun discard(): Int = ids.size.also { clear() }

    private fun clear() {
        target = null
        ids.clear()
        accessed.clear()
        missing.clear()
        metrics.clear()
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
}

/** A resolver-policy upgrade must rescan even when the old, potentially wrong catalog has synced. */
internal fun shouldSyncEntityLearningOnStartup(
    learningEnabled: Boolean,
    lastSyncAt: Long,
    resolverMigration: DashboardEntityDefaultResolverMigration,
    forceBootstrap: Boolean = false,
): Boolean = learningEnabled && resolverMigration != DashboardEntityDefaultResolverMigration.PERSIST_FAILED &&
    (forceBootstrap || resolverMigration == DashboardEntityDefaultResolverMigration.REBOOTSTRAP || lastSyncAt == 0L)

/** An empty installation starts narrow; any stored manual list is preserved for explicit review. */
internal fun shouldBootstrapEntityLearning(
    learningEnabled: Boolean,
    applied: Boolean,
    configuredIds: Collection<String>,
): Boolean = learningEnabled && !applied && configuredIds.isEmpty()

internal enum class AutomaticSyncDecision { BLOCKED, OBSERVE, APPLY, BOOTSTRAP }

/** Pure safety policy: a blocking scan is atomic rejection, and disabled learning is scan-only. */
internal fun automaticSyncDecision(
    learningEnabled: Boolean,
    applied: Boolean,
    configuredIds: Collection<String>,
    blockingIssues: Boolean,
    forceBootstrap: Boolean = false,
): AutomaticSyncDecision = when {
    blockingIssues -> AutomaticSyncDecision.BLOCKED
    !learningEnabled -> AutomaticSyncDecision.OBSERVE
    applied -> AutomaticSyncDecision.APPLY
    forceBootstrap || configuredIds.isEmpty() -> AutomaticSyncDecision.BOOTSTRAP
    else -> AutomaticSyncDecision.OBSERVE
}

internal data class EntitySubscriptionPreview(
    val currentCount: Int,
    val additions: Int,
    val removals: Int,
    val streamChange: Boolean,
)

/** Pure current→policy preview used by both status and the confirmed apply result. */
internal fun previewEntitySubscription(
    filtered: Boolean,
    currentIds: Collection<String>,
    catalogCount: Int,
    desiredIds: Collection<String>,
): EntitySubscriptionPreview {
    val desired = desiredIds.toSet()
    if (!filtered) return EntitySubscriptionPreview(
        currentCount = catalogCount,
        additions = 0,
        removals = (catalogCount - desired.size).coerceAtLeast(0),
        // Enabling an empty allow-list is still a real transport change: the existing unfiltered
        // WebView must reload so subscribe_entities is rewritten to entity_ids=[].
        streamChange = true,
    )
    val current = currentIds.toSet()
    return EntitySubscriptionPreview(
        currentCount = current.size,
        additions = (desired - current).size,
        removals = (current - desired).size,
        streamChange = current != desired,
    )
}

/** Process-local rendezvous between the service-owned learner and DashboardActivity's JS bridge. */
object EntityLearningRuntime {
    @Volatile private var current: EntityLearningManager? = null
    fun attach(manager: EntityLearningManager) { current = manager }
    fun detach(manager: EntityLearningManager) { if (current === manager) current = null }
    fun recordAccessBatch(text: String) { current?.recordAccessBatch(text) }
    fun recordMetricBatch(text: String) { current?.recordMetricBatch(text) }
    fun blockingIssueCount(): Int = current?.blockingIssueCount() ?: 0
    fun bootstrapProblem(): EntityBootstrapProblem? = current?.bootstrapProblem()
    /** Explicit user retry only. syncNow rejects the request while an existing scan is active. */
    fun retryBootstrap(): Boolean = current?.syncNow("dashboard-retry") ?: false
    fun ignoreBlockingIssues(): Boolean = current?.ignoreAllBlockingIssues() ?: false
    fun disableAutomaticFilter(): Boolean = current?.setEnabled(false) ?: false
}

enum class EntityBootstrapProblem { AUTHENTICATION, SYNCHRONIZATION }

internal fun classifyEntityBootstrapProblem(state: String, error: String): EntityBootstrapProblem? {
    if (state != "degraded" || error.isBlank()) return null
    val normalized = error.lowercase()
    return if (
        "http 401" in normalized || "http 403" in normalized ||
        "credential rejected" in normalized || "token unavailable" in normalized
    ) EntityBootstrapProblem.AUTHENTICATION else EntityBootstrapProblem.SYNCHRONIZATION
}
