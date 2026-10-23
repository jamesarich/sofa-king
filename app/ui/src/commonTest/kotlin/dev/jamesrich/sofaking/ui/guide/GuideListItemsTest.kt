package dev.jamesrich.sofaking.ui.guide

import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Program
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class GuideListItemsTest {
    private fun channel(id: String) = Channel(id, id.toDouble(), "Ch $id", null, listOf("News"))
    private val later = Program("101", Instant.parse("2026-10-12T20:00:00Z"), Instant.parse("2026-10-12T21:00:00Z"), "Late Show", null)
    private val channels = listOf(channel("100"), channel("101"), channel("102"))

    @Test
    fun `a later line follows the row of a channel with upcoming matches`() {
        val items = guideListItems(channels, mapOf("101" to listOf(later)))
        // A Double prints as "100.0" on the JVM and "100" on JS, so the keys are built the way the items build them.
        val (c100, c101, c102) = channels.map { "${it.guideId}#${it.number}" }
        assertEquals(listOf(c100, c101, "later:$c101", c102), items.map { it.key })
        assertEquals(GuideListItem.Later(channels[1], listOf(later)), items[2])
    }

    @Test
    fun `without a search the items are the rows alone`() {
        assertEquals(channels.map { GuideListItem.Row(it) }, guideListItems(channels, emptyMap()))
    }

    @Test
    fun `rows and later lines are found by channel`() {
        val items = guideListItems(channels, mapOf("101" to listOf(later)))
        assertEquals(3, rowIndexOf(items, channels[2]))
        assertEquals(2, laterIndexOf(items, "101"))
        assertEquals(-1, laterIndexOf(items, "100"))
        assertEquals(-1, rowIndexOf(items, channel("999")))
    }
}
