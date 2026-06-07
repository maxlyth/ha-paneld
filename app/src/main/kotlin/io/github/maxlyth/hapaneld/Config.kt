package io.github.maxlyth.hapaneld

import android.content.Context
import android.os.Build
import android.provider.Settings
import io.github.maxlyth.hapaneld.device.DeviceProfile
import java.util.Locale

/**
 * Runtime configuration. v0.1.0 reads from SharedPreferences with sensible defaults; a Web UI
 * (Phase >=2) will write these. The MQTT broker defaults to empty, which disables MQTT — the
 * /play HTTP contract works standalone without a broker, so first-run never blocks on MQTT.
 */
class Config(context: Context) {
    private val appCtx = context.applicationContext
    private val prefs = context.getSharedPreferences("ha-paneld", Context.MODE_PRIVATE)

    val httpPort: Int get() = prefs.getInt("http_port", DEFAULT_PORT)

    /** Empty => MQTT disabled. e.g. "tcp://172.31.12.1:1883". */
    val mqttBroker: String get() = prefs.getString("mqtt_broker", "")!!
    val mqttUser: String get() = prefs.getString("mqtt_user", "")!!
    val mqttPassword: String get() = prefs.getString("mqtt_password", "")!!

    /** Stable per-panel id used in entity_ids / MQTT topics. Defaults to a slug of the device name,
     *  but the SoC model is identical across a fleet (e.g. `px30_evb`), so when no real device name
     *  is set we append a short stable per-device suffix to avoid collisions out of the box. */
    val panelId: String
        get() = prefs.getString("panel_id", null) ?: defaultPanelId()

    /** True when [panelId] is the auto-derived default (no explicit panel_id set yet). */
    val panelIdIsDefault: Boolean get() = prefs.getString("panel_id", null).isNullOrBlank()

    private fun defaultPanelId(): String {
        val name = Settings.Global.getString(appCtx.contentResolver, Settings.Global.DEVICE_NAME)
        // A meaningful, non-generic device name → use it; else model + a short ANDROID_ID suffix.
        return if (!name.isNullOrBlank() && !name.equals(Build.MODEL, ignoreCase = true)) slug(name)
        else slug(Build.MODEL) + "_" + androidId.takeLast(4).ifBlank { "panel" }
    }

    /** Persist a new panel id (used by the HTTP config page). */
    fun setPanelId(id: String) {
        prefs.edit().putString("panel_id", id).apply()
    }

    /**
     * HA device display name (`device.name` in discovery). Defaults to the device's own name —
     * the same source the HA Companion app uses for its default device name. (ha-paneld can't read
     * the Companion app's private device_id across the Android sandbox, so it mirrors the heuristic
     * rather than the exact registration id.)
     */
    val friendlyName: String
        get() = prefs.getString("friendly_name", null)?.takeIf { it.isNotBlank() } ?: deviceName()
    fun setFriendlyName(name: String) {
        prefs.edit().putString("friendly_name", name).apply()
    }

    /** Stable per-device id (Settings.Secure.ANDROID_ID); used as the HA device serial_number. */
    val androidId: String
        get() = Settings.Secure.getString(appCtx.contentResolver, Settings.Secure.ANDROID_ID) ?: ""

    /** The device's configured name (Companion's default-name source), else the model. */
    private fun deviceName(): String =
        (Settings.Global.getString(appCtx.contentResolver, Settings.Global.DEVICE_NAME)
            ?: Build.MODEL).ifBlank { Build.MODEL }

    /** Persist MQTT settings (used by the HTTP config page). A null password leaves it unchanged. */
    fun setMqtt(broker: String, user: String, password: String?) {
        prefs.edit().apply {
            putString("mqtt_broker", broker)
            putString("mqtt_user", user)
            if (password != null) putString("mqtt_password", password)
            apply()
        }
    }

    /** App package whose force-stop+relaunch is the dashboard "reload". Empty => reload disabled. */
    val dashboardPackage: String get() = prefs.getString("dashboard_package", "")!!
    fun setDashboardPackage(pkg: String) {
        prefs.edit().putString("dashboard_package", pkg).apply()
    }

    /** Launcher package the Launcher button brings forward. Empty => auto-pick a non-default home. */
    val launcherPackage: String get() = prefs.getString("launcher_package", "")!!
    fun setLauncherPackage(pkg: String) {
        prefs.edit().putString("launcher_package", pkg).apply()
    }

    /** Master switch for the (instrumentation-only) performance sampler. Default on, but page-view
     *  gated so it idles near-zero; a user who's finished tuning can hard-disable it here. */
    val instrumentationEnabled: Boolean get() = prefs.getBoolean("instrumentation", true)
    fun setInstrumentation(on: Boolean) {
        prefs.edit().putBoolean("instrumentation", on).apply()
    }

    // Wake the screen locally the instant proximity reads near (low latency, network-independent).
    // Default on where a proximity sensor exists; the HA switch can disable it (e.g. a hallway panel).
    val wakeOnWave: Boolean get() = prefs.getBoolean("wake_on_wave", true)
    fun setWakeOnWave(on: Boolean) {
        prefs.edit().putBoolean("wake_on_wave", on).apply()
    }

    // After an app update the launcher shows the App UI; when configured + MQTT-connected, bounce back
    // to the dashboard so it doesn't linger. Default on.
    val autoReturnDashboard: Boolean get() = prefs.getBoolean("auto_return_dashboard", true)
    fun setAutoReturnDashboard(on: Boolean) {
        prefs.edit().putBoolean("auto_return_dashboard", on).apply()
    }

    /**
     * HA device card manufacturer/model. The OS Build props are the generic SoC platform
     * (e.g. `rockchip`/`px30_evb`), not the product, so these are configurable — set e.g.
     * "Sonoff" / "NSPanel Pro 120". Default to the generic agent identity; ha-paneld's own version
     * is reported separately as the device `sw_version`.
     */
    // The active device profile, attached once at service startup; supplies per-panel manufacturer/
    // model defaults when the user hasn't set them. Null before attach (resolution falls back to Build).
    @Volatile private var profile: DeviceProfile? = null
    fun attachProfile(p: DeviceProfile) { profile = p }

    /** Raw user-set values (empty if unset) — for the Configure form's input value. */
    val manufacturerRaw: String get() = prefs.getString("manufacturer", "")!!
    val modelRaw: String get() = prefs.getString("model", "")!!

    /** Resolved HA device-card manufacturer: user value → profile default → inferred from Build. */
    val manufacturer: String
        get() = manufacturerRaw.ifBlank { null }
            ?: profile?.manufacturer
            ?: Build.MANUFACTURER.replaceFirstChar { it.titlecase(Locale.ROOT) }.ifBlank { "Unknown" }

    /** Resolved HA device-card model. User value is used verbatim; otherwise the profile/inferred name
     *  gets a " (ha-paneld)" suffix so this device is distinguishable from a co-installed integration
     *  managing the same hardware (HA shows the model in the device list). */
    val model: String
        get() = modelRaw.ifBlank { null }
            ?: ((profile?.model ?: inferredModel()) + " (ha-paneld)")

    private fun inferredModel(): String =
        listOf(Build.MODEL, Build.DEVICE, Build.PRODUCT).firstOrNull { !it.isNullOrBlank() } ?: "panel"

    fun setHardware(manufacturer: String, model: String) {
        prefs.edit().putString("manufacturer", manufacturer).putString("model", model).apply()
    }

    // --- proximity calibration (raw values stay on-device & in the HTTP UI; only the derived
    // binary is published to HA, so a graded ToF can't flood the recorder). The near/far captures
    // absorb the cross-device scale + polarity inversion; the published binary is a Schmitt trigger
    // whose dead-zone width comes from the sensitivity preset. ---

    /** Hysteresis band width as a fraction of the near/far capture span (flap resistance). */
    enum class ProxSensitivity(val fraction: Float) { HIGH(0.08f), MEDIUM(0.15f), LOW(0.30f) }

    val proximityNearRaw: Float get() = prefs.getFloat("prox_near_raw", Float.NaN)
    val proximityFarRaw: Float get() = prefs.getFloat("prox_far_raw", Float.NaN)
    val proximityThreshold: Float get() = prefs.getFloat("prox_threshold", Float.NaN)
    val proximityNearBelow: Boolean get() = prefs.getBoolean("prox_near_below", true)
    val proximityCalibrated: Boolean
        get() = !proximityNearRaw.isNaN() && !proximityFarRaw.isNaN() && !proximityThreshold.isNaN()
    val proximitySensitivity: ProxSensitivity
        get() = runCatching { ProxSensitivity.valueOf(prefs.getString("prox_sensitivity", "MEDIUM")!!) }
            .getOrDefault(ProxSensitivity.MEDIUM)

    /** Schmitt half-band in raw units = sensitivity × |near − far|. 0 when uncalibrated. */
    val proximityMargin: Float
        get() = if (proximityCalibrated)
            proximitySensitivity.fraction * kotlin.math.abs(proximityNearRaw - proximityFarRaw) else 0f

    /** Store one capture; when both exist, derive threshold (midpoint) + polarity (near = below?). */
    fun captureProximity(step: String, raw: Float) {
        prefs.edit().putFloat(if (step == "near") "prox_near_raw" else "prox_far_raw", raw).apply()
        val n = proximityNearRaw; val f = proximityFarRaw
        if (!n.isNaN() && !f.isNaN()) {
            prefs.edit().putFloat("prox_threshold", (n + f) / 2f).putBoolean("prox_near_below", n < f).apply()
        }
    }

    fun setProximityThreshold(v: Float) { prefs.edit().putFloat("prox_threshold", v).apply() }
    fun setProximitySensitivity(s: String) {
        runCatching { ProxSensitivity.valueOf(s) }.onSuccess {
            prefs.edit().putString("prox_sensitivity", it.name).apply()
        }
    }
    fun resetProximityCalibration() {
        prefs.edit().remove("prox_near_raw").remove("prox_far_raw").remove("prox_threshold").apply()
    }

    // --- last-known actuator state, re-applied/published on (re)connect so HA reflects reality ---

    /** Last navigated URL (published as the navigate state on connect; empty if never set). */
    var lastNavigate: String
        get() = prefs.getString("last_navigate", "")!!
        set(v) { prefs.edit().putString("last_navigate", v).apply() }

    /** Last LED state packed as "on,r,g,b" (e.g. "1,255,0,0"); empty if never set. */
    var lastLed: String
        get() = prefs.getString("last_led", "")!!
        set(v) { prefs.edit().putString("last_led", v).apply() }

    private fun slug(s: String): String =
        s.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifEmpty { "ha_paneld_panel" }

    companion object {
        const val DEFAULT_PORT = 8888
        const val VERSION = BuildConfig.VERSION_NAME
        const val MDNS_SERVICE_TYPE = "_ha-paneld._tcp.local."
    }
}
