package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.ItemKind
import dev.jamesrich.sofaking.data.Lineup
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.Program
import dev.jamesrich.sofaking.data.search
import dev.jamesrich.sofaking.data.searchSections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class WebSearchTest {
    private val at = Instant.parse("2026-11-27T20:00:00Z")
    private val news = Channel("a", 5.0, "News 5", logo = null, groups = listOf("News"))
    private val movies = Channel("b", 7.0, "Report Channel", logo = null, groups = emptyList())
    private val evening = Program("a", at - 1.hours, at + 1.hours, "Evening Report", description = null)
    private val late = Program("a", at + 3.hours, at + 4.hours, "Late Report", description = null)
    private val lineup = Lineup(listOf(news, movies), mapOf("a" to listOf(evening, late)))

    private fun web(item: MediaItem) = WebItem(
        item.id, item.libraryId, item.kind.name.lowercase(), item.title, null, null, null, null, null, null, null,
        true, 0, 0, 0.0, false, null,
    )

    @Test
    fun `a search crosses with its channels on now and its library rows and its programs coming up`() {
        val found = listOf(
            MediaItem("m1", "movies", ItemKind.MOVIE, "The Report"),
            MediaItem("m2", "movies", ItemKind.MOVIE, "Night Train"),
            MediaItem("e1", "shows", ItemKind.EPISODE, "Report Card"),
        )

        val results =
            searchToWeb(lineup.search("report", at), searchSections(found, "report", listOf("Ada Report")), ::web)

        assertEquals("report", results.query)
        assertEquals(listOf("b", "a"), results.now.map { it.channel.id })
        assertNull(results.now.first().program)
        assertEquals("Evening Report", results.now.last().program?.title)
        assertEquals(listOf("movies", "cast_and_crew", "episodes"), results.rows.map { it.group })
        assertEquals(listOf("m2"), results.rows[1].items.map { it.id })
        assertEquals(listOf("Late Report"), results.later.map { it.program?.title })
        assertEquals(late.start.toEpochMilliseconds().toDouble(), results.later.single().program?.startMs)
        assertEquals(listOf("Ada Report"), results.people.toList())
    }

    @Test
    fun `a search that finds nothing crosses empty`() {
        val results = searchToWeb(lineup.search("zebra", at), searchSections(emptyList(), "zebra"), ::web)

        assertEquals(0, results.now.size + results.rows.size + results.later.size)
    }
}
