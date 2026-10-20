package dev.jamesrich.sofaking.backend.discovery

import dev.jamesrich.sofaking.contract.Wire
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
}
