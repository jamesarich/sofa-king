package dev.jamesrich.sofaking.ui.player

import dev.jamesrich.sofaking.data.Channel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ChannelNumberEntryTest {
    private val channels = listOf(
        channel(number = 2.0, name = "KQED"),
        channel(number = 10.0, name = "CNN"),
        channel(number = 105.0, name = "ESPN"),
        channel(number = 7.5, name = "Odd"),
    )

    @Test
    fun `digits accumulate in order`() {
        val entry = ChannelNumberEntry().append(1).append(0)
        assertEquals("10", entry.digits)
    }

    @Test
    fun `digits past the maximum are ignored`() {
        var entry = ChannelNumberEntry()
        repeat(ChannelNumberEntry.MAX_DIGITS + 3) { entry = entry.append(9) }
        assertEquals("9".repeat(ChannelNumberEntry.MAX_DIGITS), entry.digits)
    }

    @Test
    fun `rejects a value that is not a digit`() {
        assertFailsWith<IllegalArgumentException> { ChannelNumberEntry().append(10) }
    }

    @Test
    fun `empty buffer matches nothing`() {
        assertNull(ChannelNumberEntry().indexIn(channels))
    }

    @Test
    fun `matches a whole channel number to its index in the zap list`() {
        assertEquals(1, ChannelNumberEntry().append(1).append(0).indexIn(channels))
        assertEquals(0, ChannelNumberEntry().append(2).indexIn(channels))
        assertEquals(2, ChannelNumberEntry().append(1).append(0).append(5).indexIn(channels))
    }

    @Test
    fun `a prefix of a channel number does not match`() {
        assertNull(ChannelNumberEntry().append(1).indexIn(channels))
    }

    @Test
    fun `no channel with that number gives null`() {
        assertNull(ChannelNumberEntry().append(4).append(2).indexIn(channels))
    }

    @Test
    fun `lookup only searches the list it is given`() {
        val subset = channels.filter { it.name == "CNN" }
        assertNull(ChannelNumberEntry().append(2).indexIn(subset))
        assertEquals(0, ChannelNumberEntry().append(1).append(0).indexIn(subset))
    }

    private fun channel(number: Double, name: String) = Channel(
        guideId = number.toString(),
        number = number,
        name = name,
        logo = null,
        groups = listOf("Test"),
    )
}
