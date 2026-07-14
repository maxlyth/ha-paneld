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
    private lateinit var appContext: Context
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "hapaneld-shizuku-client").apply { isDaemon = true }
    }

    private val args: Shizuku.UserServiceArgs
        get() = Shizuku.UserServiceArgs(ComponentName(appContext, ShizukuShellService::class.java))
            .tag("hapaneld-shell-v1")
            .version(BuildConfig.VERSION_CODE)
            .processNameSuffix("shell")
            .daemon(false)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val candidate = IShizukuShellService.Stub.asInterface(service)
            executor.execute {
                val accepted = runCatching {
                    ShizukuPolicy.usable(candidate.identityUid(), candidate.protocolVersion())
                }.getOrDefault(false)
                if (accepted) {
                    remote = candidate
                    state = ShizukuState.READY
                } else {
                    remote = null
                    state = ShizukuState.INCOMPATIBLE
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            remote = null
            state = managerIdleState()
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener {
        remote = null
        state = managerIdleState()
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode != REQUEST_CODE) return@OnRequestPermissionResultListener
        if (grantResult == PackageManager.PERMISSION_GRANTED) bind()
        else state = ShizukuState.PERMISSION_REQUIRED
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

    fun enable(context: Context, managed: Boolean = false) {
        initialize(context)
        ShizukuConsent.enable(appContext, managed)
        refresh(requestPermission = true)
    }

    fun disable() {
        if (!initialized) return
        ShizukuConsent.disable(appContext)
        remote = null
        runCatching { Shizuku.unbindUserService(args, connection, true) }
        state = managerIdleState()
    }

    fun refresh(requestPermission: Boolean = false) {
        if (!initialized) return
        val managerStatus = ShizukuManagerIdentity.status(appContext)
        if (managerStatus != ShizukuManagerIdentity.Status.TRUSTED) {
            state = if (managerStatus == ShizukuManagerIdentity.Status.UNTRUSTED) {
                ShizukuState.MANAGER_UNTRUSTED
            } else {
                ShizukuState.MANAGER_MISSING
            }
            remote = null
            return
        }
        if (!ShizukuConsent.enabled(appContext)) {
            state = ShizukuState.STOPPED
            remote = null
            return
        }
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            state = ShizukuState.STOPPED
            remote = null
            return
        }
        val granted = runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
            .getOrDefault(false)
        if (!granted) {
            state = ShizukuState.PERMISSION_REQUIRED
            if (requestPermission && !runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(true)) {
                runCatching { Shizuku.requestPermission(REQUEST_CODE) }
            }
            return
        }
        bind()
    }

    private fun bind() {
        if (remote != null) return
        state = ShizukuState.BINDING
        runCatching { Shizuku.bindUserService(args, connection) }
            .onFailure {
                Log.w(TAG, "bind failed", it)
                state = ShizukuState.ERROR
            }
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
            future.get(SHORT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
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
        return call(timeoutMs) { service ->
            ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                service.installApk(fd, apk.length(), allowDowngrade)
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
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            future.cancel(true)
            Log.w(TAG, "operation failed", e)
            null
        }
    }
}
