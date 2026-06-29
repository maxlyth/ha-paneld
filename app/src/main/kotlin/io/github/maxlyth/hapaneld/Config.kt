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
        // The panel_id is the HA device identifier, so only an ACTUAL change invalidates the cached device
        // link. The config form resubmits panel_id on every save (unchanged), so clearing unconditionally
        // would drop the link on every save.
        val changed = id != prefs.getString("panel_id", null)
        prefs.edit().putString("panel_id", id).apply()
        if (changed) prefs.edit().remove("ha_device_url").apply()
    }

    /** Cached HA device-settings URL (resolved once via HaLink when the MQTT creds are a valid HA user);
     *  blank until/unless resolved. Shown as an "Open in Home Assistant" link on the info page. */
    val haDeviceUrl: String get() = prefs.getString("ha_device_url", "")!!
    fun setHaDeviceUrl(url: String) { prefs.edit().putString("ha_device_url", url).apply() }

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

    /**
     * Opt-in blocklist of intrusive vendor packages to **tame** on boot (force-stop + disable the
     * boot-relaunch + strip the floating-overlay permission). The non-empty list IS the opt-in — nothing
     * is ever touched unless the user deliberately adds a package here; the default is empty, so a stock
     * panel is never modified. A confirmed example HA users add is `com.eWeLinkControlPanel` (the
     * eWeLink/Sonoff control-panel app that relaunches on boot and draws a widget over the dashboard).
     * Critical system packages are refused regardless (see [control.TameController] + the daemon backstop).
     */
    val tameVendorPackages: List<String>
        get() = prefs.getString("tame_vendor_packages", "")!!
            .split(Regex("[\\s,]+")).map { it.trim() }.filter { it.isNotEmpty() }
    /** Raw user-set value (whitespace/comma-separated) — for the Configure form's input value. */
    val tameVendorPackagesRaw: String get() = prefs.getString("tame_vendor_packages", "")!!
    fun setTameVendorPackages(raw: String) {
        prefs.edit().putString("tame_vendor_packages", raw.trim()).apply()
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

    // Prevent the vendor firmware idle-dimming the backlight at the screen-off timeout (it drops the
    // hardware backlight to ~1% after the timeout even while the OS keeps the screen on). On by default —
    // these are mains-powered wall panels; turn it off to restore the firmware's own dimming behaviour.
    val preventIdleDim: Boolean get() = prefs.getBoolean("prevent_idle_dim", true)
    fun setPreventIdleDim(on: Boolean) {
        prefs.edit().putBoolean("prevent_idle_dim", on).apply()
    }

    // App watchdog: poll the dashboard app and self-heal it — relaunch if its process dies, and return
    // to it if it's been backgrounded too long. Opt-in (off by default): a stock panel never auto-acts.
    val watchdogEnabled: Boolean get() = prefs.getBoolean("watchdog_enabled", false)
    fun setWatchdogEnabled(on: Boolean) {
        prefs.edit().putBoolean("watchdog_enabled", on).apply()
    }

    // The screen-off timeout (ms) seen before we first raised it, so disabling preventIdleDim can restore
    // the firmware default. -1 = not yet captured.
    var savedScreenOffTimeout: Int
        get() = prefs.getInt("saved_screen_off_timeout", -1)
        set(v) {
            prefs.edit().putInt("saved_screen_off_timeout", v).apply()
        }

    // Soft on-screen navigation bar mode: "Off" | "Always on" | "Swipe reveal" (NavbarController.MODES).
    // Default Off — panels with a working native navbar (or no need for one) are untouched; the user
    // opts a panel in via the HA select. Persisted so the bar is restored on boot.
    val navbarMode: String get() = prefs.getString("navbar_mode", "Off")!!
    fun setNavbarMode(mode: String) {
        prefs.edit().putString("navbar_mode", mode).apply()
    }

    // After an app update the launcher shows the App UI; when configured + MQTT-connected, bounce back
    // to the dashboard so it doesn't linger. Default on.
    val autoReturnDashboard: Boolean get() = prefs.getBoolean("auto_return_dashboard", true)
    fun setAutoReturnDashboard(on: Boolean) {
        prefs.edit().putBoolean("auto_return_dashboard", on).apply()
    }

    // Silence the firmware startup chime by zeroing the ring/notification volume via Settings.System.
    // Default off — existing panels already have their own volume state; only opt in deliberately.
    val silenceBootChime: Boolean get() = prefs.getBoolean("silence_boot_chime", false)
    fun setSilenceBootChime(on: Boolean) { prefs.edit().putBoolean("silence_boot_chime", on).apply() }

    // --- remote log shipping (opt-in) --------------------------------------------------------------
    // Forward ha-paneld's OWN process logcat (its Log.* output + the Ktor/HiveMQ SLF4J library logs,
    // all emitted by this app's uid → readable with no READ_LOGS and no root) to a central aggregator
    // (e.g. a Vector collector) for fleet-wide debugging without per-panel `adb logcat`. OFF by default
    // with an EMPTY host — a stock panel ships nothing until deliberately configured. LAN-only by intent;
    // lines are redacted (tokens/passwords/URL secrets) before they leave the device. No on-panel UI:
    // set via the HTTP /config endpoint (provision.sh --log-* flags). See logship/LogShipper.
    val logShipEnabled: Boolean get() = prefs.getBoolean("log_ship_enabled", false)
    /** Sink host (e.g. a Vector collector). Empty => shipping stays inert regardless of the flag. */
    val logShipHost: String get() = prefs.getString("log_ship_host", "")!!
    val logShipPort: Int get() = prefs.getInt("log_ship_port", 514)
    /** Transport: "syslog" (TCP, RFC5424) or "http" (NDJSON POST). */
    val logShipProtocol: String get() = prefs.getString("log_ship_protocol", "syslog")!!
    /** True only when shipping is enabled AND a sink host is configured. */
    val logShipActive: Boolean get() = logShipEnabled && logShipHost.isNotBlank()
    fun setLogShipping(enabled: Boolean, host: String, port: Int, protocol: String) {
        prefs.edit().apply {
            putBoolean("log_ship_enabled", enabled)
            putString("log_ship_host", host.trim())
            putInt("log_ship_port", port)
            putString("log_ship_protocol", protocol.trim().lowercase(Locale.ROOT).ifBlank { "syslog" })
            apply()
        }
    }
    // Desired Zigbee-router state, persisted so the gateway can be auto-started on boot when nothing
    // else launches it (the NSPanel Pro gateway is not init-started — verified 2026-06-08). Default off.
    val zigbeeRouterEnabled: Boolean get() = prefs.getBoolean("zigbee_router_enabled", false)
    // True once the user has EXPLICITLY toggled the zigbee switch. Gates boot-reconcile: we only enforce
    // an off-state (killing a vendor-started gateway) when the user actually asked for off — never by our
    // default — so panels relying on stock vendor Zigbee are left untouched until configured.
    val zigbeeRouterConfigured: Boolean get() = prefs.getBoolean("zigbee_router_configured", false)
    fun setZigbeeRouterEnabled(on: Boolean) {
        prefs.edit().putBoolean("zigbee_router_enabled", on).putBoolean("zigbee_router_configured", true).apply()
    }

    // Optional on-panel auto-brightness engine (see control/AutoBrightnessController). Default OFF →
    // ha-paneld stays a pure brightness actuator; HA drives the screen. When ON, the engine maps a lux
    // stream (panel ALS where present, else HA-fed) to the backlight.
    val autoBrightness: Boolean get() = prefs.getBoolean("auto_brightness", false)
    fun setAutoBrightness(on: Boolean) {
        prefs.edit().putBoolean("auto_brightness", on).apply()
    }

    /** Dimmer(−) ↔ Brighter(+) bias added to the auto-brightness curve, in 0–255 brightness units. */
    val brightnessBias: Int get() = prefs.getInt("brightness_bias", 0)
    fun setBrightnessBias(v: Int) {
        prefs.edit().putInt("brightness_bias", v.coerceIn(-100, 100)).apply()
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

    // A user capture wins; otherwise fall back to the profile. A written profile is authoritative
    // knowledge — if it supplies near/far the panel is calibrated out of the box (no manual dance); the
    // two-point capture is the fallback for unprofiled sensors (and a user override).
    private val userCalibrated: Boolean get() = !prefs.getFloat("prox_threshold", Float.NaN).isNaN()
    val proximityNearRaw: Float get() {
        val v = prefs.getFloat("prox_near_raw", Float.NaN)
        return if (!v.isNaN()) v else profile?.proximityNearRaw ?: Float.NaN
    }
    val proximityFarRaw: Float get() {
        val v = prefs.getFloat("prox_far_raw", Float.NaN)
        return if (!v.isNaN()) v else profile?.proximityFarRaw ?: Float.NaN
    }
    val proximityThreshold: Float get() {
        val v = prefs.getFloat("prox_threshold", Float.NaN)
        if (!v.isNaN()) return v
        val n = profile?.proximityNearRaw; val f = profile?.proximityFarRaw
        return if (n != null && f != null) (n + f) / 2f else Float.NaN
    }
    val proximityNearBelow: Boolean get() {
        if (userCalibrated) return prefs.getBoolean("prox_near_below", true)        // user capture wins
        profile?.proximityNearBelow?.let { return it }                             // explicit profile polarity
        val n = profile?.proximityNearRaw; val f = profile?.proximityFarRaw
        if (n != null && f != null) return n < f                                   // derived from profile near/far
        return prefs.getBoolean("prox_near_below", true)                           // legacy default
    }
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
