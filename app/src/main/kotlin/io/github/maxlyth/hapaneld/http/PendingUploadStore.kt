package io.github.maxlyth.hapaneld.http

import android.os.SystemClock
import io.github.maxlyth.hapaneld.util.DownloadAbort
import java.io.File
import java.util.UUID

/** Owns the one inspected-but-uncommitted upload without letting concurrent requests swap identities. */
internal class PendingUploadStore(
    private val monotonicMs: () -> Long = SystemClock::elapsedRealtime,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val newToken: () -> String = { UUID.randomUUID().toString() },
) {
    sealed interface BeginResult {
        data class Granted(val lease: Lease) : BeginResult
        data object Busy : BeginResult
        data object Closed : BeginResult
    }

    class Lease internal constructor(internal val epoch: Long, internal val id: Long)
    class Entry internal constructor(
        val token: String,
        val file: File,
        val identity: UploadedApkIdentity?,
        internal val epoch: Long,
        internal val leaseId: Long,
        internal val stagedAtMs: Long,
    )

    private var epoch = 0L
    private var nextLeaseId = 0L
    private var open = false
    private var receiving: Lease? = null
    private var active: Entry? = null

    // Panel-side work belonging to the current reservation. Held HERE rather than in a second owner
    // beside this store, because every way a reservation ends — superseded, aborted, staged, cleared,
    // closed, expired — already runs through this class. A separate owner left seams at each of those
    // points: an in-flight fetch outlived a disable, a shutdown and a replacing request.
    private var receivingAbort: DownloadAbort? = null
    private var receivingOwner: String? = null

    // Declared when the slot is reserved, not when the abort handle arrives, so the gap between the two
    // cannot make a fetch look like an un-retractable upload to a request trying to replace it.
    private var receivingIsPanelWork = false

    @Synchronized
    fun open() {
        stopPanelWork()
        active?.file?.delete()
        active = null
        receiving = null
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
     */
    @Synchronized
    fun begin(panelWork: Boolean = false): BeginResult {
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
        receivingIsPanelWork = panelWork
        return BeginResult.Granted(Lease(epoch, ++nextLeaseId).also { receiving = it })
    }

    /** Bind panel-side work to the reservation that started it, so ending the reservation ends the work. */
    @Synchronized
    fun attachPanelWork(lease: Lease, owner: String, abort: DownloadAbort) {
        if (!receiving.matches(lease)) {
            // The reservation moved on while this request was being admitted; never start work for it.
            abort.abort()
            return
        }
        receivingAbort = abort
        receivingOwner = owner
    }

    /** Stop the in-flight fetch only when [owner] is the request that started it. */
    @Synchronized
    fun cancelPanelWork(owner: String): Boolean {
        if (receivingOwner != owner) return false
        receivingAbort?.abort()
        return true
    }

    private fun stopPanelWork() {
        receivingAbort?.abort()
        receivingAbort = null
        receivingOwner = null
        receivingIsPanelWork = false
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
        if (!open || lease.epoch != epoch || !receiving.matches(lease) || active != null) {
            file.delete()
            return null
        }
        receiving = null
        stopPanelWork()
        return Entry(newToken(), file, identity, epoch, lease.id, monotonicMs()).also { active = it }
    }

    /** Read the immutable inspected identity without transferring the staged file. */
    @Synchronized
    fun peek(token: String): Entry? {
        expireActive()
        return active?.takeIf { open && it.token == token && it.file.exists() }
    }

    /** Transfer ownership only to the client that received this exact upload's inspection token. */
    @Synchronized
    fun claim(token: String): Entry? {
        expireActive()
        val entry = active?.takeIf { open && it.token == token && it.file.exists() } ?: return null
        active = null
        return entry
    }

    /** Put a claim back after a busy race, unless a newer reservation or server lifetime owns the slot. */
    @Synchronized
    fun restore(entry: Entry): Boolean {
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
