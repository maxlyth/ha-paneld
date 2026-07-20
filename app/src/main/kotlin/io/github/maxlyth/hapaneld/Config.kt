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
import io.github.maxlyth.hapaneld.config.SettingValue
import io.github.maxlyth.hapaneld.config.SettingsRegistry
import io.github.maxlyth.hapaneld.config.Validation
import io.github.maxlyth.hapaneld.device.DeviceProfile
import io.github.maxlyth.hapaneld.persistence.AppState
import io.github.maxlyth.hapaneld.util.AndroidInput
import io.github.maxlyth.hapaneld.util.HaLink
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

internal data class MqttCredentialsSnapshot(
    val broker: String,
    val user: String,
    val password: String,
)

internal data class HaAuthSnapshot(
    val url: String,
    val accessToken: String,
    val refreshToken: String,
    val tokenExpiry: Long,
    val clientId: String,
)

/** Credential ownership that remains stable while a refresh token rotates its current access token. */
internal data class HaAuthOwner(
    val url: String,
    val refreshToken: String,
    val clientId: String,
    val staticAccessToken: String,
)

/** Process-local authority for one administrator-browser Home Assistant login. The epoch makes a
 * newer Connect request supersede an older callback even when that callback already consumed state. */
internal data class HaOAuthAttemptAuthority(
    val owner: HaAuthOwner,
    val epoch: Long,
)

internal fun HaAuthSnapshot.stableOwner(): HaAuthOwner = HaAuthOwner(
    url = url.trim().trimEnd('/'),
    refreshToken = refreshToken,
    clientId = clientId,
    staticAccessToken = accessToken.takeIf { refreshToken.isBlank() }.orEmpty(),
)

internal data class DashboardEntityBackupState(
    val instanceKey: String,
    val instanceOrigin: String,
    val instanceUuid: String,
    val dashboardPath: String,
    val filterIds: String,
    val filterEnabled: Boolean,
    val filterOwner: String,
    val learningApplied: Boolean,
    val appliedOwner: String,
    val overrides: String,
    val overrideOwner: String,
)

/** One-time migration input for the adaptive proximity learner. */
internal data class ProximityLearningSeed(
    val nearRaw: Float,
    val farRaw: Float,
)

/**
 * Runtime configuration. Production state is transactional SQLite; the SharedPreferences-shaped
 * interface remains as a test and typed-caller compatibility boundary. The MQTT broker defaults to
 * empty, which disables MQTT — the
 * /play HTTP contract works standalone without a broker, so first-run never blocks on MQTT.
 */
class Config private constructor(
    private val prefs: SharedPreferences,
    private val contentResolver: ContentResolver?,
    private val calibrationPrefs: SharedPreferences,
) {
    enum class SecurityMode { RELAXED, HARDENED }

    private val wakeOnWaveEpoch = AtomicLong()
    private val haOAuthAttemptEpoch = AtomicLong()

    constructor(context: Context) : this(
        AppState.preferences(context, "config", "ha-paneld"),
        context.applicationContext.contentResolver,
        AppState.preferences(context, "profile-calibration", PROFILE_CALIBRATION_PREFS),
    )

    /** JVM-test seam; identity defaults that consult Android settings require the production constructor. */
    internal constructor(prefs: SharedPreferences) : this(prefs, null, prefs)

    /** JVM-test seam that keeps configuration and non-transferable sensor calibration separate. */
    internal constructor(prefs: SharedPreferences, calibrationPrefs: SharedPreferences) :
        this(prefs, null, calibrationPrefs)

    val httpPort: Int get() = prefs.getInt("http_port", DEFAULT_PORT)

    /**
     * Device-local control-plane posture. This key deliberately lives outside SettingsRegistry, so a
     * remote config import, backup restore, HTTP request, or MQTT command cannot enable or disable it.
     * The non-exported native settings activity is the only UI that changes it.
     */
    val securityMode: SecurityMode
        get() = if (prefs.getBoolean(SECURITY_MODE_PREF, false)) SecurityMode.HARDENED else SecurityMode.RELAXED

    /** Hardened mode and ha-paneld-owned network ADB are mutually exclusive. The check and mode
     * commit share [CONFIG_LOCK] with network-ADB ownership so concurrent local/network requests
     * cannot cross and leave both durable authorities enabled. */
    fun setSecurityMode(mode: SecurityMode): Boolean = synchronized(CONFIG_LOCK) {
        if (mode == SecurityMode.HARDENED && prefs.getBoolean("network_adb_enabled", false)) return@synchronized false
        prefs.edit().putBoolean(SECURITY_MODE_PREF, mode == SecurityMode.HARDENED).commit()
    }

    val hardenedSecurityEnabled: Boolean get() = securityMode == SecurityMode.HARDENED

    /** Empty => MQTT disabled. e.g. "tcp://192.168.1.10:1883". */
    val mqttBroker: String get() = prefs.getString("mqtt_broker", "")!!
    val mqttUser: String get() = prefs.getString("mqtt_user", "")!!
    val mqttPassword: String get() = prefs.getString("mqtt_password", "")!!

    /** One immutable durable-state generation for connection setup; consumers must not combine
     * three independent getters across a concurrent credential commit. */
    internal fun mqttCredentialsSnapshot(): MqttCredentialsSnapshot = synchronized(CONFIG_LOCK) {
        val all = prefs.all
        MqttCredentialsSnapshot(
            broker = all["mqtt_broker"] as? String ?: "",
            user = all["mqtt_user"] as? String ?: "",
            password = all["mqtt_password"] as? String ?: "",
        )
    }

    /** Device-local MQTT route memory. One broker tuple survives a controlled process boundary but is
     * excluded from config APIs/bundles because it is observed transport state, not user configuration. */
    internal fun mqttFamilyPreference(brokerIdentity: String): Boolean? = synchronized(CONFIG_LOCK) {
        if (prefs.getString(MQTT_FAMILY_BROKER_PREF, null) == brokerIdentity) {
            prefs.getBoolean(MQTT_FAMILY_IPV4_PREF, false)
        } else {
            null
        }
    }

    internal fun rememberMqttFamilyPreference(brokerIdentity: String, preferIpv4: Boolean): Boolean =
        synchronized(CONFIG_LOCK) {
            prefs.edit()
                .putString(MQTT_FAMILY_BROKER_PREF, brokerIdentity)
                .putBoolean(MQTT_FAMILY_IPV4_PREF, preferIpv4)
                .commit()
        }

    internal fun forgetMqttFamilyPreference(): Boolean = synchronized(CONFIG_LOCK) {
        if (!prefs.contains(MQTT_FAMILY_BROKER_PREF) && !prefs.contains(MQTT_FAMILY_IPV4_PREF)) {
            return@synchronized true
        }
        prefs.edit()
            .remove(MQTT_FAMILY_BROKER_PREF)
            .remove(MQTT_FAMILY_IPV4_PREF)
            .commit()
    }

    /** True when this exact MQTT configuration still owns its one announcement-wedge process boundary. */
    internal fun mqttAnnouncementBoundaryAvailable(identity: String): Boolean = synchronized(CONFIG_LOCK) {
        val consumed = prefs.getString(MQTT_ANNOUNCEMENT_BOUNDARY_PREF, null) ?: return@synchronized true
        if (consumed == identity) return@synchronized false
        // A relevant broker/profile/credential identity change starts a new bounded recovery epoch.
        prefs.edit().remove(MQTT_ANNOUNCEMENT_BOUNDARY_PREF).commit()
    }

    internal fun consumeMqttAnnouncementBoundary(identity: String): Boolean = synchronized(CONFIG_LOCK) {
        if (prefs.getString(MQTT_ANNOUNCEMENT_BOUNDARY_PREF, null) == identity) return@synchronized false
        prefs.edit().putString(MQTT_ANNOUNCEMENT_BOUNDARY_PREF, identity).commit()
    }

    internal fun clearMqttAnnouncementBoundary(identity: String): Boolean = synchronized(CONFIG_LOCK) {
        if (prefs.getString(MQTT_ANNOUNCEMENT_BOUNDARY_PREF, null) != identity) return@synchronized true
        prefs.edit().remove(MQTT_ANNOUNCEMENT_BOUNDARY_PREF).commit()
    }

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
    fun applyBatch(afterCommit: () -> Unit = {}, block: () -> Unit): Boolean = synchronized(CONFIG_LOCK) {
        val ed = prefs.edit()
        batchEditor = ed
        batchThread = Thread.currentThread()
        try {
            block()
        } finally {
            batchEditor = null
            batchThread = null
        }
        ed.commit().also { committed -> if (committed) afterCommit() }
    }

    /** Serialize a compound config operation across every Config wrapper in this process. */
    internal fun <T> synchronizedTransaction(block: () -> T): T = synchronized(CONFIG_LOCK, block)

    /** Write helper: stage into the active [applyBatch] editor (same thread) or, outside a batch, apply
     *  immediately exactly as before. Setters call this instead of prefs.edit()…apply() so they compose. */
    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        synchronized(CONFIG_LOCK) {
            val b = batchEditor
            if (b != null && batchThread === Thread.currentThread()) {
                b.block()
            } else {
                val e = prefs.edit()
                e.block()
                e.apply()
            }
        }
    }

    /** Synchronous variant for writes that must survive an immediate reboot. Still composes into a batch. */
    private fun editCommit(block: SharedPreferences.Editor.() -> Unit) {
        synchronized(CONFIG_LOCK) {
            val b = batchEditor
            if (b != null && batchThread === Thread.currentThread()) {
                b.block()
            } else {
                val e = prefs.edit()
                e.block()
                e.commit()
            }
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
        if (changed) clearHaDeviceLink(editor)
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
        accepted["home_dashboard"]?.let { next ->
            val nextPath = normalizeDashboardEntityPath(next)
            if (nextPath != normalizeDashboardEntityPath(homeDashboard)) {
                editor.putString("dashboard_entity_dashboard_path", nextPath)
            }
        }

        val haUrl = accepted["ha_url"]
        if (haUrl != null) {
            val normalized = haUrl.trimEnd('/')
            editor.putString("ha_url", normalized)
            stageDashboardEntityHaUrlChange(editor, normalized)
        }
        if (haUrl?.isEmpty() == true) {
            editor.putString("ha_token", "")
            editor.putString("ha_refresh_token", "")
            editor.putString("ha_client_id", "")
            editor.putLong("ha_token_expiry", 0L)
        } else {
            val access = accepted["ha_token"]
            val explicitAccess = access != null && access.isNotEmpty()
            if ("ha_token_expiry" !in accepted &&
                ("ha_token" in accepted || "ha_refresh_token" in accepted)
            ) {
                // Compatibility with backups/config bundles written before expiry joined the registry:
                // never retain an unrelated target panel's expiry beside restored credential material.
                // Unknown expiry forces DashboardAuth to refresh before trusting the restored access token.
                editor.putLong("ha_token_expiry", 0L)
            }
            if (explicitAccess && "ha_refresh_token" !in accepted && haRefreshToken.isNotEmpty()) {
                editor.putString("ha_refresh_token", "")
                editor.putLong("ha_token_expiry", 0L)
            }
        }
    }

    /** Cached HA device-settings URL. [haDeviceLinkTarget] records which configured HA endpoint and
     *  panel identity produced the random device-registry id so a renderer/server change cannot leave
     *  "Open in HA" pointing at a device which does not exist on the new server. */
    val haDeviceUrl: String get() = prefs.getString("ha_device_url", "")!!
    val haDeviceLinkTarget: String get() = prefs.getString("ha_link_target", "")!!
    /** When [haDeviceUrl] was last resolved — drives a periodic re-resolve so a link left stale by an HA
     *  device delete+recreate (the id changes without a panel_id change) self-heals. */
    val haLinkResolvedAt: Long get() = prefs.getLong("ha_link_at", 0L)
    fun setHaDeviceUrl(url: String, target: String) {
        prefs.edit()
            .putString("ha_device_url", url)
            .putString("ha_link_target", target)
            .putLong("ha_link_at", System.currentTimeMillis())
            .apply()
    }

    /** A pre-ownership (legacy) cache is deliberately stale so the first upgraded start repairs it. */
    fun haDeviceLinkIsFresh(target: String, nowMs: Long, ttlMs: Long): Boolean =
        haDeviceUrl.isNotBlank() && haDeviceLinkTarget == target && nowMs - haLinkResolvedAt in 0 until ttlMs

    /** HA's frontend URL as the Companion knows it (its internal/external_url), resolved from the Companion
     *  DB + cached. The header "Open in HA" button falls back to this when [haDeviceUrl] (the panel's own
     *  device page) hasn't resolved — e.g. a remote panel over a tunnel — so the button is always present. */
    val haBaseUrl: String get() = prefs.getString("ha_base_url", "")!!
    fun setHaBaseUrl(url: String) { prefs.edit().putString("ha_base_url", url).apply() }

    /** Best "Open in HA" target. Never expose an unowned legacy cache or a device id resolved for a
     * different native endpoint/panel while its asynchronous repair is still running. */
    val haLinkUrl: String get() {
        val nativeTarget = haUrl.takeIf(String::isNotBlank)?.let { HaLink.resolutionTarget(it, panelId) }
        val ownedDeviceUrl = haDeviceUrl.takeIf {
            haDeviceLinkTarget.isNotBlank() && (nativeTarget == null || haDeviceLinkTarget == nativeTarget)
        }
        return ownedDeviceUrl ?: haUrl.ifBlank { haBaseUrl }
    }

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

    /** One immutable durable-state generation for renderer authentication and refresh. */
    internal fun haAuthSnapshot(): HaAuthSnapshot = synchronized(CONFIG_LOCK) {
        val all = prefs.all
        HaAuthSnapshot(
            url = all["ha_url"] as? String ?: "",
            accessToken = all["ha_token"] as? String ?: "",
            refreshToken = all["ha_refresh_token"] as? String ?: "",
            tokenExpiry = (all["ha_token_expiry"] as? Number)?.toLong() ?: 0L,
            clientId = all["ha_client_id"] as? String ?: "",
        )
    }

    internal fun beginHaOAuthAttempt(): HaOAuthAttemptAuthority = synchronized(CONFIG_LOCK) {
        HaOAuthAttemptAuthority(
            owner = haAuthSnapshot().stableOwner(),
            epoch = haOAuthAttemptEpoch.incrementAndGet(),
        )
    }

    internal fun isHaOAuthAttemptCurrent(epoch: Long): Boolean = synchronized(CONFIG_LOCK) {
        haOAuthAttemptEpoch.get() == epoch
    }

    /** Persist the built-in renderer connection (HTTP config page / provisioning). A null token leaves
     *  it unchanged, mirroring [setMqtt]'s password semantics. */
    fun setHaConnection(url: String, token: String?) {
        edit {
            val normalized = url.trim().trimEnd('/')
            putString("ha_url", normalized)
            stageDashboardEntityHaUrlChange(this, normalized)
            if (token != null) putString("ha_token", token)
        }
    }

    /** Persist a refreshed access token + its expiry (called by the on-demand refresh in the renderer). */
    fun setHaRefreshedToken(access: String, expiryEpochSec: Long) {
        edit { putString("ha_token", access); putLong("ha_token_expiry", expiryEpochSec) }
    }

    /** Persist a refresh result only while the complete auth generation used to obtain it is still
     * current. This prevents a late OAuth response from overwriting credentials replaced by an admin. */
    internal fun setHaRefreshedTokenIfOwned(
        expected: HaAuthSnapshot,
        access: String,
        expiryEpochSec: Long,
    ): Boolean = synchronized(CONFIG_LOCK) {
        if (haAuthSnapshot() != expected) return false
        prefs.edit()
            .putString("ha_token", access)
            .putLong("ha_token_expiry", expiryEpochSec)
            .commit()
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

    // Wake locally after the learner confirms a deliberate far-to-near-to-far gesture.
    // Default on where a proximity sensor exists; the HA switch can disable it (e.g. a hallway panel).
    val wakeOnWave: Boolean get() = prefs.getBoolean("wake_on_wave", true)
    val wakeOnWaveGeneration: Long get() = wakeOnWaveEpoch.get()
    fun setWakeOnWave(on: Boolean) {
        edit { putBoolean("wake_on_wave", on) }
        wakeOnWaveEpoch.incrementAndGet()
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
    fun commitKioskLock(on: Boolean): Boolean = synchronized(CONFIG_LOCK) {
        prefs.edit().putBoolean("kiosk_lock", on).commit()
    }

    /** Device-local acknowledgement of the built-in launch screen for one exact app version. This is
     * outside SettingsRegistry, so config export/import cannot suppress an intro on another panel. */
    val lastLaunchScreenVersionCode: Long?
        get() = if (prefs.contains(LAST_LAUNCH_SCREEN_VERSION_PREF)) {
            prefs.getLong(LAST_LAUNCH_SCREEN_VERSION_PREF, Long.MIN_VALUE)
        } else {
            null
        }

    /** Commit only after MainActivity has actually selected and installed the intro view. */
    fun commitLaunchScreenVersionShown(versionCode: Long): Boolean = synchronized(CONFIG_LOCK) {
        prefs.edit().putLong(LAST_LAUNCH_SCREEN_VERSION_PREF, versionCode).commit()
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
    fun setNetworkAdbEnabled(on: Boolean): Boolean = synchronized(CONFIG_LOCK) {
        if (on && prefs.getBoolean(SECURITY_MODE_PREF, false)) return@synchronized false
        prefs.edit().putBoolean("network_adb_enabled", on).commit()
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
        edit {
            val normalized = p.trim()
            stageHomeDashboard(this, normalized, homeDashboard)
        }
    }

    private fun stageHomeDashboard(editor: SharedPreferences.Editor, normalized: String, previous: String) {
        editor.putString("home_dashboard", normalized)
        val nextPath = normalizeDashboardEntityPath(normalized)
        if (nextPath != normalizeDashboardEntityPath(previous)) {
            editor.putString("dashboard_entity_dashboard_path", nextPath)
        }
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

    /**
     * Experimental built-in-renderer entity filter. The exact entity ids deliberately live outside the
     * settings registry: they are a potentially large, installation-specific API input rather than a
     * settled product setting or an HA entity. The dedicated `/api/v1/dashboard/entity-filter` endpoint
     * is the only management surface and reports count/hash rather than echoing the ids.
     */
    val dashboardEntityFilterEnabled: Boolean
        get() = !dashboardEntityDefaultResolverMigrationRequired &&
            !dashboardEntityDefaultResolverRebootstrapPending &&
            entityStateOwnedByCurrent("dashboard_entity_filter_instance") &&
            prefs.getBoolean("dashboard_entity_filter_enabled", false)
    val dashboardEntityFilterIds: List<String>
        get() = if (entityStateOwnedByCurrent("dashboard_entity_filter_instance")) rawDashboardEntityFilterIds else emptyList()
    internal val rawDashboardEntityFilterIds: List<String>
        get() = prefs.getString("dashboard_entity_filter_ids", "").orEmpty()
            .lineSequence().map(String::trim).filter(String::isNotEmpty).distinct().sorted().toList()

    /** Exact owner-scoped filter state for full backup and local config revisions. Derived SQLite
     * evidence remains rebuildable and is intentionally excluded. */
    internal fun dashboardEntityBackupState(): DashboardEntityBackupState = synchronized(CONFIG_LOCK) {
        val all = prefs.all
        fun string(key: String) = all[key] as? String ?: ""
        DashboardEntityBackupState(
            instanceKey = string("dashboard_entity_instance"),
            instanceOrigin = string("dashboard_entity_instance_origin"),
            instanceUuid = string("dashboard_entity_instance_uuid"),
            dashboardPath = string("dashboard_entity_dashboard_path"),
            filterIds = string("dashboard_entity_filter_ids"),
            filterEnabled = all["dashboard_entity_filter_enabled"] as? Boolean ?: false,
            filterOwner = string("dashboard_entity_filter_instance"),
            learningApplied = all["dashboard_entity_learning_applied"] as? Boolean ?: false,
            appliedOwner = string("dashboard_entity_applied_instance"),
            overrides = string("dashboard_entity_overrides"),
            overrideOwner = string("dashboard_entity_override_instance"),
        )
    }

    /** Stage a prevalidated backup snapshot into the caller's existing config transaction. */
    internal fun stageDashboardEntityBackupState(
        editor: SharedPreferences.Editor,
        state: DashboardEntityBackupState,
    ) {
        editor.putString("dashboard_entity_instance", state.instanceKey)
            .putString("dashboard_entity_instance_origin", state.instanceOrigin)
            .putString("dashboard_entity_instance_uuid", state.instanceUuid)
            .putString("dashboard_entity_dashboard_path", state.dashboardPath)
            .putString("dashboard_entity_filter_ids", state.filterIds)
            .putBoolean("dashboard_entity_filter_enabled", state.filterEnabled)
            .putString("dashboard_entity_filter_instance", state.filterOwner)
            .putBoolean("dashboard_entity_learning_applied", state.learningApplied)
            .putString("dashboard_entity_applied_instance", state.appliedOwner)
            .putString("dashboard_entity_overrides", state.overrides)
            .putString("dashboard_entity_override_instance", state.overrideOwner)
    }

    /** The evidence namespace currently selected for the configured HA endpoint. A URL-derived key is
     *  used until mDNS can tie the authenticated endpoint to HA's stable core.uuid. */
    val dashboardEntityInstanceKey: String get() = prefs.getString("dashboard_entity_instance", "").orEmpty()
    val dashboardEntityInstanceOrigin: String get() = prefs.getString("dashboard_entity_instance_origin", "").orEmpty()
    val dashboardEntityInstanceUuid: String get() = prefs.getString("dashboard_entity_instance_uuid", "").orEmpty()
    val dashboardEntityDashboardPath: String
        get() = prefs.getString("dashboard_entity_dashboard_path", "").orEmpty()
    val dashboardEntityTargetKey: String
        get() = dashboardEntityInstanceKey.takeIf(String::isNotBlank)?.let {
            dashboardEntityTargetKey(it, dashboardEntityDashboardPath)
        }.orEmpty()

    private fun entityStateOwnedByCurrent(ownerPreference: String): Boolean {
        val current = dashboardEntityTargetKey
        val owner = prefs.getString(ownerPreference, "").orEmpty()
        // Entirely blank selection/owner is the pre-instance-identity compatibility state. Once a URL or
        // dashboard setter records a target change, even legacy unowned state must fail closed immediately.
        if (current.isBlank() && owner.isBlank() && dashboardEntityInstanceOrigin.isBlank() &&
            dashboardEntityDashboardPath.isBlank()) return true
        if (current.isBlank()) return false
        val configuredOrigin = canonicalHaOrigin(haUrl) ?: return false
        val configuredPath = normalizeDashboardEntityPath(homeDashboard)
        return current == owner && dashboardEntityInstanceOrigin == configuredOrigin &&
            dashboardEntityDashboardPath == configuredPath
    }

    /** Select the URL-keyed namespace and dashboard after a target change. Existing ownership is left
     *  behind, so a different/unverified HA or dashboard cannot inherit filter state. Null means the
     *  durable preference transaction failed and callers must not proceed with the selected target. */
    fun prepareDashboardEntityInstance(
        origin: String,
        dashboardPath: String,
        legacyKey: String,
    ): String? = synchronized(CONFIG_LOCK) {
        if (legacyKey.isBlank()) return null
        val canonicalOrigin = canonicalHaOrigin(origin) ?: return null
        val normalizedPath = normalizeDashboardEntityPath(dashboardPath)
        val currentOrigin = dashboardEntityInstanceOrigin
        val currentKey = dashboardEntityInstanceKey
        val currentPath = dashboardEntityDashboardPath
        if (currentKey.isNotBlank() && currentOrigin == canonicalOrigin && currentPath == normalizedPath) return currentKey
        val firstBinding = currentKey.isBlank() && currentOrigin.isBlank() && currentPath.isBlank()
        // A dashboard change at a verified origin retains its stable instance namespace. An origin change
        // must fall back to its URL-derived key until mDNS proves that it is merely an alias.
        val selectedKey = currentKey.takeIf { it.isNotBlank() && currentOrigin == canonicalOrigin } ?: legacyKey
        val selectedUuid = dashboardEntityInstanceUuid.takeIf {
            selectedKey == currentKey && currentOrigin == canonicalOrigin
        }.orEmpty()
        val selectedTarget = dashboardEntityTargetKey(selectedKey, normalizedPath)
        val committed = applyBatch {
            edit {
                putString("dashboard_entity_instance_origin", canonicalOrigin)
                putString("dashboard_entity_instance", selectedKey)
                putString("dashboard_entity_instance_uuid", selectedUuid)
                putString("dashboard_entity_dashboard_path", normalizedPath)
                if (firstBinding && prefs.getString("dashboard_entity_filter_instance", "").isNullOrBlank()) {
                    putString("dashboard_entity_filter_instance", selectedTarget)
                }
                if (firstBinding && prefs.getString("dashboard_entity_applied_instance", "").isNullOrBlank()) {
                    putString("dashboard_entity_applied_instance", selectedTarget)
                }
                if (firstBinding && prefs.getString("dashboard_entity_override_instance", "").isNullOrBlank()) {
                    putString("dashboard_entity_override_instance", selectedTarget)
                }
            }
        }
        return selectedKey.takeIf { committed }
    }

    /** Adopt a UUID namespace only if the endpoint is still the one that was authenticated/discovered.
     *  First adoption moves legacy ownership without altering a known-good list; later URL aliases for
     *  the same UUID simply select the already-owned stable key. */
    fun adoptDashboardEntityInstance(
        expectedOrigin: String,
        expectedDashboardPath: String,
        uuid: String,
        legacyKey: String,
        stableKey: String,
    ): Boolean = synchronized(CONFIG_LOCK) {
        if (uuid.isBlank() || legacyKey.isBlank() || stableKey.isBlank()) return false
        val canonicalOrigin = canonicalHaOrigin(expectedOrigin) ?: return false
        val normalizedPath = normalizeDashboardEntityPath(expectedDashboardPath)
        if (dashboardEntityInstanceOrigin != canonicalOrigin || dashboardEntityDashboardPath != normalizedPath) return false
        val current = dashboardEntityInstanceKey
        if (current != legacyKey && current != stableKey) return false
        val legacyTarget = dashboardEntityTargetKey(legacyKey, normalizedPath)
        val stableTarget = dashboardEntityTargetKey(stableKey, normalizedPath)
        return applyBatch {
            edit {
                putString("dashboard_entity_instance", stableKey)
                putString("dashboard_entity_instance_origin", canonicalOrigin)
                putString("dashboard_entity_instance_uuid", uuid)
                putString("dashboard_entity_dashboard_path", normalizedPath)
                val filterOwner = prefs.getString("dashboard_entity_filter_instance", "").orEmpty()
                if (filterOwner.isBlank() || filterOwner == legacyKey || filterOwner == legacyTarget) {
                    putString("dashboard_entity_filter_instance", stableTarget)
                }
                val appliedOwner = prefs.getString("dashboard_entity_applied_instance", "").orEmpty()
                if (appliedOwner.isBlank() || appliedOwner == legacyKey || appliedOwner == legacyTarget) {
                    putString("dashboard_entity_applied_instance", stableTarget)
                }
                val overrideOwner = prefs.getString("dashboard_entity_override_instance", "").orEmpty()
                if (overrideOwner.isBlank() || overrideOwner == legacyKey || overrideOwner == legacyTarget) {
                    putString("dashboard_entity_override_instance", stableTarget)
                }
            }
        }
    }

    /** Productized learner request. Distinct from runtime filter-active so first sync can fail open. */
    val dashboardEntityLearningEnabled: Boolean
        get() = prefs.getBoolean("dashboard_entity_learning", false)
    fun setDashboardEntityLearningEnabled(enabled: Boolean) { edit { putBoolean("dashboard_entity_learning", enabled) } }

    /** Which evidence sources may automatically change the live subscription. Evidence is still
     * collected when either switch is off so the Entities tab can present it for manual pinning. */
    val dashboardEntityAutoStatic: Boolean
        get() = prefs.getBoolean("dashboard_entity_auto_static", true)
    val dashboardEntityAutoRuntime: Boolean
        get() = prefs.getBoolean("dashboard_entity_auto_runtime", true)
    fun setDashboardEntityAutoPolicy(staticRefs: Boolean, runtimeRefs: Boolean): Boolean = applyBatch {
        edit {
            putBoolean("dashboard_entity_auto_static", staticRefs)
            putBoolean("dashboard_entity_auto_runtime", runtimeRefs)
        }
    }
    fun commitDashboardEntityLearningEnabled(enabled: Boolean): Boolean = applyBatch {
        setDashboardEntityLearningEnabled(enabled)
        if (!enabled) setDashboardEntityLearningApplied(false)
    }

    /**
     * v220 initially resolved a blank/default dashboard as ordinary Lovelace. That could publish an
     * exact allow-list for a different panel than the frontend rendered. Invalidate that one ambiguous
     * automatic result once, while retaining its ids as inactive rollback/debug evidence, and require a
     * fresh scan using the complete user -> system -> built-in Home resolution chain.
     *
     * [dashboardEntityFilterEnabled] also checks this migration latch, so a failed preference commit
     * cannot briefly reactivate the suspect allow-list.
     */
    internal fun migrateDashboardEntityDefaultResolver(): DashboardEntityDefaultResolverMigration =
        synchronized(CONFIG_LOCK) {
        if (!dashboardEntityDefaultResolverMigrationRequired) return DashboardEntityDefaultResolverMigration.NOT_NEEDED
        val committed = applyBatch { edit {
            putInt(DASHBOARD_ENTITY_DEFAULT_RESOLVER_VERSION_KEY, DASHBOARD_ENTITY_DEFAULT_RESOLVER_VERSION)
            putString(DASHBOARD_ENTITY_DEFAULT_RESOLVER_TARGET_KEY, dashboardEntityTargetKey)
            putString(DASHBOARD_ENTITY_DEFAULT_RESOLVER_PENDING_KEY, dashboardEntityTargetKey)
            putBoolean("dashboard_entity_filter_enabled", false)
            putBoolean("dashboard_entity_learning_applied", false)
            putString("dashboard_entity_applied_instance", dashboardEntityTargetKey)
        } }
        return when {
            !committed -> DashboardEntityDefaultResolverMigration.PERSIST_FAILED
            else -> DashboardEntityDefaultResolverMigration.REBOOTSTRAP
        }
    }

    private val dashboardEntityDefaultResolverMigrationRequired: Boolean
        get() {
            val target = dashboardEntityTargetKey
            if (target.isBlank() || !dashboardEntityLearningEnabled ||
                normalizeDashboardEntityPath(homeDashboard) != "/") return false
            return prefs.getInt(DASHBOARD_ENTITY_DEFAULT_RESOLVER_VERSION_KEY, 0) <
                DASHBOARD_ENTITY_DEFAULT_RESOLVER_VERSION ||
                prefs.getString(DASHBOARD_ENTITY_DEFAULT_RESOLVER_TARGET_KEY, "").orEmpty() != target
        }

    internal val dashboardEntityDefaultResolverRebootstrapPending: Boolean
        get() {
            val target = dashboardEntityTargetKey
            return target.isNotBlank() &&
                prefs.getString(DASHBOARD_ENTITY_DEFAULT_RESOLVER_PENDING_KEY, "").orEmpty() == target
        }

    /** Clear the durable hold only after the replacement subscription has committed successfully. */
    internal fun clearDashboardEntityDefaultResolverRebootstrapPending(): Boolean = synchronized(CONFIG_LOCK) {
        if (!dashboardEntityDefaultResolverRebootstrapPending) return true
        return applyBatch { edit { remove(DASHBOARD_ENTITY_DEFAULT_RESOLVER_PENDING_KEY) } }
    }

    /** Change automatic-learning mode without exposing a half-disabled stream. A fresh enable clears
     * the activation latch and reactivates any current-owner preserved allow-list for safe observation;
     * disabling turns interception off but keeps that list for a later re-enable. */
    fun commitDashboardEntityLearningMode(enabled: Boolean, clearApplied: Boolean): Boolean {
        val restorePreservedFilter = enabled && dashboardEntityFilterIds.isNotEmpty()
        return applyBatch {
            edit {
                putBoolean("dashboard_entity_learning", enabled)
                if (clearApplied) {
                    putBoolean("dashboard_entity_learning_applied", false)
                    putString("dashboard_entity_applied_instance", dashboardEntityTargetKey)
                }
                // Keep the old list under its old owner, but disable interception globally. Otherwise
                // switching back to that owner later would silently reactivate a filter after learning
                // was explicitly disabled on another target.
                if (!enabled) {
                    putBoolean("dashboard_entity_filter_enabled", false)
                } else if (restorePreservedFilter) {
                    putBoolean("dashboard_entity_filter_enabled", true)
                }
            }
        }
    }

    /** True after a fresh installation has safely bootstrapped or an observed set was explicitly applied. */
    val dashboardEntityLearningApplied: Boolean
        get() = entityStateOwnedByCurrent("dashboard_entity_applied_instance") &&
            prefs.getBoolean("dashboard_entity_learning_applied", false)
    fun setDashboardEntityLearningApplied(applied: Boolean) {
        edit {
            putBoolean("dashboard_entity_learning_applied", applied)
            putString("dashboard_entity_applied_instance", dashboardEntityTargetKey)
        }
    }
    fun commitDashboardEntityLearningApplied(applied: Boolean): Boolean = applyBatch {
        setDashboardEntityLearningApplied(applied)
    }

    /** Backup-safe expert overrides; the derived catalog and metrics remain rebuildable SQLite state. */
    val dashboardEntityOverrides: Map<String, String>
        get() = if (!entityStateOwnedByCurrent("dashboard_entity_override_instance")) emptyMap() else
            prefs.getString("dashboard_entity_overrides", "").orEmpty().lineSequence().mapNotNull { line ->
            val marker = line.firstOrNull() ?: return@mapNotNull null
            val id = line.drop(1).trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            when (marker) { '+' -> id to "pinned"; '-' -> id to "forced_exclude"; else -> null }
        }.toMap()

    fun setDashboardEntityOverrides(values: Map<String, String>): Boolean {
        val encoded = values.toSortedMap().mapNotNull { (id, value) ->
            when (value) { "pinned" -> "+$id"; "forced_exclude" -> "-$id"; else -> null }
        }.joinToString("\n")
        return applyBatch { edit {
            putString("dashboard_entity_overrides", encoded)
            putString("dashboard_entity_override_instance", dashboardEntityTargetKey)
        } }
    }

    /** Persist the complete experimental filter atomically so enabled cannot reference a partial list. */
    fun setDashboardEntityFilter(enabled: Boolean, entityIds: Collection<String>): Boolean {
        val normalized = entityIds.asSequence().map(String::trim).filter(String::isNotEmpty)
            .distinct().sorted().joinToString("\n")
        return applyBatch {
            edit {
                putString("dashboard_entity_filter_ids", normalized)
                putBoolean("dashboard_entity_filter_enabled", enabled && normalized.isNotEmpty())
                putString("dashboard_entity_filter_instance", dashboardEntityTargetKey)
            }
        }
    }

    /** Publish an automatic subscription and its activation latch as one owner-scoped transaction. */
    fun commitDashboardEntitySubscription(
        enabled: Boolean,
        entityIds: Collection<String>,
        applied: Boolean,
    ): Boolean {
        val normalized = entityIds.asSequence().map(String::trim).filter(String::isNotEmpty)
            .distinct().sorted().joinToString("\n")
        return applyBatch { edit {
            putString("dashboard_entity_filter_ids", normalized)
            // Automatic learning may intentionally install an empty allow-list after the user ignores
            // an unsafe selector. Enabled-empty means "subscribe nothing", never "fall back unfiltered".
            putBoolean("dashboard_entity_filter_enabled", enabled)
            putBoolean("dashboard_entity_learning_applied", applied)
            putString("dashboard_entity_filter_instance", dashboardEntityTargetKey)
            putString("dashboard_entity_applied_instance", dashboardEntityTargetKey)
        } }
    }

    /** Reset rebuildable learning preferences in one commit. The default preserves the known-good live
     * allow-list; an explicit clean-slate reset also clears it without disabling automatic learning. */
    fun commitDashboardEntityEvidenceReset(clearFilter: Boolean): Boolean {
        val target = dashboardEntityTargetKey
        if (target.isBlank()) return false
        return applyBatch { edit {
            putString("dashboard_entity_overrides", "")
            putString("dashboard_entity_override_instance", target)
            putBoolean("dashboard_entity_learning_applied", false)
            putString("dashboard_entity_applied_instance", target)
            if (clearFilter) {
                putString("dashboard_entity_filter_ids", "")
                putBoolean("dashboard_entity_filter_enabled", false)
                putString("dashboard_entity_filter_instance", target)
            }
        } }
    }

    /** Restore the exact owner-scoped preference state if the transactional SQLite evidence reset
     * fails. Identity fields are intentionally excluded: reset never owns or mutates the target. */
    internal fun restoreDashboardEntityEvidencePreferences(state: DashboardEntityBackupState): Boolean =
        applyBatch { edit {
            putString("dashboard_entity_filter_ids", state.filterIds)
            putBoolean("dashboard_entity_filter_enabled", state.filterEnabled)
            putString("dashboard_entity_filter_instance", state.filterOwner)
            putBoolean("dashboard_entity_learning_applied", state.learningApplied)
            putString("dashboard_entity_applied_instance", state.appliedOwner)
            putString("dashboard_entity_overrides", state.overrides)
            putString("dashboard_entity_override_instance", state.overrideOwner)
        } }

    /** Commit a promotion policy and any resulting live subscription as one durable preference change.
     *  A null [activeEntityIds] means the current stream is unchanged. */
    fun commitDashboardEntityPromotionPolicy(
        staticRefs: Boolean,
        runtimeRefs: Boolean,
        activeEntityIds: Collection<String>?,
        applied: Boolean,
    ): Boolean {
        val normalized = activeEntityIds?.asSequence()?.map(String::trim)?.filter(String::isNotEmpty)
            ?.distinct()?.sorted()?.joinToString("\n")
        return applyBatch { edit {
            putBoolean("dashboard_entity_auto_static", staticRefs)
            putBoolean("dashboard_entity_auto_runtime", runtimeRefs)
            putBoolean("dashboard_entity_learning_applied", applied)
            putString("dashboard_entity_applied_instance", dashboardEntityTargetKey)
            if (normalized != null) {
                putString("dashboard_entity_filter_ids", normalized)
                putBoolean("dashboard_entity_filter_enabled", applied)
                putString("dashboard_entity_filter_instance", dashboardEntityTargetKey)
            }
        } }
    }

    /** Atomically leave automatic learning and install a manually managed filter for the selected target.
     *  An enabled manual filter must contain at least one id; a target must have been durably prepared. */
    fun commitDashboardManualEntityFilter(enabled: Boolean, entityIds: Collection<String>): Boolean {
        val normalized = entityIds.asSequence().map(String::trim).filter(String::isNotEmpty)
            .distinct().sorted().joinToString("\n")
        val target = dashboardEntityTargetKey
        if (enabled && normalized.isEmpty()) return false
        if (target.isBlank() || canonicalHaOrigin(haUrl) != dashboardEntityInstanceOrigin ||
            normalizeDashboardEntityPath(homeDashboard) != dashboardEntityDashboardPath) return false
        return applyBatch { edit {
            putBoolean("dashboard_entity_learning", false)
            putBoolean("dashboard_entity_learning_applied", false)
            putString("dashboard_entity_applied_instance", target)
            putString("dashboard_entity_filter_ids", normalized)
            putBoolean("dashboard_entity_filter_enabled", enabled && normalized.isNotEmpty())
            putString("dashboard_entity_filter_instance", target)
        } }
    }

    /** Immediately hide filter state when the configured endpoint changes. The URL-derived instance key
     *  is selected later by [prepareDashboardEntityInstance], once its legacy key has been calculated. */
    private fun stageDashboardEntityHaUrlChange(editor: SharedPreferences.Editor, nextUrl: String) {
        val currentEndpoint = haUrl.trim().trimEnd('/')
        val nextEndpoint = nextUrl.trim().trimEnd('/')
        if (currentEndpoint != nextEndpoint) clearHaDeviceLink(editor)
        val current = canonicalHaOrigin(haUrl) ?: haUrl.trim().trimEnd('/')
        val next = canonicalHaOrigin(nextUrl) ?: nextUrl.trim().trimEnd('/')
        if (current == next) return
        editor.remove("dashboard_entity_instance")
            .remove("dashboard_entity_instance_uuid")
            .putString("dashboard_entity_instance_origin", next)
    }

    private fun clearHaDeviceLink(editor: SharedPreferences.Editor) {
        editor.remove("ha_device_url").remove("ha_link_target").remove("ha_link_at")
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
        edit { putString("navbar_mode", mode) }
    }

    // After an app update the launcher shows the App UI; when the configured renderer is ready, bounce
    // back to the dashboard so it does not linger. MQTT is optional. Default on.
    val autoReturnDashboard: Boolean get() = prefs.getBoolean("auto_return_dashboard", true)
    fun setAutoReturnDashboard(on: Boolean) {
        edit { putBoolean("auto_return_dashboard", on) }
    }

    // Silence the firmware startup chime by zeroing the ring/notification volume via Settings.System.
    // Default off — existing panels already have their own volume state; only opt in deliberately.
    val silenceBootChime: Boolean get() = prefs.getBoolean(
        "silence_boot_chime",
        SettingsRegistry.DEFAULT_SILENCE_BOOT_CHIME,
    )
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

    /** Strength of deviations from the learned ambient-light baseline; 50 is the neutral response. */
    val autoBrightnessSensitivity: Int
        get() = prefs.getInt("auto_brightness_sensitivity", 50).coerceIn(0, 100)
    fun setAutoBrightnessSensitivity(value: Int) {
        edit { putInt("auto_brightness_sensitivity", value.coerceIn(0, 100)) }
    }

    /** Lowest automatic target in user-facing percent; the actuator's visible floor remains absolute. */
    val autoBrightnessMinimumPercent: Int
        get() = prefs.getInt("auto_brightness_minimum_percent", 4).coerceIn(4, 95)
    fun setAutoBrightnessMinimumPercent(value: Int) {
        edit { putInt("auto_brightness_minimum_percent", value.coerceIn(4, 95)) }
    }

    /** Exact HA illuminance entity selected instead of the local ALS; blank uses the panel sensor. */
    val autoBrightnessHaEntity: String
        get() = prefs.getString("auto_brightness_ha_entity", "").orEmpty()
    fun setAutoBrightnessHaEntity(entityId: String) {
        edit { putString("auto_brightness_ha_entity", entityId.trim()) }
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
    @Volatile private var proximityCalibrationPrefix: String? = null
    @Volatile private var proximityCalibrationProfilePrefix: String? = null
    fun attachProfile(p: DeviceProfile) {
        // Retain the old profile namespace only long enough for the adaptive learner to consume a
        // complete user-captured near/far pair. New proximity state is owned by the learner.
        proximityCalibrationProfilePrefix = "profile_calibration.${sanitizeProfileIdentity(p.id)}"
        proximityCalibrationPrefix = "${proximityCalibrationProfilePrefix}.${sanitizeProfileIdentity(p.revision)}"
        migrateLegacyButtonBacklight(p)
        profile = p
    }

    private fun profileStateSuffix(profile: DeviceProfile): String =
        "${sanitizeProfileIdentity(profile.id)}.${sanitizeProfileIdentity(profile.revision)}"

    private fun sanitizeProfileIdentity(value: String): String =
        value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9._-]"), "-")

    private fun buttonBacklightKey(profile: DeviceProfile): String =
        "last_button_backlight.${profileStateSuffix(profile)}"

    private fun migrateLegacyButtonBacklight(profile: DeviceProfile) {
        if (!profile.hasButtonBacklight || !prefs.contains("last_button_backlight")) return
        val target = buttonBacklightKey(profile)
        val editor = prefs.edit()
        if (!prefs.contains(target)) editor.putInt(target, prefs.getInt("last_button_backlight", -1))
        editor.remove("last_button_backlight").apply()
    }

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

    // --- legacy proximity calibration retirement -----------------------------------------------

    /**
     * Returns one complete user-captured near/far pair for the adaptive learner, then synchronously
     * removes every old threshold, polarity, endpoint, sensitivity, and migration key. The active
     * profile revision wins, followed by the earlier profile-id namespace and unscoped legacy state.
     * Incomplete or non-finite captures are discarded with the rest of the retired calibration state.
     */
    internal fun consumeLegacyProximityLearningSeed(): ProximityLearningSeed? = synchronized(CONFIG_LOCK) {
        val currentPrefix = proximityCalibrationPrefix
        val profilePrefix = proximityCalibrationProfilePrefix

        fun seedIn(store: SharedPreferences, prefix: String): ProximityLearningSeed? {
            val separator = if (prefix.isEmpty()) "" else "$prefix."
            val near = store.all["${separator}prox_near_raw"] as? Float ?: return null
            val far = store.all["${separator}prox_far_raw"] as? Float ?: return null
            return ProximityLearningSeed(near, far).takeIf { near.isFinite() && far.isFinite() }
        }

        fun namespacesIn(store: SharedPreferences): List<String> = buildList {
            currentPrefix?.let(::add)
            profilePrefix?.let(::add)
            if (profilePrefix != null) {
                store.all.keys.asSequence()
                    .filter { it.startsWith("$profilePrefix.") && it.endsWith(".prox_near_raw") }
                    .map { it.removeSuffix(".prox_near_raw") }
                    // A revision is one sanitized segment. This avoids borrowing a capture from a
                    // different dotted profile id that happens to extend the active profile's id.
                    .filter { !it.removePrefix("$profilePrefix.").contains('.') }
                    .sorted()
                    .forEach(::add)
            }
            add("")
        }.distinct()

        val seed = sequence {
            for (namespace in namespacesIn(calibrationPrefs)) yield(seedIn(calibrationPrefs, namespace))
            for (namespace in namespacesIn(prefs)) yield(seedIn(prefs, namespace))
        }.filterNotNull().firstOrNull()

        fun keysToRetire(store: SharedPreferences): List<String> = store.all.keys.filter { key ->
            key == PROXIMITY_PROFILE_MIGRATED || LEGACY_PROXIMITY_KEYS.any { legacy ->
                key == legacy || (key.startsWith("profile_calibration.") && key.endsWith(".$legacy"))
            }
        }

        // Clear the backup-eligible store first. If that commit fails, the isolated calibration store
        // still retains the seed for a later retry; only report consumption after both commits succeed.
        val configKeys = keysToRetire(prefs)
        val configCleared = prefs.edit().also { editor -> configKeys.forEach(editor::remove) }.commit()
        if (!configCleared) return@synchronized null
        val calibrationKeys = keysToRetire(calibrationPrefs)
        val calibrationCleared = calibrationPrefs.edit().also { editor -> calibrationKeys.forEach(editor::remove) }.commit()
        if (!calibrationCleared) return@synchronized null
        seed
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
        val v = raw.trim().toFloatOrNull()?.takeIf { it.isFinite() } ?: return
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
        get() = if (profile?.hasButtonBacklight == true) {
            prefs.getInt(buttonBacklightKey(profile!!), -1)
        } else {
            -1
        }
        set(v) {
            profile?.takeIf { it.hasButtonBacklight }?.let {
                prefs.edit().putInt(
                    buttonBacklightKey(it),
                    v.coerceIn(-1, 255),
                ).apply()
            }
        }

    // --- arrangeable dashboard layout (per-panel; serialized by the web UI, opaque to the backend) ---
    /** Gridstack layout JSON for the Dashboard tab, persisted per-panel (empty = default layout). */
    var uiDashboardLayout: String
        get() = prefs.getString("ui_dashboard_layout", "")!!
        set(v) { prefs.edit().putString("ui_dashboard_layout", v).apply() }

    // --- registry-driven generic access (HTTP config API + bundles + revisions) -----------------
    // These read/write a setting by its [SettingSpec] so the config API, form generation, export and
    // import all go through one typed path instead of ~35 bespoke getters. Typed convenience getters
    // above remain for callers; they read the same durable keys.

    /** Current raw string value for a registry key (the spec default if unset). */
    fun getRaw(spec: SettingSpec): String = when (spec.type) {
        SettingType.BOOL -> prefs.getBoolean(spec.key, spec.default.toBoolean()).toString()
        SettingType.INT -> prefs.getInt(spec.key, spec.default.toIntOrNull() ?: 0).toString()
        SettingType.LONG -> prefs.getLong(spec.key, spec.default.toLongOrNull() ?: 0L).toString()
        SettingType.FLOAT -> prefs.getFloat(spec.key, spec.default.toFloatOrNull() ?: 0f).toString()
        else -> prefs.getString(spec.key, spec.default) ?: spec.default
    }

    /** Stage a typed write into [editor] (no commit) — used by the transactional bundle import. */
    fun stage(editor: SharedPreferences.Editor, spec: SettingSpec, normalized: String) {
        when (spec.type) {
            SettingType.BOOL -> editor.putBoolean(spec.key, normalized.toBoolean())
            SettingType.INT -> editor.putInt(spec.key, normalized.toIntOrNull() ?: 0)
            SettingType.LONG -> editor.putLong(spec.key, normalized.toLongOrNull() ?: 0L)
            SettingType.FLOAT -> editor.putFloat(spec.key, normalized.toFloatOrNull() ?: 0f)
            else -> editor.putString(spec.key, normalized)
        }
    }

    /** Synchronously persist one validated registry value before a low-frequency live actuator is
     * acknowledged. Transient inputs and publish-only sensors have no durable configuration value. */
    internal fun commitRaw(spec: SettingSpec, normalized: String): Boolean = synchronized(CONFIG_LOCK) {
        if (spec.transient || spec.readOnly) return false
        val value = (SettingValue.validate(spec, normalized) as? Validation.Ok)?.normalized ?: return false
        val committed = prefs.edit().also { editor ->
            if (spec.key == "home_dashboard") stageHomeDashboard(editor, value, homeDashboard)
            else stage(editor, spec, value)
        }.commit()
        if (committed && spec.key == "wake_on_wave") wakeOnWaveEpoch.incrementAndGet()
        committed
    }

    /** A new editor for staging a batch of registry writes (commit with [SharedPreferences.Editor.commit]). */
    fun editor(): SharedPreferences.Editor = prefs.edit()

    /** Commit a caller-staged transaction under the same lock as grouped credential mutation and OAuth
     * refresh ownership checks. */
    internal fun commit(
        editor: SharedPreferences.Editor,
        afterCommit: () -> Unit = {},
    ): Boolean = synchronized(CONFIG_LOCK) {
        editor.commit().also { committed -> if (committed) afterCommit() }
    }

    /** Schema the live store was last written at. Absent → 1 (the original shape, before this tracking),
     *  so an app upgrade that bumps [SettingsRegistry.SCHEMA] triggers a one-time on-device migration. */
    private val storedSchema: Int get() = prefs.getInt("config_schema", 1)

    /**
     * Migrate the live config store to the current [SettingsRegistry.SCHEMA] when it was
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
        /** Every Config wrapper addresses one process-wide SQLite namespace, so transaction ownership
         * must be process-wide as well. */
        private val CONFIG_LOCK = Any()
        private const val TAG = "ha-paneld/config"
        private const val SECURITY_MODE_PREF = "device_local_hardened_security"
        private const val MQTT_FAMILY_BROKER_PREF = "device_local_mqtt_family_broker"
        private const val MQTT_FAMILY_IPV4_PREF = "device_local_mqtt_family_ipv4"
        private const val MQTT_ANNOUNCEMENT_BOUNDARY_PREF =
            "device_local_mqtt_announcement_boundary_consumed"
        private const val LAST_LAUNCH_SCREEN_VERSION_PREF =
            "device_local_last_launch_screen_version"
        private const val DASHBOARD_ENTITY_DEFAULT_RESOLVER_VERSION_KEY =
            "dashboard_entity_default_resolver_version"
        private const val DASHBOARD_ENTITY_DEFAULT_RESOLVER_TARGET_KEY =
            "dashboard_entity_default_resolver_target"
        private const val DASHBOARD_ENTITY_DEFAULT_RESOLVER_PENDING_KEY =
            "dashboard_entity_default_resolver_pending"
        private const val DASHBOARD_ENTITY_DEFAULT_RESOLVER_VERSION = 1
        private const val PROXIMITY_PROFILE_MIGRATED = "proximity_profile_calibration_migrated"
        internal const val PROFILE_CALIBRATION_PREFS = "ha-paneld-profile-calibration"
        private val LEGACY_PROXIMITY_KEYS = listOf(
            "prox_near_raw",
            "prox_far_raw",
            "prox_threshold",
            "prox_near_below",
            "prox_sensitivity",
        )
        const val DEFAULT_PORT = 8888
        const val VERSION = BuildConfig.VERSION_NAME
        const val MDNS_SERVICE_TYPE = "_ha-paneld._tcp.local."
    }
}

internal enum class DashboardEntityDefaultResolverMigration {
    NOT_NEEDED,
    REBOOTSTRAP,
    PERSIST_FAILED,
}

/** Dashboard paths are ownership boundaries. Query/fragment state does not identify a Lovelace dashboard. */
internal fun normalizeDashboardEntityPath(raw: String): String {
    val value = raw.trim().ifBlank { "/" }
    val parsedPath = runCatching { java.net.URI(value).path }.getOrNull().orEmpty().ifBlank { value }
    val leading = if (parsedPath.startsWith('/')) parsedPath else "/$parsedPath"
    return leading.replace(Regex("/{2,}"), "/").let { if (it.length > 1) it.trimEnd('/') else it }
}

/** Length-prefixing avoids collisions if a future instance-key encoding contains the separator. */
internal fun dashboardEntityTargetKey(instanceKey: String, dashboardPath: String): String {
    val key = instanceKey.trim()
    if (key.isEmpty()) return ""
    val path = normalizeDashboardEntityPath(dashboardPath)
    return "${key.length}:$key$path"
}
