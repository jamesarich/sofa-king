package dev.jamesrich.sofaking.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerDiscoveryTest {
    @Test
    fun `resolved servers list by name with the address Settings stores`() = runTest {
        val found = DiscoveredServers()
        found.resolved("Sofa King", "192.0.2.10", 9193, "0.1.0-dev.330")
        found.resolved("attic", "192.0.2.11", 9200, "")
        assertEquals(
            listOf(
                DiscoveredServer("attic", "http://192.0.2.11:9200"),
                DiscoveredServer("Sofa King", "http://192.0.2.10:9193", "0.1.0-dev.330"),
            ),
            found.list.first(),
        )
    }

    @Test
    fun `a lost server leaves the list and a newer address replaces the old one`() = runTest {
        val found = DiscoveredServers()
        found.resolved("Sofa King", "192.0.2.10", 9193, null)
        found.resolved("Sofa King", "192.0.2.12", 9193, null)
        assertEquals(listOf(DiscoveredServer("Sofa King", "http://192.0.2.12:9193")), found.list.first())
        found.lost("Sofa King")
        assertEquals(emptyList(), found.list.first())
    }

    @Test
    fun `IPv6 hosts and impossible ports are left out`() = runTest {
        val found = DiscoveredServers()
        found.resolved("v6", "fe80::1", 9193, null)
        found.resolved("no port", "192.0.2.10", 0, null)
        assertEquals(emptyList(), found.list.first())
    }

    @Test
    fun `None finds nothing`() = runTest {
        assertEquals(emptyList(), ServerDiscovery.None.servers().first())
    }
}
