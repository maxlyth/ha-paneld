package io.github.maxlyth.hapaneld.util

import android.content.Context
import android.os.SystemClock
import io.github.maxlyth.hapaneld.BuildConfig
import io.github.maxlyth.hapaneld.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Checks GitHub releases for available updates to ha-paneld and the installed HA Companion app. Catalog
 * state is cached, but every entry remains tied to the channel and device safety policy used to resolve it.
 */
object UpdateChecker {

    data class UpdateInfo(
        val label: String,
        val currentVersion: String,
        val latestVersion: String,
        val releaseUrl: String,
    )

    private data class CacheKey(val paneldChannel: String, val companionChannel: String, val companionCap: String?)

    @Volatile var available: List<UpdateInfo> = emptyList()
        private set
    @Volatile private var lastCheckElapsedMs = -1L
    @Volatile private var cacheKey: CacheKey? = null
    @Volatile private var paneldCacheChannel: String? = null
    @Volatile private var companionCachePolicy: Pair<String, String?>? = null
    private val checkMutex = Mutex()

    /** True when a monotonic cache stamp is absent, rolled back, expired, or belongs to another policy. */
    internal fun shouldCheck(nowMs: Long, lastMs: Long, staleMs: Long, samePolicy: Boolean): Boolean =
        !samePolicy || lastMs < 0L || nowMs < lastMs || nowMs - lastMs > staleMs

    /** Check only when the cache is stale or was resolved for different channels/cap. */
    suspend fun checkIfStale(
        context: Context,
        channel: String = "stable",
        staleMs: Long = 3_600_000L,
        companionChannel: String = Config(context).companionUpdateChannel,
        companionMaxVersion: String?,
    ) {
        val key = CacheKey(channel, companionChannel, companionMaxVersion)
        if (shouldCheck(SystemClock.elapsedRealtime(), lastCheckElapsedMs, staleMs, cacheKey == key)) {
            check(context, channel, companionChannel, companionMaxVersion)
        }
    }

    /** Resolve both components under one serialized cache transaction. A failed component lookup keeps its
     *  last-known result and leaves the cache stale so a later stale check can retry; a successful lookup
     *  authoritatively adds or removes that component's update. */
    suspend fun check(
        context: Context,
        channel: String = "stable",
        companionChannel: String = Config(context).companionUpdateChannel,
        companionMaxVersion: String?,
    ) = withContext(Dispatchers.IO) {
        checkMutex.withLock {
            val previous = available
            val paneldLatest = SelfUpdater.resolve(channel)?.first
            val paneld = if (paneldLatest == null) {
                if (paneldCacheChannel == channel) previous.filter { it.label == PANELD_LABEL } else emptyList()
            } else {
                paneldCacheChannel = channel
                val current = BuildConfig.VERSION_NAME
                if (isNewer(paneldLatest, current)) {
                    listOf(UpdateInfo(PANELD_LABEL, current, paneldLatest, "https://github.com/maxlyth/ha-paneld/releases"))
                } else {
                    emptyList()
                }
            }

            val companion = installedCompanion(context)
            val companionTarget = if (companion == null) null else CompanionInstaller.target(companionChannel, companionMaxVersion)
            val requestedCompanionPolicy = companionChannel to companionMaxVersion
            val companionUpdates = when {
                companion == null -> {
                    companionCachePolicy = requestedCompanionPolicy
                    emptyList()
                }
                companionTarget == null -> {
                    if (companionCachePolicy == requestedCompanionPolicy) {
                        previous.filter { it.label == COMPANION_LABEL }
                    } else {
                        emptyList()
                    }
                }
                else -> {
                    companionCachePolicy = requestedCompanionPolicy
                    if (isNewer(companionTarget.version, stripVariant(companion.second))) {
                        listOf(
                            UpdateInfo(
                                COMPANION_LABEL,
                                companion.second,
                                companionTarget.version,
                                companionTarget.releaseUrl,
                            ),
                        )
                    } else {
                        emptyList()
                    }
                }
            }

            available = paneld + companionUpdates
            if (paneldLatest != null && (companion == null || companionTarget != null)) {
                cacheKey = CacheKey(channel, companionChannel, companionMaxVersion)
                lastCheckElapsedMs = SystemClock.elapsedRealtime()
            }
        }
    }

    /** Available updates minus any the user dismissed at their current latest version. */
    fun visible(ignored: Map<String, String>): List<UpdateInfo> = filterIgnored(available, ignored)

    /** Revalidate the cache against the current install state and version. This drops a stale Companion
     *  entry after uninstall or successful install even when the following network refresh failed. */
    fun current(context: Context, ignored: Map<String, String> = emptyMap()): List<UpdateInfo> {
        val companion = installedCompanion(context)
        return filterCurrent(filterIgnored(available, ignored), companion?.second)
    }

    private fun installedCompanion(context: Context): Pair<String, String>? = COMPANION_PKGS.firstNotNullOfOrNull { pkg ->
        runCatching {
            val version = context.packageManager.getPackageInfo(pkg, 0).versionName ?: ""
            pkg to version
        }.getOrNull()
    }

    /** Pure core of [current]. */
    internal fun filterCurrent(list: List<UpdateInfo>, companionVersion: String?): List<UpdateInfo> = list.mapNotNull { info ->
        if (info.label != COMPANION_LABEL) return@mapNotNull info
        val installed = companionVersion?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        if (isNewer(info.latestVersion, stripVariant(installed))) info.copy(currentVersion = installed) else null
    }

    /** Compatibility wrapper retained for existing tests/callers. */
    internal fun filterAbsent(list: List<UpdateInfo>, companionInstalled: Boolean): List<UpdateInfo> =
        if (companionInstalled) list else list.filterNot { it.label == COMPANION_LABEL }

    internal val COMPANION_PKGS = listOf(
        CompanionInstaller.FULL_PKG,
        CompanionInstaller.MINIMAL_PKG,
    )

    internal fun filterIgnored(list: List<UpdateInfo>, ignored: Map<String, String>): List<UpdateInfo> =
        list.filterNot { ignored[it.label] == it.latestVersion }

    /** HA Companion version names carry a variant suffix that is not a prerelease marker. */
    internal fun stripVariant(v: String): String = Regex("-(?:full|minimal|wear)$").replace(v.trim(), "")

    private data class ParsedVersion(val numeric: List<Int>, val suffix: String)

    private fun parseVersion(value: String): ParsedVersion? {
        val normalized = value.trim().removePrefix("v")
        val base = normalized.substringBefore('-')
        if (base.isEmpty()) return null
        val numeric = base.split('.').map { it.toIntOrNull() ?: return null }
        return ParsedVersion(numeric, normalized.substringAfter('-', "").lowercase())
    }

    /** Compare dotted versions with stable > prerelease and alpha < beta < rc ordering. Null means one
     *  input was malformed, allowing safety callers to fail closed instead of treating junk as version 0. */
    internal fun compareVersions(candidate: String, current: String): Int? {
        val left = parseVersion(candidate) ?: return null
        val right = parseVersion(current) ?: return null
        for (i in 0 until maxOf(left.numeric.size, right.numeric.size)) {
            val compared = left.numeric.getOrElse(i) { 0 }.compareTo(right.numeric.getOrElse(i) { 0 })
            if (compared != 0) return compared
        }
        if (left.suffix == right.suffix) return 0
        if (left.suffix.isEmpty()) return 1
        if (right.suffix.isEmpty()) return -1
        return comparePrerelease(left.suffix, right.suffix)
    }

    private fun comparePrerelease(left: String, right: String): Int {
        fun parts(value: String): Pair<String, Int> {
            val label = Regex("[a-z]+").find(value)?.value.orEmpty()
            val number = Regex("\\d+").findAll(value).lastOrNull()?.value?.toIntOrNull() ?: 0
            return label to number
        }
        fun rank(label: String): Int = when (label) {
            "alpha" -> 0
            "beta" -> 1
            "rc" -> 2
            else -> -1
        }
        val (leftLabel, leftNumber) = parts(left)
        val (rightLabel, rightNumber) = parts(right)
        if (leftLabel != rightLabel) {
            val leftRank = rank(leftLabel)
            val rightRank = rank(rightLabel)
            if (leftRank != rightRank) return leftRank.compareTo(rightRank)
            return leftLabel.compareTo(rightLabel)
        }
        return leftNumber.compareTo(rightNumber)
    }

    internal fun isNewer(candidate: String, current: String): Boolean = compareVersions(candidate, current)?.let { it > 0 } == true

    private const val PANELD_LABEL = "ha-paneld"
    private const val COMPANION_LABEL = "HA Companion"
}
