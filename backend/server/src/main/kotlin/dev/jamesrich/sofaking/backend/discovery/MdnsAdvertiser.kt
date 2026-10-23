package dev.jamesrich.sofaking.backend.discovery

import dev.jamesrich.sofaking.contract.Wire
import org.slf4j.LoggerFactory
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * Advertises the server on the LAN as a [Wire.Discovery.SERVICE_TYPE] service named [name] on [port], so the app lists
 * it without an address, and answers [HOST_NAME]`.local` for browsers. One JmDNS responder runs on each address
 * [addresses] returns; a name another device already holds gets a number added by JmDNS. A failure on one address is
 * logged and leaves the others. The addresses are read again every [RESCAN], so a server that starts before its
 * network is up advertises once it is, and a changed address is advertised in place of the old one.
 */
class MdnsAdvertiser(
    private val name: String,
    private val port: Int,
    private val version: String,
    private val addresses: () -> List<InetAddress> = ::lanAddresses,
    private val responder: (InetAddress) -> AutoCloseable = { register(it, name, port, version) },
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(MdnsAdvertiser::class.java)
    private val responders = LinkedHashMap<InetAddress, AutoCloseable>()
    private val failed = HashSet<InetAddress>()
    private var logged: Set<InetAddress>? = null
    private var rescans: ScheduledExecutorService? = null
    private var closed = false

    /**
     * Advertises on the current addresses, then checks them again every [RESCAN] on a daemon thread. It blocks while
     * JmDNS probes for the name.
     */
    @Synchronized fun start() {
        if (closed || rescans != null) return
        refresh()
        rescans = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("mdns").factory()).also {
            it.scheduleWithFixedDelay(::refresh, RESCAN.toSeconds(), RESCAN.toSeconds(), TimeUnit.SECONDS)
        }
    }

    /** Starts a responder on each new address and closes the ones on addresses that are gone; logs when the set changes. */
    @Synchronized internal fun refresh() {
        if (closed) return
        val found = runCatching(addresses).getOrElse { emptyList() }.toSet()
        (responders.keys - found).forEach { address -> responders.remove(address)?.let { runCatching { it.close() } } }
        failed.retainAll(found)
        for (address in found - responders.keys) {
            runCatching { responders[address] = responder(address) }
                .onFailure {
                    if (failed.add(
                            address,
                        )
                    ) {
                        log.warn("mdns: cannot advertise on {} ({})", address.hostAddress, it.javaClass.simpleName)
                    }
                }
        }
        val advertised = responders.keys.toSet()
        if (advertised == logged) return
        logged = advertised
        if (advertised.isEmpty()) {
            log.info("mdns: no LAN address to advertise on; apps need the server's address")
        } else {
            log.info(
                "mdns: advertising {} as {} on {} port {}",
                name,
                Wire.Discovery.SERVICE_TYPE,
                advertised.map {
                    it.hostAddress
                },
                port,
            )
        }
    }

    /** Withdraws the service, so apps drop it at once instead of when its record expires. */
    @Synchronized override fun close() {
        closed = true
        rescans?.shutdownNow()
        responders.values.forEach { runCatching { it.close() } }
        responders.clear()
    }

    companion object {
        /** The host name the responders answer, `sofa-king.local`, kept apart from the machine's own name. */
        const val HOST_NAME = "sofa-king"

        /** How often the addresses are read again. */
        val RESCAN: Duration = Duration.ofMinutes(1)

        /** Interface name prefixes of container, VPN and virtual-machine networks, which no app on the LAN reaches. */
        private val VIRTUAL_PREFIXES = listOf(
            "docker", "br-", "veth", "virbr", "vnet", "cni", "flannel", "cali", "vxlan", "kube", "lxc", "lxd", "podman",
            "tailscale", "zt", "wg", "tun", "tap",
        )

        internal fun serviceInfo(name: String, port: Int, version: String): ServiceInfo = ServiceInfo.create(
            "${Wire.Discovery.SERVICE_TYPE}.local.",
            name,
            port,
            0,
            0,
            mapOf(Wire.Discovery.TXT_VERSION to version),
        )

        /** A JmDNS responder on [address] with the service registered; closing it withdraws the service. */
        private fun register(address: InetAddress, name: String, port: Int, version: String): AutoCloseable {
            val jmdns = JmDNS.create(address, HOST_NAME)
            try {
                jmdns.registerService(serviceInfo(name, port, version))
            } catch (e: Exception) {
                runCatching { jmdns.close() }
                throw e
            }
            return AutoCloseable {
                jmdns.unregisterAllServices()
                jmdns.close()
            }
        }

        /** Whether an interface named [name] can carry the advertisement to the LAN. */
        internal fun advertisable(
            name: String,
            up: Boolean,
            loopback: Boolean,
            virtual: Boolean,
            multicast: Boolean,
        ): Boolean = up && !loopback && !virtual && multicast && VIRTUAL_PREFIXES.none { name.startsWith(it) }

        /** The IPv4 addresses of the interfaces [advertisable] admits, in the order the system lists them. */
        fun lanAddresses(): List<InetAddress> = NetworkInterface.networkInterfaces().toList()
            .filter {
                runCatching {
                    advertisable(it.name, it.isUp, it.isLoopback, it.isVirtual, it.supportsMulticast())
                }.getOrDefault(false)
            }
            .flatMap { it.inetAddresses.toList() }
            .filter { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
    }
}
