package dev.jamesrich.sofaking.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppLinkTest {
    private val lineup =
        Lineup(
            channels = listOf(
                Channel("news", 5.1, "News Now", null, listOf("News")),
                Channel("film", 12.0, "Movie Night", null, listOf("Movies")),
            ),
            guide = emptyMap(),
        )

    @Test
    fun `an item link may name where to start`() {
        assertEquals(AppLink.Item("abc123"), parseAppLink(" sofaking://vod/abc123 "))
        assertEquals(AppLink.Item("abc123", startMs = 90_000), parseAppLink("sofaking://vod/abc123?t=90"))
        assertEquals(AppLink.Item("abc123"), parseAppLink("sofaking://vod/abc123?t=soon"), "a start that is no number")
        assertEquals(AppLink.Item("a b"), parseAppLink(itemLink("a b")))
    }

    @Test
    fun `a live link names a channel and other links name nothing`() {
        assertEquals(AppLink.Live("5.1"), parseAppLink(liveLink("5.1")))
        assertEquals(AppLink.Live("Movie Night"), parseAppLink(liveLink("Movie Night")))
        assertNull(parseAppLink("sofaking://live/"))
        assertNull(parseAppLink("sofaking://pair?server=http://h&code=123456"))
        assertNull(parseAppLink("https://live/5"))
        assertNull(parseAppLink(""))
    }

    @Test
    fun `a details link round trips and one without an id names nothing`() {
        assertEquals(AppLink.Details("abc123"), parseAppLink(detailsLink("abc123")))
        assertEquals(AppLink.Details("a b"), parseAppLink(detailsLink("a b")))
        assertNull(parseAppLink("sofaking://item/"))
        assertNull(parseAppLink("sofaking://item"))
        assertNull(parseAppLink("sofaking://item/a/b"))
    }

    @Test
    fun `a live link finds its channel by number and then by name`() {
        assertEquals("news", linkedChannel(lineup, "5.1")?.guideId)
        assertEquals("film", linkedChannel(lineup, "12")?.guideId)
        assertEquals("film", linkedChannel(lineup, "movie night")?.guideId)
        assertNull(linkedChannel(lineup, "7"))
    }
}
