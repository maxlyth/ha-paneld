package io.github.maxlyth.hapaneld.control

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import io.github.maxlyth.hapaneld.device.DeviceProfile
import io.github.maxlyth.hapaneld.util.HelperClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

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
 * **Commission mechanism**: after flash, the helper's `THREAD_COMMISSION` verb is called with the
 * Active Operational Dataset fetched from the HA OTBR REST API (`/node/dataset/active`). The helper
 * writes the dataset to the NCP via Spinel/HDLC-Lite over `/dev/ttyS5` and starts the Thread stack.
 * The radio then joins the Thread mesh as a router autonomously.
 *
 * **Routing**: panels with `appCanSu = true` (NSPanel Pro) drive the XMODEM flash via [Su] directly;
 * all Spinel operations go through the helper daemon regardless of su availability.
 *
 * **Firmware**: the `.gbl` image must be placed at `assets/firmware/efr32mg21-thread-ncp.gbl`
 * in the APK. Obtain the correct image from Silicon Labs for the EFR32MG21 variant (read the
 * exact part from the Gecko Bootloader prompt by entering bootloader mode via the EZSP
 * `ezspLaunchStandaloneBootloader` command on `/dev/ttyS5`).
 */
class ThreadController(private val profile: DeviceProfile = DeviceProfile.detect()) {

    private val canSu: Boolean   get() = profile.appCanSu
    private val dir: String?     get() = profile.zigbeeGatewayDir

    private val OTA_FILE      = "/data/vendor/siliconlabs_host/ota-files/device_ota.zigbee"
    private val STATE_MARKER  = "/data/vendor/siliconlabs_host/.thread-provisioned"
    private val JOINED_MARKER = "/data/vendor/siliconlabs_host/.thread-joined"
    private val ASSET_PATH    = "firmware/efr32mg21-thread-ncp.gbl"

    enum class EfrState { ZIGBEE_NCP, THREAD_NCP, THREAD_NCP_JOINED, NONE }

    /** True when this panel has an EFR32 radio (and therefore the Thread flash path is meaningful). */
    fun present(): Boolean = dir != null

    /** Whether the firmware asset is bundled in this APK build. The actual `.gbl` must exist at
     *  `assets/firmware/efr32mg21-thread-ncp.gbl` — without it, [flash] will fail immediately. */
    fun firmwareAvailable(context: Context): Boolean =
        runCatching { context.assets.open(ASSET_PATH).use { true } }.getOrDefault(false)

    /** One-line status for the info page. */
    fun statusText(): String = when (status()) {
        EfrState.THREAD_NCP_JOINED -> "Thread NCP · mesh member"
        EfrState.THREAD_NCP        -> "Thread NCP · not yet commissioned"
        EfrState.ZIGBEE_NCP        -> "EZSP · Zigbee NCP (factory)"
        EfrState.NONE              -> "none"
    }

    /** Current EFR32 state. On `appCanSu` panels this probes via root shell; on daemon panels via
     *  the helper's `THREAD_STATUS` verb. Returns [EfrState.NONE] when no EFR32 is present. */
    fun status(): EfrState {
        if (dir == null) return EfrState.NONE
        return if (canSu) statusViaSu() else statusViaDaemon()
    }

    private fun statusViaSu(): EfrState {
        if (Su.runOutput("test -f '$JOINED_MARKER' && echo yes")?.trim() == "yes") return EfrState.THREAD_NCP_JOINED
        if (Su.runOutput("test -f '$STATE_MARKER' && echo yes")?.trim() == "yes")  return EfrState.THREAD_NCP
        val gatewayPresent = Su.runOutput("test -f '$dir/zgateway' && echo yes")?.trim() == "yes"
        return if (gatewayPresent) EfrState.ZIGBEE_NCP else EfrState.NONE
    }

    private fun statusViaDaemon(): EfrState = when (HelperClient.send("THREAD_STATUS")?.trim()) {
        "JOINED" -> EfrState.THREAD_NCP_JOINED
        "THREAD" -> EfrState.THREAD_NCP
        "EZSP"   -> EfrState.ZIGBEE_NCP
        else     -> EfrState.NONE
    }

    /**
     * Flash the EFR32 with the bundled OpenThread NCP firmware, then commission it onto the Thread
     * network via the OTBR REST API at [otbrUrl]. Both steps are zero-config: the flash uses the
     * vendor zgateway trigger mechanism and the commission fetches the Active Operational Dataset
     * from HA's OTBR add-on automatically.
     *
     * The XMODEM flash takes 60–90 seconds; Spinel commission takes ~10 s. [onProgress] receives
     * human-readable status strings throughout. Must be called from a coroutine.
     *
     * Returns [Result.success] when the EFR32 is commissioned as a Thread mesh router; [Result.failure]
     * with a descriptive message on any error. If [otbrUrl] is blank, commission is skipped (the radio
     * ends up in [EfrState.THREAD_NCP] — flashed but not yet on the mesh).
     */
    /**
     * Commission an already-flashed Thread NCP radio onto the mesh. Skips the GBL flash step.
     * Use when the EFR32 is already in [EfrState.THREAD_NCP] but not yet joined.
     */
    suspend fun commissionOnly(context: Context, onProgress: (String) -> Unit = {}): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (dir == null) return@withContext Result.failure(Exception("no EFR32 on this panel"))
            if (status() == EfrState.ZIGBEE_NCP)
                return@withContext Result.failure(Exception("EFR32 still running Zigbee NCP — flash first"))
            onProgress("Discovering Thread border router on the LAN…")
            val otbrUrl = discoverOtbrUrl(context)
            if (otbrUrl == null) {
                onProgress("No border router found via mDNS.")
                return@withContext Result.failure(Exception("no OTBR discovered on the LAN"))
            }
            onProgress("Found OTBR at $otbrUrl")
            commission(otbrUrl, onProgress)
        }

    suspend fun flash(context: Context, otbrUrl: String = "", onProgress: (String) -> Unit = {}): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (dir == null) return@withContext Result.failure(Exception("no EFR32 on this panel"))
            // Skip re-flashing if the GBL is already installed; go straight to commissioning.
            if (status() != EfrState.ZIGBEE_NCP) {
                onProgress("EFR32 already running Thread NCP — skipping flash, commissioning only.")
                return@withContext commissionOnly(context, onProgress)
            }
            val gbl = runCatching { extractFirmware(context) }.getOrElse {
                return@withContext Result.failure(Exception("firmware asset not found: $ASSET_PATH"))
            }
            try {
                val flashResult = if (canSu) flashViaSu(gbl, onProgress) else flashViaDaemon(gbl, onProgress)
                if (flashResult.isFailure) return@withContext flashResult
                // Resolve OTBR URL: prefer explicit arg, then mDNS discovery, then skip commission.
                val resolved = when {
                    otbrUrl.isNotBlank() -> otbrUrl
                    else -> {
                        onProgress("Discovering Thread border router on the LAN…")
                        discoverOtbrUrl(context).also { found ->
                            if (found != null) onProgress("Found OTBR at $found")
                            else onProgress("No border router found — skipping commissioning (flash still succeeded).")
                        }
                    }
                }
                if (resolved != null) commission(resolved, onProgress) else flashResult
            } finally {
                gbl.delete()
            }
        }

    /**
     * Discover all `_meshcop._udp` / `_meshcop._tcp` hosts on the LAN within [timeoutMs].
     * Returns base URLs like `http://<ip>:8080` for every resolved service. Note: Apple TVs and
     * other HomeKit devices also advertise `_meshcop._udp` but have no OpenThread REST API —
     * callers must probe each candidate with [fetchDatasetHex] and use the first that responds.
     */
    private suspend fun discoverOtbrCandidates(context: Context, timeoutMs: Long = 8_000L): List<String> {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return emptyList()
        val found = mutableListOf<String>()
        val listeners = mutableListOf<NsdManager.DiscoveryListener>()

        fun addCandidate(addr: String) {
            val url = "http://$addr:8080"
            synchronized(found) { if (url !in found) found += url }
        }

        fun makeListener(): NsdManager.DiscoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(st: String, err: Int) {}
            override fun onStopDiscoveryFailed(st: String, err: Int) {}
            override fun onDiscoveryStarted(st: String) {}
            override fun onDiscoveryStopped(st: String) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                val resolveListener = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo, err: Int) {}
                    override fun onServiceResolved(i: NsdServiceInfo) {
                        val addr = if (Build.VERSION.SDK_INT >= 34)
                            i.hostAddresses.firstOrNull()?.hostAddress
                        else
                            @Suppress("DEPRECATION") i.host?.hostAddress
                        if (addr != null) addCandidate(addr)
                    }
                }
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT >= 34)
                    nsd.resolveService(info, context.mainExecutor, resolveListener)
                else
                    nsd.resolveService(info, resolveListener)
            }
        }

        listOf("_meshcop._udp", "_meshcop._tcp").forEach { svcType ->
            val l = makeListener()
            listeners += l
            runCatching { nsd.discoverServices(svcType, NsdManager.PROTOCOL_DNS_SD, l) }
        }
        delay(timeoutMs)
        listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
        return synchronized(found) { found.toList() }
    }

    /**
     * Find the first mDNS-discovered host that actually serves a valid Thread dataset.
     * Filters out Apple TVs and other non-OTBR devices that advertise `_meshcop` but have no API.
     */
    private suspend fun discoverOtbrUrl(context: Context): String? {
        val candidates = discoverOtbrCandidates(context)
        Log.d("ThreadController", "mDNS candidates: $candidates")
        return candidates.firstOrNull { url ->
            runCatching { fetchDatasetHex(url) }.isSuccess
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
                onProgress("Flash complete — EFR32 running Thread NCP firmware.")
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
        onProgress("Starting flash via helper daemon (60–90 s)…")
        val reply = HelperClient.sendLong("THREAD_FLASH ${gbl.absolutePath}", timeoutMs = 120_000)
        return when (reply) {
            "OK"         -> { onProgress("Flash complete — EFR32 running Thread NCP firmware."); Result.success(Unit) }
            "ERR:path"   -> Result.failure(Exception("helper rejected firmware path"))
            "ERR:flash"  -> Result.failure(Exception("flash timed out on device"))
            null         -> Result.failure(Exception("helper daemon not reachable"))
            else         -> Result.failure(Exception("unexpected reply: $reply"))
        }
    }

    /** Fetch the Active Operational Dataset from the OTBR REST API and commission the NCP. */
    private fun commission(otbrUrl: String, onProgress: (String) -> Unit): Result<Unit> {
        onProgress("Fetching Thread network credentials from OTBR…")
        val hex = runCatching { fetchDatasetHex(otbrUrl) }.getOrElse { e ->
            return Result.failure(Exception("could not fetch OTBR dataset: ${e.message}"))
        }

        onProgress("Commissioning Thread radio (${hex.length / 2} B dataset)…")
        val reply = HelperClient.sendLong("THREAD_COMMISSION $hex", timeoutMs = 30_000)
        return when (reply) {
            "OK"           -> { onProgress("Thread radio joined the mesh."); Result.success(Unit) }
            "ERR:hex"      -> Result.failure(Exception("OTBR returned an invalid dataset"))
            "ERR:spinel"   -> Result.failure(Exception("Spinel communication failed — is Thread firmware running?"))
            null           -> Result.failure(Exception("helper daemon not reachable"))
            else           -> Result.failure(Exception("unexpected reply: $reply"))
        }
    }

    /**
     * GET <otbrUrl>/node/dataset/active → JSON {"ActiveDataset":"<hex>"} → hex string.
     * Probes the alternate port (8081↔8080) on failure — the HA OTBR add-on v3+ uses 8081
     * for the REST API while older installs / alternative stacks use 8080.
     */
    private fun fetchDatasetHex(otbrUrl: String): String {
        return runCatching { fetchDatasetHexAt(otbrUrl) }.recover { first ->
            val alt = when {
                otbrUrl.endsWith(":8081") -> otbrUrl.dropLast(4) + "8080"
                otbrUrl.endsWith(":8080") -> otbrUrl.dropLast(4) + "8081"
                else -> throw first
            }
            fetchDatasetHexAt(alt)
        }.getOrThrow()
    }

    private fun fetchDatasetHexAt(otbrUrl: String): String {
        val conn = URL("${otbrUrl.trimEnd('/')}/node/dataset/active").openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout    = 5_000
        // text/plain → SLZB-Ultima3 and vanilla OpenThread REST return raw hex TLV.
        // HA OTBR add-on returns {"ActiveDataset":"<hex>"} JSON regardless of Accept,
        // so we check both formats in the response parser below.
        // Do NOT include application/json: the SLZB prefers it over text/plain and
        // returns the expanded decoded object (no ActiveDataset field) instead of the TLV.
        conn.setRequestProperty("Accept", "text/plain")
        val body = try {
            conn.inputStream.bufferedReader().readText().trim()
        } finally {
            conn.disconnect()
        }
        val hex = when {
            body.matches(Regex("[0-9a-fA-F]+")) -> body          // raw hex (SLZB-Ultima3 / vanilla OTBR)
            body.startsWith("{") -> JSONObject(body)              // JSON wrapper (HA add-on)
                .optString("ActiveDataset").trim()
                .takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("OTBR JSON has no ActiveDataset field")
            else -> throw IllegalStateException("unrecognised OTBR response: ${body.take(80)}")
        }
        if (hex.isBlank()) throw IllegalStateException("OTBR returned an empty dataset")
        return hex
    }
}
