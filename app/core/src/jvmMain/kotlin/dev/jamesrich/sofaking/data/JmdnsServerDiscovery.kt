package dev.jamesrich.sofaking.data

import dev.jamesrich.sofaking.contract.Wire
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

/** Browses with JmDNS on each IPv4 address of the desktop's network interfaces that are up and carry multicast. */
class JmdnsServerDiscovery : ServerDiscovery {
    override fun servers(): Flow<List<DiscoveredServer>> = callbackFlow {
        val found = DiscoveredServers()
        launch { found.list.collect(::send) }
        val type = "${Wire.Discovery.SERVICE_TYPE}.local."
        val listener = object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) = event.dns.requestServiceInfo(event.type, event.name)
            override fun serviceRemoved(event: ServiceEvent) = found.lost(event.name)
            override fun serviceResolved(event: ServiceEvent) {
                val info = event.info
                val host = info.inet4Addresses.firstOrNull()?.hostAddress ?: return
                found.resolved(info.name, host, info.port, info.getPropertyString(Wire.Discovery.TXT_VERSION))
            }
        }
        val browsers = lanAddresses().mapNotNull { address ->
            runCatching { JmDNS.create(address).also { it.addServiceListener(type, listener) } }.getOrNull()
        }
        awaitClose {
            browsers.forEach {
                runCatching {
                    it.removeServiceListener(type, listener)
                    it.close()
                }
            }
        }
    }.flowOn(ioDispatcher)

    private fun lanAddresses() = NetworkInterface.networkInterfaces().toList()
        .filter {
            runCatching { it.isUp && !it.isLoopback && !it.isVirtual && it.supportsMulticast() }.getOrDefault(false)
        }
        .flatMap { it.inetAddresses.toList() }
        .filter { it is Inet4Address && !it.isLinkLocalAddress }
}
