package io.github.maxlyth.hapaneld

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.maxlyth.hapaneld.control.AdbController
import io.github.maxlyth.hapaneld.device.DeviceProfile
import io.github.maxlyth.hapaneld.device.probe.AndroidPassiveProfileProbe
import io.github.maxlyth.hapaneld.device.profile.ProfileDraftFactory
import io.github.maxlyth.hapaneld.device.profile.RuntimeProfileRegistry
import io.github.maxlyth.hapaneld.input.EvdevButtonClient
import io.github.maxlyth.hapaneld.control.AutoBrightnessController
import io.github.maxlyth.hapaneld.control.BootChimeController
import io.github.maxlyth.hapaneld.control.BrightnessController
import io.github.maxlyth.hapaneld.control.CpuController
import io.github.maxlyth.hapaneld.control.CompanionDb
import io.github.maxlyth.hapaneld.control.NavbarController
import io.github.maxlyth.hapaneld.control.PowerController
import io.github.maxlyth.hapaneld.control.NavigateController
import io.github.maxlyth.hapaneld.control.RelayController
import io.github.maxlyth.hapaneld.control.OverlayWakeTap
import io.github.maxlyth.hapaneld.control.ScreenController
import io.github.maxlyth.hapaneld.control.Su
import io.github.maxlyth.hapaneld.control.SystemController
import io.github.maxlyth.hapaneld.control.TameController
import io.github.maxlyth.hapaneld.control.KioskController
import io.github.maxlyth.hapaneld.control.LedEffectController
import io.github.maxlyth.hapaneld.control.WatchdogController
import io.github.maxlyth.hapaneld.control.TouchSoundController
import io.github.maxlyth.hapaneld.control.VolumeController
import io.github.maxlyth.hapaneld.control.ZigbeeController
import io.github.maxlyth.hapaneld.device.LedMechanism
import io.github.maxlyth.hapaneld.hardware.LedController
import io.github.maxlyth.hapaneld.hardware.LedFactory
import io.github.maxlyth.hapaneld.hardware.Rk3576LedController
import io.github.maxlyth.hapaneld.hardware.SocketLedController
import io.github.maxlyth.hapaneld.config.Capabilities
import io.github.maxlyth.hapaneld.dashboard.EntityLearningManager
import io.github.maxlyth.hapaneld.dashboard.EntityLearningRuntime
import io.github.maxlyth.hapaneld.http.PaneldServer
import io.github.maxlyth.hapaneld.http.PanelInfo
import io.github.maxlyth.hapaneld.logship.LogCapture
import io.github.maxlyth.hapaneld.logship.LogShipper
import io.github.maxlyth.hapaneld.media.AudioPlaybackCoordinator
import io.github.maxlyth.hapaneld.sensors.SensorReporter
import io.github.maxlyth.hapaneld.util.localIpv4
import io.github.maxlyth.hapaneld.util.localIpv6
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import io.github.maxlyth.hapaneld.util.ServiceRuntimeOwner
import io.github.maxlyth.hapaneld.util.BorrowedRendererSettings
import io.github.maxlyth.hapaneld.util.RendererPreparationCoordinator
import io.github.maxlyth.hapaneld.util.RendererPreparationState
import io.github.maxlyth.hapaneld.util.ProfileRestartCoordinator
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import io.github.maxlyth.hapaneld.util.UpdateChecker
import io.github.maxlyth.hapaneld.util.CompanionInstaller
import io.github.maxlyth.hapaneld.util.InstallProgress
import io.github.maxlyth.hapaneld.util.HelperClient
import io.github.maxlyth.hapaneld.util.HelperInstallReconciler
import io.github.maxlyth.hapaneld.util.HelperInstallTransaction
import io.github.maxlyth.hapaneld.util.SelfUpdater
import io.github.maxlyth.hapaneld.util.WebViewInstaller
import io.github.maxlyth.hapaneld.mqtt.ConnectionSupervisor
import io.github.maxlyth.hapaneld.mqtt.isAuthRecoveryState
import io.github.maxlyth.hapaneld.platform.AndroidScreenPower
import io.github.maxlyth.hapaneld.platform.AndroidSystemEnv
import io.github.maxlyth.hapaneld.util.periodic
import io.github.maxlyth.hapaneld.util.SystemProps
import io.github.maxlyth.hapaneld.dashboard.shouldReloadBuiltinAfterEntityFilterChange
import io.github.maxlyth.hapaneld.shizuku.ShizukuBridge
import java.io.File

internal fun commitBorrowedRendererTarget(
    commit: () -> Boolean,
    onCommitted: () -> Unit,
): Boolean {
    val committed = commit()
    if (committed) onCommitted()
    return committed
}

internal fun prepareEntityLearningStartup(
    startMdns: () -> Unit,
    reconcileRenderer: () -> RendererPreparationCoordinator.Result,
    startLearning: () -> Unit,
): RendererPreparationCoordinator.Result {
    startMdns()
    return reconcileRenderer().also { result ->
        if (result != RendererPreparationCoordinator.Result.CLOSED) startLearning()
    }
}

/** Start a replacement network runtime without making native HA link resolution depend on MQTT reaching
 * an onConnected callback. This keeps live HA URL/token changes effective when MQTT is disabled. */
internal fun startReconfiguredNetworkRuntime(
    startMdns: () -> Unit,
    resolveHaLink: () -> Unit,
    startMqtt: () -> Unit,
) {
    startMdns()
    resolveHaLink()
    startMqtt()
}

internal data class EntityLearningShutdownResult(
    val ingressStopped: Boolean,
    val rendererDrained: Boolean,
    val scopeDrained: Boolean,
    val storeClosed: Boolean,
)

/** Close every producer before the SQLite-backed learner. A timeout deliberately leaks the store until
 * process death rather than letting a late HTTP/renderer/scope callback use a closed database. */
internal fun shutdownEntityLearningAfterIngress(
    stopIngress: () -> Boolean,
    closeRendererAdmission: () -> Boolean,
    detachRuntime: () -> Unit,
    cancelAndDrainScope: () -> Boolean,
    closeStore: () -> Unit,
): EntityLearningShutdownResult {
    val ingressStopped = stopIngress()
    val rendererDrained = closeRendererAdmission()
    detachRuntime()
    val scopeDrained = cancelAndDrainScope()
    val storeClosed = ingressStopped && rendererDrained && scopeDrained
    if (storeClosed) closeStore()
    return EntityLearningShutdownResult(ingressStopped, rendererDrained, scopeDrained, storeClosed)
}

/**
 * Persistent foreground service. Hosts the Ktor HTTP listener, the JmDNS advertiser, the MQTT
 * control bridge and the hardware controllers for the panel's lifetime. Declared
 * `foregroundServiceType=specialUse` because a wall-panel on-LAN agent has no analogue among the
 * predefined FGS types.
 *
 * Critically: this service draws no UI and never takes HOME foreground — the HA Companion app's
 * WebView stays the visible launcher throughout, matching the bash reference behaviour.
 */
class PaneldService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // One dedicated transition lane owns initial start, config rebuilds, reconnects, and final teardown.
    // Observations capture the generation and concrete MQTT/mDNS pair together, so watchdog/network work cannot read a generation from one runtime and then reach a replacement through a mutable field.
    private data class NetworkRuntime(val mqtt: MqttBridge, val mdns: MdnsAdvertiser)
    private lateinit var runtime: ServiceRuntimeOwner<NetworkRuntime>
    private lateinit var config: Config
    private lateinit var server: PaneldServer
    private lateinit var rendererPreparation: RendererPreparationCoordinator
    private lateinit var entityLearning: EntityLearningManager
    private val mqtt: MqttBridge get() = runtime.current().mqtt
    private val mdns: MdnsAdvertiser get() = runtime.current().mdns
    // Default-network callback that nudges an MQTT reconnect when the network returns (see registerNetworkCallback).
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    // MQTT watchdog runs on a DEDICATED thread (not Dispatchers.IO), so slow/contended su can't starve it.
    @Volatile private var mqttWatchdogAlive = false
    @Volatile private var mqttWatchdogThread: Thread? = null
    @Volatile private var serviceStopping = false
    @Volatile private var kioskReassertThread: Thread? = null
    private lateinit var sensors: SensorReporter
    private lateinit var logShipper: LogShipper
    private lateinit var logCaptureApp: LogCapture
    private lateinit var logCaptureSystem: LogCapture

    // Controllers are fields so the MQTT bridge can be rebuilt on a panel_id change.
    private lateinit var brightness: BrightnessController
    private lateinit var autoBright: AutoBrightnessController
    private lateinit var screen: ScreenController
    private lateinit var led: LedController
    // Effect loop for the LED — owned here (not the MQTT bridge) so a bridge rebuild never orphans it.
    private lateinit var ledEffect: LedEffectController
    private lateinit var navigate: NavigateController
    private lateinit var volume: VolumeController
    private lateinit var audio: AudioPlaybackCoordinator
    private lateinit var system: SystemController
    private lateinit var tame: TameController
    private lateinit var navbar: NavbarController
    private lateinit var watchdog: WatchdogController
    private lateinit var kiosk: KioskController
    private lateinit var touchSound: TouchSoundController
    private lateinit var bootChime: BootChimeController
    private lateinit var zigbee: ZigbeeController
    private lateinit var relay: RelayController
    private lateinit var cpu: CpuController
    private lateinit var adb: AdbController
    private lateinit var power: PowerController
    private lateinit var profile: DeviceProfile
    private lateinit var profileRegistry: RuntimeProfileRegistry
    private lateinit var passiveProfileProbe: AndroidPassiveProfileProbe
    private lateinit var profileRestart: ProfileRestartCoordinator
    private lateinit var activeProfileIdentity: String
    private var profileActivationGeneration: Long? = null
    // One-time-start guard for onStartCommand (see there for why). Reset in onDestroy.
    @Volatile private var started = false

    override fun onCreate() {
        super.onCreate()
        config = Config(this)
        config.migrateLiveStore()   // carry persisted settings across a schema bump before anything reads them
        reconcileHelperInstallStaging()
        // Resolve one immutable profile revision before constructing any hardware owner. Activations are
        // restart-bound, so every controller below observes this exact object for the service lifetime.
        profileRegistry = RuntimeProfileRegistry(this)
        val resolvedProfile = profileRegistry.resolveForStartup()
        profile = resolvedProfile.profile
        activeProfileIdentity = resolvedProfile.summary.ref.let { "${it.id}@${it.revision}" }
        profileActivationGeneration = resolvedProfile.activationGeneration
        resolvedProfile.issues.forEach { issue ->
            Log.w(TAG, "profile ${issue.severity.name.lowercase()} ${issue.path}: ${issue.message}")
        }
        passiveProfileProbe = AndroidPassiveProfileProbe(this)
        val mainHandler = Handler(Looper.getMainLooper())
        profileRestart = ProfileRestartCoordinator(
            schedule = { delayMs, action -> mainHandler.postDelayed(action, delayMs) },
            restartProcess = {
                // START_STICKY is already the field-established restart route used for WebView
                // replacement. It avoids Android 12+'s background foreground-service start ban.
                Log.i(TAG, "restarting process to activate staged profile")
                kotlin.system.exitProcess(0)
            },
            safeToRestart = { !InstallProgress.running },
        )
        config.attachProfile(profile)   // supplies per-panel manufacturer/model defaults
        sensors = SensorReporter(this, config, profile)
        // Shared demand-driven logcat captures (one subprocess + one redaction pass each): the app
        // source feeds both remote shipping and the :8888 live log viewer; the system source (su)
        // only the viewer. Idle-stopped — no subprocess runs until something subscribes.
        logCaptureApp = LogCapture.app(scope)
        logCaptureSystem = LogCapture.system(scope)
        // Optional remote log shipping (off + inert unless a sink host is configured). Started in
        // onStartCommand alongside the other network subsystems; restarted on a /config change.
        logShipper = LogShipper(config, scope, logCaptureApp)

        brightness = BrightnessController(this)
        brightness.applyPreventIdleDim(config.preventIdleDim, config)
        autoBright = AutoBrightnessController(brightness, config)
        screen = ScreenController(
            brightness,
            AndroidScreenPower(this),
            wakeTap = OverlayWakeTap(this),
            route = profile.screenOff,
        )
        // After a LOCAL touch-wake, tell HA the screen is on so `light.<panel>_screen` tracks reality.
        screen.onWakeByTap = { runCatching { mqtt.publishScreenOn() } }
        led = LedFactory.detect(profile)
        ledEffect = LedEffectController(led)
        navigate = NavigateController(this)
        volume = VolumeController(this)
        audio = AudioPlaybackCoordinator(
            AudioPlayer.factory(cacheDir),
            onFailure = { error -> Log.w(TAG, "audio playback failed: ${error.javaClass.simpleName}") },
        )
        system = SystemController(AndroidSystemEnv(this))
        entityLearning = EntityLearningManager(
            context = this,
            config = config,
            scope = scope,
            // The runtime owner is initialized here; onStartCommand starts its mDNS instance before the
            // learner can synchronize, so UUID discovery has a live resolver from the first attempt.
            resolveInstanceUuid = { urls -> runtime.current().mdns.discoverHaInstanceUuid(urls) },
            onFilterChanged = {
                // Synchronization can finish while another renderer is deliberately selected (for example
                // during a staged cutover). Persist the learned set, but never let that background work
                // launch DashboardActivity behind the configured renderer's back.
                if (shouldReloadBuiltinAfterEntityFilterChange(
                        config.dashboardPackage,
                        SystemController.BUILTIN_DASHBOARD,
                    )) {
                    system.reloadDashboard(SystemController.BUILTIN_DASHBOARD)
                }
            },
        )
        EntityLearningRuntime.attach(entityLearning)
        watchdog = WatchdogController(system, config)
        kiosk = KioskController(this, system, config)
        // On-device unlock gesture (7 corner taps): persist OFF + clear the lock + tell HA — off the main
        // thread (the gesture fires on the overlay's touch listener; root + HiveMQ must not run there).
        kiosk.onUnlockRequested = {
            Thread {
                if (serviceStopping) return@Thread
                config.setKioskLock(false)
                runCatching { kiosk.apply(false) }
                if (!serviceStopping) runCatching { mqtt.publishKioskState() }
            }.apply { isDaemon = true; name = "kiosk-unlock" }.start()
        }
        // When taming disables a vendor home launcher (e.g. eWeLink), immediately re-assert the dashboard
        // as home and foreground it — otherwise the panel sits on our admin launcher until the 300s
        // watchdog returns to the dashboard (a ~5-minute gap on boot). Off-main is already guaranteed:
        // tame() only ever runs inside a scope.launch / worker thread.
        tame = TameController(this) {
            system.ensureDashboardHome(config.dashboardPackage, config.haUrl.isNotBlank())
            system.launchHome(config.dashboardPackage)
        }
        // Tame opt-in: neutralise the vendor packages the user listed (force-stop + disable boot-relaunch
        // + strip the overlay permission). No-op when the blocklist is empty (the default — a stock panel
        // is never touched); run off-main since pm/am are slow and this is a boot-time one-shot. Critical
        // packages are refused by TameController and the daemon backstop.
        config.tameVendorPackages.takeIf { it.isNotEmpty() }?.let { pkgs ->
            scope.launch { tame.applyBlocklist(pkgs) }
        }
        // The navbar gets the CONFIGURED dashboard value (may be the "builtin" sentinel), never
        // dashboardTarget(): that resolves builtin to our own package name (for perf attribution), and
        // reloadDashboard(ownPackage) would take the foreign-app path — `am force-stop` on ourselves.
        navbar = NavbarController(
            this, system, volume, brightness, { config.launcherPackage }, { config.dashboardPackage },
            profile.appCanSu, profile.hasRecents,
            onBrightnessChanged = { mqtt.publishScreenOn() },
            onVolumeChanged = { mqtt.publishVolume() },
        )
        touchSound = TouchSoundController(this)
        // Re-apply at boot: the switch raises the system-stream volume only when toggled, so a panel that
        // booted with touch-sound already on would otherwise stay silent (volume left at 0).
        if (touchSound.isEnabled()) touchSound.set(true)
        bootChime = BootChimeController(this, config)
        bootChime.applyPersisted()
        zigbee = ZigbeeController(profile)
        relay = RelayController(profile)
        cpu = CpuController(profile)
        adb = AdbController(config)
        power = PowerController(this)

        runtime = ServiceRuntimeOwner(
            initial = NetworkRuntime(buildMqtt(), MdnsAdvertiser(this, config)),
            threadName = "ha-paneld-runtime",
            onError = { operation, error -> Log.e(TAG, "runtime $operation failed", error) },
        )
        rendererPreparation = RendererPreparationCoordinator(
            builtinPackage = SystemController.BUILTIN_DASHBOARD,
            state = { RendererPreparationState(
                config.dashboardPackage,
                config.haUrl,
                config.rendererLaunchPending,
            ) },
            borrow = borrow@{
                val login = CompanionDb.readLogin(this, Su) ?: return@borrow null
                BorrowedRendererSettings(
                    url = login.url,
                    accessToken = login.accessToken,
                    refreshToken = login.refreshToken,
                    tokenExpiry = login.expirySec,
                    clientId = CompanionDb.COMPANION_CLIENT_ID,
                    zoom = CompanionDb.readPageZoom(this, Su)?.coerceIn(50, 300),
                )
            },
            persist = { borrowed ->
                commitBorrowedRendererTarget(
                    commit = {
                        config.setBorrowedRendererSettings(
                            url = borrowed.url,
                            accessToken = borrowed.accessToken,
                            refreshToken = borrowed.refreshToken,
                            tokenExpiry = borrowed.tokenExpiry,
                            clientId = borrowed.clientId,
                            zoom = borrowed.zoom,
                        )
                    },
                    onCommitted = entityLearning::onTargetConfigurationChanged,
                )
            },
            completeLaunch = config::completeRendererLaunch,
        )
        server = PaneldServer(
            config, cacheDir, scope, this, sensors, profile, system, volume, audio::submit, ::reconfigure,
            // Capture the field (not the current instance) so it always targets the live bridge,
            // which reconfigure() rebuilds on a panel_id / MQTT change.
            { k, v -> mqtt.applySetting(k, v) },
            // Controller-sourced setting values (their state isn't in SharedPreferences) so the
            // config form/schema/dashboard show live truth. Called on Ktor IO threads (su-safe).
            liveValues = {
                mapOf(
                    "touch_sound" to touchSound.isEnabled().toString(),
                    "cpu_governor" to (cpu.currentTier() ?: "Auto"),
                    "network_adb" to adb.isPersisted().toString(),
                    "zigbee_router" to config.zigbeeRouterEnabled.toString(),
                )
            },
            capabilities = ::capabilitiesSnapshot,
            info = ::panelInfo,
            recommendedDensity = profile.recommendedDensity,
            recommendedFontScale = profile.recommendedFontScale,
            // Vendor taming: the controller and this panel's curated recommendations (picker group 1).
            tame = tame, tameProfileCandidates = profile.tameVendorCandidates,
            // Live log viewer sources (Logs tab). System is gated on Su.available() per request.
            logApp = logCaptureApp, logSystem = logCaptureSystem,
            effectiveBrightness = { brightness.getBrightness() },
            onRepairCompanionUrl = { repairCompanionUrl() },
            onInstallComponent = { name, action, version -> installComponent(name, action, version) },
            // One-line EFR32 radio status for the Install-tab Radio card; null when this panel has no radio.
            radioStatus = { if (profile.zigbeeGatewayDir != null) zigbee.status() else null },
            // LAN ha-paneld peers over mDNS for the header panel switcher. Captures the `mdns` FIELD (not a
            // snapshot) so it follows reconfigure()'s reassignment; browsePeers null-guards the swap window.
            peers = { mdns.browsePeers() },
            rendererPreparation = rendererPreparation,
            entityLearning = entityLearning,
            profileAdmin = profileRegistry,
            profileTemplate = {
                val report = passiveProfileProbe.report()
                ProfileDraftFactory.create(report.facts, report).rawYaml
            },
            profileDeviceDraft = {
                passiveProfileProbe.report().takeIf { profile.id == "generic" }
                    ?.let(ProfileDraftFactory::fromReport)
            },
            profileReport = passiveProfileProbe::report,
            profileProbe = { passiveProfileProbe.report() },
            onProfileRestart = { profileRestart.request() },
            profileRestartAllowed = { !InstallProgress.running },
            onProfileRestartAbort = profileRegistry::abortPendingActivation,
        )
    }

    private fun buildMqtt(stalePanelId: String? = null): MqttBridge = MqttBridge(
        config, brightness, screen, led, ledEffect, navigate, volume, system, navbar, watchdog, kiosk, touchSound, bootChime, zigbee, relay, cpu, adb,
        accessibilityEnabled(), profile.evdevButtons.isNotEmpty(),
        { capabilitiesSnapshot() },
        sensors.hasLight(), sensors.hasProximity(),
        sensors.hasTemperature(), sensors.hasHumidity(),
        profile.hasCht8305,
        // Button backlight is a distinct profiled node (TPA10), not a property of the RGB backend:
        // SMT1019 also uses SocketLedController for RGB but has no button-backlight node.
        profile.hasButtonBacklight,
        profile.appCanSu, profile.hasRecents,
        autoBright, { localIpv4()?.let { "http://$it:${config.httpPort}/" } },
        // When no broker is configured, find HA on the LAN via mDNS and default to its :1883.
        discoverHaIp = { mdns.discoverHaIp() },
        // HA's advertised base URL (from zeroconf) for the "Open in HA" device link.
        discoverHaUrl = { mdns.discoverHaBaseUrl() },
        // Companion install/update button → run off-thread (network + su).
        onUpdateCompanion = {
            if (!installComponent("companion", "reinstall", "")) {
                Log.w(TAG, "Companion manual update skipped: another destructive operation is running")
            }
        },
        // ha-paneld self-update (off-thread): force=true from the update_paneld button + a pre-release→
        // stable channel switch; force=false lets isNewer gate (no auto-downgrade off an rc).
        onSelfUpdate = { force ->
            if (!installComponent("paneld", if (force) "reinstall" else "update", "")) {
                Log.w(TAG, "self-update skipped: another destructive operation is running")
            }
        },
        onDashboardTargetChanged = entityLearning::onTargetConfigurationChanged,
        stalePanelId = stalePanelId,
        profileIdentity = activeProfileIdentity,
        profileButtonEventTypes = profile.evdevButtons.mapTo(linkedSetOf()) { it.eventType },
    )

    /**
     * Apply config the HTTP page has already written to [config], then rebuild MQTT + mDNS. A renamed
     * panel's discovery cleanup is handed to the replacement bridge so it runs on a live connection.
     */
    private fun reconfigure() {
        runtime.reconfigure(
            stop = { previous ->
                // The replacement uses the same availability topic unless the panel id changed. Do not race
                // its retained online with a late offline from the retiring client. A genuinely different
                // broker still needs an explicit offline because the replacement cannot clean that broker.
                val publishOffline = mqttReconfigurePublishesOffline(
                    previous.mqtt.panelId,
                    config.panelId,
                    previous.mqtt.configuredBroker,
                    config.mqttBroker,
                )
                runCatching {
                    previous.mqtt.stop(publishOffline = publishOffline, clearDiscovery = publishOffline)
                }
                runCatching { previous.mdns.stop() }
            },
            build = { previous ->
                val stalePanelId = previous.mqtt.panelId.takeIf { it != config.panelId }
                NetworkRuntime(buildMqtt(stalePanelId), MdnsAdvertiser(this@PaneldService, config))
            },
            start = { replacement ->
                startReconfiguredNetworkRuntime(
                    startMdns = replacement.mdns::start,
                    resolveHaLink = replacement.mqtt::maybeResolveHaLink,
                    startMqtt = replacement.mqtt::start,
                )
            },
            complete = {
                // Re-read the log sink (host/port/protocol/enabled); restarts only if it changed.
                runCatching { logShipper.reconfigure() }
                runCatching { power.apply(config.keepAwake) }   // apply a keep_awake toggle live
                io.github.maxlyth.hapaneld.http.PerfReader.dashboardPkg = dashboardTarget()
                io.github.maxlyth.hapaneld.http.PerfReader.builtinActive = config.dashboardPackage == SystemController.BUILTIN_DASHBOARD
                Log.i(TAG, "reconfigured: panel=${config.panelId} broker=${config.mqttBroker.ifEmpty { "(disabled)" }}")
            },
        )
    }

    /** Ordered facts for the info page (`GET /`). */
    private fun panelInfo(): Map<String, String> {
        // activeBroker reflects auto-discovery (tcp://<ha-ip>:1883) when no broker is configured.
        val broker = mqtt.activeBroker.ifBlank { config.mqttBroker }
        val host = broker.substringAfter("://").substringBefore(":").ifBlank { "?" }
        val auto = config.mqttBroker.isBlank() && mqtt.activeBroker.isNotBlank()
        val mqttStatus = when (mqtt.state) {
            "connected" -> "$host · connected" + (if (auto) " (auto)" else "")
            "auth-retrying" -> "$host · auth retrying…"
            "auth-failed" -> "$host · reachable, auth rejected — check username/password"
            "unreachable" -> "$host · unreachable"
            "connecting" -> "$host · connecting…"
            else -> "disabled"
        }
        val pv = SystemProps.get("ro.product.version")
        val extras = linkedMapOf(
            "panel_id" to config.panelId,
            "Friendly name" to config.friendlyName,
            "HTTP port" to config.httpPort.toString(),
            "Local IP" to (localIpv4() ?: "?"),
            "Local IPv6" to (localIpv6() ?: "—"),
            "MQTT" to mqttStatus,
            // Host-free state + liveness age + family preference. Deliberately separate from "MQTT"
            // (which carries the broker host and is omitted from /diag): this row IS included in a
            // /diag dump, so a pasted report finally answers "is this panel broker-connected?".
            "MQTT state" to mqtt.statusPublic(),
            // Wakelock/Wi-Fi-lock intent vs reality — a panel that should be keep-awake but isn't
            // holding the lock is a strong hint for stalled-idle-connection reports.
            "Keep awake" to if (config.keepAwake) (if (power.isHeld()) "on · wakelock held" else "on · wakelock NOT held") else "off",
            "mDNS" to "${config.panelId} ${Config.MDNS_SERVICE_TYPE}",
            "Platform" to "${profile.displayName} · ${profile.socClass}",
            "Model" to profile.panelModelLabel(pv),
            "LED" to ledLabel(),
            "Light sensor" to sensorRow(sensors.hasLight(), profile.lightTech, sensors.lightDesc()),
            "Proximity" to sensorRow(sensors.hasProximity(), profile.proximityTech, sensors.proximityDesc()),
            // a11y service = software back/recents nav, NOT physical buttons (NSPanel Pro has none).
            "Nav actions (a11y)" to yesNo(accessibilityEnabled()),
            // Soft navbar overlay mode + whether the overlay can actually be drawn (SYSTEM_ALERT_WINDOW).
            "Navbar" to (config.navbarMode + if (config.navbarMode != "Off" && !canDrawOverlays()) " · no overlay permission" else ""),
            // Zigbee EFR32 state (NSPanel Pro only; "none" elsewhere). Calls su — fine here because
            // the info page is served off the main thread.
            "Zigbee" to zigbee.status(),
            "Relays" to relay.count().let { if (it > 0) it.toString() else "none" },
            "CPU profile" to (cpu.currentTier() ?: "n/a"),
            "Network ADB" to when {
                adb.isPersisted() -> "persistent (5555) · re-asserted by ha-paneld at boot"
                adb.isActive() -> "active (5555) · external — not persisted by ha-paneld"
                else -> "off"
            },
            "Log shipping" to logShipper.statusText(),
            "Audio playback" to audio.snapshot().statusText(),
        )
        if (pv.isNotEmpty()) extras["Product version"] = pv
        // Recent "changed outside MQTT" events (brightness/volume/backlight/governor) — shown only when
        // something has actually synced, so it doesn't clutter a steady panel. Flows to /diag too.
        mqtt.recentSyncEvents().takeIf { it.isNotEmpty() }?.let { extras["Local-state sync"] = it.joinToString(" · ") }
        extras["State convergence"] = mqtt.convergenceStatus()
        return PanelInfo.collect(this, extras, profile)
    }

    private fun ledLabel(): String = when {
        !led.available() -> "none"
        led is Rk3576LedController -> "Rockchip /dev/ledjni (RGB)"
        led is SocketLedController && profile.ledMechanism == LedMechanism.RK3576_IOCTL_DAEMON -> "Rockchip /dev/ledjni helper daemon (RGB)"
        led is SocketLedController && profile.ledMechanism == LedMechanism.SYSFS_DAEMON -> "sysfs helper daemon (RGB)"
        led is SocketLedController -> "helper daemon (RGB)"
        led.colorCapable() -> "RGB"
        else -> "brightness"
    }

    private fun yesNo(b: Boolean) = if (b) "yes" else "no"

    /** "no", or "yes" with any declared technology + runtime value-type/range appended ("yes · Infrared ·
     *  Binary · near/far (0 / 5 cm)"). */
    private fun sensorRow(present: Boolean, tech: String?, desc: String?): String =
        if (!present) "no" else "yes" + listOfNotNull(tech, desc).joinToString("") { " · $it" }

    // zigbee.present() costs a su exec, so probe it once (lazily, off the main thread — first caller
    // is an HTTP handler or MQTT connect, both on background threads) and reuse the answer.
    private val zigbeePresent: Boolean by lazy { zigbee.present() }

    /** This panel's capability snapshot for the settings registry's availableWhen gates
     *  (the Configure form/schema + the dashboard's read-only values card). */
    private fun capabilitiesSnapshot(): Capabilities = Capabilities(
        hasProximity = sensors.hasProximity(),
        hasLight = sensors.hasLight(),
        hasTemperature = sensors.hasTemperature(),
        hasHumidity = sensors.hasHumidity(),
        hasCht8305 = profile.hasCht8305,
        appCanSu = profile.appCanSu,
        hasRecents = profile.hasRecents,
        cpuGovernors = cpu.available(),
        networkAdb = adb.available(),
        zigbeePresent = zigbeePresent,
        hasSystemDarkMode = Build.VERSION.SDK_INT >= 29,   // Android 10+ has the system dark/light setting
        companionInstalled = UpdateChecker.COMPANION_PKGS.any {
            runCatching { packageManager.getPackageInfo(it, 0) }.isSuccess
        },
        webViewManaged = profile.recommendedWebView != null,
        shizukuReady = ShizukuBridge.available(),
        canInstallVerifiedApps = Su.available() || HelperClient.available() || ShizukuBridge.available(),
        canCaptureAndInput = Su.available() || HelperClient.available() || ShizukuBridge.available(),
        canSetDisplay = Su.available() || HelperClient.available() || ShizukuBridge.available(),
    )

    /** Run one destructive operation under the same process-wide ticket used by HTTP restore/APK routes. */
    private suspend fun completeOperation(
        progress: InstallProgress.Ticket,
        logLabel: String,
        operation: suspend () -> String,
        after: suspend (String) -> Unit = {},
    ): String {
        var result = "cancelled"
        try {
            result = try {
                operation()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "$logLabel failed", e)
                "error: ${e.message}"
            }
            Log.i(TAG, "$logLabel: $result")
            InstallProgress.finish(progress, result)
            after(result)
            return result
        } finally {
            // Covers cancellation, fatal errors, and direct callers; stale tickets cannot clear a successor.
            InstallProgress.finish(progress, result)
        }
    }

    /** Start an asynchronous destructive operation only when the shared lane is free. */
    private fun launchOperation(
        component: String,
        logLabel: String,
        operation: suspend () -> String,
        after: suspend (String) -> Unit = {},
    ): Boolean {
        val progress = InstallProgress.start(component) ?: return false
        val job = scope.launch { completeOperation(progress, logLabel, operation, after) }
        // A cancelled job can complete before its body starts, so the body's finally is not sufficient.
        InstallProgress.finishOnFailure(progress, job)
        return true
    }

    /** Run a scheduled destructive operation inline, or skip it when another owner holds the lane. */
    private suspend fun runOperation(
        component: String,
        logLabel: String,
        operation: suspend () -> String,
        after: suspend (String) -> Unit = {},
    ): String? {
        val progress = InstallProgress.start(component) ?: run {
            Log.i(TAG, "$logLabel skipped: another destructive operation is running")
            return null
        }
        return completeOperation(progress, logLabel, operation, after)
    }

    /** Activate a successfully installed WebView provider in whichever renderer owns the dashboard. */
    private suspend fun activateWebView(result: String, verb: String) {
        if (!result.startsWith("OK")) return
        kotlinx.coroutines.delay(2_000)
        if (config.dashboardPackage == io.github.maxlyth.hapaneld.control.SystemController.BUILTIN_DASHBOARD) {
            // A WebView provider binds once per process. Progress is terminal and the HTTP reply has
            // time to flush before START_STICKY restarts the service and HOME on the new provider.
            Log.i(TAG, "WebView $verb — restarting process so the built-in renderer binds the new provider")
            kotlinx.coroutines.delay(1_000)
            kotlin.system.exitProcess(0)
        }
        system.reloadDashboard(config.dashboardPackage)
    }

    /** Scheduled WebView auto-update (opt-in, update tick): advance the System WebView to the profile's
     *  pinned build when it's newer than the running engine. A loop guard skips re-downloading a version
     *  that already installed but never became the provider; it clears when the pinned version advances. */
    private suspend fun autoUpdateWebView(): String {
        val rec = profile.recommendedWebView ?: return "skipped: no managed WebView"
        val engineMajor = io.github.maxlyth.hapaneld.http.PanelInfo.webViewStatus(this@PaneldService).engineMajor
        if (io.github.maxlyth.hapaneld.util.WebViewInstaller.shouldSkipAutoUpdate(config.webViewAutoLastVersion, rec.version, rec.major, engineMajor)) {
            val skipped = "skipped: ${rec.version} already attempted but engine is ${engineMajor ?: "?"}; manual heal may be needed"
            Log.w(TAG, "WebView auto-update: $skipped")
            return skipped
        }
        val r = io.github.maxlyth.hapaneld.util.WebViewInstaller.heal(
            this@PaneldService, profile, engineMajor = engineMajor, force = false, autoUpdate = true,
        )
        // Persist only terminal evidence. A signature-locked provider rejection should not re-download
        // forever, but a transient network/staging/storage/root failure must retry on the next daily tick.
        // A pin bump clears the guard; the manual button always retries regardless of the marker. The
        // caller owns successful provider activation so manual, component, and scheduled paths cannot drift.
        if (io.github.maxlyth.hapaneld.util.WebViewInstaller.shouldRecordAutoAttempt(r)) {
            config.setWebViewAutoLastVersion(rec.version)
        }
        return r
    }

    /** Companion internal_url repair (Install-tab button): copy each server's external_url into a blank
     *  internal_url so HA 2026.7 stops rejecting the dashboard with "Missing 'Host' header". Off-thread
     *  (su force-stops + relaunches the Companion). */
    private fun repairCompanionUrl(): Boolean {
        return launchOperation(
            component = "Companion URL repair",
            logLabel = "Companion internal_url repair",
            operation = {
                io.github.maxlyth.hapaneld.control.CompanionDb.repairInternalUrl(
                    this@PaneldService,
                    io.github.maxlyth.hapaneld.control.Su,
                )
            },
        )
    }

    /** Install/update a managed component from the Install tab (POST /api/v1/install/component). Runs
     *  off-thread; progress is reported via InstallProgress so the web UI can poll. action="reinstall"
     *  forces even when the installed build is already current. Single-slot (InstallProgress.start gates). */
    private fun installComponent(name: String, action: String, version: String): Boolean {
        val label = when (name) {
            "paneld" -> "ha-paneld"; "companion" -> "HA Companion"; "webview" -> "System WebView"; else -> name
        }
        val force = action == "reinstall"
        val tag = version.takeIf { it.isNotBlank() }
        return launchOperation(
            component = label,
            logLabel = "install $name",
            operation = {
                when (name) {
                    // A specific picked version installs that exact tag; otherwise the channel's newest.
                    "paneld" -> if (tag != null) SelfUpdater.installVersion(this@PaneldService, tag)
                        else SelfUpdater.checkAndUpdate(this@PaneldService, config.updateChannel, force = force)
                    "companion" -> if (tag != null) CompanionInstaller.installVersion(
                        this@PaneldService,
                        tag,
                        profile.companionMaxVersion,
                    )
                        else CompanionInstaller.installOrUpdate(
                            this@PaneldService,
                            force = force,
                            channel = config.companionUpdateChannel,
                            maxVersion = profile.companionMaxVersion,
                        )
                    "webview" -> WebViewInstaller.heal(this@PaneldService, profile, engineMajor = null, force = true)
                    else -> "unknown component"
                }
            },
            after = { result ->
                if (name == "webview") activateWebView(result, "healed")
                // Refresh the available-update list so the banner + Install tab reflect the new state.
                runCatching {
                    UpdateChecker.check(
                        this@PaneldService,
                        config.updateChannel,
                        config.companionUpdateChannel,
                        profile.companionMaxVersion,
                    )
                }
            },
        )
    }

    /** Smoothness-metrics target: the configured override, else the installed HA Companion app
     *  (this is an HA project — the dashboard is the Companion app, so no config needed normally). */
    private fun dashboardTarget(): String {
        // Built-in renderer: the WebView runs in our own process, so smoothness metrics target us.
        if (config.dashboardPackage == SystemController.BUILTIN_DASHBOARD) return packageName
        config.dashboardPackage.takeIf { it.isNotBlank() }?.let { return it }
        for (p in listOf("io.homeassistant.companion.android.minimal", "io.homeassistant.companion.android")) {
            if (runCatching { packageManager.getPackageInfo(p, 0) }.isSuccess) return p
        }
        return ""
    }

    /** Advertise the button-event entity only if our a11y service is actually enabled. */
    private fun accessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
        return enabled?.contains(packageName) == true
    }

    /** Whether SYSTEM_ALERT_WINDOW is held — required to draw the soft-navbar overlay. */
    private fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(this)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        // Start subsystems once. Android re-delivers onStartCommand on every startForegroundService()
        // re-issue and on START_STICKY re-create; re-running this block would call server.start() again,
        // binding a second Ktor server on :8888 -> BindException crashes the process (and would also
        // double-start mqtt/mdns/sensors). started is reset in onDestroy so a genuine restart re-inits.
        if (started) return START_STICKY
        started = true
        // Cache HA's frontend URL from the Companion (its internal/external_url) so the header "Open in HA"
        // button always has a target — even when the panel's own device-page URL hasn't resolved (e.g. a
        // remote panel over a tunnel). Root sqlite read, off the main thread; best-effort.
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                io.github.maxlyth.hapaneld.control.CompanionDb.serverUrl(this@PaneldService, io.github.maxlyth.hapaneld.control.Su)
            }.getOrNull()?.let { config.setHaBaseUrl(it) }
        }
        val startupActivationGeneration = profileActivationGeneration
        val startup = runtime.start runtimeStart@{ activeRuntime ->
            // Learning resolves the HA instance identity through this runtime. Start mDNS before either
            // the initial learner sync or a borrowed renderer commit can notify the learner of a target.
            val rendererResult = prepareEntityLearningStartup(
                startMdns = activeRuntime.mdns::start,
                reconcileRenderer = {
                    // Re-assert the configured HOME and repair an interrupted switch to the built-in renderer
                    // before the HTTP surface can accept another configuration transaction. The durable retry
                    // condition is built-in + blank URL; borrowed connection and zoom commit atomically.
                    rendererPreparation.reconcileStartup(
                        ensureHome = { pkg, ready -> system.ensureDashboardHome(pkg, ready) },
                        launchHome = { pkg -> system.launchHome(pkg) },
                    )
                },
                startLearning = entityLearning::start,
            )
            if (rendererResult == RendererPreparationCoordinator.Result.PERSIST_FAILED) {
                Log.e(TAG, "built-in renderer startup preparation did not commit; leaving it retryable")
            }
            if (rendererResult == RendererPreparationCoordinator.Result.CLOSED) return@runtimeStart
            // Resolve "Open in HA" from the prepared built-in renderer session even when MQTT is disabled.
            // MqttBridge owns the compatibility fallback, but native HA URL/token always win.
            activeRuntime.mqtt.maybeResolveHaLink()
            // Keep the SoC + network awake (screen still free to sleep) so Doze/suspend can't freeze the
            // MQTT reactor + keepalive into a half-open, unreachable connection. On by default; see keep_awake.
            runCatching { power.apply(config.keepAwake) }
            io.github.maxlyth.hapaneld.http.PerfReader.dashboardPkg = dashboardTarget()
            io.github.maxlyth.hapaneld.http.PerfReader.builtinActive = config.dashboardPackage == SystemController.BUILTIN_DASHBOARD
            io.github.maxlyth.hapaneld.http.PerfReader.start(scope)
            server.start()
            activeRuntime.mqtt.start()
            // Forward our own logcat to the configured aggregator (no-op unless a sink host is set).
            logShipper.start()
            // Restore the soft navbar to its persisted mode (no-op when Off / no overlay permission).
            navbar.apply(config.navbarMode)
            // Start the app watchdog if enabled (off by default; self-heals a dead/abandoned dashboard).
            watchdog.apply(config.watchdogEnabled)
            // Experimental kiosk lock: a reboot CLEARS the runtime lock (by design — the anti-brick net), so
            // re-assert it after a delay if it was enabled. The delay leaves an unlocked window each boot so
            // an admin is never stranded; skipped if it was turned off (corner gesture / :8888 / HA) meanwhile.
            if (config.kioskLock) scheduleKioskReassert()
            // Boot re-assert of network adb — some firmwares strip persist.adb.tcp.port at boot, so
            // re-apply it when ha-paneld is persisting it (no-op otherwise). See AdbController.reassert.
            runCatching { adb.reassert() }
            sensors.start(
                onLux = { lux -> mqtt.publishLight(lux) },
                onLuxRaw = { lux -> autoBright.submitLux(lux) },
                onProximity = { near ->
                    mqtt.publishProximity(near)
                    // Wake-on-wave: local, instant, wake-only. onProximity fires only on far->near
                    // transitions (natural debounce); sleep stays HA's job. Publish the ON state so the
                    // HA screen entity tracks the local wake (GitHub #6 — was staying OFF in HA).
                    // screen.wake() calls Su.run() — must NOT run on the main thread (ANR risk when
                    // su is under load during the proximity callback, which delivers on the main looper).
                    if (near && config.wakeOnWave) Thread {
                        if (serviceStopping) return@Thread
                        screen.wake()
                        if (!serviceStopping) mqtt.publishScreenOn()
                    }.start()
                },
                onTemperature = { c -> mqtt.publishTemperature(c) },
                onHumidity = { h -> mqtt.publishHumidity(h) },
            )
            // Stream daemon-instrumented hardware buttons into the same event entity as a11y capture.
            // It starts and stops with this runtime so a recreated service cannot inherit a blocked reader.
            EvdevButtonClient.start(profile.evdevButtons)
            scope.periodic(
                intervalMs = 24 * 3_600 * 1_000L,
                initialDelayMs = 30_000L, // let startup settle before hitting the network
                tag = TAG,
                name = "update-check",
            ) {
                // Each sub-step is isolated so one failing doesn't skip the others; the periodic boundary
                // is the outer net that keeps the loop alive across an unexpected throw.
                runCatching {
                    UpdateChecker.check(
                        this@PaneldService,
                        config.updateChannel,
                        config.companionUpdateChannel,
                        profile.companionMaxVersion,
                    )
                }
                // Companion self-heal: when enabled, install a missing Companion / update an out-of-date one.
                if (config.companionAutoUpdate) {
                    runOperation(
                        component = "HA Companion",
                        logLabel = "Companion auto",
                        operation = {
                            CompanionInstaller.installOrUpdate(
                                this@PaneldService,
                                channel = config.companionUpdateChannel,
                                maxVersion = profile.companionMaxVersion,
                            )
                        },
                    )
                }
                // System WebView auto-update (opt-in): advance to the profile's pinned build. BEFORE
                // self-update because a successful WebView install also restarts the process.
                if (config.webViewAutoUpdate) {
                    runOperation(
                        component = "System WebView",
                        logLabel = "WebView auto-update",
                        operation = { autoUpdateWebView() },
                        after = { result -> activateWebView(result, "auto-updated") },
                    )
                }
                // ha-paneld self-update LAST — a successful install restarts this process (and this loop).
                if (config.selfUpdate) {
                    runOperation(
                        component = "ha-paneld",
                        logLabel = "self-update auto",
                        operation = { SelfUpdater.checkAndUpdate(this@PaneldService, config.updateChannel) },
                    )
                }
            }
            startMqttWatchdog()
            // Never-blank-screen watchdog. A screen-off kills the backlight but leaves the device
            // interactive, and nothing re-lights it — so a stray/stale screen-off (e.g. the retained-command
            // strand) or a firmware idle-dim can leave the panel dark and apparently bricked. If the screen
            // is dark but ha-paneld did NOT deliberately turn it off, re-light it; a user-intended
            // "screen off" (isIntendedOff) is left alone.
            scope.periodic(
                intervalMs = SCREEN_WATCHDOG_MS,
                initialDelayMs = 15_000L, // let boot settle before the first check
                tag = TAG,
                name = "never-blank",
            ) {
                if (!screen.isIntendedOff() && screen.looksDark()) {
                    Log.w(TAG, "screen dark with no intent — re-lighting (never-blank guard)")
                    screen.wake()
                    mqtt.publishScreenOn()
                }
            }
            // The HTTP surface, network owner, sensors, evdev and periodic safety owners have all been
            // constructed successfully. Only now may a pending profile replace the previous LKG target.
            profileActivationGeneration?.let { generation ->
                if (profileRegistry.markActivationHealthy(generation)) {
                    Log.i(TAG, "profile activation generation $generation is healthy")
                    profileActivationGeneration = null
                } else {
                    Log.w(TAG, "profile activation generation $generation could not be marked healthy")
                    throw IllegalStateException("profile activation health state could not be persisted")
                }
            }
            if (profileActivationGeneration == null) {
                profileRegistry.status().active?.ref?.let { ref ->
                    if (!profileRegistry.markStartupHealthy(ref)) {
                        Log.w(TAG, "could not persist the healthy profile revision snapshot")
                    }
                }
            }
        }
        if (startupActivationGeneration != null) {
            Thread({
                val healthy = runCatching { startup.get() }.getOrDefault(false)
                if (!healthy) {
                    Log.e(TAG, "profile activation startup failed; scheduling rollback restart")
                    profileRestart.request()
                }
            }, "profile-activation-health").apply { isDaemon = true; start() }
        }
        registerNetworkCallback()
        return START_STICKY
    }

    /**
     * MQTT reconnect watchdog on a DEDICATED thread — deliberately NOT a coroutine on Dispatchers.IO.
     * On a panel with slow/contended `su` (toolbox su under load), blocking su calls exhaust the IO
     * thread pool, so a coroutine watchdog's post-delay continuation never gets scheduled and it silently
     * stops ticking (observed in the field: MQTT "connected" but zero watchdog ticks, so a
     * half-open connection never self-healed). A plain thread ticks regardless of dispatcher pressure.
     *
     * Two stall modes, both healed by a full client rebuild:
     *   (1) STATE-stuck — HiveMQ's auto-reconnect stalls (transient auth reject on an HA/broker restart,
     *       or its reconnect thread is power-management-deferred); rebuild after 2 non-connected checks.
     *   (2) LIVENESS-stale — the broker dropped the link but HiveMQ never noticed the half-open
     *       (CLOSE-WAIT) socket, so it still reports "connected" while publishing into the void.
     *       isConnected() lies, so key on TRUE liveness: each tick send a heartbeat (a QoS-1 publish the
     *       broker must ACK) and, if nothing has been ACKed for MQTT_STALE_MS, force a rebuild.
     *
     * CRITICAL INVARIANT: the loop thread makes NO potentially-blocking MQTT call. A HiveMQ publish —
     * and even disconnect/rebuild — can block on an internal client monitor exactly when the connection
     * is wedged (the same trap as the sensor-callback ANR), which is precisely when the watchdog is
     * needed. rc10 called heartbeat() inline and the watchdog froze inside its own probe (observed in
     * the field: thread parked on a futex, liveness 44 min stale, no rebuild). Heartbeat therefore uses
     * a sacrificial side-thread. Rebuild admission is generation-checked on the lifecycle coordinator,
     * then the accepted reconnect runs on a recovery worker so neither the watchdog nor the serialized
     * transition lane can be trapped by it. If the previous operation is still in flight, skip; the guard
     * re-arms via a timeout so a hung rebuild cannot permanently disable healing. The rebuild DECISION
     * lives in [ConnectionSupervisor]; this thread owns only tick cadence and off-thread dispatch.
     */
    private fun startMqttWatchdog() {
        if (mqttWatchdogAlive) return
        mqttWatchdogAlive = true
        val supervisor = ConnectionSupervisor(MQTT_STALE_MS, REBUILD_ABANDON_MS)
        val worker = Thread {
            try {
                var heartbeat: Thread? = null
                var rebuild: java.util.concurrent.Future<Boolean>? = null
                while (mqttWatchdogAlive) {
                    try { Thread.sleep(MQTT_WATCHDOG_MS) } catch (e: InterruptedException) { break }
                    if (!mqttWatchdogAlive) break
                    val sinceOk = mqtt.msSinceLastOk()
                    val now = android.os.SystemClock.elapsedRealtime()
                    Log.i(TAG, "mqtt watchdog tick: state=${mqtt.state} sinceOk=${sinceOk}ms hb=${heartbeat?.isAlive == true} rebuild=${rebuild?.isDone == false}")
                    when (val action = supervisor.tick(mqtt.state, mqtt.lastOkMs, sinceOk, now, rebuild?.isDone == false)) {
                        is ConnectionSupervisor.Action.Rebuild -> {
                            Log.w(TAG, "MQTT ${action.reason} stall (${sinceOk}ms, state=${mqtt.state}) — forcing reconnect (flip family)")
                            runtime.observe()?.let { observed ->
                                rebuild = runtime.reconnect(observed) { target ->
                                    target.mqtt.reconnect(flipFamily = true)
                                }
                            }
                        }
                        is ConnectionSupervisor.Action.SkipRebuild ->
                            Log.w(TAG, "mqtt ${action.reason} rebuild wanted but one in flight ${action.inFlightMs}ms — skipping")
                        ConnectionSupervisor.Action.None -> {}
                    }
                    // Heartbeat LAST and OFF-THREAD: a wedged client blocks the publish, but only this
                    // sacrificial thread — the loop keeps ticking and the un-refreshed lastOkMs is itself
                    // the failure signal. Skip while one is still in flight (its ACK never came).
                    if (heartbeat?.isAlive != true) {
                        val observed = runtime.observe()
                        heartbeat = Thread({
                            if (observed != null && runtime.isCurrent(observed)) {
                                runCatching { observed.value.mqtt.heartbeat() }
                            }
                        }, "mqtt-heartbeat").apply { isDaemon = true; start() }
                    }
                }
            } finally {
                synchronized(this@PaneldService) {
                    if (mqttWatchdogThread === Thread.currentThread()) mqttWatchdogThread = null
                }
            }
        }.apply { isDaemon = true; name = "mqtt-watchdog" }
        synchronized(this) { mqttWatchdogThread = worker }
        worker.start()
    }

    /**
     * Nudge MQTT to reconnect the moment the default network returns (Wi-Fi / router flap), instead of
     * waiting out the auto-reconnect backoff. Best-effort: no-op if MQTT is already connected or disabled.
     */
    private fun registerNetworkCallback() {
        if (netCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val observed = runtime.observe() ?: return
                val target = observed.value.mqtt
                target.refreshDiscoveryAddress()
                if (target.state != "connected" && target.state != "disabled" && !isAuthRecoveryState(target.state)) {
                    Log.i(TAG, "network available — nudging MQTT reconnect")
                    runtime.reconnect(observed) { it.mqtt.reconnect() }
                }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                runtime.observe()?.value?.mqtt?.refreshDiscoveryAddress()
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }.onSuccess { netCallback = cb }
    }

    private fun startForegroundCompat() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val silent = config.silenceBootChime
        val channelId = if (silent) SILENT_CHANNEL_ID else CHANNEL_ID
        if (mgr.getNotificationChannel(channelId) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(channelId, "ha-paneld", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Panel hardware agent for Home Assistant"
                    if (silent) {
                        setSound(null, null)
                        enableVibration(false)
                        setShowBadge(false)
                    }
                },
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("ha-paneld")
            .setContentText("Listening on :${config.httpPort}")
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(silent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        Log.i(TAG, "foreground service started")
    }

    override fun onDestroy() {
        serviceStopping = true
        audio.closeAdmission()
        audio.cancelCurrent()
        started = false
        netCallback?.let { cb ->
            runCatching { (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.unregisterNetworkCallback(cb) }
            netCallback = null
        }
        stopMqttWatchdog()
        val learningShutdown = shutdownEntityLearningAfterIngress(
            stopIngress = {
                if (!::server.isInitialized) true else runCatching { server.stop() }.isSuccess
            },
            closeRendererAdmission = {
                if (!::rendererPreparation.isInitialized) true
                else rendererPreparation.close(RENDERER_SHUTDOWN_MS)
            },
            detachRuntime = {
                if (::entityLearning.isInitialized) EntityLearningRuntime.detach(entityLearning)
            },
            cancelAndDrainScope = {
                val root = scope.coroutineContext[Job]
                scope.cancel()
                if (root == null) true else runBlocking {
                    withTimeoutOrNull(SCOPE_SHUTDOWN_MS) { root.join(); true } ?: false
                }
            },
            closeStore = { if (::entityLearning.isInitialized) entityLearning.close() },
        )
        if (!learningShutdown.ingressStopped) Log.w(TAG, "HTTP ingress did not stop cleanly before learner teardown")
        if (!learningShutdown.rendererDrained) {
            Log.w(TAG, "renderer transaction did not become idle within ${RENDERER_SHUTDOWN_MS}ms")
        }
        if (!learningShutdown.scopeDrained) Log.w(TAG, "service jobs did not drain within ${SCOPE_SHUTDOWN_MS}ms")
        if (::entityLearning.isInitialized && !learningShutdown.storeClosed) {
            Log.w(TAG, "entity-learning store left open for process teardown because a producer did not drain")
        }
        val stopped = runtime.shutdown(RUNTIME_SHUTDOWN_MS) { activeRuntime ->
            fun closeOwner(name: String, close: () -> Unit) {
                runCatching(close).onFailure { error ->
                    Log.w(TAG, "$name cleanup failed", error)
                }
            }
            val audioStopped = runCatching {
                kotlinx.coroutines.runBlocking { audio.close(AUDIO_SHUTDOWN_MS) }
            }.getOrDefault(false)
            if (!audioStopped) {
                Log.w(TAG, "audio cleanup exceeded ${AUDIO_SHUTDOWN_MS}ms")
            }
            cancelKioskReassert()
            // Close and drain command ingress before producers and hardware owners. MqttBridge.stop()
            // also owns HTTP live-setting dispatch, so neither route can enter an owner during teardown.
            closeOwner("MQTT") { activeRuntime.mqtt.stop() }
            closeOwner("evdev") { EvdevButtonClient.stop() }
            closeOwner("sensors") { sensors.stop() }
            closeOwner("watchdog") { watchdog.stop() }
            closeOwner("LED effect") { ledEffect.close() }
            closeOwner("screen") { screen.close() } // restore a deliberate dark screen before releasing power
            closeOwner("navbar") { navbar.cleanup() }
            closeOwner("power") { power.apply(false) }
            closeOwner("log shipper") { logShipper.stop() }
            closeOwner("performance reader") { io.github.maxlyth.hapaneld.http.PerfReader.stop() }
            closeOwner("app log capture") { logCaptureApp.close() }
            closeOwner("system log capture") { logCaptureSystem.close() }
            closeOwner("mDNS") { activeRuntime.mdns.stop() }
        }
        if (!stopped) Log.w(TAG, "runtime teardown exceeded ${RUNTIME_SHUTDOWN_MS}ms; cleanup continues on its owner thread")
        super.onDestroy()
    }

    private fun scheduleKioskReassert() {
        val worker = Thread {
            try {
                Thread.sleep(KIOSK_REASSERT_MS)
                if (!serviceStopping && config.kioskLock) runCatching { kiosk.apply(true) }
            } catch (_: InterruptedException) {
                // Service teardown cancels the delayed lock before it can act.
            } finally {
                synchronized(this@PaneldService) {
                    if (kioskReassertThread === Thread.currentThread()) kioskReassertThread = null
                }
            }
        }.apply { isDaemon = true; name = "kiosk-reassert" }
        synchronized(this) { kioskReassertThread = worker }
        worker.start()
    }

    private fun cancelKioskReassert() {
        val worker = synchronized(this) { kioskReassertThread.also { kioskReassertThread = null } }
        worker?.interrupt()
        runCatching { worker?.join(KIOSK_CANCEL_JOIN_MS) }
    }

    private fun stopMqttWatchdog() {
        mqttWatchdogAlive = false
        val worker = synchronized(this) { mqttWatchdogThread.also { mqttWatchdogThread = null } }
        worker?.interrupt()
        runCatching { worker?.join(WATCHDOG_CANCEL_JOIN_MS) }
    }

    private fun reconcileHelperInstallStaging() {
        scope.launch {
            val stagingDir = File(filesDir, HelperInstallTransaction.STAGING_DIR)
            val reconciler = HelperInstallReconciler(HelperClient)
            while (isActive) {
                val result = runCatching { reconciler.reconcile(stagingDir) }
                    .onFailure { Log.w(TAG, "helper install staging reconciliation failed", it) }
                    .getOrNull()
                if (result != null && (result.removed > 0 || result.remaining > 0)) {
                    Log.d(TAG, "helper install staging: removed=${result.removed} remaining=${result.remaining}")
                }
                delay(INSTALL_RECONCILE_MS)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "ha-paneld/svc"
        private const val CHANNEL_ID = "ha-paneld"
        // Channel sound is immutable after creation. A distinct ID guarantees the silence setting can
        // supersede an older install whose original channel was created with a notification sound.
        private const val SILENT_CHANNEL_ID = "ha-paneld-silent-v1"
        private const val NOTIF_ID = 1
        // MQTT reconnect-watchdog poll interval; a stuck bridge self-heals after ~2 of these.
        private const val MQTT_WATCHDOG_MS = 60_000L
        // A rebuild thread wedged inside the old client for this long is abandoned (it stays parked as
        // a leaked daemon thread) and a fresh rebuild is attempted — healing must never stay disabled.
        private const val REBUILD_ABANDON_MS = 300_000L
        // An auth-rejected state younger than this (vs the last broker-ACKed activity) renders as
        // "reconnecting…" — only a PERSISTENT rejection surfaces the check-your-credentials warning.
        private const val AUTH_PERSIST_MS = 300_000L
        // No broker-ACKed publish for this long (with a heartbeat sent every tick) ⇒ the link is dead
        // even if HiveMQ still claims "connected" (half-open socket) ⇒ force a rebuild. ~2.5 missed ticks.
        private const val MQTT_STALE_MS = 150_000L
        // Never-blank-screen watchdog poll interval; re-lights an unintentionally-dark panel within one tick.
        private const val SCREEN_WATCHDOG_MS = 60_000L
        private const val INSTALL_RECONCILE_MS = 60_000L
        private const val KIOSK_REASSERT_MS = 60_000L // post-boot delay before re-locking (admin escape window)
        private const val KIOSK_CANCEL_JOIN_MS = 500L
        private const val WATCHDOG_CANCEL_JOIN_MS = 500L
        private const val RENDERER_SHUTDOWN_MS = 1_000L
        private const val SCOPE_SHUTDOWN_MS = 2_000L
        private const val AUDIO_SHUTDOWN_MS = 2_000L
        private const val RUNTIME_SHUTDOWN_MS = 5_000L

        fun start(context: Context) {
            val intent = Intent(context, PaneldService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
