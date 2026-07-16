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
