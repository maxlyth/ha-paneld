package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.util.ServiceRuntimeOwner
import java.net.InetAddress

/**
 * Retains default-network truth until the service runtime can accept an mDNS revalidation.
 *
 * Android may deliver the initial DHCP link properties while [ServiceRuntimeOwner] is still
 * STARTING, when [ServiceRuntimeOwner.observe] deliberately returns null. The successful-start hook
 * replays the latest retained topology; later callbacks continue through the same serialized path.
 */
internal class MdnsRuntimeReconciler<T : Any>(
    private val owner: ServiceRuntimeOwner<T>,
    private val revalidate: (ServiceRuntimeOwner.Observation<T>, String?) -> Unit,
) {
    private var networkObserved = false
    private var latestLanIp: String? = null

    @Synchronized fun networkChanged(addresses: Collection<InetAddress>): Boolean {
        networkObserved = true
        latestLanIp = defaultNetworkIpv4(addresses)
        return replayLocked()
    }

    @Synchronized fun networkLost(): Boolean {
        networkObserved = true
        latestLanIp = null
        return replayLocked()
    }

    /** Called after a lifecycle transition has published RUNNING. */
    @Synchronized fun runtimeRunning(): Boolean = replayLocked()

    private fun replayLocked(): Boolean {
        if (!networkObserved) return false
        val current = owner.observe() ?: return false
        revalidate(current, latestLanIp)
        return true
    }
}
