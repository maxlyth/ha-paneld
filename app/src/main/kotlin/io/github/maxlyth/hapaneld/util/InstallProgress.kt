package io.github.maxlyth.hapaneld.util

/**
 * Cross-thread progress for a one-shot install/update kicked off from the Install tab (managed
 * components + WebView heal). The service flips [running] around the off-thread install and records the
 * installer's result string; the web UI polls GET /api/v1/install/status to know when to re-read the
 * installed versions. Single-slot by design — only one component install runs at a time (the UI disables
 * the action buttons while [running]).
 */
object InstallProgress {
    @Volatile var running: Boolean = false; private set
    @Volatile var component: String = ""; private set
    @Volatile var message: String = ""; private set

    /** Mark an install of [component] as started. Returns false if one is already in flight. */
    @Synchronized
    fun start(component: String): Boolean {
        if (running) return false
        this.component = component
        this.message = "Working…"
        this.running = true
        return true
    }

    /** Record the finished install's [result] (installer return string) and clear [running]. */
    @Synchronized
    fun finish(result: String) {
        this.message = result
        this.running = false
    }

    fun json(): String =
        """{"running":$running,"component":${esc(component)},"message":${esc(message)}}"""

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
}
