package dev.jamesrich.sofaking.backend.discovery

import dev.jamesrich.sofaking.contract.Wire
import java.net.InetAddress
import kotlin.test.*

class MdnsAdvertiserTest {
    @Test fun `the service carries the type, the name, the port and the version`() {
        val info = MdnsAdvertiser.serviceInfo("Sofa King", 9193, "0.1.0-dev.330")
        assertEquals("${Wire.Discovery.SERVICE_TYPE}.local.", info.type)
        assertEquals("Sofa King", info.name)
        assertEquals(9193, info.port)
        assertEquals("0.1.0-dev.330", info.getPropertyString(Wire.Discovery.TXT_VERSION))
    }

    @Test fun `only up multicast LAN interfaces carry the advertisement`() {
        assertTrue(MdnsAdvertiser.advertisable("eth0", up = true, loopback = false, virtual = false, multicast = true))
        assertTrue(MdnsAdvertiser.advertisable("enp3s0", up = true, loopback = false, virtual = false, multicast = true))
        assertFalse(MdnsAdvertiser.advertisable("eth0", up = false, loopback = false, virtual = false, multicast = true))
        assertFalse(MdnsAdvertiser.advertisable("lo", up = true, loopback = true, virtual = false, multicast = true))
        assertFalse(MdnsAdvertiser.advertisable("eth0", up = true, loopback = false, virtual = false, multicast = false))
        for (name in listOf("docker0", "br-4f2a", "veth12ab", "virbr0", "tailscale0", "wg0", "tun0")) {
            assertFalse(MdnsAdvertiser.advertisable(name, up = true, loopback = false, virtual = false, multicast = true), name)
        }
    }

    @Test fun `no LAN address advertises nothing and closing twice is safe`() {
        val advertiser = MdnsAdvertiser("Sofa King", 9193, "unknown", addresses = { emptyList() })
        advertiser.start()
        advertiser.close()
        advertiser.close()
    }

    @Test fun `a rescan advertises on an address that appears and withdraws from one that goes`() {
        val first = InetAddress.getByName("192.0.2.10")
        val second = InetAddress.getByName("192.0.2.20")
        var current = emptyList<InetAddress>()
        val open = mutableListOf<InetAddress>()
        val advertiser = MdnsAdvertiser("Sofa King", 9193, "unknown", addresses = { current }, responder = { address ->
            open += address
            AutoCloseable { open -= address }
        })
        advertiser.refresh()
        assertEquals(emptyList<InetAddress>(), open)
        current = listOf(first)
        advertiser.refresh()
        advertiser.refresh()
        assertEquals(listOf(first), open)
        current = listOf(second)
        advertiser.refresh()
        assertEquals(listOf(second), open)
        advertiser.close()
        assertEquals(emptyList<InetAddress>(), open)
        current = listOf(first)
        advertiser.refresh()
        assertEquals(emptyList<InetAddress>(), open)
    }

    @Test fun `an address that fails is tried again at the next rescan`() {
        val address = InetAddress.getByName("192.0.2.10")
        var attempts = 0
        val advertiser = MdnsAdvertiser("Sofa King", 9193, "unknown", addresses = { listOf(address) }, responder = {
            if (++attempts == 1) error("interface not ready")
            AutoCloseable { }
        })
        advertiser.refresh()
        advertiser.refresh()
        advertiser.refresh()
        assertEquals(2, attempts)
        advertiser.close()
    }
}
