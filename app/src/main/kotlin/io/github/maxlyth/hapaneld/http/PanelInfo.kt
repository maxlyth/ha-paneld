package io.github.maxlyth.hapaneld.http

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.Settings
import android.webkit.WebSettings
import io.github.maxlyth.hapaneld.control.SystemController
import io.github.maxlyth.hapaneld.device.DeviceProfile
import android.webkit.WebView
import io.github.maxlyth.hapaneld.BuildConfig
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Gathers the panel facts shown on the info page (`GET /`). Static device/version facts live here;
 * runtime status (MQTT/LED/sensors) is passed in as [extras] by the service, which owns those
 * objects. Returns an ordered map rendered verbatim as a key/value table.
 */
object PanelInfo {
    internal data class PhysicalDisplaySize(
        val diagonalInches: Double,
        val widthCm: Double,
        val heightCm: Double,
    )

    /** Physical dimensions are valid only when the profile supplies independently verified PPI.
     *  Android's current/base logical density must never be used to infer panel dimensions. */
    internal fun physicalDisplaySize(widthPx: Int, heightPx: Int, physicalPpi: Int?): PhysicalDisplaySize? {
        if (widthPx <= 0 || heightPx <= 0 || physicalPpi == null || physicalPpi <= 0) return null
        return PhysicalDisplaySize(
            diagonalInches = Math.hypot(widthPx.toDouble(), heightPx.toDouble()) / physicalPpi,
            widthCm = widthPx * 2.54 / physicalPpi,
            heightCm = heightPx * 2.54 / physicalPpi,
        )
    }

    fun collect(
        context: Context,
        extras: Map<String, String>,
        profile: DeviceProfile = DeviceProfile.detect(),
    ): LinkedHashMap<String, String> {
        val m = LinkedHashMap<String, String>()
        m["ha-paneld"] = "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"
        m["Android"] = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        m["Firmware"] = Build.DISPLAY
        // Model + Platform promoted to lines 4-5 (panel identity up top). putAll below updates the values
        // in place without moving them (LinkedHashMap keeps first-insertion order).
        extras["Model"]?.let { m["Model"] = it }
        extras["Platform"]?.let { m["Platform"] = it }
        m["Device"] = "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})"
        m["Device ID"] = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "?"
        } catch (e: Throwable) {
            "?"
        }
        m["CPU"] = cpu()
        m["RAM"] = ram(context)
        m["Storage"] = storage()
        m["Display"] = display(context, profile.physicalPpi)
        m["System WebView"] = webViewStatus(context).display
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
        val free = fs.availableBlocksLong * fs.blockSizeLong
        // Advertised spec = the whole internal eMMC, so a user can tell e.g. an 8 GB panel from a 32 GB
        // one; /data is just one partition (~3.5 GB) and reads nothing like the box spec. Report eMMC
        // total + usable /data free; fall back to the /data total if the eMMC size can't be read.
        val emmc = emmcTotalBytes()
        if (emmc != null) "${gb(emmc)} eMMC · ${gb(free)} free (data)"
        else "${gb(fs.blockCountLong * fs.blockSizeLong)} (data) · ${gb(free)} free"
    } catch (e: Throwable) {
        "?"
    }

    /** Total internal storage = the largest WHOLE block device in `/sys/block` (its `size` in 512-byte
     *  sectors). Scanning beats deriving from /data's mount, which is a by-name symlink (e.g.
     *  `/dev/block/by-name/userdata`) an app can't traverse. Matches whole eMMC/SD devices only — the
     *  full-string regex excludes partitions and eMMC boot/rpmb areas (`mmcblk1boot0`, `mmcblk1p20`). */
    private fun emmcTotalBytes(): Long? = try {
        File("/sys/block").listFiles { f -> f.name.matches(Regex("mmcblk\\d+|sd[a-z]")) }
            ?.mapNotNull { runCatching { File(it, "size").readText().trim().toLong() }.getOrNull() }
            ?.maxOrNull()
            ?.let { it * 512L }
    } catch (e: Throwable) {
        null
    }

    private fun gib(bytes: Long): String = "%.1f GiB".format(bytes / 1024.0 / 1024.0 / 1024.0)

    /** Marketing GB (÷10⁹) so the number reads close to the advertised spec (8 GB / 32 GB …). */
    private fun gb(bytes: Long): String = "%.1f GB".format(bytes / 1e9)

    /** Physical resolution, current logical density, and independently profiled physical PPI when known. */
    private fun display(context: Context, physicalPpi: Int?): String = try {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val dm = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        displaySummary(dm.widthPixels, dm.heightPixels, dm.densityDpi, physicalPpi)
    } catch (e: Throwable) {
        "?"
    }

    /** Pure formatter so a base logical density can never regress into being labelled physical/native. */
    internal fun displaySummary(width: Int, height: Int, logicalDpi: Int, physicalPpi: Int?): String =
        "${width}×${height} px · logical $logicalDpi dpi" +
            (physicalPpi?.let { " · physical ≈$it ppi" } ?: "")

    /** Package + real-engine WebView status for the info row and the health banner. */
    // [engineMajor] = the real Chromium major (from the WebView UA when the package version looked old),
    // or the package major on the fast path, or null. The WebView auto-heal keys its install on this.
    class WebViewStatus(val display: String, val tooOld: Boolean, val engineMajor: Int? = null)

    /**
     * WebView status, disambiguating the Cromite/LineageOS version spoof. The package versionName
     * (`getCurrentWebViewPackage`) is what those SystemWebView builds STAMP to the OEM stock value to
     * pass a signature-locked provider gate — so it lies (e.g. reports 83 while the engine is Chromium
     * 147). We escalate to the real engine version (the WebView UA, which isn't spoofed) ONLY when the
     * package version looks too old, so a modern-package panel never pays the provider-load cost and a
     * Cromite-updated panel is no longer falsely warned.
     */
    fun webViewStatus(context: Context): WebViewStatus {
        val pkg = webViewPackage()
        val pkgMajor = PanelHealth.chromiumMajor(pkg)
        // Fast path: unknown or already ≥ threshold — trust it, don't load the WebView provider.
        if (pkgMajor == null || pkgMajor >= PanelHealth.MIN_CHROMIUM) return WebViewStatus(pkg, false, pkgMajor)
        // Package looks old: could be genuinely old, or a Cromite/LineageOS swap stamping the OEM
        // version. The UA carries the true engine version — use it.
        val engine = engineVersion(context)
        val engineMajor = engine?.substringBefore('.')?.toIntOrNull()
        val display = if (engineMajor != null && engineMajor != pkgMajor) "$pkg · engine Chromium $engine" else pkg
        return WebViewStatus(display, PanelHealth.webViewTooOld(pkg, engineMajor), engineMajor ?: pkgMajor)
    }

    private fun webViewPackage(): String = try {
        WebView.getCurrentWebViewPackage()?.let { "${it.packageName} ${it.versionName}" } ?: "unknown"
    } catch (e: Throwable) {
        "unknown"
    }

    // Real engine version from the WebView default UA, computed once and cached: the fetch loads the
    // WebView provider into this process and must run on a Looper thread (see [defaultUserAgent]), and
    // it's only reached via the escalation gate above, so modern-package panels never trigger it.
    @Volatile private var uaComputed = false
    @Volatile private var uaEngineVersion: String? = null

    private fun engineVersion(context: Context): String? {
        if (uaComputed) return uaEngineVersion
        synchronized(this) {
            if (uaComputed) return uaEngineVersion
            uaEngineVersion = defaultUserAgent(context)?.let { PanelHealth.engineVersionFromUa(it) }
            uaComputed = true
            return uaEngineVersion
        }
    }

    /** [WebSettings.getDefaultUserAgent] initialises the WebView provider, which is only safe on a
     *  Looper thread; the info page renders on a Ktor worker, so hop to the main thread (one-shot). */
    private fun defaultUserAgent(context: Context): String? = try {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            WebSettings.getDefaultUserAgent(context)
        } else {
            val ref = AtomicReference<String?>()
            val latch = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                ref.set(runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull())
                latch.countDown()
            }
            latch.await(3, TimeUnit.SECONDS)
            ref.get()
        }
    } catch (e: Throwable) {
        null
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

    /** Package id of Fully Kiosk Browser — a dashboard renderer some users prefer over the Companion. */
    const val FULLY_KIOSK = "de.ozerov.fully"

    /** Dashboard renderers that will actually draw on this panel: HA Companion (either variant), Fully
     *  Kiosk, the built-in renderer (only when it is BOTH selected — `dashboard_package=builtin` — AND
     *  configured with an `ha_url`; an `ha_url` alone doesn't render anything), and any explicitly
     *  configured dashboard package. Empty ⇒ nothing will draw a dashboard (ha-paneld itself still runs
     *  fine). Drives the soft "no dashboard app" health notice, so a mere `ha_url` must not suppress it. */
    fun dashboardRenderers(context: Context, dashboardPackage: String, haUrl: String = ""): List<String> {
        val out = mutableListOf<String>()
        if (COMPANION_IDS.any { installed(context, it) }) out += "HA Companion"
        if (installed(context, FULLY_KIOSK)) out += "Fully Kiosk"
        if (dashboardPackage == SystemController.BUILTIN_DASHBOARD && haUrl.isNotBlank()) out += "Built-in renderer"
        if (dashboardPackage.isNotBlank() && installed(context, dashboardPackage)) out += dashboardPackage
        return out
    }

    private fun installed(context: Context, id: String): Boolean = try {
        context.packageManager.getPackageInfo(id, 0); true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
