package io.github.maxlyth.hapaneld.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import io.github.maxlyth.hapaneld.BuildConfig
import io.github.maxlyth.hapaneld.platform.ShellPrivilege
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Official Shizuku client adapter. No Shizuku type escapes this package. */
object ShizukuBridge : ShellPrivilege {
    private const val TAG = "ha-paneld/shizuku"
    private const val REQUEST_CODE = 41_907
    private const val SHORT_TIMEOUT_MS = 10_000L

    @Volatile var state: ShizukuState = ShizukuState.STOPPED
        private set
    @Volatile private var remote: IShizukuShellService? = null
    @Volatile private var initialized = false
    @Volatile private var bindingGeneration = 0L
    @Volatile private var activeConnection: ServiceConnection? = null
    private lateinit var appContext: Context
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "hapaneld-shizuku-client").apply { isDaemon = true }
    }

    private val args: Shizuku.UserServiceArgs
        get() = Shizuku.UserServiceArgs(ComponentName(appContext, ShizukuShellService::class.java))
            .tag("hapaneld-shell-v2")
            .version(BuildConfig.VERSION_CODE)
            .processNameSuffix("shell")
            .daemon(false)

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener { clearBinding(managerIdleState()) }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode != REQUEST_CODE) return@OnRequestPermissionResultListener
        if (grantResult == PackageManager.PERMISSION_GRANTED) refresh()
        else refresh()
    }

    @Synchronized fun initialize(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        initialized = true
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        refresh()
    }

    fun enable(context: Context) {
        initialize(context)
        ShizukuConsent.enable(appContext)
        refresh(requestPermission = true)
    }

    fun disable() {
        if (!initialized) return
        ShizukuConsent.disable(appContext)
        clearBinding(managerIdleState())
    }

    fun refresh(requestPermission: Boolean = false) {
        if (!initialized) return
        val managerStatus = ShizukuManagerIdentity.status(appContext)
        if (managerStatus != ShizukuManagerIdentity.Status.TRUSTED) {
            clearBinding(if (managerStatus == ShizukuManagerIdentity.Status.UNTRUSTED) {
                ShizukuState.MANAGER_UNTRUSTED
            } else {
                ShizukuState.MANAGER_MISSING
            })
            return
        }
        if (!ShizukuConsent.enabled(appContext)) {
            clearBinding(ShizukuState.STOPPED)
            return
        }
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            clearBinding(ShizukuState.STOPPED)
            return
        }
        val granted = runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
            .getOrDefault(false)
        if (!granted) {
            val rationale = runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(true)
            clearBinding(
                if (rationale) ShizukuState.MANUAL_GRANT_REQUIRED else ShizukuState.PERMISSION_REQUIRED,
            )
            if (ShizukuPolicy.shouldRequestPermission(requestPermission, rationale)) {
                runCatching { Shizuku.requestPermission(REQUEST_CODE) }
                    .onFailure { Log.w(TAG, "permission request failed", it) }
            }
            return
        }
        bind()
    }

    private fun bind() {
        val generation: Long
        val connection: ServiceConnection
        synchronized(this) {
            if (remote != null || activeConnection != null) return
            generation = ++bindingGeneration
            connection = connectionFor(generation)
            activeConnection = connection
            state = ShizukuState.BINDING
        }
        runCatching { Shizuku.bindUserService(args, connection) }
            .onFailure {
                Log.w(TAG, "bind failed", it)
                rejectBinding(connection, generation, ShizukuState.ERROR)
            }
    }

    private fun connectionFor(generation: Long): ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val candidate = IShizukuShellService.Stub.asInterface(service)
            executor.execute {
                val identityUsable = runCatching {
                    ShizukuPolicy.usable(candidate.identityUid(), candidate.protocolVersion())
                }.getOrDefault(false)
                val managerTrusted = ShizukuManagerIdentity.status(appContext) ==
                    ShizukuManagerIdentity.Status.TRUSTED
                val accepted = synchronized(this@ShizukuBridge) {
                    ShizukuPolicy.canAcceptBinding(
                        callbackGeneration = generation,
                        currentGeneration = bindingGeneration,
                        connectionIsCurrent = activeConnection === this,
                        consentEnabled = ShizukuConsent.enabled(appContext),
                        managerTrusted = managerTrusted,
                        identityUsable = identityUsable,
                    ).also { allowed ->
                        if (allowed) {
                            remote = candidate
                            state = ShizukuState.READY
                        }
                    }
                }
                if (!accepted) {
                    rejectBinding(
                        this,
                        generation,
                        if (identityUsable) managerIdleState() else ShizukuState.INCOMPATIBLE,
                    )
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            synchronized(this@ShizukuBridge) {
                if (generation != bindingGeneration || activeConnection !== this) return
                bindingGeneration++
                activeConnection = null
                remote = null
                state = managerIdleState()
            }
        }
    }

    private fun clearBinding(nextState: ShizukuState) {
        val connection = synchronized(this) {
            bindingGeneration++
            remote = null
            state = nextState
            activeConnection.also { activeConnection = null }
        }
        if (connection != null) runCatching { Shizuku.unbindUserService(args, connection, true) }
    }

    private fun rejectBinding(connection: ServiceConnection, generation: Long, nextState: ShizukuState) {
        synchronized(this) {
            if (generation == bindingGeneration && activeConnection === connection) {
                bindingGeneration++
                activeConnection = null
                remote = null
                state = nextState
            }
        }
        runCatching { Shizuku.unbindUserService(args, connection, true) }
    }

    override fun available(): Boolean = remote != null && state == ShizukuState.READY
    fun managerRunning(): Boolean = initialized &&
        ShizukuManagerIdentity.status(appContext) == ShizukuManagerIdentity.Status.TRUSTED &&
        runCatching { Shizuku.pingBinder() }.getOrDefault(false)
    override fun uid(): Int? = call(SHORT_TIMEOUT_MS) { it.identityUid() }
    override fun screenshot(): ByteArray? {
        val fd = call(SHORT_TIMEOUT_MS) { it.captureScreenshot() } ?: return null
        val future = executor.submit(Callable {
            runCatching {
                ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (out.size() + read > ShizukuPolicy.MAX_SCREENSHOT_BYTES) return@Callable null
                        out.write(buffer, 0, read)
                    }
                    out.toByteArray().takeUnless { it.isEmpty() }
                }
            }.getOrNull()
        })
        return try {
            future.get(ShizukuPolicy.clientDeadline(SHORT_TIMEOUT_MS), TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            runCatching { fd.close() }
            future.cancel(true)
            Log.w(TAG, "screenshot read failed", e)
            null
        }
    }

    override fun inputKey(keyCode: Int): Boolean =
        ShizukuPolicy.validKeyCode(keyCode) && call(SHORT_TIMEOUT_MS) { it.inputKey(keyCode) } == true
    override fun tap(x: Int, y: Int): Boolean =
        ShizukuPolicy.validCoordinate(x) && ShizukuPolicy.validCoordinate(y) &&
            call(SHORT_TIMEOUT_MS) { it.inputTap(x, y) } == true
    override fun density(): String? = call(SHORT_TIMEOUT_MS) { it.readDensity() }
    override fun setDensity(dpi: Int): Boolean =
        ShizukuPolicy.validDensity(dpi) && call(SHORT_TIMEOUT_MS) { it.setDensity(dpi) } == true
    override fun resetDensity(): Boolean = call(SHORT_TIMEOUT_MS) { it.resetDensity() } == true
    override fun fontScale(): String? = call(SHORT_TIMEOUT_MS) { it.readFontScale() }
    override fun setFontScale(scale: Float): Boolean =
        ShizukuPolicy.validFontScale(scale) && call(SHORT_TIMEOUT_MS) { it.setFontScale(scale) } == true
    override fun resetFontScale(): Boolean = call(SHORT_TIMEOUT_MS) { it.resetFontScale() } == true

    override fun installApk(apk: File, allowDowngrade: Boolean, timeoutMs: Long): String? {
        if (!ShizukuPolicy.validApkLength(apk.length())) return null
        val serviceDeadline = ShizukuPolicy.installServiceDeadline(timeoutMs) ?: return null
        return call(serviceDeadline) { service ->
            ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                service.installApk(fd, apk.length(), allowDowngrade, serviceDeadline)
            }
        }
    }

    private fun managerIdleState(): ShizukuState {
        if (!initialized) return ShizukuState.STOPPED
        return when (ShizukuManagerIdentity.status(appContext)) {
            ShizukuManagerIdentity.Status.TRUSTED -> ShizukuState.STOPPED
            ShizukuManagerIdentity.Status.UNTRUSTED -> ShizukuState.MANAGER_UNTRUSTED
            ShizukuManagerIdentity.Status.MISSING -> ShizukuState.MANAGER_MISSING
        }
    }

    private fun <T> call(timeoutMs: Long, block: (IShizukuShellService) -> T): T? {
        val service = remote ?: return null
        val future = executor.submit(Callable { block(service) })
        return try {
            future.get(ShizukuPolicy.clientDeadline(timeoutMs), TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            future.cancel(true)
            Log.w(TAG, "operation failed", e)
            null
        }
    }
}
