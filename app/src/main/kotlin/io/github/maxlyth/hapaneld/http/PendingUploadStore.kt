package io.github.maxlyth.hapaneld.http

import android.os.SystemClock
import io.github.maxlyth.hapaneld.util.DownloadAbort
import java.io.File
import java.util.UUID

/** Owns the one inspected-but-uncommitted upload without letting concurrent requests swap identities. */
internal class PendingUploadStore(
    private val monotonicMs: () -> Long = SystemClock::elapsedRealtime,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val newDiscardId: () -> String = { UUID.randomUUID().toString() },
    private val newToken: () -> String = { UUID.randomUUID().toString() },
) {
    sealed interface BeginResult {
        /** [abort] is present only for panel-side work, and exists from the instant the slot is taken. */
        data class Granted(val lease: Lease, val abort: DownloadAbort? = null) : BeginResult
        data object Busy : BeginResult
        data object Closed : BeginResult
    }

    enum class DiscardResult { DISCARDED, NOTHING_PENDING, DIFFERENT_PENDING, INSTALL_IN_FLIGHT }

    /** The staged entry's inspected identity plus its discard reference for state probes — never the
     *  token, because the token is the commit authority and this surface answers any LAN client. The
     *  [discardId] scopes a recovery discard to exactly this entry while carrying no install
     *  authority: it cannot peek, claim or commit anything. */
    class PendingSummary internal constructor(val identity: UploadedApkIdentity?, val discardId: String)

    class Lease internal constructor(internal val epoch: Long, internal val id: Long)
    class Entry internal constructor(
        val token: String,
        val file: File,
        val identity: UploadedApkIdentity?,
        internal val discardId: String,
        internal val epoch: Long,
        internal val leaseId: Long,
        internal val stagedAtMs: Long,
    )

    private var epoch = 0L
    private var nextLeaseId = 0L
    private var open = false
    private var receiving: Lease? = null
    private var active: Entry? = null

    // The entry a commit has claimed while it decides whether its install can actually start. In that
    // window the entry is neither pending nor gone: a busy answer will restore it. A discard naming it
    // must be told the truth ("an install is deciding") rather than "nothing pending" — the review
    // found that false completion contradicted by the entry reappearing seconds later.
    private var claimedForInstall: Entry? = null

    // Panel-side work belonging to the current reservation. Held HERE rather than in a second owner
    // beside this store, because every way a reservation ends — superseded, aborted, staged, cleared,
    // closed, expired — already runs through this class. A separate owner left seams at each of those
    // points: an in-flight fetch outlived a disable, a shutdown and a replacing request.
    private var receivingAbort: DownloadAbort? = null
    private var receivingOwner: String? = null

    // Declared when the slot is reserved, not when the abort handle arrives, so the gap between the two
    // cannot make a fetch look like an un-retractable upload to a request trying to replace it.
    private var receivingIsPanelWork = false

    // A cancel must decide the OUTCOME, not merely try to stop the transfer. Aborting alone left a
    // race: bytes that had already arrived went on to be staged, so the operator was told the download
    // was cancelled while a claimable token existed for the whole entry TTL.
    private var receivingCancelled = false

    @Synchronized
    fun open() {
        stopPanelWork()
        active?.file?.delete()
        active = null
        receiving = null
        claimedForInstall = null
        epoch++
        open = true
    }

    /**
     * Exclusively reserve the upload slot before receiving a potentially slow request body.
     *
     * A new reservation **supersedes** work the panel was doing on the operator's behalf: an in-flight
     * fetch is aborted and an already-staged token is retired, because both represent an intent the
     * operator has just replaced. Leaving them would strand the panel for up to a download deadline or
     * the entry TTL, and would answer the operator's newest action with "busy".
     *
     * What is NOT superseded is a request body still arriving from a client: the panel cannot retract
     * someone else's upload stream, so that remains [BeginResult.Busy].
     *
     * A non-null [owner] declares panel-side work and registers its identity **and** its abort handle
     * in the same synchronized step that takes the slot. Registering them afterwards left an interval
     * in which a cancel found no owner, answered "nothing cancelled", and let the fetch go on to
     * complete and publish a token.
     */
    @Synchronized
    fun begin(owner: String? = null): BeginResult {
        expireActive()
        if (!open) return BeginResult.Closed
        if (receiving != null) {
            if (!receivingIsPanelWork) return BeginResult.Busy
            // Panel-side work: stop it. Its owning handler still releases its own lease and staging
            // file, and its stage() will be refused because the reservation has moved on.
            stopPanelWork()
        }
        active?.let {
            active = null
            it.file.delete()
        }
        receivingIsPanelWork = owner != null
        receivingCancelled = false
        receivingOwner = owner
        receivingAbort = owner?.let { DownloadAbort() }
        val lease = Lease(epoch, ++nextLeaseId).also { receiving = it }
        return BeginResult.Granted(lease, receivingAbort)
    }

    /** Stop the in-flight fetch only when [owner] is the request that started it. */
    @Synchronized
    fun cancelPanelWork(owner: String): Boolean {
        if (receivingOwner != owner) return false
        receivingCancelled = true
        receivingAbort?.abort()
        return true
    }

    /** Whether [lease]'s work was cancelled, so a refusal can say so rather than blaming the link. */
    @Synchronized
    fun isCancelled(lease: Lease): Boolean = receiving.matches(lease) && receivingCancelled

    private fun stopPanelWork() {
        receivingAbort?.abort()
        receivingAbort = null
        receivingOwner = null
        receivingIsPanelWork = false
        receivingCancelled = false
    }

    /** Release a receive/inspection lease, deleting a staged entry if its response was not handed off. */
    @Synchronized
    fun abort(lease: Lease) {
        if (receiving.matches(lease)) {
            receiving = null
            stopPanelWork()
        }
        active?.takeIf { it.epoch == lease.epoch && it.leaseId == lease.id }?.let {
            active = null
            it.file.delete()
        }
    }

    /** Atomically convert the exclusive receive lease into the one inspected, commit-ready entry. */
    @Synchronized
    fun stage(lease: Lease, file: File, identity: UploadedApkIdentity? = null): Entry? {
        // A cancelled reservation can never produce a token, however far its transfer had got.
        if (!open || lease.epoch != epoch || !receiving.matches(lease) || active != null || receivingCancelled) {
            file.delete()
            return null
        }
        receiving = null
        stopPanelWork()
        return Entry(newToken(), file, identity, newDiscardId(), epoch, lease.id, monotonicMs()).also { active = it }
    }

    /** Read the immutable inspected identity without transferring the staged file. */
    @Synchronized
    fun peek(token: String): Entry? {
        expireActive()
        return active?.takeIf { open && it.token == token && it.file.exists() }
    }

    /** Transfer ownership only to the client that received this exact upload's inspection token.
     *  Opens the claim window: until [confirmClaim] or [restore], a discard naming this entry is told
     *  an install is in flight rather than that nothing is pending. */
    @Synchronized
    fun claim(token: String): Entry? {
        expireActive()
        val entry = active?.takeIf { open && it.token == token && it.file.exists() } ?: return null
        active = null
        claimedForInstall = entry
        return entry
    }

    /** The claimed install is genuinely starting: the claim window closes and the entry has left the
     *  store for good, so a later discard naming it finds nothing — and nothing will reappear. */
    @Synchronized
    fun confirmClaim(entry: Entry) {
        if (claimedForInstall === entry) claimedForInstall = null
    }

    /**
     * Retire the inspected-but-uncommitted entry named by [ref], deleting its staged file.
     *
     * [ref] is either the entry's commit token (the preview client holds it) or its [Entry.discardId]
     * (a reload-recovery client got it from [pendingSummary]). Every discard is scoped: a reference to
     * an entry this slot no longer holds removes nothing, so a stale recovery card can never delete a
     * replacement upload staged after its probe — that unscoped blind discard was a known failure mode.
     * A discard never touches a body still being received or in-flight panel work (each has its own
     * cancel), and an entry inside the commit's claim window answers [DiscardResult.INSTALL_IN_FLIGHT]
     * so the caller re-asks instead of being told "nothing pending" moments before a busy answer
     * restores the entry.
     */
    @Synchronized
    fun discard(ref: String): DiscardResult {
        expireActive()
        claimedForInstall?.takeIf { ref == it.token || ref == it.discardId }?.let {
            return DiscardResult.INSTALL_IN_FLIGHT
        }
        val entry = active ?: return DiscardResult.NOTHING_PENDING
        if (ref != entry.token && ref != entry.discardId) return DiscardResult.DIFFERENT_PENDING
        active = null
        entry.file.delete()
        return DiscardResult.DISCARDED
    }

    /** What is pending for a client that holds no token, or null when nothing is discardable. */
    @Synchronized
    fun pendingSummary(): PendingSummary? {
        expireActive()
        return active?.takeIf { open && it.file.exists() }?.let { PendingSummary(it.identity, it.discardId) }
    }

    /** Put a claim back after a busy race, unless a newer reservation or server lifetime owns the slot.
     *  Always closes the claim window, so a discard that was told "in flight" can now act on the truth. */
    @Synchronized
    fun restore(entry: Entry): Boolean {
        if (claimedForInstall === entry) claimedForInstall = null
        if (expired(entry, monotonicMs())) {
            entry.file.delete()
            return false
        }
        if (open && entry.epoch == epoch && receiving == null && active == null && entry.file.exists()) {
            active = entry
            return true
        }
        entry.file.delete()
        return false
    }

    @Synchronized
    fun clear() {
        stopPanelWork()
        active?.file?.delete()
        active = null
        receiving = null
        claimedForInstall = null
    }

    @Synchronized
    fun close() {
        clear()
        epoch++
        open = false
    }

    private fun Lease?.matches(other: Lease): Boolean =
        this != null && epoch == other.epoch && id == other.id

    private fun expireActive() {
        val now = monotonicMs()
        active?.takeIf { expired(it, now) }?.let {
            active = null
            it.file.delete()
        }
    }

    /** A monotonic clock should not move backwards; fail closed if an injected/platform clock does. */
    private fun expired(entry: Entry, now: Long): Boolean =
        now < entry.stagedAtMs || now - entry.stagedAtMs >= ttlMs

    private companion object {
        const val DEFAULT_TTL_MS = 10 * 60 * 1000L
    }
}
