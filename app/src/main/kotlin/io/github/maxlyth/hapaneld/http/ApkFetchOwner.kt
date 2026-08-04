package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.util.DownloadAbort

/**
 * Binds an in-flight APK download to the exact request that started it, so a cancel can only ever stop
 * the operation its sender owns.
 *
 * Without this, "cancel the current download" is ambiguous the moment two requests overlap: an operator
 * who cancels a fetch and immediately starts another would cancel the replacement. Ownership is the
 * request identifier the client minted and sent, so a cancel naming a finished or superseded request
 * is answered honestly as "nothing was cancelled" rather than silently stopping someone else's work.
 *
 * Only one fetch can be in flight — [PendingUploadStore] hands out a single exclusive lease — so this
 * holds one slot rather than a map.
 */
internal class ApkFetchOwner {
    private var currentRequest: String? = null
    private var currentAbort: DownloadAbort? = null

    /** Take ownership for [request], or null when another fetch already owns the slot. */
    @Synchronized
    fun begin(request: String): DownloadAbort? {
        if (currentRequest != null) return null
        val abort = DownloadAbort()
        currentRequest = request
        currentAbort = abort
        return abort
    }

    /** Release the slot, but only if [request] still owns it. */
    @Synchronized
    fun end(request: String) {
        if (currentRequest == request) {
            currentRequest = null
            currentAbort = null
        }
    }

    /** Abort the in-flight fetch only when [request] is the one that started it. */
    @Synchronized
    fun cancel(request: String): Boolean {
        if (currentRequest != request) return false
        currentAbort?.abort()
        return true
    }
}
