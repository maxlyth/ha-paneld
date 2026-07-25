package io.github.maxlyth.hapaneld

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import io.github.maxlyth.hapaneld.util.Json
import io.github.maxlyth.hapaneld.util.MonotonicDeadline
import io.github.maxlyth.hapaneld.util.RetirableMutationGate
import io.github.maxlyth.hapaneld.util.interruptAndJoin
import io.github.maxlyth.hapaneld.util.localIpv4
import io.github.maxlyth.hapaneld.util.shutdownNowAndAwait
import io.github.maxlyth.hapaneld.metrics.FeatureCostOperation
import io.github.maxlyth.hapaneld.metrics.FeatureCostOutcome
import io.github.maxlyth.hapaneld.metrics.FeatureCosts
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/**
 * Advertises `_ha-paneld._tcp.local.` via JmDNS so HA's zeroconf discovery can auto-pair the
 * panel. JmDNS is used in preference to Android's NsdManager because NsdManager's TXT-record
 * handling is unreliable across the API 26→30 range the fleet spans. A WifiManager.MulticastLock
 * is held for the lifetime of the advertisement (panels otherwise drop multicast in doze).
 */
class MdnsAdvertiser(
    private val context: Context,
    private val config: Config,
    private val runtimePanelId: String = config.panelId,
    private val runtimeFriendlyName: String = config.friendlyName,
    private val runtimeHttpPort: Int = config.httpPort,
) {
    private val ownerGate = RetirableMutationGate()
    private var jmdns: JmDNS? = null
    private var lock: WifiManager.MulticastLock? = null
    @Volatile private var browsing = false
    @Volatile private var refreshThread: Thread? = null
    @Volatile private var resolver: ThreadPoolExecutor? = null
    private val browseGeneration = AtomicLong()
    private val retirement = AtomicReference<CompletableFuture<Boolean>?>()
    // The LAN address JmDNS is actually bound to. A panel's IPv4 arrives asynchronously (DHCP) and can
    // change later, so this is compared against the live address on every [start] to catch a stale bind.
    @Volatile private var boundIp: String? = null
    // Set when the refresh sweep proves the responder died under a still-non-null JmDNS (see the sweep).
    @Volatile private var responderDead = false
    @Volatile private var selfMissedSweeps = 0
    private val recovering = AtomicBoolean(false)

    private fun newResolver() = ThreadPoolExecutor(
        RESOLVE_WORKERS, RESOLVE_WORKERS, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(RESOLVE_QUEUE),
        { task -> Thread(task, "mdns-resolve").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    // Live roster of discovered ha-paneld panels (keyed by mDNS instance name = panel_id), maintained by a
    // persistent [peerListener]. Reads are cheap + non-blocking and — unlike a one-shot dns.list, which
    // returns only what's in this JmDNS's cache within a short window (observed to miss panels a peer sees) —
    // this converges to the full fleet over the listener's periodic queries and self-prunes on service-removed.
    private val peerMap = ConcurrentHashMap<String, Peer>()

    private val peerListener = object : ServiceListener {
        override fun serviceAdded(event: ServiceEvent) {
            // Resolve OFF the JmDNS packet thread (getServiceInfo blocks) so we never stall mDNS processing.
            val dns = jmdns ?: return
            val type = event.type
            val name = event.name
            val executor = resolver ?: return
            try {
                executor.execute {
                    runCatching { dns.getServiceInfo(type, name, RESOLVE_MS) }.getOrNull()?.let {
                        if (browsing && jmdns === dns) record(it, name)
                    }
                }
            } catch (_: RejectedExecutionException) {
                Log.w(TAG, "mDNS resolve queue saturated; dropping $name")
            }
        }

        override fun serviceResolved(event: ServiceEvent) {
            event.info?.let { record(it, event.name) } // some JmDNS paths deliver the resolved info directly
        }

        override fun serviceRemoved(event: ServiceEvent) {
            peerMap.remove(event.name)
        }
    }

    /** Fold a resolved mDNS record into the roster (needs a resolved IPv4 to be navigable). */
    private fun record(info: ServiceInfo, fallbackName: String) {
        if (!browsing) return
        val p = toPeer(
            instanceName = info.name ?: fallbackName,
            txtName = info.getPropertyString("name"),
            txtVer = info.getPropertyString("ver"),
            ipv4 = info.inet4Addresses?.firstOrNull()?.hostAddress,
            port = info.port,
            selfId = runtimePanelId,
            selfIp = localIpv4(),
        )
        if (p.panelId.isBlank() || p.ip == null) return
        // Don't DOWNGRADE a resolved friendly name back to the panel_id fallback: a later TXT-less resolve
        // (common right after a whole-fleet restart floods mDNS) must not clobber a good name. Otherwise store.
        val existing = peerMap[p.panelId]
        if (existing == null && peerMap.size >= MAX_PEERS) return
        val newHasName = p.name != p.panelId
        val oldHasName = existing != null && existing.name != existing.panelId
        if (existing == null || newHasName || !oldHasName) peerMap[p.panelId] = p
    }

    /**
     * Blocking network setup — call off the main thread.
     *
     * Safe (and cheap) to call repeatedly: it revalidates an existing advertisement instead of assuming
     * that a non-null JmDNS is a healthy one. A panel whose bind went stale or whose responder died is
     * torn down and rebuilt here, because nothing else in the process ever notices — the HTTP API keeps
     * serving the last known roster and the panel silently disappears from every other panel's switcher.
     */
    fun start() {
        ownerGate.runIfOpen(Unit) start@{
            val lanIp = localIpv4()
            if (lanIp == null) {
                // No LAN IPv4 yet — ha-paneld starts before DHCP completes on a cold boot. Binding now
                // would land on loopback: the panel would join the mDNS group on `lo`, advertise an
                // unreachable 127.0.0.1 and never see a peer. Defer; the network callback re-runs this.
                Log.i(TAG, "mDNS advertise deferred — no LAN IPv4 yet")
                return@start
            }
            if (jmdns != null) {
                // `browsing` false with a live JmDNS is the zombie a partially-drained stop() leaves
                // behind: still advertising, but record() no-ops so the roster can never change again.
                if (boundIp == lanIp && !responderDead && browsing) return@start // healthy — nothing to do
                Log.w(
                    TAG,
                    "mDNS unhealthy (bound=$boundIp lan=$lanIp dead=$responderDead browsing=$browsing) — rebuilding",
                )
                stopResources(MonotonicDeadline(OWNER_STOP_MS))
                // A teardown that could not drain leaves JmDNS owned by an in-flight call; retry later
                // rather than stacking a second responder on the same port.
                if (jmdns != null) return@start
            }
            if (resolver?.isShutdown != false) resolver = newResolver()
            try {
                val wifi = context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as WifiManager
                lock = wifi.createMulticastLock("ha-paneld-mdns").apply {
                    setReferenceCounted(true)
                    acquire()
                }
                val addr = InetAddress.getByName(lanIp)
                val dns = JmDNS.create(addr, runtimePanelId)
                val props = mapOf(
                    "ver" to Config.VERSION,
                    "caps" to "tts",
                    "path" to "/play",
                    // Friendly name so a peer's fleet switcher can label this panel nicely (falls back to the
                    // instance name = panel_id on older panels that don't advertise it). Additive TXT key.
                    "name" to runtimeFriendlyName.ifBlank { runtimePanelId },
                )
                val info = ServiceInfo.create(
                    Config.MDNS_SERVICE_TYPE,
                    runtimePanelId,
                    runtimeHttpPort,
                    0,
                    0,
                    props,
                )
                jmdns = dns
                boundIp = lanIp
                responderDead = false
                selfMissedSweeps = 0
                dns.registerService(info)
                // Start the persistent peer browse (powers the header switcher) — begins querying immediately
                // and keeps the roster fresh in the background, so a UI read is instant + complete.
                browsing = true
                runCatching { dns.addServiceListener(Config.MDNS_SERVICE_TYPE, peerListener) }
                // Periodic re-browse: dns.list resolves each service fully (incl. TXT), so this refreshes names
                // and fills any TXT missed during a congested (whole-fleet-restart) window. Merges with the
                // don't-downgrade rule in record(), so it only closes gaps and never flaps a good name.
                val generation = browseGeneration.incrementAndGet()
                lateinit var worker: Thread
                worker = Thread {
                    while (mdnsRunCurrent(browseGeneration, generation, browsing, jmdns === dns)) {
                        try { Thread.sleep(REFRESH_MS) } catch (e: InterruptedException) { break }
                        if (!mdnsRunCurrent(browseGeneration, generation, browsing, jmdns === dns)) break
                        val cost = FeatureCosts.registry.span(FeatureCostOperation.MDNS_PEER_REFRESH)
                        try {
                            val services = dns.list(Config.MDNS_SERVICE_TYPE, LIST_MS)?.toList().orEmpty()
                            cost.work(units = services.size.toLong())
                            if (!mdnsRunCurrent(browseGeneration, generation, browsing, jmdns === dns)) {
                                cost.outcome(FeatureCostOutcome.CANCELLED)
                                break
                            }
                            services.forEach { record(it, it.name ?: "") }
                            // Liveness. A live JmDNS always lists THIS panel's own advertisement — it is
                            // learned back over the same multicast path as any peer's. A JmDNS that was
                            // closed (or whose socket died) keeps the object non-null while list() goes
                            // empty, so without this the panel stays invisible to the whole fleet while
                            // /api/v1/peers happily serves the frozen roster. Require several consecutive
                            // misses so one congested sweep can't flap a healthy advertiser.
                            selfMissedSweeps = nextMissedSweeps(
                                selfMissedSweeps,
                                services.any { (it.name ?: "") == runtimePanelId },
                            )
                            if (selfMissedSweeps >= DEAD_SWEEPS) {
                                Log.w(TAG, "mDNS responder stopped listing this panel — rebuilding")
                                responderDead = true
                                requestRecovery()
                                break
                            }
                        } catch (failure: Exception) {
                            cost.outcome(FeatureCostOutcome.FAILURE)
                        } finally {
                            cost.close()
                        }
                    }
                    if (refreshThread === worker) refreshThread = null
                }.apply { isDaemon = true; name = "mdns-peer-refresh" }
                refreshThread = worker
                worker.start()
                Log.i(TAG, "advertising ${Config.MDNS_SERVICE_TYPE} as $runtimePanelId @ $addr:$runtimeHttpPort")
            } catch (e: Exception) {
                Log.w(TAG, "mDNS advertise failed", e)
                stopResources(MonotonicDeadline(OWNER_STOP_MS))
            }
        }
    }

    /** Stop the current advertisement while still allowing a later network-recovery restart. */
    fun stop() = ownerGate.runExclusive { stopResources(MonotonicDeadline(OWNER_STOP_MS)) }

    /**
     * Rebuild off the refresh thread. [stopResources] has to interrupt-and-join that thread, which
     * refuses a self-join — so the sweep that detects the death must hand the restart to someone else
     * or the teardown fails and the advertiser stays dead. Collapses to one in-flight recovery.
     */
    private fun requestRecovery() {
        if (!recovering.compareAndSet(false, true)) return
        Thread {
            try {
                runRecovery()
            } catch (failure: Throwable) {
                Log.w(TAG, "mDNS recovery failed", failure)
            } finally {
                recovering.set(false)
            }
        }.apply { isDaemon = true; name = "mdns-recover"; start() }
    }

    /** Retry the teardown+rebuild a few times: the detecting sweep has already exited, so a teardown that
     *  loses its drain race here would otherwise leave the responder dead until the next network event. */
    private fun runRecovery() {
        repeat(RECOVERY_ATTEMPTS) { attempt ->
            if (!ownerGate.isOpen()) return // retired — a late recovery must not resurrect this generation
            ownerGate.runExclusive { stopResources(MonotonicDeadline(OWNER_STOP_MS)) }
            start()
            if (health().advertising) return
            if (attempt < RECOVERY_ATTEMPTS - 1) {
                try {
                    Thread.sleep(RECOVERY_BACKOFF_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
        // Still down: health() now reports it, so the status banner shows the panel has left the fleet,
        // and the next network callback re-runs start().
        Log.w(TAG, "mDNS recovery gave up after $RECOVERY_ATTEMPTS attempts")
    }

    /** Advertiser health for the status banner — see [mdnsHealthWarning]. Never throws. */
    fun health(): MdnsHealth = MdnsHealth(
        advertising = jmdns != null && browsing,
        boundIp = boundIp,
        lanIp = localIpv4(),
        responderDead = responderDead,
    )

    /** Permanently close this runtime generation so a late recovery callback cannot resurrect it. */
    internal fun retire(deadline: MonotonicDeadline): CompletableFuture<Boolean> {
        ownerGate.closeAdmission()
        retirement.get()?.let { return it }
        val result = CompletableFuture<Boolean>()
        if (!retirement.compareAndSet(null, result)) return checkNotNull(retirement.get())
        Thread {
            try {
                result.complete(ownerGate.runExclusive { stopResources(deadline) })
            } catch (failure: Throwable) {
                result.completeExceptionally(failure)
            }
        }.apply {
            isDaemon = true
            name = "mdns-retire"
            start()
        }
        return result
    }

    private fun stopResources(deadline: MonotonicDeadline): Boolean {
        var complete = true
        browsing = false
        browseGeneration.incrementAndGet()
        val refresh = refreshThread
        val refreshDrained = refresh.interruptAndJoin(deadline)
        if (refreshDrained) refreshThread = null else complete = false
        val activeResolver = resolver
        val resolverDrained = activeResolver?.shutdownNowAndAwait(deadline) ?: true
        if (resolverDrained) resolver = null else complete = false
        // Do not dismantle JmDNS underneath an admitted list/resolve call. The terminal retirement fence
        // is already installed; a failed drain selects the process boundary instead of a successor.
        if (!complete || deadline.remainingMs() <= 0L) return false
        runCatching { jmdns?.removeServiceListener(Config.MDNS_SERVICE_TYPE, peerListener) }
            .onFailure { complete = false }
        peerMap.clear()
        runCatching { jmdns?.unregisterAllServices() }.onFailure { complete = false }
        runCatching { jmdns?.close() }.onFailure { complete = false }
        jmdns = null
        boundIp = null
        selfMissedSweeps = 0
        runCatching { lock?.release() }.onFailure { complete = false }
        lock = null
        return complete && deadline.remainingMs() > 0L
    }

    /**
     * Browse for Home Assistant's own zeroconf advertisement (`_home-assistant._tcp.local.`) and
     * return the IPv4 of one that actually has the MQTT port (1883) open — so on a LAN with more than
     * one HA instance we pick the one running a broker, and skip an HA whose broker lives elsewhere.
     * Falls back to the first HA host if none probe open. Used to default the MQTT broker to
     * `tcp://<ha-ip>:1883` when none is set. Blocking up to [timeoutMs]; call off the main thread.
     */
    fun discoverHaIp(timeoutMs: Long = 4000): String? = ownerGate.runIfOpen<String?>(null) {
        val dns = jmdns ?: return@runIfOpen null
        val ips = runCatching {
            dns.list("_home-assistant._tcp.local.", timeoutMs)
                ?.mapNotNull { it.inet4Addresses?.firstOrNull()?.hostAddress }?.distinct() ?: emptyList()
        }.getOrDefault(emptyList())
        val chosen = ips.firstOrNull { mqttPortOpen(it) } ?: ips.firstOrNull()
        Log.i(TAG, "HA mDNS discovery: hosts=$ips -> ${chosen ?: "none found"}")
        chosen
    }

    /**
     * HA's own advertised base URL (scheme+host+port) from its zeroconf TXT — `internal_url` preferred,
     * then `base_url`, then `external_url`. Authoritative for setups that don't use the default http :8123
     * (e.g. HA Core terminating TLS on :443, or behind a reverse proxy), so callers never have to guess a
     * port/scheme. Null if no HA service is found or it advertises no URL. Blocking; call off the main thread.
     */
    fun discoverHaBaseUrl(configuredBroker: String, timeoutMs: Long = 4000): String? =
        ownerGate.runIfOpen<String?>(null) {
        val dns = jmdns ?: return@runIfOpen null
        val brokerIps = brokerHostIps(configuredBroker) // all of the broker host's addresses (v4 AND v6) — see below
        val url = runCatching {
            val svcs = dns.list("_home-assistant._tcp.local.", timeoutMs)?.toList() ?: emptyList()
            // The panel's HA is the one hosting its MQTT broker. Match the configured broker by IP — against
            // ALL of the service's addresses (v4 + v6), since the broker host commonly resolves to a global
            // IPv6 first — so on a LAN with several HA instances (a primary + a secondary) we never pick the
            // wrong one. Else fall back to whichever HA has :1883 open; else give up rather than guess.
            val pick = svcs.firstOrNull { s ->
                s.inetAddresses?.any { (it.hostAddress?.substringBefore('%')) in brokerIps } == true
            } ?: if (configuredBroker.isBlank()) {
                // No explicit broker → auto-discover: the local HA that runs the broker (:1883 open).
                svcs.firstOrNull { s -> s.inet4Addresses?.firstOrNull()?.hostAddress?.let { mqttPortOpen(it) } == true }
            } else {
                // Explicit broker that matched no local HA (e.g. its HA is across a tunnel) → return null so
                // the caller's broker-HOST fallback handles it, rather than picking an unrelated local HA.
                null
            }
            pick?.let {
                val advertised = it.getPropertyString("internal_url") ?: it.getPropertyString("base_url")
                    ?: it.getPropertyString("external_url")
                val serviceIps = it.inetAddresses.orEmpty()
                    .mapNotNull { address -> address.hostAddress?.substringBefore('%') }
                    .toSet()
                safeAdvertisedHaUrl(advertised, serviceIps)
            }
        }.getOrNull()
        Log.i(TAG, "HA mDNS base url (broker=${brokerIps.joinToString(",").ifEmpty { "?" }}): ${url ?: "none"}")
        url
    }

    /**
     * Resolve the stable Home Assistant instance id advertised in zeroconf TXT. Identity is accepted only
     * when exactly one valid record matches the configured HA origin or one of [candidateUrls] returned by
     * that authenticated HA API. IP address, MQTT location and record order are deliberately irrelevant:
     * several HA instances can share a host or reverse proxy. A `<uuid>.local` candidate is also an exact
     * identity match because Home Assistant defines that hostname from the same advertised uuid.
     *
     * Blocking for at most the bounded JmDNS browse budget; call off the main thread. Malformed, ambiguous
     * and unmatched records all fail closed with null.
     */
    fun discoverHaInstanceUuid(candidateUrls: Collection<String> = emptyList(), timeoutMs: Long = 4000): String? =
        ownerGate.runIfOpen<String?>(null) {
        val dns = jmdns ?: return@runIfOpen null
        val candidates = buildList {
            config.haUrl.takeIf { it.isNotBlank() }?.let(::add)
            addAll(candidateUrls)
        }
        if (candidates.isEmpty()) return@runIfOpen null
        runCatching {
            val services = dns.list(HA_SERVICE_TYPE, timeoutMs.coerceIn(1L, MAX_HA_BROWSE_MS))
                ?.toList() ?: emptyList()
            // Truncating a crowded result could hide a second matching UUID. Likewise, silently dropping
            // a malformed record could turn an ambiguous browse into an apparently unique identity.
            if (services.size > MAX_HA_RECORDS) return@runCatching null
            val records = services.map { info ->
                parseHaTxtRecord(
                        uuid = info.getPropertyString("uuid"),
                        internalUrl = info.getPropertyString("internal_url"),
                        externalUrl = info.getPropertyString("external_url"),
                        baseUrl = info.getPropertyString("base_url"),
                        server = info.server,
                    ) ?: return@runCatching null
            }
            matchHaInstanceUuid(candidates, records)
        }.getOrNull()
    }

    /**
     * The LAN ha-paneld roster (`_ha-paneld._tcp.local.`) for the header panel switcher — a cheap,
     * non-blocking snapshot of [peerMap], which [peerListener] keeps converged + fresh in the background.
     * This panel's own advertised service is included and [Peer.self]-marked against this advertiser's
     * immutable runtime identity, so a newer config cannot relabel the retiring generation. Never throws.
     */
    fun browsePeers(): List<Peer> {
        val selfId = runtimePanelId
        val selfIp = localIpv4()
        val selfName = runtimeFriendlyName.ifBlank { selfId }
        return dedupePeers(
            peerMap.values.map {
                val self = it.panelId == selfId || (it.ip != null && it.ip == selfIp)
                // mDNS self-resolution can miss our OWN TXT (name), so a panel would show ITSELF as its
                // panel_id while peers show its friendly name — making the alphabetical menu order differ
                // between panels. Force this panel's authoritative friendly name for the self entry.
                if (self) it.copy(self = true, name = selfName) else it.copy(self = false)
            },
        )
    }

    /** All of the configured MQTT broker host's IP addresses (v4 + v6), to match against HA mDNS records. */
    private fun brokerHostIps(configuredBroker: String): Set<String> = runCatching {
        val host = configuredBroker.substringAfter("://").substringBefore(":").substringBefore("/").trim()
        if (host.isBlank()) emptySet()
        else java.net.InetAddress.getAllByName(host).mapNotNull { it.hostAddress?.substringBefore('%') }.toSet()
    }.getOrDefault(emptySet())

    /** True if TCP 1883 accepts a connection on [ip] within [timeoutMs] (the MQTT broker is there). */
    private fun mqttPortOpen(ip: String, port: Int = 1883, timeoutMs: Int = 600): Boolean =
        runCatching {
            java.net.Socket().use { it.connect(java.net.InetSocketAddress(ip, port), timeoutMs) }; true
        }.getOrDefault(false)

    companion object {
        private const val TAG = "ha-paneld/mdns"
        private const val RESOLVE_MS = 2500L // per-peer mDNS resolve budget (off the JmDNS thread)
        private const val RESOLVE_WORKERS = 2
        private const val RESOLVE_QUEUE = 32
        private const val MAX_PEERS = 64
        private const val REFRESH_MS = 60_000L // periodic re-browse interval (name refresh / gap fill)
        private const val DEAD_SWEEPS = 3 // consecutive self-less sweeps before declaring JmDNS dead
        private const val RECOVERY_ATTEMPTS = 3
        private const val RECOVERY_BACKOFF_MS = 5_000L
        private const val LIST_MS = 3000L // dns.list resolve budget per refresh sweep
        private const val OWNER_STOP_MS = 2_000L
        private const val HA_SERVICE_TYPE = "_home-assistant._tcp.local."
        private const val MAX_HA_BROWSE_MS = 5_000L
        private const val MAX_HA_RECORDS = 16
    }
}

/** A zeroconf TXT URL is untrusted input. Keep credentials on the service host that advertised it. */
internal fun safeAdvertisedHaUrl(raw: String?, serviceIps: Set<String>): String? {
    val uri = runCatching { java.net.URI(raw?.trim().orEmpty()) }.getOrNull() ?: return null
    if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null) return null
    val destinationIps = runCatching {
        java.net.InetAddress.getAllByName(uri.host).mapNotNull { it.hostAddress?.substringBefore('%') }.toSet()
    }.getOrDefault(emptySet())
    if (serviceIps.isEmpty() || destinationIps.intersect(serviceIps).isEmpty()) return null
    return uri.toString().trimEnd('/')
}

/** Observable state of the mDNS advertiser. [boundIp] is the address JmDNS is bound to (null when it is
 *  not advertising); [lanIp] is the panel's live LAN IPv4, also null before DHCP has handed one out. */
data class MdnsHealth(
    val advertising: Boolean,
    val boundIp: String?,
    val lanIp: String?,
    val responderDead: Boolean,
)

/**
 * Pure: the operator-facing warning for an unhealthy advertiser, or null when there is nothing to say.
 * A silently dead responder is the whole failure class this guards — the panel keeps working over HTTP
 * and keeps serving its last roster, so without a banner nobody notices it left the fleet switcher.
 */
internal fun mdnsHealthWarning(health: MdnsHealth): String? = when {
    // No LAN address at all: the panel has bigger problems and every other card already says so.
    health.lanIp == null -> null
    !health.advertising || health.responderDead ->
        "⚠ <b>Panel discovery (mDNS) is not running</b> — this panel is missing from other panels' " +
            "switcher menus, and Home Assistant cannot auto-discover it. It retries automatically."
    health.boundIp != health.lanIp ->
        "⚠ <b>Panel discovery (mDNS) is advertising a stale address</b> (${health.boundIp}, now " +
            "${health.lanIp}) — other panels cannot reach this one from their switcher until it rebinds."
    else -> null
}

/** Pure: consecutive refresh sweeps that did not list this panel's own advertisement. */
internal fun nextMissedSweeps(previous: Int, sawSelf: Boolean): Int = if (sawSelf) 0 else previous + 1

internal fun mdnsRunCurrent(
    generation: AtomicLong,
    expected: Long,
    browsing: Boolean,
    ownsResolver: Boolean,
): Boolean = browsing && ownsResolver && generation.get() == expected

/** Validated identity-bearing subset of one resolved Home Assistant zeroconf record. */
internal data class HaTxtRecord(
    val uuid: String,
    val origins: Set<String>,
    val serverHost: String?,
)

/** Pure TXT parser. A malformed UUID or non-empty advertised URL invalidates the record. */
internal fun parseHaTxtRecord(
    uuid: String?,
    internalUrl: String?,
    externalUrl: String?,
    baseUrl: String?,
    server: String?,
): HaTxtRecord? {
    val id = uuid?.trim()?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{32}")) } ?: return null
    val rawUrls = listOf(internalUrl, externalUrl, baseUrl).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
    val origins = rawUrls.map { canonicalHaOrigin(it) ?: return null }.toSet()
    val host = server?.trim()?.trimEnd('.')?.lowercase()?.takeIf(String::isNotEmpty)
    return HaTxtRecord(id, origins, host)
}

/**
 * Pure fail-closed identity matcher. Duplicate advertisements for one UUID are harmless; two distinct
 * matching UUIDs are ambiguous. URL paths are intentionally discarded because authenticated `/api/config`
 * candidates and configured dashboard paths still identify the same HTTP origin.
 */
internal fun matchHaInstanceUuid(candidateUrls: Collection<String>, records: Collection<HaTxtRecord>): String? {
    val candidates = candidateUrls.mapNotNull(::canonicalHaCandidate).toSet()
    if (candidates.isEmpty()) return null
    val matches = records.asSequence().filter { record ->
        candidates.any { candidate ->
            candidate.origin in record.origins ||
                candidate.host == "${record.uuid}.local" ||
                record.serverHost == "${record.uuid}.local" && candidate.host == record.serverHost
        }
    }.map { it.uuid }.toSet()
    return matches.singleOrNull()
}

private data class CanonicalHaCandidate(val origin: String, val host: String)

private fun canonicalHaCandidate(raw: String): CanonicalHaCandidate? {
    val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
    val origin = canonicalHaOrigin(uri) ?: return null
    return CanonicalHaCandidate(origin, uri.host.trimEnd('.').lowercase())
}

/** Canonical HTTP origin with scheme/host case and default ports normalized. */
internal fun canonicalHaOrigin(raw: String): String? =
    runCatching { URI(raw.trim()) }.getOrNull()?.let(::canonicalHaOrigin)

private fun canonicalHaOrigin(uri: URI): String? {
    val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
    val host = uri.host?.trimEnd('.')?.lowercase()?.takeIf(String::isNotEmpty) ?: return null
    if (uri.userInfo != null) return null
    val port = when {
        uri.port < 0 -> -1
        scheme == "http" && uri.port == 80 -> -1
        scheme == "https" && uri.port == 443 -> -1
        else -> uri.port
    }
    return URI(scheme, null, host, port, null, null, null).toString()
}

/** One ha-paneld panel discovered over mDNS (or this panel itself, [self]=true). [ip] is null when the
 *  record didn't resolve to an IPv4 (IPv6-only / unresolved) — such peers aren't navigable in the switcher. */
data class Peer(
    val panelId: String,
    val name: String,
    val ip: String?,
    val port: Int,
    val version: String,
    val self: Boolean,
)

/** Pure: dedupe by panel id (a panel can resolve to several records/addresses), preferring the entry with
 *  a resolved IPv4; sort purely by friendly name so the roster is IDENTICAL on every panel (self is flagged,
 *  not reordered — the switcher must look the same everywhere). Blank panel ids are dropped. Unit-tested. */
internal fun dedupePeers(peers: List<Peer>): List<Peer> {
    val byId = LinkedHashMap<String, Peer>()
    for (p in peers) {
        if (p.panelId.isBlank()) continue
        val existing = byId[p.panelId]
        if (existing == null || (existing.ip == null && p.ip != null)) byId[p.panelId] = p
    }
    return byId.values.sortedBy { it.name.lowercase() }
}

/** Pure: serialize the switcher roster to the `/api/v1/peers` JSON array. Kept here beside the peer
 *  mapping (not inline in the HTTP server, which needs a Context) so it's unit-testable without booting
 *  Ktor — `ip`/`url` emit a literal `null` for a peer whose IPv4 didn't resolve (un-navigable). */
internal fun peersJson(list: List<Peer>): String =
    list.joinToString(",", "[", "]") { p ->
        val ip = p.ip?.let { Json.str(it) } ?: "null"
        val url = p.ip?.let { Json.str("http://$it:${p.port}/") } ?: "null"
        """{"panel_id":${Json.str(p.panelId)},"name":${Json.str(p.name)},"ip":$ip,"port":${p.port},"url":$url,"version":${Json.str(p.version)},"self":${p.self}}"""
    }

/** Pure mDNS-record → [Peer] mapper (plain strings, no ServiceInfo) so it's JVM-unit-testable. */
internal fun toPeer(
    instanceName: String,
    txtName: String?,
    txtVer: String?,
    ipv4: String?,
    port: Int,
    selfId: String,
    selfIp: String?,
): Peer = Peer(
    panelId = instanceName,
    name = txtName?.takeIf { it.isNotBlank() } ?: instanceName,
    ip = ipv4,
    port = port,
    version = txtVer ?: "",
    self = instanceName == selfId || (ipv4 != null && ipv4 == selfIp),
)
