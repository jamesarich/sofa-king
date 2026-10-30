package dev.jamesrich.sofaking.ui.library

import dev.jamesrich.sofaking.data.CONTINUE_SHELF
import dev.jamesrich.sofaking.data.ItemKind
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.MediaShelf
import dev.jamesrich.sofaking.data.RECENT_SHELF
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpotlightChoicesTest {
    private fun item(id: String) = MediaItem(id, "lib", ItemKind.MOVIE, "Title $id")

    private val continuing = MediaShelf(CONTINUE_SHELF, "Continue watching", listOf(item("c1"), item("c2")))
    private val recent =
        MediaShelf(RECENT_SHELF, "Recently added", listOf(item("r1"), item("r2")), libraryId = "movies")

    @Test
    fun `the Continue watching menu offers the page and the others do not`() {
        val opened = mutableListOf<String>()
        val menu = CardMenu(onPlayFromStart = {}, onSetWatched = { _, _ -> })
        val actions = LibraryHomeActions({ opened += it.id }, {}, {}, {}, menu = menu, onRemoveFromContinue = {})
        actions.menuFor(continuing)!!.onOpen!!(item("c1"))
        assertEquals(listOf("c1"), opened)
        assertNull(actions.menuFor(recent)!!.onOpen)
    }

    @Test
    fun `the placeholder rows are a wide row then a poster row`() {
        assertEquals(listOf(CardStyle.WIDE, CardStyle.POSTER), skeletonStyles())
    }

    @Test
    fun `a placeholder card is as big as the real one`() {
        val (width, ratio) = skeletonCardSize(CardStyle.POSTER)
        assertEquals(POSTER_WIDTH, width)
        // A float constant is folded at compile time, so compare within a tolerance: JS computes 2f / 3f as a double.
        assertEquals(2f / 3f, ratio, 0.0001f)
        assertEquals(WIDE_WIDTH, skeletonCardSize(CardStyle.WIDE).first)
    }
}
