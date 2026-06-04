package io.github.maxlyth.hapaneld.http

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.webkit.WebView
import io.github.maxlyth.hapaneld.BuildConfig

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
        m["Firmware"] = Build.DISPLAY
        m["Device"] = "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})"
        m["Device ID"] = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "?"
        } catch (e: Throwable) {
            "?"
        }
        m["CPU"] = cpu()
        m["RAM"] = ram(context)
        m["Storage"] = storage()
        m["System WebView"] = webView()
        m["HA Companion"] = companion(context)
        m.putAll(extras)
        return m
    }

    private fun cpu(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "?"
        val cores = Runtime.getRuntime().availableProcessors()
        val hw = Build.HARDWARE
        return "$cores cores · $abi · $hw"
    }

    private fun ram(context: Context): String = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        "${gib(mi.totalMem)} total · ${gib(mi.availMem)} free"
    } catch (e: Throwable) {
        "?"
    }

    private fun storage(): String = try {
        val fs = StatFs(Environment.getDataDirectory().path)
        val total = fs.blockCountLong * fs.blockSizeLong
        val free = fs.availableBlocksLong * fs.blockSizeLong
        "${gib(total)} total · ${gib(free)} free (data)"
    } catch (e: Throwable) {
        "?"
    }

    private fun gib(bytes: Long): String = "%.1f GiB".format(bytes / 1024.0 / 1024.0 / 1024.0)

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
