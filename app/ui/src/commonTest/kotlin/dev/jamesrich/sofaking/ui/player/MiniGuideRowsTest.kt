package dev.jamesrich.sofaking.ui.player

import dev.jamesrich.sofaking.data.Channel
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

class MiniGuideRowsTest {
    private fun channel(id: String, group: String = "News") = Channel(guideId = id, number = id.toDouble(), name = "Ch $id", logo = null, groups = listOf(group))
    private val lineup = listOf(channel("1"), channel("2", "Sports"), channel("3"), channel("4", "Sports"))

    private fun rows(
        filter: MiniGuideFilter,
        scope: Set<String>? = null,
        favorites: Set<String> = emptySet(),
        recent: List<String> = emptyList(),
        previous: String? = null,
    ) = miniGuideRows(filter, lineup, scope, favorites, recent, previous)

    @Test
    fun `without a last channel every channel is listed in lineup order`() {
        val rows = rows(MiniGuideFilter.ALL)
        assertEquals(listOf("1", "2", "3", "4"), rows.map { it.channel.guideId })
        assertTrue(rows.none { it.pinnedLast })
    }

    @Test
    fun `the last channel is pinned first and also keeps its place in the list`() {
        val rows = rows(MiniGuideFilter.ALL, previous = "3")
        assertEquals(listOf("3", "1", "2", "3", "4"), rows.map { it.channel.guideId })
        assertTrue(rows.first().pinnedLast)
        assertFalse(rows.drop(1).any { it.pinnedLast })
    }

    @Test
    fun `row keys stay unique when the last channel is duplicated`() {
        val keys = rows(MiniGuideFilter.ALL, previous = "3").map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `a last channel the lineup no longer has is not pinned`() {
        assertEquals(4, rows(MiniGuideFilter.ALL, previous = "9").size)
    }

    @Test
    fun `the scope chip lists the guide filter's channels and pins the last one only when it is inside`() {
        val scope = setOf("2", "4")
        assertEquals(listOf("2", "4"), rows(MiniGuideFilter.SCOPE, scope = scope).map { it.channel.guideId })
        assertEquals(listOf("4", "2", "4"), rows(MiniGuideFilter.SCOPE, scope = scope, previous = "4").map { it.channel.guideId })
        assertEquals(listOf("2", "4"), rows(MiniGuideFilter.SCOPE, scope = scope, previous = "1").map { it.channel.guideId })
    }

    @Test
    fun `favorites and recent list without the pinned row and recent keeps the watched order`() {
        assertEquals(listOf("1", "4"), rows(MiniGuideFilter.FAVORITES, favorites = setOf("4", "1"), previous = "1").map { it.channel.guideId })
        val recent = rows(MiniGuideFilter.RECENT, recent = listOf("4", "9", "1"), previous = "4")
        assertEquals(listOf("4", "1"), recent.map { it.channel.guideId })
        assertTrue(recent.none { it.pinnedLast })
    }

    @Test
    fun `focus lands on the current channel's own row or the top`() {
        val rows = rows(MiniGuideFilter.ALL, previous = "3")
        assertEquals(3, miniGuideFocusRow(rows, "3"))
        assertFalse(rows[miniGuideFocusRow(rows, "3")].pinnedLast)
        assertEquals(0, miniGuideFocusRow(rows, "9"))
    }

    @Test
    fun `chips show Recent once something was watched and Scope only from a guide filter`() {
        assertEquals(listOf(MiniGuideFilter.FAVORITES, MiniGuideFilter.ALL), miniGuideFilters(hasRecent = false, scoped = false))
        assertEquals(
            listOf(MiniGuideFilter.RECENT, MiniGuideFilter.FAVORITES, MiniGuideFilter.SCOPE, MiniGuideFilter.ALL),
            miniGuideFilters(hasRecent = true, scoped = true),
        )
        assertEquals(MiniGuideFilter.SCOPE, miniGuideDefaultFilter(scoped = true))
        assertEquals(MiniGuideFilter.ALL, miniGuideDefaultFilter(scoped = false))
    }
}
