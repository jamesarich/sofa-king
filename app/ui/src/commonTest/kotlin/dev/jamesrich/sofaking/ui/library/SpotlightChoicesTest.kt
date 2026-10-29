package dev.jamesrich.sofaking.ui.library

import dev.jamesrich.sofaking.data.CONTINUE_SHELF
import dev.jamesrich.sofaking.data.ItemKind
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.MediaShelf
import dev.jamesrich.sofaking.data.PlayProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpotlightChoicesTest {
    private fun item(
        id: String,
        kind: ItemKind = ItemKind.MOVIE,
        overview: String? = null,
        progress: PlayProgress? = null,
    ) = MediaItem(id, "lib", kind, "Title $id", overview = overview, progress = progress)

    private val continuing = MediaShelf(CONTINUE_SHELF, "Continue watching", listOf(item("c1"), item("c2")))
    private val recent =
        MediaShelf(RECENT_SHELF, "Recently added", listOf(item("r1"), item("r2")), libraryId = "movies")
    private val watchlist = MediaShelf("watchlist", "Watchlist", listOf(item("w1")))

    @Test
    fun `the spotlight shows the first Continue watching item until focus moves`() {
        assertEquals("c1", spotlightItem(listOf(watchlist, continuing, recent), null)?.id)
    }

    @Test
    fun `the spotlight follows focus onto a Continue watching or recently added card`() {
        assertEquals("c2", spotlightItem(listOf(continuing, recent), "c2")?.id)
        assertEquals("r2", spotlightItem(listOf(continuing, recent), "r2")?.id)
    }

    @Test
    fun `the spotlight keeps its item when focus is on another row or an item that left`() {
        assertEquals("c1", spotlightItem(listOf(continuing, watchlist, recent), "w1")?.id)
        assertEquals("c1", spotlightItem(listOf(continuing, recent), "gone")?.id)
    }

    @Test
    fun `the spotlight falls back to recently added without Continue watching`() {
        assertEquals("r1", spotlightItem(listOf(watchlist, recent), null)?.id)
    }

    @Test
    fun `the spotlight skips music and is empty without a candidate`() {
        val songs = MediaShelf(
            RECENT_SHELF,
            "Recently added",
            listOf(item("t1", ItemKind.TRACK), item("a1", ItemKind.ALBUM)),
        )
        assertNull(spotlightItem(listOf(songs, watchlist), null))
        assertNull(spotlightItem(emptyList(), "c1"))
    }

    @Test
    fun `OK plays on Continue watching and opens the page on every other row`() {
        assertEquals(CardOk.PLAY, cardOkFor(continuing, item("c1")))
        assertEquals(CardOk.PLAY, cardOkFor(continuing, item("e", ItemKind.EPISODE)))
        assertEquals(CardOk.OPEN_PAGE, cardOkFor(recent, item("r1")))
        assertEquals(CardOk.OPEN_PAGE, cardOkFor(watchlist, item("w1")))
    }

    @Test
    fun `OK opens the page of a Continue watching item that does not play`() {
        assertEquals(CardOk.OPEN_PAGE, cardOkFor(continuing, item("s", ItemKind.SHOW)))
    }

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
    fun `the button reads Resume for an item partway in`() {
        assertEquals(true, resumes(item("m", progress = PlayProgress(60_000, 6_000_000, false))))
        assertEquals(false, resumes(item("m")))
    }

    @Test
    fun `the tagline is the first sentence kept short`() {
        val two = item("m", overview = "A crew finds a signal. Then it answers.")
        assertEquals("A crew finds a signal.", spotlightTagline(two))
        assertEquals("Version 2.0 ships soon", spotlightTagline(item("m", overview = "Version 2.0 ships soon")))
        assertEquals("abcd…", spotlightTagline(item("m", overview = "abcdefgh"), limit = 5))
        assertNull(spotlightTagline(item("m", overview = "  ")))
        assertNull(spotlightTagline(item("m")))
    }

    @Test
    fun `the placeholder rows are a wide row then a poster row`() {
        assertEquals(listOf(CardStyle.WIDE, CardStyle.POSTER), skeletonStyles())
    }

    @Test
    fun `a placeholder card is as big as the real one`() {
        assertEquals(POSTER_WIDTH to (2f / 3f), skeletonCardSize(CardStyle.POSTER))
        assertEquals(WIDE_WIDTH, skeletonCardSize(CardStyle.WIDE).first)
    }
}
