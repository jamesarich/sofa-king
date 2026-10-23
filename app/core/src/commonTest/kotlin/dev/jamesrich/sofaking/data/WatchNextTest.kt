package dev.jamesrich.sofaking.data

import dev.jamesrich.sofaking.LibraryHomeState
import dev.jamesrich.sofaking.contract.Wire
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WatchNextTest {
    private fun item(id: String, kind: ItemKind = ItemKind.EPISODE) =
        MediaItem(id = id, libraryId = "tv", kind = kind, title = id)

    private fun home(vararg shelves: MediaShelf) = LibraryHomeState.Ready(MediaHome(shelves.toList(), emptyList()))

    @Test
    fun `the row mirrors the Continue watching shelf`() {
        val shelves = home(
            MediaShelf("recent", "Recently added", listOf(item("new"))),
            MediaShelf(
                Wire.ShelfId.CONTINUE,
                "Continue watching",
                listOf(item("e2"), item("m1", ItemKind.MOVIE), item("show", ItemKind.SHOW)),
            ),
        )
        assertEquals(listOf("e2", "m1"), watchNextItems(shelves)?.map { it.id }, "only what plays")
        assertEquals(emptyList(), watchNextItems(home(MediaShelf("recent", "Recently added", listOf(item("new"))))))
    }

    @Test
    fun `the row clears with no server and stays while the shelves load`() {
        assertEquals(emptyList(), watchNextItems(LibraryHomeState.Unconfigured))
        assertEquals(emptyList(), watchNextItems(LibraryHomeState.Unavailable))
        assertNull(watchNextItems(LibraryHomeState.Loading))
        assertNull(watchNextItems(LibraryHomeState.Failed("offline")))
    }

    @Test
    fun `an item link round trips`() {
        assertEquals("sofaking://vod/abc123", itemLink("abc123"))
        assertEquals("a b", parseItemLink(itemLink("a b")))
        assertEquals("abc123", parseItemLink(" sofaking://vod/abc123 "))
    }

    @Test
    fun `other links are refused`() {
        assertNull(parseItemLink("sofaking://pair?server=http://h&code=123456"))
        assertNull(parseItemLink("sofaking://vod/"))
        assertNull(parseItemLink("sofaking://vod/a/b"))
        assertNull(parseItemLink("https://vod/abc"))
        assertNull(parseItemLink(""))
    }
}
