package io.github.maxlyth.hapaneld.http

import android.content.Context
import android.util.Log
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.DashboardEntityBackupState
import io.github.maxlyth.hapaneld.normalizeDashboardEntityPath
import io.github.maxlyth.hapaneld.peersJson
import io.github.maxlyth.hapaneld.config.Capabilities
import io.github.maxlyth.hapaneld.config.ConfigBundle
import io.github.maxlyth.hapaneld.config.ConfigDiff
import io.github.maxlyth.hapaneld.config.Migrations
import io.github.maxlyth.hapaneld.backup.PanelBackup
import io.github.maxlyth.hapaneld.backup.CompanionRestore
import io.github.maxlyth.hapaneld.config.Scope
import io.github.maxlyth.hapaneld.config.SettingType
import io.github.maxlyth.hapaneld.config.SettingValue
import io.github.maxlyth.hapaneld.config.SettingsRegistry
import io.github.maxlyth.hapaneld.config.Validation
import io.github.maxlyth.hapaneld.control.CdpRelay
import io.github.maxlyth.hapaneld.control.CompanionDb
import io.github.maxlyth.hapaneld.control.CompanionDataOperationGate
import io.github.maxlyth.hapaneld.control.CompanionDataOperationState
import io.github.maxlyth.hapaneld.control.DensityController
import io.github.maxlyth.hapaneld.control.InteractiveController
import io.github.maxlyth.hapaneld.control.Su
import io.github.maxlyth.hapaneld.control.SystemController
import io.github.maxlyth.hapaneld.control.TameController
import io.github.maxlyth.hapaneld.control.VolumeController
import io.github.maxlyth.hapaneld.control.ZigbeeHealthSnapshot
import io.github.maxlyth.hapaneld.control.ZigbeeHealthState
import io.github.maxlyth.hapaneld.dashboard.EntityFilterProtocol
import io.github.maxlyth.hapaneld.dashboard.EntityFilterTelemetry
import io.github.maxlyth.hapaneld.dashboard.EntityLearningManager
import io.github.maxlyth.hapaneld.device.DeviceProfile
import io.github.maxlyth.hapaneld.device.TameCandidate
import io.github.maxlyth.hapaneld.device.profile.PassiveProfileDraft
import io.github.maxlyth.hapaneld.device.profile.PassiveProfileReport
import io.github.maxlyth.hapaneld.device.profile.ProfileAdmin
import io.github.maxlyth.hapaneld.device.profile.ProfileBackup
import io.github.maxlyth.hapaneld.device.profile.ProfileBackupRestoreOutcome
import io.github.maxlyth.hapaneld.device.profile.ProfileBackupRestorePlan
import io.github.maxlyth.hapaneld.device.profile.ProfileBackupRestoreResult
import io.github.maxlyth.hapaneld.logship.LogCapture
import io.github.maxlyth.hapaneld.metrics.FeatureCosts
import io.github.maxlyth.hapaneld.metrics.FeatureCostOperation
import io.github.maxlyth.hapaneld.metrics.FeatureCostOutcome
import io.github.maxlyth.hapaneld.provisioning.ProvisioningActivationSnapshot
import io.github.maxlyth.hapaneld.provisioning.ProvisioningReader
import io.github.maxlyth.hapaneld.sensors.SensorReporter
import io.github.maxlyth.hapaneld.shizuku.ShizukuBridge
import io.github.maxlyth.hapaneld.util.Cached
import io.github.maxlyth.hapaneld.util.AppInstaller
import io.github.maxlyth.hapaneld.util.AndroidInput
import io.github.maxlyth.hapaneld.util.BoundedStreams
import io.github.maxlyth.hapaneld.util.BundledHelperInstaller
import io.github.maxlyth.hapaneld.util.CompanionInstaller
import io.github.maxlyth.hapaneld.util.CompanionHelperProtocol
import io.github.maxlyth.hapaneld.util.CompanionOperationStatus
import io.github.maxlyth.hapaneld.util.HelperClient
import io.github.maxlyth.hapaneld.util.ByteLimitExceeded
import io.github.maxlyth.hapaneld.util.InstallProgress
import io.github.maxlyth.hapaneld.util.Json
import io.github.maxlyth.hapaneld.util.KeyedLatestDispatcher
import io.github.maxlyth.hapaneld.util.GenerationSingleFlight
import io.github.maxlyth.hapaneld.util.RendererPreparationCoordinator
import io.github.maxlyth.hapaneld.util.SelfUpdater
import io.github.maxlyth.hapaneld.util.UpdateChecker
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.parseQueryString
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveStream
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File

internal fun panelBrowserTitle(friendlyName: String, section: String? = null): String {
    val panel = friendlyName.trim().ifBlank { "ha-paneld" }
    val suffix = section?.trim().orEmpty()
    return if (suffix.isBlank()) panel else "$panel · $suffix"
}

/**
 * Keep app-side launch suppression until the helper affirmatively reports that no Companion-data
 * worker can still be mutating files. An unreachable status socket is not evidence that a worker
 * from an earlier, timed-out connection has stopped; the daemon may merely be temporarily unable to
 * accept or answer the probe. The global Companion gate admits only one lease, so retaining it and
 * one low-frequency polling coroutine is resource-bounded even across a prolonged outage.
 *
 * A legacy `UNSUPPORTED` reply is not affirmative while the app marker is armed: a newer helper may
 * have published a durable journal and then died before an older init-managed helper restarted.
 */
internal suspend fun retainCompanionLeaseUntilHelperIdle(
    lease: CompanionDataOperationGate.Lease,
    operationState: CompanionDataOperationState,
    afterRelease: () -> Unit,
    operationStatus: () -> CompanionOperationStatus,
    pollMs: Long,
) {
    try {
        while (true) {
            val status = runCatching(operationStatus).getOrDefault(CompanionOperationStatus.UNAVAILABLE)
            when (status) {
                CompanionOperationStatus.IDLE -> if (operationState.clear()) break
                CompanionOperationStatus.BUSY,
                CompanionOperationStatus.UNSUPPORTED,
                CompanionOperationStatus.UNAVAILABLE -> delay(pollMs.coerceAtLeast(1L))
            }
            if (status == CompanionOperationStatus.IDLE) delay(pollMs.coerceAtLeast(1L))
        }
    } finally {
        lease.close()
    }
    afterRelease()
}

/**
 * Resolve one app-side Companion lease. A BUSY/indeterminate exchange retains durable suppression;
 * every terminal or explicit never-submitted outcome clears it immediately. If clearing cannot be
 * made durable, retain the lease and let status reconciliation retry rather than launching partially.
 *
 * Returns true when ownership was transferred to asynchronous retention.
 */
internal fun settleCompanionOperationLease(
    lease: CompanionDataOperationGate.Lease,
    operationState: CompanionDataOperationState,
    possiblyInFlight: Boolean,
    retain: (CompanionDataOperationGate.Lease, () -> Unit) -> Unit,
    afterRelease: () -> Unit,
): Boolean {
    if (possiblyInFlight || !operationState.clear()) {
        retain(lease, afterRelease)
        return true
    }
    lease.close()
    afterRelease()
    return false
}

/** Bound config mutations before Ktor or JSONObject materializes attacker-controlled form/JSON data. */
internal suspend fun receiveBoundedConfigParameters(
    call: ApplicationCall,
    maxBytes: Long = PaneldServer.MAX_CONFIG_POST_BODY_BYTES,
): Parameters? {
    val body = when (val receipt = receiveBoundedBody(call, maxBytes)) {
        is BoundedBodyReceipt.Received -> String(receipt.bytes, Charsets.UTF_8)
        BoundedBodyReceipt.TooLarge -> {
            call.respondText("request too large\n", status = HttpStatusCode.PayloadTooLarge)
            return null
        }
        BoundedBodyReceipt.TimedOut -> {
            call.respondText("request timeout\n", status = HttpStatusCode.RequestTimeout)
            return null
        }
    }
    return try {
        if (call.request.headers["Content-Type"].orEmpty().substringBefore(';').trim()
                .equals(ContentType.Application.Json.toString(), ignoreCase = true)
        ) {
            val json = JSONObject(body)
            Parameters.build {
                json.keys().forEach { key ->
                    val value = json.get(key)
                    require(value === JSONObject.NULL || value is String || value is Number || value is Boolean)
                    append(key, if (value === JSONObject.NULL) "" else value.toString())
                }
            }
        } else {
            parseQueryString(body)
        }
    } catch (_: Throwable) {
        call.respondText("invalid config body\n", status = HttpStatusCode.BadRequest)
        null
    }
}

/** Materialize the many small form-only control posts under one total-body limit. Ktor's default
 * receiveParameters limit is 50 MiB per field, which is disproportionate on low-memory wall panels. */
internal suspend fun receiveBoundedFormParameters(
    call: ApplicationCall,
    maxBytes: Long = PaneldServer.MAX_SMALL_FORM_POST_BODY_BYTES,
): Parameters? {
    val body = when (val receipt = receiveBoundedBody(call, maxBytes)) {
        is BoundedBodyReceipt.Received -> String(receipt.bytes, Charsets.UTF_8)
        BoundedBodyReceipt.TooLarge -> {
            call.respondText("request too large\n", status = HttpStatusCode.PayloadTooLarge)
            return null
        }
        BoundedBodyReceipt.TimedOut -> {
            call.respondText("request timeout\n", status = HttpStatusCode.RequestTimeout)
            return null
        }
    }
    return parseQueryString(body)
}

/** Result of validating a direct config POST before any preference or controller mutation. */
internal sealed class ConfigPostParameters {
    data class Ok(val values: Parameters) : ConfigPostParameters()
    data class Bad(val reason: String) : ConfigPostParameters()
}

/**
 * Validate and normalize every direct-config value in one pass. Historically the bespoke route
 * normalized only panel_id while malformed booleans became false, numeric values were silently
 * clamped/ignored, and large identity strings could be repeated into every MQTT discovery payload.
 * Keeping this admission step ahead of applyBatch gives form, JSON and registry command paths the
 * same schema semantics and ensures a bad field cannot produce a partial commit.
 */
internal fun normalizeConfigPostParameters(raw: Parameters): ConfigPostParameters {
    val normalized = Parameters.build {
        for (name in raw.names()) {
            val all = raw.getAll(name).orEmpty()
            if (all.size != 1) return ConfigPostParameters.Bad("$name: expected one value")
            val value = all.single()
            val spec = SettingsRegistry.spec(name)
            val accepted = when {
                spec != null -> {
                    if (spec.readOnly) return ConfigPostParameters.Bad("$name: read-only")
                    when (val result = SettingValue.validate(spec, value)) {
                        is Validation.Ok -> result.normalized
                        is Validation.Bad -> return ConfigPostParameters.Bad(result.reason)
                    }
                }
                name.startsWith("ha_expose_") -> {
                    val target = name.removePrefix("ha_expose_")
                    val targetSpec = SettingsRegistry.spec(target)
                    if (targetSpec?.ha == null) return ConfigPostParameters.Bad("$name: unknown exposure setting")
                    SettingValue.parseBool(value)?.toString()
                        ?: return ConfigPostParameters.Bad("$name: expected a boolean")
                }
                name == "ha_token_expiry" -> {
                    val expiry = value.trim().toLongOrNull()
                        ?: return ConfigPostParameters.Bad("ha_token_expiry: expected an integer")
                    if (expiry < 0L) return ConfigPostParameters.Bad("ha_token_expiry: must be ≥ 0")
                    expiry.toString()
                }
                name == "tame_vendor_packages" -> {
                    val trimmed = value.trim()
                    if (trimmed.length > 8_192) {
                        return ConfigPostParameters.Bad("tame_vendor_packages: must be at most 8192 characters")
                    }
                    val packages = trimmed.split(Regex("[\\s,]+")).filter(String::isNotEmpty)
                    if (packages.size > 128 || packages.any { it.length > 255 || !AndroidInput.isPackage(it) }) {
                        return ConfigPostParameters.Bad("tame_vendor_packages: expected at most 128 Android package names")
                    }
                    packages.distinct().joinToString(" ")
                }
                name == "http_allowed_hosts" -> {
                    val trimmed = value.trim()
                    if (trimmed.length > 4_096) {
                        return ConfigPostParameters.Bad("http_allowed_hosts: must be at most 4096 characters")
                    }
                    val hosts = trimmed.split(Regex("[\\s,]+")).filter(String::isNotEmpty)
                    if (hosts.size > 128 || hosts.any { it.length > 253 || it.any(Char::isWhitespace) }) {
                        return ConfigPostParameters.Bad("http_allowed_hosts: expected at most 128 host names")
                    }
                    hosts.distinct().joinToString(" ")
                }
                else -> return ConfigPostParameters.Bad("$name: unknown setting")
            }
            append(name, accepted)
        }
    }
    return ConfigPostParameters.Ok(normalized)
}

/** Conservative disk peak while source entries, archive plaintext and optional ciphertext overlap. */
internal fun backupStagingRequirement(includeCompanion: Boolean, encrypted: Boolean): Long {
    val sources = PaneldServer.MAX_BACKUP_MANIFEST_BYTES +
        2L * PaneldServer.MAX_ENTITY_BACKUP_TEXT_BYTES +
        PaneldServer.MAX_PROFILE_BACKUP_ENTRY_BYTES +
        if (includeCompanion) PaneldServer.MAX_COMPANION_BACKUP_BYTES else 0L
    val archives = PaneldServer.MAX_RESTORE_BYTES * if (encrypted) 2L else 1L
    val archivePeak = sources + archives
    val rawCapturePeak = if (includeCompanion) CompanionHelperProtocol.MAX_BACKUP_STREAM_BYTES else 0L
    return PaneldServer.BACKUP_STORAGE_MARGIN_BYTES + maxOf(archivePeak, rawCapturePeak)
}

/**
 * Ktor CIO HTTP surface on :8888. Serves the TTS-announce contract plus a small panel info/config
 * UI at `/` (the device's `configuration_url`, so HA shows a "Visit" link).
 *
 * Routes:
 *   GET  /         panel info + panel_id config form (HTML)
 *   POST /config   set panel_id (form `panel_id`), then live-reconfigure
 *   GET  /health   200 with version + panel id
 *   POST /play     body has a URL (raw or `{"url":"…"}`) -> 200 "playing", background playback;
 *                  no URL -> 400 "no-url"
 *
 * [info] returns the ordered key/value facts to render; [onConfig] applies new settings (panel id
 * + MQTT broker/user/password). Both are supplied by the service, which owns the runtime objects.
 */
class PaneldServer internal constructor(
    private val config: Config,
    private val cacheDir: File,
    private val scope: CoroutineScope,
    private val appContext: Context,
    private val sensors: SensorReporter,
    private val profile: DeviceProfile,
    // For the on-screen Controls card (software navbar) on panels with no physical nav bar.
    private val system: SystemController,
    private val volume: VolumeController,
    // Service-owned latest-wins audio lane. False means teardown has closed admission.
    private val playAudio: (String) -> Boolean,
    // Called after this server has written new settings to [config]; the service rebuilds MQTT/mDNS.
    private val onReconfigure: () -> Unit,
    // Applies a single behaviour setting through the MQTT bridge's command path (persist → drive
    // hardware → publish HA state). Lets the config API set the formerly MQTT-only keys identically
    // to an HA command. Returns true if the key was a recognised live setting.
    private val applySetting: (String, String) -> Boolean,
    // Current values of the CONTROLLER-sourced settings (touch_sound, cpu_governor, network_adb,
    // zigbee_router) — their state lives in controllers, not the config namespace, so the config
    // form/schema/dashboard read them through this instead of Config.getRaw.
    private val liveValues: () -> Map<String, String> = { emptyMap() },
    // This panel's capability snapshot (sensors + cpu/adb/zigbee probes) for availableWhen gating.
    private val capabilities: () -> Capabilities = { Capabilities() },
    private val info: () -> Map<String, String>,
    // Per-panel "HA-optimised" density + text-scale suggestions (DeviceProfile), or null.
    private val recommendedDensity: Int? = null,
    private val recommendedFontScale: Float? = null,
    // Vendor-taming: the controller (applies the action) and the profile's curated recommendations (the
    // picker's "Recommended" group).
    private val tame: TameController,
    private val tameProfileCandidates: List<TameCandidate> = emptyList(),
    // Live log viewer sources (Logs tab / SSE stream). App = own-process logcat (no root); system =
    // full logcat via su, gated on Su.available() at request time. Null → the viewer 404s.
    private val logApp: LogCapture? = null,
    private val logSystem: LogCapture? = null,
    // EFFECTIVE backlight (sysfs actual_brightness via BrightnessController, cached) — the sensors
    // endpoint + Live-state row report what the hardware is doing, not just the Android setting.
    private val effectiveBrightness: () -> Int = { -1 },
    // Repair a Companion server row with an empty internal_url (the HA 2026.7 "Missing Host header"
    // incident). False means the shared destructive-operation lane is busy.
    private val onRepairCompanionUrl: () -> Boolean = { false },
    // Install/update a managed component from the Install tab. name ∈ {paneld, companion, webview};
    // action ∈ {update, reinstall}; version = a specific release tag to install (blank = channel newest).
    // Runs off-thread; progress is reported via InstallProgress. Injected by the service.
    private val onInstallComponent: (String, String, String) -> Boolean = { _, _, _ -> false },
    // Bounded EFR32 health snapshot, or null when this panel has no radio gateway.
    private val radioStatus: () -> ZigbeeHealthSnapshot? = { null },
    // LAN ha-paneld peers discovered over mDNS — powers the header panel switcher. Injected by the service
    // (captures the live MdnsAdvertiser field). Blocking browse; called only through [peersCache] off-thread.
    private val peers: () -> List<io.github.maxlyth.hapaneld.Peer> = { emptyList() },
    // Shared with the service startup path: serializes renderer config commit → atomic Companion borrow →
    // launch, and retries an interrupted built-in switch from its durable blank-URL state.
    private val rendererPreparation: RendererPreparationCoordinator,
    private val entityLearning: EntityLearningManager,
    private val companionDataOperationState: CompanionDataOperationState =
        CompanionDataOperationState.from(appContext),
    // Runtime-loadable profiles. Optional during staged integration so the existing service can keep
    // constructing the HTTP server before its repository/restart wiring lands; the tab then reports 503.
    private val profileAdmin: ProfileAdmin? = null,
    private val profileTemplate: () -> String? = { null },
    private val profileDeviceDraft: () -> PassiveProfileDraft? = { null },
    private val profileReport: () -> PassiveProfileReport? = { null },
    private val profileProbe: (String) -> PassiveProfileReport? = { null },
    private val onProfileRestart: () -> Boolean = { false },
    private val profileRestartAllowed: () -> Boolean = { true },
    private val onProfileRestartAbort: (String) -> Boolean = { false },
    private val provisioningReader: ProvisioningReader? = null,
    private val provisioningActivation: () -> ProvisioningActivationSnapshot = {
        error("provisioning activation provider is unavailable")
    },
) {
    // Per-INSTALL build token (changes on every (re)install, not just a version bump) so an open info
    // page can auto-reload after the app is updated — even a same-version dev re-spin. /health carries it.
    private fun buildToken(): String =
        runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0).lastUpdateTime.toString() }
            .getOrDefault(Config.VERSION)

    /** True if [host] (the request's source IP) is a LAN-local address: loopback, RFC1918, link-local, or
     *  IPv6 ULA. Global/public sources return false → 403. Parses with InetAddress, which unmaps an
     *  IPv4-mapped IPv6 source (`::ffff:a.b.c.d`) to its IPv4 form, so the dual-stack bind can't smuggle a
     *  public IPv4 past the RFC1918 check. Strips any `%zone` and leading `/`. */
    private fun isLocalSource(host: String): Boolean = runCatching {
        val a = java.net.InetAddress.getByName(host.substringBefore('%').removePrefix("/"))
        a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isAnyLocalAddress ||
            (a is java.net.Inet6Address && (a.address[0].toInt() and 0xfe) == 0xfc) // fc00::/7 ULA
    }.getOrDefault(false)

    // Panel-info rows blurred by default (screenshot hygiene) — identity + network values a casual share
    // shouldn't leak. "Reveal" un-blurs them. Not access control: the values are still in the page source.
    private val SECRET_FIELDS = setOf("Device ID", "MQTT")
    // Address rows blur ONLY when the value is globally ROUTABLE — an unroutable RFC1918 / ULA / link-local
    // address (e.g. the LAN IPv4, or a ULA v6) has no external use, so it stays visible.
    private val ADDRESS_FIELDS = setOf("Local IP", "Local IPv6")

    /** True only for a parseable, globally-routable address (not loopback / RFC1918 / ULA / link-local).
     *  Unparseable values (e.g. "—") return false → not blurred. */
    private fun isRoutable(host: String): Boolean = runCatching {
        val a = java.net.InetAddress.getByName(host.substringBefore('%').removePrefix("/"))
        !(a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isAnyLocalAddress ||
            (a is java.net.Inet6Address && (a.address[0].toInt() and 0xfe) == 0xfc))
    }.getOrDefault(false)

    /** Appends physical dimensions only when the device profile supplies independently verified PPI.
     *  Logical density is a layout setting and must never be used to infer the panel's physical size. */
    private fun displayCell(v: String): String {
        val resolution = Regex("^(\\d+)×(\\d+) px\\b").find(v) ?: return esc(v)
        val widthPx = resolution.groupValues[1].toIntOrNull() ?: return esc(v)
        val heightPx = resolution.groupValues[2].toIntOrNull() ?: return esc(v)
        val size = PanelInfo.physicalDisplaySize(widthPx, heightPx, profile.physicalPpi) ?: return esc(v)
        val inchS = "%.1f".format(size.diagonalInches)
        val cmS = "%.1f".format(size.diagonalInches * 2.54)
        val title = "W %.1f × H %.1f cm".format(size.widthCm, size.heightCm)
        return """${esc(v)} · <span class="diag" data-in="$inchS″" data-cm="$cmS cm" """ +
            """title="${esc(title)}" onclick="diagToggle(this)">$inchS″</span>"""
    }

    // Display sizing (density + text scale) via `wm density` / `font_scale` — su panels only.
    private val density = DensityController(canSu = profile.appCanSu)
    private val interactive = InteractiveController(canSu = profile.appCanSu)
    // On-panel config revision history (ring buffer) — written on every successful apply.
    private val revisions = RevisionStore(appContext.filesDir)
    // Stored as a stop lambda over a type-inferred server local, so we never have to name Ktor's
    // EmbeddedServer<TEngine, TConfiguration> generic type (which shifts between Ktor versions).
    private var stopServer: (() -> Unit)? = null
    private val inspectLock = Any()
    @Volatile private var stopping = true
    private enum class TameMutation { TAME, UNTAME, RECOMMENDED }
    private val tameMutations: KeyedLatestDispatcher<String, TameMutation> = KeyedLatestDispatcher(
        threadName = "ha-paneld-tame",
        maxPendingKeys = 256,
        consume = ::executeTameMutation,
    )

    private fun executeTameMutation(pkg: String, mutation: TameMutation) {
        FeatureCosts.registry.setBacklog(FeatureCostOperation.TAME_MUTATION, tameMutations.pendingCount())
        val cost = FeatureCosts.registry.span(FeatureCostOperation.TAME_MUTATION).work(units = 1)
        try {
            when (mutation) {
                TameMutation.TAME -> if (!tame.tame(pkg)) cost.outcome(FeatureCostOutcome.FAILURE)
                TameMutation.UNTAME -> if (!tame.untame(pkg)) cost.outcome(FeatureCostOutcome.FAILURE)
                TameMutation.RECOMMENDED -> {
                    val tamed = tame.applyRecommended(tameProfileCandidates)
                    if (tamed.isNotEmpty()) {
                        config.setTameVendorPackages(
                            (config.tameVendorPackages.toSet() + tamed).joinToString(" "),
                        )
                        snapInvalidate()
                    }
                    cost.work(units = tamed.size.toLong())
                }
            }
        } catch (error: Exception) {
            cost.outcome(FeatureCostOutcome.FAILURE)
            Log.w(TAG, "vendor package mutation failed", error)
        } finally {
            cost.close()
        }
    }
    private sealed class RemoteControl(val key: String) {
        class Tap(val x: Float, val y: Float) : RemoteControl("tap")
        class Action(val name: String) : RemoteControl(name)
    }
    private val remoteControls: KeyedLatestDispatcher<String, RemoteControl> = KeyedLatestDispatcher(
        threadName = "ha-paneld-remote-control",
        maxPendingKeys = 8,
        consume = { _, command -> executeRemoteControl(command) },
    )
    private val clearStorageGate = GenerationSingleFlight()

    private fun executeRemoteControl(command: RemoteControl) {
        FeatureCosts.registry.setBacklog(FeatureCostOperation.REMOTE_INPUT, remoteControls.pendingCount())
        val cost = FeatureCosts.registry.span(FeatureCostOperation.REMOTE_INPUT).work(units = 1)
        val ok = runCatching {
            when (command) {
                is RemoteControl.Tap -> interactive.tap(command.x, command.y)
                is RemoteControl.Action -> when (command.name) {
                    "back" -> interactive.back()
                    "recents" -> interactive.recents()
                    "launcher" -> { system.launchLauncher(config.launcherPackage); true }
                    "admin_launcher" -> { system.launchAdminLauncher(); true }
                    "reboot" -> { system.reboot(); true }
                    "volup" -> { volume.step(up = true); true }
                    "voldn" -> { volume.step(up = false); true }
                    else -> false
                }
            }
        }.getOrDefault(false)
        if (!ok) cost.outcome(FeatureCostOutcome.FAILURE)
        cost.close()
    }
    private val pendingApks = PendingUploadStore()

    fun start() {
        stopping = false
        pendingApks.open()
        // Bind the IPv6 wildcard "::" — on Android this is dual-stack (net.ipv6.bindv6only=0), so the
        // server answers on both IPv6 and IPv4, instead of the IPv4-only default 0.0.0.0.
        val server = embeddedServer(CIO, port = config.httpPort, host = "::") {
            // 0.8.1 security: refuse any request whose SOURCE is not LAN-local. The unauthenticated control
            // surface answers on the panel's globally-routable IPv6 (dual-stack "::"), so without this it can
            // be reached from the internet whenever the home router doesn't firewall inbound IPv6 — and we
            // must not depend on that. Allow loopback / RFC1918 / link-local / ULA; global/public source 403s.
            // (Known limitation to iterate on: a LAN peer reaching the panel via its *global* v6 uses a global
            // source and is also rejected — use IPv4 on-LAN; a same-/64-prefix exception is the next refinement.)
            intercept(ApplicationCallPipeline.Plugins) {
                // Use origin.remoteAddress (the RAW peer IP), NOT remoteHost — remoteHost reverse-resolves to
                // a hostname, and forward-resolving that picks a (possibly global) address that fails the
                // RFC1918 check, 403-ing legitimate LAN clients. Verified: remoteAddress returns 192.168.x etc.
                if (!isLocalSource(call.request.origin.remoteAddress)) {
                    call.respondText("forbidden\n", status = HttpStatusCode.Forbidden)
                    return@intercept finish()
                }
                // CSRF guard: a LAN browser on a malicious page must not be able to silently drive a
                // state-changing endpoint (e.g. POST /config → MQTT takeover). Cross-origin writes carry
                // a mismatched Origin/Referer and are refused; same-origin UI fetches and header-less API
                // clients (curl / HA rest_command) pass. See OriginGuard.
                if (!OriginGuard.allowed(
                        call.request.origin.method.value,
                        call.request.headers["Origin"],
                        call.request.headers["Referer"],
                        call.request.headers["Host"],
                    )
                ) {
                    call.respondText("cross-origin refused\n", status = HttpStatusCode.Forbidden)
                    return@intercept finish()
                }
                // Anti-DNS-rebinding: pin the Host header to unrebindable values (IP / localhost /
                // *.local) + any configured names, so a rebound hostname can't read secrets or drive
                // the surface as "same-origin". See OriginGuard.hostAllowed.
                if (!OriginGuard.hostAllowed(call.request.headers["Host"], config.httpAllowedHosts)) {
                    call.respondText("host not allowed\n", status = HttpStatusCode.Forbidden)
                    return@intercept finish()
                }
            }
            routing {
                controlPlaneRoutes(
                    ControlPlaneRouteDependencies(
                        playAudio = playAudio,
                        installComponent = onInstallComponent,
                        installedComponentVersion = { name ->
                            when (name) {
                                "paneld" -> Config.VERSION
                                "companion" -> CompanionInstaller.installedPkg(appContext)?.let { pkg ->
                                    AppInstaller.installedVersion(appContext, pkg).takeIf { it.isNotBlank() }
                                }
                                "webview" -> runCatching {
                                    android.webkit.WebView.getCurrentWebViewPackage()?.versionName
                                }.getOrNull()
                                else -> null
                            }
                        },
                        buildBackup = { includeCompanion, passphrase ->
                            withContext(Dispatchers.IO) {
                                buildBackupArtifact(includeCompanion, passphrase)
                            }
                        },
                        backupFileStem = { config.panelId },
                        apkUpload = ApkUploadRouteDependencies(
                            enabled = { config.apkUploadAllowed },
                            rootAvailable = { rootOk() },
                            pending = pendingApks,
                            createStagingFile = { File.createTempFile("apk-upload-", ".apk", appContext.cacheDir) },
                            inspect = { staged ->
                                withContext(Dispatchers.IO) { AppInstaller.inspect(appContext, staged.absolutePath) }?.let {
                                    UploadedApkIdentity(it.pkg, it.version, it.signerSha256)
                                }
                            },
                            startInstall = { claimed, progress ->
                                val apk = claimed.file
                                val job = scope.launch {
                                    val result = runCatching { AppInstaller.installLocalApk(appContext, apk) }
                                        .getOrElse { "error: ${it.message}" }
                                    Log.i(TAG, "APK upload install: $result")
                                    InstallProgress.finish(progress, result)
                                }
                                job.invokeOnCompletion { cause -> if (cause != null) apk.delete() }
                                InstallProgress.finishOnFailure(progress, job)
                            },
                        ),
                    ),
                )
                get("/") {
                    call.respondText(infoHtml(), ContentType.Text.Html)
                }
                // Static front-end assets (externalised from the Kotlin string so CI can lint them).
                get("/info.js") {
                    call.response.headers.append("Cache-Control", "no-cache")  // assets iterate; always serve fresh
                    call.respondText(asset("info.js"), ContentType.Application.JavaScript)
                }
                get("/info.css") {
                    call.response.headers.append("Cache-Control", "no-cache")
                    call.respondText(asset("info.css"), ContentType.Text.CSS)
                }
                get("/icon.svg") {
                    call.respondText(asset("icon.svg"), ContentType.Image.SVG)
                }
                get("/favicon.svg") {
                    call.respondText(asset("favicon.svg"), ContentType.Image.SVG)
                }
                // Generic bundled-asset server for the redesigned UI (page scripts + vendored libs).
                get("/assets/{f...}") {
                    val rel = call.parameters.getAll("f")?.joinToString("/").orEmpty()
                    val body = if (rel.isEmpty() || rel.contains("..")) null else runCatching { asset(rel) }.getOrNull()
                    if (body == null) {
                        call.respondText("not found\n", status = HttpStatusCode.NotFound)
                    } else {
                        val ct = when {
                            rel.endsWith(".js") -> ContentType.Application.JavaScript
                            rel.endsWith(".css") -> ContentType.Text.CSS
                            rel.endsWith(".svg") -> ContentType.Image.SVG
                            rel.endsWith(".json") -> ContentType.Application.Json
                            else -> ContentType.Text.Plain
                        }
                        call.response.headers.append("Cache-Control", "no-cache")
                        call.respondText(body, ct)
                    }
                }
                // Tabbed multi-page shell. `/` stays the existing dashboard (now with a tab bar); the
                // other tabs are dedicated pages that consume /api/v1.
                get("/configure") { call.respondText(page("configure", "Configure", configureBody()), ContentType.Text.Html) }
                get("/profiles") { call.respondText(page("profiles", "Profile", profilesBody()), ContentType.Text.Html) }
                // The experimental remote-control page is withheld from 0.9.2. Keep old bookmarks
                // useful while its tap-injection UX is reviewed for a later release.
                get("/test") { call.respondRedirect("/") }
                get("/install") { call.respondText(page("install", "Install", installBody()), ContentType.Text.Html) }
                get("/fleet") { call.respondText(page("fleet", "Fleet", fleetBody()), ContentType.Text.Html) }
                get("/logs") { call.respondText(page("logs", "Logs", logsBody()), ContentType.Text.Html) }
                get("/entities") { call.respondText(page("entities", "Entities", entitiesBody()), ContentType.Text.Html) }
                // Self-contained REST API explorer (no Swagger-UI CDN bundle) + the OpenAPI spec it
                // renders — the spec also imports into Swagger/Postman for fleet tooling.
                get("/api") {
                    val html = asset("api.html").replace(
                        "<title>ha-paneld · REST API</title>",
                        "<title>${esc(panelBrowserTitle(config.friendlyName, "REST API"))}</title>",
                    )
                    call.respondText(html, ContentType.Text.Html)
                }
                get("/health") {
                    call.respondText("ha-paneld ${Config.VERSION} panel=${config.panelId} build=${buildToken()} cfg=${configConcurrencyHash()}\n")
                }
                // Pre-0.8.5 flat machine endpoints → 308 to their /api/v1 homes.
                legacyRedirects()

                // ---- /api/v1 — the canonical machine API (0.8.5 conformity pass). Every machine
                // endpoint lives here; the pre-0.8.5 flat paths 308 to their v1 homes (method + body
                // preserved), except /health and /play which stay REAL at the root too — they're the
                // external "contract" endpoints called by plain curl (no -L) from HA automations and
                // monitors. Human pages + static assets stay top-level. ----
                route("/api/v1") {
                    provisioningReader?.let { reader ->
                        provisioningRoutes(
                            ProvisioningRouteDependencies(
                                reader = reader,
                                activation = provisioningActivation,
                            ),
                        )
                    }
                    profileAdmin?.let { admin ->
                        profileRoutes(
                            ProfileRouteDependencies(
                                admin = admin,
                                requestRestart = onProfileRestart,
                                restartAllowed = profileRestartAllowed,
                                abortPendingRestart = onProfileRestartAbort,
                                readOnly = ProfileRouteReadOnlyProviders(
                                    template = profileTemplate,
                                    deviceDraft = profileDeviceDraft,
                                    latestReport = profileReport,
                                    probe = profileProbe,
                                ),
                            ),
                        )
                    } ?: unavailableProfileRoutes()
                    get("/health") {
                        call.respondText("ha-paneld ${Config.VERSION} panel=${config.panelId} build=${buildToken()} cfg=${configConcurrencyHash()}\n")
                    }
                    get("/config") { call.respondText(configJson(), ContentType.Application.Json) }
                    post("/config") { handleConfigPost(call) }
                    get("/config/schema") { call.respondText(configSchemaJson(), ContentType.Application.Json) }
                    // Versioned config bundle: backup (export) and validated restore/deploy (import).
                    get("/config/export") { handleConfigExport(call) }
                    post("/config/import") { handleConfigImport(call) }
                    // Restore a .hpb bundle (raw body; passphrase in the X-Backup-Passphrase header so it
                    // never lands in a query log). ?dry_run=1 decrypts + reports the contents WITHOUT writing.
                    // A real restore is DESTRUCTIVE (rewrites config; force-stops + rewrites the Companion DB).
                    post("/restore") { handleRestore(call) }
                    // On-panel revision history + rollback.
                    get("/config/revisions") { call.respondText(revisionsJson(), ContentType.Application.Json) }
                    post("/config/revisions/{id}/restore") {
                        val id = call.parameters["id"]?.toLongOrNull()
                        if (id == null) call.respondText("bad-id\n", status = HttpStatusCode.BadRequest)
                        else handleRevisionRestore(call, id)
                    }
                    get("/perf") {
                        if (!admitActiveRead(call)) return@get
                        PerfReader.touch()
                        call.respondText(PerfReader.json(), ContentType.Application.Json)
                    }
                    // Sparse A/B harvesters use this projection without activating the 2 s sampler whose
                    // own CPU and process probes would perturb the feature burden being measured.
                    get("/perf/costs") {
                        call.respondText(FeatureCosts.json(), ContentType.Application.Json)
                    }
                    get("/perf/history") {
                        val hours = call.request.queryParameters["hours"]?.toIntOrNull() ?: 24
                        call.respondText(entityLearning.performanceHistoryJson(hours), ContentType.Application.Json)
                    }
                    // Experimental built-in-renderer entity filter. The exact ids are accepted at runtime
                    // but never echoed, logged, or included in config exports; status is count+hash.
                    get("/dashboard/entity-filter") {
                        call.respondText(entityFilterStatusJson(), ContentType.Application.Json)
                    }
                    post("/dashboard/entity-filter") { handleEntityFilterPost(call) }
                    get("/dashboard/entities") {
                        call.respondText(
                            entityLearning.entitiesJson(
                                call.request.queryParameters["q"].orEmpty(),
                                call.request.queryParameters["filter"] ?: "active",
                                call.request.queryParameters["limit"]?.toIntOrNull() ?: 100,
                                call.request.queryParameters["offset"]?.toIntOrNull() ?: 0,
                                call.request.queryParameters["sort"] ?: "entity_id",
                                call.request.queryParameters["dir"] ?: "asc",
                            ),
                            ContentType.Application.Json,
                        )
                    }
                    get("/dashboard/entities/sync") { call.respondText(entityLearning.statusJson(), ContentType.Application.Json) }
                    get("/dashboard/entities/issues") {
                        call.respondText(entityLearning.issuesJson(), ContentType.Application.Json)
                    }
                    post("/dashboard/entities/issues") {
                        val obj = receiveEntityAdminJson(call) ?: return@post
                        if (!obj.has("fingerprint") || !obj.has("ignored")) {
                            return@post call.respondText(
                                "fingerprint and ignored are required\n", status = HttpStatusCode.BadRequest,
                            )
                        }
                        val response = runCatching {
                            entityLearning.setIssueIgnored(obj.optString("fingerprint"), obj.optBoolean("ignored"))
                        }.getOrElse {
                            return@post call.respondText("invalid issue override: ${it.message}\n", status = HttpStatusCode.BadRequest)
                        }
                        call.respondText(response, ContentType.Application.Json)
                    }
                    post("/dashboard/entities/sync") {
                        if (entityLearning.syncNow("manual")) {
                            call.respondText(entityLearning.statusJson(), ContentType.Application.Json, HttpStatusCode.Accepted)
                        } else call.respondText("synchronization already running\n", status = HttpStatusCode.Conflict)
                    }
                    post("/dashboard/entities/activate") {
                        val obj = receiveEntityAdminJson(call, allowBlank = true) ?: return@post
                        val response = runCatching { entityLearning.activate(obj.optBoolean("confirm", false)) }.getOrElse {
                            return@post call.respondText("activation failed: ${it.message}\n", status = HttpStatusCode.BadRequest)
                        }
                        val status = if (JSONObject(response).optBoolean("confirmation_required")) HttpStatusCode.Conflict else HttpStatusCode.OK
                        call.respondText(response, ContentType.Application.Json, status)
                    }
                    post("/dashboard/entities/policy") {
                        val obj = receiveEntityAdminJson(call) ?: return@post
                        if (!obj.has("auto_static") || !obj.has("auto_runtime")) {
                            return@post call.respondText("auto_static and auto_runtime are required\n", status = HttpStatusCode.BadRequest)
                        }
                        val response = runCatching {
                            entityLearning.setPromotionPolicy(obj.optBoolean("auto_static"), obj.optBoolean("auto_runtime"))
                        }.getOrElse {
                            return@post call.respondText("invalid policy: ${it.message}\n", status = HttpStatusCode.BadRequest)
                        }
                        call.respondText(response, ContentType.Application.Json)
                    }
                    post("/dashboard/entities/override") {
                        val obj = receiveEntityAdminJson(call) ?: return@post
                        val response = runCatching {
                            entityLearning.setOverride(
                                obj.optString("entity_id"), obj.optString("override"), obj.optBoolean("force", false),
                            )
                        }.getOrElse {
                            return@post call.respondText("invalid override: ${it.message}\n", status = HttpStatusCode.BadRequest)
                        }
                        val status = if (JSONObject(response).optBoolean("confirmation_required")) HttpStatusCode.Conflict else HttpStatusCode.OK
                        call.respondText(response, ContentType.Application.Json, status)
                    }
                    post("/dashboard/entities/overrides") {
                        val obj = receiveEntityAdminJson(call) ?: return@post
                        val ids = obj.optJSONArray("entity_ids")?.let { array ->
                            (0 until array.length()).map { array.optString(it) }
                        }.orEmpty()
                        val response = runCatching {
                            entityLearning.setOverrides(
                                ids, obj.optBoolean("all_candidates", false), obj.optString("override"), obj.optBoolean("force", false),
                            )
                        }.getOrElse {
                            return@post call.respondText("invalid bulk override: ${it.message}\n", status = HttpStatusCode.BadRequest)
                        }
                        val status = if (JSONObject(response).optBoolean("confirmation_required")) HttpStatusCode.Conflict else HttpStatusCode.OK
                        call.respondText(response, ContentType.Application.Json, status)
                    }
                    post("/dashboard/entities/reset") {
                        val obj = receiveEntityAdminJson(call, allowBlank = true) ?: return@post
                        val response = runCatching {
                            entityLearning.resetEvidence(
                                confirm = obj.optBoolean("confirm", false),
                                clearFilter = obj.optBoolean("clear_filter", false),
                            )
                        }.getOrElse {
                            return@post call.respondText("reset failed: ${it.message}\n", status = HttpStatusCode.Conflict)
                        }
                        val status = if (JSONObject(response).optBoolean("confirmation_required")) HttpStatusCode.Conflict else HttpStatusCode.OK
                        call.respondText(response, ContentType.Application.Json, status)
                    }
                    get("/dashboard/entities/export") {
                        call.response.headers.append("Content-Disposition", "attachment; filename=ha-paneld-entities.json")
                        call.respondTextWriter(ContentType.Application.Json) {
                            entityLearning.writeExportJson(this)
                        }
                    }
                    get("/proximity") { call.respondText(sensors.proximityJson(), ContentType.Application.Json) }
                    // Live Sensors card: last-published values + live extras. Volume is the current
                    // media-stream percent; brightness is the system setting (0-255, -1 unknown).
                    get("/sensors") {
                        // Effective backlight first (reflects firmware dims); raw setting as fallback.
                        val bright = effectiveBrightness().takeIf { it >= 0 } ?: runCatching {
                            android.provider.Settings.System.getInt(appContext.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS)
                        }.getOrDefault(-1)
                        call.respondText(
                            """{${sensors.valuesJson()},"volume_pct":${runCatching { volume.getPercent() }.getOrDefault(-1)},"brightness":$bright}""",
                            ContentType.Application.Json,
                        )
                    }
                    // LAN ha-paneld panels for the header panel switcher — a cheap, non-blocking snapshot of
                    // the live mDNS roster (a background listener keeps it converged + fresh; see browsePeers).
                    get("/peers") {
                        call.respondText(peersJson(peers()), ContentType.Application.Json)
                    }
                    // Hydration payload for the dashboard (see infoJson) — the one place the probe
                    // suite actually runs; cached + single-flight, so concurrent viewers share it.
                    get("/info") {
                        call.respondText(withContext(Dispatchers.IO) { infoJson() }, ContentType.Application.Json)
                    }
                    get("/diag") {
                        // Last-known snapshot facts, refreshed in the background: the [panel] block is
                        // ancillary context, and a blocking snapCache.get() re-ran the FULL probe suite
                        // whenever the snapshot was stale (>12s on a PX30 for a "simple" text page).
                        val facts = withContext(Dispatchers.IO) { snapStaleOk().facts }
                        call.respondText(
                            DiagReader.dump(appContext, profile, facts, radioStatus()),
                            ContentType.Text.Plain,
                        )
                    }
                    // Live log tail as Server-Sent Events (?source=app|system). Feeds the Logs tab;
                    // also curl-able (`curl -N .../api/v1/logs/stream`). Lines are pre-redacted.
                    get("/logs/stream") {
                        if (admitActiveRead(call)) handleLogStream(call)
                    }
                    // Health + capabilities as JSON (warnings as ready-to-render HTML) — feeds every
                    // variant's Install/health section client-side. ?refresh=1 forces a fresh GitHub
                    // update check first (the "Run health audit" button), else the cached result is used.
                    get("/status") {
                        if (call.request.queryParameters["refresh"] == "1") {
                            if (!admitActiveRead(call)) return@get
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    UpdateChecker.check(
                                        appContext,
                                        config.updateChannel,
                                        config.companionUpdateChannel,
                                        profile.companionMaxVersion,
                                    )
                                }
                            }
                        }
                        call.respondText(statusJson(), ContentType.Application.Json)
                    }
                    // Dismiss a component update from the DASHBOARD banner only (per-version; re-surfaces when
                    // a newer release ships). The Install tab still lists it. See Config.ignoreUpdate.
                    post("/updates/ignore") {
                        val p = receiveBoundedFormParameters(call) ?: return@post
                        val label = p["label"]?.trim().orEmpty()
                        val version = p["version"]?.trim().orEmpty()
                        if (label.isEmpty() || version.isEmpty())
                            call.respondText("""{"ok":false}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                        else { config.ignoreUpdate(label, version); call.respondText("""{"ok":true}""", ContentType.Application.Json) }
                    }
                    // Recent installable versions for a component's picker (name ∈ {paneld,companion};
                    // channel ∈ {stable,prerelease}). Up to 10, newest first, each with a release-notes URL.
                    get("/install/versions") {
                        val name = call.request.queryParameters["name"]?.trim().orEmpty()
                        val channel = call.request.queryParameters["channel"]?.trim()?.ifEmpty { "stable" } ?: "stable"
                        val vers = withContext(Dispatchers.IO) {
                            when (name) {
                                "paneld" -> SelfUpdater.versions(channel)
                                "companion" -> CompanionInstaller.versions(
                                    channel,
                                    maxVersion = profile.companionMaxVersion,
                                )
                                else -> emptyList()
                            }
                        }
                        val arr = vers.joinToString(",") { v ->
                            """{"version":${jsonStr(v.version)},"tag":${jsonStr(v.tag)},"notes":${jsonStr(v.notesUrl)},"installable":${v.installable},"apk":${jsonStr(v.apkUrl ?: "")}}"""
                        }
                        call.respondText("""{"channel":${jsonStr(channel)},"versions":[$arr]}""", ContentType.Application.Json)
                    }
                    get("/install/status") { call.respondText(InstallProgress.json(), ContentType.Application.Json) }
                    // Enable/disable the APK-upload capability (the card's toggle).
                    post("/install/apk/allow") {
                        val on = (receiveBoundedFormParameters(call) ?: return@post)["on"]
                            ?.let { it == "true" || it == "1" } ?: true
                        config.setApkUploadAllowed(on)
                        if (!on) pendingApks.clear()
                        call.respondText("""{"ok":true,"allowed":$on}""", ContentType.Application.Json)
                    }
                    // Removable apps (third-party + updated-system; excludes ha-paneld + stock system apps,
                    // which pm can't uninstall anyway) for the Uninstall card's picker.
                    get("/packages") { call.respondText(withContext(Dispatchers.IO) { packagesJson() }, ContentType.Application.Json) }
                    // Launchable apps (incl. system launchers/renderers) — populates the Configure tab's
                    // Dashboard-app / Launcher-app pickers.
                    get("/apps") { call.respondText(withContext(Dispatchers.IO) { launchableAppsJson() }, ContentType.Application.Json) }
                    // Uninstall a package over root. Guarded: never ha-paneld itself; the picker only offers
                    // removable apps. `pm uninstall` (system/vendor apps aren't removable, only disable-able
                    // via taming — a separate, safer path).
                    post("/uninstall") {
                        if (!suCache.get()) return@post call.respondText(
                            """{"ok":false,"error":"no-root"}""", ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
                        val pkg = (receiveBoundedFormParameters(call) ?: return@post)["pkg"]?.trim().orEmpty()
                        val protected = pkg == appContext.packageName || pkg == io.github.maxlyth.hapaneld.util.WebViewInstaller.WEBVIEW_PKG
                        if (pkg.isEmpty() || protected || !AndroidInput.isPackage(pkg))
                            return@post call.respondText("""{"ok":false,"error":"bad-package"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                        val progress = InstallProgress.start("Uninstall") ?: return@post call.respondText(
                            """{"ok":false,"error":"busy"}""", ContentType.Application.Json, HttpStatusCode.Conflict)
                        var progressResult = "uninstall cancelled"
                        try {
                            val (out, path) = withContext(Dispatchers.IO) {
                                Su.runOutput("pm uninstall $pkg")?.trim() to
                                    Su.runOutput("pm path $pkg 2>/dev/null")?.trim()
                            }
                            // Empty stdout can be a real persistent-shell success, but null means the probe failed.
                            val ok = uninstallSucceeded(out, path)
                            progressResult = if (ok) "uninstalled $pkg" else "uninstall failed: $pkg"
                            if (ok) Log.i(TAG, "uninstalled $pkg")
                            val result = out?.ifEmpty { if (ok) "removed" else "uninstall failed" } ?: "uninstall failed"
                            call.respondText("""{"ok":$ok,"result":${jsonStr(result)}}""", ContentType.Application.Json)
                        } finally {
                            InstallProgress.finish(progress, progressResult)
                        }
                    }
                    // EFR32 radio status (Install-tab Radio card). {present, status}. present=false → no radio.
                    get("/radio") {
                        val st = withContext(Dispatchers.IO) { radioStatus() }
                        val body = if (st == null) """{"present":false,"status":"none"}""" else JSONObject()
                            .put("present", true)
                            .put("status", st.publicSummary())
                            .put("state", st.state.wireValue)
                            .put("attributes", JSONObject(st.mqttAttributes()))
                            .toString()
                        call.respondText(body, ContentType.Application.Json)
                    }
                    // Auto-heal the System WebView (download + install the profile's recommended build).
                    // Fire-and-forget: the install runs off-thread (large download); the client refreshes.
                    post("/webview/heal") {
                        val status = if (onInstallComponent("webview", "reinstall", "")) "started" else "busy"
                        call.respondText("""{"status":"$status"}""", ContentType.Application.Json)
                    }
                    // Clear the built-in renderer's browsing data (localStorage/IndexedDB/caches/cookies)
                    // — the remote heal for a corrupted-storage dashboard that survives plain reloads.
                    // Sign-in is NOT stored there (the external-auth bridge holds the token in Config),
                    // so this never logs the panel out. Relaunches the built-in renderer when it's the
                    // active dashboard so it comes back on a clean slate. WebView APIs are UI-thread-only.
                    post("/dashboard/clear-storage") {
                        val token = clearStorageGate.claim()
                        if (token == null) {
                            call.respondText(
                                """{"status":"busy"}""",
                                ContentType.Application.Json,
                                if (stopping) HttpStatusCode.ServiceUnavailable else HttpStatusCode.Conflict,
                            )
                            return@post
                        }
                        val posted = android.os.Handler(android.os.Looper.getMainLooper()).post storage@{
                            val cost = FeatureCosts.registry.span(FeatureCostOperation.DASHBOARD_STORAGE_CLEAR)
                            try {
                                if (!clearStorageGate.isCurrent(token) || stopping) {
                                    cost.outcome(FeatureCostOutcome.CANCELLED)
                                    return@storage
                                }
                                android.webkit.WebStorage.getInstance().deleteAllData()
                                android.webkit.CookieManager.getInstance().removeAllCookies(null)
                                // The HTTP resource cache is per-application but only reachable through a
                                // WebView instance — a throwaway one clears it for every WebView we host
                                // (a corrupted cached asset is exactly what this heal exists for).
                                runCatching {
                                    android.webkit.WebView(appContext).apply {
                                        settings.allowContentAccess = false
                                        settings.allowFileAccess = false
                                        clearCache(true)
                                        destroy()
                                    }
                                }
                                if (config.dashboardPackage == "builtin" && !stopping) {
                                    // Privileged-first relaunch (BAL rules block a plain startActivity
                                    // from a service context) — off the main thread, it may shell out.
                                    scope.launch { runCatching { system.reloadDashboard(SystemController.BUILTIN_DASHBOARD) } }
                                }
                            } catch (error: Exception) {
                                cost.outcome(FeatureCostOutcome.FAILURE)
                                Log.w(TAG, "dashboard storage clear failed", error)
                            } finally {
                                clearStorageGate.finish(token)
                                cost.close()
                            }
                        }
                        if (!posted) {
                            clearStorageGate.finish(token)
                            FeatureCosts.registry.recordDropped(FeatureCostOperation.DASHBOARD_STORAGE_CLEAR)
                            call.respondText(
                                """{"status":"stopping"}""",
                                ContentType.Application.Json,
                                HttpStatusCode.ServiceUnavailable,
                            )
                            return@post
                        }
                        call.respondText(
                            """{"status":"started"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.Accepted,
                        )
                    }
                    // Repair a Companion server row with an empty internal_url (HA 2026.7 "Missing Host
                    // header" incident). Fire-and-forget: the repair force-stops + relaunches the Companion
                    // off-thread; invalidate the health cache so the warning clears on the next poll.
                    post("/companion/repair-url") {
                        val started = onRepairCompanionUrl()
                        if (started) companionUrlCache.invalidate()
                        call.respondText("""{"status":"${if (started) "started" else "busy"}"}""", ContentType.Application.Json)
                    }
                    // Per-panel Canvas dashboard layout (opaque Gridstack JSON, stored in Config).
                    get("/ui/layout") {
                        call.respondText("""{"layout":${jsonStr(config.uiDashboardLayout)}}""", ContentType.Application.Json)
                    }
                    post("/ui/layout") {
                        config.uiDashboardLayout = (receiveBoundedFormParameters(
                            call,
                            MAX_CONFIG_POST_BODY_BYTES,
                        ) ?: return@post)["layout"].orEmpty()
                        call.respondText("""{"ok":true}""", ContentType.Application.Json)
                    }
                    // Inject a tap at device pixel (x,y). The UI is withheld pending a remote-control
                    // review, but the API remains available for established automation and testing.
                    post("/input") {
                        val q = receiveBoundedFormParameters(call) ?: return@post
                        val x = q["x"]?.trim()?.toFloatOrNull()
                        val y = q["y"]?.trim()?.toFloatOrNull()
                        if (x == null || y == null || !x.isFinite() || !y.isFinite() || x < 0f || y < 0f) {
                            call.respondText("bad-coords\n", status = HttpStatusCode.BadRequest)
                        } else {
                            respondRemoteAdmission(call, RemoteControl.Tap(x, y))
                        }
                    }
                    // On-screen Controls card (software navbar) for panels with no physical nav bar.
                    post("/action") {
                        val a = (receiveBoundedFormParameters(call) ?: return@post)["a"]
                        if (a !in REMOTE_ACTIONS) call.respondText("bad-action\n", status = HttpStatusCode.BadRequest)
                        else respondRemoteAdmission(call, RemoteControl.Action(a!!))
                    }
                    // Debug-only sensor trace (RAM ring buffer, on by default) for fit-testing the
                    // auto-brightness + proximity filters. CSV by default (drop into a plot); ?format=json
                    // for programmatic use / a future on-panel chart. Not an HA/MQTT surface.
                    get("/sensortrace") {
                        if (call.request.queryParameters["format"] == "json") {
                            call.respondText(io.github.maxlyth.hapaneld.sensors.SensorTrace.toJson(), ContentType.Application.Json)
                        } else {
                            call.respondText(io.github.maxlyth.hapaneld.sensors.SensorTrace.toCsv(), ContentType("text", "csv"))
                        }
                    }
                    // Live panel screenshot via root `screencap` (LAN-only like the rest of this surface).
                    // Embedded scaled in the info page + linkable full-size; also usable as an HA camera
                    // still_image_url. Captured on demand — no background polling.
                    get("/screenshot.png") {
                        if (!admitActiveRead(call)) return@get
                        val png = withContext(Dispatchers.IO) { interactive.screenshot() }
                        if (png != null && png.isNotEmpty()) {
                            call.respondBytes(png, ContentType.Image.PNG)
                        } else {
                            call.respondText("screenshot-unavailable\n", status = HttpStatusCode.ServiceUnavailable)
                        }
                    }
                    get("/openapi.json") {
                        call.respondText(asset("openapi.json"), ContentType.Application.Json)
                    }
                    // Live proximity state for the tuning UI (raw never goes to HA; it lives here).
                    get("/proximity") {
                        call.respondText(sensors.proximityJson(), ContentType.Application.Json)
                    }
                    // step=near|far -> snapshot the current raw into that calibration slot.
                    post("/proximity/capture") {
                        val step = (receiveBoundedFormParameters(call) ?: return@post)["step"]
                        val raw = sensors.lastRaw
                        when {
                            step != "near" && step != "far" ->
                                call.respondText("bad-step\n", status = HttpStatusCode.BadRequest)
                            raw.isNaN() ->
                                call.respondText("no-reading\n", status = HttpStatusCode.BadRequest)
                            else -> {
                                config.captureProximity(step, raw)
                                sensors.reevaluate()
                                call.respondText(sensors.proximityJson(), ContentType.Application.Json)
                            }
                        }
                    }
                    // v=<float> -> manual threshold fine-tune (overrides the captured midpoint).
                    post("/proximity/threshold") {
                        val v = (receiveBoundedFormParameters(call) ?: return@post)["v"]?.toFloatOrNull()
                        if (!validProximityThreshold(v)) {
                            call.respondText("bad-value\n", status = HttpStatusCode.BadRequest)
                        } else {
                            config.setProximityThreshold(v!!)
                            sensors.reevaluate()
                            call.respondText(sensors.proximityJson(), ContentType.Application.Json)
                        }
                    }
                    // s=HIGH|MEDIUM|LOW -> hysteresis band width (flap resistance).
                    post("/proximity/sensitivity") {
                        config.setProximitySensitivity(
                            (receiveBoundedFormParameters(call) ?: return@post)["s"].orEmpty(),
                        )
                        sensors.reevaluate()
                        call.respondText(sensors.proximityJson(), ContentType.Application.Json)
                    }
                    post("/proximity/reset") {
                        config.resetProximityCalibration()
                        sensors.reevaluate()
                        call.respondText(sensors.proximityJson(), ContentType.Application.Json)
                    }
                    // Per-package vendor taming from the Vendor packages card. action=tame adds the package to
                    // the blocklist and tames it now; action=untame removes it and re-enables it. The work is
                    // privileged + slow, so it runs off-thread and the browser gets a short auto-reload back to
                    // the info page (the row's new state shows on reload).
                    post("/tame") {
                        val p = receiveBoundedFormParameters(call) ?: return@post
                        // One-click "Tame all recommended" (the profile's defaultTame set) — no pkg needed.
                        // Persists the actually-tamed packages so they re-apply on boot; tame() is guarded
                        // (hands over the home role + refuses to strand), so this never leaves the panel homeless.
                        if (p["action"]?.trim() == "recommended") {
                            if (!submitTameMutation(RECOMMENDED_TAME_KEY, TameMutation.RECOMMENDED)) {
                                call.respondText("vendor mutation queue busy\n", status = HttpStatusCode.ServiceUnavailable)
                                return@post
                            }
                            call.respondText(
                                "<!doctype html><meta charset=utf-8><meta http-equiv=refresh content='2;url=/configure'>" +
                                    "<body style='font-family:system-ui;background:#111;color:#eee;padding:20px'>" +
                                    "applying recommended vendor-app taming…</body>",
                                ContentType.Text.Html,
                            )
                            return@post
                        }
                        val pkg = p["pkg"]?.trim().orEmpty()
                        val untame = p["action"]?.trim() == "untame"
                        // Re-enable is always allowed; taming is refused for protected packages (the brick-guard
                        // — critical AOSP names, vendor-renamed persistent system services, launchers, the IME)
                        // so a hand-typed package name can't disable something the panel needs.
                        if (!AndroidInput.isPackage(pkg) || (!untame && tame.isProtected(pkg))) {
                            call.respondText("invalid or protected package\n", status = HttpStatusCode.BadRequest)
                            return@post
                        }
                        val mutation = if (untame) TameMutation.UNTAME else TameMutation.TAME
                        if (!submitTameMutation(pkg, mutation)) {
                            call.respondText("vendor mutation queue busy\n", status = HttpStatusCode.ServiceUnavailable)
                            return@post
                        }
                        val next = config.tameVendorPackages.toMutableSet()
                        if (untame) next.remove(pkg) else next.add(pkg)
                        config.setTameVendorPackages(next.joinToString(" "))
                        snapInvalidate()
                        val verb = if (untame) "re-enabling" else "taming"
                        call.respondText(
                            "<!doctype html><meta charset=utf-8><meta http-equiv=refresh content='2;url=/configure'>" +
                                "<body style='font-family:system-ui;background:#111;color:#eee;padding:20px'>" +
                                "$verb ${esc(pkg)}…</body>",
                            ContentType.Text.Html,
                        )
                    }
                    // The "Find a package…" picker pop-up content: an on-demand, grouped list of packages a
                    // non-expert might want to control — Recommended (profile) / Other apps / Using the most
                    // CPU. Lazy (only built when the dialog opens) and excludes what's already tamed (the card).
                    get("/tame/suggest") {
                        if (!admitActiveRead(call)) return@get
                        PerfReader.touch()   // keep the CPU sampler warm so the "most CPU" group can populate
                        val groups = runCatching {
                            tame.suggestionGroups(tameProfileCandidates, config.tameVendorPackages.toSet(), PerfReader.topNames())
                        }.getOrDefault(emptyList())
                        val frag = if (groups.isEmpty())
                            """<p class="note">No other packages found — you can still tame one by name.</p>"""
                        else groups.joinToString("\n") { g ->
                            val items = if (g.items.isEmpty())
                                """<p class="note" style="margin:0 0 4px;color:#666">— none —</p>"""
                            else g.items.joinToString("\n") { tameRowHtml(it) }
                            """<h4 style="margin:14px 0 1px">${esc(g.title)}</h4>""" +
                                """<p class="note" style="margin:0 0 4px">${esc(g.hint)}</p>$items"""
                        }
                        // One-click "Tame all recommended", shown only when there's an active recommended pick.
                        val hasRec = groups.any { g -> g.items.any { it.recommended && !it.blocked && !it.disabled && it.installed } }
                        val recBtn = if (hasRec)
                            """<form method="post" action="/api/v1/tame" style="margin:0 0 12px"><input type="hidden" name="action" value="recommended"><button type="submit" style="background:#2e6b3f;border-color:#2e6b3f">✓ Tame all recommended</button> <span class="note" style="font-size:.8em">the badged first-picks below, in one click</span></form>"""
                            else ""
                        call.respondText(recBtn + frag, ContentType.Text.Html)
                    }
                    post("/display/density") {
                        val p = receiveBoundedFormParameters(call) ?: return@post
                        val action = p["action"]                          // "reset" | "rec" (buttons)
                        val d = p["density"]?.trim()?.toIntOrNull()       // custom density (Apply)
                        val f = p["font"]?.trim()?.toFloatOrNull()        // custom font scale (Apply)
                        val ok = when (action) {
                            "reset" -> DensityController.allApplied(density.reset(), density.resetFontScale())
                            "rec" -> DensityController.allApplied(
                                recommendedDensity?.let { density.set(it) },
                                recommendedFontScale?.let { density.setFontScale(it) },
                            )
                            else -> {  // Apply: set whichever fields were provided
                                DensityController.allApplied(
                                    d?.let { density.set(it) },
                                    f?.let { density.setFontScale(it) },
                                )
                            }
                        }
                        // Prime the density cache with the KNOWN result so the redirected /configure shows it
                        // at once — reading `wm density` back immediately after a change can still return the
                        // pre-write override for a second or two (the change is async), which flashed a stale
                        // value on the page until a manual reload. Only the just-changed field could race, so
                        // we take the value we set (d / recommendedDensity / base) and only re-read the
                        // unchanged fields (which are stable).
                        val base = density.base()
                        val postDpi = when (action) {
                            "reset" -> base
                            "rec" -> recommendedDensity ?: density.current()
                            else -> d ?: density.current()
                        }
                        val postFont = when (action) {
                            "reset" -> 1.0f
                            "rec" -> recommendedFontScale ?: density.fontScale()
                            else -> f ?: density.fontScale()
                        }
                        snapInvalidate()
                        if (ok) densityCache.set(Triple(postDpi, base, postFont))
                        call.respondText(
                            "<!doctype html><meta charset=utf-8><meta http-equiv=refresh content='1;url=/configure'>" +
                                "<body style='font-family:system-ui;background:#111;color:#eee;padding:20px'>" +
                                (if (ok) "display density applied" else "density unchanged") + "…</body>",
                            ContentType.Text.Html,
                        )
                    }
                    // 1-click WebView DevTools: expose the dashboard's CDP socket to the LAN (root relay)
                    // so the user can chrome://inspect with no adb. See CdpRelay.
                    get("/inspect") {
                        call.respondText(inspectJson(if (CdpRelay.running) "started" else "off"), ContentType.Application.Json)
                    }
                    post("/inspect/start") {
                        val status = synchronized(inspectLock) {
                            if (stopping) "off" else CdpRelay.start(appContext)
                        }
                        call.respondText(inspectJson(status), ContentType.Application.Json)
                    }
                    post("/inspect/stop") {
                        synchronized(inspectLock) { if (CdpRelay.running) CdpRelay.stop() }
                        call.respondText(inspectJson("off"), ContentType.Application.Json)
                    }
                }
            }
        }
        // Treat the bind as required startup, not a best-effort sidecar. A failure must close this
        // generation's admission and reach the service runtime owner, which records FAILED and prevents
        // a staged profile from being marked healthy without its management/control surface.
        try {
            startOwnedHttpServer(
                start = { server.start(wait = false) },
                stop = { server.stop(500, 1500) },
                closeIngress = pendingApks::close,
            )
            stopServer = { server.stop(500, 1500) }
            Log.i(TAG, "HTTP listening on :${config.httpPort}")
        } catch (e: Exception) {
            // HTTP is part of the service's required control plane. Propagate a bind/start failure to the
            // runtime owner so this generation becomes FAILED and a staged profile cannot be marked healthy.
            stopping = true
            Log.e(TAG, "HTTP bind on :${config.httpPort} failed", e)
            throw e
        }
        // Pre-warm the probe snapshot so even the first dashboard visit usually renders complete.
        scope.launch(Dispatchers.IO) { runCatching { snapCache.get() } }
    }

    fun stop() {
        stopping = true
        clearStorageGate.close()
        stopServer?.invoke()
        stopServer = null
        // Serialize against an admitted start: teardown either prevents it or waits and then kills it.
        synchronized(inspectLock) { if (CdpRelay.running) CdpRelay.stop() }
        pendingApks.close()
        if (!tameMutations.closeAndJoin(TAME_SHUTDOWN_MS)) {
            Log.w(TAG, "vendor mutation did not drain within ${TAME_SHUTDOWN_MS}ms")
        }
        if (!remoteControls.closeAndJoin(REMOTE_CONTROL_SHUTDOWN_MS)) {
            Log.w(TAG, "remote control did not drain within ${REMOTE_CONTROL_SHUTDOWN_MS}ms")
        }
    }

    // The panel's physical resolution as a CSS aspect-ratio (e.g. "750/1334") so the Screenshot card can
    // reserve the exact box and not reflow when the image arrives. Sane portrait fallback if unavailable.
    private fun screenAspectRatio(): String = try {
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val dm = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
        if (dm.widthPixels > 0 && dm.heightPixels > 0) "${dm.widthPixels}/${dm.heightPixels}" else "3/4"
    } catch (e: Throwable) { "3/4" }

    /** 308 for a legacy flat path — preserves method, body and query so pre-0.8.5 tooling keeps working. */
    private suspend fun legacy(call: ApplicationCall, new: String) {
        val q = call.request.uri.substringAfter('?', "")
        val loc = if (q.isEmpty()) new else "$new?$q"
        call.response.headers.append("Location", loc)
        call.respondText("moved-permanently: $loc\n", status = HttpStatusCode.PermanentRedirect)
    }

    /** Every pre-0.8.5 flat machine endpoint → its /api/v1 home. GET+POST both registered — 308
     *  preserves the method, so the right verb reaches the real handler either way. */
    private fun io.ktor.server.routing.Route.legacyRedirects() {
        val map = mapOf(
            "/perf" to "/api/v1/perf",
            "/action" to "/api/v1/action",
            "/diag" to "/api/v1/diag",
            "/sensortrace" to "/api/v1/sensortrace",
            "/screenshot.png" to "/api/v1/screenshot.png",
            "/openapi.json" to "/api/v1/openapi.json",
            "/proximity" to "/api/v1/proximity",
            "/proximity/capture" to "/api/v1/proximity/capture",
            "/proximity/threshold" to "/api/v1/proximity/threshold",
            "/proximity/sensitivity" to "/api/v1/proximity/sensitivity",
            "/proximity/reset" to "/api/v1/proximity/reset",
            "/config" to "/api/v1/config",
            "/tame" to "/api/v1/tame",
            "/tame/suggest" to "/api/v1/tame/suggest",
            "/density" to "/api/v1/display/density",
            "/inspect" to "/api/v1/inspect",
            "/inspect/start" to "/api/v1/inspect/start",
            "/inspect/stop" to "/api/v1/inspect/stop",
        )
        for ((old, new) in map) {
            get(old) { legacy(call, new) }
            post(old) { legacy(call, new) }
        }
    }

    // ---- live log stream (SSE) ----

    /** Tail a [LogCapture] to the client as Server-Sent Events. Backlog first (ring snapshot while
     *  capture is already running for the shipper / another viewer, else a one-shot `logcat -d`
     *  dump), then live lines. A per-connection drop-oldest channel means a stalled browser can
     *  never back-pressure the capture; a 15s `: ping` comment detects dead peers so the
     *  subscription (and with it the logcat subprocess) is released. */
    private suspend fun handleLogStream(call: ApplicationCall) {
        val cap = when (val src = call.request.queryParameters["source"] ?: "app") {
            "app" -> logApp
            "system" -> if (suCache.get()) logSystem else {
                call.respondText("system log needs root\n", status = HttpStatusCode.ServiceUnavailable)
                return
            }
            else -> {
                call.respondText("unknown source '$src' (app|system)\n", status = HttpStatusCode.BadRequest)
                return
            }
        }
        if (cap == null) {
            call.respondText("log viewer unavailable\n", status = HttpStatusCode.NotFound)
            return
        }
        val viewer = when (val admission = cap.admitViewer()) {
            is LogCapture.ViewerAdmission.Accepted -> admission.lease
            LogCapture.ViewerAdmission.CapacityExceeded -> {
                call.response.headers.append("Retry-After", "5")
                call.respondText("too many live log viewers\n", status = HttpStatusCode.TooManyRequests)
                return
            }
            LogCapture.ViewerAdmission.Unavailable -> {
                call.respondText("log viewer unavailable\n", status = HttpStatusCode.ServiceUnavailable)
                return
            }
        }
        try {
            // Backlog BEFORE subscribing: a few ms of lines can fall in the gap, which beats the visible
            // duplicates the opposite order produces (the dump overlaps the live stream's first lines).
            val backlog = withContext(Dispatchers.IO) { cap.initialBacklog() }
            val chan = Channel<String>(capacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
            val sub = cap.subscribe { chan.trySend(it) }
            try {
                call.response.headers.append("Cache-Control", "no-cache")
                call.respondTextWriter(ContentType.Text.EventStream) {
                    for (line in backlog) write("data: $line\n\n")
                    flush()
                    while (true) {
                        val line = withTimeoutOrNull(15_000) { chan.receive() }
                        write(if (line == null) ": ping\n\n" else "data: $line\n\n")
                        flush()
                    }
                }
            } finally {
                runCatching { sub.close() }
                chan.close()
            }
        } finally {
            runCatching { viewer.close() }
        }
    }

    /** Protect GET routes whose generation starts material work from opaque cross-origin browser loads. */
    private suspend fun admitActiveRead(call: ApplicationCall): Boolean {
        if (OriginGuard.activeReadAllowed(
                call.request.headers["Origin"],
                call.request.headers["Referer"],
                call.request.headers["Host"],
                call.request.headers["Sec-Fetch-Site"],
                call.request.headers["User-Agent"],
            )
        ) return true
        call.respondText("cross-origin active read refused\n", status = HttpStatusCode.Forbidden)
        return false
    }

    // ---- tabbed multi-page shell ----

    /** The shared tab bar; [active] highlights the current page. */
    private fun navBar(active: String): String {
        fun tab(id: String, href: String, label: String): String =
            """<a href="$href"${if (id == active) " class=\"active\"" else ""}>$label</a>"""
        val entityTab = if (config.dashboardEntityLearningEnabled && config.dashboardPackage == SystemController.BUILTIN_DASHBOARD)
            tab("entities", "/entities", "Entities")
        else """<span class="disabled-tab" title="Enable Automatic dashboard entity filter with the built-in renderer">Entities</span>"""
        return "<div class=\"nav\">" +
            tab("dashboard", "/", "Dashboard") +
            tab("configure", "/configure", "Configure") +
            tab("profiles", "/profiles", "Profile") +
            entityTab +
            tab("install", "/install", "Install") +
            tab("fleet", "/fleet", "Fleet") +
            tab("logs", "/logs", "Logs") +
            """<a href="/api">API</a></div>"""
    }

    private fun entitiesBody(): String = if (!config.dashboardEntityLearningEnabled || config.dashboardPackage != SystemController.BUILTIN_DASHBOARD) {
        """<div class="cards"><div class="card"><h2>Dashboard entities <small>· experimental</small></h2>
        <p>Enable <b>Automatic dashboard entity filter</b> on Configure → Dashboard while using the built-in renderer.</p></div></div>"""
    } else """
        <div class="cards entity-cards">
          <div class="card"><h2>Entity subscription filter<span class="cardbadge skunk">skunk-works</span></h2>
            <div id="entity-status">Loading…</div>
            <div style="display:flex;gap:8px;flex-wrap:wrap;margin-top:12px">
              <button class="pbtn" id="entity-sync">Scan dashboard now</button>
              <button class="pbtn" id="entity-activate" disabled>Checking subscription…</button>
              <a class="pbtn" href="/api/v1/dashboard/entities/export">Export details</a>
            </div>
            <div id="entity-action-result" class="entity-action-result muted" role="status" aria-live="polite"></div>
            <fieldset class="entity-policy"><legend>Automatic promotion</legend>
              <label><input type="checkbox" id="entity-auto-static"> Add entities parsed from dashboard configuration</label>
              <label><input type="checkbox" id="entity-auto-runtime"> Add missing entities accessed through <code>hass.states</code></label>
              <p class="muted">Turn either source off to keep collecting its evidence without changing the live subscription.</p>
            </fieldset>
            <div style="margin-top:12px"><input id="entity-search" placeholder="Search the complete Home Assistant entity catalogue"></div>
          </div>
          <div class="card entity-issues" id="entity-issues"><h2>Entity-filter configuration checks</h2>
            <div id="entity-issues-summary" class="muted">Checking the dashboard configuration…</div>
            <div id="entity-issues-list" class="entity-issues-list"></div>
            <section id="entity-dynamic" class="entity-dynamic" hidden>
              <h3>Dynamic expressions to exercise</h3>
              <p class="muted">These expressions are not configuration errors. Exercise the relevant dashboard state so runtime observation can reveal concrete entity IDs.</p>
              <div id="entity-dynamic-list" class="entity-dynamic-list"></div>
            </section>
            <button class="pbtn" id="entity-issues-rescan" type="button">Re-scan after editing dashboard</button>
          </div>
          ${entityTableHtml("current", "Current subscribed entities", "The entities in the live Home Assistant stream. An unfiltered stream contains the complete visible catalog.", "subscribed")}
          ${entityTableHtml("suggested", "Suggested dashboard entities", "Unpinned dashboard references and runtime lookups that are not currently subscribed. Excluded entities remain visible when the dashboard still uses them. While searching, this table also shows unpinned matches from the complete Home Assistant catalogue.", "candidate")}
          ${entityTableHtml("review", "Stale or noisy entities", "Current-stream entities missing from Home Assistant, or receiving updates without being observed as dashboard dependencies. Review only; nothing is removed automatically.", "review")}
        </div>
        <script src="/assets/entities.js"></script>
    """.trimIndent()

    private fun entityTableHtml(id: String, title: String, note: String, filter: String): String = """
      <div class="card entity-list" data-filter="$filter" data-table="$id"><h2>$title</h2>
        <p class="muted">$note</p>
        <div class="entity-bulk" style="display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-bottom:10px">
          <button class="pbtn" data-bulk="pinned">Pin selected</button><button class="pbtn" data-bulk="auto">Auto selected</button><button class="pbtn" data-bulk="forced_exclude">Exclude selected</button>
          ${if (filter == "candidate") "<button class=\"pbtn\" data-all-candidates=\"true\">Pin all suggested</button>" else ""}<span class="muted entity-selected">0 selected</span>
        </div>
        <div class="tablewrap"><table class="entity-table"><thead><tr><th class="col-select"><input type="checkbox" class="entity-select-page" aria-label="Select this page"></th><th class="col-entity"><button data-sort="entity_id">Entity</button></th><th class="col-access"><button data-sort="access_1h">Accesses <small>1m / 1h / 1d</small></button></th><th class="col-rate"><button data-sort="rate_1h_bps">Data rate <small>B/s · 1m / 1h / 1d</small></button></th><th class="col-reason"><button data-sort="reasons">Reason</button></th><th class="col-last"><button data-sort="last_access">Last access</button></th><th class="col-override"><button data-sort="override">Override</button></th></tr></thead><tbody></tbody></table></div>
        <div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-top:10px">
          <button class="pbtn entity-prev">Previous</button><button class="pbtn entity-next">Next</button><span class="muted entity-msg">Loading…</span>
        </div>
      </div>
    """.trimIndent()

    /** Shared page shell (header + tab bar + body) for the non-dashboard tabs. */
    private fun page(active: String, title: String, body: String): String {
        val haLink = if (config.haLinkUrl.isNotBlank())
            """<a class="pbtn" href="${esc(config.haLinkUrl)}" target="_blank" rel="noopener">Open in HA</a>""" else ""
        return """<!doctype html><html lang="en"><head><meta charset="utf-8">
<script>/* ?theme=light|dark pins the UI theme for testing (else the browser preference rules) */
(function(){var m=location.search.match(/[?&]theme=(dark|light)\b/);if(m)document.documentElement.setAttribute("data-theme",m[1])})();</script>
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(panelBrowserTitle(config.friendlyName, title))}</title>
<link rel="icon" type="image/svg+xml" href="/favicon.svg">
<link rel="stylesheet" href="/info.css"></head><body data-build="${buildToken()}" data-cfg="${configConcurrencyHash()}"><div class="wrap">
<div class="topbar"><div class="hdr"><button id="navburger" class="navburger pbtn" aria-label="Menu">☰</button><h1><img src="/icon.svg" class="logo" alt=""><span class="brand">ha-paneld</span> <small id="pswitch" data-self-id="${esc(config.panelId)}" data-self-name="${esc(config.friendlyName)}"><span class="sep">·</span>${esc(config.friendlyName)}</small></h1>
 <span style="display:flex;gap:10px;align-items:center">$haLink<a class="gh" href="$REPO_URL" target="_blank" rel="noopener" title="ha-paneld on GitHub" aria-label="GitHub"><svg viewBox="0 0 24 24"><path d="$GH_ICON"/></svg></a></span></div>
${navBar(active)}</div>
<div id="verbar" class="setup" style="display:none">⟳ A newer ha-paneld is installed — <a href="#" onclick="location.reload();return false">reload</a> to refresh this page.</div>
$body
<script src="/assets/switcher.js"></script>
<script src="/assets/buildwatch.js"></script>
</div></body></html>"""
    }

    /** Configure tab — schema-driven form (Basic/Advanced + inline expose pips) + bundle backup/restore. */
    private fun configureBody(): String = """
<!-- Basic/Advanced tab bar hidden until every setting is assigned a Basic/Advanced tier; with it hidden
     the form shows ALL settings (configure.js defaults `advanced=true`), so nothing is lost. The tier
     machinery (SettingSpec.tier + cfgTab) stays in place — restore the bar once tiers are curated. -->
<div class="cfg-tabs" style="display:none"><button id="tab-basic" onclick="cfgTab(false)">Basic</button><button id="tab-adv" class="on" onclick="cfgTab(true)">Advanced</button></div>
<div id="cfg-status" class="muted" style="margin-bottom:10px">Loading settings…</div>
<div id="cfg-groups" class="cards"></div>
<div class="savebar"><button id="savebtn" disabled onclick="cfgSave()">Save changes</button><span id="cfg-msg" class="muted"></span></div>
<div class="cards">
${proximityCardHtml()}
${displayCardHtml()}
${tameCardHtml()}
<div class="card"><h2>Backup &amp; restore</h2>
<p class="note">Download this panel's configuration as a versioned bundle, or preview and apply one. Valid entries are applied; invalid or unsupported entries are reported and skipped.</p>
<div style="display:flex;gap:10px;flex-wrap:wrap;align-items:center">
 <a class="pbtn" href="/api/v1/config/export">⭳ Export bundle</a>
 <a class="pbtn" href="/api/v1/config/export?include_secrets=1">⭳ Export incl. secrets</a>
 <label class="pbtn" style="cursor:pointer">⭱ Import…<input type="file" id="impfile" accept="application/json" style="display:none" onchange="cfgImport(this)"></label>
</div>
<pre id="imp-result" class="muted" style="white-space:pre-wrap;margin-top:10px"></pre></div></div>
<script src="/assets/configure.js"></script>
<script src="/assets/prox.js"></script>"""

    /** Runtime profile authoring. All content is hydrated through the guarded /api/v1/profile routes. */
    private fun profilesBody(): String = """
<link rel="stylesheet" href="/assets/profiles.css">
<main class="profile-page">
  <div class="profile-toolbar" aria-label="Profile actions">
    <div class="profile-pickers">
      <label for="profile-select" class="muted">Revision</label>
      <select id="profile-select" aria-label="Profile revision"><option>Loading profiles…</option></select>
    </div>
    <div class="profile-actions">
      <div class="profile-action-group" aria-label="Profile editing">
        <button class="pbtn" id="profile-new" type="button">New</button>
        <button class="pbtn" id="profile-edit" type="button" disabled>Edit</button>
        <button class="pbtn" id="profile-fork" type="button" disabled>Fork</button>
        <label class="pbtn" for="profile-import">Import<input id="profile-import" type="file" accept=".yaml,.yml,application/yaml,text/yaml" hidden></label>
        <button class="pbtn" id="profile-export" type="button">Export</button>
      </div>
      <div class="profile-action-group" aria-label="Profile review">
        <button class="pbtn primary" id="profile-validate" type="button" disabled>Validate YAML</button>
        <button class="pbtn" id="profile-compare" type="button" disabled>Compare</button>
      </div>
      <div class="profile-action-group" aria-label="Profile activation">
        <button class="pbtn primary" id="savebtn" type="button" disabled>Save revision</button>
        <button class="pbtn primary" id="profile-activate" type="button" disabled>Activate</button>
        <button class="pbtn" id="profile-auto" type="button" disabled>Use automatic</button>
        <button class="pbtn" id="profile-rollback" type="button" disabled>Rollback</button>
        <button class="pbtn danger" id="profile-delete" type="button" disabled>Delete</button>
      </div>
    </div>
  </div>
  <div id="profile-badges" class="profile-badges" aria-label="Profile state"></div>
  <div id="profile-status" class="profile-status" role="status" aria-live="polite">Loading profiles…</div>
  <div class="profile-workspace">
    <section class="profile-editor-pane" aria-labelledby="profile-editor-title">
      <div class="profile-editor-head"><h2 id="profile-editor-title">Profile YAML</h2><span id="profile-editor-meta" class="profile-editor-meta"></span></div>
      <div id="profile-editor"></div>
    </section>
    <aside class="profile-inspector" aria-labelledby="profile-inspector-title">
      <div class="profile-inspector-head"><h2 id="profile-inspector-title">Review</h2></div>
      <div class="profile-inspector-body">
        <section><h3>Catalog and runtime</h3><div id="profile-catalog-issues" class="profile-issues"></div></section>
        <section><h3>Validation</h3><div id="profile-issues" class="profile-issues"></div></section>
        <div class="profile-guidance" id="profile-shizuku-guidance" hidden>
          <p><b>Shizuku guidance</b></p>
          <p>Direct access is the normal path. Shizuku is an optional enhancement for panels where it is already installed and approved; this profile does not install, enable, or approve it.</p>
          <p><a href="$REPO_URL/blob/main/docs/shizuku.md" target="_blank" rel="noopener">Read the setup and capability guide</a></p>
        </div>
        <section><h3>Compared with active</h3><div id="profile-diff" class="profile-diff"></div></section>
        <section><h3>Observed device facts</h3><p class="profile-report-note">Runtime observations only — these are not YAML fields. Use them to choose matching predicates and supported profile fields.</p><div id="profile-report" class="profile-report"></div></section>
        <div class="profile-draft" id="profile-generic-draft" hidden>
          <p><b>Starting from Generic?</b> Build a read-only draft from passive Android facts. Unknown hardware stays marked TODO; this does not run root, helper, Shizuku, input, or hardware commands.</p>
          <p><button class="pbtn" id="profile-draft" type="button">Generate device draft</button> <button class="pbtn" id="profile-use-draft" type="button" hidden>Copy draft to edit</button></p>
        </div>
      </div>
    </aside>
  </div>
</main>
<div id="profile-modal" class="profile-modal" role="dialog" aria-modal="true" aria-labelledby="profile-modal-title" hidden>
  <div class="profile-modal-card"><h2 id="profile-modal-title">Confirm</h2><pre id="profile-modal-detail"></pre>
    <div class="profile-modal-actions"><button class="pbtn" id="profile-modal-cancel" type="button">Cancel</button><button class="pbtn primary" id="profile-modal-confirm" type="button">Confirm</button></div>
  </div>
</div>
<script src="/assets/vendor/profile-editor/codemirror.js"></script>
<script src="/assets/profiles.js"></script>"""

    /** Install tab — software-management hub: setup warnings, managed component versions, radio firmware,
     *  on-demand health audit, and config backup. (The Capabilities card lives on the Dashboard.) */
    private fun installBody(): String {
        // Engine-aware WebView age check (a Cromite swap reports the stale OEM package version).
        val wv = PanelInfo.webViewStatus(appContext)
        val root = rootOk()
        val installer = installOk()
        val su = suCache.get()
        val companionHelper = companionHelperCache.get()
        // Same finding set as the dashboard banner (HealthAudit). Update findings are surfaced by the
        // Managed-components card below, so the top warnings show only the render-blocking states.
        val problems = HealthAudit.evaluate(
            webViewTooOld = wv.tooOld,
            webViewDisplay = wv.display,
            hasRenderer = PanelInfo.dashboardRenderers(appContext, config.dashboardPackage, config.haUrl).isNotEmpty(),
            updates = emptyList(),
        )
        // Auto-heal offer: if the profile ships a known-good WebView and we have root/daemon to install it,
        // the too-old warning gets a one-tap "Update WebView now" button (POST /api/v1/webview/heal).
        val canHeal = wv.tooOld && profile.recommendedWebView != null && root
        // A missing dashboard app can be self-healed by installing the minimal HA Companion over root — a
        // Play-managed full Companion would already count as a renderer, so NO_RENDERER + root ⇒ safe.
        val canInstallCompanion = installer
        // Two warnings not modelled by HealthAudit (crash-looping dashboard, Companion blank internal_url)
        // — shared with the dashboard banner. Here (Install tab, install.js loaded) they get inline buttons.
        val extra = adHocWarnings(inlineRepair = true)
        val warnings = extra + problems.joinToString("") { installWarning(it, canHeal, canInstallCompanion) }
        val allGood = if (problems.isEmpty() && extra.isEmpty()) """<div class="card"><p class="note">✓ No setup problems detected — this panel looks ready.</p></div>""" else ""
        return """$warnings
<div class="cards">
${componentsCardHtml(wv, root, installer)}
${apkCardHtml(root)}
${uninstallCardHtml(su)}
<div class="card" id="radiocard" style="display:none"><h2>Radio firmware</h2>
<table><tr><th>EFR32 radio</th><td id="radio-status">…</td></tr>
<tr><th>Gateway health</th><td id="radio-health">…</td></tr></table>
<p class="note">Zigbee gateway on this panel's Silicon Labs EFR32. Toggle the <b>Zigbee router</b> role on the <a href="/configure#cfg-zigbee_router">Configure</a> tab. <span class="muted">Thread NCP flashing is planned (experimental) — not yet available.</span></p></div>
<div class="card"><h2>Health audit</h2>
<p class="note">Re-check this panel for problems that stop the dashboard rendering — old WebView, no dashboard app, available updates.</p>
<button class="pbtn" onclick="healthAudit(this)">Run health audit</button>
<div id="audit-out" style="margin-top:10px"></div>
<p class="note"><a href="/api/v1/diag" target="_blank" style="color:#9cf">⭳ Diagnostics dump</a> — full hardware/firmware/SELinux/su report for bug reports.</p></div>
${backupCardHtml(companionHelper)}
$allGood</div>
<script src="/assets/install.js"></script>"""
    }

    /** One top-of-tab warning for a render-blocking finding (WebView old / no dashboard app). WebView gets
     *  the inline "Update WebView now" heal button when [canHeal]; a missing renderer gets a one-tap
     *  "Install HA Companion" button when [canInstallCompanion]. */
    private fun installWarning(f: HealthAudit.Finding, canHeal: Boolean, canInstallCompanion: Boolean): String = when (f.kind) {
        HealthAudit.Kind.WEBVIEW_OLD ->
            """<div class="setup crit">⚠ <b>System WebView is too old</b> (${esc(f.detail)}) — the Home Assistant """ +
                """dashboard may render blank or broken. <a href="$WEBVIEW_DOC" target="_blank" rel="noopener">""" +
                """How &amp; why to update</a> (target: Chromium ${PanelHealth.MIN_CHROMIUM}+).""" +
                (if (canHeal) """<div style="margin-top:10px"><button class="pbtn" onclick="healWebView(this)">⬇ Update WebView now</button> <span id="wv-heal" class="muted"></span></div>""" else "") +
                """</div>"""
        HealthAudit.Kind.NO_RENDERER ->
            """<div class="setup">ℹ <b>No dashboard app detected</b> — install the HA Companion app, """ +
                """<a href="https://www.fully-kiosk.com/" target="_blank" rel="noopener">Fully Kiosk</a>, or set a """ +
                """dashboard package on <a href="/configure">Configure</a>.""" +
                (if (canInstallCompanion) """<div style="margin-top:10px"><button class="pbtn" onclick="installComp('companion','update',this)">⬇ Install HA Companion</button> <span class="muted">progress shows in Managed components below.</span></div>""" else "") +
                """</div>"""
        HealthAudit.Kind.UPDATE -> "" // shown in the Managed-components card, not as a top warning
    }

    /** Managed-components card. ha-paneld + HA Companion get a channel + version picker (default channel
     *  from Configure; up to 10 recent versions hydrated by install.js) with a release-notes link and an
     *  Install-selected-version button. The System WebView is a single known-good build (heal/up-to-date).
     *  All actions POST /api/v1/install/component and poll /api/v1/install/status. */
    private fun componentsCardHtml(wv: PanelInfo.WebViewStatus, root: Boolean, installer: Boolean): String {
        val paneldCur = Config.VERSION
        val compPkg = CompanionInstaller.installedPkg(appContext)
        val compFull = compPkg == CompanionInstaller.FULL_PKG
        val compCur = compPkg?.let { AppInstaller.installedVersion(appContext, it) }?.takeIf { it.isNotBlank() }
        val rec = profile.recommendedWebView

        val paneldRow = pickerRow("paneld", "ha-paneld", paneldCur, config.updateChannel, installer)
        // A Play-managed FULL Companion must never be touched by ha-paneld — show it read-only.
        val compRow = if (compFull)
            simpleRow("HA Companion", compCur, """<span class="muted">Play-managed — updates via the Play Store</span>""")
        else pickerRow("companion", "HA Companion", compCur, config.companionUpdateChannel, installer)
        val wvAction = when {
            wv.tooOld && rec != null && root -> """<button class="pbtn" onclick="installComp('webview','update',this)">⬇ Update WebView</button>"""
            wv.tooOld && rec != null -> """<span class="muted">needs root/daemon to update</span>"""
            wv.tooOld -> """<span class="muted">no known-good build for this panel</span>"""
            else -> """<span class="muted">up to date</span>"""
        }
        val installNote = if (installer) "" else """<p class="note">⚠ Installing/updating needs root, the helper daemon, or locally approved Shizuku access — unavailable on this panel.</p>"""
        return """<div class="card"><h2>Managed components</h2>
$paneldRow
$compRow
${simpleRow("System WebView", wv.display, wvAction)}
$installNote
<p class="note">The default channel is set on the <a href="/configure">Configure</a> tab; changing it here only affects this picker.</p>
<p class="note" id="comp-msg"></p></div>"""
    }

    /** Backup & restore card: an ENCRYPTED device-state bundle (ha-paneld config + optionally the HA
     *  Companion login) with a passphrase; restore shows a decrypt preview before the destructive apply.
     *  Also links the plain config-only bundle (for cloning settings between panels). */
    private fun backupCardHtml(companionHelper: Boolean): String {
        val compRow = if (companionHelper)
            """<label style="display:flex;flex-direction:row;gap:8px;align-items:center;font-size:.85rem"><input type="checkbox" id="bk-comp" checked> Include HA Companion login</label>"""
        else """<p class="note">HA Companion login backup needs the current ha-paneld helper. Update or reprovision this rooted panel to enable it.</p>"""
        val restoreWarn = if (companionHelper) " and rewrites the HA Companion login (force-stops it)" else ""
        return """<div class="card"><h2>Backup &amp; restore</h2>
<p class="note">A bundle of this panel's ha-paneld config${if (companionHelper) " + the HA Companion login" else ""}. Backups contain credentials and are encrypted with your passphrase by default; it can't be recovered if lost.</p>
<div style="display:flex;flex-direction:column;gap:8px;max-width:440px">
$compRow
<input type="password" id="bk-pw" placeholder="Passphrase (required for encrypted backup)">
<label style="display:flex;flex-direction:row;gap:8px;align-items:flex-start;font-size:.85rem;color:#c88"><input type="checkbox" id="bk-plain"> Create an unencrypted plaintext ZIP instead (contains credentials)</label>
<button class="pbtn" onclick="doBackup(this)">⭳ Download backup</button>
</div>
<hr style="border:0;border-top:1px solid #2a2a2a;margin:14px 0">
<p class="note"><b>Restore</b> overwrites this panel's config$restoreWarn — you'll see a preview of the bundle's contents before it applies.</p>
<div style="display:flex;flex-direction:column;gap:8px;max-width:440px">
<input type="password" id="rs-pw" placeholder="Bundle passphrase">
<label class="pbtn" style="cursor:pointer">⭱ Choose backup (.hpb)…<input type="file" id="rs-file" accept=".hpb,application/octet-stream" style="display:none" onchange="restorePick(this)"></label>
<div id="rs-preview"></div>
</div>
<p class="note" id="bk-msg"></p>
<p class="note"><a href="/api/v1/config/export" style="color:#9cf">⭳ Config-only bundle (unencrypted)</a> — settings only, for cloning to another panel via Configure → Import.</p></div>"""
    }

    /** "Install an APK" card (Install tab). ⚠ Root-installs an arbitrary user-supplied APK over the
     *  unauthenticated LAN-trust :8888 — carries a prominent in-card security warning, an enable toggle
     *  (config.apkUploadAllowed), and a parse-then-confirm flow (see install.js). Root/helper-gated. */
    private fun apkCardHtml(root: Boolean): String {
        val body = if (!root) {
            """<p class="note">⚠ Installing an arbitrary APK needs root or the helper daemon — unavailable on this panel.</p>"""
        } else {
            val allowed = config.apkUploadAllowed
            """<div class="setup">⚠ <b>Security:</b> this root-installs <b>any</b> APK you choose, over the panel's """ +
                """<b>unauthenticated</b> LAN web UI. Only upload APKs you trust. """ +
                """<small>(Panel access is LAN-only today; authenticated access is planned for a later release.)</small></div>
<label style="display:flex;flex-direction:row;gap:8px;align-items:center;margin:10px 0"><input type="checkbox" id="apk-allow" ${if (allowed) "checked" else ""} onchange="apkAllow(this)"> Enable APK install on this panel</label>
<div id="apk-ui"${if (allowed) "" else " style=\"display:none\""}>
<label class="pbtn" style="cursor:pointer">⭱ Choose APK…<input type="file" id="apk-file" accept=".apk,application/vnd.android.package-archive" style="display:none" onchange="apkPick(this)"></label>
<div id="apk-preview" style="margin-top:10px"></div>
</div>"""
        }
        return """<div class="card"><h2>Install an APK</h2>
<p class="note">Sideload an app (e.g. a dashboard renderer) by uploading its APK — you'll see its package, version and signer before it installs.</p>
$body
<p class="note" id="apk-msg"></p></div>"""
    }

    /** "Uninstall an app" card. Lists only removable apps (see packagesJson) so the picker can't strand the
     *  panel; the endpoint additionally refuses ha-paneld itself. Root-gated. */
    private fun uninstallCardHtml(root: Boolean): String {
        val body = if (!root) """<p class="note">⚠ Uninstalling an app needs root — unavailable on this panel.</p>"""
        else """<p class="note">Remove an installed app. Only removable (third-party / updated) apps are listed — ha-paneld and stock system apps are excluded. To just hide a vendor app, <a href="/configure">tame</a> it instead.</p>
<div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap">
<select id="uninst-pkg" style="min-width:220px;background:#1c1c1c;color:#eee;border:1px solid #444;border-radius:7px;padding:5px 8px"><option>loading…</option></select>
<button class="pbtn" onclick="doUninstall(this)">Uninstall</button>
</div>
<p class="note" id="uninst-msg"></p>"""
        return """<div class="card"><h2>Uninstall an app</h2>
$body</div>"""
    }

    /** Removable apps (third-party or updated-system) for the Uninstall picker, sorted by label. Stock
     *  system apps + ha-paneld are excluded — pm can't uninstall stock system apps (only disable), and
     *  self-uninstall would kill the tool. */
    /** All apps with a launcher entry (incl. system launchers/renderers), {pkg,label} sorted by label —
     *  the source for the Configure tab's Dashboard-app / Launcher-app pickers. */
    private fun launchableAppsJson(): String {
        val pm = appContext.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val apps = runCatching {
            pm.queryIntentActivities(intent, 0)
                .mapNotNull { it.activityInfo?.applicationInfo }
                .filter { it.packageName != appContext.packageName }
                .associate { it.packageName to runCatching { pm.getApplicationLabel(it).toString() }.getOrDefault(it.packageName) }
                .toList()
                .sortedBy { it.second.lowercase(java.util.Locale.ROOT) }
        }.getOrDefault(emptyList())
        val arr = apps.joinToString(",") { (pkg, label) -> "{\"pkg\":${jsonStr(pkg)},\"label\":${jsonStr(label)}}" }
        return "{\"apps\":[$arr]}"
    }

    private fun packagesJson(): String {
        val pm = appContext.packageManager
        // Exclude ha-paneld itself and the System WebView provider — uninstalling the WebView reverts it to
        // the ancient stock build and blanks the dashboard (the opposite of the WebView-heal feature).
        val excluded = setOf(appContext.packageName, io.github.maxlyth.hapaneld.util.WebViewInstaller.WEBVIEW_PKG)
        val apps = runCatching {
            pm.getInstalledApplications(0)
                .filter { it.packageName !in excluded }
                .filter {
                    it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 ||
                        it.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
                }
                .map { it.packageName to runCatching { pm.getApplicationLabel(it).toString() }.getOrDefault(it.packageName) }
                .sortedBy { it.second.lowercase(java.util.Locale.ROOT) }
        }.getOrDefault(emptyList())
        val arr = apps.joinToString(",") { (pkg, label) -> "{\"pkg\":${jsonStr(pkg)},\"label\":${jsonStr(label)}}" }
        return "{\"packages\":[$arr]}"
    }

    /** A component row with a channel + version picker (versions hydrated by install.js), a release-notes
     *  link, and an Install button — for the GitHub-hosted components (ha-paneld, HA Companion). The
     *  channel select defaults to [defaultChannel] (the Configure-tab setting). */
    private fun pickerRow(name: String, label: String, installed: String?, defaultChannel: String, installer: Boolean): String {
        fun sel(v: String) = if (defaultChannel == v) " selected" else ""
        return """<div class="comprow" data-name="${esc(name)}">
<div class="compname"><b>${esc(label)}</b> <span class="muted">${if (installed != null) """installed <span class="cver">${esc(installed)}</span>""" else """<span class="cver">not installed</span>"""}</span></div>
<div class="comppick">
<label class="muted">Channel <select class="cchan" onchange="loadVersions('$name')"><option value="stable"${sel("stable")}>Stable</option><option value="prerelease"${sel("prerelease")}>Prerelease</option></select></label>
<label class="muted">Version <select class="cvsel" onchange="verChanged('$name')"><option>loading…</option></select></label>
<a class="cfglink cnotes" target="_blank" rel="noopener" title="Release notes ↗" style="visibility:hidden">↗</a>
${if (installer) """<button class="pbtn cinstall" onclick="installSel('$name',this)" data-root="1" disabled>Install</button>"""
        else """<a class="pbtn cdl" style="display:none" target="_blank" rel="noopener" title="This panel has no privileged installer, so ha-paneld can't install APKs itself — download the APK, then install it from your admin machine: adb install -r <file>">⬇ Download APK</a>"""}
</div></div>"""
    }

    /** A component row with no picker — installed version + a single action/state (System WebView, or a
     *  Play-managed Companion). */
    private fun simpleRow(label: String, installed: String?, action: String): String =
        """<div class="comprow">
<div class="compname"><b>${esc(label)}</b> <span class="muted">${if (installed != null) """installed <span class="cver">${esc(installed)}</span>""" else """<span class="cver">not installed</span>"""}</span></div>
<div class="comppick">$action</div></div>"""

    /** Logs tab — live log tail over SSE. App source always; system source needs root (gated live). */
    private fun logsBody(): String {
        val sysOk = suCache.get()
        val sysBtn = if (sysOk) """<button id="lg-src-system" onclick="lgSource('system')">System</button>"""
        else """<button id="lg-src-system" disabled title="Full system logcat needs root — unavailable on this panel">System</button>"""
        // Deliberately NOT inside a `.cards` masonry container — the log card wants the full page width.
        return """
<div class="card"><h2>Live logs <small id="lg-state" class="muted">· connecting…</small></h2>
<div class="modebar" style="flex-wrap:wrap;gap:8px">
 <span class="seg"><button id="lg-src-app" class="on" onclick="lgSource('app')">App</button>$sysBtn</span>
 <select id="lg-level" onchange="lgRender()" title="Minimum level">
  <option value="V" selected>Verbose+</option><option value="D">Debug+</option><option value="I">Info+</option>
  <option value="W">Warning+</option><option value="E">Error+</option>
 </select>
 <input id="lg-filter" placeholder="filter text…" oninput="lgRender()" style="flex:1;min-width:120px">
 <button id="lg-pause" class="pbtn" onclick="lgPause()">⏸ Pause</button>
 <button class="pbtn" onclick="lgClear()">Clear</button>
 <label class="muted" style="display:flex;flex-direction:row;align-items:center;gap:4px"><input type="checkbox" id="lg-follow" checked> Follow</label>
</div>
<div id="lg-out" class="logview" onscroll="lgScrolled()"></div>
<p class="note">App = ha-paneld's own process log (no root needed). System = the full device logcat (root).
Tokens/passwords are redacted before display; the stream is LAN-only and stops when this page closes.
Raw stream: <code>curl -N http://&lt;panel&gt;:${config.httpPort}/api/v1/logs/stream</code></p></div>
<script src="/assets/logs.js"></script>"""
    }

    /** Fleet tab — placeholder (discovery hooks exist; the roster lands later). */
    private fun fleetBody(): String = """
<div class="cards"><div class="card"><h2>Fleet overview <small>· coming soon</small></h2>
<p class="note">A multi-panel roster — name · IP · health, with a link to each panel's UI — will live here.
Every ha-paneld already advertises itself over mDNS (<code>${esc(Config.MDNS_SERVICE_TYPE)}</code>) and
publishes MQTT availability, so the discovery hooks are in place.</p>
<p class="note">For now, open another panel directly at <code>http://&lt;its-ip&gt;:${config.httpPort}/</code>.</p></div></div>"""

    /** Health + capabilities as JSON for the variant UIs. Warnings are ready-to-render HTML fragments. */
    private fun statusJson(): String {
        // Engine-aware WebView age check (a Cromite swap reports the stale OEM package version). Same finding
        // set as the dashboard banner + Install tab (HealthAudit); the audit lists ALL available updates
        // (not the ignore-filtered view — Ignore only silences the dashboard banner). Plus two warnings not
        // modelled by HealthAudit: a crash-looping dashboard app and a Companion with a blank internal_url.
        val wv = PanelInfo.webViewStatus(appContext)
        val findings = HealthAudit.evaluate(
            webViewTooOld = wv.tooOld,
            webViewDisplay = wv.display,
            hasRenderer = PanelInfo.dashboardRenderers(appContext, config.dashboardPackage, config.haUrl).isNotEmpty(),
            updates = UpdateChecker.current(appContext),
        )
        val warns = mutableListOf<String>()
        if (io.github.maxlyth.hapaneld.PanelStatus.dashboardCrashLooping) warns.add(
            "⛔ <b>Dashboard app is crash-looping</b> — the watchdog stopped relaunching it to avoid a restart storm. " +
                "Reinstall or downgrade the dashboard/Companion app, or reboot the panel.",
        )
        companionUrlCache.get().let { u ->
            if (u.needsRepair) warns.add(
                "⚠ <b>Home Assistant Companion has no internal URL</b> (${u.affected} server${if (u.affected == 1) "" else "s"}) — " +
                    "the dashboard can fail with \"Missing 'Host' header\". Repair it on the Install tab.",
            )
        }
        radioStatus()?.let { z ->
            zigbeeWarning(z)?.let(warns::add)
        }
        warns.addAll(findings.map { statusWarning(it) })
        val capColor = mapOf("ok" to "#48c774", "degraded" to "#d9a528", "none" to "#d04a3b")
        val caps = DiagReader.capabilities(appContext, profile).joinToString(",") { c ->
            "{\"name\":${jsonStr(c.name)},\"note\":${jsonStr(c.note)},\"color\":${jsonStr(capColor[c.status] ?: "#888")}}"
        }
        val zigbee = radioStatus()?.let {
            JSONObject(it.mqttAttributes()).put("state", it.state.wireValue).toString()
        } ?: "null"
        return "{\"warnings\":[${warns.joinToString(",") { jsonStr(it) }}],\"capabilities\":[$caps],\"zigbee_gateway\":$zigbee}"
    }

    /** A health finding as a one-line HTML warning for GET /api/v1/status (no Ignore button; updates keep
     *  a direct download link — this is the machine-readable audit, not the dashboard banner). */
    private fun statusWarning(f: HealthAudit.Finding): String = when (f.kind) {
        HealthAudit.Kind.WEBVIEW_OLD ->
            "⚠ <b>System WebView is too old</b> (${esc(f.detail)}) — the Home Assistant dashboard may render blank. " +
                "<a href=\"$WEBVIEW_DOC\" target=\"_blank\" rel=\"noopener\">How &amp; why to update</a> (target Chromium ${PanelHealth.MIN_CHROMIUM}+)."
        HealthAudit.Kind.NO_RENDERER ->
            "ℹ <b>No dashboard app detected</b> — install the HA Companion, Fully Kiosk, or set a dashboard package."
        HealthAudit.Kind.UPDATE -> f.update!!.let { u ->
            "⬆ <b>${esc(u.label)}</b> ${esc(u.latestVersion)} is available (installed ${esc(u.currentVersion)}) — " +
                "<a href=\"${esc(u.releaseUrl)}\" target=\"_blank\" rel=\"noopener\">download</a>"
        }
    }


    /** Proximity tuning card — lives on the Configure tab; driven by /assets/prox.js. */
    private fun proximityCardHtml(): String = """<div class="card" id="cfg-proximity"><h2>Proximity tuning <small id="proxstate"></small><span class="cardbadge exp">experimental</span></h2>
<div id="proxbox" style="display:none">
<canvas id="proxgauge" width="600" height="46" class="gradedonly" style="height:46px"></canvas>
<div style="font-size:.85rem;margin-bottom:8px">raw <b id="proxraw" style="color:#4a9eff">–</b>
 <span id="proxthwrap" class="gradedonly">· threshold <b id="proxth">–</b></span> · state <b id="proxnear">–</b></div>
<div class="gradedonly" style="display:flex;gap:6px;flex-wrap:wrap;align-items:center;font-size:.85rem;margin-bottom:8px">
 <span style="color:#9af">Capture:</span>
 <button type="button" class="pbtn" onclick="proxCap('near')">Near</button>
 <button type="button" class="pbtn" onclick="proxCap('far')">Far</button>
 <span class="gradedonly" style="color:#9af;margin-left:10px">Sensitivity:</span>
 <button type="button" class="pbtn psen gradedonly" data-s="HIGH" onclick="proxSen('HIGH')">High</button>
 <button type="button" class="pbtn psen gradedonly" data-s="MEDIUM" onclick="proxSen('MEDIUM')">Med</button>
 <button type="button" class="pbtn psen gradedonly" data-s="LOW" onclick="proxSen('LOW')">Low</button>
 <button type="button" class="pbtn" style="margin-left:10px" onclick="proxReset()">Reset</button></div>
<label class="gradedonly" style="font-size:.8rem;color:#9af">Threshold fine-tune
 <input type="range" id="proxslider" min="0" max="100" step="0.1" style="width:100%"
  oninput="document.getElementById('proxth').textContent=(+this.value).toFixed(1)"
  onchange="proxThSet(this.value)"></label>
<p id="proxhint" class="note"></p>
</div></div>"""

    /** The pencil that marks a value as CONFIGURABLE (vs a static fact) and deep-links to the exact
     *  setting/card on the Configure tab (`/configure#<anchor>` scrolls + flashes it). */
    private fun cfgIcon(anchor: String): String =
        """ <a class="cfglink" href="/configure#$anchor" title="Edit on the Configure tab" aria-label="Edit">✎</a>"""

    /** What the "auto" (blank) package settings actually resolved to — shown as `auto (pkg)` in the
     *  dashboard rows and as the Configure-field placeholder, so "auto" is never a mystery. When no
     *  launcher app resolves, the Launcher key falls back to ha-paneld's own admin launcher (see
     *  SystemController.launchLauncher) — say so instead of leaving a "—" that reads like a dead key. */
    private fun autoHints(): Map<String, String> = buildMap {
        system.resolveDashboard("").takeIf { it.isNotBlank() }?.let { put("dashboard_package", it) }
        put("launcher_package", system.resolvedLauncher("") ?: "ha-paneld admin launcher")
        // Unset home_dashboard = reload/boot land on whatever HA's frontend picks (the device-level
        // "set as default on this device" if ever used, else the HA user's profile default). The
        // Companion has no config of its own for this, so there's nothing more specific to read.
        put("home_dashboard", "HA default view")
    }

    /** One read-only dashboard row for a registry setting: label → current value + the edit pencil.
     *  Null when the setting doesn't exist on this panel (capability-gated). */
    private fun settingRowHtml(key: String, live: Map<String, String>, caps: Capabilities, hints: Map<String, String> = emptyMap()): String? {
        val spec = SettingsRegistry.spec(key) ?: return null
        if (!spec.availableWhen(caps)) return null
        val raw = effectiveValue(spec, live)
        val shown = when {
            spec.secret -> if (raw.isNotEmpty()) "set" else "—"
            spec.type == SettingType.BOOL -> if (raw.toBoolean()) "on" else "off"
            raw.isBlank() -> hints[key]?.let { "auto ($it)" } ?: "—"
            // The built-in renderer sentinel has no package label — show its friendly name, not "builtin".
            raw == SystemController.BUILTIN_DASHBOARD -> "Built-in renderer (skunk-works)"
            else -> raw
        }
        return """<tr><th>${esc(spec.label)}</th><td>${esc(shown)}${cfgIcon("cfg-$key")}</td></tr>"""
    }

    // ---- dashboard snapshot (probe results) + hydration ---------------------------------------------
    //
    // Rendering `/` used to gather every root/probe value inline — ~8 serialized su round-trips
    // (zigbee status, CPU tier, network-ADB ×2, touch sound, `wm density` ×3, su presence), a 12+s
    // blank page on PX30 panels. The probes now funnel through ONE cached snapshot: `/` renders
    // whatever is last known instantly (placeholders on a cold start) and the page hydrates from
    // GET /api/v1/info, which is the only place the probes actually run — at most once per TTL,
    // single-flight, pre-warmed at server start.

    /** Everything the dashboard shows that costs a root/probe round-trip, gathered once. */
    private class Snap(
        val facts: Map<String, String>,
        val live: Map<String, String>,
        val caps: Capabilities,
        val densityCur: Int?,
        val densityBase: Int?,
        val fontScale: Float,
        val rootOk: Boolean,
        val captureOk: Boolean,
        val shizukuReady: Boolean,
    )

    // The density trio is shared with the Configure tab's Display card (the bulk of ITS slow render).
    private val densityCache = Cached(DENSITY_TTL_MS) { Triple(density.current(), density.base(), density.fontScale()) }
    // su presence doesn't flap — cache the probe gating screenshot / controls / system logs / taming.
    private val suCache = Cached(SU_TTL_MS) { Su.available() }
    private val companionHelperCache = Cached(SU_TTL_MS) { HelperClient.supportsCompanionData() }
    private fun ensureCompanionHelper(): Boolean {
        if (HelperClient.supportsCompanionData()) return true
        val ready = BundledHelperInstaller.ensureCurrent(appContext, profile.appCanSu) in setOf(
            BundledHelperInstaller.Result.ALREADY_CURRENT,
            BundledHelperInstaller.Result.INSTALLED,
        )
        if (ready) companionHelperCache.invalidate()
        return ready
    }
    private fun rootOk(): Boolean = suCache.get() || HelperClient.available()
    private fun shellOk(): Boolean = ShizukuBridge.available()
    private fun installOk(): Boolean = rootOk() || shellOk()
    private fun captureOk(): Boolean = rootOk() || shellOk()
    private fun displayOk(): Boolean = rootOk() || shellOk()

    // Companion internal_url health (root sqlite3 read). Cached so the polled /status endpoint doesn't
    // spawn su per request; only checked when su is present (the DB is app-private). Invalidated on repair.
    private val companionUrlCache = Cached(COMPANION_URL_TTL_MS) {
        if (suCache.get()) CompanionDb.internalUrlStatus(appContext, Su) else CompanionDb.UrlStatus(false, 0)
    }
    private val snapCache = Cached(SNAP_TTL_MS) {
        val d = densityCache.get()
        Snap(
            facts = info(),
            live = liveValues(),
            caps = capabilities(),
            densityCur = d.first, densityBase = d.second, fontScale = d.third,
            rootOk = rootOk(),
            captureOk = captureOk(),
            shizukuReady = shellOk(),
        )
    }

    /** Call after any write that changes probed state (config apply/import/restore, density, tame),
     *  so the next render doesn't show pre-write values for a TTL. */
    private fun snapInvalidate() {
        snapCache.invalidate()
        densityCache.invalidate()
    }

    /** Last-known snapshot with a background refresh when stale — never blocks once built, so the
     *  Configure endpoints (form values, schema capabilities, Display card) render instantly like
     *  the dashboard. Blocks only before the start-up pre-warm has ever completed. */
    private fun snapStaleOk(): Snap {
        val p = snapCache.peek() ?: return snapCache.get()
        if (snapCache.ageMs() > SNAP_TTL_MS) scope.launch(Dispatchers.IO) { runCatching { snapCache.get() } }
        return p
    }

    private val NET_KEYS = listOf("Local IP", "Local IPv6", "HTTP port", "MQTT", "mDNS", "Network ADB")
    // Rows whose values are DECLARED by the DeviceProfile, so wrong data points a contributor straight
    // at the fix: Platform=displayName/socClass, LED=ledMechanism, sensor tech=proximityTech/lightTech,
    // Zigbee=zigbeeGatewayDir, Relays=relayBase, CPU profile=cpuGovernors.
    private val PROF_KEYS = listOf("Platform", "LED", "Light sensor", "Proximity", "Zigbee", "Relays", "CPU profile")
    private fun infoKeys(s: Snap): List<String> = s.facts.keys.filter { it !in NET_KEYS && it !in PROF_KEYS }

    /** The setup / health / update banners — everything above the cards. Needs the facts map (MQTT
     *  state), so on a cold start it hydrates with the rest. */
    private fun bannersHtml(s: Snap): String {
        val mqtt = s.facts["MQTT"] ?: "disabled"
        // Pure decision (unit-tested in SetupBannerTest) — note a CONFIGURED broker that's merely
        // mid-(re)connect must not be reported as missing.
        val needs = SetupBanner.needs(mqtt, config.mqttBroker.isNotBlank(), config.panelIdIsDefault)
        val setup = if (needs.isNotEmpty())
            """<div class="setup">⚠ This panel needs <a href="/configure">${needs.joinToString(" and ")}</a> — set on the Configure tab.</div>"""
        else ""
        // Panel-health + update findings: states that stop the panel rendering the dashboard as expected but
        // that the info map otherwise reports neutrally. Soft + best-effort — ha-paneld runs fine regardless.
        // The WebView verdict is from the REAL engine version (WebView UA), not the stamped package version
        // (cached, so cheap). Shared decision — see HealthAudit; updates are filtered by the per-version
        // dismissals so an "Ignore this version" click stays hidden until a newer release ticks it back.
        val findings = HealthAudit.evaluate(
            webViewTooOld = PanelInfo.webViewStatus(appContext).tooOld,
            webViewDisplay = s.facts["System WebView"] ?: "",
            hasRenderer = PanelInfo.dashboardRenderers(appContext, config.dashboardPackage, config.haUrl).isNotEmpty(),
            updates = UpdateChecker.current(appContext, config.ignoredUpdates),
        )
        // Order: actively-broken states (crash-loop / blank internal_url) first, then render findings
        // (WebView / renderer / updates), then the needs-config setup notice. On the dashboard the ad-hoc
        // warnings link to the Install tab for the fix (their one-tap buttons live there, with install.js).
        return adHocWarnings(inlineRepair = false) + findings.joinToString("") { bannerFor(it) } + setup
    }

    /** Render-blocking warnings not modelled by HealthAudit: a crash-looping dashboard app, and a Companion
     *  server row with a blank internal_url (HA 2026.7 "Missing 'Host' header"). Shown on BOTH the dashboard
     *  banner and the Install tab as high-severity (`crit`). [inlineRepair] adds the one-tap repair button
     *  (Install tab, where install.js is loaded); the dashboard links to the Install tab for the action. */
    private fun adHocWarnings(inlineRepair: Boolean): String = buildString {
        radioStatus()?.let { z ->
            zigbeeWarning(z)?.let { warning ->
                append("""<div class="setup${if (z.state in setOf(ZigbeeHealthState.RUNAWAY, ZigbeeHealthState.CONTAINMENT_FAILED)) " crit" else ""}">$warning</div>""")
            }
        }
        if (io.github.maxlyth.hapaneld.control.BuiltinDashboard.authLatched) append(
            """<div class="setup crit">⛔ <b>Built-in renderer: Home Assistant sign-in rejected</b> — the """ +
                """saved login settings were rejected, so the dashboard stopped retrying (it shows fix """ +
                """instructions on the panel). To borrow a signed-in Home Assistant Companion login again, clear """ +
                """the Home Assistant server URL and save; or enter a new """ +
                """long-lived access token from your Home Assistant profile on """ +
                """<a href="/configure">Configure → Dashboard</a>; the dashboard reloads automatically when """ +
                """the credentials change.</div>""",
        )
        if (io.github.maxlyth.hapaneld.PanelStatus.dashboardCrashLooping) append(
            """<div class="setup crit">⛔ <b>Dashboard app is crash-looping</b> — the watchdog stopped relaunching it """ +
                """to avoid a restart storm. This usually means an incompatible dashboard-app update; reinstall or """ +
                """downgrade the dashboard/Companion app (see <a href="/install">updates</a>), or reboot the panel.</div>""",
        )
        companionUrlCache.get().let { u ->
            if (u.needsRepair) {
                val action = if (inlineRepair)
                    """<div style="margin-top:10px"><button class="pbtn" onclick="repairCompUrl(this)">⚙ Repair internal URL</button> <span id="cu-fix" class="muted"></span></div>"""
                else """ <a href="/install">Repair on the Install tab →</a>"""
                append(
                    """<div class="setup crit">⚠ <b>Home Assistant Companion has no internal URL</b> """ +
                        """(${u.affected} server${if (u.affected == 1) "" else "s"}) — the dashboard can fail to load """ +
                        """with <i>"Missing 'Host' header"</i>. Repair sets the internal URL to the external URL.$action</div>""",
                )
            }
        }
        // Built-in renderer zoomed off 100% (usually carried over from the Companion's "Page zoom"). App
        // zoom is a compatibility lever; the cleaner way to size the dashboard is the panel display density
        // — so we only nudge when that's actually available (rooted / helper daemon). No root = app zoom is
        // the only sizing tool, so stay quiet. densityBase comes from the shared snapshot (no su round-trip).
        val zoom = config.dashboardZoom
        if (config.dashboardPackage == SystemController.BUILTIN_DASHBOARD && zoom != 100 && snapStaleOk().densityBase != null) {
            // Reset is a plain form POST (no JS), so it works on the dashboard banner too — not just the
            // Install tab. The message already links to the Display-sizing card.
            append(
                """<div class="setup">⚠ <b>Dashboard zoom is $zoom%</b> (not 100%) — usually carried over from the """ +
                    """Companion app's Page zoom. The cleaner way to size a dashboard is the panel's """ +
                    """<a href="/configure#cfg-display">display density</a>, which scales the whole panel crisply; """ +
                    """set that, then reset the zoom here.""" +
                    """ <form method="post" action="/api/v1/config" style="display:inline">""" +
                    """<input type="hidden" name="dashboard_zoom" value="100">""" +
                    """<button class="pbtn" type="submit">Reset zoom to 100%</button></form></div>""",
            )
        }
    }

    private fun zigbeeWarning(snapshot: ZigbeeHealthSnapshot): String? = when {
        snapshot.state == ZigbeeHealthState.CONTAINED ->
            "⛔ <b>Zigbee gateway runaway was contained</b> — the router switch was turned OFF after sustained unjoined high CPU or repeated restarts."
        snapshot.state == ZigbeeHealthState.CONTAINMENT_FAILED ->
            "⛔ <b>Zigbee gateway containment was incomplete</b> — the respawner was stopped where possible and surviving work was demoted. Review diagnostics before retrying."
        snapshot.state == ZigbeeHealthState.RUNAWAY ->
            "⛔ <b>Zigbee gateway is runaway</b> — automatic containment is in progress."
        snapshot.state == ZigbeeHealthState.DEGRADED_HIGH_CPU ->
            "⚠ <b>Joined Zigbee gateway has sustained high CPU</b> — it remains running because joined routers are warn-only."
        snapshot.state == ZigbeeHealthState.DEGRADED_UNJOINED ->
            "⚠ <b>Zigbee gateway is running but not joined</b> — pair it or turn the router switch OFF."
        snapshot.recursiveWatchdogAssignment ->
            "⚠ <b>Legacy Zigbee watchdog defect detected</b> — the exact recursive LD_LIBRARY_PATH assignment is present. ha-paneld will not edit the vendor script automatically."
        else -> null
    }

    /** One dashboard banner for a health finding. Update findings link to the Install tab (where the user
     *  manages versions) and carry an "Ignore this version" button — a per-version dismissal that stays
     *  hidden until a newer release ships (see Config.ignoreUpdate / UpdateChecker.visible). */
    private fun bannerFor(f: HealthAudit.Finding): String = when (f.kind) {
        HealthAudit.Kind.WEBVIEW_OLD ->
            """<div class="setup crit">⚠ <b>System WebView is too old</b> (${esc(f.detail)}) — the Home Assistant """ +
                """dashboard may render blank or broken. <a href="$WEBVIEW_DOC" target="_blank" rel="noopener">""" +
                """How &amp; why to update</a> (target: Chromium ${PanelHealth.MIN_CHROMIUM}+). """ +
                """<small>Checked against the real engine version (from the WebView UA), not just the """ +
                """stamped package version — so a Cromite/LineageOS SystemWebView won't trip this.</small> """ +
                """<a href="/install">Manage on the Install tab →</a></div>"""
        HealthAudit.Kind.NO_RENDERER ->
            """<div class="setup">ℹ <b>No dashboard app detected</b> — install the HA Companion app, """ +
                """<a href="https://www.fully-kiosk.com/" target="_blank" rel="noopener">Fully Kiosk</a>, or set a """ +
                """dashboard package on <a href="/configure">Configure</a>, or this panel won't display a dashboard. """ +
                """<small>(ha-paneld itself runs fine without one.)</small> <a href="/install">Install tab →</a></div>"""
        HealthAudit.Kind.UPDATE -> {
            val u = f.update!!
            """<div class="setup info" data-update="${esc(u.label)}" data-version="${esc(u.latestVersion)}">""" +
                """⬆ <b>${esc(u.label)}</b> ${esc(u.latestVersion)} is available (installed: ${esc(u.currentVersion)}) — """ +
                """<a href="/install">manage on the Install tab</a> """ +
                """<button class="pbtn" onclick="ignoreUpdate(this)">Ignore this version</button></div>"""
        }
    }

    /** Table rows for one facts card (Panel information / Networking / ha-paneld profile). */
    private fun factRowsHtml(s: Snap, keys: List<String>): String {
        val webViewTooOld = PanelInfo.webViewStatus(appContext).tooOld
        return keys.filter { s.facts.containsKey(it) }.joinToString("\n") { k ->
            val v = s.facts.getValue(k)
            // Version: plain text + a small "open releases" icon (a hyperlinked version reads ugly).
            val cell = if (k == "ha-paneld") {
                """${esc(v)} <a class="ext" href="$RELEASES_URL" target="_blank" rel="noopener" """ +
                    """title="Releases" aria-label="Releases"><svg viewBox="0 0 24 24"><path d="$EXT_ICON"/></svg></a>"""
            } else if (k == "Display") {
                displayCell(v)
            } else if (k == "System WebView" && webViewTooOld) {
                """<span style="color:#f5c451">${esc(v)} ⚠</span>"""
            } else if (k in SECRET_FIELDS || (k in ADDRESS_FIELDS && isRoutable(v))) {
                // Blurred by default so a casual screenshot doesn't leak it; "Reveal" un-blurs (screenshot
                // hygiene, not access control — the value is still in the page source).
                """<span class="secret">${esc(v)}</span>"""
            } else {
                esc(v)
            }
            // Facts backed by a setting get the ✎ marker (configurable vs static at a glance),
            // deep-linking to the exact row on the Configure tab.
            val edit = FACT_CFG[k]?.let { cfgIcon(it) } ?: ""
            "<tr><th>${esc(k)}</th><td>$cell$edit</td></tr>"
        }
    }

    // Live control states (what HA's control entities currently show) — controls, not config.
    private fun liveRowsHtml(): String {
        val led = config.lastLed.split(",").mapNotNull { it.toIntOrNull() }
        val ledShown = if (led.size == 5 && led[0] == 1) "on · rgb(${led[2]},${led[3]},${led[4]}) @ ${led[1]}" else "off"
        val brightnessShown = effectiveBrightness().takeIf { it >= 0 }?.toString() ?: runCatching {
            android.provider.Settings.System.getInt(appContext.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS)
        }.getOrNull()?.toString() ?: "?"
        return listOf(
            "Screen brightness" to brightnessShown,
            "Volume" to "${volume.getPercent()}%",
            "Navigate" to config.lastNavigate.ifEmpty { "/" },
            "LED" to ledShown,
        ).joinToString("\n") { (k, v) -> """<tr><th>${esc(k)}</th><td>${esc(v)}</td></tr>""" }
    }

    private fun behaviourRowsHtml(s: Snap): String = listOf(
        "wake_on_wave", "prevent_idle_dim", "watchdog_enabled", "touch_sound",
        "silence_boot_chime", "keep_awake", "home_dashboard", "dashboard_package", "launcher_package",
    ).let { keys -> val hints = autoHints(); keys.mapNotNull { settingRowHtml(it, s.live, s.caps, hints) } }.joinToString("\n")

    // Display & tuning: registry values plus the Configure-card-backed ones (density / text /
    // proximity / tame), each deep-linking to its card.
    private fun displayRowsHtml(s: Snap): String {
        val proxShown = when {
            !sensors.hasProximity() -> null
            config.proximityCalibrated -> "calibrated · threshold ${"%.1f".format(config.proximityThreshold)}"
            else -> "not calibrated"
        }
        return listOf("auto_brightness", "brightness_bias").mapNotNull { settingRowHtml(it, s.live, s.caps) }
            .joinToString("\n") + "\n" + listOfNotNull(
            s.densityCur?.let { """<tr><th>Logical density</th><td>$it dpi (factory base ${s.densityBase ?: "?"})${cfgIcon("cfg-display")}</td></tr>""" },
            s.densityCur?.let { """<tr><th>Text size</th><td>${s.fontScale}${cfgIcon("cfg-display")}</td></tr>""" },
            proxShown?.let { """<tr><th>Proximity</th><td>${esc(it)}${cfgIcon("cfg-proximity")}</td></tr>""" },
            """<tr><th>Tamed packages</th><td>${esc(config.tameVendorPackagesRaw.ifBlank { "none" })}${cfgIcon("cfg-tame")}</td></tr>""",
        ).joinToString("\n")
    }

    private fun updatesRowsHtml(s: Snap): String = listOf("self_update", "update_channel", "companion_auto_update")
        .mapNotNull { settingRowHtml(it, s.live, s.caps) }.joinToString("\n")

    private fun capRowsHtml(): String {
        val capColor = mapOf("ok" to "#48c774", "degraded" to "#d9a528", "none" to "#d04a3b")
        return DiagReader.capabilities(appContext, profile).joinToString("\n") { c ->
            val col = capColor[c.status] ?: "#888"
            """<tr><th>${esc(c.name)}</th><td><span style="color:$col">●</span> ${esc(c.note)}</td></tr>"""
        }
    }

    /** Visible "this needs root" banner for a root-gated card/control group — shown (never hidden) so a
     *  no-root user sees the feature and what root would unlock, next to controls rendered disabled. */
    private fun rootLockBanner(unlocks: String): String =
        """<div class="setup rootlock">🔒 Needs a rooted panel — this one has no root, so the controls below are disabled. $unlocks</div>"""

    private fun privilegedLockBanner(unlocks: String): String =
        """<div class="setup rootlock">🔒 Needs privileged panel access — no approved route is ready, so the controls below are disabled. $unlocks</div>"""

    /** The Controls-card button rows. [s] null (cold shell) → everything disabled as "checking…";
     *  hydration swaps in the capability-gated real state. */
    private fun controlsHtml(s: Snap?): String {
        // Controls buttons: render but DISABLE (not hide, not silently-broken) when the action's capability
        // is missing — back/recents accept Accessibility or Shizuku input; launcher/reboot need root;
        // volume always works.
        val a11yOk = s?.facts?.get("Nav actions (a11y)") == "yes"
        val navigation = ControlAvailability.navigation(
            accessibilityReady = a11yOk,
            shizukuReady = s?.shizukuReady == true,
            hasRecents = profile.hasRecents,
        )
        // Recents is only real where the firmware has an overview screen — KEYCODE_APP_SWITCH no-ops on
        // single-purpose panels, so the policy gates it on the profile rather than show a dead one.
        val rootOk = s?.rootOk == true
        val checking = s == null
        fun pbtn(action: String, label: String, ok: Boolean, needs: String, style: String = "", disabledTitle: String? = null): String {
            val dis = when {
                checking -> """ disabled title="checking capabilities…""""
                !ok -> """ disabled title="needs $needs""""
                disabledTitle != null -> """ disabled title="$disabledTitle""""
                else -> ""
            }
            return """<button class="pbtn"$style onclick="act('$action')"$dis>$label</button>"""
        }
        // "Launcher" opens the best real home-screen launcher; "Admin launcher" always opens ha-paneld's
        // own. When no separate launcher exists (e.g. the vendor kiosk is tamed), "Launcher" would just
        // fall through to the admin launcher — so DISABLE it rather than show two buttons that do the same
        // thing. resolvedLauncher() is a cheap PackageManager query (no root).
        val hasDistinctLauncher = !checking && system.resolvedLauncher(config.launcherPackage) != null
        // Launcher / Admin launcher / Reboot need root; a disabled button's tooltip is invisible on a
        // touch panel, so add a visible note that accurately reflects the remaining navigation routes.
        val rootNote = if (!checking && !rootOk)
            """<div class="setup rootlock" style="margin:0 0 8px">🔒 Launcher, Admin launcher and Reboot need root — disabled on this panel. ${navigation.rootlessNote}</div>""" else ""
        return """$rootNote<div class="ctlrow" style="display:flex;gap:8px;flex-wrap:wrap">
 ${pbtn("back", "←<span class=\"lbl\"> Back</span>", navigation.backEnabled, ControlAvailability.INPUT_REQUIREMENT)}
 ${pbtn("recents", "▢<span class=\"lbl\"> Recents</span>", navigation.recentsEnabled, navigation.recentsRequirement)}
 ${pbtn("launcher", "⊞<span class=\"lbl\"> Launcher</span>", rootOk, "a rooted panel", """ style="margin-left:auto"""", disabledTitle = if (hasDistinctLauncher) null else "No separate launcher on this panel — same as Admin launcher")}
 ${pbtn("admin_launcher", "⚙<span class=\"lbl\"> Admin launcher</span>", rootOk, "a rooted panel")}
</div>
<div style="display:flex;gap:8px;flex-wrap:wrap;margin-top:8px">
 ${pbtn("voldn", "Vol −", !checking, "")}
 ${pbtn("volup", "Vol +", !checking, "")}
 ${pbtn("reboot", "⟳ Reboot", rootOk, "a rooted panel", """ style="margin-left:auto;border-color:#7a3a2a;color:#f5a08a"""")}
</div>"""
    }

    /** Hydration payload for the dashboard: ready-to-inject HTML fragments, rendered by the same
     *  functions as the warm server render so the two paths can't drift. Builds the snapshot (this
     *  is where the probe cost actually lands — once per TTL). */
    private fun infoJson(): String {
        val s = snapCache.get()
        val cards = listOf(
            "livetbl" to liveRowsHtml(),
            "behavtbl" to behaviourRowsHtml(s),
            "disptbl" to displayRowsHtml(s),
            "updtbl" to updatesRowsHtml(s),
            "infotbl" to factRowsHtml(s, infoKeys(s)),
            "nettbl" to factRowsHtml(s, NET_KEYS),
            "proftbl" to factRowsHtml(s, PROF_KEYS),
            "captbl" to capRowsHtml(),
        ).joinToString(",") { (k, v) -> "\"$k\":${jsonStr(v)}" }
        return """{"banners":${jsonStr(bannersHtml(s))},"shot":${s.captureOk},"controls":${jsonStr(controlsHtml(s))},"cards":{$cards}}"""
    }

    private fun infoHtml(): String {
        val pid = esc(config.panelId)
        val fname = esc(config.friendlyName)
        val browserTitle = esc(panelBrowserTitle(config.friendlyName))
        // Stale-while-revalidate: render the last-known snapshot instantly (placeholders if none yet)
        // and let the page hydrate/refresh from /api/v1/info when the snapshot is missing or old.
        val s = snapCache.peek()
        val hydrate = s == null || snapCache.ageMs() > SNAP_TTL_MS
        val placeholder = """<tr><td style="color:#888">reading…</td></tr>"""
        // One facts/value card: cold → placeholder rows (hydration fills or hides); warm → rows, and
        // an EMPTY card is omitted exactly as before.
        fun tcard(id: String, title: String, rows: String?, pre: String = "", post: String = ""): String = when {
            rows == null -> """<div class="card"><h2>${esc(title)}</h2>$pre<table id="$id">$placeholder</table>$post</div>"""
            rows.isBlank() -> ""
            else -> """<div class="card"><h2>${esc(title)}</h2>$pre<table id="$id">$rows</table>$post</div>"""
        }
        val profNote = """<p class="note">Values declared by this panel's <a href="$REPO_URL/blob/main/docs/architecture/device-profiles.md" target="_blank" rel="noopener" style="color:#9cf">device profile</a> — if one looks wrong, that's where to correct it.</p>"""
        val capNote = """<p class="note"><a href="/api/v1/diag" target="_blank" style="color:#9cf">⭳ Diagnostics dump</a> — a copy-paste
report of this panel's hardware, firmware, SELinux, su and node probes for bug reports.</p>"""
        // Screenshot card: needs a privileged capture route, which the cold shell doesn't know yet — render it hidden with
        // the img src deferred (data-src) so a rootless panel never fires a doomed screencap request.
        val shotInner = { src: Boolean ->
            """<a class="shot" href="/api/v1/screenshot.png" target="_blank" rel="noopener" title="Open full size in a new window" style="aspect-ratio:${screenAspectRatio()}"><img ${if (src) "src=\"/api/v1/screenshot.png\"" else "data-src=\"/api/v1/screenshot.png\""} alt="panel screenshot" onload="this.parentElement.classList.add('loaded')" onerror="this.parentElement.classList.add('failed')"></a>
<p class="note"><a href="#" onclick="var s=this.closest('.card').querySelector('.shot');s.classList.remove('loaded','failed');s.querySelector('img').src='/api/v1/screenshot.png?t='+Date.now();return false" style="color:#9cf">↻ Refresh</a> · click the image to open it full size. Captured on demand through the available privileged route; local-network only.</p>"""
        }
        val shotCard = when {
            s == null -> """<div class="card" id="shotcard" style="display:none"><h2>Screenshot <small>· live panel</small></h2>${shotInner(false)}</div>"""
            s.captureOk -> """<div class="card" id="shotcard"><h2>Screenshot <small>· live panel</small></h2>${shotInner(true)}</div>"""
            else -> ""
        }
        return """<!doctype html><html lang="en"><head><meta charset="utf-8">
<script>/* ?theme=light|dark pins the UI theme for testing (else the browser preference rules) */
(function(){var m=location.search.match(/[?&]theme=(dark|light)\b/);if(m)document.documentElement.setAttribute("data-theme",m[1])})();</script>
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>$browserTitle</title>
<link rel="icon" type="image/svg+xml" href="/favicon.svg">
<link rel="stylesheet" href="/info.css"></head><body data-ver="${Config.VERSION}" data-build="${buildToken()}" data-cfg="${configConcurrencyHash()}" data-hydrate="${if (hydrate) "1" else "0"}"><div class="wrap">
<div class="topbar"><div class="hdr"><button id="navburger" class="navburger pbtn" aria-label="Menu">☰</button><h1><img src="/icon.svg" class="logo" alt=""><span class="brand">ha-paneld</span> <small id="pswitch" data-self-id="$pid" data-self-name="$fname"><span class="sep">·</span>$fname</small></h1>
 <span style="display:flex;gap:10px;align-items:center">${if (config.haLinkUrl.isNotBlank()) """<a class="pbtn" href="${esc(config.haLinkUrl)}" target="_blank" rel="noopener" title="Open Home Assistant (this panel's device page when known)">Open in HA</a>""" else ""}<button id="revbtn" class="pbtn" onclick="toggleReveal()" title="Show/hide blurred values for editing — they're blurred by default so screenshots don't leak them">Reveal</button>
 <a class="gh" href="$REPO_URL" target="_blank" rel="noopener" title="ha-paneld on GitHub" aria-label="GitHub"><svg viewBox="0 0 24 24"><path d="$GH_ICON"/></svg></a></span></div>
${navBar("dashboard")}</div>
<div id="verbar" class="setup" style="display:none">⟳ A newer ha-paneld is installed — <a href="#" onclick="location.reload();return false">reload</a> to refresh this page.</div>
<div id="bannerzone">${s?.let { bannersHtml(it) } ?: ""}</div>
<div class="cards">
<div class="card"><h2>Controls <small>· software nav bar</small></h2>
<div id="ctlzone">${controlsHtml(s)}</div>
<p class="note">For panels with no physical nav bar. Back/Recents use the accessibility service; Launcher/Reboot need root — actions whose capability is missing are disabled (hover for why).</p></div>
$shotCard
${tcard("infotbl", "Panel information", s?.let { factRowsHtml(it, infoKeys(it)) })}
${tcard("nettbl", "Networking", s?.let { factRowsHtml(it, NET_KEYS) })}
${tcard("proftbl", "ha-paneld profile", s?.let { factRowsHtml(it, PROF_KEYS) }, post = profNote)}
${tcard("captbl", "Capabilities", if (s == null) null else capRowsHtml(), post = capNote)}
<div class="card"><h2>Dashboard responsiveness <small id="smhdr"></small></h2>
<canvas id="respchart" width="600" height="150" style="height:150px"></canvas>
<div class="leg"><span style="color:#d04a3b">▬</span> interaction latency&nbsp;&nbsp;<span style="color:#4a9eff">▬</span> state updates&nbsp;&nbsp;<span style="color:#f5a623">▬</span> main-thread blocking · ~4 min</div>
<table id="smtbl"><tr><td style="color:#888">measuring…</td></tr></table></div>
<div class="card"><h2>Home Assistant state stream <small>· built-in renderer</small></h2>
<table id="streamtbl"><tr><td style="color:#888">waiting for state traffic…</td></tr></table>
<p class="note">Payload is uncompressed application JSON. Main-thread time measures dispatch until the browser yields; it is not claimed as Home Assistant-only CPU. <a href="/entities">Open entity diagnostics</a>.</p></div>
<div class="card"><h2>Sensors <small id="sensage"></small></h2>
<table id="senstbl"><tr><td style="color:#888">reading…</td></tr></table>
<p class="note">Live readings from this panel’s sensors — shown even when a value is hidden from Home Assistant.</p></div>
<div class="card"><h2>Performance <small id="perfage"></small></h2>
<div style="color:#666;font-size:.78rem;margin-bottom:8px">Samples only while this page is open.</div>
<canvas id="perfchart" width="600" height="96" style="height:96px"></canvas>
<div class="leg"><span style="color:#4a9eff">■</span> CPU&nbsp;&nbsp;<span style="color:#48c774">■</span> RAM&nbsp;&nbsp;<span style="color:#f5a623">■</span> GPU (% used) · ~4&nbsp;min</div>
<table id="perf"><tr><td style="color:#888">sampling…</td></tr></table></div>
<div class="card"><h2>Top processes <small>· by CPU</small></h2>
<table class="dt" id="topproc"><tr><td style="color:#888">top processes…</td></tr></table></div>
<div class="card"><h2>WebView debugging <small id="insthdr"></small></h2>
<div style="display:flex;gap:8px;margin-bottom:4px">
 <button type="button" class="pbtn" onclick="inspStart()">Enable</button>
 <button type="button" class="pbtn" onclick="inspStop()">Stop</button></div>
<p class="note" id="insthint"></p></div>
${tcard("livetbl", "Live state", if (s == null) null else liveRowsHtml(), pre = """<p class="note">What Home Assistant's control entities currently show.</p>""")}
${tcard("behavtbl", "Behaviour", s?.let { behaviourRowsHtml(it) })}
${tcard("disptbl", "Display & tuning", s?.let { displayRowsHtml(it) })}
${tcard("updtbl", "Updates", s?.let { updatesRowsHtml(it) })}
</div>
<p class="note" style="text-align:center;margin-top:18px"><a href="/api" style="color:#9cf">REST API explorer</a>
 · <a href="/api/v1/diag" target="_blank" style="color:#9cf">diagnostics</a> · <a href="$REPO_URL" target="_blank" rel="noopener" style="color:#9cf">GitHub</a></p>
<script src="/info.js"></script>
<script src="/assets/switcher.js"></script>
<script src="/assets/buildwatch.js"></script>
</div></body></html>"""
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** JSON-quote a string value (escapes backslash + double-quote). */
    private fun jsonStr(s: String): String = Json.str(s)

    /**
     * Standalone "Vendor packages" card. Taming intrusive firmware apps is a distinct, deploy-time concept
     * — not part of basic configuration — so it gets its own card with **per-package action buttons**, not
     * a checkbox list behind a shared Save (which made "did it apply?" and "how do I remove one?" unclear).
     * Each row acts immediately via `POST /tame`: an active app offers **Tame**, a tamed/disabled one offers
     * **Re-enable**. A free-text box tames any package by name. Hidden where no privileged path exists (taming
     * needs root or the helper daemon). Critical / HA / own packages are never listed.
     */
    /** One Vendor-packages row: label + package id, an optional state badge, and the single action button.
     *  Shared by the card and the picker. [showState] is false on the card — every row there is already
     *  tamed (disabled), so the column is redundant and just crowds the layout. */
    private fun tameRowHtml(c: TameController.Candidate, showState: Boolean = true, disabled: Boolean = false): String {
        val tamed = c.blocked || c.disabled
        val state = if (!showState) "" else when {
            !c.installed -> """<span style="width:80px;text-align:right;font-size:.85em;color:var(--dim)">not installed</span>"""
            c.disabled -> """<span style="width:80px;text-align:right;font-size:.85em;color:#d9a528">disabled</span>"""
            else -> """<span style="width:80px;text-align:right;font-size:.85em;color:#3fb950">active</span>"""
        }
        val action = if (tamed) "untame" else "tame"
        val label = if (tamed) "Re-enable" else "Tame"
        val btn = if (tamed) "" else "background:#7a2e2e;border-color:#7a2e2e"
        // Tags (authored or heuristic: core/vendor/user/overlay) after the label; note below the package id.
        val tags = c.tags.joinToString("") {
            """<span class="vtag">${esc(it)}</span>"""
        }
        // A "recommended" badge marks the profile's defaultTame picks (safe first picks / the "Tame all
        // recommended" set) while they're still active.
        val recBadge = if (c.recommended && !tamed)
            """<span class="vtag rec">recommended</span>""" else ""
        val note = if (c.note.isNotBlank())
            """<br><small style="color:#9aa">${esc(c.note)}</small>""" else ""
        // A non-removable package (core Android / dashboard / ourselves) is shown for context with a muted
        // "protected" label where the action button would be — no way to disable it.
        val control = if (!c.removable)
            """<span style="font-size:.8em;color:#777;white-space:nowrap">protected</span>"""
        else
            """<form method="post" action="/api/v1/tame" style="margin:0"><input type="hidden" name="pkg" value="${esc(c.pkg)}"><input type="hidden" name="action" value="$action"><button type="submit" style="$btn;white-space:nowrap"${if (disabled) " disabled" else ""}>$label</button></form>"""
        return """  <div style="display:flex;align-items:center;gap:10px;padding:9px 0;border-top:1px solid #222">
   <span style="flex:1;min-width:0;overflow:hidden">${esc(c.label)}$recBadge$tags<br><small style="color:#888">${esc(c.pkg)}</small>$note</span>
   $state
   $control
  </div>"""
    }

    private fun tameCardHtml(): String {
        // Root-gated, but shown (never hidden) so a no-root user sees the feature: the profile's candidate
        // vendor apps are listed greyed with a lock banner, actions disabled. Discovery (PackageManager)
        // needs no root; the tame/re-enable ACTIONS do.
        val locked = !rootOk()
        // The card shows what's currently TAMED (the blocklist); discovery lives in the Find-a-package
        // picker. So a tamed package always has a visible Re-enable here. When locked, fall back to the
        // profile's candidate list so there's something to show.
        val cands = runCatching {
            tame.cardCandidates(config.tameVendorPackages, tameProfileCandidates)
        }.getOrDefault(emptyList())
        val rows = cands.joinToString("\n") { tameRowHtml(it, showState = false, disabled = locked) }
        val body = when {
            locked -> """<div class="locked">${rows.ifBlank { """<p class="note">The vendor apps this panel could hide would be listed here.</p>""" }}</div>"""
            else -> rows.ifBlank {
                """<p class="note">Nothing tamed yet. Press <b>Find a package…</b> to see what's on this panel.</p>"""
            }
        }
        val dis = if (locked) " disabled" else ""
        val lock = if (locked) rootLockBanner("With root, ha-paneld can hide vendor clutter (test tools, the vendor launcher) so only your dashboard shows.") else ""
        return """<div class="card" id="cfg-tame"><h2>Vendor packages<span class="cardbadge exp">experimental</span></h2>
$lock<p class="note"><b>Tame</b> force-stops an app, stops it relaunching on boot, and blocks it drawing over the dashboard — applied immediately and on every boot. <b>Re-enable</b> undoes it. Critical system apps are never offered; nothing changes until you press a button.</p>
$body
<div style="display:flex;flex-direction:column;gap:8px;margin-top:12px" class="${if (locked) "locked" else ""}">
 <button type="button" onclick="pkgPick()"$dis>Find a package…</button>
 <form method="post" action="/api/v1/tame" style="display:flex;flex-direction:row;gap:8px;margin:0">
  <input name="pkg" autocapitalize="none" autocorrect="off" spellcheck="false" placeholder="…or tame a package by name" style="flex:1"$dis>
  <input type="hidden" name="action" value="tame">
  <button type="submit"$dis>Tame</button>
 </form>
</div>
<dialog id="pkgdlg" style="background:#1a1a1a;color:#eee;border:1px solid #333;border-radius:12px;max-width:520px;width:92%;padding:16px">
 <h3 style="margin:0 0 4px">Find a package to control</h3>
 <p class="note" style="margin:0 0 8px">Apps on this panel you might want to tame — pick one to act on it. Not every entry is unwanted; only tame things you recognise.</p>
 <div id="pkgdlgbody" style="max-height:55vh;overflow:auto">Loading…</div>
 <form method="dialog" style="margin-top:12px;text-align:right"><button>Close</button></form>
</dialog>
<script>function pkgPick(){var d=document.getElementById('pkgdlg');d.showModal();
document.getElementById('pkgdlgbody').innerHTML='Loading…';
fetch('/api/v1/tame/suggest').then(function(r){return r.text()}).then(function(t){document.getElementById('pkgdlgbody').innerHTML=t}).catch(function(){document.getElementById('pkgdlgbody').textContent='Could not list packages.'});}</script></div>"""
    }

    /** Display-sizing card (density + text scale). Empty when su isn't reachable (no control). */
    private fun displayCardHtml(): String {
        // Read the density trio FRESH from densityCache (not the stale-while-revalidate snapshot) so a
        // value just applied via this card shows immediately: the POST primes densityCache, whereas
        // snapStaleOk returns the pre-change snapshot for a refresh cycle. densityCache is TTL-cached and
        // primed on write, so this only pays the su reads on a genuinely cold Configure load.
        val (curOverride, base, fs) = densityCache.get()
        // Shown even without root (density can't be READ without it either) so a no-root user sees the
        // feature — but greyed, with a lock banner, and every control disabled. `dis` toggles all of it.
        val locked = !displayOk()
        // Prefill: the active override if one is set, else the profile's HA-optimised recommendation
        // (so a fresh panel offers the right value to Apply rather than the factory base), else the base.
        val cur = curOverride?.takeIf { it != base } ?: recommendedDensity ?: base ?: DensityController.MIN_DPI
        val dis = if (locked) " disabled" else ""
        val rec = if (!locked && (recommendedDensity != null || recommendedFontScale != null))
            """ <button type="submit" name="action" value="rec" formnovalidate>HA-optimised</button>""" else ""
        val lock = if (locked) privilegedLockBanner("With root, the helper daemon, or locally approved Shizuku access, ha-paneld can match the dashboard's density and text size to the physical screen.") else ""
        return """<div class="card" id="cfg-display"><h2>Display sizing<span class="cardbadge exp">experimental</span></h2>
$lock<p class="note"><b>Experimental / R&amp;D — the right values aren't dialled in yet; experiment at your own
pace.</b> Match an HA dashboard's size to a desktop browser. <b>Density</b> scales the whole layout
(lower dpi = more fits); <b>text size</b> scales WebView text. Panel firmware often ships these
mismatched to the physical screen. Applies live, persists across reboot; needs root, the helper daemon, or locally approved Shizuku access.</p>
<form method="post" action="/api/v1/display/density" class="${if (locked) "locked" else ""}" style="display:flex;flex-direction:column;gap:10px">
 <label style="display:flex;flex-direction:row;justify-content:space-between;align-items:center;gap:12px">
  <span>Logical density (dpi) <small style="color:#888">· factory base ${base ?: "?"}</small></span>
  <input name="density" type="number" min="${DensityController.MIN_DPI}" max="${DensityController.MAX_DPI}" value="$cur" style="width:96px"$dis>
 </label>
 <label style="display:flex;flex-direction:row;justify-content:space-between;align-items:center;gap:12px">
  <span>Text size <small style="color:#888">· default 1.0</small></span>
  <input name="font" type="number" step="0.05" min="${DensityController.MIN_FONT}" max="${DensityController.MAX_FONT}" value="$fs" style="width:96px"$dis>
 </label>
 <div style="display:flex;gap:8px;flex-wrap:wrap;margin-top:2px">
  <button type="submit"$dis>Apply</button>$rec
  <button type="submit" name="action" value="reset" formnovalidate$dis>Reset</button>
 </div>
</form></div>"""
    }

    /** Read a bundled static asset (info.js / info.css) as text. */
    private fun asset(name: String): String =
        appContext.assets.open(name).bufferedReader().use { it.readText() }

    private fun inspectJson(status: String): String =
        """{"running":${CdpRelay.running},"port":${CdpRelay.PORT},"status":"$status"}"""

    private fun entityFilterStatusJson(): String {
        val ids = runCatching { EntityFilterProtocol.normalize(config.dashboardEntityFilterIds) }
            .getOrDefault(emptyList())
        val hash = EntityFilterProtocol.hash(ids)
        return "{" +
            "\"enabled\":${config.dashboardEntityFilterEnabled}," +
            "\"entity_count\":${ids.size},\"filter_hash\":\"$hash\"," +
            "\"runtime\":${EntityFilterTelemetry.json()},\"learning\":${entityLearning.statusJson()}}"
    }

    /** Entity administration requests are tiny control messages. Bound both declared and chunked bodies
     *  before materializing JSON so a LAN client cannot exhaust a panel's heap. */
    private suspend fun receiveEntityAdminJson(call: ApplicationCall, allowBlank: Boolean = false): JSONObject? {
        val bytes = when (val receipt = receiveBoundedBody(call, MAX_ENTITY_ADMIN_BODY_BYTES)) {
            is BoundedBodyReceipt.Received -> receipt.bytes
            BoundedBodyReceipt.TooLarge -> {
                call.respondText("request too large\n", status = HttpStatusCode.PayloadTooLarge)
                return null
            }
            BoundedBodyReceipt.TimedOut -> {
                call.respondText("request timeout\n", status = HttpStatusCode.RequestTimeout)
                return null
            }
        }
        val text = String(bytes, Charsets.UTF_8)
        return try {
            JSONObject(if (allowBlank && text.isBlank()) "{}" else text)
        } catch (_: Throwable) {
            call.respondText("invalid JSON\n", status = HttpStatusCode.BadRequest)
            null
        }
    }

    /** Replace/toggle the complete experimental allow-list. Existing state supplies omitted fields,
     *  making `{\"enabled\":false}` a cheap A/B switch while the list remains stored on the panel. */
    private suspend fun handleEntityFilterPost(call: ApplicationCall) {
        val body = when (val receipt = receiveBoundedBody(call, EntityFilterProtocol.MAX_API_BODY_BYTES.toLong())) {
            is BoundedBodyReceipt.Received -> String(receipt.bytes, Charsets.UTF_8)
            BoundedBodyReceipt.TooLarge -> {
                call.respondText("request too large\n", status = HttpStatusCode.PayloadTooLarge)
                return
            }
            BoundedBodyReceipt.TimedOut -> {
                call.respondText("request timeout\n", status = HttpStatusCode.RequestTimeout)
                return
            }
        }
        val update = runCatching { EntityFilterProtocol.parseUpdate(body) }
            .getOrElse {
                call.respondText("invalid entity filter: ${it.message}\n", status = HttpStatusCode.BadRequest)
                return
            }
        if (update.mode == "automatic") {
            val requested = update.enabled ?: true
            if (!entityLearning.setEnabled(requested)) {
                call.respondText("configuration commit failed\n", status = HttpStatusCode.InternalServerError)
                return
            }
            call.respondText(entityFilterStatusJson(), ContentType.Application.Json)
            return
        }
        val ids = update.entityIds ?: config.dashboardEntityFilterIds
        val enabled = update.enabled ?: config.dashboardEntityFilterEnabled
        if (update.entityIds != null && ids.isEmpty()) {
            call.respondText("entity_ids must contain at least one valid entity\n", status = HttpStatusCode.BadRequest)
            return
        }
        if (enabled && ids.isEmpty()) {
            call.respondText("entity_ids required when enabled\n", status = HttpStatusCode.BadRequest)
            return
        }
        val committed = withContext(Dispatchers.IO) {
            if (update.mode == "manual" || update.entityIds != null) {
                config.commitDashboardManualEntityFilter(enabled, ids)
            } else {
                config.setDashboardEntityFilter(enabled, ids)
            }
        }
        if (!committed) {
            call.respondText("configuration commit failed\n", status = HttpStatusCode.InternalServerError)
            return
        }
        call.respondText(entityFilterStatusJson(), ContentType.Application.Json)
        // The live renderer is singleTask. reloadDashboard marks a reload intent; onNewIntent sees the
        // changed filter signature and rebuilds the WebView so document-start wiring is atomic.
        if (config.dashboardPackage == SystemController.BUILTIN_DASHBOARD) {
            scope.launch { runCatching { system.reloadDashboard(SystemController.BUILTIN_DASHBOARD) } }
        }
    }

    /**
     * Apply a POSTed config form/JSON (partial-merge), then live-reconfigure. Shared by the legacy
     * `/config` route and `/api/v1/config`. Fleet/JSON clients (Accept: application/json) get the new
     * config back; a browser form gets an HTML redirect to the info page. The bespoke handling of
     * identity/MQTT/logging/tame keys is preserved; the formerly MQTT-only behaviour keys are applied
     * through [applySetting] (same path as an HA command), and per-row HA-exposure toggles are stored.
     */
    private suspend fun handleConfigPost(call: ApplicationCall) {
        val received = receiveBoundedConfigParameters(call) ?: return
        val p = when (val result = normalizeConfigPostParameters(received)) {
            is ConfigPostParameters.Ok -> result.values
            is ConfigPostParameters.Bad -> {
                call.respondText("${result.reason}\n", status = HttpStatusCode.BadRequest)
                return
            }
        }
        // Partial-merge: apply ONLY keys present, so a fleet tool can set one field without clobbering
        // the rest. The UI form sends every key (blank = clear), preserving its full-replace behaviour.
        val panelId = p["panel_id"]
        val dashboardPackage = p["dashboard_package"]
        // Persisted fields are staged into one editor and committed atomically, so a power loss mid-apply
        // can't leave a half-written config (e.g. broker set but credentials not). Live side-effects
        // (behaviour keys, reconfigure) run after the commit so they read freshly-committed state.
        // Live-apply side-effects are DETECTED inside the batch (from POSTED values — a read-back inside
        // applyBatch returns the pre-commit value, which silently defeated change-detection) but EXECUTED
        // after it commits, so the relaunched renderer can never read stale config.
        var applyDark: Boolean? = null
        var relaunchForHa = false
        var relaunchForDash = false
        var relaunchForFullscreen = false
        var relaunchForOverscroll = false
        var reloadForZoom = false
        var entityLearningChanged: Boolean? = null
        var entityTargetChanged = false
        var homeDashboardAppliedEarly = false
        var homeDashboardChangedEarly = false
        var tameDelta = TameDelta.NONE
        var rendererFailure: Throwable? = null
        val committed = withContext(Dispatchers.IO) {
            rendererPreparation.transaction {
                val previous = ConfigBundle.fromValues(
                    revisionValues(), kind = ConfigBundle.KIND_REVISION,
                    exportedAt = System.currentTimeMillis().toString(), exportedBy = config.panelId,
                )
                val saved = config.applyBatch {
                    panelId?.let { config.setPanelId(it) }
                    p["friendly_name"]?.let { config.setFriendlyName(it.trim()) }
                    val prevDash = config.dashboardPackage
                    dashboardPackage?.let { config.setDashboardPackage(it) }
                    val dashChanged = dashboardPackage?.let { it != prevDash } == true
                    p["launcher_package"]?.let { config.setLauncherPackage(it.trim()) }
                    p["tame_vendor_packages"]?.let { raw ->
                        val next = raw.split(Regex("[\\s,]+")).map { it.trim() }
                            .filter { it.isNotEmpty() && !TameController.isCritical(it) }.toSet()
                        tameDelta = TameDelta.between(config.tameVendorPackages.toSet(), next)
                        if (!tameDelta.isEmpty) config.setTameVendorPackages(next.joinToString(" "))
                    }
                    p["http_allowed_hosts"]?.let { config.setHttpAllowedHosts(it) }
                    // Live keys are deliberately excluded from this batch. Their handlers must observe
                    // the previous value before the live-setting authority persists the applied value.
                    // Keep-awake (partial wakelock so SoC/network never suspend). Applied live by reconfigure().
                    p["keep_awake"]?.let { config.setKeepAwake(it.trim().equals("true", ignoreCase = true) || it.trim() == "1") }
                    // Room-temperature calibration trim (°C) — a plain local pref with no MQTT command, so it
                    // persists here rather than through HTTP_LIVE_KEYS/applySetting (the command path).
                    p["room_temp_offset"]?.let { config.setRoomTempOffset(it) }
                    // Built-in-renderer local prefs (no MQTT entity → bespoke persist, like room_temp_offset).
                    // dashboard_idle_return_min previously had NO persist path at all — the Configure field
                    // rendered but silently never saved (found wiring dashboard_fullscreen, issue #25/#24 pass).
                    p["dashboard_idle_return_min"]?.trim()?.toIntOrNull()?.let { config.setDashboardIdleReturnMin(it.coerceIn(0, 1440)) }
                    // Live-apply a fullscreen toggle: a bare foreground relaunch of the running renderer re-runs
                    // onResume → applyFullscreen with the new value, without touching the page (no reload flag).
                    // Detected from the POSTED value — config read-back inside the batch is pre-commit.
                    val prevFullscreen = config.dashboardFullscreen
                    val postedFullscreen = p["dashboard_fullscreen"]?.let { it.trim().equals("true", ignoreCase = true) || it.trim() == "1" }
                    postedFullscreen?.let { config.setDashboardFullscreen(it) }
                    relaunchForFullscreen = postedFullscreen != null && postedFullscreen != prevFullscreen && !dashChanged
                    // Overscroll stretch/glow (hidden, API-only). Same live-apply as fullscreen: a foreground
                    // relaunch re-runs onResume → applyOverscroll. Detected from the POSTED value (read-back
                    // inside applyBatch is pre-commit).
                    val prevOverscroll = config.dashboardOverscroll
                    val postedOverscroll = p["dashboard_overscroll"]?.let { it.trim().equals("true", ignoreCase = true) || it.trim() == "1" }
                    postedOverscroll?.let { config.setDashboardOverscroll(it) }
                    relaunchForOverscroll = postedOverscroll != null && postedOverscroll != prevOverscroll && !dashChanged
                    val prevEntityLearning = config.dashboardEntityLearningEnabled
                    val postedEntityLearning = p["dashboard_entity_learning"]?.let {
                        it.trim().equals("true", ignoreCase = true) || it.trim() == "1"
                    }
                    if (postedEntityLearning != null && postedEntityLearning != prevEntityLearning) {
                        // The manager owns this transition after the surrounding config transaction.
                        // Pre-writing it here makes setEnabled() observe the new value as the old value,
                        // defeating its fresh-opt-in latch reset and bootstrap semantics.
                        entityLearningChanged = postedEntityLearning
                    }
                    // Page zoom (%). A fresh load is where setInitialScale reliably takes effect, so on a change
                    // we reload the renderer rather than just re-foregrounding it. Detected from the POSTED value.
                    val prevZoom = config.dashboardZoom
                    val postedZoom = p["dashboard_zoom"]?.trim()?.toIntOrNull()?.coerceIn(50, 300)
                    postedZoom?.let { config.setDashboardZoom(it) }
                    reloadForZoom = postedZoom != null && postedZoom != prevZoom && !dashChanged
                    // Dark mode (Display card; only meaningful on panels WITHOUT a system dark-mode setting,
                    // Android 9-). Detected from the POSTED value (read-back inside the batch is pre-commit —
                    // this exact bug made the toggle a silent no-op); executed after the batch commits.
                    val prevDark = config.darkMode
                    val postedDark = p["dark_mode"]?.let { it.trim().equals("true", ignoreCase = true) || it.trim() == "1" }
                    postedDark?.let { config.setDarkMode(it) }
                    if (postedDark != null && postedDark != prevDark && android.os.Build.VERSION.SDK_INT < 29) applyDark = postedDark
                    val logEnabled = p["log_ship_enabled"]?.let { it.trim().equals("true", ignoreCase = true) || it.trim() == "1" }
                    val logHost = p["log_ship_host"]?.trim()
                    val logPort = p["log_ship_port"]?.trim()?.toIntOrNull()
                    val logProto = p["log_ship_protocol"]?.trim()
                    if (logEnabled != null || logHost != null || logPort != null || logProto != null) {
                        config.setLogShipping(
                            logEnabled ?: config.logShipEnabled,
                            logHost ?: config.logShipHost,
                            logPort ?: config.logShipPort,
                            logProto ?: config.logShipProtocol,
                        )
                    }
                    val mfr = p["manufacturer"]?.trim()
                    val mdl = p["model"]?.trim()
                    if (mfr != null || mdl != null) config.setHardware(
                        (mfr ?: config.manufacturer).ifEmpty { "ha-paneld" },
                        (mdl ?: config.model).ifEmpty { "panel agent" },
                    )
                    val broker = p["mqtt_broker"]?.trim()
                    val user = p["mqtt_user"]?.trim()
                    // Blank password keeps the current one; clearing the username clears the password too.
                    val pw = if (user != null && user.isEmpty()) "" else p["mqtt_password"]?.takeIf { it.isNotEmpty() }
                    if (broker != null || user != null || pw != null) config.setMqtt(
                        broker ?: config.mqttBroker, user ?: config.mqttUser, pw,
                    )
                    // Built-in renderer connection — same semantics: blank token keeps the current one;
                    // clearing the URL clears the token too (no orphaned HA credential on the panel).
                    val prevHaUrl = config.haUrl
                    val prevHaToken = config.haToken
                    val prevHaRefresh = config.haRefreshToken
                    val prevHaExpiry = config.haTokenExpiry
                    val prevHaClientId = config.haClientId
                    val haUrl = p["ha_url"]?.trim()
                    val haToken = if (haUrl != null && haUrl.isEmpty()) "" else p["ha_token"]?.takeIf { it.isNotEmpty() }
                    if (haUrl != null || haToken != null) config.setHaConnection(
                        haUrl ?: config.haUrl, haToken,
                    )
                    // Refresh token (+ optional access-token expiry) from a provisioning login. Blank keeps the
                    // current; clearing the URL clears it too. When set with an access token + expiry, persist
                    // the whole session so the renderer's lazy refresh has a valid starting point.
                    val clearingHa = haUrl != null && haUrl.isEmpty()
                    val haRefresh = if (clearingHa) "" else p["ha_refresh_token"]?.takeIf { it.isNotEmpty() }
                    haRefresh?.let { config.setHaRefreshToken(it) }
                    val haExpiry = p["ha_token_expiry"]?.trim()?.toLongOrNull()
                    val haClientId = p["ha_client_id"]?.trim()?.let { if (clearingHa) "" else it }
                    haExpiry?.let(config::setHaTokenExpiry)
                    haClientId?.let(config::setHaClientId)
                    if (clearingHa) config.setHaRefreshToken("")
                    // A NEW access token (with no new refresh token beside it) is an explicit re-credential:
                    // drop the stored refresh token + expiry so the renderer actually uses it. Without this a
                    // revoked refresh token could never be cleared from the form ("blank keeps current") and
                    // DashboardAuth would keep preferring the dead refresh model — the auth latch's own "set a
                    // new access token" fix instructions wouldn't work.
                    val refreshCleared = haToken != null && haToken.isNotEmpty() && haRefresh == null && prevHaRefresh.isNotEmpty()
                    if (refreshCleared) { config.setHaRefreshToken(""); config.setHaTokenExpiry(0L) }
                    // A CHANGED built-in-renderer credential/URL takes effect immediately: reloadDashboard is
                    // the privileged-first relaunch (a plain startActivity from a service context is silently
                    // blocked by Android 10+ background-activity-launch rules), flags reload-intent for
                    // onNewIntent, and clears the crash latch — the "fix the token on Configure" path the
                    // latch's on-panel message points at. Change-gated, not presence-gated: the form posts
                    // every key on every save, and relaunching on presence would reload the dashboard on every
                    // unrelated settings save.
                    val haChanged = (haUrl != null && haUrl != prevHaUrl) ||
                        (haToken != null && haToken != prevHaToken) ||
                        (haRefresh != null && haRefresh != prevHaRefresh) || refreshCleared ||
                        (haExpiry != null && haExpiry != prevHaExpiry) ||
                        (haClientId != null && haClientId != prevHaClientId)
                    relaunchForHa = haChanged
                    entityTargetChanged = haChanged
                    // Live-apply a renderer switch: re-anchor HOME to the new renderer and bring it up now —
                    // previously changing "Dashboard app" did nothing until the next boot. Off-thread (su/daemon).
                    // The kiosk/watchdog loops read dashboard_package per tick, so they retarget on their own.
                    relaunchForDash = dashChanged

                    // Per-row "expose to HA" toggles (ha_expose_<key>=true|false) — take effect on the reconfigure.
                    for (name in p.names()) {
                        if (name.startsWith("ha_expose_")) {
                            SettingValue.parseBool(p[name].orEmpty())?.let {
                                config.setHaExposed(name.removePrefix("ha_expose_"), it)
                            }
                        }
                    }
                }
                if (saved) {
                    revisions.snapshot(previous)
                    runCatching {
                        // Home dashboard normally travels through the live MQTT-equivalent path. Apply this
                        // one target-defining value before any renderer effect so owner-scoped filter state
                        // is rebound (or hidden) before a relaunched WebView can observe it.
                        p["home_dashboard"]?.let { posted ->
                            val previousHome = config.homeDashboard
                            check(applySetting("home_dashboard", posted)) { "home_dashboard live apply refused" }
                            homeDashboardAppliedEarly = true
                            homeDashboardChangedEarly = config.homeDashboard != previousHome
                        }
                        if (entityTargetChanged && !homeDashboardChangedEarly) entityLearning.onTargetConfigurationChanged()
                        entityLearningChanged?.let { enabled ->
                            check(entityLearning.setEnabled(enabled)) { "entity-learning transition failed" }
                            entityLearningChanged = null
                        }
                        applyRendererEffects(
                            RendererConfigEffects.coalesce(
                                dashboardChanged = relaunchForDash,
                                credentialChanged = relaunchForHa,
                                zoomChanged = reloadForZoom,
                                fullscreenChanged = relaunchForFullscreen,
                                overscrollChanged = relaunchForOverscroll,
                                darkMode = applyDark,
                            ),
                        )
                    }.onFailure { rendererFailure = it }
                }
                saved
            }
        }
        if (!committed) {
            call.respondText("configuration commit failed\n", status = HttpStatusCode.InternalServerError)
            return
        }
        if (!tameDelta.isEmpty) {
            tameDelta.add.forEach { submitTameMutation(it, TameMutation.TAME) }
            tameDelta.remove.forEach { submitTameMutation(it, TameMutation.UNTAME) }
        }
        // Formerly MQTT-only behaviour settings — applied through the bridge's command path so HTTP
        // and HA behave identically. Registry-validated where the key is registered; invalid skipped.
        val liveApplyFailures = ArrayList<String>()
        for (key in HTTP_LIVE_KEYS) {
            if (key == "home_dashboard" && homeDashboardAppliedEarly) continue
            val raw = p[key] ?: continue
            val spec = SettingsRegistry.spec(key)
            val value = if (spec != null) {
                when (val v = SettingValue.validate(spec, raw)) {
                    is Validation.Ok -> v.normalized
                    is Validation.Bad -> continue
                }
            } else {
                raw.trim()
            }
            if (!applySetting(key, value)) liveApplyFailures += key
        }
        snapInvalidate()
        onReconfigure()
        rendererFailure?.let {
            Log.e(TAG, "configuration committed but renderer preparation failed", it)
            call.respondText(
                "settings saved; renderer preparation failed and remains retryable on service restart\n",
                ContentType.Text.Plain,
                HttpStatusCode.InternalServerError,
            )
            return
        }
        if (liveApplyFailures.isNotEmpty()) {
            val body = "{\"ok\":false,\"status\":\"committed-apply-failed\",\"keys\":${jarr(liveApplyFailures)}}"
            call.respondText(body, ContentType.Application.Json, HttpStatusCode.InternalServerError)
            return
        }
        if (call.request.headers["Accept"]?.contains("application/json") == true) {
            call.respondText(configJson(), ContentType.Application.Json)
        } else {
            call.respondText(
                "<!doctype html><meta charset=utf-8>" +
                    "<meta http-equiv=refresh content='2;url=/'>" +
                    "<body style='font-family:system-ui;background:#111;color:#eee;padding:20px'>" +
                    "settings saved — reconnecting…</body>",
                ContentType.Text.Html,
            )
        }
    }

    /** Admit vendor mutations without creating one coroutine/thread per HTTP request. */
    private fun submitTameMutation(key: String, mutation: TameMutation): Boolean {
        val admission = tameMutations.submit(key, mutation)
        when (admission) {
            KeyedLatestDispatcher.Admission.ACCEPTED -> Unit
            KeyedLatestDispatcher.Admission.COALESCED ->
                FeatureCosts.registry.recordCoalesced(FeatureCostOperation.TAME_MUTATION)
            KeyedLatestDispatcher.Admission.REJECTED,
            KeyedLatestDispatcher.Admission.CLOSED ->
                FeatureCosts.registry.recordDropped(FeatureCostOperation.TAME_MUTATION)
        }
        FeatureCosts.registry.setBacklog(
            FeatureCostOperation.TAME_MUTATION,
            tameMutations.pendingCount(),
        )
        return admission == KeyedLatestDispatcher.Admission.ACCEPTED ||
            admission == KeyedLatestDispatcher.Admission.COALESCED
    }

    private suspend fun respondRemoteAdmission(call: ApplicationCall, command: RemoteControl) {
        val admission = remoteControls.submit(command.key, command)
        when (admission) {
            KeyedLatestDispatcher.Admission.ACCEPTED -> Unit
            KeyedLatestDispatcher.Admission.COALESCED ->
                FeatureCosts.registry.recordCoalesced(FeatureCostOperation.REMOTE_INPUT)
            KeyedLatestDispatcher.Admission.REJECTED,
            KeyedLatestDispatcher.Admission.CLOSED ->
                FeatureCosts.registry.recordDropped(FeatureCostOperation.REMOTE_INPUT)
        }
        FeatureCosts.registry.setBacklog(FeatureCostOperation.REMOTE_INPUT, remoteControls.pendingCount())
        if (admission == KeyedLatestDispatcher.Admission.ACCEPTED ||
            admission == KeyedLatestDispatcher.Admission.COALESCED
        ) {
            call.respondText("accepted\n", status = HttpStatusCode.Accepted)
        } else {
            call.respondText(
                "control queue busy\n",
                status = if (stopping) HttpStatusCode.ServiceUnavailable else HttpStatusCode.Conflict,
            )
        }
    }

    /**
     * Registry metadata for generating the Configure form (type/group/tier/scope/options/range +
     * whether the setting is an HA entity and currently exposed), capability-gated to this panel.
     * Values themselves come from GET /config; this endpoint is metadata only.
     */
    private fun configSchemaJson(): String {
        fun s(v: String) = Json.str(v)
        val caps = snapStaleOk().caps   // capability probes (cpu/adb su) via the snapshot
        val hints = autoHints()   // what blank ("auto") package fields resolve to → field placeholder
        // Include the settable settings PLUS the read-only HA sensors (diagnostics): the latter carry
        // no editable value but still render an expose pip, so the user can opt them into HA.
        val schemaSpecs = SettingsRegistry.SPECS.filter { (!it.readOnly || it.ha != null) && !it.hidden }
        val items = schemaSpecs.joinToString(",") { spec ->
            val opts = spec.options.joinToString(",") { s(it) }
            val isHa = spec.ha != null
            val placeholder = hints[spec.key]?.let { "auto ($it)" }
            "{" +
                "\"key\":${s(spec.key)}," +
                "\"type\":${s(spec.type.name)}," +
                "\"group\":${s(spec.group)}," +
                "\"label\":${s(spec.label)}," +
                "\"help\":${s(spec.help)}," +
                "\"default\":${s(spec.default)}," +
                "\"tier\":${s(spec.tier.name)}," +
                "\"scope\":${s(spec.scope.name)}," +
                "\"secret\":${spec.secret}," +
                "\"readOnly\":${spec.readOnly}," +
                "\"available\":${spec.availableWhen(caps)}," +
                "\"options\":[$opts]," +
                "\"picker\":${spec.picker?.let { s(it) } ?: "null"}," +
                "\"min\":${spec.min?.toString() ?: "null"}," +
                "\"max\":${spec.max?.toString() ?: "null"}," +
                "\"step\":${spec.step?.toString() ?: "null"}," +
                "\"maxLength\":${if (spec.type == SettingType.STRING || spec.type == SettingType.PASSWORD) spec.maxChars else "null"}," +
                "\"ha\":$isHa," +
                "\"exposed\":${if (isHa) config.haExposed(spec.key, spec.haExposedByDefault) else false}," +
                "\"placeholder\":${placeholder?.let { s(it) } ?: "null"}" +
                "}"
        }
        return "[$items]"
    }

    /** A setting's effective current value: controller-sourced live state where it exists, identity
     *  fields resolved (panel_id auto-derives when unset), else the persisted value. */
    private fun effectiveValue(spec: io.github.maxlyth.hapaneld.config.SettingSpec, live: Map<String, String>): String =
        live[spec.key] ?: when (spec.key) {
            "panel_id" -> config.panelId
            "friendly_name" -> config.friendlyName
            else -> config.getRaw(spec)
        }

    /** Registry-driven current values (typed JSON; secrets blanked) for the Configure form. */
    private fun settingsValuesJson(): String {
        fun s(v: String) = Json.str(v)
        val live = snapStaleOk().live   // controller-sourced keys via the snapshot, not fresh su probes
        val parts = SettingsRegistry.settable().joinToString(",") { spec ->
            val raw = effectiveValue(spec, live)
            val v = when {
                spec.secret -> "\"\""
                spec.type == SettingType.BOOL -> if (raw.toBoolean()) "true" else "false"
                spec.type == SettingType.INT || spec.type == SettingType.LONG ->
                    raw.toLongOrNull()?.toString() ?: s(raw)
                spec.type == SettingType.FLOAT -> raw.toDoubleOrNull()?.toString() ?: s(raw)
                else -> s(raw)
            }
            "${s(spec.key)}:$v"
        }
        return "{$parts}"
    }

    /** Per-key HA-exposure flags for every HA-capable setting (for the inline expose pips). */
    private fun haExposeJson(): String {
        val parts = SettingsRegistry.SPECS.filter { it.ha != null }.joinToString(",") { spec ->
            "\"${spec.key}\":${config.haExposed(spec.key, spec.haExposedByDefault)}"
        }
        return "{$parts}"
    }

    /** Inject a tap at device pixel (x,y) for the interactive screenshot. */
    private fun injectTap(x: Float, y: Float): Boolean = interactive.tap(x, y)

    private fun exposureSpec(key: String) = key.takeIf { it.startsWith("ha_expose_") }
        ?.removePrefix("ha_expose_")
        ?.let(SettingsRegistry::spec)
        ?.takeIf { it.ha != null }

    // ---- config bundles (export / validated import) + on-panel revision history ----------------

    /** Current registry values as a flat map (skips transient inputs; controller-sourced settings
     *  read their live state). The basis for export, the pre-change snapshot, and the dry-run diff. */
    private fun currentValues(): Map<String, String> {
        val live = liveValues()
        val m = LinkedHashMap<String, String>()
        for (spec in SettingsRegistry.settable()) {
            if (spec.transient) continue
            m[spec.key] = effectiveValue(spec, live)
        }
        SettingsRegistry.SPECS.filter { it.ha != null }.forEach { spec ->
            m["ha_expose_${spec.key}"] = config.haExposed(spec.key, spec.haExposedByDefault).toString()
        }
        return m
    }

    private fun configConcurrencyHash(values: Map<String, String> = currentValues()): String =
        io.github.maxlyth.hapaneld.config.ConfigHash.of(configConcurrencyValues(values))

    private fun revisionValues(
        values: Map<String, String> = currentValues(),
        state: DashboardEntityBackupState = config.dashboardEntityBackupState(),
    ): Map<String, String> = LinkedHashMap(values).apply {
        put("$ENTITY_REVISION_PREFIX.instance_key", state.instanceKey)
        put("$ENTITY_REVISION_PREFIX.instance_origin", state.instanceOrigin)
        put("$ENTITY_REVISION_PREFIX.instance_uuid", state.instanceUuid)
        put("$ENTITY_REVISION_PREFIX.dashboard_path", state.dashboardPath)
        put("$ENTITY_REVISION_PREFIX.filter_ids", state.filterIds)
        put("$ENTITY_REVISION_PREFIX.filter_enabled", state.filterEnabled.toString())
        put("$ENTITY_REVISION_PREFIX.filter_owner", state.filterOwner)
        put("$ENTITY_REVISION_PREFIX.learning_applied", state.learningApplied.toString())
        put("$ENTITY_REVISION_PREFIX.applied_owner", state.appliedOwner)
        put("$ENTITY_REVISION_PREFIX.overrides", state.overrides)
        put("$ENTITY_REVISION_PREFIX.override_owner", state.overrideOwner)
    }

    private fun revisionEntityState(values: Map<String, String>): DashboardEntityBackupState? {
        val fields = values.filterKeys { it.startsWith("$ENTITY_REVISION_PREFIX.") }
        if (fields.isEmpty()) return null
        val obj = org.json.JSONObject()
        for ((key, value) in fields) {
            val name = key.removePrefix("$ENTITY_REVISION_PREFIX.")
            obj.put(name, if (name == "filter_enabled" || name == "learning_applied") {
                SettingValue.parseBool(value) ?: return null
            } else value)
        }
        return runCatching { planEntityBackup(obj) }.getOrNull()
    }

    /** Export a versioned config bundle. Secrets are excluded unless `?include_secrets=1`. */
    private suspend fun handleConfigExport(call: ApplicationCall) {
        val includeSecrets = call.request.queryParameters["include_secrets"] == "1"
        val live = liveValues()
        val values = LinkedHashMap<String, String>()
        for (spec in SettingsRegistry.settable()) {
            if (spec.transient) continue
            if (spec.secret && !includeSecrets) continue
            values[spec.key] = effectiveValue(spec, live)
        }
        SettingsRegistry.SPECS.filter { it.ha != null }.forEach { spec ->
            values["ha_expose_${spec.key}"] = config.haExposed(spec.key, spec.haExposedByDefault).toString()
        }
        val bundle = ConfigBundle.fromValues(
            values, exportedAt = System.currentTimeMillis().toString(), exportedBy = config.panelId,
        )
        call.response.headers.append("Content-Disposition", "attachment; filename=\"${config.panelId}-config.json\"")
        call.respondText(bundle.serialize(), ContentType.Application.Json)
    }

    /**
     * Bundle import — BEST-EFFORT by design (a bundle exported from different hardware or a different
     * ha-paneld version must still restore what it can). Parse → migrate to the current schema →
     * scope/secret filter (`?mode=fleet` applies only PORTABLE, non-secret keys; default `restore`
     * applies everything) → validate per-key against the registry: valid keys apply, invalid keys are
     * reported in `errors` and skipped, unknown keys warn and skip. `?strict=1` restores the old
     * all-or-nothing validation behaviour. Apply is ordered in two phases: atomically commit ordinary
     * preferences, then apply controller/hardware-backed live settings and reconfigure. The latter
     * cannot be rolled back across Android settings, sysfs, services, and hardware. `?dry_run=1`
     * returns the diff without writing.
     * Status: "applied" (all valid), "partial" (some skipped as invalid), "rejected" (nothing usable
     * or strict mode with any error).
     */
    private suspend fun handleConfigImport(call: ApplicationCall) {
        val body = when (val receipt = receiveBoundedBody(call, MAX_CONFIG_IMPORT_BYTES)) {
            is BoundedBodyReceipt.Received -> String(receipt.bytes, Charsets.UTF_8)
            BoundedBodyReceipt.TooLarge -> {
                call.respondText("""{"status":"too-large"}""", ContentType.Application.Json, HttpStatusCode.PayloadTooLarge)
                return
            }
            BoundedBodyReceipt.TimedOut -> {
                call.respondText("""{"status":"timeout"}""", ContentType.Application.Json, HttpStatusCode.RequestTimeout)
                return
            }
        }
        val bundle = ConfigBundle.parse(body)
        if (bundle == null) {
            call.respondText("""{"status":"bad-bundle"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return
        }
        if (bundle.kind != ConfigBundle.KIND_CONFIG || bundle.schema < 1) {
            call.respondText("""{"status":"wrong-kind-or-schema"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            return
        }
        val (migrated, warnings) = Migrations.migrate(bundle.schema, bundle.values)
        val fleet = call.request.queryParameters["mode"] == "fleet"
        val dryRun = call.request.queryParameters["dry_run"] == "1"
        val expectedConfig = call.request.queryParameters["expected_cfg"]?.trim().orEmpty()
        if (expectedConfig.isNotEmpty() && !expectedConfig.matches(Regex("^[a-f0-9]{8}$"))) {
            call.respondText(
                """{"status":"bad-expected-cfg"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return
        }
        val accepted = LinkedHashMap<String, String>()
        val skipped = ArrayList<String>()
        val errors = ArrayList<String>()
        val warn = warnings.toMutableList()
        for ((key, raw) in migrated) {
            val spec = SettingsRegistry.spec(key)
            val exposedSpec = exposureSpec(key)
            if (exposedSpec != null) {
                val normalized = SettingValue.parseBool(raw)?.toString()
                if (normalized == null) errors.add("$key: expected a boolean") else accepted[key] = normalized
                continue
            }
            if (spec == null) { warn.add("unknown key skipped: $key"); continue }
            if (spec.readOnly || spec.transient) { skipped.add(key); continue }
            if (fleet && (spec.scope != Scope.PORTABLE || spec.secret)) { skipped.add(key); continue }
            when (val v = SettingValue.validate(spec, raw)) {
                is Validation.Ok -> {
                    // A blank portable HA URL means “renderer not configured” on the source panel. In
                    // fleet mode it must not clear a target's URL and, through import dependencies, its
                    // device-local OAuth credentials. A non-blank common endpoint remains portable.
                    if (fleetImportPreservesTargetLocalValue(fleet, key, v.normalized)) {
                        skipped.add(key)
                        warn.add("blank ha_url skipped in fleet mode to preserve target-local Home Assistant login")
                    } else {
                        accepted[key] = v.normalized
                    }
                }
                is Validation.Bad -> errors.add(v.reason)
            }
        }
        val strict = call.request.queryParameters["strict"] == "1"
        if ((strict && errors.isNotEmpty()) || (accepted.isEmpty() && errors.isNotEmpty())) {
            call.respondText(importJson("rejected", emptyList(), skipped, warn, errors), ContentType.Application.Json, HttpStatusCode.UnprocessableEntity)
            return
        }
        if (dryRun) {
            val current = currentValues()
            call.respondText(
                configDryRunJson(
                    configPreviewDiff(current, accepted),
                    skipped,
                    warn + errors.map { "would skip (invalid): $it" },
                    configConcurrencyHash(current),
                ),
                ContentType.Application.Json,
            )
            return
        }
        if (accepted.isEmpty()) {
            call.respondText(importJson("no-op", emptyList(), skipped, warn, errors), ContentType.Application.Json)
            return
        }
        when (applyAccepted(accepted, expectedConfig.ifEmpty { null })) {
            ApplyAcceptedResult.STALE -> {
                val actual = configConcurrencyHash()
                call.respondText(
                    """{"status":"stale-preview","expected_cfg":${jsonStr(expectedConfig)},"actual_cfg":${jsonStr(actual)}}""",
                    ContentType.Application.Json,
                    HttpStatusCode.Conflict,
                )
                return
            }
            ApplyAcceptedResult.COMMIT_FAILED -> {
                call.respondText(
                    importJson("error", emptyList(), skipped, warn, listOf("configuration commit failed")),
                    ContentType.Application.Json,
                    HttpStatusCode.InternalServerError,
                )
                return
            }
            ApplyAcceptedResult.APPLIED -> Unit
        }
        val status = if (errors.isEmpty()) "applied" else "partial"
        call.respondText(importJson(status, accepted.keys.toList(), skipped, warn, errors), ContentType.Application.Json)
    }

    /** Apply a validated value set in two ordered phases: snapshot current → atomically commit ordinary
     *  preference fields → run live controller/hardware persistence and side-effects → reconfigure.
     *  External state cannot be rolled back and only starts after a successful preference commit.
     *  Returns false without starting side-effects when the preference commit fails. */
    private enum class ApplyAcceptedResult { APPLIED, STALE, COMMIT_FAILED }

    private suspend fun applyAccepted(
        accepted: Map<String, String>,
        expectedConfig: String? = null,
        expectedRevision: String? = null,
        entityState: DashboardEntityBackupState? = null,
        onDurableRevision: (String) -> Unit = {},
        afterCommitBeforeRenderer: (RendererConfigEffects, String) -> Unit = { _, _ -> },
        afterApply: () -> Unit = {},
    ): ApplyAcceptedResult = withContext(Dispatchers.IO) {
        rendererPreparation.transaction {
            var earlyResult: ApplyAcceptedResult? = null
            var committed: AcceptedCommit? = null
            config.synchronizedTransaction {
                if (expectedConfig != null &&
                    configConcurrencyHash() != expectedConfig
                ) {
                    earlyResult = ApplyAcceptedResult.STALE
                    return@synchronizedTransaction
                }
                if (expectedRevision != null &&
                    io.github.maxlyth.hapaneld.config.ConfigHash.of(revisionValues()) != expectedRevision
                ) {
                    earlyResult = ApplyAcceptedResult.STALE
                    return@synchronizedTransaction
                }
                val previous = ConfigBundle.fromValues(
                    revisionValues(), kind = ConfigBundle.KIND_REVISION,
                    exportedAt = System.currentTimeMillis().toString(), exportedBy = config.panelId,
                )
                val editor = config.editor()
                val live = ArrayList<Pair<String, String>>()
                for ((key, value) in accepted) {
                    when {
                        key == "panel_id" -> config.stagePanelId(editor, value)
                        exposureSpec(key) != null -> editor.putBoolean(key, SettingValue.parseBool(value) == true)
                        // EntityLearningManager owns enable/disable transition semantics and commits this
                        // preference after the ordinary bundle transaction succeeds.
                        key == "dashboard_entity_learning" -> Unit
                        key in HTTP_LIVE_KEYS -> live.add(key to value)
                        else -> SettingsRegistry.spec(key)?.let { config.stage(editor, it, value) }
                    }
                }
                config.stageImportDependencies(editor, accepted)
                entityState?.let { config.stageDashboardEntityBackupState(editor, it) }
                if (!config.commit(editor)) {
                    earlyResult = ApplyAcceptedResult.COMMIT_FAILED
                    return@synchronizedTransaction
                }
                committed = AcceptedCommit(
                    previous = previous,
                    live = live,
                    effects = RendererConfigEffects.between(previous.values, accepted),
                )
            }
            earlyResult?.let { return@transaction it }
            val phase = requireNotNull(committed)
            revisions.snapshot(phase.previous)
            var rendererFailure: Throwable? = null
            runCatching {
                // The base transaction deliberately excludes live keys. Publish every actually durable
                // generation to rollback ownership, then converge all live values before any external
                // Companion/profile work can fail.
                onDurableRevision(io.github.maxlyth.hapaneld.config.ConfigHash.of(revisionValues()))
                val previousHome = phase.previous.values["home_dashboard"].orEmpty()
                phase.live.firstOrNull { it.first == "home_dashboard" }?.let { (_, value) ->
                    val applied = applySetting("home_dashboard", value)
                    onDurableRevision(io.github.maxlyth.hapaneld.config.ConfigHash.of(revisionValues()))
                    check(applied) { "home_dashboard live apply refused" }
                }
                for ((k, v) in phase.live) if (k != "home_dashboard") {
                    val applied = applySetting(k, v)
                    onDurableRevision(io.github.maxlyth.hapaneld.config.ConfigHash.of(revisionValues()))
                    check(applied) { "$k live apply refused" }
                }
                val homeChanged = normalizeDashboardEntityPath(config.homeDashboard) !=
                    normalizeDashboardEntityPath(previousHome)
                val credentialsChanged = RendererConfigEffects.credentialsChanged(phase.previous.values, accepted)
                if (homeChanged || credentialsChanged) entityLearning.onTargetConfigurationChanged()
                accepted["dashboard_entity_learning"]?.let { raw ->
                    val enabled = SettingValue.parseBool(raw)
                        ?: error("validated automatic entity-filter value became invalid")
                    if (enabled != config.dashboardEntityLearningEnabled && !entityLearning.setEnabled(enabled)) {
                        error("entity-learning transition failed")
                    }
                    onDurableRevision(io.github.maxlyth.hapaneld.config.ConfigHash.of(revisionValues()))
                }
                // All accepted values are now durable. This same fence remains exact if either the
                // Companion/renderer callback or the later profile callback fails.
                afterCommitBeforeRenderer(
                    phase.effects,
                    io.github.maxlyth.hapaneld.config.ConfigHash.of(revisionValues()),
                )
                applyRendererEffects(phase.effects)
            }.onFailure { rendererFailure = it }
            snapInvalidate()
            onReconfigure()
            rendererFailure?.let { throw it }
            afterApply()
            ApplyAcceptedResult.APPLIED
        }
    }

    private data class AcceptedCommit(
        val previous: ConfigBundle,
        val live: List<Pair<String, String>>,
        val effects: RendererConfigEffects,
    )

    /** Apply renderer changes only after their preferences commit. A dashboard switch dominates all
     *  reloads; otherwise a reload dominates a foreground relaunch, so one request schedules at most
     *  one renderer operation. */
    private fun applyRendererEffects(effects: RendererConfigEffects) {
        effects.darkMode?.let { dark ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                    if (dark) androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                    else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO,
                )
            }
        }
        when {
            effects.dashboardChanged -> {
                val result = rendererPreparation.launchConfigured(
                    ensureHome = { pkg, ready -> system.ensureDashboardHome(pkg, ready) },
                    launchHome = { pkg -> system.launchHome(pkg) },
                )
                requireRendererResult(result)
                Log.i(TAG, "renderer switch completed (preparation=$result)")
            }
            effects.reloadBuiltin && config.dashboardPackage == SystemController.BUILTIN_DASHBOARD -> {
                val result = rendererPreparation.prepareIfNeeded()
                requireRendererResult(result)
                system.reloadDashboard(SystemController.BUILTIN_DASHBOARD)
            }
            effects.relaunchBuiltin && config.dashboardPackage == SystemController.BUILTIN_DASHBOARD -> {
                val result = rendererPreparation.prepareIfNeeded()
                requireRendererResult(result)
                system.launchHome(SystemController.BUILTIN_DASHBOARD)
            }
        }
    }

    private fun requireRendererResult(result: RendererPreparationCoordinator.Result) {
        check(result != RendererPreparationCoordinator.Result.PERSIST_FAILED) {
            "built-in renderer preparation did not commit"
        }
        check(result != RendererPreparationCoordinator.Result.CLOSED) {
            "renderer lifecycle is stopping"
        }
    }

    // ---- Full panel backup / restore (device-state bundle) ------------------------------------------
    //
    // A backup bundle = ha-paneld config (all settable keys incl. secrets) + optionally the HA Companion's
    // login files (its HomeAssistantDB carries HA access/refresh tokens). Sealed when a passphrase is
    // supplied; an explicit, prominently warned plaintext export remains supported for local recovery.
    // Companion capture/restore needs su; the SELinux context matters (per-app MLS categories), so restore
    // reapplies the LIVE dir's owner uid + context rather than trusting restorecon.

    // Deliberately NOT the -wal/-shm sidecars: capture checkpoints the WAL into the main DB first, so the
    // single HomeAssistantDB file is complete. Writing back a STALE -wal/-shm makes SQLite discard it and
    // lose the login (the `servers` row lives in the WAL until a checkpoint) — validated the hard way.
    private data class CapturedCompanionFile(val relativePath: String, val file: File)
    private data class CapturedCompanion(
        val packageName: String,
        val files: List<CapturedCompanionFile>,
        val owner: java.io.Closeable,
    )
    private data class BackupArchiveParts(
        val manifest: String,
        val sources: List<PanelBackup.ArchiveSource>,
        val ownedFiles: List<File>,
    )

    /** Build a file-backed v2 container. Companion bytes are raw ZIP entries, not base64 JSON. */
    private fun buildBackupArtifact(includeCompanion: Boolean, passphrase: String): PanelBackup.Artifact {
        if (cacheDir.usableSpace < backupStagingRequirement(includeCompanion, passphrase.isNotEmpty())) {
            throw CompanionBackupUnavailable("Insufficient storage to stage a backup safely")
        }
        val capture = if (includeCompanion) captureCompanion() else null
        val plain = File.createTempFile("panel-backup-", ".zip", cacheDir)
        var sealed: File? = null
        var parts: BackupArchiveParts? = null
        try {
            parts = backupArchiveParts(capture)
            plain.outputStream().use { output ->
                PanelBackup.writeArchive(
                    output,
                    parts.manifest,
                    parts.sources,
                    MAX_BACKUP_MANIFEST_BYTES,
                )
            }
            val plaintextLimit = if (passphrase.isEmpty()) MAX_RESTORE_BYTES
                else PanelBackup.maxSealablePlaintextBytes(MAX_RESTORE_BYTES)
            if (plain.length() !in 1..plaintextLimit) throw ByteLimitExceeded(plaintextLimit)
            if (passphrase.isEmpty()) return PanelBackup.Artifact(plain, "zip")
            sealed = File.createTempFile("panel-backup-", ".hpb", cacheDir)
            plain.inputStream().use { input ->
                sealed.outputStream().use { output -> PanelBackup.seal(input, output, passphrase) }
            }
            if (sealed.length() !in 1..MAX_RESTORE_BYTES) throw ByteLimitExceeded(MAX_RESTORE_BYTES)
            plain.delete()
            return PanelBackup.Artifact(sealed)
        } catch (error: Exception) {
            plain.delete()
            sealed?.delete()
            throw error
        } finally {
            parts?.ownedFiles?.forEach(File::delete)
            capture?.owner?.close()
        }
    }

    /** Keep the v2 manifest small: large profile and owner-scoped entity strings are bounded ZIP entries. */
    private fun backupArchiveParts(companion: CapturedCompanion?): BackupArchiveParts {
        val owned = ArrayList<File>(3)
        return try {
            fun textEntry(name: String, prefix: String, text: String, maxBytes: Long): PanelBackup.ArchiveSource {
                val file = File.createTempFile(prefix, ".payload", cacheDir).also(owned::add)
                file.writer(Charsets.UTF_8).use { it.write(text) }
                if (file.length() > maxBytes) throw ByteLimitExceeded(maxBytes)
                return PanelBackup.ArchiveSource(name, file)
            }
            val entity = config.dashboardEntityBackupState()
            val filter = textEntry(ENTITY_FILTER_BACKUP_ENTRY, "entity-filter-backup-", entity.filterIds, MAX_ENTITY_BACKUP_TEXT_BYTES)
            val overrides = textEntry(ENTITY_OVERRIDES_BACKUP_ENTRY, "entity-overrides-backup-", entity.overrides, MAX_ENTITY_BACKUP_TEXT_BYTES)
            val profile = profileAdmin?.exportBackup()?.let {
                textEntry(PROFILE_BACKUP_ENTRY, "profile-backup-", it.toJson().toString(), MAX_PROFILE_BACKUP_ENTRY_BYTES)
            }
            val sources = ArrayList<PanelBackup.ArchiveSource>(6)
            sources.add(filter)
            sources.add(overrides)
            profile?.let(sources::add)
            sources += companion?.files.orEmpty().mapIndexed { index, file ->
                PanelBackup.ArchiveSource("companion/$index", file.file)
            }
            BackupArchiveParts(
                manifest = backupManifest(companion, entity, filter.file.length(), overrides.file.length(), profile?.file?.length()),
                sources = sources,
                ownedFiles = owned,
            )
        } catch (error: Exception) {
            owned.forEach(File::delete)
            throw error
        }
    }

    /** Build bounded metadata only. A requested Companion capture is all-or-error. */
    private fun backupManifest(
        companion: CapturedCompanion?,
        entity: DashboardEntityBackupState,
        filterBytes: Long,
        overrideBytes: Long,
        profileBytes: Long?,
    ): String {
        val live = liveValues()
        val cfg = SettingsRegistry.settable()
            .filterNot { it.transient || it.key in ENTITY_STATE_CONFIG_KEYS }
            .joinToString(",") { s -> "${jsonStr(s.key)}:${jsonStr(effectiveValue(s, live))}" }
        val exposures = SettingsRegistry.SPECS.filter { it.ha != null }
            .joinToString(",") { spec ->
                "${jsonStr("ha_expose_${spec.key}")}:${jsonStr(config.haExposed(spec.key, spec.haExposedByDefault).toString())}"
            }
        val sb = StringBuilder("{\"kind\":\"ha-paneld-backup\",\"schema\":${SettingsRegistry.SCHEMA}")
        sb.append(",\"panel_id\":${jsonStr(config.panelId)},\"created\":${jsonStr(System.currentTimeMillis().toString())}")
        sb.append(",\"config\":{").append(listOf(cfg, exposures).filter { it.isNotEmpty() }.joinToString(",")).append("}")
        sb.append(",\"entity_state\":").append(entityBackupArchiveJson(entity, filterBytes, overrideBytes))
        if (profileBytes != null) {
            sb.append(",\"profiles\":{\"entry\":").append(jsonStr(PROFILE_BACKUP_ENTRY))
                .append(",\"size\":").append(profileBytes).append('}')
        }
        if (companion != null) {
            val files = companion.files.mapIndexed { index, file ->
                "{\"rel\":${jsonStr(file.relativePath)},\"entry\":${jsonStr("companion/$index")},\"size\":${file.file.length()}}"
            }.joinToString(",")
            sb.append(",\"companion\":{\"pkg\":${jsonStr(companion.packageName)},\"files\":[")
                .append(files).append("]}")
        }
        return sb.append("}").toString()
    }

    /** Capture descriptor-opened raw files, then checkpoint only the private-cache SQLite copy. */
    private fun captureCompanion(): CapturedCompanion {
        val pkg = CompanionInstaller.installedPkg(appContext)
            ?: throw CompanionBackupUnavailable("HA Companion is not installed")
        if (pkg !in CompanionInstaller.SUPPORTED_PACKAGES || !AndroidInput.isPackage(pkg)) {
            throw CompanionBackupUnavailable("HA Companion package is not supported")
        }
        if (!ensureCompanionHelper()) {
            throw CompanionBackupUnavailable("HA Companion backup needs the current ha-paneld helper")
        }
        val lease = CompanionDataOperationGate.acquire(pkg)
            ?: throw CompanionBackupUnavailable("Another Companion data operation is running")
        if (!companionDataOperationState.arm()) {
            lease.close()
            throw CompanionBackupUnavailable("Companion operation safety marker could not be persisted")
        }
        var helperCapture: CompanionHelperProtocol.Capture? = null
        var needsCompanionRecovery = false
        var leaseTransferred = false
        try {
            val result = HelperClient.backupCompanion(pkg, cacheDir)
            val capture = when (result) {
                is CompanionHelperProtocol.BackupResult.Success -> result.capture.also {
                    needsCompanionRecovery = !it.relaunched
                }
                CompanionHelperProtocol.BackupResult.Busy -> {
                    leaseTransferred = settleCompanionOperationLease(
                        lease,
                        companionDataOperationState,
                        possiblyInFlight = true,
                        retain = { retained, after -> retainCompanionLeaseUntilHelperIdle(retained, after) },
                        afterRelease = {},
                    )
                    throw CompanionBackupUnavailable("Companion helper is busy")
                }
                CompanionHelperProtocol.BackupResult.NotSubmitted ->
                    throw CompanionBackupUnavailable("Companion helper is unavailable")
                is CompanionHelperProtocol.BackupResult.Failed -> {
                    needsCompanionRecovery = result.relaunchFailed
                    throw CompanionBackupUnavailable(
                        if (result.relaunchFailed) "Companion capture failed and relaunch was not confirmed"
                        else "Companion capture failed",
                    )
                }
                CompanionHelperProtocol.BackupResult.Indeterminate -> {
                    leaseTransferred = settleCompanionOperationLease(
                        lease,
                        companionDataOperationState,
                        possiblyInFlight = true,
                        retain = { retained, after -> retainCompanionLeaseUntilHelperIdle(retained, after) },
                        afterRelease = {
                            system.launchHome(pkg)
                            if (system.resolveDashboard(config.dashboardPackage) != pkg) {
                                system.launchHome(config.dashboardPackage)
                            }
                        },
                    )
                    throw CompanionBackupUnavailable("Companion capture result was indeterminate")
                }
            }
            helperCapture = capture
            val database = capture.files[CompanionRestore.DATABASE_FILE]
                ?: throw CompanionBackupUnavailable("Companion login database was not captured")
            if (!io.github.maxlyth.hapaneld.backup.CompanionDatabasePreparation.checkpointCapturedDatabase(
                    database,
                    capture.files[CompanionHelperProtocol.DATABASE_WAL_FILE],
                    capture.files[CompanionHelperProtocol.DATABASE_SHM_FILE],
                )
            ) throw CompanionBackupUnavailable("Companion login database could not be checkpointed safely")

            val captured = CompanionRestore.ALLOWED_FILES.mapNotNull { relative ->
                capture.files[relative]?.let { CapturedCompanionFile(relative, it) }
            }
            val total = captured.sumOf { it.file.length() }
            if (captured.none { it.relativePath == CompanionRestore.DATABASE_FILE } ||
                captured.any { it.file.length() !in 1..CompanionRestore.maxBytes(it.relativePath) } ||
                total > MAX_COMPANION_BACKUP_BYTES
            ) throw CompanionBackupUnavailable("Companion capture exceeded portable backup bounds")
            if (!capture.relaunched) {
                throw CompanionBackupUnavailable("Companion was captured but helper relaunch failed")
            }
            helperCapture = null
            return CapturedCompanion(pkg, captured, capture)
        } finally {
            if (!leaseTransferred) {
                settleCompanionOperationLease(
                    lease,
                    companionDataOperationState,
                    possiblyInFlight = false,
                    retain = { retained, after -> retainCompanionLeaseUntilHelperIdle(retained, after) },
                    afterRelease = {
                        // The helper always launches Companion to clear Android's stopped state. Restore the
                        // configured dashboard after releasing suppression when Companion is not it.
                        if (needsCompanionRecovery) system.launchHome(pkg)
                        if (system.resolveDashboard(config.dashboardPackage) != pkg) {
                            system.launchHome(config.dashboardPackage)
                        }
                    },
                )
            }
            helperCapture?.close()
        }
    }

    /** Restore endpoint: decrypt + validate; ?dry_run=1 reports contents without writing. A real restore is
     *  DESTRUCTIVE (config rewrite + Companion force-stop/rewrite), run off-thread with InstallProgress. */
    private suspend fun handleRestore(call: ApplicationCall) {
        val pw = call.request.headers["X-Backup-Passphrase"].orEmpty()
        val dryRun = call.request.queryParameters["dry_run"] == "1"
        // Claim the shared destructive-operation lane before buffering, decrypting, or parsing a bundle.
        // Otherwise several losing requests can each consume 64 MiB and expensive KDF/JSON work before
        // discovering that another restore/install already owns admission.
        val progress = InstallProgress.start(if (dryRun) "Restore preview" else "Restore")
            ?: return call.respondText(
                """{"status":"busy"}""",
                ContentType.Application.Json,
                HttpStatusCode.Conflict,
            )
        var transferredToJob = false
        var requestAccepted = false
        val restoreFiles = ArrayList<File>(4)
        var retainedCompanionPlan: CompanionRestore.Plan? = null
        try {
            val receivedFile = File.createTempFile("panel-restore-", ".upload", cacheDir).also(restoreFiles::add)
            val stagingLimit = restoreBodyStagingLimit(cacheDir.usableSpace)
            val declaredBytes = call.request.headers["Content-Length"]?.toLongOrNull()
            if (stagingLimit <= 0L || (declaredBytes != null && declaredBytes > stagingLimit)) {
                return call.respondText(
                    """{"ok":false,"error":"insufficient-storage"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.InsufficientStorage,
                )
            }
            try {
                withContext(Dispatchers.IO) {
                    call.receiveStream().use { input ->
                        receivedFile.outputStream().use { output ->
                            DeadlineBoundedBody.copy(
                                input,
                                output,
                                stagingLimit,
                                RESTORE_BODY_RECEIPT_DEADLINE_MS,
                            )
                        }
                    }
                }
            } catch (_: ByteLimitExceeded) {
                val storageBound = stagingLimit < MAX_RESTORE_BYTES
                return call.respondText(
                    if (storageBound) """{"ok":false,"error":"insufficient-storage"}"""
                    else """{"ok":false,"error":"bundle-too-large"}""",
                    ContentType.Application.Json,
                    if (storageBound) HttpStatusCode.InsufficientStorage else HttpStatusCode.PayloadTooLarge,
                )
            } catch (_: BodyReceiptTimeout) {
                return call.respondText(
                    """{"ok":false,"error":"bundle-timeout"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.RequestTimeout,
                )
            }
            if (receivedFile.length() <= 0L) return call.respondText(
                """{"ok":false,"error":"not a ha-paneld backup"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            // GCM decryption writes to a private temporary file and the tag must authenticate fully before
            // any JSON is parsed or a restore plan can be applied.
            val plainFile = if (PanelBackup.isSealed(receivedFile)) {
                if (pw.isEmpty()) return call.respondText(
                    """{"ok":false,"error":"this bundle is encrypted — enter its passphrase"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                val decrypted = File.createTempFile("panel-restore-", ".plain", cacheDir).also(restoreFiles::add)
                val opened = try {
                    withContext(Dispatchers.IO) {
                        receivedFile.inputStream().use { input ->
                            decrypted.outputStream().use { output ->
                                PanelBackup.open(input, output, pw, MAX_RESTORE_BYTES)
                            }
                        }
                    }
                } catch (_: ByteLimitExceeded) {
                    return call.respondText(
                        """{"ok":false,"error":"bundle-too-large"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.PayloadTooLarge,
                    )
                }
                if (!opened) return call.respondText(
                    """{"ok":false,"error":"wrong passphrase or corrupt bundle"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                decrypted
            } else receivedFile
            val archiveManifest = PanelBackup.readManifest(plainFile, MAX_BACKUP_MANIFEST_BYTES)
            // Legacy v1 embeds Companion files as base64 inside one JSON object. JSONObject necessarily
            // holds both the source text and parsed strings, so keep that compatibility path under a
            // much smaller semantic ceiling. v2 archives carry large payloads as streamed ZIP entries.
            if (archiveManifest == null && plainFile.length() > MAX_LEGACY_BACKUP_JSON_BYTES) {
                return call.respondText(
                    """{"ok":false,"error":"legacy backup is too large; create a new backup before restoring"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.PayloadTooLarge,
                )
            }
            val obj = runCatching {
                val json = archiveManifest ?: String(
                    plainFile.inputStream().use { BoundedStreams.readBytes(it, MAX_LEGACY_BACKUP_JSON_BYTES) },
                    Charsets.UTF_8,
                )
                org.json.JSONObject(json)
            }.getOrNull()
            if (obj == null || obj.optString("kind") != "ha-paneld-backup") {
                return call.respondText(
                    """{"ok":false,"error":"not a ha-paneld backup"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
            }
            val cfgObj = obj.optJSONObject("config")
                ?: return call.respondText(
                    """{"ok":false,"error":"backup contains no config object"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
            val backupSchema = obj.optInt("schema", -1)
            if (backupSchema < 1) return call.respondText(
                """{"ok":false,"error":"backup contains no valid schema"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            val configPlan = planRestoreConfig(cfgObj, backupSchema)
            if (configPlan.errors.isNotEmpty()) {
                return call.respondText(
                    """{"ok":false,"error":"invalid backup config","errors":${jarr(configPlan.errors)}}""",
                    ContentType.Application.Json,
                    HttpStatusCode.UnprocessableEntity,
                )
            }
            val entityObj = obj.optJSONObject("entity_state")
            if (obj.has("entity_state") && entityObj == null) return call.respondText(
                """{"ok":false,"error":"invalid entity_state object"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            val profilesObj = obj.optJSONObject("profiles")
            if (obj.has("profiles") && profilesObj == null) return call.respondText(
                """{"ok":false,"error":"invalid profiles object"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            val comp = obj.optJSONObject("companion")
            if (obj.has("companion") && comp == null) {
                return call.respondText(
                    """{"ok":false,"error":"Invalid Companion restore section"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
            }
            val archiveEntries = if (archiveManifest != null) {
                runCatching { declaredArchiveEntries(entityObj, profilesObj, comp) }.getOrNull()
                    ?: return call.respondText(
                        """{"ok":false,"error":"invalid backup archive metadata"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest,
                    )
            } else emptySet()
            if (archiveManifest != null && !PanelBackup.extractArchive(plainFile, emptyList(), archiveEntries)) {
                return call.respondText(
                    """{"ok":false,"error":"backup archive contains missing or unexpected files"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
            }
            val entityState = entityObj?.let {
                runCatching {
                    if (archiveManifest != null && it.has("filter_ids_entry")) {
                        planEntityArchive(it, plainFile, archiveEntries)
                    } else {
                        planEntityBackup(it)
                    }
                }.getOrNull()
            }
            if (entityObj != null && entityState == null) return call.respondText(
                """{"ok":false,"error":"invalid owner-scoped entity state"}""",
                ContentType.Application.Json,
                HttpStatusCode.UnprocessableEntity,
            )
            if (entityState == null && (
                    configPlan.values["dashboard_entity_overrides"].orEmpty().isNotBlank() ||
                        configPlan.values["dashboard_entity_learning_applied"] == "true"
                    )
            ) return call.respondText(
                """{"ok":false,"error":"entity state is missing its owner namespace"}""",
                ContentType.Application.Json,
                HttpStatusCode.UnprocessableEntity,
            )
            val profilePayload = profilesObj?.let {
                if (archiveManifest != null && it.has("entry")) {
                    readProfileArchive(it, plainFile, archiveEntries)
                } else {
                    ProfileBackup.fromJson(it)
                }
            }
            if (profilesObj != null && profilePayload == null) return call.respondText(
                """{"ok":false,"error":"invalid profile archive entry"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            if (profilePayload != null && profilePayload.payload == null) return call.respondText(
                """{"ok":false,"error":"invalid profile catalog","errors":${jarr(profilePayload.issues.map(::profileIssueText))}}""",
                ContentType.Application.Json,
                HttpStatusCode.UnprocessableEntity,
            )
            if (profilePayload?.payload != null && profileAdmin == null) return call.respondText(
                """{"ok":false,"error":"profile catalog restore is unavailable"}""",
                ContentType.Application.Json,
                HttpStatusCode.ServiceUnavailable,
            )
            val profilePlan = profilePayload?.payload?.let { requireNotNull(profileAdmin).planBackupRestore(it) }
            if (profilePlan != null && !profilePlan.valid) return call.respondText(
                """{"ok":false,"error":"profile catalog is not restorable","errors":${jarr(profilePlan.issues.map(::profileIssueText))}}""",
                ContentType.Application.Json,
                HttpStatusCode.UnprocessableEntity,
            )
            val plannedCompanion = when {
                comp != null && archiveManifest != null -> planCompanionArchive(comp, plainFile, archiveEntries)
                comp != null -> planCompanionRestore(comp)
                else -> null
            }
            if (plannedCompanion is CompanionRestore.PlanResult.Invalid) {
                return call.respondText(
                    """{"ok":false,"error":${jsonStr(plannedCompanion.reason)}}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
            }
            val companionPlan = (plannedCompanion as? CompanionRestore.PlanResult.Valid)?.plan
            retainedCompanionPlan = companionPlan
            val compFiles = companionPlan?.files?.size ?: 0
            if (dryRun) {
                requestAccepted = true
                return call.respondText(
                    """{"ok":true,"dry_run":true,"panel_id":${jsonStr(obj.optString("panel_id"))},""" +
                        """"config_keys":${configPlan.values.size},"config_warnings":${jarr(configPlan.warnings)},"profile_revisions":${profilePlan?.toImport?.size ?: 0},"profile_restart_required":${profilePlan?.restartRequired ?: false},"companion_pkg":${jsonStr(companionPlan?.packageName ?: "")},"companion_files":$compFiles}""",
                    ContentType.Application.Json,
                )
            }
            if (companionPlan != null && !withContext(Dispatchers.IO) { ensureCompanionHelper() }) {
                return call.respondText(
                    """{"ok":false,"error":"Companion restore needs the current ha-paneld helper"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable,
                )
            }
            val job = scope.launch {
                val before = currentValues()
                val beforeEntityState = config.dashboardEntityBackupState()
                val beforeRevisionHash = io.github.maxlyth.hapaneld.config.ConfigHash.of(
                    revisionValues(before, beforeEntityState),
                )
                var configCommitted = false
                var configItems = 0
                var companionResult: CompanionApplyResult? = null
                var profileResult: ProfileBackupRestoreResult? = null
                var appliedRevisionHash: String? = null
                val operation = runCatching {
                    configItems = applyRestoreConfig(
                        configPlan.values,
                        entityState,
                        beforeRevisionHash,
                        onDurableRevision = { appliedHash ->
                            configCommitted = true
                            appliedRevisionHash = appliedHash
                        },
                        afterCommitBeforeRenderer = { effects, acceptedCount, appliedHash ->
                            configItems = acceptedCount
                            appliedRevisionHash = appliedHash
                            companionResult = companionPlan?.let(::restoreCompanion)
                            if (companionResult?.ok == false) throw CompanionApplyFailed()
                            reconcileAfterCompanionRestore(effects)
                        },
                        afterApply = afterApply@{
                            val payload = profilePayload?.payload ?: return@afterApply
                            profileResult = requireNotNull(profileAdmin).restoreBackup(
                                payload,
                                requireNotNull(profilePlan).expectedCatalogRevision,
                            )
                            if (profileResult?.outcome != ProfileBackupRestoreOutcome.SUCCEEDED) {
                                throw ProfileApplyFailed()
                            }
                            if (profileResult?.restartRequired == true) {
                                rejectFailedProfileRestart(
                                    restartAllowed = true,
                                    requestRestart = onProfileRestart,
                                    abortPendingRestart = onProfileRestartAbort,
                                )?.let { throw ProfileApplyFailed(it) }
                            }
                        },
                    )
                    RestoreOperationResult(
                        message = "Restore completed",
                        structured = InstallProgress.OperationResult(
                            status = InstallProgress.Outcome.SUCCEEDED,
                            config = succeededComponent(configItems),
                            profiles = profileComponent(profileResult),
                            companion = companionResult?.component
                                ?: skippedComponent("not present"),
                        ),
                    )
                }.getOrElse { error ->
                    Log.w(TAG, "restore failed", error)
                    val profileRestartRejection = (error as? ProfileApplyFailed)?.restartRejection
                    val rollback = if (configCommitted) {
                        val expected = appliedRevisionHash
                        val restored = expected != null && runCatching {
                            applyAccepted(before, expectedRevision = expected, entityState = beforeEntityState) ==
                                ApplyAcceptedResult.APPLIED
                        }
                            .getOrDefault(false)
                        if (restored) InstallProgress.ComponentResult(InstallProgress.Outcome.ROLLED_BACK)
                        else InstallProgress.ComponentResult(InstallProgress.Outcome.ROLLBACK_FAILED)
                    } else null
                    val partial = companionResult?.ok == true ||
                        companionResult?.component?.status == InstallProgress.Outcome.PARTIAL ||
                        profileResult?.outcome == ProfileBackupRestoreOutcome.SUCCEEDED ||
                        profileResult?.outcome == ProfileBackupRestoreOutcome.PARTIAL ||
                        rollback?.status == InstallProgress.Outcome.ROLLBACK_FAILED
                    RestoreOperationResult(
                        message = if (partial) "Restore partially completed" else "Restore failed",
                        structured = InstallProgress.OperationResult(
                            status = if (partial) InstallProgress.Outcome.PARTIAL else InstallProgress.Outcome.FAILED,
                            config = when {
                                rollback?.status == InstallProgress.Outcome.ROLLED_BACK ->
                                    InstallProgress.ComponentResult(InstallProgress.Outcome.ROLLED_BACK, configItems)
                                rollback?.status == InstallProgress.Outcome.ROLLBACK_FAILED ->
                                    InstallProgress.ComponentResult(InstallProgress.Outcome.ROLLBACK_FAILED, configItems)
                                configCommitted -> InstallProgress.ComponentResult(InstallProgress.Outcome.PARTIAL, configItems)
                                else -> InstallProgress.ComponentResult(InstallProgress.Outcome.FAILED, 0)
                            },
                            profiles = profileRestartRejection?.let {
                                profileRestartFailureComponent(it, profileResult?.imported?.size ?: 0)
                            } ?: profileComponent(profileResult, profilePlan != null),
                            companion = companionResult?.component
                                ?: if (companionPlan == null) skippedComponent("not present")
                                else InstallProgress.ComponentResult(InstallProgress.Outcome.FAILED, 0),
                            rollback = rollback,
                        ),
                    )
                }
                Log.i(TAG, "restore: ${operation.message}")
                InstallProgress.finish(progress, operation.message, operation.structured)
            }
            job.invokeOnCompletion {
                retainedCompanionPlan?.close()
                restoreFiles.forEach(File::delete)
            }
            transferredToJob = true
            requestAccepted = true
            InstallProgress.finishOnFailure(progress, job)
            call.respondText("""{"status":"started"}""", ContentType.Application.Json)
        } finally {
            if (!transferredToJob) {
                retainedCompanionPlan?.close()
                restoreFiles.forEach(File::delete)
                val result = if (requestAccepted) {
                    InstallProgress.OperationResult(InstallProgress.Outcome.SUCCEEDED)
                } else {
                    InstallProgress.OperationResult(InstallProgress.Outcome.FAILED)
                }
                InstallProgress.finish(
                    progress,
                    if (requestAccepted) "Restore preview complete" else "Restore request rejected",
                    result,
                )
            }
        }
    }

    private data class RestoreOperationResult(
        val message: String,
        val structured: InstallProgress.OperationResult,
    )

    private data class CompanionApplyResult(
        val ok: Boolean,
        val component: InstallProgress.ComponentResult,
    )

    private class CompanionApplyFailed : IllegalStateException("Companion restore failed")
    private class ProfileApplyFailed(
        val restartRejection: ProfileRestartRejection? = null,
    ) : IllegalStateException(restartRejection?.message ?: "Profile catalog restore failed")

    private fun succeededComponent(items: Int) = InstallProgress.ComponentResult(
        InstallProgress.Outcome.SUCCEEDED,
        items,
    )

    private fun skippedComponent(detail: String) = InstallProgress.ComponentResult(
        InstallProgress.Outcome.SKIPPED,
        detail = detail,
    )

    private fun profileComponent(
        result: ProfileBackupRestoreResult?,
        present: Boolean = result != null,
    ): InstallProgress.ComponentResult = when {
        result == null && !present -> skippedComponent("not present")
        result == null -> InstallProgress.ComponentResult(InstallProgress.Outcome.FAILED, 0)
        else -> InstallProgress.ComponentResult(
            status = when (result.outcome) {
                ProfileBackupRestoreOutcome.SUCCEEDED -> InstallProgress.Outcome.SUCCEEDED
                ProfileBackupRestoreOutcome.PARTIAL -> InstallProgress.Outcome.PARTIAL
                ProfileBackupRestoreOutcome.REJECTED -> InstallProgress.Outcome.FAILED
            },
            items = result.imported.size,
            detail = result.message,
        )
    }

    private fun profileRestartFailureComponent(
        rejection: ProfileRestartRejection,
        imported: Int,
    ) = InstallProgress.ComponentResult(
        status = if (rejection.abortPersisted) {
            InstallProgress.Outcome.ROLLED_BACK
        } else {
            InstallProgress.Outcome.ROLLBACK_FAILED
        },
        items = imported,
        detail = rejection.message,
    )

    private fun profileIssueText(issue: io.github.maxlyth.hapaneld.device.profile.ProfileIssue): String =
        "${issue.path}: ${issue.message}"

    private data class ArchiveTextRef(
        val entry: String,
        val size: Long,
        val maxBytes: Long,
        val allowEmpty: Boolean,
    )

    private fun archiveTextRef(
        obj: org.json.JSONObject,
        entryKey: String,
        sizeKey: String,
        expectedEntry: String,
        maxBytes: Long,
        allowEmpty: Boolean,
    ): ArchiveTextRef {
        val entry = obj.opt(entryKey) as? String ?: throw IllegalArgumentException("missing $entryKey")
        require(entry == expectedEntry) { "unexpected $entryKey" }
        val rawSize = obj.opt(sizeKey) as? Number ?: throw IllegalArgumentException("missing $sizeKey")
        val size = rawSize.toLong()
        require(rawSize.toDouble() == size.toDouble())
        val minimum = if (allowEmpty) 0L else 1L
        require(size in minimum..maxBytes)
        return ArchiveTextRef(entry, size, maxBytes, allowEmpty)
    }

    private fun declaredArchiveEntries(
        entity: org.json.JSONObject?,
        profiles: org.json.JSONObject?,
        companion: org.json.JSONObject?,
    ): Set<String> {
        val entries = ArrayList<String>(6)
        if (entity?.has("filter_ids_entry") == true || entity?.has("overrides_entry") == true) {
            entries += archiveTextRef(
                entity,
                "filter_ids_entry",
                "filter_ids_size",
                ENTITY_FILTER_BACKUP_ENTRY,
                MAX_ENTITY_BACKUP_TEXT_BYTES,
                allowEmpty = true,
            ).entry
            entries += archiveTextRef(
                entity,
                "overrides_entry",
                "overrides_size",
                ENTITY_OVERRIDES_BACKUP_ENTRY,
                MAX_ENTITY_BACKUP_TEXT_BYTES,
                allowEmpty = true,
            ).entry
        }
        if (profiles?.has("entry") == true) {
            entries += archiveTextRef(
                profiles,
                "entry",
                "size",
                PROFILE_BACKUP_ENTRY,
                MAX_PROFILE_BACKUP_ENTRY_BYTES,
                allowEmpty = false,
            ).entry
        }
        companion?.optJSONArray("files")?.let { files ->
            for (index in 0 until files.length()) {
                val file = files.optJSONObject(index)
                    ?: throw IllegalArgumentException("invalid Companion file metadata")
                entries += (file.opt("entry") as? String)
                    ?: throw IllegalArgumentException("missing Companion entry")
            }
        }
        require(entries.size < PanelBackup.MAX_ARCHIVE_ENTRIES)
        require(entries.toSet().size == entries.size)
        return entries.toSet()
    }

    private fun readArchiveText(
        archive: File,
        ref: ArchiveTextRef,
        allowedEntries: Set<String>,
        prefix: String,
    ): String {
        val target = File.createTempFile(prefix, ".payload", cacheDir)
        try {
            require(
                PanelBackup.extractArchive(
                    archive,
                    listOf(PanelBackup.ArchiveTarget(ref.entry, target, ref.maxBytes, ref.allowEmpty)),
                    allowedEntries,
                ),
            )
            require(target.length() == ref.size)
            val bytes = target.inputStream().use { BoundedStreams.readBytes(it, ref.maxBytes) }
            return Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        } finally {
            target.delete()
        }
    }

    private fun readProfileArchive(
        metadata: org.json.JSONObject,
        archive: File,
        allowedEntries: Set<String>,
    ): io.github.maxlyth.hapaneld.device.profile.ProfileBackupDecodeResult? = runCatching {
        val ref = archiveTextRef(
            metadata,
            "entry",
            "size",
            PROFILE_BACKUP_ENTRY,
            MAX_PROFILE_BACKUP_ENTRY_BYTES,
            allowEmpty = false,
        )
        ProfileBackup.fromJson(org.json.JSONObject(readArchiveText(archive, ref, allowedEntries, "profile-restore-")))
    }.getOrNull()

    private fun planEntityArchive(
        metadata: org.json.JSONObject,
        archive: File,
        allowedEntries: Set<String>,
    ): DashboardEntityBackupState {
        val filterRef = archiveTextRef(
            metadata,
            "filter_ids_entry",
            "filter_ids_size",
            ENTITY_FILTER_BACKUP_ENTRY,
            MAX_ENTITY_BACKUP_TEXT_BYTES,
            allowEmpty = true,
        )
        val overridesRef = archiveTextRef(
            metadata,
            "overrides_entry",
            "overrides_size",
            ENTITY_OVERRIDES_BACKUP_ENTRY,
            MAX_ENTITY_BACKUP_TEXT_BYTES,
            allowEmpty = true,
        )
        val filterIds = readArchiveText(archive, filterRef, allowedEntries, "entity-filter-restore-")
        val overrides = readArchiveText(archive, overridesRef, allowedEntries, "entity-overrides-restore-")
        return planEntityBackup(
            org.json.JSONObject(metadata.toString())
                .put("filter_ids", filterIds)
                .put("overrides", overrides),
        )
    }

    /** Convert untrusted JSON to a completely validated and decoded plan before any config commit or app stop. */
    private fun planCompanionRestore(comp: org.json.JSONObject): CompanionRestore.PlanResult {
        val files = comp.optJSONArray("files")
            ?: return CompanionRestore.PlanResult.Invalid("Companion restore contains no files")
        val encoded = ArrayList<CompanionRestore.EncodedFile>(files.length())
        for (i in 0 until files.length()) {
            val file = files.optJSONObject(i)
                ?: return CompanionRestore.PlanResult.Invalid("Invalid Companion file entry at index $i")
            encoded += CompanionRestore.EncodedFile(file.optString("rel"), file.optString("b64"))
        }
        return CompanionRestore.plan(
            packageName = comp.optString("pkg"),
            files = encoded,
            installedPackages = CompanionInstaller.installedPackages(appContext),
            stagingDir = cacheDir,
        )
    }

    /** Extract a v2 archive's raw Companion entries under per-file and aggregate decoded limits. */
    private fun planCompanionArchive(
        comp: org.json.JSONObject,
        archive: File,
        allowedEntries: Set<String>,
    ): CompanionRestore.PlanResult {
        val files = comp.optJSONArray("files")
            ?: return CompanionRestore.PlanResult.Invalid("Companion restore contains no files")
        if (files.length() !in 1..CompanionRestore.ALLOWED_FILES.size) {
            return CompanionRestore.PlanResult.Invalid("Companion restore contains an invalid file count")
        }
        data class Pending(val relativePath: String, val entry: String, val size: Long, val target: File)
        val pending = ArrayList<Pending>(files.length())
        var ownershipTransferred = false
        try {
            for (index in 0 until files.length()) {
                val file = files.optJSONObject(index)
                    ?: return CompanionRestore.PlanResult.Invalid("Invalid Companion file entry at index $index")
                val relativePath = file.optString("rel")
                val entry = file.optString("entry")
                val declaredSize = file.optLong("size", -1L)
                if (relativePath !in CompanionRestore.ALLOWED_FILES ||
                    declaredSize !in 1..CompanionRestore.maxBytes(relativePath)
                ) return CompanionRestore.PlanResult.Invalid("Invalid Companion file metadata at index $index")
                pending += Pending(
                    relativePath,
                    entry,
                    declaredSize,
                    File.createTempFile("companion-restore-", ".payload", cacheDir),
                )
            }
            if (pending.map { it.relativePath }.toSet().size != pending.size ||
                pending.map { it.entry }.toSet().size != pending.size ||
                pending.sumOf { it.size } > CompanionRestore.MAX_AGGREGATE_BYTES
            ) return CompanionRestore.PlanResult.Invalid("Duplicate or oversized Companion archive metadata")
            val extracted = PanelBackup.extractArchive(
                archive,
                pending.map { PanelBackup.ArchiveTarget(it.entry, it.target, CompanionRestore.maxBytes(it.relativePath)) },
                allowedEntries,
            )
            if (!extracted || pending.any { it.target.length() != it.size }) {
                return CompanionRestore.PlanResult.Invalid("Companion archive files are missing, corrupt, or too large")
            }
            val result = CompanionRestore.planFiles(
                packageName = comp.optString("pkg"),
                files = pending.map { CompanionRestore.FilePayload(it.relativePath, it.target) },
                installedPackages = CompanionInstaller.installedPackages(appContext),
            )
            ownershipTransferred = result is CompanionRestore.PlanResult.Valid
            return result
        } finally {
            if (!ownershipTransferred) pending.forEach { it.target.delete() }
        }
    }

    /** Validate + apply the config half of a backup (reuses the import apply path). Returns keys applied. */
    private data class RestoreConfigPlan(
        val values: Map<String, String>,
        val warnings: List<String>,
        val errors: List<String>,
    )

    private fun entityBackupJson(state: DashboardEntityBackupState): String = buildString {
        append("{\"instance_key\":").append(jsonStr(state.instanceKey))
        append(",\"instance_origin\":").append(jsonStr(state.instanceOrigin))
        append(",\"instance_uuid\":").append(jsonStr(state.instanceUuid))
        append(",\"dashboard_path\":").append(jsonStr(state.dashboardPath))
        append(",\"filter_ids\":").append(jsonStr(state.filterIds))
        append(",\"filter_enabled\":").append(state.filterEnabled)
        append(",\"filter_owner\":").append(jsonStr(state.filterOwner))
        append(",\"learning_applied\":").append(state.learningApplied)
        append(",\"applied_owner\":").append(jsonStr(state.appliedOwner))
        append(",\"overrides\":").append(jsonStr(state.overrides))
        append(",\"override_owner\":").append(jsonStr(state.overrideOwner))
        append('}')
    }

    private fun entityBackupArchiveJson(
        state: DashboardEntityBackupState,
        filterBytes: Long,
        overrideBytes: Long,
    ): String = buildString {
        append("{\"instance_key\":").append(jsonStr(state.instanceKey))
        append(",\"instance_origin\":").append(jsonStr(state.instanceOrigin))
        append(",\"instance_uuid\":").append(jsonStr(state.instanceUuid))
        append(",\"dashboard_path\":").append(jsonStr(state.dashboardPath))
        append(",\"filter_ids_entry\":").append(jsonStr(ENTITY_FILTER_BACKUP_ENTRY))
        append(",\"filter_ids_size\":").append(filterBytes)
        append(",\"filter_enabled\":").append(state.filterEnabled)
        append(",\"filter_owner\":").append(jsonStr(state.filterOwner))
        append(",\"learning_applied\":").append(state.learningApplied)
        append(",\"applied_owner\":").append(jsonStr(state.appliedOwner))
        append(",\"overrides_entry\":").append(jsonStr(ENTITY_OVERRIDES_BACKUP_ENTRY))
        append(",\"overrides_size\":").append(overrideBytes)
        append(",\"override_owner\":").append(jsonStr(state.overrideOwner))
        append('}')
    }

    private fun planEntityBackup(obj: org.json.JSONObject): DashboardEntityBackupState {
        fun string(key: String, max: Int, allowNewline: Boolean = false): String {
            val value = obj.opt(key) as? String ?: throw IllegalArgumentException("$key must be a string")
            require(value.length <= max && value.none {
                it.code < 0x20 && !(allowNewline && it == '\n')
            }) { "$key is invalid" }
            return value
        }
        fun bool(key: String): Boolean = obj.opt(key) as? Boolean
            ?: throw IllegalArgumentException("$key must be boolean")
        val ids = EntityFilterProtocol.normalize(
            string("filter_ids", 13_000_000, allowNewline = true).lineSequence().toList(),
        )
            .joinToString("\n")
        val overrideLines = string("overrides", 13_000_000, allowNewline = true)
            .lineSequence().filter(String::isNotBlank).toList()
        val overrideIds = overrideLines.map { line ->
            require(line.firstOrNull() == '+' || line.firstOrNull() == '-') { "invalid override marker" }
            line.drop(1).trim()
        }
        EntityFilterProtocol.normalize(overrideIds)
        return DashboardEntityBackupState(
            instanceKey = string("instance_key", 256),
            instanceOrigin = string("instance_origin", 2_048),
            instanceUuid = string("instance_uuid", 256),
            dashboardPath = string("dashboard_path", 2_048),
            filterIds = ids,
            filterEnabled = bool("filter_enabled"),
            filterOwner = string("filter_owner", 2_560),
            learningApplied = bool("learning_applied"),
            appliedOwner = string("applied_owner", 2_560),
            overrides = overrideLines.sorted().joinToString("\n"),
            overrideOwner = string("override_owner", 2_560),
        )
    }

    private fun planRestoreConfig(cfgObj: org.json.JSONObject, schema: Int): RestoreConfigPlan {
        val raw = LinkedHashMap<String, String>()
        for (key in cfgObj.keys()) {
            val value = cfgObj.opt(key)
            if (value == null || value == org.json.JSONObject.NULL || value is org.json.JSONObject || value is org.json.JSONArray) {
                return RestoreConfigPlan(emptyMap(), emptyList(), listOf("$key: expected a scalar setting value"))
            }
            raw[key] = value.toString()
        }
        val (migrated, warnings) = Migrations.migrate(schema, raw)
        val accepted = LinkedHashMap<String, String>()
        val errors = ArrayList<String>()
        for ((key, value) in migrated) {
            val spec = SettingsRegistry.spec(key)
            val exposedSpec = exposureSpec(key)
            when {
                exposedSpec != null -> {
                    val normalized = SettingValue.parseBool(value)?.toString()
                    if (normalized == null) errors += "$key: expected a boolean" else accepted[key] = normalized
                }
                spec == null -> errors += "$key: unknown setting"
                spec.readOnly || spec.transient -> errors += "$key: setting cannot be restored"
                else -> when (val validated = SettingValue.validate(spec, value)) {
                    is Validation.Ok -> accepted[key] = validated.normalized
                    is Validation.Bad -> errors += "$key: ${validated.reason}"
                }
            }
        }
        if (accepted.isEmpty() && errors.isEmpty()) errors += "config object contains no restorable settings"
        return RestoreConfigPlan(accepted, warnings, errors)
    }

    private suspend fun applyRestoreConfig(
        accepted: Map<String, String>,
        entityState: DashboardEntityBackupState?,
        expectedRevision: String,
        onDurableRevision: (String) -> Unit,
        afterCommitBeforeRenderer: (RendererConfigEffects, Int, String) -> Unit,
        afterApply: () -> Unit = {},
    ): Int {
        check(applyAccepted(
            accepted,
            expectedRevision = expectedRevision,
            entityState = entityState,
            onDurableRevision = onDurableRevision,
            afterCommitBeforeRenderer = { effects, appliedHash ->
                afterCommitBeforeRenderer(effects, accepted.size, appliedHash)
            },
            afterApply = afterApply,
        ) == ApplyAcceptedResult.APPLIED) {
            "configuration commit failed"
        }
        return accepted.size
    }

    /** A Companion-only restore can make an interrupted built-in switch repairable without changing a
     * renderer setting. When an ordinary renderer effect exists, that effect performs preparation. */
    private fun reconcileAfterCompanionRestore(effects: RendererConfigEffects?) {
        if (effects != null && (effects.dashboardChanged || effects.reloadBuiltin || effects.relaunchBuiltin)) return
        val result = rendererPreparation.reconcileStartup(
            ensureHome = { pkg, ready -> system.ensureDashboardHome(pkg, ready) },
            launchHome = { pkg -> system.launchHome(pkg) },
        )
        requireRendererResult(result)
    }

    /** Execute a prevalidated Companion restore through the descriptor-confined helper transaction. */
    private fun restoreCompanion(plan: CompanionRestore.Plan): CompanionApplyResult {
        if (plan.packageName !in CompanionInstaller.SUPPORTED_PACKAGES || !AndroidInput.isPackage(plan.packageName)) {
            return CompanionApplyResult(
                false,
                InstallProgress.ComponentResult(InstallProgress.Outcome.FAILED, 0, "unsupported Companion package"),
            )
        }
        val preparation = io.github.maxlyth.hapaneld.backup.CompanionDatabasePreparation.prepare(plan, cacheDir)
            ?: return CompanionApplyResult(
                false,
                InstallProgress.ComponentResult(InstallProgress.Outcome.FAILED, 0, "Companion payload validation failed"),
            )
        preparation.use { prepared ->
            val lease = CompanionDataOperationGate.acquire(plan.packageName)
                ?: return CompanionApplyResult(
                    false,
                    InstallProgress.ComponentResult(InstallProgress.Outcome.FAILED, 0, "Companion helper is busy"),
                )
            if (!companionDataOperationState.arm()) {
                lease.close()
                return CompanionApplyResult(
                    false,
                    InstallProgress.ComponentResult(
                        InstallProgress.Outcome.FAILED,
                        0,
                        "Companion operation safety marker could not be persisted",
                    ),
                )
            }
            var result = CompanionHelperProtocol.RestoreResult.INDETERMINATE
            var leaseTransferred = false
            try {
                result = HelperClient.restoreCompanion(
                    plan.packageName,
                    prepared.files.associate { it.relativePath to it.file },
                )
                if (result == CompanionHelperProtocol.RestoreResult.INDETERMINATE ||
                    result == CompanionHelperProtocol.RestoreResult.BUSY
                ) {
                    leaseTransferred = settleCompanionOperationLease(
                        lease,
                        companionDataOperationState,
                        possiblyInFlight = true,
                        retain = { retained, after -> retainCompanionLeaseUntilHelperIdle(retained, after) },
                        afterRelease = {
                            if (system.resolveDashboard(config.dashboardPackage) != plan.packageName) {
                                system.launchHome(config.dashboardPackage)
                            }
                        },
                    )
                }
            } finally {
                if (!leaseTransferred) {
                    settleCompanionOperationLease(
                        lease,
                        companionDataOperationState,
                        possiblyInFlight = false,
                        retain = { retained, after -> retainCompanionLeaseUntilHelperIdle(retained, after) },
                        afterRelease = {
                            if (result in setOf(
                                CompanionHelperProtocol.RestoreResult.COMMITTED_RELAUNCH_FAILED,
                                CompanionHelperProtocol.RestoreResult.ROLLED_BACK_RELAUNCH_FAILED,
                            )
                            ) system.launchHome(plan.packageName)
                            if (system.resolveDashboard(config.dashboardPackage) != plan.packageName) {
                                system.launchHome(config.dashboardPackage)
                            }
                        },
                    )
                }
            }
            val repaired = prepared.repairedInternalUrls
            return when (result) {
                CompanionHelperProtocol.RestoreResult.COMMITTED -> CompanionApplyResult(
                    true,
                    InstallProgress.ComponentResult(
                        InstallProgress.Outcome.SUCCEEDED,
                        plan.files.size,
                        if (repaired > 0) "$repaired blank internal URL(s) repaired" else "owner/context restored",
                    ),
                )
                CompanionHelperProtocol.RestoreResult.COMMITTED_RELAUNCH_FAILED -> CompanionApplyResult(
                    false,
                    InstallProgress.ComponentResult(
                        InstallProgress.Outcome.PARTIAL,
                        plan.files.size,
                        "files restored but Companion relaunch was not confirmed",
                    ),
                )
                CompanionHelperProtocol.RestoreResult.ROLLED_BACK,
                CompanionHelperProtocol.RestoreResult.ROLLED_BACK_RELAUNCH_FAILED -> CompanionApplyResult(
                    false,
                    InstallProgress.ComponentResult(
                        InstallProgress.Outcome.ROLLED_BACK,
                        0,
                        "restore failed; prior Companion files retained",
                    ),
                )
                CompanionHelperProtocol.RestoreResult.ROLLBACK_FAILED,
                CompanionHelperProtocol.RestoreResult.ROLLBACK_FAILED_RELAUNCH_FAILED,
                CompanionHelperProtocol.RestoreResult.ROLLBACK_FAILED_RELAUNCH_SUPPRESSED -> CompanionApplyResult(
                    false,
                    InstallProgress.ComponentResult(
                        InstallProgress.Outcome.ROLLBACK_FAILED,
                        null,
                        "restore and rollback failed; Companion state may be partial",
                    ),
                )
                CompanionHelperProtocol.RestoreResult.BUSY -> CompanionApplyResult(
                    false,
                    InstallProgress.ComponentResult(InstallProgress.Outcome.FAILED, 0, "Companion helper is busy"),
                )
                CompanionHelperProtocol.RestoreResult.NOT_SUBMITTED -> CompanionApplyResult(
                    false,
                    InstallProgress.ComponentResult(InstallProgress.Outcome.FAILED, 0, "Companion helper is unavailable"),
                )
                CompanionHelperProtocol.RestoreResult.FAILED -> CompanionApplyResult(
                    false,
                    InstallProgress.ComponentResult(InstallProgress.Outcome.FAILED, 0, "restore rejected before commit"),
                )
                CompanionHelperProtocol.RestoreResult.INDETERMINATE -> CompanionApplyResult(
                    false,
                    InstallProgress.ComponentResult(
                        InstallProgress.Outcome.PARTIAL,
                        null,
                        "restore terminal status was indeterminate",
                    ),
                )
            }
        }
    }

    /** A timed-out socket does not cancel the helper worker. Keep every automatic launch path blocked
     * until a reachable helper affirmatively reports that the transaction can no longer be active. */
    private fun retainCompanionLeaseUntilHelperIdle(
        lease: CompanionDataOperationGate.Lease,
        afterRelease: () -> Unit,
    ) {
        scope.launch(Dispatchers.IO) {
            retainCompanionLeaseUntilHelperIdle(
                lease = lease,
                operationState = companionDataOperationState,
                afterRelease = afterRelease,
                operationStatus = HelperClient::companionOperationStatus,
                pollMs = COMPANION_STATUS_POLL_MS,
            )
        }
    }

    /** List on-panel revisions (newest first) as `[{id, exported_at, keys}]`. */
    private fun revisionsJson(): String =
        "[" + revisions.list().joinToString(",") { (id, b) ->
            "{\"id\":$id,\"exported_at\":\"${b.exportedAt}\",\"keys\":${b.values.size}}"
        } + "]"

    /** Roll back to a stored revision (itself recorded as a new revision, so restores are undoable). */
    private suspend fun handleRevisionRestore(call: ApplicationCall, id: Long) {
        val bundle = revisions.get(id)
        if (bundle == null) {
            call.respondText("""{"status":"not-found"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
            return
        }
        val entityState = revisionEntityState(bundle.values)
        val ordinaryValues = bundle.values.filterKeys { !it.startsWith("$ENTITY_REVISION_PREFIX.") }
        val (migrated, _) = Migrations.migrate(bundle.schema, ordinaryValues)
        val accepted = LinkedHashMap<String, String>()
        for ((key, raw) in migrated) {
            if (exposureSpec(key) != null) {
                SettingValue.parseBool(raw)?.let { accepted[key] = it.toString() }
                continue
            }
            val spec = SettingsRegistry.spec(key) ?: continue
            if (spec.readOnly || spec.transient) continue
            (SettingValue.validate(spec, raw) as? Validation.Ok)?.let { accepted[key] = it.normalized }
        }
        if (entityState == null && (
                accepted["dashboard_entity_overrides"].orEmpty().isNotBlank() ||
                    accepted["dashboard_entity_learning_applied"] == "true"
                )
        ) {
            call.respondText(
                importJson("rejected", emptyList(), emptyList(), emptyList(), listOf("revision lacks entity owner metadata")),
                ContentType.Application.Json,
                HttpStatusCode.UnprocessableEntity,
            )
            return
        }
        if (applyAccepted(accepted, entityState = entityState) != ApplyAcceptedResult.APPLIED) {
            call.respondText(
                importJson("error", emptyList(), emptyList(), emptyList(), listOf("configuration commit failed")),
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
            return
        }
        call.respondText(importJson("restored", accepted.keys.toList(), emptyList(), emptyList(), emptyList()), ContentType.Application.Json)
    }

    private fun jarr(items: List<String>): String =
        "[" + items.joinToString(",") { Json.str(it) } + "]"

    private fun importJson(status: String, applied: List<String>, skipped: List<String>, warnings: List<String>, errors: List<String>): String =
        "{\"status\":\"$status\",\"applied\":${jarr(applied)},\"skipped\":${jarr(skipped)},\"warnings\":${jarr(warnings)},\"errors\":${jarr(errors)}}"

    /** Full config as JSON for fleet management. The MQTT password is never emitted — only a boolean
     *  saying whether one is set. `http_port` is read-only (changing it needs a restart). */
    private fun configJson(): String {
        fun s(v: String) = Json.str(v)
        return "{" +
            "\"panel_id\":${s(config.panelId)}," +
            "\"friendly_name\":${s(config.friendlyName)}," +
            "\"manufacturer\":${s(config.manufacturer)}," +
            "\"model\":${s(config.model)}," +
            "\"http_port\":${config.httpPort}," +
            "\"mqtt_broker\":${s(config.mqttBroker)}," +
            "\"mqtt_user\":${s(config.mqttUser)}," +
            "\"mqtt_password_set\":${config.mqttPassword.isNotEmpty()}," +
            "\"dashboard_package\":${s(config.dashboardPackage)}," +
            "\"launcher_package\":${s(config.launcherPackage)}," +
            "\"tame_vendor_packages\":${s(config.tameVendorPackagesRaw)}," +
            "\"silence_boot_chime\":${config.silenceBootChime}," +
            "\"keep_awake\":${config.keepAwake}," +
            "\"log_ship_enabled\":${config.logShipEnabled}," +
            "\"log_ship_host\":${s(config.logShipHost)}," +
            "\"log_ship_port\":${config.logShipPort}," +
            "\"log_ship_protocol\":${s(config.logShipProtocol)}," +
            "\"version\":${s(Config.VERSION)}," +
            "\"proximity\":${sensors.proximityJson()}," +
            // Registry-driven current values + per-key HA-exposure flags for the Configure form.
            "\"settings\":${settingsValuesJson()}," +
            "\"ha_expose\":${haExposeJson()}" +
            "}"
    }

    companion object {
        private const val TAG = "ha-paneld/http"
        private const val ENTITY_REVISION_PREFIX = "_local.entity_state"
        private const val RECOMMENDED_TAME_KEY = "@recommended"
        private const val TAME_SHUTDOWN_MS = 5_000L
        private const val REMOTE_CONTROL_SHUTDOWN_MS = 5_000L
        private val REMOTE_ACTIONS = setOf(
            "back", "recents", "launcher", "admin_launcher", "reboot", "volup", "voldn",
        )

        /** Behaviour keys routed through [applySetting] after an HTTP persistence commit. */
        internal val HTTP_LIVE_KEYS = listOf(
            "wake_on_wave", "prevent_idle_dim", "watchdog_enabled", "kiosk_lock", "auto_brightness",
            "brightness_bias", "navbar_mode", "touch_sound", "cpu_governor",
            "network_adb", "zigbee_router", "ambient_lux",
            "companion_auto_update", "companion_update_channel", "self_update", "webview_auto_update",
            "update_channel", "home_dashboard", "silence_boot_chime",
        )

        // Probe-cache TTLs: the dashboard renders from the snapshot, so these bound both staleness
        // and how often the su round-trips can run. Density/su flap even less than the rest.
        private const val SNAP_TTL_MS = 15_000L
        private const val DENSITY_TTL_MS = 30_000L
        private const val SU_TTL_MS = 60_000L
        private const val COMPANION_URL_TTL_MS = 60_000L
        private const val COMPANION_STATUS_POLL_MS = 1_000L
        internal const val MAX_PLAY_BODY_BYTES = 16L * 1024L
        internal const val MAX_CONFIG_POST_BODY_BYTES = 256L * 1024L
        internal const val MAX_SMALL_FORM_POST_BODY_BYTES = 16L * 1024L
        internal const val MAX_ENTITY_ADMIN_BODY_BYTES = 256L * 1024L
        internal const val MAX_CONFIG_IMPORT_BYTES = 1L * 1024L * 1024L
        internal const val MAX_RESTORE_BYTES = 64L * 1024L * 1024L
        internal const val MAX_APK_UPLOAD_BYTES = 256L * 1024L * 1024L
        internal const val MAX_COMPANION_BACKUP_BYTES = CompanionRestore.MAX_AGGREGATE_BYTES
        // v2 keeps large profile/entity payloads in separately bounded entries, leaving only config and
        // small ownership metadata here. This avoids one multi-tens-of-MiB String + JSONObject allocation.
        internal const val MAX_BACKUP_MANIFEST_BYTES = 1L * 1024L * 1024L
        internal const val MAX_PROFILE_BACKUP_ENTRY_BYTES = 9L * 1024L * 1024L
        internal const val MAX_ENTITY_BACKUP_TEXT_BYTES = 13_000_000L
        // Compatibility-only v1 JSON is multiply materialized by JSONObject; keep its heap exposure much
        // smaller than the streamed/file-backed v2 manifest. New backups are always v2.
        internal const val MAX_LEGACY_BACKUP_JSON_BYTES = 6L * 1024L * 1024L
        internal const val BACKUP_STORAGE_MARGIN_BYTES = 64L * 1024L * 1024L
        private const val PROFILE_BACKUP_ENTRY = "profiles/catalog.json"
        private const val ENTITY_FILTER_BACKUP_ENTRY = "entity/filter-ids.txt"
        private const val ENTITY_OVERRIDES_BACKUP_ENTRY = "entity/overrides.txt"
        private val ENTITY_STATE_CONFIG_KEYS = setOf(
            "dashboard_entity_overrides",
            "dashboard_entity_learning_applied",
        )

        // Dashboard fact rows that are BACKED BY A SETTING → the Configure anchor the ✎ marker
        // deep-links to. Facts absent here are static (hardware/runtime) and get no marker.
        private val FACT_CFG = mapOf(
            "panel_id" to "cfg-panel_id",
            "Friendly name" to "cfg-friendly_name",
            "MQTT" to "cfg-mqtt_broker",
            "Navbar" to "cfg-navbar_mode",
            "Zigbee" to "cfg-zigbee_router",
            "CPU profile" to "cfg-cpu_governor",
            "Network ADB" to "cfg-network_adb",
            "Log shipping" to "cfg-log_ship_enabled",
        )
        private const val RELEASES_URL = "https://github.com/maxlyth/ha-paneld/releases"
        private const val REPO_URL = "https://github.com/maxlyth/ha-paneld"
        private const val WEBVIEW_DOC = "https://github.com/maxlyth/ha-paneld/blob/main/docs/hardware/README.md#updating-the-system-webview"
        // GitHub mark (official, CC0 simple-icons) + Material "open in new" glyph — icon links in the UI.
        private const val GH_ICON = "M12 .297c-6.63 0-12 5.373-12 12 0 5.303 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61C4.422 18.07 3.633 17.7 3.633 17.7c-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 3.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 1.98-.399 3-.405 1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 2.22 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 22.092 24 17.592 24 12.297c0-6.627-5.373-12-12-12"
        private const val EXT_ICON = "M14 3v2h3.59l-9.83 9.83 1.41 1.41L19 6.41V10h2V3m-2 16H5V5h7V3H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2v-7h-2v7z"
    }
}

/**
 * Project a config-import preview without turning the dry-run endpoint into a secret read oracle.
 *
 * Every submitted secret is represented as the same redacted change regardless of whether it equals the
 * current value. The key remains visible so the preview can confirm its scope, but neither credential
 * content nor equality is exposed.
 */
internal fun configDryRunJson(
    diff: List<ConfigDiff.Change>,
    skipped: List<String>,
    warnings: List<String>,
    expectedConfig: String,
): String {
    fun q(value: String) = Json.str(value)
    fun array(items: List<String>) = "[" + items.joinToString(",") { q(it) } + "]"
    val changes = diff.joinToString(",") { change ->
        val secret = SettingsRegistry.spec(change.key)?.secret == true
        val from = if (secret) q(REDACTED_CONFIG_VALUE) else change.from?.let(::q) ?: "null"
        val to = if (secret) q(REDACTED_CONFIG_VALUE) else q(change.to)
        "{\"key\":${q(change.key)},\"from\":$from,\"to\":$to}"
    }
    return "{\"status\":\"dry_run\",\"expected_cfg\":${q(expectedConfig)}," +
        "\"changes\":[$changes],\"skipped\":${array(skipped)},\"warnings\":${array(warnings)}}"
}

private const val REDACTED_CONFIG_VALUE = "[redacted]"

/** Public/UI concurrency hashes deliberately exclude credential-bearing settings. */
internal fun configConcurrencyValues(values: Map<String, String>): Map<String, String> =
    values.filterKeys { key -> SettingsRegistry.spec(key)?.secret != true }

/**
 * Secret submissions always produce the same projected entry, including when the guess equals the
 * stored value. This preserves acknowledgement that a secret was submitted without an equality oracle.
 */
internal fun configPreviewDiff(
    current: Map<String, String>,
    candidate: Map<String, String>,
): List<ConfigDiff.Change> {
    val ordinary = ConfigDiff.diff(
        current,
        candidate.filterKeys { key -> SettingsRegistry.spec(key)?.secret != true },
    )
    val secrets = candidate.keys
        .filter { key -> SettingsRegistry.spec(key)?.secret == true }
        .map { key -> ConfigDiff.Change(key, null, REDACTED_CONFIG_VALUE) }
    return (ordinary + secrets).sortedBy { it.key }
}

/** Keep room for the received envelope, authenticated plaintext, and extracted Companion payloads. */
internal fun restoreBodyStagingLimit(
    usableBytes: Long,
    maxPayloadBytes: Long = PaneldServer.MAX_RESTORE_BYTES,
    safetyMarginBytes: Long = 64L * 1024L * 1024L,
): Long {
    if (usableBytes <= safetyMarginBytes || maxPayloadBytes <= 0L) return 0L
    return minOf(maxPayloadBytes, (usableBytes - safetyMarginBytes) / 3L)
}

/** Start one HTTP-engine generation. A failed start must release any partially acquired engine resources
 * and close request admission without replacing the original failure that the service lifecycle observes. */
internal fun startOwnedHttpServer(
    start: () -> Unit,
    stop: () -> Unit,
    closeIngress: () -> Unit,
) {
    try {
        start()
    } catch (error: Exception) {
        runCatching(stop)
        runCatching(closeIngress)
        throw error
    }
}

internal fun fleetImportPreservesTargetLocalValue(fleet: Boolean, key: String, normalized: String): Boolean =
    fleet && key == "ha_url" && normalized.isEmpty()

internal fun validProximityThreshold(value: Float?): Boolean =
    value != null && value.isFinite() && value in 0f..MAX_PROXIMITY_THRESHOLD

private const val MAX_PROXIMITY_THRESHOLD = 1_000_000f

/** Package actions required to converge a vendor-taming blocklist after its preference commit. */
internal data class TameDelta(val add: Set<String>, val remove: Set<String>) {
    val isEmpty: Boolean get() = add.isEmpty() && remove.isEmpty()

    companion object {
        val NONE = TameDelta(emptySet(), emptySet())
        fun between(old: Set<String>, next: Set<String>) = TameDelta(next - old, old - next)
    }
}

/** Coalesced renderer work caused by a committed configuration change. */
internal data class RendererConfigEffects(
    val dashboardChanged: Boolean,
    val reloadBuiltin: Boolean,
    val relaunchBuiltin: Boolean,
    val darkMode: Boolean?,
) {
    companion object {
        private val CREDENTIAL_KEYS = setOf(
            "ha_url", "ha_token", "ha_refresh_token", "ha_token_expiry", "ha_client_id",
        )

        fun credentialsChanged(previous: Map<String, String>, accepted: Map<String, String>): Boolean {
            fun changed(key: String): Boolean {
                val next = accepted[key] ?: return false
                val before = previous[key]
                return if (key == "ha_url") next.trimEnd('/') != before?.trimEnd('/') else next != before
            }
            val accessReplacesRefresh = accepted["ha_token"]?.isNotEmpty() == true &&
                "ha_refresh_token" !in accepted && previous["ha_refresh_token"].orEmpty().isNotEmpty()
            val urlClearDropsCredentials = accepted["ha_url"]?.isEmpty() == true &&
                listOf("ha_token", "ha_refresh_token", "ha_client_id").any { previous[it].orEmpty().isNotEmpty() }
            return CREDENTIAL_KEYS.any(::changed) || accessReplacesRefresh || urlClearDropsCredentials
        }

        fun between(previous: Map<String, String>, accepted: Map<String, String>): RendererConfigEffects {
            fun changed(key: String): Boolean {
                val next = accepted[key] ?: return false
                val before = previous[key]
                return if (key == "ha_url") next.trimEnd('/') != before?.trimEnd('/') else next != before
            }
            return coalesce(
                dashboardChanged = changed("dashboard_package"),
                credentialChanged = credentialsChanged(previous, accepted),
                zoomChanged = changed("dashboard_zoom"),
                fullscreenChanged = changed("dashboard_fullscreen"),
                overscrollChanged = changed("dashboard_overscroll"),
                darkMode = accepted["dark_mode"]?.toBooleanStrictOrNull()
                    ?.takeIf { changed("dark_mode") && android.os.Build.VERSION.SDK_INT < 29 },
            )
        }

        fun coalesce(
            dashboardChanged: Boolean,
            credentialChanged: Boolean,
            zoomChanged: Boolean,
            fullscreenChanged: Boolean,
            overscrollChanged: Boolean,
            darkMode: Boolean?,
        ): RendererConfigEffects {
            val reload = !dashboardChanged && (credentialChanged || zoomChanged || darkMode != null)
            val relaunch = !dashboardChanged && !reload && (fullscreenChanged || overscrollChanged)
            return RendererConfigEffects(dashboardChanged, reload, relaunch, darkMode)
        }
    }
}
