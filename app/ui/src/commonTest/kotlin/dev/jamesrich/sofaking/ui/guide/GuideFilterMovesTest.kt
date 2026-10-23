package dev.jamesrich.sofaking.ui.guide

import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.ProgramKind
import dev.jamesrich.sofaking.data.Programme
import dev.jamesrich.sofaking.ui.player.ChannelNumberEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class GuideFilterMovesTest {
    private fun channel(id: String, group: String = "News") = Channel(id, id.toDouble(), "Ch $id", null, listOf(group))
    private val all = listOf(channel("100"), channel("101"), channel("300", "Sports"), channel("301", "Sports"))
    private val news = all.filter { "News" in it.groups }

    private fun typed(digits: String) = digits.fold(ChannelNumberEntry()) { entry, c -> entry.append(c.digitToInt()) }

    @Test
    fun `chips list every group once in the order its first channel appears`() {
        val local = Channel("5", 5.0, "KSNV", null, listOf("Local", "News"))
        val filters = guideFilters(listOf(local) + all)
        assertEquals(
            listOf(
                GuideFilter.All,
                GuideFilter.Favorites,
                GuideFilter.Recent,
                GuideFilter.Group("Local"),
                GuideFilter.Group("News"),
                GuideFilter.Group("Sports"),
            ),
            filters,
        )
    }

    @Test
    fun `counts include a channel in each of its groups`() {
        val local = Channel("5", 5.0, "KSNV", null, listOf("Local", "News"))
        val counts =
            guideFilterCounts(
                listOf(local) + all,
                favorites = setOf("300", "gone"),
                recent = listOf("101", "gone", "5"),
            )
        assertEquals(5, counts[GuideFilter.All])
        assertEquals(1, counts[GuideFilter.Favorites])
        assertEquals(2, counts[GuideFilter.Recent])
        assertEquals(1, counts[GuideFilter.Group("Local")])
        assertEquals(3, counts[GuideFilter.Group("News")])
        assertEquals(2, counts[GuideFilter.Group("Sports")])
    }

    @Test
    fun `recent channels keep their order and skip ones that left the lineup`() {
        assertEquals(listOf("301", "100"), recentChannels(all, listOf("301", "gone", "100")).map { it.guideId })
        assertEquals(emptyList(), recentChannels(all, emptyList()))
    }

    @Test
    fun `a filter round trips through its key and All is stored as nothing`() {
        assertNull(GuideFilter.All.key())
        for (filter in listOf(
            GuideFilter.Favorites,
            GuideFilter.Recent,
            GuideFilter.Group("Local News"),
            GuideFilter.OnNow(ProgramKind.SPORTS),
        )) {
            assertEquals(filter, guideFilterFromKey(filter.key()))
        }
        assertEquals(GuideFilter.All, guideFilterFromKey(null))
        assertEquals(GuideFilter.All, guideFilterFromKey("nonsense"))
        assertEquals(GuideFilter.All, guideFilterFromKey("group:"))
        assertEquals(GuideFilter.All, guideFilterFromKey("now:opera"))
    }

    @Test
    fun `kinds on now list the channels showing one and chips follow Recent`() {
        val at = Instant.parse("2026-10-19T12:10:00Z")
        fun show(id: String, start: String, stop: String, vararg kinds: ProgramKind) =
            Programme(id, Instant.parse(start), Instant.parse(stop), "P", null, kinds = kinds.toSet())
        val lineup = Lineup(
            all,
            mapOf(
                "100" to listOf(show("100", "2026-10-19T12:00:00Z", "2026-10-19T13:00:00Z", ProgramKind.NEWS)),
                "300" to
                    listOf(
                        show(
                            "300",
                            "2026-10-19T12:00:00Z",
                            "2026-10-19T14:00:00Z",
                            ProgramKind.SPORTS,
                            ProgramKind.KIDS,
                        ),
                    ),
                "301" to listOf(show("301", "2026-10-19T13:00:00Z", "2026-10-19T14:00:00Z", ProgramKind.SPORTS)),
            ),
        )
        val onNow = channelsOnNow(lineup, at)
        assertEquals(
            mapOf(
                ProgramKind.NEWS to listOf("100"),
                ProgramKind.SPORTS to listOf("300"),
                ProgramKind.KIDS to listOf("300"),
            ),
            onNow.mapValues { (_, c) -> c.map { it.guideId } },
        )
        assertEquals(
            listOf(
                GuideFilter.All,
                GuideFilter.Favorites,
                GuideFilter.Recent,
                GuideFilter.OnNow(ProgramKind.SPORTS),
                GuideFilter.OnNow(ProgramKind.KIDS),
                GuideFilter.OnNow(ProgramKind.NEWS),
                GuideFilter.Group("News"),
                GuideFilter.Group("Sports"),
            ),
            guideFilters(all, onNow.keys),
        )
        assertEquals(1, guideFilterCounts(all, emptySet(), emptyList(), onNow)[GuideFilter.OnNow(ProgramKind.SPORTS)])
    }

    @Test
    fun `a number on screen moves to its row`() {
        assertEquals(GuideNumberTarget.Row(1), guideNumberTarget(typed("101"), news, all))
    }

    @Test
    fun `a number the filter hides still finds its channel`() {
        assertEquals(GuideNumberTarget.Hidden("300"), guideNumberTarget(typed("300"), news, all))
    }

    @Test
    fun `a number no channel has is a miss`() {
        assertEquals(GuideNumberTarget.Missing, guideNumberTarget(typed("999"), news, all))
    }

    @Test
    fun `a filter change keeps the focused channel in its slot when it is still listed`() {
        assertEquals(2 - FOCUS_ROW_SLOT, firstItemAfterFilter(all, "300"))
        assertEquals(0, firstItemAfterFilter(all, "100"))
    }

    @Test
    fun `a filter change that hides the focused channel starts at the top`() {
        val sports = all.filter { "Sports" in it.groups }
        assertEquals(0, firstItemAfterFilter(sports, "101"))
        assertEquals(0, firstItemAfterFilter(sports, null))
    }
}
