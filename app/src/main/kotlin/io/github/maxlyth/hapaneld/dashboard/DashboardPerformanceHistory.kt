package io.github.maxlyth.hapaneld.dashboard

import org.json.JSONArray
import org.json.JSONObject

internal fun interface DashboardPerformanceHistorySink {
    fun record(batch: EntityFilterProtocol.TrafficBatch)
}

internal data class DashboardPerformanceSample(
    val instance: String,
    val path: String,
    val minute: Long,
    val filterActive: Boolean,
    val entityCount: Int,
    val batch: EntityFilterProtocol.TrafficBatch,
)

internal data class DashboardPerformanceKey(
    val instance: String,
    val path: String,
    val minute: Long,
)

/** One in-memory minute rollup. Five-second browser batches merge here before SQLite is touched. */
internal data class DashboardPerformanceAggregate(
    val key: DashboardPerformanceKey,
    var filterActive: Boolean,
    var entityCount: Int,
    var sampleMs: Long = 0,
    var frames: Long = 0,
    var payloadBytes: Long = 0,
    var updates: Long = 0,
    var hydrationUpdates: Long = 0,
    var observerMicros: Long = 0,
    var droppedFrames: Long = 0,
    var stateTaskMicros: Long = 0,
    var stateTaskMaxMicros: Long = 0,
    var interactionCount: Long = 0,
    var interactionMaxMicros: Long = 0,
    var inputDelayMicros: Long = 0,
    var interactionProcessingMicros: Long = 0,
    var presentationMicros: Long = 0,
    var loafCount: Long = 0,
    var blockingMicros: Long = 0,
    var loafMaxMicros: Long = 0,
    var scriptMicros: Long = 0,
    var renderMicros: Long = 0,
    var longTaskCount: Long = 0,
) {
    fun merge(sample: DashboardPerformanceSample) {
        require(key == DashboardPerformanceKey(sample.instance, sample.path, sample.minute))
        val batch = sample.batch
        filterActive = sample.filterActive
        entityCount = sample.entityCount
        sampleMs += batch.sampleMs
        frames += batch.frames
        payloadBytes += batch.payloadBytes
        updates += batch.entityUpdates
        hydrationUpdates += batch.hydrationUpdates
        observerMicros += batch.observerMicros
        droppedFrames += batch.droppedFrames
        stateTaskMicros += batch.stateTaskMicros
        stateTaskMaxMicros = maxOf(stateTaskMaxMicros, batch.stateTaskMaxMicros)
        interactionCount += batch.interactionBins.sum()
        if (batch.interactionMaxMicros > interactionMaxMicros) {
            interactionMaxMicros = batch.interactionMaxMicros
            inputDelayMicros = batch.inputDelayMicros
            interactionProcessingMicros = batch.interactionProcessingMicros
            presentationMicros = batch.presentationMicros
        }
        loafCount += batch.loafCount
        blockingMicros += batch.blockingMicros
        loafMaxMicros = maxOf(loafMaxMicros, batch.loafMaxMicros)
        scriptMicros += batch.scriptMicros
        renderMicros += batch.renderMicros
        longTaskCount += batch.longTaskCount
    }

    companion object {
        fun from(sample: DashboardPerformanceSample): DashboardPerformanceAggregate =
            DashboardPerformanceAggregate(
                key = DashboardPerformanceKey(sample.instance, sample.path, sample.minute),
                filterActive = sample.filterActive,
                entityCount = sample.entityCount,
            ).also { it.merge(sample) }
    }
}

/** Bounded insertion-ordered minute accumulator; overflow retains the newest target-minutes. */
internal class DashboardPerformanceAccumulator(private val maxEntries: Int) {
    private val entries = LinkedHashMap<DashboardPerformanceKey, DashboardPerformanceAggregate>()

    init {
        require(maxEntries > 0)
    }

    /** Returns the evicted aggregate when a new distinct target-minute exceeds the bound. */
    fun add(sample: DashboardPerformanceSample): DashboardPerformanceAggregate? {
        val key = DashboardPerformanceKey(sample.instance, sample.path, sample.minute)
        entries[key]?.merge(sample) ?: run { entries[key] = DashboardPerformanceAggregate.from(sample) }
        if (entries.size <= maxEntries) return null
        val oldest = entries.entries.first()
        entries.remove(oldest.key)
        return oldest.value
    }

    fun drain(): List<DashboardPerformanceAggregate> =
        entries.values.toList().also { entries.clear() }

    fun isNotEmpty(): Boolean = entries.isNotEmpty()
    fun size(): Int = entries.size
}

internal data class DashboardPerformanceMinute(
    val minute: Long,
    val filterActive: Boolean,
    val entityCount: Int,
    val sampleMs: Long,
    val frames: Long,
    val payloadBytes: Long,
    val updates: Long,
    val hydrationUpdates: Long,
    val observerMicros: Long,
    val droppedFrames: Long,
    val stateTaskMicros: Long,
    val stateTaskMaxMicros: Long,
    val interactionCount: Long,
    val interactionMaxMicros: Long,
    val inputDelayMicros: Long,
    val interactionProcessingMicros: Long,
    val presentationMicros: Long,
    val loafCount: Long,
    val blockingMicros: Long,
    val loafMaxMicros: Long,
    val scriptMicros: Long,
    val renderMicros: Long,
    val longTaskCount: Long,
) {
    fun json(): JSONObject = JSONObject()
        .put("minute", minute)
        .put("filterActive", filterActive)
        .put("entityCount", entityCount)
        .put("sampleMs", sampleMs)
        .put("updatesPerSec", rate(updates))
        .put("payloadBytesPerSec", rate(payloadBytes))
        .put("mainThreadMsPerSec", microsPerSecond(stateTaskMicros))
        .put("blockedMsPerSec", microsPerSecond(blockingMicros))
        .put("interactionCount", interactionCount)
        .put("worstInteractionMs", microsToMs(interactionMaxMicros))
        .put("longestStateTaskMs", microsToMs(stateTaskMaxMicros))
        .put("longestFrameMs", microsToMs(loafMaxMicros))
        .put("frames", frames)
        .put("hydrationUpdates", hydrationUpdates)
        .put("observerMicros", observerMicros)
        .put("droppedFrames", droppedFrames)
        .put("loafCount", loafCount)
        .put("longTaskCount", longTaskCount)
        .put("scriptMsPerSec", microsPerSecond(scriptMicros))
        .put("renderMsPerSec", microsPerSecond(renderMicros))
        .put("slowestInteraction", JSONObject()
            .put("inputDelayMs", microsToMs(inputDelayMicros))
            .put("processingMs", microsToMs(interactionProcessingMicros))
            .put("presentationMs", microsToMs(presentationMicros)))

    private fun rate(value: Long): Double = value * 1000.0 / sampleMs.coerceAtLeast(1L)
    private fun microsPerSecond(value: Long): Double = value.toDouble() / sampleMs.coerceAtLeast(1L)
    private fun microsToMs(value: Long): Double = value / 1000.0
}

internal fun dashboardPerformanceHistoryJson(
    rows: List<DashboardPerformanceMinute>,
    retentionDays: Int,
): String = JSONObject()
    .put("schema", 1)
    .put("retentionDays", retentionDays)
    .put("resolution", "minute")
    .put("samples", JSONArray(rows.map(DashboardPerformanceMinute::json)))
    .toString()
