package io.github.maxlyth.hapaneld.util

import kotlinx.coroutines.Job

/**
 * Process-wide ownership and progress for destructive control-plane operations. HTTP, MQTT, and scheduled
 * callers all acquire the same single slot before installs, uninstall, repair, or restore; the web UI polls
 * GET /api/v1/install/status for the current owner and result.
 */
object InstallProgress {
    class Ticket internal constructor(internal val id: Long)

    enum class Outcome(val wireValue: String) {
        SUCCEEDED("succeeded"),
        PARTIAL("partial"),
        FAILED("failed"),
        SKIPPED("skipped"),
        ROLLED_BACK("rolled_back"),
        ROLLBACK_FAILED("rollback_failed"),
    }

    /** Fixed-shape, bounded machine result for multi-component control-plane operations. Details are
     * deliberately short and optional: this status endpoint must never become a path for bundle data,
     * credentials, entity ids, or other request-controlled payloads. */
    data class ComponentResult(
        val status: Outcome,
        val items: Int? = null,
        val detail: String = "",
    )

    data class OperationResult(
        val status: Outcome,
        val config: ComponentResult? = null,
        val profiles: ComponentResult? = null,
        val companion: ComponentResult? = null,
        val rollback: ComponentResult? = null,
    )

    @Volatile var running: Boolean = false; private set
    @Volatile var component: String = ""; private set
    @Volatile var message: String = ""; private set
    @Volatile private var result: OperationResult? = null
    private var generation = 0L
    private var active: Ticket? = null

    /** Claim the operation lane for [component]. Returns null if another owner is already in flight. */
    @Synchronized
    fun start(component: String): Ticket? {
        if (running) return null
        val ticket = Ticket(++generation)
        active = ticket
        this.component = component
        this.message = "Working…"
        this.result = null
        this.running = true
        return ticket
    }

    /** Record [result] only if [ticket] still owns the single progress slot. */
    @Synchronized
    fun finish(ticket: Ticket, result: String, structured: OperationResult? = null) {
        if (active != ticket) return
        this.message = result
        this.result = structured
        this.running = false
        this.active = null
    }

    /** Ensure cancellation before a launched body begins cannot strand the process-global slot busy. */
    fun finishOnFailure(ticket: Ticket, job: Job): Job = job.also {
        it.invokeOnCompletion { cause -> if (cause != null) finish(ticket, "cancelled") }
    }

    @Synchronized
    fun json(): String = buildString {
        append("{\"running\":").append(running)
        append(",\"component\":").append(esc(component))
        append(",\"message\":").append(esc(message))
        result?.let { append(",\"result\":").append(resultJson(it)) }
        append('}')
    }

    private fun resultJson(result: OperationResult): String = buildString {
        append("{\"status\":").append(esc(result.status.wireValue))
        result.config?.let { append(",\"config\":").append(componentJson(it)) }
        result.profiles?.let { append(",\"profiles\":").append(componentJson(it)) }
        result.companion?.let { append(",\"companion\":").append(componentJson(it)) }
        result.rollback?.let { append(",\"rollback\":").append(componentJson(it)) }
        append('}')
    }

    private fun componentJson(result: ComponentResult): String = buildString {
        append("{\"status\":").append(esc(result.status.wireValue))
        result.items?.let { append(",\"items\":").append(it.coerceAtLeast(0)) }
        if (result.detail.isNotBlank()) append(",\"detail\":").append(esc(result.detail.take(MAX_DETAIL_CHARS)))
        append('}')
    }

    /** Minimal JSON string escaper — installer results can contain quotes/newlines/backslashes. */
    private fun esc(s: String): String {
        val b = StringBuilder(s.length + 2).append('"')
        for (c in s) when (c) {
            '"' -> b.append("\\\"")
            '\\' -> b.append("\\\\")
            '\n' -> b.append("\\n")
            '\r' -> b.append("\\r")
            '\t' -> b.append("\\t")
            else -> if (c < ' ') b.append("\\u%04x".format(c.code)) else b.append(c)
        }
        return b.append('"').toString()
    }

    private const val MAX_DETAIL_CHARS = 256
}
