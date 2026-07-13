package io.github.maxlyth.hapaneld

import android.content.Context
import android.content.ContentResolver
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import android.util.Log
import io.github.maxlyth.hapaneld.config.Migrations
import io.github.maxlyth.hapaneld.config.SettingSpec
import io.github.maxlyth.hapaneld.config.SettingType
import io.github.maxlyth.hapaneld.config.SettingsRegistry
import io.github.maxlyth.hapaneld.device.DeviceProfile
import io.github.maxlyth.hapaneld.util.AndroidInput
import java.util.Locale

/**
 * Runtime configuration. v0.1.0 reads from SharedPreferences with sensible defaults; a Web UI
 * (Phase >=2) will write these. The MQTT broker defaults to empty, which disables MQTT — the
 * /play HTTP contract works standalone without a broker, so first-run never blocks on MQTT.
 */
class Config private constructor(
    private val prefs: SharedPreferences,
    private val contentResolver: ContentResolver?,
) {
    constructor(context: Context) : this(
        context.getSharedPreferences("ha-paneld", Context.MODE_PRIVATE),
        context.applicationContext.contentResolver,
    )

    /** JVM-test seam; identity defaults that consult Android settings require the production constructor. */
    internal constructor(prefs: SharedPreferences) : this(prefs, null)

    val httpPort: Int get() = prefs.getInt("http_port", DEFAULT_PORT)

    /** Empty => MQTT disabled. e.g. "tcp://192.168.1.10:1883". */
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
        val resolver = requireNotNull(contentResolver) { "Android settings unavailable" }
        val name = Settings.Global.getString(resolver, Settings.Global.DEVICE_NAME)
        // A meaningful, non-generic device name → use it; else model + a short ANDROID_ID suffix.
        return if (!name.isNullOrBlank() && !name.equals(Build.MODEL, ignoreCase = true)) slug(name)
        else slug(Build.MODEL) + "_" + androidId.takeLast(4).ifBlank { "panel" }
    }

    // --- atomic batch writes -----------------------------------------------------------------------
    // Setters normally each fire their own async prefs.edit().apply(). Inside [applyBatch] they instead
    // stage into one shared editor, committed once, so a multi-field write (e.g. the /config form apply)
    // is all-or-nothing and durable before we return — a power loss can't leave a half-applied config.
    @Volatile private var batchEditor: SharedPreferences.Editor? = null
    @Volatile private var batchThread: Thread? = null

    /** Run [block] with every [edit]-based setter write staged into one editor and committed atomically
     *  when it returns. Confined to the calling thread: a setter fired on another thread during the batch
     *  takes its normal immediate-apply path (SharedPreferences.Editor isn't thread-safe). Non-nesting;
     *  the /config apply that uses it is single-threaded per request. */
    @Synchronized
    fun applyBatch(block: () -> Unit): Boolean {
        val ed = prefs.edit()
        batchEditor = ed
        batchThread = Thread.currentThread()
        try {
            block()
        } finally {
            batchEditor = null
            batchThread = null
        }
        return ed.commit()
    }

    /** Write helper: stage into the active [applyBatch] editor (same thread) or, outside a batch, apply
     *  immediately exactly as before. Setters call this instead of prefs.edit()…apply() so they compose. */
    private inline fun edit(block: SharedPreferences.Editor.() -> Unit) {
        val b = batchEditor
        if (b != null && batchThread === Thread.currentThread()) {
            b.block()
        } else {
            val e = prefs.edit()
            e.block()
            e.apply()
        }
    }

    /** Synchronous variant for writes that must survive an immediate reboot. Still composes into a batch. */
    private inline fun editCommit(block: SharedPreferences.Editor.() -> Unit) {
        val b = batchEditor
        if (b != null && batchThread === Thread.currentThread()) {
            b.block()
        } else {
            val e = prefs.edit()
            e.block()
            e.commit()
        }
    }

    /** Persist a new panel id (used by the HTTP config page). */
    fun setPanelId(id: String) {
        edit { this@Config.stagePanelId(this, id) }
    }

    /** Stage [id] and its dependent HA-link invalidation into a caller-owned transaction. */
    fun stagePanelId(editor: SharedPreferences.Editor, id: String) {
        // The panel_id is the HA device identifier, so only an ACTUAL change invalidates the cached device
        // link. The config form resubmits panel_id on every save (unchanged), so clearing unconditionally
        // would drop the link on every save.
        val changed = id != prefs.getString("panel_id", null)
        editor.putString("panel_id", id)
        if (changed) editor.remove("ha_device_url")
    }

    /** Apply secondary-key semantics for a validated bundle import using the SAME transaction as its
     *  primary values. These mirror the config form: clearing an identity also clears its credential,
     *  and a replacement HA access token supersedes an old refresh session unless a new refresh token
     *  is supplied beside it. Call after staging the accepted values so these safety clears win. */
    internal fun stageImportDependencies(editor: SharedPreferences.Editor, accepted: Map<String, String>) {
        if (accepted["mqtt_user"]?.isEmpty() == true) editor.putString("mqtt_password", "")

        accepted["dashboard_package"]?.let { next ->
            if (next != dashboardPackage) editor.putBoolean("renderer_launch_pending", true)
        }

        val haUrl = accepted["ha_url"]
        if (haUrl != null) editor.putString("ha_url", haUrl.trimEnd('/'))
        if (haUrl?.isEmpty() == true) {
            editor.putString("ha_token", "")
            editor.putString("ha_refresh_token", "")
            editor.putString("ha_client_id", "")
            editor.putLong("ha_token_expiry", 0L)
        } else {
            val access = accepted["ha_token"]
            val explicitAccess = access != null && access.isNotEmpty()
            if (explicitAccess && "ha_refresh_token" !in accepted && haRefreshToken.isNotEmpty()) {
                editor.putString("ha_refresh_token", "")
                editor.putLong("ha_token_expiry", 0L)
            }
        }
    }

    /** Cached HA device-settings URL (resolved once via HaLink when the MQTT creds are a valid HA user);
     *  blank until/unless resolved. Shown as an "Open in Home Assistant" link on the info page. */
    val haDeviceUrl: String get() = prefs.getString("ha_device_url", "")!!
    /** When [haDeviceUrl] was last resolved — drives a periodic re-resolve so a link left stale by an HA
     *  device delete+recreate (the id changes without a panel_id change) self-heals. */
    val haLinkResolvedAt: Long get() = prefs.getLong("ha_link_at", 0L)
    fun setHaDeviceUrl(url: String) {
        prefs.edit().putString("ha_device_url", url).putLong("ha_link_at", System.currentTimeMillis()).apply()
    }

    /** HA's frontend URL as the Companion knows it (its internal/external_url), resolved from the Companion
     *  DB + cached. The header "Open in HA" button falls back to this when [haDeviceUrl] (the panel's own
     *  device page) hasn't resolved — e.g. a remote panel over a tunnel — so the button is always present. */
    val haBaseUrl: String get() = prefs.getString("ha_base_url", "")!!
    fun setHaBaseUrl(url: String) { prefs.edit().putString("ha_base_url", url).apply() }

    /** Best "Open in HA" target: the resolved device page if known, else the Companion's HA frontend URL. */
    val haLinkUrl: String get() = haDeviceUrl.ifBlank { haBaseUrl }

    /** Update versions the user dismissed from the dashboard banner, label -> ignored latestVersion.
     *  Stored as newline-joined "label\tversion" rows (component labels + semver never contain \t or \n).
     *  Only the dashboard banner honours this — the Install tab always lists every available update. The
     *  suppression is version-exact ([UpdateChecker.visible]), so a newer release re-surfaces the banner. */
    val ignoredUpdates: Map<String, String>
        get() = prefs.getString("update_ignored", "")!!.lineSequence()
            .mapNotNull { row -> row.indexOf('\t').let { i -> if (i <= 0) null else row.substring(0, i) to row.substring(i + 1) } }
            .toMap()

    /** Record that [label]'s update to [version] was dismissed (merges with any existing ignores). */
    fun ignoreUpdate(label: String, version: String) {
        val raw = ignoredUpdates.toMutableMap().apply { put(label, version) }
            .entries.joinToString("\n") { "${it.key}\t${it.value}" }
        edit { putString("update_ignored", raw) }
    }

    /** Whether the Install tab may install an arbitrary user-uploaded APK over root. Default ON (the turnkey
     *  LAN-trust posture — see the Install-tab security note), but flippable per-panel so a hardened
     *  deployment can disable this high-impact, unauthenticated capability until token auth lands. */
    val apkUploadAllowed: Boolean get() = prefs.getBoolean("allow_apk_upload", true)
    fun setApkUploadAllowed(on: Boolean) { edit { putBoolean("allow_apk_upload", on) } }

    /**
     * HA device display name (`device.name` in discovery). Defaults to the device's own name —
     * the same source the HA Companion app uses for its default device name. (ha-paneld can't read
     * the Companion app's private device_id across the Android sandbox, so it mirrors the heuristic
     * rather than the exact registration id.)
     */
    val friendlyName: String
        get() = prefs.getString("friendly_name", null)?.takeIf { it.isNotBlank() } ?: deviceName()
    fun setFriendlyName(name: String) {
        edit { putString("friendly_name", name) }
    }

    /** Stable per-device id (Settings.Secure.ANDROID_ID); used as the HA device serial_number. */
    val androidId: String
        get() = Settings.Secure.getString(requireNotNull(contentResolver), Settings.Secure.ANDROID_ID) ?: ""

    /** The device's configured name (Companion's default-name source), else the model. */
    private fun deviceName(): String =
        (Settings.Global.getString(requireNotNull(contentResolver), Settings.Global.DEVICE_NAME)
            ?: Build.MODEL).ifBlank { Build.MODEL }

    /** Persist MQTT settings (used by the HTTP config page). A null password leaves it unchanged. */
    fun setMqtt(broker: String, user: String, password: String?) {
        edit {
            putString("mqtt_broker", broker)
            putString("mqtt_user", user)
            if (password != null) putString("mqtt_password", password)
        }
    }

    /** App package whose force-stop+relaunch is the dashboard "reload". Empty => reload disabled. */
    val dashboardPackage: String get() = prefs.getString("dashboard_package", "")!!
    fun setDashboardPackage(pkg: String) {
        require(AndroidInput.isDashboardTarget(pkg)) { "invalid dashboard package" }
        val changed = pkg != dashboardPackage
        edit {
            putString("dashboard_package", pkg)
            if (changed) putBoolean("renderer_launch_pending", true)
        }
    }

    /** Durable handoff between a renderer config commit and its required launch side-effect. */
    val rendererLaunchPending: Boolean get() = prefs.getBoolean("renderer_launch_pending", false)

    /** Clear the handoff only after the configured renderer has been launched. */
    fun completeRendererLaunch(): Boolean = applyBatch {
        edit { putBoolean("renderer_launch_pending", false) }
    }

    /** Home Assistant base URL for the built-in dashboard renderer, e.g. "http://homeassistant.local:8123".
     *  Empty => the built-in renderer is unavailable (external renderers unaffected). */
    val haUrl: String get() = prefs.getString("ha_url", "")!!

    /** Access token the built-in renderer hands the HA frontend via the external-auth bridge. Either a
     *  long-lived access token (set once at provisioning) OR the current short-lived access token kept
     *  fresh from [haRefreshToken]. Never typed on the panel. */
    val haToken: String get() = prefs.getString("ha_token", "")!!

    /** OAuth refresh token (from a provisioning login). When set, [haToken] is a short-lived access
     *  token the app refreshes on demand — so no 10-year token lives on the panel. Blank => [haToken] is
     *  treated as a static long-lived token that never needs refreshing. */
    val haRefreshToken: String get() = prefs.getString("ha_refresh_token", "")!!

    /** Epoch-seconds expiry of the current [haToken] (refresh model only). 0 => unknown → refresh now. */
    val haTokenExpiry: Long get() = prefs.getLong("ha_token_expiry", 0L)

    /** Dark mode for panels WITHOUT a system dark/light setting (Android 9-; default true — wall panels
     *  live in dark rooms): themes ha-paneld's own native activities (DayNight) and sets the built-in
     *  renderer's dashboard default. Android 10+ panels follow the OS setting instead (the Display-card
     *  toggle is hidden there); the `:8888` web UI always follows the viewing browser's preference. */
    val darkMode: Boolean get() = prefs.getBoolean("dark_mode", true)
    fun setDarkMode(v: Boolean) = edit { putBoolean("dark_mode", v) }

    /** OAuth client_id to use when refreshing [haToken]. Blank => the HA origin (`<ha_url>/`, what the
     *  frontend uses). Set it to match the client the refresh token was issued for — e.g.
     *  `https://home-assistant.io/android` to reuse an HA Companion refresh token. */
    val haClientId: String get() = prefs.getString("ha_client_id", "")!!

    /** Persist the built-in renderer connection (HTTP config page / provisioning). A null token leaves
     *  it unchanged, mirroring [setMqtt]'s password semantics. */
    fun setHaConnection(url: String, token: String?) {
        edit {
            putString("ha_url", url.trim().trimEnd('/'))
            if (token != null) putString("ha_token", token)
        }
    }

    /** Persist a refreshed access token + its expiry (called by the on-demand refresh in the renderer). */
    fun setHaRefreshedToken(access: String, expiryEpochSec: Long) {
        edit { putString("ha_token", access); putLong("ha_token_expiry", expiryEpochSec) }
    }

    /** Set (or clear, with "") the OAuth refresh token — provisioning path. */
    fun setHaRefreshToken(refresh: String) { edit { putString("ha_refresh_token", refresh) } }

    /** Set the current access-token expiry (epoch seconds) — provisioning path. */
    fun setHaTokenExpiry(epochSec: Long) { edit { putLong("ha_token_expiry", epochSec) } }

    /** Set (or clear, with "") the OAuth client_id used for token refresh. */
    fun setHaClientId(clientId: String) { edit { putString("ha_client_id", clientId) } }

    /** Atomically persist every value borrowed from the Companion for a built-in renderer switch. */
    fun setBorrowedRendererSettings(
        url: String,
        accessToken: String,
        refreshToken: String,
        tokenExpiry: Long,
        clientId: String,
        zoom: Int?,
    ): Boolean = applyBatch {
        setHaConnection(url, accessToken)
        setHaRefreshToken(refreshToken)
        setHaTokenExpiry(tokenExpiry)
        setHaClientId(clientId)
        zoom?.let(::setDashboardZoom)
        edit { putBoolean("renderer_launch_pending", true) }
    }

    /** Launcher package the Launcher button brings forward. Empty => auto-pick a non-default home. */
    val launcherPackage: String get() = prefs.getString("launcher_package", "")!!
    fun setLauncherPackage(pkg: String) {
        edit { putString("launcher_package", pkg) }
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
        edit { putString("tame_vendor_packages", raw.trim()) }
    }

    /**
     * Extra hostnames allowed in the HTTP `Host` header — the anti-DNS-rebinding allowlist for the
     * `:8888` guard ([http.OriginGuard.hostAllowed]). The guard always allows IP literals, `localhost`,
     * and `*.local` (mDNS), which covers reaching a panel by IP or its mDNS name, so this is only needed
     * when a panel is fronted by a **custom DNS name** (e.g. `kitchen-panel.myhome.lan`). Set it via the
     * always-allowed IP path (`POST /config` to `http://<ip>:8888`) or provisioning — never a lockout.
     * Whitespace/comma-separated; matched case-insensitively; default empty.
     */
    val httpAllowedHosts: Set<String>
        get() = prefs.getString("http_allowed_hosts", "")!!
            .split(Regex("[\\s,]+")).map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    /** Raw user-set value — for the Configure form's input value. */
    val httpAllowedHostsRaw: String get() = prefs.getString("http_allowed_hosts", "")!!
    fun setHttpAllowedHosts(raw: String) {
        edit { putString("http_allowed_hosts", raw.trim()) }
    }

    // Wake the screen locally the instant proximity reads near (low latency, network-independent).
    // Default on where a proximity sensor exists; the HA switch can disable it (e.g. a hallway panel).
    val wakeOnWave: Boolean get() = prefs.getBoolean("wake_on_wave", true)
    fun setWakeOnWave(on: Boolean) {
        edit { putBoolean("wake_on_wave", on) }
    }

    // Prevent the vendor firmware idle-dimming the backlight at the screen-off timeout (it drops the
    // hardware backlight to ~1% after the timeout even while the OS keeps the screen on). On by default —
    // these are mains-powered wall panels; turn it off to restore the firmware's own dimming behaviour.
    val preventIdleDim: Boolean get() = prefs.getBoolean("prevent_idle_dim", true)
    fun setPreventIdleDim(on: Boolean) {
        edit { putBoolean("prevent_idle_dim", on) }
    }

    // Hold a partial wakelock so the SoC + network never suspend (screen still free to sleep). ON by
    // default: a mains wall panel must never Doze into an unreachable state where the MQTT reactor +
    // keepalive freeze and the broker connection dies half-open. Toggle off only for a battery panel.
    val keepAwake: Boolean get() = prefs.getBoolean("keep_awake", true)
    fun setKeepAwake(on: Boolean) {
        edit { putBoolean("keep_awake", on) }
    }

    // App watchdog: poll the dashboard app and self-heal it — relaunch if its process dies, and return
    // to it if it's been backgrounded too long. Opt-in (off by default): a stock panel never auto-acts.
    val watchdogEnabled: Boolean get() = prefs.getBoolean("watchdog_enabled", false)
    fun setWatchdogEnabled(on: Boolean) {
        edit { putBoolean("watchdog_enabled", on) }
    }

    // Experimental kiosk lock: suppress + disable the system nav (HOME/RECENT/shade) so a non-admin can't
    // accidentally leave the dashboard. Runtime-only + many escapes (see KioskController); off by default.
    val kioskLock: Boolean get() = prefs.getBoolean("kiosk_lock", false)
    fun setKioskLock(on: Boolean) {
        edit { putBoolean("kiosk_lock", on) }
    }

    // HA Companion app auto-manage: when on, ha-paneld installs the minimal Companion if it's
    // missing and updates it when a newer release exists (root panels; the minimal variant has no Play
    // auto-update, so ha-paneld is the only update path). Default off — installing/updating an app is
    // invasive, AND an upstream Companion release can be incompatible with a panel's old Android (e.g.
    // 2026.6.5-minimal crash-loops on Android 8.1/PX30 — a missing android.car class — blanking the
    // dashboard fleet-wide when auto-update was on). Opt in per panel (provision --companion-auto or the
    // MQTT switch); a per-profile known-good version pin is the planned safer gate.
    val companionAutoUpdate: Boolean get() = prefs.getBoolean("companion_auto_update", false)
    fun setCompanionAutoUpdate(on: Boolean) {
        edit { putBoolean("companion_auto_update", on) }
    }
    /** Release channel the Companion auto-updater follows — mirrors [updateChannel] for ha-paneld. */
    val companionUpdateChannel: String get() = prefs.getString("companion_update_channel", "stable") ?: "stable"
    fun setCompanionUpdateChannel(ch: String) {
        val v = if (ch.trim().lowercase().startsWith("pre")) "prerelease" else "stable"
        edit { putString("companion_update_channel", v) }
    }

    // ha-paneld self-update: when on, ha-paneld installs a newer build of ITSELF from GitHub releases on
    // the selected [updateChannel] (root; no Play Store on these panels). **Default OFF** — silent
    // auto-pull from the release repo is a supply-chain risk if control of the repo were ever lost, so it
    // is strictly opt-in (the pinned-signer check in AppInstaller mitigates a repo-only compromise, but
    // off-by-default is the safer stance the user chose, 2026-07-01). When a user DOES enable it, it never
    // auto-DOWNGRADES: running a pre-release while on the stable channel simply waits ("suspended") until
    // stable catches up; the one deliberate move off an rc is an explicit channel switch pre-release→stable.
    val selfUpdate: Boolean get() = prefs.getBoolean("self_update", false)
    fun setSelfUpdate(on: Boolean) {
        edit { putBoolean("self_update", on) }
    }
    val updateChannel: String get() = prefs.getString("update_channel", "stable") ?: "stable"
    fun setUpdateChannel(ch: String) {
        val v = if (ch.trim().lowercase().startsWith("pre")) "prerelease" else "stable"
        edit { putString("update_channel", v) }
    }

    // System WebView auto-update: when on, ha-paneld advances the WebView to the profile's pinned
    // recommended build (from the webview-mirror release) on the update tick — the same curated pin the
    // too-old auto-heal installs, but proactively rather than only when the engine is broken. **Default
    // OFF**: a provider swap binds per-process (needs a restart) and is more invasive than an app update,
    // and the pin is advanced deliberately by the maintainer (there is no clean upstream feed to chase).
    val webViewAutoUpdate: Boolean get() = prefs.getBoolean("webview_auto_update", false)
    fun setWebViewAutoUpdate(on: Boolean) {
        // commit() (not apply()): the natural workflow is "enable, then reboot to let it run", and an
        // async write can be lost if the reboot lands before it flushes to disk.
        editCommit { putBoolean("webview_auto_update", on) }
    }
    // Loop guard: the exact recommended version last auto-installed. If a later tick still doesn't see it
    // as the engine, the provider isn't switching (variant hardware) — don't re-download it daily; a pin
    // bump (new version string) clears the guard. commit() (not apply): must persist across the restart
    // the successful install triggers.
    val webViewAutoLastVersion: String get() = prefs.getString("webview_auto_last_version", "") ?: ""
    fun setWebViewAutoLastVersion(v: String) {
        prefs.edit().putString("webview_auto_last_version", v).commit()
    }

    // Network-adb persist INTENT (the switch). ha-paneld re-asserts adb-tcp at boot/reconnect when this
    // is true (some firmwares strip persist.adb.tcp.port at boot), and only tears adb down on OFF if it
    // was ha-paneld that turned it on — never disabling adb another mechanism started.
    val networkAdbEnabled: Boolean get() = prefs.getBoolean("network_adb_enabled", false)
    fun setNetworkAdbEnabled(on: Boolean) {
        edit { putBoolean("network_adb_enabled", on) }
    }

    // The ha-paneld version whose discovery set was last published to HA. On an upgrade (this differs
    // from the running version) MqttBridge prunes any entity a prior version published but this one no
    // longer does — so a refactored-away entity is actively removed from HA, not left as a zombie.
    val lastDiscoveryVersion: String get() = prefs.getString("last_discovery_version", "")!!
    fun setLastDiscoveryVersion(v: String) {
        prefs.edit().putString("last_discovery_version", v).apply()
    }

    // Per-panel intended "home" dashboard path (e.g. "/lovelace/0"). When set, a reload keeps the hard
    // restart but re-navigates HERE once the frontend is back up, instead of leaving the Companion on its
    // user-default view. Empty = keep current behaviour (cold-start to the Companion default).
    val homeDashboard: String get() = prefs.getString("home_dashboard", "")!!
    fun setHomeDashboard(p: String) {
        edit { putString("home_dashboard", p.trim()) }
    }

    /** Built-in renderer: minutes of no touch before it navigates back to [homeDashboard] (0 = off). */
    val dashboardIdleReturnMin: Int get() = prefs.getInt("dashboard_idle_return_min", 0)

    /** Built-in renderer: hide the system status + navigation bars (immersive edge-to-edge kiosk).
     *  Swipe-from-edge still transiently reveals them, so an admin is never locked out. */
    val dashboardFullscreen: Boolean get() = prefs.getBoolean("dashboard_fullscreen", true)
    fun setDashboardFullscreen(on: Boolean) { edit { putBoolean("dashboard_fullscreen", on) } }
    fun setDashboardIdleReturnMin(min: Int) { edit { putInt("dashboard_idle_return_min", min) } }

    /** Built-in renderer: allow Android's overscroll stretch/glow past the top or bottom of the page.
     *  Off by default (a wall panel rarely scrolls; the bounce looks out of place). API-only setting. */
    val dashboardOverscroll: Boolean get() = prefs.getBoolean("dashboard_overscroll", false)
    fun setDashboardOverscroll(on: Boolean) { edit { putBoolean("dashboard_overscroll", on) } }

    /** Built-in renderer: dashboard page zoom %. 100 matches the HA Companion's default sizing (which
     *  scales the page by device density), so a switched-over panel keeps its layout. */
    val dashboardZoom: Int get() = prefs.getInt("dashboard_zoom", 100)
    fun setDashboardZoom(pct: Int) { edit { putInt("dashboard_zoom", pct) } }

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
        edit { putString("navbar_mode", mode) }
    }

    // After an app update the launcher shows the App UI; when configured + MQTT-connected, bounce back
    // to the dashboard so it doesn't linger. Default on.
    val autoReturnDashboard: Boolean get() = prefs.getBoolean("auto_return_dashboard", true)
    fun setAutoReturnDashboard(on: Boolean) {
        edit { putBoolean("auto_return_dashboard", on) }
    }

    // Silence the firmware startup chime by zeroing the ring/notification volume via Settings.System.
    // Default off — existing panels already have their own volume state; only opt in deliberately.
    val silenceBootChime: Boolean get() = prefs.getBoolean("silence_boot_chime", false)
    fun setSilenceBootChime(on: Boolean) { edit { putBoolean("silence_boot_chime", on) } }

    /** Last measured DashboardActivity-start → Android default-network wait. Used only to make the
     * next boot's launch progress honest for this particular panel; zero means no learned estimate. */
    val lastNetworkWaitMs: Long get() = prefs.getLong("last_network_wait_ms", 0L)
    fun setLastNetworkWaitMs(ms: Long) {
        prefs.edit().putLong("last_network_wait_ms", ms.coerceIn(1_000L, 300_000L)).apply()
    }

    /** Last light/dark choice observed from HA's own `localStorage.selectedTheme`. Null until the
     * renderer has seen an explicit HA preference; the launch screen then follows the OS/config. */
    val dashboardThemeDark: Boolean?
        get() = if (prefs.contains("dashboard_theme_dark")) prefs.getBoolean("dashboard_theme_dark", true) else null
    fun setDashboardThemeDark(dark: Boolean) {
        prefs.edit().putBoolean("dashboard_theme_dark", dark).apply()
    }

    // --- remote log shipping (opt-in) --------------------------------------------------------------
    // Forward ha-paneld's OWN process logcat (its Log.* output + the Ktor/HiveMQ SLF4J library logs,
    // all emitted by this app's uid → readable with no READ_LOGS and no root) to a central aggregator
    // (any syslog- or HTTP-ingesting log collector) for fleet-wide debugging without per-panel `adb logcat`. OFF by default
    // with an EMPTY host — a stock panel ships nothing until deliberately configured. LAN-only by intent;
    // lines are redacted (tokens/passwords/URL secrets) before they leave the device. No on-panel UI:
    // set via the HTTP /config endpoint (provision.sh --log-* flags). See logship/LogShipper.
    val logShipEnabled: Boolean get() = prefs.getBoolean("log_ship_enabled", false)
    /** Sink host (the log collector to ship to). Empty => shipping stays inert regardless of the flag. */
    val logShipHost: String get() = prefs.getString("log_ship_host", "")!!
    val logShipPort: Int get() = prefs.getInt("log_ship_port", 514)
    /** Transport: "syslog" (TCP, RFC5424) or "http" (NDJSON POST). */
    val logShipProtocol: String get() = prefs.getString("log_ship_protocol", "syslog")!!
    /** True only when shipping is enabled AND a sink host is configured. */
    val logShipActive: Boolean get() = logShipEnabled && logShipHost.isNotBlank()
    fun setLogShipping(enabled: Boolean, host: String, port: Int, protocol: String) {
        edit {
            putBoolean("log_ship_enabled", enabled)
            putString("log_ship_host", host.trim())
            putInt("log_ship_port", port)
            putString("log_ship_protocol", protocol.trim().lowercase(Locale.ROOT).ifBlank { "syslog" })
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
        edit { putBoolean("zigbee_router_enabled", on); putBoolean("zigbee_router_configured", true) }
    }

    // Optional on-panel auto-brightness engine (see control/AutoBrightnessController). Default OFF →
    // ha-paneld stays a pure brightness actuator; HA drives the screen. When ON, the engine maps a lux
    // stream (panel ALS where present, else HA-fed) to the backlight.
    val autoBrightness: Boolean get() = prefs.getBoolean("auto_brightness", false)
    fun setAutoBrightness(on: Boolean) {
        edit { putBoolean("auto_brightness", on) }
    }

    /** Dimmer(−) ↔ Brighter(+) bias added to the auto-brightness curve, in 0–255 brightness units. */
    val brightnessBias: Int get() = prefs.getInt("brightness_bias", 0)
    fun setBrightnessBias(v: Int) {
        edit { putInt("brightness_bias", v.coerceIn(-100, 100)) }
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
        edit { putString("manufacturer", manufacturer); putString("model", model) }
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

    /** Effective CHT8305 room-temperature calibration offset (°C): the profile's characterised self-heat
     *  baseline PLUS the user's `room_temp_offset` trim (both default 0 → no correction). Additive so either
     *  the profile or the API can move it independently, and a future schema migration writing the config
     *  default (0) can't wipe the profile baseline. */
    val roomTempOffsetC: Float
        get() = (profile?.roomTempOffsetC ?: 0f) + prefs.getFloat("room_temp_offset", 0f)

    /** Persist the user's `room_temp_offset` trim (°C), clamped to the registry range. Uses [edit] so it
     *  composes with the /config POST batch — without this the key had a [SettingSpec] and rendered a form
     *  field but no persistence path, so a posted value was silently dropped. */
    fun setRoomTempOffset(raw: String) {
        val v = raw.trim().toFloatOrNull() ?: return   // non-numeric → keep current
        val spec = SettingsRegistry.spec("room_temp_offset")
        val lo = spec?.min?.toFloat() ?: -20f
        val hi = spec?.max?.toFloat() ?: 20f
        edit { putFloat("room_temp_offset", v.coerceIn(lo, hi)) }
    }

    // --- last-known actuator state, re-applied/published on (re)connect so HA reflects reality ---

    /** Last navigated URL (published as the navigate state on connect; empty if never set). */
    var lastNavigate: String
        get() = prefs.getString("last_navigate", "")!!
        set(v) { prefs.edit().putString("last_navigate", v).apply() }

    /** Last LED state packed as "on,br,r,g,b" (e.g. "1,255,255,0,0"); empty if never set. */
    var lastLed: String
        get() = prefs.getString("last_led", "")!!
        set(v) { prefs.edit().putString("last_led", v).apply() }

    /** Last active LED effect name (HA `effect_list` member, e.g. "strobe"); empty = solid colour. Kept
     *  separate from [lastLed] (which stays the base colour) so the effect resumes on reconnect/reboot. */
    var lastLedEffect: String
        get() = prefs.getString("last_led_effect", "")!!
        set(v) { prefs.edit().putString("last_led_effect", v).apply() }

    /** Last requested button-backlight level (0=off, 1..255=on, -1=never commanded/readable). */
    var lastButtonBacklight: Int
        get() = prefs.getInt("last_button_backlight", -1)
        set(v) { prefs.edit().putInt("last_button_backlight", v.coerceIn(-1, 255)).apply() }

    // --- arrangeable dashboard layout (per-panel; serialized by the web UI, opaque to the backend) ---
    /** Gridstack layout JSON for the Dashboard tab, persisted per-panel (empty = default layout). */
    var uiDashboardLayout: String
        get() = prefs.getString("ui_dashboard_layout", "")!!
        set(v) { prefs.edit().putString("ui_dashboard_layout", v).apply() }

    // --- registry-driven generic access (HTTP config API + bundles + revisions) -----------------
    // These read/write a setting by its [SettingSpec] so the config API, form generation, export and
    // import all go through one typed path instead of ~35 bespoke getters. Typed convenience getters
    // above remain for callers; they read the same SharedPreferences keys.

    /** Current raw string value for a registry key (the spec default if unset). */
    fun getRaw(spec: SettingSpec): String = when (spec.type) {
        SettingType.BOOL -> prefs.getBoolean(spec.key, spec.default.toBoolean()).toString()
        SettingType.INT -> prefs.getInt(spec.key, spec.default.toIntOrNull() ?: 0).toString()
        SettingType.FLOAT -> prefs.getFloat(spec.key, spec.default.toFloatOrNull() ?: 0f).toString()
        else -> prefs.getString(spec.key, spec.default) ?: spec.default
    }

    /** Stage a typed write into [editor] (no commit) — used by the transactional bundle import. */
    fun stage(editor: SharedPreferences.Editor, spec: SettingSpec, normalized: String) {
        when (spec.type) {
            SettingType.BOOL -> editor.putBoolean(spec.key, normalized.toBoolean())
            SettingType.INT -> editor.putInt(spec.key, normalized.toIntOrNull() ?: 0)
            SettingType.FLOAT -> editor.putFloat(spec.key, normalized.toFloatOrNull() ?: 0f)
            else -> editor.putString(spec.key, normalized)
        }
    }

    /** A new editor for staging a batch of registry writes (commit with [SharedPreferences.Editor.commit]). */
    fun editor(): SharedPreferences.Editor = prefs.edit()

    /** Schema the live store was last written at. Absent → 1 (the original shape, before this tracking),
     *  so an app upgrade that bumps [SettingsRegistry.SCHEMA] triggers a one-time on-device migration. */
    private val storedSchema: Int get() = prefs.getInt("config_schema", 1)

    /**
     * Migrate the live SharedPreferences store to the current [SettingsRegistry.SCHEMA] when it was
     * written by an older shape, using the same [Migrations] chain as bundle import. A fleet self-updates
     * unattended, so the first time a key is renamed or retyped the persisted value must be carried
     * forward, not silently reset to its default. No-op (and cheap) while already current; call once at
     * startup before the store is read. Committed synchronously so a reboot can't race the write.
     */
    fun migrateLiveStore() {
        val from = storedSchema
        if (from == SettingsRegistry.SCHEMA) return
        val specs = SettingsRegistry.SPECS.filterNot { it.readOnly || it.transient }
        val current = specs.associate { it.key to getRaw(it) }
        val (migrated, warnings) = Migrations.migrate(from, current)
        warnings.forEach { Log.w(TAG, "live-store migration: $it") }
        val ed = prefs.edit()
        for (spec in specs) {
            val next = migrated[spec.key] ?: continue
            if (next != current[spec.key]) stage(ed, spec, next)
        }
        ed.putInt("config_schema", SettingsRegistry.SCHEMA)
        ed.commit()
        Log.i(TAG, "migrated live config store: schema $from -> ${SettingsRegistry.SCHEMA}")
    }

    /** Whether an HA-capable setting is currently exposed to Home Assistant (per-panel override). */
    fun haExposed(key: String, default: Boolean = true): Boolean = prefs.getBoolean("ha_expose_$key", default)
    fun setHaExposed(key: String, on: Boolean) { edit { putBoolean("ha_expose_$key", on) } }

    private fun slug(s: String): String =
        s.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifEmpty { "ha_paneld_panel" }

    companion object {
        private const val TAG = "ha-paneld/config"
        const val DEFAULT_PORT = 8888
        const val VERSION = BuildConfig.VERSION_NAME
        const val MDNS_SERVICE_TYPE = "_ha-paneld._tcp.local."
    }
}
