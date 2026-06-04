package io.github.maxlyth.hapaneld.http

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.webkit.WebView
import io.github.maxlyth.hapaneld.BuildConfig
import io.github.maxlyth.hapaneld.Config

/**
 * Gathers the panel facts shown on the info page (`GET /`). Static device/version facts live here;
 * runtime status (MQTT/LED/sensors) is passed in as [extras] by the service, which owns those
 * objects. Returns an ordered map rendered verbatim as a key/value table.
 */
object PanelInfo {
    fun collect(context: Context, extras: Map<String, String>): LinkedHashMap<String, String> {
        val m = LinkedHashMap<String, String>()
        m["ha-paneld"] = "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"
        m["Android"] = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        m["Device"] = "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})"
        m["ABI"] = Build.SUPPORTED_ABIS.firstOrNull() ?: "?"
        m["System WebView"] = webView()
        m["HA Companion"] = companion(context)
        m.putAll(extras)
        return m
    }

    private fun webView(): String = try {
        WebView.getCurrentWebViewPackage()?.let { "${it.packageName} ${it.versionName}" } ?: "unknown"
    } catch (e: Throwable) {
        "unknown"
    }

    private fun companion(context: Context): String {
        for (id in COMPANION_IDS) {
            try {
                val pi = context.packageManager.getPackageInfo(id, 0)
                return "${pi.versionName} ($id)"
            } catch (e: PackageManager.NameNotFoundException) {
                // try next variant
            }
        }
        return "not installed"
    }

    private val COMPANION_IDS = listOf(
        "io.homeassistant.companion.android",
        "io.homeassistant.companion.android.minimal",
    )
}
