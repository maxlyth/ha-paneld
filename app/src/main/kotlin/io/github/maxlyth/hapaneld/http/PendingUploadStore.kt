package io.github.maxlyth.hapaneld.http

import java.io.File
import java.util.UUID

/** Owns the one inspected-but-uncommitted upload without letting concurrent requests swap identities. */
internal class PendingUploadStore(
    private val newToken: () -> String = { UUID.randomUUID().toString() },
) {
    class Lease internal constructor(internal val epoch: Long)
    class Entry internal constructor(val token: String, val file: File, internal val epoch: Long)

    private var epoch = 0L
    private var open = false
    private var active: Entry? = null

    @Synchronized
    fun open() {
        active?.file?.delete()
        active = null
        epoch++
        open = true
    }

    /** Reserve the current server lifetime before receiving a potentially slow request body. */
    @Synchronized
    fun begin(): Lease? = if (open) Lease(epoch) else null

    /** Publish an inspected file. A newer upload replaces it, but its token cannot claim this file. */
    @Synchronized
    fun stage(lease: Lease, file: File): Entry? {
        if (!open || lease.epoch != epoch) {
            file.delete()
            return null
        }
        active?.file?.takeIf { it.absolutePath != file.absolutePath }?.delete()
        return Entry(newToken(), file, epoch).also { active = it }
    }

    /** Transfer ownership only to the client that received this exact upload's inspection token. */
    @Synchronized
    fun claim(token: String): Entry? {
        val entry = active?.takeIf { open && it.token == token && it.file.exists() } ?: return null
        active = null
        return entry
    }

    /** Put a claim back after a busy race, unless a newer upload or server lifetime now owns the slot. */
    @Synchronized
    fun restore(entry: Entry): Boolean {
        if (open && entry.epoch == epoch && active == null && entry.file.exists()) {
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
    }

    @Synchronized
    fun close() {
        clear()
        epoch++
        open = false
    }
}
