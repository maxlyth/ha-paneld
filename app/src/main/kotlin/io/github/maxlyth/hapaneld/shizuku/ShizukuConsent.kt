package io.github.maxlyth.hapaneld.shizuku

import android.content.Context

/** Local-only consent. It is intentionally absent from HTTP, MQTT, config bundles, and fleet push. */
object ShizukuConsent {
    private const val PREFS = "ha-paneld-shizuku"
    private const val ENABLED = "enabled"
    private const val MANAGED = "managed"
    private const val AUTO_UPDATE = "auto_update"

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(ENABLED, false)
    fun managed(context: Context): Boolean = prefs(context).getBoolean(MANAGED, false)
    fun autoUpdate(context: Context): Boolean =
        prefs(context).getBoolean(AUTO_UPDATE, managed(context))

    fun enable(context: Context, managed: Boolean) {
        prefs(context).edit()
            .putBoolean(ENABLED, true)
            .putBoolean(MANAGED, managed)
            .putBoolean(AUTO_UPDATE, managed)
            .apply()
    }

    fun setAutoUpdate(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(AUTO_UPDATE, enabled).apply()
    }

    fun disable(context: Context) {
        prefs(context).edit()
            .putBoolean(ENABLED, false)
            .putBoolean(MANAGED, false)
            .putBoolean(AUTO_UPDATE, false)
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
