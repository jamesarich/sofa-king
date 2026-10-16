package dev.jamesrich.sofaking.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ChannelPreferencesTest {
    private fun channel(id: String) = Channel(id, id.toDouble(), "Channel $id", null, listOf("Test"))
    private val channels = listOf("1", "2", "3", "4").map(::channel)

    @Test
    fun `hidden channels leave the list in lineup order`() {
        assertEquals(listOf("1", "3"), visibleChannels(channels, setOf("2", "4", "9")).map { it.guideId })
        assertSame(channels, visibleChannels(channels, emptySet()))
    }

    @Test
    fun `favorites follow the stored order then the lineup`() {
        val favorites = setOf("1", "2", "4")
        assertEquals(listOf("1", "2", "4"), orderedFavorites(channels, favorites, emptyList()).map { it.guideId })
        assertEquals(listOf("4", "1", "2"), orderedFavorites(channels, favorites, listOf("4", "3", "9", "4")).map { it.guideId })
    }

    @Test
    fun `a favorite moves by steps and stops at the ends`() {
        val order = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), movedFavorite(order, "b", -1))
        assertEquals(listOf("b", "c", "a"), movedFavorite(order, "a", 5))
        assertEquals(order, movedFavorite(order, "a", -1))
        assertEquals(order, movedFavorite(order, "zz", 1))
    }

    @Test
    fun `groups follow first appearance and count what is shown`() {
        val lineup = listOf(
            Channel("1", 1.0, "One", null, listOf("Local", "News")),
            Channel("2", 2.0, "Two", null, listOf("News")),
            Channel("3", 3.0, "Three", null, listOf("Sports")),
        )
        val groups = channelGroups(lineup, hidden = setOf("2", "3"))
        assertEquals(listOf("Local", "News", "Sports"), groups.map { it.name })
        assertEquals(listOf(listOf("1"), listOf("1", "2"), listOf("3")), groups.map { g -> g.channels.map { it.guideId } })
        assertEquals(listOf(1, 1, 0), groups.map { it.shown })
        assertEquals(listOf(true, true, false), groups.map { it.anyShown })
        assertEquals(2, groups[1].total)
    }
}
