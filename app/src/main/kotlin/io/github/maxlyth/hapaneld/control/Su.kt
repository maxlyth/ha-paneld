package io.github.maxlyth.hapaneld.control

import android.util.Log

/**
 * Root command execution that copes with the two su syntaxes seen across the panel fleet:
 * toolbox `su -c '<cmd>'` (Sonoff PX30) and Android `su 0 sh -c '<cmd>'` (Tuya TPA10 userdebug).
 * Probes once, caches the working form. Graceful: returns false if no su works (a panel without
 * root simply loses the root-gated capabilities — LED, reload, reboot — like load-if-present).
 */
object Su {
    private const val TAG = "ha-paneld/su"

    // -1 = unprobed, 0 = "su -c", 1 = "su 0 sh -c", 2 = none-found
    @Volatile
    private var form: Int = -1

    private fun argv(f: Int, cmd: String): Array<String> = when (f) {
        0 -> arrayOf("su", "-c", cmd)
        else -> arrayOf("su", "0", "sh", "-c", cmd)
    }

    /** Run [cmd] as root, waiting for completion. Returns true on exit 0. */
    @Synchronized
    fun run(cmd: String): Boolean {
        val forms = if (form in 0..1) intArrayOf(form) else intArrayOf(0, 1)
        for (f in forms) {
            try {
                val ok = Runtime.getRuntime().exec(argv(f, cmd)).waitFor() == 0
                if (ok) { form = f; return true }
            } catch (e: Exception) {
                Log.d(TAG, "su form $f failed for: $cmd", e)
            }
        }
        if (form == -1) form = 2
        return false
    }

    /** Fire [cmd] as root without waiting (for commands like `reboot` that kill the process). */
    fun fireAndForget(cmd: String) {
        val forms = if (form in 0..1) intArrayOf(form) else intArrayOf(0, 1)
        for (f in forms) {
            try {
                Runtime.getRuntime().exec(argv(f, cmd))
                return
            } catch (e: Exception) {
                Log.d(TAG, "su fire form $f failed", e)
            }
        }
    }

    fun available(): Boolean = run("true")
}
