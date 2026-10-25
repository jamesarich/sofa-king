package dev.jamesrich.sofaking.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
    fun `the visible lineup drops hidden channels and in kids mode all but the kids ones`() {
        val kidsChannel = Channel("5", 5.0, "Cartoons", null, listOf("Kids"))
        val lineup = Lineup(channels + kidsChannel, emptyMap())
        assertSame(lineup, visibleLineup(lineup, emptySet(), kids = false))
        assertEquals(
            listOf("1", "3", "5"),
            visibleLineup(lineup, setOf("2", "4"), kids = false).channels.map {
                it.guideId
            },
        )
        assertEquals(listOf("5"), visibleLineup(lineup, setOf("2"), kids = true).channels.map { it.guideId })
        assertEquals(emptyList(), visibleLineup(lineup, setOf("5"), kids = true).channels)
    }

    @Test
    fun `the player zaps through hidden channels unless asked and kids mode plays only its own`() {
        val lineup = Lineup(channels, emptyMap())
        val visible = visibleLineup(lineup, setOf("2"), kids = false)
        assertSame(lineup, playerLineup(lineup, visible, "1", null))
        assertSame(lineup, playerLineup(lineup, visible, "1", AppSettings(hiddenChannels = setOf("2"))))
        val skipping = AppSettings(hiddenChannels = setOf("2"), zapSkipsHidden = true)
        assertSame(visible, playerLineup(lineup, visible, "1", skipping))
        assertSame(lineup, playerLineup(lineup, visible, "2", skipping), "a hidden channel opened by number zaps all")
        val kids = AppSettings(kidsMode = true)
        assertSame(visible, playerLineup(lineup, visible, "1", kids))
        assertNull(playerLineup(lineup, visible, "2", kids))
    }

    @Test
    fun `favorites follow the stored order then the lineup`() {
        val favorites = setOf("1", "2", "4")
        assertEquals(listOf("1", "2", "4"), orderedFavorites(channels, favorites, emptyList()).map { it.guideId })
        assertEquals(
            listOf("4", "1", "2"),
            orderedFavorites(channels, favorites, listOf("4", "3", "9", "4")).map {
                it.guideId
            },
        )
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
        assertEquals(
            listOf(listOf("1"), listOf("1", "2"), listOf("3")),
            groups.map { g ->
                g.channels.map { it.guideId }
            },
        )
        assertEquals(listOf(1, 1, 0), groups.map { it.shown })
        assertEquals(listOf(true, true, false), groups.map { it.anyShown })
        assertEquals(2, groups[1].total)
    }
}
