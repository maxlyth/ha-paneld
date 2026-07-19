package io.github.maxlyth.hapaneld.http

import android.os.SystemClock
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

    @Synchronized
    fun open() {
        active?.file?.delete()
        active = null
        receiving = null
        epoch++
        open = true
    }

    /** Exclusively reserve the upload slot before receiving a potentially slow request body. */
    @Synchronized
    fun begin(): BeginResult {
        expireActive()
        return when {
            !open -> BeginResult.Closed
            receiving != null || active != null -> BeginResult.Busy
            else -> BeginResult.Granted(Lease(epoch, ++nextLeaseId).also { receiving = it })
        }
    }

    /** Release a receive/inspection lease, deleting a staged entry if its response was not handed off. */
    @Synchronized
    fun abort(lease: Lease) {
        if (receiving.matches(lease)) receiving = null
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
