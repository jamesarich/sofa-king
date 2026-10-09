package dev.jamesrich.sofaking.ui.player

import dev.jamesrich.sofaking.data.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerChannelsTest {
    private fun channel(guideId: String, number: Double, name: String = "Ch $guideId") =
        Channel(guideId, number, name, null, "Test", "http://x/$guideId")

    private val five = channel("5", 5.0)
    private val eight = channel("8", 8.0)
    private val twelve = channel("12", 12.0)

    @Test
    fun `a channel keeps its row when the lineup is unchanged`() {
        assertEquals(1, indexAfterRefresh(listOf(five, eight, twelve), eight))
    }

    @Test
    fun `a channel is followed to its new row after a reorder`() {
        assertEquals(2, indexAfterRefresh(listOf(twelve, five, eight), eight))
    }

    @Test
    fun `a channel inserted before the tuned one shifts its row`() {
        assertEquals(2, indexAfterRefresh(listOf(channel("1", 1.0), five, eight), eight))
    }

    @Test
    fun `a renumbered channel is still found by guide id`() {
        assertEquals(1, indexAfterRefresh(listOf(five, channel("8", 8.1), twelve), eight))
    }

    @Test
    fun `the exact number wins over another channel sharing the guide id`() {
        val duplicate = channel("8", 8.1)
        assertEquals(2, indexAfterRefresh(listOf(five, duplicate, eight), eight))
    }

    @Test
    fun `a channel dropped from the lineup has no row`() {
        assertNull(indexAfterRefresh(listOf(five, twelve), eight))
        assertNull(indexAfterRefresh(emptyList(), eight))
    }
}
