package dev.jamesrich.sofaking.data

import kotlin.test.Test
import kotlin.test.assertEquals

class KidsModeTest {
    private fun channel(id: String, vararg groups: String) =
        Channel(id, id.toDouble(), "Channel $id", null, groups.toList())

    private fun item(id: String, rating: String?) =
        MediaItem(id = id, libraryId = "movies", kind = ItemKind.MOVIE, title = id, contentRating = rating)

    @Test
    fun `kids mode keeps the channels filed under a kids group in lineup order`() {
        val channels = listOf(channel("1", "News"), channel("2", "Kids"), channel("3", "Local", "Children's"))
        assertEquals(listOf("2", "3"), KidsMode.channels(channels).map { it.guideId })
    }

    @Test
    fun `kids mode keeps titles rated for children and hides unrated ones`() {
        val items = listOf(item("a", "PG"), item("b", "tv-y7"), item("c", "PG-13"), item("d", null), item("e", " G "))
        assertEquals(listOf("a", "b", "e"), items.filter(KidsMode::allows).map { it.id })
    }

    @Test
    fun `kids mode drops the shelves it leaves empty`() {
        val home = MediaHome(
            listOf(
                MediaShelf("continue", "Continue watching", listOf(item("a", "R"))),
                MediaShelf("recent", "Recently added", listOf(item("b", "R"), item("c", "G"))),
            ),
            emptyList(),
        )
        val shown = KidsMode.home(home).shelves
        assertEquals(listOf("recent"), shown.map { it.id })
        assertEquals(listOf("c"), shown.single().items.map { it.id })
    }
}
