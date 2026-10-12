package dev.jamesrich.sofaking.ui.player

import dev.jamesrich.sofaking.data.Channel
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

class PlayerChannelsTest {
    private fun channel(guideId: String, number: Double, name: String = "Ch $guideId") =
        Channel(guideId, number, name, null, listOf("Test"))

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

    // resolveReplacement

    @Test
    fun `a re-imported channel is followed to the new id with its number`() {
        val reimported = listOf(channel("a", 5.0), channel("b", 8.0, "Renamed"), channel("c", 12.0))
        assertEquals(1, resolveReplacement(reimported, eight))
    }

    @Test
    fun `a renumbered channel is followed by its name ignoring case`() {
        val reimported = listOf(channel("a", 5.0), channel("b", 9.0, "ch 8"), channel("c", 12.0))
        assertEquals(1, resolveReplacement(reimported, eight))
    }

    @Test
    fun `the number wins over the name`() {
        val reimported = listOf(channel("x", 8.0, "Other"), channel("y", 9.0, "Ch 8"))
        assertEquals(0, resolveReplacement(reimported, eight))
    }

    @Test
    fun `the dead id itself is not a replacement`() {
        assertNull(resolveReplacement(listOf(five, eight, twelve), eight))
        assertNull(resolveReplacement(listOf(five, twelve), eight))
        assertNull(resolveReplacement(emptyList(), eight))
    }
}
