package io.github.maxlyth.hapaneld

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.ContentObserver
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
import io.github.maxlyth.hapaneld.persistence.AppState
import io.github.maxlyth.hapaneld.input.EvdevButtonClient
import io.github.maxlyth.hapaneld.control.AutoBrightnessController
import io.github.maxlyth.hapaneld.control.BootChimeController
import io.github.maxlyth.hapaneld.control.BrightnessController
import io.github.maxlyth.hapaneld.control.BrightnessPreferenceOrigin
import io.github.maxlyth.hapaneld.control.CpuController
import io.github.maxlyth.hapaneld.control.CompanionDb
import io.github.maxlyth.hapaneld.control.CompanionDataOperationGate
import io.github.maxlyth.hapaneld.control.CompanionDataOperationState
import io.github.maxlyth.hapaneld.control.CdpRelay
import io.github.maxlyth.hapaneld.control.NavbarController
import io.github.maxlyth.hapaneld.control.PowerController
import io.github.maxlyth.hapaneld.control.PrivilegeRoute
import io.github.maxlyth.hapaneld.control.PrivilegedRouteObservation
import io.github.maxlyth.hapaneld.control.NavigateController
import io.github.maxlyth.hapaneld.control.RelayController
import io.github.maxlyth.hapaneld.control.OverlayWakeTap
import io.github.maxlyth.hapaneld.control.ScreenController
import io.github.maxlyth.hapaneld.control.WakeOutcome
import io.github.maxlyth.hapaneld.control.AndroidWifiDiagnostics
import io.github.maxlyth.hapaneld.control.Su
import io.github.maxlyth.hapaneld.control.SystemController
import io.github.maxlyth.hapaneld.control.TameController
import io.github.maxlyth.hapaneld.control.KioskController
import io.github.maxlyth.hapaneld.control.LedEffectController
import io.github.maxlyth.hapaneld.control.WatchdogController
import io.github.maxlyth.hapaneld.control.TouchSoundController
import io.github.maxlyth.hapaneld.control.VolumeController
import io.github.maxlyth.hapaneld.control.ZigbeeController
import io.github.maxlyth.hapaneld.control.AndroidZigbeeGatewayHealthSource
import io.github.maxlyth.hapaneld.control.ZigbeeHealthMonitor
import io.github.maxlyth.hapaneld.control.ZigbeeObservation
import io.github.maxlyth.hapaneld.control.observeTypedShellCapability
import io.github.maxlyth.hapaneld.device.LedMechanism
import io.github.maxlyth.hapaneld.hardware.LedController
import io.github.maxlyth.hapaneld.hardware.LedFactory
import io.github.maxlyth.hapaneld.hardware.Rk3576LedController
import io.github.maxlyth.hapaneld.hardware.SocketLedController
import io.github.maxlyth.hapaneld.config.Capabilities
import io.github.maxlyth.hapaneld.config.SettingValue
import io.github.maxlyth.hapaneld.config.SettingsRegistry
import io.github.maxlyth.hapaneld.dashboard.EntityLearningManager
import io.github.maxlyth.hapaneld.dashboard.EntityLearningRuntime
import io.github.maxlyth.hapaneld.http.PaneldServer
import io.github.maxlyth.hapaneld.http.PanelInfo
import io.github.maxlyth.hapaneld.http.ManagementProjection
import io.github.maxlyth.hapaneld.http.DiagReader
import io.github.maxlyth.hapaneld.http.AutoBrightnessHttpAction
import io.github.maxlyth.hapaneld.http.AutoBrightnessHttpApi
import io.github.maxlyth.hapaneld.http.AutoBrightnessHttpValidation
import io.github.maxlyth.hapaneld.http.retainCompanionLeaseUntilHelperIdle
import io.github.maxlyth.hapaneld.logship.LogCapture
import io.github.maxlyth.hapaneld.logship.LogShipper
import io.github.maxlyth.hapaneld.media.AudioPlaybackCoordinator
import io.github.maxlyth.hapaneld.provisioning.AndroidProvisioningObservationCollector
import io.github.maxlyth.hapaneld.provisioning.ProvisioningActivationSnapshot
import io.github.maxlyth.hapaneld.provisioning.ProvisioningCoordinator
import io.github.maxlyth.hapaneld.provisioning.ProvisioningCoreIdentity
import io.github.maxlyth.hapaneld.provisioning.toProvisioningProfile
import io.github.maxlyth.hapaneld.sensors.SensorReporter
import io.github.maxlyth.hapaneld.sensors.SensorLightPublisher
import io.github.maxlyth.hapaneld.sensors.submitIlluminanceIfExposed
import io.github.maxlyth.hapaneld.sensors.HaAmbientLuxSubscriber
import io.github.maxlyth.hapaneld.sensors.HaAmbientSourceValidation
import io.github.maxlyth.hapaneld.sensors.HaAmbientSourcePhase
import io.github.maxlyth.hapaneld.sensors.HaSiteMetadataClient
import io.github.maxlyth.hapaneld.util.localIpv4
import io.github.maxlyth.hapaneld.util.localIpv6
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import io.github.maxlyth.hapaneld.util.LatestOperationPolicy
import io.github.maxlyth.hapaneld.util.LatestOperationTimeoutPolicy
import io.github.maxlyth.hapaneld.util.ServiceRuntimeOwner
import io.github.maxlyth.hapaneld.util.SingleFlightExecutor
import io.github.maxlyth.hapaneld.util.SuccessStickyProbe
import io.github.maxlyth.hapaneld.metrics.FeatureCostOperation
import io.github.maxlyth.hapaneld.metrics.FeatureCostOutcome
import io.github.maxlyth.hapaneld.metrics.FeatureCosts
import io.github.maxlyth.hapaneld.util.BorrowedRendererSettings
import io.github.maxlyth.hapaneld.util.BundledHelperInstaller
import io.github.maxlyth.hapaneld.util.RendererPreparationCoordinator
import io.github.maxlyth.hapaneld.util.RendererPreparationState
import io.github.maxlyth.hapaneld.util.MonotonicDeadline
import io.github.maxlyth.hapaneld.util.awaitSuccessful
import io.github.maxlyth.hapaneld.util.awaitTrue
import io.github.maxlyth.hapaneld.util.ProfileRestartCoordinator
import io.github.maxlyth.hapaneld.util.ServiceRestartBarrier
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import io.github.maxlyth.hapaneld.util.UpdateChecker
import io.github.maxlyth.hapaneld.util.CompanionInstaller
import io.github.maxlyth.hapaneld.util.CompanionOperationStatus
import io.github.maxlyth.hapaneld.util.InstallProgress
import io.github.maxlyth.hapaneld.util.HelperClient
import io.github.maxlyth.hapaneld.util.HelperInstallReconciler
import io.github.maxlyth.hapaneld.util.HelperInstallTransaction
import io.github.maxlyth.hapaneld.util.SelfUpdater
import io.github.maxlyth.hapaneld.util.WebViewInstaller
import io.github.maxlyth.hapaneld.mqtt.ConnectionSupervisor
import io.github.maxlyth.hapaneld.mqtt.HeartbeatAdmission
import io.github.maxlyth.hapaneld.mqtt.isAuthRecoveryState
import io.github.maxlyth.hapaneld.platform.AndroidScreenPower
import io.github.maxlyth.hapaneld.platform.AndroidSystemEnv
import io.github.maxlyth.hapaneld.util.periodic
import io.github.maxlyth.hapaneld.util.SystemProps
import io.github.maxlyth.hapaneld.dashboard.shouldReloadBuiltinAfterEntityFilterChange
import io.github.maxlyth.hapaneld.shizuku.ShizukuBridge
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal fun shouldDisableAutoBrightnessForMissingSource(
    enabled: Boolean,
    haEntity: String,
    hasLocalSensor: Boolean,
): Boolean = enabled && haEntity.isBlank() && !hasLocalSensor

internal fun preferredAdminHomeRepairRoute(
    privilege: PrivilegedRouteObservation,
): PrivilegeRoute? = preferredAdminHomeRepairRoute(
    helperRootReady = privilege.helperRootReady,
    directSuReady = privilege.directSuReady,
)

internal fun preferredAdminHomeRepairRoute(
    helperRootReady: Boolean,
    directSuReady: Boolean,
): PrivilegeRoute? = when {
    helperRootReady -> PrivilegeRoute.DAEMON
    directSuReady -> PrivilegeRoute.SU
    else -> null
}

internal fun shouldAttemptPeriodicAdminHomeRepair(
    serviceStopping: Boolean,
    adminHomeSelected: Boolean,
    admittedRoute: PrivilegeRoute?,
    policyGeneration: Long,
    failedGeneration: Long,
): Boolean = !serviceStopping && adminHomeSelected && admittedRoute != null &&
    failedGeneration != policyGeneration

internal enum class ServiceStartupDisposition {
    RUNNING,
    PROFILE_ACTIVATION_ROLLBACK,
    DEGRADED,
}

internal data class StartupRecoveryDecision(val restart: Boolean, val nextAttempt: Int)

internal fun shouldForceFreshProcessAfterExternalRecovery(
    attempt: Int,
    maxAttempts: Int,
    kioskSafe: Boolean,
    navbarSafe: Boolean,
    screenSafe: Boolean,
    relaySafe: Boolean,
): Boolean = attempt >= maxAttempts && screenSafe && !(kioskSafe && navbarSafe && relaySafe)

/** Main-safe phase zero for both Android destruction and explicit process replacement. */
internal fun beginAudioTeardown(
    closeAdmission: () -> Unit,
    cancelCurrent: () -> Unit,
) {
    closeAdmission()
    cancelCurrent()
}

/** Continue the complete owner sweep while retaining any ambiguous cleanup result for the final gate. */
internal class ServiceOwnerCleanupTracker {
    private val complete = AtomicBoolean(true)

    fun run(block: () -> Unit): Throwable? = try {
        block()
        null
    } catch (error: Throwable) {
        complete.set(false)
        error
    }

    fun record(result: Boolean) {
        if (!result) complete.set(false)
    }

    fun isComplete(): Boolean = complete.get()
}

/** Reuse the onDestroy screen owner; a second mutator could wake a successor after barrier release. */
internal fun proveScreenSafeForBoundary(
    existingOwner: CompletableFuture<Boolean>,
): Boolean = existingOwner.getNow(false)

/** Retry pending durable kiosk cleanup after the boot escape window without blocking unrelated service
 * startup. The retry count is finite so permanently unavailable root never creates steady background
 * load; retained recovery markers make the next service start retry again. */
internal fun recoverAndMaybeEnableKiosk(
    escapeDelayMs: Long,
    retryDelayMs: Long,
    maxAttempts: Int,
    shouldContinue: () -> Boolean,
    recover: () -> Boolean,
    enabled: () -> Boolean,
    enable: () -> Boolean,
    pause: (Long) -> Unit = Thread::sleep,
): Boolean {
    require(escapeDelayMs >= 0L)
    require(retryDelayMs >= 0L)
    require(maxAttempts > 0)
    return try {
        pause(escapeDelayMs)
        repeat(maxAttempts) { attempt ->
            if (!shouldContinue()) return false
            if (recover()) {
                if (!shouldContinue()) return false
                return !enabled() || enable()
            }
            if (attempt + 1 < maxAttempts) pause(retryDelayMs)
        }
        false
    } catch (_: InterruptedException) {
        false
    }
}

/** Bound process-level recovery for transient startup failures; clock rollback/reboot opens a new window. */
internal fun startupRecoveryDecision(
    previousAttempts: Int,
    previousAtMs: Long,
    nowMs: Long,
    maxAttempts: Int = 3,
    windowMs: Long = 10L * 60L * 1_000L,
): StartupRecoveryDecision {
    val sameWindow = previousAtMs in 1..nowMs && nowMs - previousAtMs <= windowMs
    val next = (if (sameWindow) previousAttempts.coerceAtLeast(0) else 0) + 1
    return StartupRecoveryDecision(next <= maxAttempts, next)
}

internal fun awaitServiceStartup(
    startup: Future<Boolean>,
    profileActivationGeneration: Long?,
): ServiceStartupDisposition {
    val healthy = runCatching { startup.get() }.getOrDefault(false)
    return when {
        healthy -> ServiceStartupDisposition.RUNNING
        profileActivationGeneration != null -> ServiceStartupDisposition.PROFILE_ACTIVATION_ROLLBACK
        else -> ServiceStartupDisposition.DEGRADED
    }
}

/** Confirm declared Zigbee hardware when readable; preserve declared capability across probe failure. */
internal fun zigbeeCapabilityPresent(
    declaredGateway: Boolean,
    observation: ZigbeeObservation,
): Boolean = declaredGateway && (!observation.probeSucceeded || observation.present)

internal fun commitBorrowedRendererTarget(
    commit: () -> Boolean,
    onCommitted: () -> Unit,
): Boolean {
    val committed = commit()
    if (committed) onCommitted()
    return committed
}

internal fun replayThenRefreshLiveConfiguration(
    replay: () -> Unit,
    refresh: () -> Unit,
) {
    replay()
    refresh()
}

internal fun adaptiveHaSource(enabled: Boolean, configuredEntity: String): String? =
    configuredEntity.trim().takeIf { enabled && it.isNotEmpty() }

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

/**
 * Reconstruct the process-local Companion launch gate only when the app-private marker proves that
 * this app may have submitted a helper transaction before process death. Ordinary helper absence
 * without that marker is not a launch block. A retained lease is released only after an affirmative
 * IDLE status also clears the marker durably.
 */
internal fun restoreCompanionLaunchSuppression(
    packageName: String,
    operationState: CompanionDataOperationState,
    operationStatus: () -> CompanionOperationStatus,
    retain: (CompanionDataOperationGate.Lease) -> Unit,
): CompanionOperationStatus {
    if (!operationState.isPending()) {
        return CompanionOperationStatus.IDLE
    }
    val status = runCatching(operationStatus).getOrDefault(CompanionOperationStatus.UNAVAILABLE)
    val resolvedPackage = packageName.takeIf(CompanionDataOperationGate::isCompanionPackage)
        ?: CompanionInstaller.MINIMAL_PKG
    when (status) {
        CompanionOperationStatus.IDLE -> {
            if (!operationState.clear()) {
                CompanionDataOperationGate.acquire(resolvedPackage)?.let(retain)
            }
        }
        CompanionOperationStatus.BUSY,
        CompanionOperationStatus.UNSUPPORTED,
        CompanionOperationStatus.UNAVAILABLE ->
            CompanionDataOperationGate.acquire(resolvedPackage)?.let(retain)
    }
    return status
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

internal enum class NetworkAvailableAction { NONE, RECONNECT, RETRY_DISCOVERY }

/** Blank-broker discovery is a recoverable waiting state, while explicit auth rejection and a stopped
 * bridge retain their own lifecycle policies. */
internal fun networkAvailableAction(state: String, configuredBroker: String): NetworkAvailableAction = when {
    state == "connected" || state == "announcing" || state == "disabled" || state == "config-error" ||
        isAuthRecoveryState(state) -> NetworkAvailableAction.NONE
    state == "discovering" && configuredBroker.isBlank() -> NetworkAvailableAction.RETRY_DISCOVERY
    else -> NetworkAvailableAction.RECONNECT
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

/** Values whose change requires replacing the concrete MQTT/mDNS runtime rather than reannouncing it. */
internal class NetworkRuntimeIdentity(
    internal val panelId: String,
    internal val friendlyName: String,
    internal val httpPort: Int,
    internal val broker: String,
    private val user: String,
    private val password: String,
) {
    override fun equals(other: Any?): Boolean = other is NetworkRuntimeIdentity &&
        panelId == other.panelId && friendlyName == other.friendlyName && httpPort == other.httpPort &&
        broker == other.broker && user == other.user && password == other.password

    override fun hashCode(): Int {
        var result = panelId.hashCode()
        result = 31 * result + friendlyName.hashCode()
        result = 31 * result + httpPort
        result = 31 * result + broker.hashCode()
        result = 31 * result + user.hashCode()
        result = 31 * result + password.hashCode()
        return result
    }

    override fun toString(): String = "NetworkRuntimeIdentity(redacted)"

    internal fun mqttCredentials(): MqttCredentialsSnapshot =
        MqttCredentialsSnapshot(broker, user, password)
}

internal data class MqttProjectionIdentity(
    val manufacturer: String,
    val model: String,
    val exposures: List<Pair<String, Boolean>>,
)

internal class HaLinkIdentity(
    private val url: String,
    private val accessToken: String,
    private val refreshToken: String,
    private val tokenExpiry: Long,
    private val clientId: String,
) {
    override fun equals(other: Any?): Boolean = other is HaLinkIdentity && url == other.url &&
        accessToken == other.accessToken && refreshToken == other.refreshToken &&
        tokenExpiry == other.tokenExpiry && clientId == other.clientId

    override fun hashCode(): Int = listOf(url, accessToken, refreshToken, tokenExpiry, clientId).hashCode()
    override fun toString(): String = "HaLinkIdentity(redacted)"
}

internal data class ConfigRefreshEffects(val reannounceMqtt: Boolean, val resolveHaLink: Boolean)

internal data class NetworkConfigurationSnapshot(
    val runtime: NetworkRuntimeIdentity,
    val projection: MqttProjectionIdentity,
    val haLink: HaLinkIdentity,
)

internal fun configRefreshEffects(
    activeProjection: MqttProjectionIdentity,
    nextProjection: MqttProjectionIdentity,
    activeHaLink: HaLinkIdentity,
    nextHaLink: HaLinkIdentity,
): ConfigRefreshEffects = ConfigRefreshEffects(
    reannounceMqtt = activeProjection != nextProjection,
    resolveHaLink = activeHaLink != nextHaLink,
)

/**
 * Persistent foreground service. Hosts the Ktor HTTP listener, the JmDNS advertiser, the MQTT
 * control bridge and the hardware controllers for the panel's lifetime. Declared
 * `foregroundServiceType=specialUse` because a wall-panel on-LAN agent has no analogue among the
 * predefined FGS types.
 *
 * The service itself draws no UI and never takes HOME foreground. Renderer ownership remains in the
 * configured dashboard activity, whether that is the built-in Home Assistant renderer or a launchable
 * external dashboard application.
 */
class PaneldService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // One dedicated transition lane owns initial start, config rebuilds, reconnects, and final teardown.
    // Observations capture the generation and concrete MQTT/mDNS pair together, so watchdog/network work cannot read a generation from one runtime and then reach a replacement through a mutable field.
    private data class NetworkRuntime(val mqtt: MqttBridge, val mdns: MdnsAdvertiser)
    private lateinit var runtime: ServiceRuntimeOwner<NetworkRuntime>
    private lateinit var restartLease: ServiceRestartBarrier.Lease
    private lateinit var mainHandler: Handler
    private lateinit var liveSettingAuthority: LiveSettingAuthority
    private lateinit var config: Config
    private lateinit var appliedNetworkConfiguration: NetworkConfigurationSnapshot
    private lateinit var server: PaneldServer
    private lateinit var rendererPreparation: RendererPreparationCoordinator
    private lateinit var entityLearning: EntityLearningManager
    private lateinit var companionDataOperationState: CompanionDataOperationState
    private val mqtt: MqttBridge get() = runtime.current().mqtt
    private val mdns: MdnsAdvertiser get() = runtime.current().mdns
    // Default-network callback that nudges an MQTT reconnect when the network returns (see registerNetworkCallback).
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    // MQTT watchdog runs on a DEDICATED thread (not Dispatchers.IO), so slow/contended su can't starve it.
    @Volatile private var mqttWatchdogAlive = false
    @Volatile private var mqttWatchdogThread: Thread? = null
    @Volatile private var serviceStopping = false
    private val launcherHomePolicyGeneration = java.util.concurrent.atomic.AtomicLong()
    private val launcherHomeFailedGeneration = java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE)
    @Volatile private var kioskReassertThread: Thread? = null
    private val wakeOnWaveWorker = SingleFlightExecutor("ha-paneld-wake-on-wave")
    private val lightMqttPublisher = SensorLightPublisher(
        publish = { lux ->
            if (!serviceStopping) {
                runCatching { mqtt.publishLight(lux) }
                    .onFailure { Log.w(TAG, "light MQTT publication failed", it) }
            }
        },
    )
    private lateinit var sensors: SensorReporter
    private lateinit var wifiDiagnostics: AndroidWifiDiagnostics
    private lateinit var logShipper: LogShipper
    private lateinit var logCaptureApp: LogCapture
    private lateinit var logCaptureSystem: LogCapture

    // Controllers are fields so the MQTT bridge can be rebuilt on a panel_id change.
    private lateinit var brightness: BrightnessController
    private lateinit var autoBright: AutoBrightnessController
    private lateinit var haAmbientLux: HaAmbientLuxSubscriber
    private lateinit var haSiteMetadata: HaSiteMetadataClient
    private var brightnessObserver: ContentObserver? = null
    private var haCandidateIdentity = ""
    private val adaptiveSiteGeneration = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var lastObservedCommandedBrightness = -1
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
    private lateinit var kioskSettings: KioskSettingCoordinator
    private lateinit var touchSound: TouchSoundController
    private lateinit var bootChime: BootChimeController
    private lateinit var zigbee: ZigbeeController
    private lateinit var zigbeeHealth: ZigbeeHealthMonitor
    private lateinit var relay: RelayController
    private lateinit var cpu: CpuController
    private lateinit var adb: AdbController
    private lateinit var power: PowerController
    private lateinit var profile: DeviceProfile
    private lateinit var profileRegistry: RuntimeProfileRegistry
    private lateinit var passiveProfileProbe: AndroidPassiveProfileProbe
    private lateinit var profileRestart: ProfileRestartCoordinator
    private lateinit var recoveryRestart: ProfileRestartCoordinator
    private lateinit var activeProfileIdentity: String
    private var profileActivationGeneration: Long? = null
    // One-time-start guard for onStartCommand (see there for why). Reset in onDestroy.
    @Volatile private var started = false
    private val teardownBoundary = ServiceTeardownBoundary()
    private val screenExitRecoveryOwner = AtomicReference<CompletableFuture<Boolean>?>(null)
    private val startupRecoveryPrefs by lazy {
        AppState.preferences(this, "startup-recovery", "ha-paneld-startup-recovery")
    }

    override fun onCreate() {
        super.onCreate()
        restartLease = SERVICE_RESTART_BARRIER.enter()
        config = Config(this)
        config.migrateLiveStore()   // carry persisted settings across a schema bump before anything reads them
        liveSettingAuthority = LiveSettingAuthority.persistent(this, MqttBridge.APPLY_SETTING_KEYS)
        // Resolve one immutable profile revision before constructing any hardware owner. Activations are
        // restart-bound, so every controller below observes this exact object for the service lifetime.
        profileRegistry = RuntimeProfileRegistry(this)
        val resolvedProfile = profileRegistry.resolveForStartup()
        profile = resolvedProfile.profile
        activeProfileIdentity = resolvedProfile.summary.ref.let { "${it.id}@${it.revision}" }
        profileActivationGeneration = resolvedProfile.activationGeneration
        val provisioningReader = ProvisioningCoordinator(
            core = ProvisioningCoreIdentity(
                version = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
            ),
            profile = resolvedProfile.toProvisioningProfile(),
            collector = AndroidProvisioningObservationCollector(this),
            monotonicMs = { android.os.SystemClock.elapsedRealtime() },
        )
        resolvedProfile.issues.forEach { issue ->
            Log.w(TAG, "profile ${issue.severity.name.lowercase()} ${issue.path}: ${issue.message}")
        }
        passiveProfileProbe = AndroidPassiveProfileProbe(this)
        mainHandler = Handler(Looper.getMainLooper())
        profileRestart = ProfileRestartCoordinator(
            schedule = { delayMs, action -> mainHandler.postDelayed(action, delayMs) },
            restartProcess = {
                // START_STICKY is already the field-established restart route used for WebView
                // replacement. It avoids Android 12+'s background foreground-service start ban.
                requestSafeProcessBoundary("activating staged profile")
            },
            safeToRestart = { !InstallProgress.running },
            shouldAbandon = { serviceStopping },
        )
        recoveryRestart = ProfileRestartCoordinator(
            schedule = { delayMs, action -> mainHandler.postDelayed(action, delayMs) },
            restartProcess = {
                requestSafeProcessBoundary("bounded runtime recovery")
            },
            safeToRestart = { !InstallProgress.running },
            shouldAbandon = { serviceStopping },
            responseGraceMs = RECOVERY_RESTART_GRACE_MS,
        )
        config.attachProfile(profile)   // supplies per-panel manufacturer/model defaults
        appliedNetworkConfiguration = currentNetworkConfigurationSnapshot()
        sensors = SensorReporter(this, config, profile)
        wifiDiagnostics = AndroidWifiDiagnostics(this) {
            Su.runOutputIsolatedBounded(
                "cmd wifi status 2>/dev/null || dumpsys wifi",
                maxBytes = 128 * 1024L,
                timeoutMs = 2_000L,
            )
        }
        // Shared demand-driven logcat captures (one subprocess + one redaction pass each): the app
        // source feeds both remote shipping and the :8888 live log viewer; the system source (su)
        // only the viewer. Idle-stopped — no subprocess runs until something subscribes.
        logCaptureApp = LogCapture.app(scope)
        logCaptureSystem = LogCapture.system(scope)
        // Optional remote log shipping (off + inert unless a sink host is configured). Started in
        // onStartCommand alongside the other network subsystems; restarted on a /config change.
        logShipper = LogShipper(config, scope, logCaptureApp)

        brightness = BrightnessController(this)
        screen = ScreenController(
            brightness,
            AndroidScreenPower(this),
            wakeTap = OverlayWakeTap(this),
            route = profile.screenOff,
        )
        autoBright = AutoBrightnessController(this, brightness, config, screen::actuateBrightnessIfOn)
        haSiteMetadata = HaSiteMetadataClient(config)
        haAmbientLux = HaAmbientLuxSubscriber(
            scope = scope,
            config = config,
            onSample = { sample -> autoBright.submitHaLux(sample.lux) },
            onStatus = { status ->
                autoBright.setHaSourceAvailable(status.phase == HaAmbientSourcePhase.LIVE)
            },
        )
        screen.onWakeCompleted = {
            scope.launch(Dispatchers.Default) { autoBright.reapplyLatest() }
        }
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
        companionDataOperationState = CompanionDataOperationState.from(this)
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
        watchdog = WatchdogController(system, config)
        kiosk = KioskController(this, system, config, profile.appCanSu)
        kioskSettings = KioskSettingCoordinator(
            canEnable = kiosk::canEnablePersistentPolicy,
            actuate = kiosk::apply,
            persist = config::commitKioskLock,
        )
        // On-device unlock gesture (7 corner taps): persist OFF + clear the lock + tell HA — off the main
        // thread (the gesture fires on the overlay's touch listener; root + HiveMQ must not run there).
        kiosk.onUnlockRequested = {
            Thread {
                if (serviceStopping) return@Thread
                kioskSettings.apply(false) {
                    if (!serviceStopping) mqtt.publishKioskState()
                }
            }.apply { isDaemon = true; name = "kiosk-unlock" }.start()
        }
        // When taming disables a vendor home launcher (e.g. eWeLink), immediately re-assert the dashboard
        // as home and foreground it — otherwise the panel sits on our admin launcher until the 300s
        // watchdog returns to the dashboard (a ~5-minute gap on boot). Activation is deferred until the
        // predecessor service has left the process; tame() itself runs in the service scope off-main.
        tame = TameController(this) {
            system.applyLauncherHomePolicy(
                config.launcherPackage,
                config.dashboardPackage,
                config.haUrl.isNotBlank(),
            )
            system.launchHome(config.dashboardPackage)
        }
        // The navbar gets the CONFIGURED dashboard value (may be the "builtin" sentinel), never
        // dashboardTarget(): that resolves builtin to our own package name (for perf attribution), and
        // reloadDashboard(ownPackage) would take the foreign-app path — `am force-stop` on ourselves.
        navbar = NavbarController(
            this, system, volume, brightness, { config.launcherPackage }, { config.dashboardPackage },
            profile.appCanSu, profile.hasRecents,
            onBrightnessChanged = { level ->
                autoBright.noteExternalBrightness(level, BrightnessPreferenceOrigin.PANEL_CONTROLS)
            },
            onBrightnessApplied = { mqtt.publishScreenOn() },
            onVolumeChanged = { mqtt.publishVolume() },
        )
        touchSound = TouchSoundController(this, profile.touchClickGain)
        bootChime = BootChimeController(this, config)
        zigbee = ZigbeeController(profile)
        zigbeeHealth = ZigbeeHealthMonitor(
            configuredOn = { config.zigbeeRouterConfigured && config.zigbeeRouterEnabled },
            source = AndroidZigbeeGatewayHealthSource(profile.zigbeeGatewayDir, zigbee),
            onContain = {
                config.setZigbeeRouterEnabled(false)
                runCatching { mqtt.publishZigbeeRouterState() }
            },
            onSnapshot = { snapshot, _ ->
                runCatching { mqtt.publishZigbeeHealth(snapshot) }
            },
        )
        relay = RelayController(profile)
        cpu = CpuController(profile)
        adb = AdbController(config)
        power = PowerController(this)

        runtime = ServiceRuntimeOwner(
            initial = NetworkRuntime(
                buildMqtt(appliedNetworkConfiguration.runtime),
                buildMdns(appliedNetworkConfiguration.runtime),
            ),
            threadName = "ha-paneld-runtime",
            onError = { operation, error -> Log.e(TAG, "runtime $operation failed", error) },
            onRecoverySaturated = {
                if (!serviceStopping && !recoveryRestart.request()) {
                    Log.w(TAG, "runtime recovery restart is already scheduled")
                }
            },
            latestOperation = LatestOperationPolicy(
                name = "network-reconfigure",
                timeout = LatestOperationTimeoutPolicy(
                    budgetMs = NETWORK_RECONFIGURE_BUDGET_MS,
                    schedule = { task, delayMs -> mainHandler.postDelayed(task, delayMs) },
                    cancel = mainHandler::removeCallbacks,
                    onTimeout = ::onNetworkReconfigureTimeout,
                ),
                operation = { performNetworkReconfigure(this) },
            ),
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
            // The bridge is replaceable. If its command admission is already draining, retain the
            // persisted setting and replay it against the replacement before reconfigure completes.
            { k, v ->
                if (k == "kiosk_lock") {
                    SettingValue.parseBool(v)?.let { on ->
                        kioskSettings.apply(on) { mqtt.publishKioskState() }
                    } ?: false
                } else {
                    liveSettingAuthority.applyOrQueue(
                        key = k,
                        value = v,
                        previousValue = previousLiveSettingValue(k),
                    ) { key, value, previous -> applyLiveSetting(mqtt, key, value, previous) }
                }
            },
            // Controller-sourced setting values (their state isn't in the config namespace) so the
            // config form/schema/dashboard show live truth. Called on Ktor IO threads (su-safe).
            configLiveValues = ::currentConfigLiveValues,
            managementProjection = ::managementProjection,
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
            radioStatus = { if (profile.zigbeeGatewayDir != null) zigbeeHealth.snapshot() else null },
            onZigbeeJoinRetry = { mqtt.requestZigbeeJoin() },
            // LAN ha-paneld peers over mDNS for the header panel switcher. Captures the `mdns` FIELD (not a
            // snapshot) so it follows reconfigure()'s reassignment; browsePeers null-guards the swap window.
            peers = { mdns.browsePeers() },
            rendererPreparation = rendererPreparation,
            entityLearning = entityLearning,
            autoBrightnessHttpApi = adaptiveBrightnessHttpApi(),
            companionDataOperationState = companionDataOperationState,
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
            provisioningReader = provisioningReader,
            provisioningActivation = {
                val status = profileRegistry.status()
                ProvisioningActivationSnapshot(
                    phase = status.activation.phase,
                    activeRef = status.active?.ref,
                    generation = status.activation.generation,
                )
            },
        )
        sensors.setRangedProximityListener {
            server.invalidateCapabilitySnapshot()
            runtime.observe()?.value?.mqtt?.notifyRangedProximityChanged()
        }
    }

    private fun buildMqtt(
        identity: NetworkRuntimeIdentity,
        stalePanelId: String? = null,
    ): MqttBridge {
        val credentials = identity.mqttCredentials()
        return MqttBridge(
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
            autoBright,
            onAutoBrightnessConfigChanged = { refreshAdaptiveBrightnessInputs() },
            configUrl = { localIpv4()?.let { "http://$it:${identity.httpPort}/" } },
            // When no broker is configured, find HA on the LAN via mDNS and default to its :1883.
            discoverHaIp = { mdns.discoverHaIp() },
            // HA's advertised base URL (from zeroconf) for the "Open in HA" device link.
            discoverHaUrl = { broker -> mdns.discoverHaBaseUrl(broker) },
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
            onDirectKioskSetting = { on ->
                kioskSettings.apply(on)
            },
            zigbeeHealth = zigbeeHealth::snapshot,
            onZigbeeExplicitRetry = zigbeeHealth::explicitRetry,
            stalePanelId = stalePanelId,
            profileIdentity = activeProfileIdentity,
            profileButtonEventTypes = profile.evdevButtons.mapTo(linkedSetOf()) { it.eventType },
            runtimePanelId = identity.panelId,
            runtimeFriendlyName = identity.friendlyName,
            runtimeBroker = credentials.broker,
            runtimeMqttUser = credentials.user,
            runtimeMqttPassword = credentials.password,
            wifiDiagnostics = wifiDiagnostics::snapshot,
            rangedProximityEligibility = sensors::hasRangedProximity,
        )
    }

    private fun buildMdns(identity: NetworkRuntimeIdentity): MdnsAdvertiser = MdnsAdvertiser(
        this,
        config,
        runtimePanelId = identity.panelId,
        runtimeFriendlyName = identity.friendlyName,
        runtimeHttpPort = identity.httpPort,
    )

    /** Make the journalled desired value immediately visible/durable, but pass the previous value through
     * to transition-sensitive handlers. Transient HA-fed inputs intentionally have no persisted value. */
    private fun previousLiveSettingValue(key: String): String? = SettingsRegistry.spec(key)?.let(config::getRaw)

    private fun applyLiveSetting(
        bridge: MqttBridge,
        key: String,
        value: String,
        previousValue: String? = null,
    ): LiveSettingApplyResult {
        val spec = SettingsRegistry.spec(key) ?: return LiveSettingApplyResult.FAILED
        return durableLiveSettingApply(
            previousValue = previousValue ?: config.getRaw(spec),
            transient = spec.transient,
            // Navbar persistence is owned by its acknowledged command handler: committing the desired
            // mode here would report durable success before asynchronous overlay actuation finishes.
            actuationOwnsPersistence = key == "navbar_mode" || key == "kiosk_lock",
            persist = { config.commitRaw(spec, value) },
            apply = { previous -> bridge.applySetting(key, value, previous) },
        )
    }

    /**
     * Apply config the HTTP page has already written to [config], then rebuild MQTT + mDNS. A renamed
     * panel's discovery cleanup is handed to the replacement bridge so it runs on a live connection.
     */
    private fun reconfigure() {
        launcherHomePolicyGeneration.incrementAndGet()
        launcherHomeFailedGeneration.set(Long.MIN_VALUE)
        scope.launch(Dispatchers.IO) {
            runCatching {
                system.applyLauncherHomePolicy(
                    config.launcherPackage,
                    config.dashboardPackage,
                    config.haUrl.isNotBlank(),
                )
            }.onFailure { Log.w(TAG, "launcher HOME policy apply failed", it) }
        }
        when (runtime.requestLatest()) {
            ServiceRuntimeOwner.LatestAdmission.COALESCED ->
                FeatureCosts.registry.recordCoalesced(FeatureCostOperation.NETWORK_RECONFIGURE)
            ServiceRuntimeOwner.LatestAdmission.CLOSED ->
                FeatureCosts.registry.recordDropped(FeatureCostOperation.NETWORK_RECONFIGURE)
            ServiceRuntimeOwner.LatestAdmission.ACCEPTED -> Unit
        }
        FeatureCosts.registry.setBacklog(
            FeatureCostOperation.NETWORK_RECONFIGURE,
            runtime.pendingLatestCount(),
        )
    }

    /** Borrow the last management proof and the existing never-blank cadence. One failed periodic
     * mutation stays suppressed until the next explicit config generation; startup/config apply already
     * owns an immediate unrestricted attempt. */
    private fun repairAdminHomeFromCapturedRoute() {
        val generation = launcherHomePolicyGeneration.get()
        val route = server.lastPrivilegeObservation()?.let(::preferredAdminHomeRepairRoute)
        if (!shouldAttemptPeriodicAdminHomeRepair(
                serviceStopping = serviceStopping,
                adminHomeSelected = system.isAdminLauncherSelection(config.launcherPackage),
                admittedRoute = route,
                policyGeneration = generation,
                failedGeneration = launcherHomeFailedGeneration.get(),
            )
        ) return
        val repaired = runCatching { system.ensureAdminLauncherHome(checkNotNull(route)) }
            .onFailure { Log.w(TAG, "could not reassert Panel admin as HOME", it) }
            .getOrDefault(false)
        if (!repaired && launcherHomePolicyGeneration.get() == generation) {
            launcherHomeFailedGeneration.set(generation)
        }
    }

    private fun registerBrightnessPreferenceObserver() {
        lastObservedCommandedBrightness = brightness.getCommanded()
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                val level = runCatching {
                    Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                }.getOrNull() ?: return
                val prior = lastObservedCommandedBrightness
                lastObservedCommandedBrightness = level
                if (brightness.consumeOwnedSettingChange(level) || screen.observedDark() == true) return
                autoBright.noteExternalBrightness(level, BrightnessPreferenceOrigin.ANDROID_SYSTEM, prior)
            }
        }
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
            false,
            observer,
        )
        brightnessObserver = observer
    }

    private fun refreshAdaptiveBrightnessInputs(restartSource: Boolean = true) {
        if (shouldDisableAutoBrightnessForMissingSource(
                enabled = config.autoBrightness,
                haEntity = config.autoBrightnessHaEntity,
                hasLocalSensor = sensors.hasLight(),
            )
        ) {
            Log.w(TAG, "Disabling auto-brightness: no local light sensor or HA illuminance source is configured")
            config.setAutoBrightness(false)
        }
        val enabled = config.autoBrightness
        val selected = adaptiveHaSource(enabled, config.autoBrightnessHaEntity)
        val candidateIdentity = config.haUrl.trim()
        if (candidateIdentity != haCandidateIdentity) {
            haCandidateIdentity = candidateIdentity
            haAmbientLux.invalidateCandidates()
        }
        autoBright.setHaSourceAvailable(false)
        if (restartSource) haAmbientLux.setSource(null)
        haAmbientLux.setSource(selected)
        val generation = adaptiveSiteGeneration.incrementAndGet()
        if (enabled) {
            scope.launch {
                val site = haSiteMetadata.fetch().metadata
                if (site != null && !serviceStopping && adaptiveSiteGeneration.get() == generation) {
                    autoBright.updateSite(site.latitude, site.longitude, site.timeZone)
                }
            }
        }
        autoBright.reapplyLatest()
    }

    private fun adaptiveBrightnessHttpApi(): AutoBrightnessHttpApi = object : AutoBrightnessHttpApi {
        override fun statusJson(): String {
            val runtime = autoBright.status()
            val haStatus = haAmbientLux.latestStatus()
            val selected = config.autoBrightnessHaEntity.trim().takeIf(String::isNotBlank)
            val candidate = selected?.let { id ->
                haAmbientLux.latestCandidates().items.firstOrNull { it.entityId == id }
            }
            return JSONObject().apply {
                put("available", true)
                put("state", if (runtime.enabled) "enabled" else "disabled")
                put("mode", runtime.mode?.name?.lowercase() ?: "waiting")
                put("paused", runtime.manualPreference.active)
                put("preferenceActive", runtime.manualPreference.active)
                put("localSourcePresent", sensors.hasLight())
                put("sourceLabel", candidate?.friendlyName ?: selected ?: if (sensors.hasLight()) {
                    "Panel ambient sensor"
                } else {
                    "No ambient light source"
                })
                put("entityId", selected ?: JSONObject.NULL)
                put("sourceAvailable", runtime.sourceAvailable)
                put("latestLux", runtime.latestLux ?: JSONObject.NULL)
                put("expectedLux", runtime.expectedLux ?: JSONObject.NULL)
                put("automaticTarget", runtime.automaticTarget ?: JSONObject.NULL)
                put("appliedTarget", runtime.appliedTarget ?: JSONObject.NULL)
                put("minimumPercent", config.autoBrightnessMinimumPercent)
                put(
                    "minimumBrightness",
                    io.github.maxlyth.hapaneld.control.AdaptiveLuxCurve.percentToBrightness(
                        config.autoBrightnessMinimumPercent,
                    ),
                )
                put("autoAuthority", runtime.manualPreference.autoAuthority)
                put("manualRemainingMs", runtime.manualPreference.remainingMs)
                if (selected != null) put("detail", haStatus.detail)
            }.toString()
        }

        override fun historyJson(hours: Int, sensitivity: Int?, minimumPercent: Int?): String {
            val nowEpochMinute = System.currentTimeMillis() / 60_000L
            val cutoffMinute = nowEpochMinute - hours * 60L
            val previewMinimum = minimumPercent ?: config.autoBrightnessMinimumPercent
            val points = autoBright.chartPoints(
                sensitivity ?: config.autoBrightnessSensitivity,
                previewMinimum,
            )
                .filter { it.epochMinute >= cutoffMinute }
            val zone = autoBright.timeZone()
            val today = Calendar.getInstance(zone).apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val dayMetadata = mutableMapOf<Int, Pair<String, Int>>()
            return JSONObject().apply {
                put("available", true)
                put("hours", hours)
                put("bucket_minutes", 5)
                put("minimum_percent", previewMinimum)
                put("minimum_brightness", io.github.maxlyth.hapaneld.control.AdaptiveLuxCurve.percentToBrightness(previewMinimum))
                put("now_epoch_minute", nowEpochMinute)
                put("time_zone", zone.id)
                put("points", JSONArray().apply {
                    points.forEach { point -> put(JSONObject().apply {
                        val local = Calendar.getInstance(zone).apply {
                            timeInMillis = point.epochMinute * 60_000L
                        }
                        val dayKey = local.get(Calendar.YEAR) * 400 + local.get(Calendar.DAY_OF_YEAR)
                        val (localDay, dayAge) = dayMetadata.getOrPut(dayKey) {
                            val day = String.format(
                                Locale.US,
                                "%04d-%02d-%02d",
                                local.get(Calendar.YEAR),
                                local.get(Calendar.MONTH) + 1,
                                local.get(Calendar.DAY_OF_MONTH),
                            )
                            val localMidnight = local.clone() as Calendar
                            localMidnight.set(Calendar.HOUR_OF_DAY, 0)
                            localMidnight.set(Calendar.MINUTE, 0)
                            localMidnight.set(Calendar.SECOND, 0)
                            localMidnight.set(Calendar.MILLISECOND, 0)
                            var age = 0
                            while (localMidnight.before(today) && age <= 7) {
                                localMidnight.add(Calendar.DAY_OF_MONTH, 1)
                                age += 1
                            }
                            day to age
                        }
                        put("epochMinute", point.epochMinute)
                        put("localDay", localDay)
                        put("minuteOfDay", local.get(Calendar.HOUR_OF_DAY) * 60 + local.get(Calendar.MINUTE))
                        put("dayAge", dayAge)
                        put("observedMeanLux", point.observedMeanLux)
                        put("minLux", point.minLux)
                        put("maxLux", point.maxLux)
                        put("expectedLux", point.expectedLux)
                        put("proposedBrightness", point.proposedBrightness)
                    }) }
                })
            }.toString()
        }

        override fun haSourcesJson(query: String, limit: Int): String {
            haAmbientLux.refreshCandidates()
            val projection = haAmbientLux.latestCandidates()
            val needle = query.lowercase()
            val items = projection.items.asSequence()
                .filter { needle.isBlank() || it.entityId.lowercase().contains(needle) || it.friendlyName.lowercase().contains(needle) }
                .take(limit)
            return JSONObject().apply {
                put("available", projection.error.isBlank())
                put("refreshing", haAmbientLux.candidateRefreshInFlight())
                if (projection.error.isNotBlank()) put("detail", projection.error)
                put("items", JSONArray().apply { items.forEach { source -> put(JSONObject().apply {
                    put("entityId", source.entityId)
                    put("friendlyName", source.friendlyName)
                    put("unit", source.unit)
                    put("currentLux", source.currentLux ?: JSONObject.NULL)
                    put("available", source.available)
                    put("lastUpdatedEpochMs", source.lastUpdatedEpochMs ?: JSONObject.NULL)
                }) } })
            }.toString()
        }

        override suspend fun validateHaSource(entityId: String): AutoBrightnessHttpValidation =
            when (val result = haAmbientLux.validateSource(entityId)) {
                is HaAmbientSourceValidation.Ready -> AutoBrightnessHttpValidation(
                    AutoBrightnessHttpAction.ok(),
                    result.authOwner,
                )
                is HaAmbientSourceValidation.Rejected -> AutoBrightnessHttpValidation(
                    AutoBrightnessHttpAction(
                        statusCode = result.statusCode,
                        json = JSONObject().put("ok", false).put("error", result.error)
                            .put("message", result.message).toString(),
                    ),
                )
            }

        override suspend fun selectHaSource(entityId: String?): AutoBrightnessHttpAction {
            if (entityId != null) {
                val previous = config.autoBrightnessHaEntity
                val validation = validateHaSource(entityId)
                if (validation.action.statusCode !in 200..299) return validation.action
                val committed = config.synchronizedTransaction {
                    if (validation.authOwner == null || config.haAuthSnapshot().stableOwner() != validation.authOwner ||
                        config.autoBrightnessHaEntity != previous
                    ) false else config.applyBatch { config.setAutoBrightnessHaEntity(entityId) }
                }
                if (!committed) return AutoBrightnessHttpAction(
                    409,
                    """{"ok":false,"error":"ha-source-validation-stale","message":"Home Assistant settings changed during the check. Try again."}""",
                )
            } else {
                config.setAutoBrightnessHaEntity("")
            }
            refreshAdaptiveBrightnessInputs()
            autoBright.reapplyLatest()
            return AutoBrightnessHttpAction.ok()
        }

        override fun resetHistory(): AutoBrightnessHttpAction {
            autoBright.resetHistory()
            return AutoBrightnessHttpAction.ok()
        }

        override fun resumeFullAuto(): AutoBrightnessHttpAction {
            autoBright.resumeFullAuto()
            return AutoBrightnessHttpAction.ok()
        }
    }

    private fun performNetworkReconfigure(
        mutation: ServiceRuntimeOwner<NetworkRuntime>.LatestMutation,
    ) {
        val executionDeadline = mutation.deadline
        if (executionDeadline.remainingMs() <= 0L) return
        // A committed configuration application starts a fresh comparison epoch while process-lifetime
        // startup evidence remains available in the same fixed-cardinality diagnostics projection.
        FeatureCosts.beginEpoch()
        FeatureCosts.registry.setBacklog(
            FeatureCostOperation.NETWORK_RECONFIGURE,
            runtime.pendingLatestCount(),
        )
        var cost: io.github.maxlyth.hapaneld.metrics.FeatureCostRegistry.Span? = null
        try {
            val desired = currentNetworkConfigurationSnapshot()
            val replacementRequired =
                desired.runtime != appliedNetworkConfiguration.runtime || !mutation.isRunning
            val operation = if (replacementRequired) FeatureCostOperation.NETWORK_RECONFIGURE
                else FeatureCostOperation.CONFIG_LIVE_REFRESH
            val operationCost = FeatureCosts.registry.span(operation)
                .work(units = if (replacementRequired) 1 else 0)
            cost = operationCost
            refreshAdaptiveBrightnessInputs()
            if (!replacementRequired) {
                val active = mutation.current.mqtt
                replayThenRefreshLiveConfiguration(
                    replay = {
                        liveSettingAuthority.replay { key, value, previous ->
                            applyLiveSetting(active, key, value, previous)
                        }
                    },
                    refresh = {
                        refreshLiveConfiguration(
                            active,
                            desired,
                            evaluateMqttEffects = true,
                            cost = operationCost,
                        )
                    },
                )
                return
            }
            val completed = mutation.replace(
                retire = { previous ->
                    // The replacement uses the same availability topic unless the panel id changed. Do not race
                    // its retained online with a late offline from the retiring client. A genuinely different
                    // broker still needs an explicit offline because the replacement cannot clean that broker.
                    val publishOffline = mqttReconfigurePublishesOffline(
                        previous.mqtt.panelId,
                        desired.runtime.panelId,
                        previous.mqtt.configuredBroker,
                        desired.runtime.broker,
                    )
                    // Start both independent retirement fences before awaiting either result. They share one
                    // monotonic budget; no locally reset phase timeout can accumulate beyond it.
                    val mdnsRetirement = previous.mdns.retire(executionDeadline)
                    val mqttRetirement = previous.mqtt.stop(
                        deadline = executionDeadline,
                        publishOffline = publishOffline,
                        clearDiscovery = publishOffline,
                    )
                    val mqttOwnersDrained = mqttRetirement.ownersDrained.awaitTrue(executionDeadline)
                    val mqttFinalized = mqttRetirement.finalization.awaitSuccessful(executionDeadline)
                    val mdnsStopped = mdnsRetirement.awaitTrue(executionDeadline)
                    val withinDeadline = executionDeadline.remainingMs() > 0L
                    check(mqttOwnersDrained && mqttFinalized && mdnsStopped && withinDeadline) {
                        "retiring network runtime did not prove cleanup " +
                            "(mqttOwners=$mqttOwnersDrained mqttFinal=$mqttFinalized " +
                            "mdns=$mdnsStopped withinDeadline=$withinDeadline)"
                    }
                },
                build = { previous ->
                    val stalePanelId = previous.mqtt.panelId.takeIf { it != desired.runtime.panelId }
                    NetworkRuntime(
                        buildMqtt(desired.runtime, stalePanelId),
                        buildMdns(desired.runtime),
                    )
                },
                start = { replacement ->
                    startReconfiguredNetworkRuntime(
                        startMdns = replacement.mdns::start,
                        resolveHaLink = replacement.mqtt::maybeResolveHaLink,
                        startMqtt = replacement.mqtt::start,
                    )
                },
                complete = { replacement ->
                    liveSettingAuthority.replay { key, value, previous ->
                        applyLiveSetting(replacement.mqtt, key, value, previous)
                    }
                    // Publish only the immutable configuration this concrete replacement consumed. A newer
                    // config can arrive while start() is blocked; retaining this older snapshot makes the
                    // conflated rerun replace/re-project again instead of mistaking that newer config for live.
                    refreshLiveConfiguration(
                        replacement.mqtt,
                        desired,
                        evaluateMqttEffects = false,
                        cost = operationCost,
                    )
                    Log.i(
                        TAG,
                        "reconfigured: panel=${desired.runtime.panelId} " +
                            "broker=${desired.runtime.broker.ifEmpty { "(disabled)" }}",
                    )
                },
            )
            if (!completed) {
                operationCost.outcome(FeatureCostOutcome.REJECTED)
                if (executionDeadline.remainingMs() > 0L) requestNetworkRecovery()
            }
        } catch (e: InterruptedException) {
            cost?.outcome(FeatureCostOutcome.CANCELLED)
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            cost?.outcome(FeatureCostOutcome.FAILURE)
            Log.w(TAG, "network reconfigure failed", e)
            if (executionDeadline.remainingMs() > 0L) requestNetworkRecovery()
        } finally {
            if (executionDeadline.remainingMs() <= 0L && !Thread.currentThread().isInterrupted) {
                cost?.outcome(FeatureCostOutcome.REJECTED)
            }
            cost?.close()
        }
    }

    private fun requestNetworkRecovery() {
        if (!serviceStopping) recoveryRestart.request()
    }

    private fun onNetworkReconfigureTimeout() {
        FeatureCosts.registry.setBacklog(
            FeatureCostOperation.NETWORK_RECONFIGURE,
            runtime.pendingLatestCount(),
        )
        requestNetworkRecovery()
    }

    private fun currentNetworkIdentity(): NetworkRuntimeIdentity {
        val credentials = config.mqttCredentialsSnapshot()
        return NetworkRuntimeIdentity(
            config.panelId,
            config.friendlyName,
            config.httpPort,
            credentials.broker.trim(),
            credentials.user,
            credentials.password,
        )
    }

    private fun currentNetworkConfigurationSnapshot(): NetworkConfigurationSnapshot =
        config.synchronizedTransaction {
            NetworkConfigurationSnapshot(
                runtime = currentNetworkIdentity(),
                projection = currentMqttProjection(),
                haLink = currentHaLinkIdentity(),
            )
        }

    private fun currentMqttProjection(): MqttProjectionIdentity = MqttProjectionIdentity(
        manufacturer = config.manufacturer,
        model = config.model,
        exposures = SettingsRegistry.SPECS.asSequence()
            .filter { it.ha != null }
            .map { it.key to config.haExposed(it.key, it.haExposedByDefault) }
            .toList(),
    )

    private fun currentHaLinkIdentity(): HaLinkIdentity = HaLinkIdentity(
        config.haUrl.trim().trimEnd('/'),
        config.haToken,
        config.haRefreshToken,
        config.haTokenExpiry,
        config.haClientId,
    )

    private fun refreshLiveConfiguration(
        bridge: MqttBridge,
        desired: NetworkConfigurationSnapshot,
        evaluateMqttEffects: Boolean,
        cost: io.github.maxlyth.hapaneld.metrics.FeatureCostRegistry.Span,
    ) {
        if (evaluateMqttEffects) {
            val effects = configRefreshEffects(
                appliedNetworkConfiguration.projection,
                desired.projection,
                appliedNetworkConfiguration.haLink,
                desired.haLink,
            )
            bridge.refreshConfiguration(effects.reannounceMqtt, effects.resolveHaLink)
            cost.work(
                units = (if (effects.reannounceMqtt) 1L else 0L) +
                    (if (effects.resolveHaLink) 1L else 0L),
            )
        }
        appliedNetworkConfiguration = desired
        // These owners already change-gate internally; they do not require broker/mDNS replacement.
        runCatching { logShipper.reconfigure() }
        runCatching { power.apply(config.keepAwake) }
        io.github.maxlyth.hapaneld.http.PerfReader.dashboardPkg = dashboardTarget()
        io.github.maxlyth.hapaneld.http.PerfReader.builtinActive =
            config.dashboardPackage == SystemController.BUILTIN_DASHBOARD
    }

    /** Controller reads shared by the dashboard facts, live values and capability projection. */
    private data class ManagementControllerObservation(
        val touchSoundEnabled: Boolean,
        val cpuTier: String?,
        val cpuGovernorsAvailable: Boolean,
        val networkAdbPersisted: Boolean,
        val networkAdbActive: Boolean,
        val zigbee: ZigbeeObservation,
        val relayCount: Int,
        val buttonLedCount: Int,
    )

    private fun observeManagementControllers(privilege: PrivilegedRouteObservation): ManagementControllerObservation {
        val persistedAdb = adb.isPersisted()
        return ManagementControllerObservation(
            touchSoundEnabled = touchSound.isEnabled(),
            cpuTier = cpu.currentTier(allowRootFallback = privilege.directSuReady),
            cpuGovernorsAvailable = cpu.available(allowRootFallback = privilege.directSuReady),
            networkAdbPersisted = persistedAdb,
            // A persisted ha-paneld intent already determines the displayed state; avoid five property
            // reads merely to rediscover that its boot reassertion is owned here.
            networkAdbActive = !persistedAdb && adb.isActive(allowRootCrossCheck = privilege.directSuReady),
            zigbee = zigbee.observe(includeRole = true, directSuReady = privilege.directSuReady),
            relayCount = relay.count(allowRootProbe = privilege.directSuReady),
            buttonLedCount = relay.ledCount(),
        )
    }

    private fun projectLiveValues(cpuTier: String?, networkAdbPersisted: Boolean, touchEnabled: Boolean): Map<String, String> =
        mapOf(
            "touch_sound" to touchEnabled.toString(),
            "cpu_governor" to (cpuTier ?: "Auto"),
            "network_adb" to networkAdbPersisted.toString(),
            "zigbee_router" to config.zigbeeRouterEnabled.toString(),
        )

    /** Fresh non-transient controller values for config export and concurrency checks. CPU governor is
     * reboot-transient and projectConfigSnapshot omits it, so probing it here would be pure wasted work. */
    private fun currentConfigLiveValues(): Map<String, String> = mapOf(
        "touch_sound" to touchSound.isEnabled().toString(),
        "network_adb" to adb.isPersisted().toString(),
        "zigbee_router" to config.zigbeeRouterEnabled.toString(),
    )

    private fun managementProjection(privilege: PrivilegedRouteObservation): ManagementProjection {
        val controllers = observeManagementControllers(privilege)
        val diagnostic = DiagReader.capabilities(this, profile, privilege)
        return ManagementProjection(
            facts = panelInfo(controllers, diagnostic.rgbLedReady),
            live = projectLiveValues(
                cpuTier = controllers.cpuTier,
                networkAdbPersisted = controllers.networkAdbPersisted,
                touchEnabled = controllers.touchSoundEnabled,
            ),
            capabilities = capabilitiesSnapshot(privilege, controllers),
            capabilityRows = diagnostic.rows,
        )
    }

    /** Ordered facts for the info page (`GET /`). */
    private fun panelInfo(
        controllers: ManagementControllerObservation,
        rgbLedReady: Boolean,
    ): Map<String, String> {
        // activeBroker reflects auto-discovery (tcp://<ha-ip>:1883) when no broker is configured.
        val broker = mqtt.activeBroker.ifBlank { config.mqttBroker }
        val host = broker.substringAfter("://").substringBefore(":").ifBlank { "?" }
        val auto = config.mqttBroker.isBlank() && mqtt.activeBroker.isNotBlank()
        val mqttStatus = when (mqtt.state) {
            "connected" -> "$host · connected" + (if (auto) " (auto)" else "")
            "announcing" -> "$host · connected, announcing…" + (if (auto) " (auto)" else "")
            "auth-retrying" -> "$host · auth retrying…"
            "auth-failed" -> "$host · reachable, auth rejected — check username/password"
            "unreachable" -> "$host · unreachable"
            "connecting" -> "$host · connecting…"
            "config-error" -> "$host · invalid or unsupported broker URL"
            else -> "disabled"
        }
        val pv = SystemProps.get("ro.product.version")
        val appDatabase = runCatching { PanelInfo.databaseSummary(entityLearning.databaseUsage()) }.getOrNull()
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
            "Security mode" to if (config.hardenedSecurityEnabled) {
                "Hardened · high-impact remote actions need physical on-panel approval"
            } else {
                "Relaxed"
            },
            // Wakelock/Wi-Fi-lock intent vs reality — a panel that should be keep-awake but isn't
            // holding the lock is a strong hint for stalled-idle-connection reports.
            "Keep awake" to if (config.keepAwake) (if (power.isHeld()) "on · wakelock held" else "on · wakelock NOT held") else "off",
            "Kiosk lock" to if (config.kioskLock) "on" else "off",
            "mDNS" to "${config.panelId} ${Config.MDNS_SERVICE_TYPE}",
            "Platform" to profile.displayName,
            "SoC" to (profile.soc?.displayText() ?: profile.socClass),
            "Model" to profile.panelModelLabel(pv),
            "LED" to ledLabel(rgbLedReady),
            "Light sensor" to sensorRow(sensors.hasLight(), profile.lightTech, sensors.lightDesc()),
            "Proximity" to sensorRow(sensors.hasProximity(), profile.proximityTech, sensors.proximityDesc()),
            // a11y service = software back/recents nav, NOT physical buttons (NSPanel Pro has none).
            "Nav actions (a11y)" to yesNo(accessibilityEnabled()),
            // Soft navbar overlay mode + whether the overlay can actually be drawn (SYSTEM_ALERT_WINDOW).
            "Navbar" to (config.navbarMode + if (config.navbarMode != "Off" && !canDrawOverlays()) " · no overlay permission" else ""),
            "Zigbee" to controllers.zigbee.status,
            "Relays" to controllers.relayCount.let { if (it > 0) it.toString() else "none" },
            "CPU profile" to (controllers.cpuTier ?: "n/a"),
            "Network ADB" to when {
                controllers.networkAdbPersisted -> "persistent (5555) · re-asserted by ha-paneld at boot"
                controllers.networkAdbActive -> "active (5555) · external — not persisted by ha-paneld"
                else -> "off"
            },
            "Log shipping" to logShipper.statusText(),
            "Audio playback" to audio.snapshot().statusText(),
        )
        appDatabase?.let { extras["App database"] = it }
        if (pv.isNotEmpty()) extras["Product version"] = pv
        // Recent "changed outside MQTT" events (brightness/volume/backlight/governor) — shown only when
        // something has actually synced, so it doesn't clutter a steady panel. Flows to /diag too.
        mqtt.recentSyncEvents().takeIf { it.isNotEmpty() }?.let { extras["Local-state sync"] = it.joinToString(" · ") }
        extras["State convergence"] = mqtt.convergenceStatus()
        return PanelInfo.collect(this, extras, profile)
    }

    private fun ledLabel(rgbLedReady: Boolean): String {
        if (!rgbLedReady) return "none"
        return when {
        led is Rk3576LedController -> "Rockchip /dev/ledjni (RGB)"
        led is SocketLedController && profile.ledMechanism == LedMechanism.RK3576_IOCTL_DAEMON -> "Rockchip /dev/ledjni helper daemon (RGB)"
        led is SocketLedController && profile.ledMechanism == LedMechanism.SYSFS_DAEMON -> "sysfs helper daemon (RGB)"
        led is SocketLedController -> "helper daemon (RGB)"
        led.colorCapable() -> "RGB"
        else -> "brightness"
        }
    }

    private fun yesNo(b: Boolean) = if (b) "yes" else "no"

    /** "no", or "yes" with any declared technology + runtime value-type/range appended ("yes · Infrared ·
     *  Binary · near/far (0 / 5 cm)"). */
    private fun sensorRow(present: Boolean, tech: String?, desc: String?): String =
        if (!present) "no" else "yes" + listOfNotNull(tech, desc).joinToString("") { " · $it" }

    // MQTT recovery runs each heartbeat. Preserve the established positive-sticky/backoff contract so
    // a declared gateway is discovered after late root startup without spawning a root process forever.
    private val zigbeePresence = SuccessStickyProbe(
        probe = {
            zigbee.observe(includeRole = false)
                .takeIf { it.probeSucceeded && it.present }
                ?.let { true }
        },
        initialBackoffMs = 5_000L,
        maxBackoffMs = 300_000L,
    )

    /** This panel's capability snapshot for the settings registry's availableWhen gates
     *  (the Configure form/schema + the dashboard's read-only values card). */
    private fun capabilitiesSnapshot(): Capabilities {
        val privilege = observeTypedShellCapability(
            directSuProbe = Su::available,
            helperRootProbe = HelperClient::available,
            shizukuSnapshot = ShizukuBridge::snapshot,
        )
        return capabilitiesSnapshot(
            directSuReady = privilege.directSuReady,
            shizukuReady = privilege.shizuku.ready,
            typedShellControlReady = privilege.typedShellControlReady,
            controllers = null,
        )
    }

    private fun capabilitiesSnapshot(
        privilege: PrivilegedRouteObservation,
        controllers: ManagementControllerObservation,
    ): Capabilities = capabilitiesSnapshot(
        directSuReady = privilege.directSuReady,
        shizukuReady = privilege.shizuku.ready,
        typedShellControlReady = privilege.typedShellControlReady,
        controllers = controllers,
    )

    private fun capabilitiesSnapshot(
        directSuReady: Boolean,
        shizukuReady: Boolean,
        typedShellControlReady: Boolean,
        controllers: ManagementControllerObservation?,
    ): Capabilities {
        val cost = FeatureCosts.registry.span(FeatureCostOperation.CAPABILITY_SNAPSHOT)
        return try {
            Capabilities(
                hasProximity = sensors.hasProximity(),
                hasRangedProximity = sensors.hasRangedProximity(),
                hasLight = sensors.hasLight(),
                hasTemperature = sensors.hasTemperature(),
                hasHumidity = sensors.hasHumidity(),
                hasWifi = packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI),
                hasWifiSsid = packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI) &&
                    wifiDiagnostics.ssidRouteAvailable(directSuReady),
                hasCht8305 = profile.hasCht8305,
                appCanSu = profile.appCanSu,
                hasRecents = profile.hasRecents,
                cpuGovernors = controllers?.cpuGovernorsAvailable ?: cpu.available(),
                // AdbController.available() is exactly Su.available(); reuse this snapshot's one root
                // authority probe rather than opening another shell transaction.
                networkAdb = directSuReady,
                zigbeePresent = controllers?.let {
                    zigbeeCapabilityPresent(
                        declaredGateway = profile.zigbeeGatewayDir != null,
                        observation = it.zigbee,
                    )
                } ?: (profile.zigbeeGatewayDir != null && zigbeePresence.get() == true),
                relays = controllers?.relayCount ?: relay.count(),
                buttonLeds = controllers?.buttonLedCount ?: relay.ledCount(),
                hasSystemDarkMode = Build.VERSION.SDK_INT >= 29,   // Android 10+ has the system dark/light setting
                companionInstalled = UpdateChecker.COMPANION_PKGS.any {
                    runCatching { packageManager.getPackageInfo(it, 0) }.isSuccess
                },
                webViewManaged = profile.recommendedWebView != null,
                shizukuReady = shizukuReady,
                canInstallVerifiedApps = typedShellControlReady,
                canCaptureAndInput = typedShellControlReady,
                canSetDisplay = typedShellControlReady,
            ).also { cost.work(units = 1) }
        } catch (e: Exception) {
            cost.outcome(FeatureCostOutcome.FAILURE)
            throw e
        } finally {
            cost.close()
        }
    }

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
            requestSafeProcessBoundary("binding the $verb WebView provider")
            return
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
        // Start subsystems once. Android re-delivers onStartCommand on every startForegroundService()
        // re-issue and on START_STICKY re-create; re-running this block would call server.start() again,
        // binding a second Ktor server on :8888 -> BindException crashes the process (and would also
        // double-start mqtt/mdns/sensors). started is reset in onDestroy so a genuine restart re-inits.
        if (started) return START_STICKY
        startForegroundCompat("Starting…")
        started = true
        val startupActivationGeneration = profileActivationGeneration
        val startup = runtime.start runtimeStart@{ activeRuntime ->
            // A prior START_STICKY instance may still be draining after its bounded main-thread wait. It
            // never releases this in-process fence: completed teardown exits the process, guaranteeing
            // that no old hardware owner can overlap the replacement generation.
            restartLease.awaitPredecessor()
            if (serviceStopping) return@runtimeStart

            // Everything below can start work, write hardware state, attach a process-global owner, or
            // create an overlay. Keep all of it behind the predecessor fence, not merely HTTP/MQTT start.
            reconcileHelperInstallStaging()
            registerBrightnessPreferenceObserver()
            refreshAdaptiveBrightnessInputs(restartSource = false)
            autoBright.activate()
            brightness.applyPreventIdleDim(config.preventIdleDim, config)
            EntityLearningRuntime.attach(entityLearning)
            // Kiosk is config-owned across boots so every restart retains its deliberate unlocked
            // window. Never let a stale/in-flight HTTP journal bypass that delay or override a newer OFF.
            if (!liveSettingAuthority.discard("kiosk_lock")) {
                Log.w(TAG, "could not discard stale kiosk live-setting journal")
            }
            if (config.kioskLock && !kiosk.isPersistentPolicyEligible()) {
                Log.w(TAG, "kiosk lock is unavailable on this profile; clearing stale desired state")
                if (!config.commitKioskLock(false)) {
                    Log.w(TAG, "could not durably clear unsupported kiosk setting")
                }
            }
            val kioskRecoveredAtStartup = kiosk.recoverPersistentState(config.kioskLock)
            if (!kioskRecoveredAtStartup) {
                Log.w(TAG, "pending kiosk platform state could not be recovered; continuing unlocked")
            }
            if (serviceStopping) return@runtimeStart
            // Re-apply at boot so a persisted enabled state loads the owned click sample and reconciles
            // Android's sound-effects setting for this process; touch sound never changes stream volume.
            if (touchSound.isEnabled()) touchSound.set(true)
            bootChime.applyPersisted()
            sensors.prepare()
            when (BundledHelperInstaller.ensureCurrent(this@PaneldService, profile.appCanSu)) {
                BundledHelperInstaller.Result.INSTALLED -> Log.i(TAG, "migrated bundled root helper for this release")
                BundledHelperInstaller.Result.FAILED ->
                    Log.w(TAG, "root helper migration failed; versioned helper features remain unavailable")
                BundledHelperInstaller.Result.ALREADY_CURRENT,
                BundledHelperInstaller.Result.SKIPPED -> Unit
            }
            val startupCompanionPackage = CompanionInstaller.installedPkg(this)
                ?: system.resolveDashboard(config.dashboardPackage)
            restoreCompanionLaunchSuppression(
                packageName = startupCompanionPackage,
                operationState = companionDataOperationState,
                operationStatus = HelperClient::companionOperationStatus,
                retain = { lease ->
                    scope.launch(Dispatchers.IO) {
                        retainCompanionLeaseUntilHelperIdle(
                            lease = lease,
                            operationState = companionDataOperationState,
                            operationStatus = HelperClient::companionOperationStatus,
                            pollMs = COMPANION_STARTUP_STATUS_POLL_MS,
                            afterRelease = {
                                if (!serviceStopping) {
                                    runCatching {
                                        rendererPreparation.reconcileStartup(
                                            ensureHome = { pkg, ready ->
                                                system.applyLauncherHomePolicy(config.launcherPackage, pkg, ready)
                                            },
                                            launchHome = system::launchHome,
                                        )
                                    }.onFailure {
                                        Log.w(TAG, "Companion-safe startup renderer retry failed", it)
                                    }
                                }
                            },
                        )
                    }
                },
            )
            // Learning resolves the HA instance identity through this runtime. Start mDNS before either
            // the initial learner sync or a borrowed renderer commit can notify the learner of a target.
            val rendererResult = prepareEntityLearningStartup(
                startMdns = activeRuntime.mdns::start,
                reconcileRenderer = {
                    // Re-assert the configured HOME and repair an interrupted switch to the built-in renderer
                    // before the HTTP surface can accept another configuration transaction. The durable retry
                    // condition is built-in + blank URL; borrowed connection and zoom commit atomically.
                    rendererPreparation.reconcileStartup(
                        ensureHome = { pkg, ready ->
                            system.applyLauncherHomePolicy(config.launcherPackage, pkg, ready)
                        },
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
            // Startup always reconciles both additions and removals from durable desired state against
            // the write-ahead overlay ownership markers. This completes work handed off by profile restart.
            server.requestTameReconcile()
            activeRuntime.mqtt.start()
            if (profile.zigbeeGatewayDir != null) zigbeeHealth.start()
            liveSettingAuthority.replay { key, value, previous ->
                applyLiveSetting(activeRuntime.mqtt, key, value, previous)
            }
            // Forward our own logcat to the configured aggregator (no-op unless a sink host is set).
            logShipper.start()
            // Restore the soft navbar to its persisted mode (no-op when Off / no overlay permission).
            navbar.apply(config.navbarMode)
            // Start the app watchdog if enabled (off by default; self-heals a dead/abandoned dashboard).
            watchdog.apply(config.watchdogEnabled)
            // Experimental kiosk lock: a reboot CLEARS the runtime lock (by design — the anti-brick net), so
            // re-assert it after a delay if it was enabled. The delay leaves an unlocked window each boot so
            // an admin is never stranded; skipped if it was turned off (corner gesture / :8888 / HA) meanwhile.
            if (config.kioskLock || !kioskRecoveredAtStartup) scheduleKioskReassert()
            // Boot re-assert of network adb — some firmwares strip persist.adb.tcp.port at boot, so
            // re-apply it when ha-paneld is persisting it (no-op otherwise). See AdbController.reassert.
            runCatching { adb.reassert() }
            sensors.start(
                onLux = { lux ->
                    submitIlluminanceIfExposed(
                        exposed = config.haExposed("illuminance", true),
                        lux = lux,
                        submit = lightMqttPublisher::submit,
                    )
                },
                onLuxRaw = autoBright::submitLux,
                onProximity = { near, level, reportMask ->
                    mqtt.publishProximity(near, level, reportMask)
                },
                onGesture = gesture@{
                    if (!config.wakeOnWave || !sensors.hasRangedProximity()) return@gesture
                    val generation = screen.currentOffGeneration() ?: return@gesture
                    val settingGeneration = config.wakeOnWaveGeneration
                    wakeOnWaveWorker.execute {
                        if (
                            serviceStopping || !config.wakeOnWave || !sensors.hasRangedProximity() ||
                            config.wakeOnWaveGeneration != settingGeneration
                        ) return@execute
                        if (screen.wakeIfStillDark(generation) {
                                !serviceStopping && config.wakeOnWave && sensors.hasRangedProximity() &&
                                    config.wakeOnWaveGeneration == settingGeneration
                            } == WakeOutcome.WOKEN && !serviceStopping
                        ) {
                            mqtt.publishScreenOn()
                        }
                    }
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
                repairAdminHomeFromCapturedRoute()
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
                if (!profileRegistry.markResolvedStartupHealthy()) {
                    Log.w(TAG, "could not persist the healthy profile revision snapshot")
                }
            }
            // Best-effort observation belongs after the required control plane and every active owner.
            // It must not contend with helper migration, live-setting replay, ADB reassertion or profile
            // activation proof. This uses the existing service scope and owns no retry/lifecycle.
            scope.launch(Dispatchers.IO) {
                server.prewarm()
            }
        }
        Thread({
            when (awaitServiceStartup(startup, startupActivationGeneration)) {
                ServiceStartupDisposition.RUNNING -> {
                    startupRecoveryPrefs.edit().clear().commit()
                    updateForegroundStatus("Listening on :${config.httpPort}")
                }
                ServiceStartupDisposition.PROFILE_ACTIVATION_ROLLBACK -> {
                    updateForegroundStatus("Degraded · profile startup failed")
                    Log.e(TAG, "profile activation startup failed; scheduling rollback restart")
                    profileRestart.request()
                }
                ServiceStartupDisposition.DEGRADED -> {
                    updateForegroundStatus("Degraded · startup failed")
                    val now = android.os.SystemClock.elapsedRealtime()
                    val decision = startupRecoveryDecision(
                        startupRecoveryPrefs.getInt("attempts", 0),
                        startupRecoveryPrefs.getLong("last_at", 0L),
                        now,
                    )
                    startupRecoveryPrefs.edit()
                        .putInt("attempts", decision.nextAttempt)
                        .putLong("last_at", now)
                        .commit()
                    if (decision.restart && recoveryRestart.request()) {
                        Log.e(TAG, "service startup failed; scheduling bounded recovery restart ${decision.nextAttempt}/3")
                    } else {
                        Log.e(TAG, "service startup failed; recovery restart limit reached, remaining degraded")
                    }
                }
            }
        }, "service-startup-health").apply { isDaemon = true; start() }
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
     * Two stall modes, both given one fresh-client fallback before a previously-live process may cross
     * the process boundary:
     *   (1) STATE-stuck — HiveMQ's auto-reconnect stalls (transient auth reject on an HA/broker restart,
     *       or its reconnect thread is power-management-deferred); rebuild after 2 non-connected checks.
     *   (2) LIVENESS-stale — the broker dropped the link but HiveMQ never noticed the half-open
     *       (CLOSE-WAIT) socket, so it still reports "connected" while publishing into the void.
     *       isConnected() lies, so key on TRUE liveness: each tick send a heartbeat (a QoS-1 publish the
     *       broker must ACK). The fresh client's exact online + state acknowledgement, not CONNACK,
     *       heartbeat, or completion of the reconnect submission, proves recovery.
     *
     * CRITICAL INVARIANT: the loop thread makes NO potentially-blocking MQTT call. A HiveMQ publish —
     * and even disconnect/rebuild — can block on an internal client monitor exactly when the connection
     * is wedged (the same trap as the sensor-callback ANR), which is precisely when the watchdog is
     * needed. An earlier implementation called heartbeat() inline; the watchdog then froze inside its own
     * probe (observed in the field: thread parked on a futex, liveness 44 min stale, no rebuild). Heartbeat uses
     * a sacrificial side-thread. Rebuild admission is generation-checked by the runtime owner,
     * then the accepted reconnect runs on a recovery worker so neither the watchdog nor the serialized
     * transition lane can be trapped by it. The alternate address family is staged durably BEFORE that
     * potentially wedged owner work is submitted, so a later process boundary cannot restart onto the
     * same route merely because the reconnect callback never entered. One rebuild then retains that client
     * for the existing five-minute progress bound. This prevents the observed 60-second IPv4/IPv6 loop;
     * only exact application readiness starts a new epoch. A previously-live runtime retains its existing
     * process-boundary policy. An announcement wedge gets one durable process escape; its replacement can
     * still alternate once, but cannot inherit an unbounded restart loop.
     * The recovery DECISION lives in [ConnectionSupervisor]; this thread owns only tick cadence and
     * off-thread dispatch.
     */
    private fun startMqttWatchdog() {
        if (mqttWatchdogAlive) return
        mqttWatchdogAlive = true
        val supervisor = ConnectionSupervisor(MQTT_STALE_MS, REBUILD_ABANDON_MS)
        val worker = Thread {
            try {
                data class HeartbeatProbe(val generation: Long, val thread: Thread)
                data class RebuildAttempt(
                    val runtimeGeneration: Long,
                    val completion: java.util.concurrent.Future<Boolean>,
                    val outcome: java.util.concurrent.atomic.AtomicReference<MqttRecoveryOutcome>,
                    val ticket: MqttRecoveryTicket,
                )
                val heartbeats = mutableListOf<HeartbeatProbe>()
                var terminalRecoveryNeeded = false
                var rebuild: RebuildAttempt? = null
                while (mqttWatchdogAlive) {
                    try { Thread.sleep(MQTT_WATCHDOG_MS) } catch (e: InterruptedException) { break }
                    if (!mqttWatchdogAlive) break
                    heartbeats.removeAll { !it.thread.isAlive }
                    if (terminalRecoveryNeeded) {
                        if (!serviceStopping && recoveryRestart.request()) break
                        if (!serviceStopping) {
                            Log.w(TAG, "MQTT watchdog terminal recovery was not admitted; retrying next tick")
                        }
                        continue
                    }
                    try {
                        val watchedRuntime = runtime.observe()
                        rebuild?.let { attempt ->
                            when {
                                attempt.runtimeGeneration != watchedRuntime?.generation -> rebuild = null
                                attempt.completion.isDone -> {
                                    rebuild = null
                                    val ownerAccepted = runCatching { attempt.completion.get() }.getOrDefault(false)
                                    when {
                                        !ownerAccepted -> {
                                            supervisor.rebuildNotAdmitted()
                                            if (watchedRuntime.value.mqtt.reconcileRejectedRecovery(attempt.ticket)) {
                                                Log.i(TAG, "MQTT fallback stage reconciled after owner rejection")
                                            } else {
                                                Log.w(TAG, "MQTT fallback rejection could not durably restore its route")
                                            }
                                        }
                                        attempt.outcome.get() == MqttRecoveryOutcome.REBUILT ->
                                            supervisor.rebuildAdmitted()
                                        attempt.outcome.get() == MqttRecoveryOutcome.NO_LONGER_NEEDED -> {
                                            supervisor.recoveryNoLongerNeeded()
                                            Log.i(TAG, "MQTT recovered before queued fallback entered; preserving the live client")
                                        }
                                        else -> {
                                            supervisor.rebuildNotAdmitted()
                                            Log.w(TAG, "MQTT fallback entered without a transport mutation")
                                        }
                                    }
                                }
                            }
                        }
                        if (watchedRuntime == null) {
                            rebuild = null
                            supervisor.runtimeUnavailable()
                            continue
                        }
                        val watched = watchedRuntime.value.mqtt
                        val watchdogObservation = watched.watchdogObservation()
                        val progress = watchdogObservation.progress
                        val now = android.os.SystemClock.elapsedRealtime()
                        val sinceOk = if (progress.lastOkMs == 0L) 0L
                        else (now - progress.lastOkMs).coerceAtLeast(0L)
                        val watchedState = watchdogObservation.state
                        Log.i(TAG, "mqtt watchdog tick: state=$watchedState sinceOk=${sinceOk}ms hb=${heartbeats.map { it.generation }} rebuild=${rebuild != null}")
                        when (val action = supervisor.tick(
                            watchedState,
                            progress.lastOkMs,
                            sinceOk,
                            now,
                            rebuild != null,
                            runtimeGeneration = watchedRuntime.generation,
                            connectionGeneration = progress.connectionGeneration,
                            holdSelectedFamily = watchdogObservation.holdSelectedFamily,
                            applicationReadyEver = watchdogObservation.applicationReadyEver,
                            announcementBoundaryAvailable =
                                watchdogObservation.announcementProcessRecoveryAvailable,
                        )) {
                            is ConnectionSupervisor.Action.Rebuild -> {
                                Log.w(TAG, "MQTT ${action.reason} stall (${sinceOk}ms, state=$watchedState) — forcing one fresh client (flip family=${action.flipFamily})")
                                val observedTicket = watchdogObservation.recoveryTicket
                                val recoveryTicket = if (action.flipFamily) {
                                    watched.stageAlternateFamilyForReconnect(observedTicket)
                                } else {
                                    watched.recoveryTicketForReconnect(observedTicket)
                                }
                                if (recoveryTicket == null) {
                                    supervisor.rebuildNotAdmitted()
                                    Log.w(TAG, "MQTT fallback could not prepare a durable current-runtime ticket")
                                    continue
                                }
                                val outcome = java.util.concurrent.atomic.AtomicReference(
                                    MqttRecoveryOutcome.NOT_ADMITTED,
                                )
                                rebuild = RebuildAttempt(
                                    runtimeGeneration = watchedRuntime.generation,
                                    completion = runtime.reconnect(watchedRuntime) { target ->
                                        if (target.mqtt.state == "discovering") target.mdns.start()
                                        // Family selection already belongs to the watchdog admission above.
                                        // The worker owns transport replacement only and must never flip twice.
                                        outcome.set(target.mqtt.reconnect(recoveryTicket))
                                    },
                                    outcome = outcome,
                                    ticket = recoveryTicket,
                                )
                            }
                            is ConnectionSupervisor.Action.SkipRebuild -> {
                                if (action.reason == "discovery") {
                                    Log.w(TAG, "mqtt discovery retry already in flight — not stacking another")
                                } else {
                                    Log.w(TAG, "mqtt ${action.reason} fallback awaiting broker progress ${action.elapsedMs}ms — retaining client")
                                }
                            }
                            is ConnectionSupervisor.Action.ProcessRecovery -> {
                                Log.e(TAG, "MQTT ${action.reason} failure exceeded the fresh-client progress bound")
                                if (action.consumeAnnouncementBudget) {
                                    if (!watched.consumeAnnouncementProcessRecovery(
                                            watchdogObservation.recoveryTicket,
                                        )
                                    ) {
                                        supervisor.recoveryNoLongerNeeded()
                                        Log.i(TAG, "MQTT announcement recovery changed before boundary consumption")
                                        continue
                                    }
                                    // The durable token is already spent. If process admission is
                                    // temporarily rejected, retry the same request without consuming again.
                                    terminalRecoveryNeeded = true
                                }
                                if (recoveryRestart.request()) break
                                Log.w(TAG, "MQTT process recovery request was not admitted; retrying next tick")
                                continue
                            }
                            ConnectionSupervisor.Action.None -> {}
                        }
                        // Do not publish through the old client after deciding to replace it. Ordinary
                        // in-flight publishes are still harmless because replacement proof is scoped to
                        // the new connection generation.
                        if (rebuild != null) continue
                        // Heartbeat LAST and OFF-THREAD: a wedged client blocks the publish, but only its
                        // generation's sacrificial thread. A replacement runtime must not be suppressed by a
                        // heartbeat stranded on the obsolete client.
                        val observed = runtime.observe()
                        val currentConnectionGeneration =
                            observed?.value?.mqtt?.heartbeatConnectionGeneration()
                        when (val admission = HeartbeatAdmission.decide(
                            currentGeneration = currentConnectionGeneration,
                            liveTrackedGenerations = heartbeats.map { it.generation },
                        )) {
                            HeartbeatAdmission.Decision.NoCurrentConnection,
                            HeartbeatAdmission.Decision.CurrentHeartbeatAlive -> Unit
                            is HeartbeatAdmission.Decision.Admit -> {
                                if (admission.replacingStranded) {
                                    FeatureCosts.registry.recordDropped(FeatureCostOperation.MQTT_HEARTBEAT_ADMISSION)
                                    Log.w(TAG, "obsolete mqtt heartbeat is stranded; admitting generation ${admission.generation}")
                                }
                                val target = observed ?: continue
                                val thread = Thread({
                                    if (runtime.isCurrent(target) &&
                                        target.value.mqtt.isCurrentHeartbeatConnection(admission.generation)
                                    ) {
                                        runCatching { target.value.mqtt.heartbeat() }
                                    }
                                }, "mqtt-heartbeat").apply { isDaemon = true; start() }
                                heartbeats += HeartbeatProbe(admission.generation, thread)
                            }
                            is HeartbeatAdmission.Decision.EscalateRecovery -> {
                                FeatureCosts.registry.recordDropped(FeatureCostOperation.MQTT_HEARTBEAT_RECOVERY)
                                Log.e(TAG, "${admission.strandedGenerations} obsolete mqtt heartbeat threads are stranded; requesting bounded process recovery")
                                if (recoveryRestart.request()) break
                                Log.w(TAG, "MQTT heartbeat recovery request was not admitted; retrying next tick")
                            }
                        }
                    } catch (failure: Exception) {
                        terminalRecoveryNeeded = true
                        rebuild = null
                        supervisor.runtimeUnavailable()
                        Log.e(TAG, "MQTT watchdog failed; requesting bounded process recovery", failure)
                        if (!serviceStopping) {
                            if (recoveryRestart.request()) break
                            Log.w(TAG, "MQTT watchdog terminal recovery was not admitted; retrying next tick")
                        }
                    }
                }
            } finally {
                synchronized(this@PaneldService) {
                    if (mqttWatchdogThread === Thread.currentThread()) {
                        mqttWatchdogAlive = false
                        mqttWatchdogThread = null
                    }
                }
            }
        }.apply { isDaemon = true; name = "mqtt-watchdog" }
        synchronized(this) { mqttWatchdogThread = worker }
        worker.start()
    }

    /**
     * Nudge MQTT to reconnect the moment the default network returns (Wi-Fi / router flap), instead of
     * waiting out the auto-reconnect backoff. A blank broker in discovery-wait also restarts mDNS before
     * retrying; an already connected, terminally stopped, or auth-recovery runtime is left alone.
     */
    private fun registerNetworkCallback() {
        if (netCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val observed = runtime.observe() ?: return
                val target = observed.value.mqtt
                target.refreshDiscoveryAddress()
                when (networkAvailableAction(target.state, target.configuredBroker)) {
                    NetworkAvailableAction.NONE -> Unit
                    NetworkAvailableAction.RECONNECT -> {
                        Log.i(TAG, "network available — nudging MQTT reconnect")
                        runtime.reconnect(observed) { it.mqtt.reconnect() }
                    }
                    NetworkAvailableAction.RETRY_DISCOVERY -> {
                        Log.i(TAG, "network available — retrying mDNS and MQTT auto-discovery")
                        runtime.reconnect(observed) {
                            it.mdns.start()
                            it.mqtt.reconnect()
                        }
                    }
                }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                runtime.observe()?.value?.mqtt?.refreshDiscoveryAddress()
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }.onSuccess { netCallback = cb }
    }

    private fun startForegroundCompat(statusText: String) {
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
        val notification = foregroundNotification(channelId, silent, statusText)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        Log.i(TAG, "foreground service started")
    }

    private fun updateForegroundStatus(statusText: String) {
        if (serviceStopping) return
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val silent = config.silenceBootChime
        val channelId = if (silent) SILENT_CHANNEL_ID else CHANNEL_ID
        mgr.notify(NOTIF_ID, foregroundNotification(channelId, silent, statusText))
    }

    private fun foregroundNotification(channelId: String, silent: Boolean, statusText: String): Notification =
        NotificationCompat.Builder(this, channelId)
            .setContentTitle("ha-paneld")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(silent)
            .build()

    override fun onDestroy() {
        // Android invokes this on the main thread. All deliberate waits below consume one deadline so
        // individually safe phase timeouts cannot accumulate into an input-dispatch ANR.
        val teardownDeadline = MonotonicDeadline(SERVICE_DESTROY_BUDGET_MS)
        // App-local completion may continue off-main, but every subsidiary owner shares this one budget.
        // External display/system-policy recovery remains mandatory even after this deadline expires.
        val asyncTeardownDeadline = MonotonicDeadline(ASYNC_TEARDOWN_BUDGET_MS)
        serviceStopping = true
        lightMqttPublisher.close()
        adaptiveSiteGeneration.incrementAndGet()
        brightnessObserver?.let { observer ->
            runCatching { contentResolver.unregisterContentObserver(observer) }
            brightnessObserver = null
        }
        beginAudioTeardown(audio::closeAdmission, audio::cancelCurrent)
        kiosk.closeAdmission()
        navbar.closeAdmission()
        screen.closeAdmission()
        // A deliberately dark panel is the only external state that becomes unrecoverable if Android
        // kills this process after onDestroy returns (the touch-wake overlay dies with it). Give its
        // dedicated recovery owner first use of the aggregate deadline, then let it keep retrying while
        // all slower server/network teardown proceeds off-main.
        val screenExitRecovery = ensureScreenExitRecovery()
        val screenRecoveryWaitMs = teardownDeadline.remainingMs()
        if (screenRecoveryWaitMs > 0L) {
            runCatching { screenExitRecovery.get(screenRecoveryWaitMs, TimeUnit.MILLISECONDS) }
        }
        started = false
        netCallback?.let { cb ->
            runCatching { (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.unregisterNetworkCallback(cb) }
            netCallback = null
        }
        stopMqttWatchdog(teardownDeadline.remainingMs())
        // The existing runtime lane owns latest configuration work. Close its one admission point now;
        // the sticky shutdown queued below becomes the sole proof that any active mutation drained.
        runtime.closeAdmission()
        FeatureCosts.registry.setBacklog(FeatureCostOperation.NETWORK_RECONFIGURE, 0)
        // Close renderer transaction admission before waiting for the runtime lane. If startup has not
        // reconciled yet it exits early; if it has progressed further, final runtime cleanup below owns
        // every resource it may still open, including HTTP.
        val rendererDrained = if (!::rendererPreparation.isInitialized) true
            else rendererPreparation.close(teardownDeadline.remainingMs())
        if (!rendererDrained) {
            Log.w(TAG, "renderer transaction did not become idle before the service teardown deadline")
        }
        val httpOwnersStopped = AtomicBoolean(!::server.isInitialized)
        val audioDrained = AtomicBoolean(false)
        val ownerCleanup = ServiceOwnerCleanupTracker()
        val mqttFinalization = AtomicReference<Future<Unit>?>(null)
        val sensorPersistenceClosed = AtomicReference<Future<Unit>?>(null)
        val stopped = runtime.shutdown(teardownDeadline.remainingMs()) { activeRuntime ->
            fun closeOwner(name: String, close: () -> Unit) {
                ownerCleanup.run(close)?.let { error ->
                    Log.w(TAG, "$name cleanup failed", error)
                }
            }
            fun closeOwnerResult(name: String, close: () -> Boolean) {
                val result = runCatching(close).onFailure { error ->
                    Log.w(TAG, "$name cleanup failed", error)
                }.getOrDefault(false)
                ownerCleanup.record(result)
                if (!result) Log.w(TAG, "$name cleanup did not complete")
            }
            // This cleanup is queued behind any in-flight startup/reconfigure. Closing HTTP here means
            // startup cannot bind a fresh listener after an earlier out-of-band stop has already run.
            // It is deliberately first: the LAN CDP relay is root-owned and survives app process death.
            httpOwnersStopped.set(
                if (!::server.isInitialized) true else runCatching { server.stop() }
                    .onFailure { Log.w(TAG, "HTTP cleanup failed", it) }
                    .getOrDefault(false),
            )
            // MQTT commands and convergence observers can use the service-owned hardware controllers.
            // Latest live refresh/replacement work has already drained on this runtime lane. Prove the
            // remaining mutation owners terminal before dismantling any dependent controller. mDNS is
            // fenced at the same time and both consume the one asynchronous teardown deadline.
            val mdnsRetirement = activeRuntime.mdns.retire(asyncTeardownDeadline)
            val mqttRetirement = activeRuntime.mqtt.stop(asyncTeardownDeadline)
            mqttFinalization.set(mqttRetirement.finalization)
            val mqttOwnersDrained = mqttRetirement.ownersDrained.awaitTrue(asyncTeardownDeadline)
            val mdnsStopped = mdnsRetirement.awaitTrue(asyncTeardownDeadline)
            val lightPublisherDrained = lightMqttPublisher.awaitTermination(asyncTeardownDeadline.remainingMs())
            if (!mqttOwnersDrained || !mdnsStopped || !lightPublisherDrained) {
                ownerCleanup.record(false)
                error(
                    "network mutation owners did not drain before hardware teardown " +
                        "(mqtt=$mqttOwnersDrained mdns=$mdnsStopped light=$lightPublisherDrained)",
                )
            }

            // Kiosk writes persistent system policy. Serialize OFF behind any admitted delayed reassert
            // before a forced process boundary; the persisted user preference itself remains unchanged.
            cancelKioskReassert()
            closeOwner("kiosk") { kiosk.apply(false) }
            closeOwner("navbar") { navbar.cleanup() }
            if (::entityLearning.isInitialized) EntityLearningRuntime.detach(entityLearning)
            val audioWaitMs = minOf(asyncTeardownDeadline.remainingMs(), AUDIO_SHUTDOWN_MS)
            val audioStopped = runCatching {
                audioWaitMs > 0L && kotlinx.coroutines.runBlocking { audio.close(audioWaitMs) }
            }.getOrDefault(false)
            audioDrained.set(audioStopped)
            if (!audioStopped) {
                Log.w(TAG, "audio cleanup exceeded ${AUDIO_SHUTDOWN_MS}ms")
            }
            // The MQTT publication target and command ingress are now terminal; drain the remaining
            // producers and hardware owners in dependency order.
            if (::zigbeeHealth.isInitialized) closeOwnerResult("Zigbee health") { zigbeeHealth.stop() }
            closeOwner("evdev") { EvdevButtonClient.stop() }
            if (::haAmbientLux.isInitialized) closeOwner("HA ambient light") { haAmbientLux.close() }
            closeOwner("sensors") { sensorPersistenceClosed.set(sensors.stop()) }
            if (::autoBright.isInitialized) {
                closeOwnerResult("adaptive brightness") {
                    autoBright.closeAndJoin(asyncTeardownDeadline.remainingMs())
                }
            }
            closeOwner("watchdog") { watchdog.stop() }
            closeOwner("LED effect") { ledEffect.close() }
            closeOwnerResult("wake-on-wave worker") {
                wakeOnWaveWorker.closeAndJoin(
                    minOf(asyncTeardownDeadline.remainingMs(), WAKE_WORKER_JOIN_MS),
                )
            }
            closeOwnerResult("power") { power.releaseAndVerify() }
            closeOwner("log shipper") { logShipper.stop() }
            closeOwner("performance reader") { io.github.maxlyth.hapaneld.http.PerfReader.stop() }
            closeOwner("app log capture") { logCaptureApp.close() }
            closeOwner("system log capture") { logCaptureSystem.close() }
        }
        if (!stopped) Log.w(TAG, "runtime teardown exceeded the service deadline; cleanup continues on its owner thread")
        if (!httpOwnersStopped.get()) Log.w(TAG, "HTTP owners did not stop cleanly before learner teardown")

        // Startup and all runtime producers are now terminal before their shared coroutine scope and
        // SQLite-backed learner disappear. On a timeout, deliberately leave the store open for process
        // teardown rather than racing late runtime cleanup.
        val root = scope.coroutineContext[Job]
        scope.cancel()
        val scopeWaitMs = teardownDeadline.remainingMs()
        val scopeDrained = when {
            root == null -> true
            scopeWaitMs <= 0L -> root.isCompleted
            else -> runBlocking {
                withTimeoutOrNull(scopeWaitMs) { root.join(); true } ?: false
            }
        }
        if (!scopeDrained) Log.w(TAG, "service jobs did not drain before the service teardown deadline")
        if (::entityLearning.isInitialized && !(stopped && httpOwnersStopped.get() && rendererDrained && scopeDrained)) {
            Log.w(TAG, "entity-learning store remains open until asynchronous teardown drains its producers")
        }
        val stateFlushMs = teardownDeadline.remainingMs()
        if (stateFlushMs <= 0L || !AppState.flush(this, stateFlushMs)) {
            Log.w(TAG, "application-state writes did not drain before the service teardown deadline")
        }
        // The main-thread flush is best effort only: timed-out runtime cleanup can admit sensor writes
        // later. The finalizer always flushes again after every producer and its persistence future drain.
        finishTeardownAsync(
            asyncTeardownDeadline,
            root,
            httpOwnersStopped,
            rendererDrained,
            audioDrained,
            ownerCleanup,
            mqttFinalization,
            sensorPersistenceClosed,
        )
        super.onDestroy()
    }

    /**
     * Finish work that exceeded Android's main-thread lifecycle budget. A completely proved ordinary
     * stop opens the next same-process lease; an explicit or incomplete teardown restores external state
     * and exits so a replacement cannot inherit ambiguous owners.
     */
    private fun finishTeardownAsync(
        finalizerDeadline: MonotonicDeadline,
        root: Job?,
        httpOwnersStopped: AtomicBoolean,
        rendererAlreadyDrained: Boolean,
        audioDrained: AtomicBoolean,
        ownerCleanup: ServiceOwnerCleanupTracker,
        mqttFinalization: AtomicReference<Future<Unit>?>,
        sensorPersistenceClosed: AtomicReference<Future<Unit>?>,
    ) {
        Thread {
            try {
                var rendererDrained = rendererAlreadyDrained
                while (!rendererDrained) {
                    val waitMs = minOf(finalizerDeadline.remainingMs(), ASYNC_TEARDOWN_WAIT_MS)
                    if (waitMs <= 0L) return@Thread finishTeardownAfterExternalStateIsSafe(
                        completed = false,
                        reason = "renderer transaction remained active",
                    )
                    rendererDrained = rendererPreparation.close(waitMs)
                }
                while (!runtime.isStopped()) {
                    val waitMs = minOf(finalizerDeadline.remainingMs(), ASYNC_TEARDOWN_WAIT_MS)
                    if (waitMs <= 0L) return@Thread finishTeardownAfterExternalStateIsSafe(
                        completed = false,
                        reason = "runtime cleanup did not finish",
                    )
                    val shutdownSucceeded = runtime.shutdown(waitMs) {}
                    if (!shutdownSucceeded && !ownerCleanup.isComplete()) {
                        return@Thread finishTeardownAfterExternalStateIsSafe(
                            completed = false,
                            reason = "network mutation owners remained active",
                        )
                    }
                    if (!shutdownSucceeded && runtime.hasFailedShutdown()) {
                        return@Thread finishTeardownAfterExternalStateIsSafe(
                            completed = false,
                            reason = "runtime cleanup failed",
                        )
                    }
                }
                if (!ownerCleanup.isComplete()) {
                    return@Thread finishTeardownAfterExternalStateIsSafe(
                        completed = false,
                        reason = "one or more runtime owners did not clean up",
                    )
                }
                if (!audioDrained.get()) {
                    return@Thread finishTeardownAfterExternalStateIsSafe(
                        completed = false,
                        reason = "audio cleanup did not drain",
                    )
                }
                val mqttClose = mqttFinalization.get()
                    ?: return@Thread finishTeardownAfterExternalStateIsSafe(
                        completed = false,
                        reason = "MQTT cleanup did not start",
                    )
                if (!awaitFinalizerFuture(finalizerDeadline, mqttClose)) {
                    return@Thread finishTeardownAfterExternalStateIsSafe(
                        completed = false,
                        reason = "final MQTT publication did not finish",
                    )
                }
                val sensorClose = sensorPersistenceClosed.get()
                    ?: return@Thread finishTeardownAfterExternalStateIsSafe(
                        completed = false,
                        reason = "sensor cleanup did not start",
                    )
                if (!awaitFinalizerFuture(finalizerDeadline, sensorClose)) {
                    return@Thread finishTeardownAfterExternalStateIsSafe(
                        completed = false,
                        reason = "proximity persistence did not drain",
                    )
                }
                if (root != null && !root.isCompleted) {
                    val waitMs = finalizerDeadline.remainingMs()
                    val scopeDrained = waitMs > 0L && runBlocking {
                        withTimeoutOrNull(waitMs) { root.join(); true } ?: false
                    }
                    if (!scopeDrained) return@Thread finishTeardownAfterExternalStateIsSafe(
                        completed = false,
                        reason = "service scope did not drain",
                    )
                }
                if (!httpOwnersStopped.get()) {
                    return@Thread finishTeardownAfterExternalStateIsSafe(
                        completed = false,
                        reason = "HTTP owners did not stop cleanly",
                    )
                }

                if (::entityLearning.isInitialized && !runFinalizerStep(finalizerDeadline) { entityLearning.close() }) {
                    return@Thread finishTeardownAfterExternalStateIsSafe(
                        completed = false,
                        reason = "entity-learning store did not close",
                    )
                }
                val stateFlushMs = finalizerDeadline.remainingMs()
                if (stateFlushMs <= 0L || !AppState.flush(this, stateFlushMs)) {
                    return@Thread finishTeardownAfterExternalStateIsSafe(
                        completed = false,
                        reason = "final application-state writes did not drain",
                    )
                }
                finishTeardownAfterExternalStateIsSafe(completed = true, reason = "service teardown completed")
            } catch (error: Throwable) {
                Log.e(TAG, "asynchronous service teardown failed", error)
                finishTeardownAfterExternalStateIsSafe(
                    completed = false,
                    reason = "unexpected finalizer failure",
                )
            }
        }.apply {
            isDaemon = true
            name = "ha-paneld-service-finalizer"
        }.start()
    }

    private fun runFinalizerStep(deadline: MonotonicDeadline, block: () -> Unit): Boolean {
        val waitMs = deadline.remainingMs()
        if (waitMs <= 0L) return false
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "ha-paneld-service-finalizer-step").apply { isDaemon = true }
        }
        return try {
            executor.submit(Callable { block(); true }).get(waitMs, TimeUnit.MILLISECONDS)
        } catch (error: Throwable) {
            Log.e(TAG, "bounded service finalizer step failed", error)
            false
        } finally {
            executor.shutdownNow()
        }
    }

    private fun awaitFinalizerFuture(deadline: MonotonicDeadline, future: Future<*>): Boolean {
        val waitMs = deadline.remainingMs()
        if (waitMs <= 0L) return false
        return try {
            future.get(waitMs, TimeUnit.MILLISECONDS)
            true
        } catch (error: Throwable) {
            Log.e(TAG, "bounded service finalizer future failed", error)
            false
        }
    }

    private fun ensureScreenExitRecovery(): CompletableFuture<Boolean> {
        screenExitRecoveryOwner.get()?.let { return it }
        val completion = CompletableFuture<Boolean>()
        if (!screenExitRecoveryOwner.compareAndSet(null, completion)) {
            return checkNotNull(screenExitRecoveryOwner.get())
        }
        if (!::screen.isInitialized) {
            completion.complete(true)
            return completion
        }
        Thread {
            while (!completion.isDone) {
                val safe = runCatching { screen.restoreAndEstablishExitSafety() }
                    .onFailure { Log.e(TAG, "screen restore before process restart failed", it) }
                    .getOrDefault(false)
                if (safe) {
                    completion.complete(true)
                    return@Thread
                }
                try {
                    Thread.sleep(EXTERNAL_STATE_RETRY_MS)
                } catch (_: InterruptedException) {
                    // Screen recovery remains mandatory until hardware readback proves it is lit.
                }
            }
        }.apply {
            isDaemon = true
            name = "ha-paneld-screen-exit-recovery"
        }.start()
        return completion
    }

    /**
     * Route profile/WebView/recovery restarts through the same external-state proof as lifecycle teardown.
     * The caller may be the main looper, so admission closes synchronously while all blocking cleanup runs
     * on one daemon owner. App-local workers need no separate drain because the proven process boundary
     * terminates them; HTTP is stopped first so it cannot reassert the root CDP relay during verification.
     */
    private fun requestSafeProcessBoundary(reason: String) {
        if (!teardownBoundary.requestExplicitBoundary()) return
        Log.i(TAG, "safe process restart requested: $reason")
        serviceStopping = true
        if (::audio.isInitialized) {
            beginAudioTeardown(audio::closeAdmission, audio::cancelCurrent)
        }
        if (::kiosk.isInitialized) kiosk.closeAdmission()
        if (::navbar.isInitialized) navbar.closeAdmission()
        if (::screen.isInitialized) screen.closeAdmission()
        Thread {
            if (::server.isInitialized) {
                runCatching { server.stop() }
                    .onFailure { Log.w(TAG, "HTTP cleanup before requested process restart failed", it) }
                    .onSuccess { complete ->
                        if (!complete) Log.w(TAG, "HTTP cleanup before requested process restart was incomplete")
                    }
            }
            finishAfterExternalStateIsSafe(completed = false, reason = reason)
        }.apply {
            isDaemon = true
            name = "ha-paneld-safe-process-boundary"
        }.start()
    }

    /**
     * App-local workers die at the process boundary, but the root CDP relay and display/system-policy
     * state do not. Never exit until the relay is proved absent and the screen, kiosk policy, and navbar
     * crop have been restored. Cleanup admission is closed first so an explicit external service stop
     * also leaves a usable panel even if Android has no remaining START_STICKY request to recreate us.
     */
    private fun finishTeardownAfterExternalStateIsSafe(completed: Boolean, reason: String) {
        if (!completed) Log.e(TAG, "service teardown was incomplete: $reason")
        finishAfterExternalStateIsSafe(completed, reason)
    }

    private fun finishAfterExternalStateIsSafe(
        completed: Boolean,
        reason: String,
    ) {
        runServiceBoundary(
            boundary = teardownBoundary,
            completed = completed,
            prepare = {
                cancelKioskReassert()
                if (::navbar.isInitialized) {
                    runCatching { navbar.cleanup() }
                        .onFailure { Log.e(TAG, "navbar cleanup before process restart failed", it) }
                }
            },
            prove = { attempt ->
                val kioskSafe = if (!::kiosk.isInitialized) true else runCatching {
                    kiosk.apply(false) && kiosk.recoverPersistentState(config.kioskLock)
                }
                    .onFailure { Log.e(TAG, "kiosk policy cleanup before process restart failed", it) }
                    .getOrDefault(false)
                val navbarSafe = if (!::navbar.isInitialized) true else runCatching {
                    navbar.recoverPersistentState()
                }
                    .onFailure { Log.e(TAG, "navbar overscan cleanup before process restart failed", it) }
                    .getOrDefault(false)
                val screenSafe = runCatching {
                    proveScreenSafeForBoundary(ensureScreenExitRecovery())
                }.onFailure { Log.e(TAG, "screen recovery before process restart failed", it) }
                    .getOrDefault(false)
                val relaySafe = runCatching {
                    CdpRelay.stopAndVerifyForProcessExit()
                }.onFailure { Log.e(TAG, "CDP relay cleanup before process restart failed", it) }
                    .getOrDefault(false)
                // Durable markers preserve deferred policy cleanup. Once the screen is proved usable,
                // privilege loss must force a fresh process rather than fence START_STICKY forever.
                val forceFreshProcess = shouldForceFreshProcessAfterExternalRecovery(
                    attempt = attempt,
                    maxAttempts = EXTERNAL_STATE_BOUNDARY_ATTEMPTS,
                    kioskSafe = kioskSafe,
                    navbarSafe = navbarSafe,
                    screenSafe = screenSafe,
                    relaySafe = relaySafe,
                )
                val externalStateSafe = kioskSafe && navbarSafe && screenSafe && relaySafe
                if (!externalStateSafe && !forceFreshProcess) {
                    Log.e(
                        TAG,
                        "withholding process restart until external state is safe " +
                            "(attempt=$attempt kioskSafe=$kioskSafe navbarSafe=$navbarSafe " +
                            "screenSafe=$screenSafe relaySafe=$relaySafe)",
                    )
                }
                ServiceBoundaryProof(externalStateSafe, forceFreshProcess)
            },
            pauseBeforeRetry = {
                try {
                    Thread.sleep(EXTERNAL_STATE_RETRY_MS)
                } catch (_: InterruptedException) {
                    // This recovery owner must keep retrying until external state is safe to abandon.
                }
            },
            finish = { disposition ->
                if (disposition == ServiceTeardownDisposition.EXIT) {
                    Log.i(TAG, "$reason; entering a clean process boundary")
                    kotlin.system.exitProcess(0)
                } else {
                    Log.i(TAG, "$reason; releasing the same-process service successor")
                    restartLease.completeTeardown()
                }
            },
        )
    }

    private fun scheduleKioskReassert() {
        val worker = Thread {
            try {
                val recovered = recoverAndMaybeEnableKiosk(
                    escapeDelayMs = KIOSK_REASSERT_MS,
                    retryDelayMs = KIOSK_RECOVERY_RETRY_MS,
                    maxAttempts = KIOSK_RECOVERY_ATTEMPTS,
                    shouldContinue = { !serviceStopping },
                    recover = {
                        kioskSettings.serialized { kiosk.recoverPersistentState(config.kioskLock) }
                    },
                    enabled = { config.kioskLock },
                    enable = {
                        kioskSettings.serialized { config.kioskLock && kiosk.apply(true) }
                    },
                )
                if (!recovered && !serviceStopping) {
                    Log.w(TAG, "kiosk platform recovery remains pending; leaving kiosk unlocked")
                }
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

    private fun stopMqttWatchdog(joinMs: Long) {
        mqttWatchdogAlive = false
        val worker = synchronized(this) { mqttWatchdogThread.also { mqttWatchdogThread = null } }
        worker?.interrupt()
        if (joinMs > 0L) runCatching { worker?.join(joinMs) }
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
        // Fresh-client broker-progress lease. A previously-live process crosses its controlled boundary
        // at expiry; a clean process gives its restored route this full lease before one alternate try.
        private const val REBUILD_ABANDON_MS = 300_000L
        private const val RECOVERY_RESTART_GRACE_MS = 1_000L
        private const val COMPANION_STARTUP_STATUS_POLL_MS = 2_000L
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
        private const val KIOSK_RECOVERY_RETRY_MS = 1_000L
        private const val KIOSK_RECOVERY_ATTEMPTS = 3
        private const val KIOSK_CANCEL_JOIN_MS = 500L
        private const val WAKE_WORKER_JOIN_MS = 500L
        private const val AUDIO_SHUTDOWN_MS = 2_000L
        private const val SERVICE_DESTROY_BUDGET_MS = 1_000L
        private const val ASYNC_TEARDOWN_WAIT_MS = 10_000L
        private const val ASYNC_TEARDOWN_BUDGET_MS = 30_000L
        private const val EXTERNAL_STATE_RETRY_MS = 1_000L
        private const val EXTERNAL_STATE_BOUNDARY_ATTEMPTS = 5
        private const val NETWORK_RECONFIGURE_BUDGET_MS = 15_000L
        private val SERVICE_RESTART_BARRIER = ServiceRestartBarrier()

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
