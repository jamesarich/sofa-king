package dev.jamesrich.sofaking.backend.discovery

import dev.jamesrich.sofaking.contract.Wire
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import org.slf4j.LoggerFactory

/**
 * Advertises the server on the LAN as a [Wire.Discovery.SERVICE_TYPE] service named [name] on [port], so the app lists
 * it without an address, and answers [HOST_NAME]`.local` for browsers. One JmDNS responder runs on each address
 * [addresses] returns; a name another device already holds gets a number added by JmDNS. A failure on one address is
 * logged and leaves the others.
 */
class MdnsAdvertiser(
    private val name: String,
    private val port: Int,
    private val version: String,
    private val addresses: () -> List<InetAddress> = ::lanAddresses,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(MdnsAdvertiser::class.java)
    private val responders = mutableListOf<JmDNS>()
    private var closed = false

    /** Starts a responder on each address and registers the service; it blocks while JmDNS probes for the name. */
    @Synchronized fun start() {
        if (closed) return
        val found = runCatching(addresses).getOrElse { emptyList() }
        if (found.isEmpty()) {
            log.info("mdns: no LAN address to advertise on; apps need the server's address")
            return
        }
        for (address in found) {
            runCatching {
                val responder = JmDNS.create(address, HOST_NAME)
                responders += responder
                responder.registerService(serviceInfo(name, port, version))
            }.onFailure { log.warn("mdns: cannot advertise on {} ({})", address.hostAddress, it.javaClass.simpleName) }
        }
        val advertised = responders.mapNotNull { runCatching { it.inetAddress.hostAddress }.getOrNull() }
        log.info("mdns: advertising {} as {} on {} port {}", name, Wire.Discovery.SERVICE_TYPE, advertised, port)
    }

    /** Withdraws the service, so apps drop it at once instead of when its record expires. */
    @Synchronized override fun close() {
        closed = true
        responders.forEach { responder -> runCatching { responder.unregisterAllServices(); responder.close() } }
        responders.clear()
    }

    companion object {
        /** The host name the responders answer, `sofa-king.local`, kept apart from the machine's own name. */
        const val HOST_NAME = "sofa-king"

        /** Interface name prefixes of container, VPN and virtual-machine networks, which no app on the LAN reaches. */
        private val VIRTUAL_PREFIXES = listOf(
            "docker", "br-", "veth", "virbr", "vnet", "cni", "flannel", "cali", "vxlan", "kube", "lxc", "lxd", "podman",
            "tailscale", "zt", "wg", "tun", "tap",
        )

        internal fun serviceInfo(name: String, port: Int, version: String): ServiceInfo =
            ServiceInfo.create("${Wire.Discovery.SERVICE_TYPE}.local.", name, port, 0, 0, mapOf(Wire.Discovery.TXT_VERSION to version))

        /** Whether an interface named [name] can carry the advertisement to the LAN. */
        internal fun advertisable(name: String, up: Boolean, loopback: Boolean, virtual: Boolean, multicast: Boolean): Boolean =
            up && !loopback && !virtual && multicast && VIRTUAL_PREFIXES.none { name.startsWith(it) }

        /** The IPv4 addresses of the interfaces [advertisable] admits, in the order the system lists them. */
        fun lanAddresses(): List<InetAddress> =
            NetworkInterface.networkInterfaces().toList()
                .filter { runCatching { advertisable(it.name, it.isUp, it.isLoopback, it.isVirtual, it.supportsMulticast()) }.getOrDefault(false) }
                .flatMap { it.inetAddresses.toList() }
                .filter { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
    }
}
