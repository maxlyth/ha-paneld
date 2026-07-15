package io.github.maxlyth.hapaneld.shizuku

import android.content.Context

/** Local-only consent. It is intentionally absent from HTTP, MQTT, config bundles, and fleet push. */
object ShizukuConsent {
    private const val PREFS = "ha-paneld-shizuku"
    private const val ENABLED = "enabled"

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(ENABLED, false)

    fun enable(context: Context) = prefs(context).edit().putBoolean(ENABLED, true).apply()

    fun disable(context: Context) = prefs(context).edit().clear().apply()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
