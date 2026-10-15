package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.HdhrConfig
import dev.jamesrich.sofaking.backend.catalog.LineupEntryRecord
import kotlin.test.*

class DeviceShareTest {
    private val enabled = (1..23).map { LineupEntryRecord("b0f6d2f4-0000-4000-8000-${"%012d".format(it)}", it.toString(), "Channel $it") }

    @Test fun `by hash every channel lands on exactly one device and stays there when the lineup changes`() {
        val config = HdhrConfig(extraPorts = listOf(5005, 5006))
        val shares = (0 until config.devices).map { deviceShare(enabled, it, config, byRange = false) }
        assertEquals(enabled.toSet(), shares.flatten().toSet(), "every channel is served")
        assertEquals(enabled.size, shares.sumOf { it.size }, "none twice")
        assertTrue(shares.all { it.isNotEmpty() }, "the hash spreads 23 channels over three devices: ${shares.map { it.size }}")
        val without = deviceShare(enabled.drop(1), 1, config, byRange = false)
        assertEquals(shares[1].filter { it != enabled[0] }, without, "dropping one channel moves no other")
        assertEquals(emptyList(), deviceShare(enabled, 0, HdhrConfig(), byRange = false).filter { it !in enabled })
        assertEquals(enabled, deviceShare(enabled, 0, HdhrConfig(), byRange = false), "one device takes everything")
    }

    @Test fun `by range devices take consecutive blocks in lineup order and the last device keeps the overflow`() {
        val config = HdhrConfig(extraPorts = listOf(5005, 5006), deviceSize = 10)
        assertEquals(enabled.subList(0, 10), deviceShare(enabled, 0, config, byRange = true))
        assertEquals(enabled.subList(10, 20), deviceShare(enabled, 1, config, byRange = true))
        assertEquals(enabled.subList(20, 23), deviceShare(enabled, 2, config, byRange = true))
        val small = HdhrConfig(extraPorts = listOf(5005), deviceSize = 5)
        assertEquals(enabled.subList(0, 5), deviceShare(enabled, 0, small, byRange = true))
        assertEquals(enabled.subList(5, 23), deviceShare(enabled, 1, small, byRange = true), "the last device holds what the others cannot")
        assertEquals(enabled, deviceShare(enabled, 0, HdhrConfig(deviceSize = 4), byRange = true))
    }
}
