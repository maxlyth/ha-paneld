package io.github.maxlyth.hapaneld.control

import android.content.Context
import io.github.maxlyth.hapaneld.device.DeviceProfile
import io.github.maxlyth.hapaneld.util.HelperClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Thread Mesh Router provisioning for panels that carry a Silicon Labs EFR32 radio coprocessor.
 * Currently only the NSPanel Pro family (Rockchip PX30, Android 8.1) qualifies — it has an
 * EFR32MG21 on `/dev/ttyS5` running factory Zigbee NCP (EZSP) firmware managed by the
 * Sonoff/CoolKit `zgateway` stack in `/vendor/bin/siliconlabs_host`.
 *
 * **Flash mechanism** (confirmed via live panel analysis 2026-06-25):
 * zgateway checks for the file `/data/vendor/siliconlabs_host/ota-files/device_ota.zigbee` every
 * time it starts. If present, it flashes the file to the EFR32 via the EZSP standalone bootloader
 * + XMODEM, then removes the file on success. This is the vendor's own NCP firmware-update path
 * (triggered here by dropping an OpenThread NCP `.gbl` image at that path and starting zgateway).
 *
 * **Routing**: panels with `appCanSu = true` (NSPanel Pro) drive the flash via [Su] directly;
 * daemon-only panels use the helper's `THREAD_FLASH` verb via [HelperClient.sendLong].
 *
 * **Firmware**: the `.gbl` image must be placed at `assets/firmware/efr32mg21-thread-ncp.gbl`
 * in the APK. Obtain the correct image from Silicon Labs for the EFR32MG21 variant (read the
 * exact part from the Gecko Bootloader prompt by entering bootloader mode via the EZSP
 * `ezspLaunchStandaloneBootloader` command on `/dev/ttyS5`).
 */
class ThreadController(private val profile: DeviceProfile = DeviceProfile.detect()) {

    private val canSu: Boolean   get() = profile.appCanSu
    private val dir: String?     get() = profile.zigbeeGatewayDir

    private val OTA_FILE     = "/data/vendor/siliconlabs_host/ota-files/device_ota.zigbee"
    private val STATE_MARKER = "/data/vendor/siliconlabs_host/.thread-provisioned"
    private val ASSET_PATH   = "firmware/efr32mg21-thread-ncp.gbl"

    enum class EfrState { ZIGBEE_NCP, THREAD_NCP, NONE }

    /** True when this panel has an EFR32 radio (and therefore the Thread flash path is meaningful). */
    fun present(): Boolean = dir != null

    /** Whether the firmware asset is bundled in this APK build. The actual `.gbl` must exist at
     *  `assets/firmware/efr32mg21-thread-ncp.gbl` — without it, [flash] will fail immediately. */
    fun firmwareAvailable(context: Context): Boolean =
        runCatching { context.assets.open(ASSET_PATH).use { true } }.getOrDefault(false)

    /** One-line status for the info page: "Thread NCP · provisioned" / "EZSP · Zigbee NCP (factory)" / "none". */
    fun statusText(): String = when (status()) {
        EfrState.THREAD_NCP -> "Thread NCP · provisioned"
        EfrState.ZIGBEE_NCP -> "EZSP · Zigbee NCP (factory)"
        EfrState.NONE       -> "none"
    }

    /** Current EFR32 state. On `appCanSu` panels this probes via root shell; on daemon panels via
     *  the helper's `THREAD_STATUS` verb. Returns [EfrState.NONE] when no EFR32 is present. */
    fun status(): EfrState {
        if (dir == null) return EfrState.NONE
        return if (canSu) statusViaSu() else statusViaDaemon()
    }

    private fun statusViaSu(): EfrState {
        val provisioned = Su.runOutput("test -f '$STATE_MARKER' && echo yes")?.trim() == "yes"
        if (provisioned) return EfrState.THREAD_NCP
        val gatewayPresent = Su.runOutput("test -f '$dir/zgateway' && echo yes")?.trim() == "yes"
        return if (gatewayPresent) EfrState.ZIGBEE_NCP else EfrState.NONE
    }

    private fun statusViaDaemon(): EfrState = when (HelperClient.send("THREAD_STATUS")?.trim()) {
        "THREAD" -> EfrState.THREAD_NCP
        "EZSP"   -> EfrState.ZIGBEE_NCP
        else     -> EfrState.NONE
    }

    /**
     * Flash the EFR32 with the bundled OpenThread NCP firmware, replacing the factory Zigbee NCP.
     *
     * This takes 60–90 seconds (XMODEM at 115200 baud). [onProgress] receives human-readable status
     * strings during the operation. Must be called from a coroutine (suspends during the XMODEM poll
     * loop without blocking the calling thread).
     *
     * Returns [Result.success] when the EFR32 is running Thread NCP firmware; [Result.failure] with
     * a descriptive message on any error. The panel's Zigbee stack is left stopped either way — the
     * two roles are mutually exclusive on the same radio.
     */
    suspend fun flash(context: Context, onProgress: (String) -> Unit = {}): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (dir == null) return@withContext Result.failure(Exception("no EFR32 on this panel"))
            val gbl = runCatching { extractFirmware(context) }.getOrElse {
                return@withContext Result.failure(Exception("firmware asset not found: $ASSET_PATH"))
            }
            try {
                if (canSu) flashViaSu(gbl, onProgress)
                else flashViaDaemon(gbl, onProgress)
            } finally {
                gbl.delete()
            }
        }

    private fun extractFirmware(context: Context): File {
        val dest = File(context.cacheDir, "efr32-thread-ncp.gbl")
        context.assets.open(ASSET_PATH).use { src -> dest.outputStream().use { src.copyTo(it) } }
        return dest
    }

    private suspend fun flashViaSu(gbl: File, onProgress: (String) -> Unit): Result<Unit> {
        onProgress("Stopping Zigbee stack…")
        ZigbeeController(profile).disable()

        onProgress("Staging firmware…")
        Su.run("mkdir -p /data/vendor/siliconlabs_host/ota-files")
        if (!Su.run("cp '${gbl.absolutePath}' $OTA_FILE && chmod 600 $OTA_FILE"))
            return Result.failure(Exception("failed to copy firmware to OTA staging path"))

        onProgress("Starting flash (60–90 s)…")
        Su.run("cd '$dir' && LD_LIBRARY_PATH='$dir' ./zgateway >>/data/vendor/siliconlabs_host/zgateway.log 2>&1 &")

        val deadline = System.currentTimeMillis() + 90_000L
        while (System.currentTimeMillis() < deadline) {
            delay(2_000)
            val fileGone = Su.runOutput("test -f '$OTA_FILE' && echo yes")?.trim() != "yes"
            if (fileGone) {
                Su.run("killall zgateway 2>/dev/null; killall mosquitto 2>/dev/null")
                Su.run("touch '$STATE_MARKER'")
                onProgress("Done — EFR32 is now running Thread NCP firmware.")
                return Result.success(Unit)
            }
            val elapsed = (90_000L - (deadline - System.currentTimeMillis())) / 1000
            onProgress("Flashing… ${elapsed}s")
        }

        Su.run("killall zgateway 2>/dev/null")
        Su.run("rm -f '$OTA_FILE'")
        return Result.failure(Exception("flash timed out after 90 s — EFR32 state unknown"))
    }

    private fun flashViaDaemon(gbl: File, onProgress: (String) -> Unit): Result<Unit> {
        // Copy firmware to a path the helper can read (app's own cache dir is accessible to root)
        onProgress("Starting flash via helper daemon (60–90 s)…")
        val reply = HelperClient.sendLong("THREAD_FLASH ${gbl.absolutePath}", timeoutMs = 120_000)
        return when (reply) {
            "OK"         -> { onProgress("Done."); Result.success(Unit) }
            "ERR:path"   -> Result.failure(Exception("helper rejected firmware path"))
            "ERR:flash"  -> Result.failure(Exception("flash timed out on device"))
            null         -> Result.failure(Exception("helper daemon not reachable"))
            else         -> Result.failure(Exception("unexpected reply: $reply"))
        }
    }
}
