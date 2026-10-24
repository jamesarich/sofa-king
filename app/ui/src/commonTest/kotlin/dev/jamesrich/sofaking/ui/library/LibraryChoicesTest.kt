package dev.jamesrich.sofaking.ui.library

import dev.jamesrich.sofaking.data.CONTINUE_SHELF
import dev.jamesrich.sofaking.data.ItemKind
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.MediaShelf
import dev.jamesrich.sofaking.data.PlayProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class LibraryChoicesTest {
    private fun item(
        id: String,
        kind: ItemKind,
        progress: PlayProgress? = null,
        parentId: String? = null,
        unwatched: Int = 0,
        season: Int? = null,
        episode: Int? = null,
    ) = MediaItem(
        id, "lib", kind, "Title $id", parentId = parentId, progress = progress, unwatched = unwatched, season = season,
        episode = episode,
    )

    @Test
    fun `continue watching and episode rows are wide and the rest are posters`() {
        assertEquals(
            CardStyle.WIDE,
            cardStyleFor(MediaShelf(CONTINUE_SHELF, "Continue", listOf(item("m", ItemKind.MOVIE)))),
        )
        assertEquals(
            CardStyle.WIDE,
            cardStyleFor(MediaShelf("r", "Recent", listOf(item("e", ItemKind.EPISODE), item("v", ItemKind.VIDEO)))),
        )
        assertEquals(
            CardStyle.POSTER,
            cardStyleFor(MediaShelf("r", "Recent", listOf(item("m", ItemKind.MOVIE), item("e", ItemKind.EPISODE)))),
        )
        assertEquals(CardStyle.POSTER, cardStyleFor(MediaShelf("r", "Recent", emptyList())))
    }

    @Test
    fun `search shows movies and shows as posters and episodes and videos as wide cards in their order`() {
        val sections =
            searchSections(
                listOf(
                    item("e1", ItemKind.EPISODE),
                    item("m", ItemKind.MOVIE),
                    item("v", ItemKind.VIDEO),
                    item("s", ItemKind.SHOW),
                ),
            )
        assertEquals(listOf("m", "s"), sections.posters.map { it.id })
        assertEquals(listOf("e1", "v"), sections.wide.map { it.id })
    }

    @Test
    fun `running times round to the minute`() {
        assertEquals(1.hours + 48.minutes, runtime(6_480_000))
        assertEquals(45.minutes, runtime(2_699_000))
        assertNull(runtime(20_000))
        assertNull(runtime(null))
    }

    @Test
    fun `a card's menu offers the start for what is partway in and the watched toggle`() {
        val partway = item("m", ItemKind.MOVIE, PlayProgress(60_000, 6_000_000, false))
        assertEquals(
            listOf(CardMenuEntry.PLAY_FROM_START, CardMenuEntry.MARK_WATCHED),
            cardMenuEntries(partway, playFromStart = true),
        )
        assertEquals(listOf(CardMenuEntry.MARK_WATCHED), cardMenuEntries(partway, playFromStart = false))
        assertEquals(
            listOf(CardMenuEntry.DETAILS, CardMenuEntry.PLAY_FROM_START, CardMenuEntry.MARK_WATCHED),
            cardMenuEntries(partway, playFromStart = true, details = true),
        )
        assertEquals(
            listOf(CardMenuEntry.MARK_UNWATCHED),
            cardMenuEntries(item("m", ItemKind.MOVIE, PlayProgress(0, 6_000_000, true)), playFromStart = true),
        )
        val show = MediaItem("s", "lib", ItemKind.SHOW, "Show", children = 2, unwatched = 0)
        assertEquals(listOf(CardMenuEntry.MARK_UNWATCHED), cardMenuEntries(show, playFromStart = true))
        assertEquals(
            listOf(CardMenuEntry.MARK_WATCHED),
            cardMenuEntries(show.copy(unwatched = 3), playFromStart = true),
        )
    }

    @Test
    fun `the next page loads near the end of what is loaded`() {
        assertTrue(shouldLoadMore(lastVisibleIndex = 45, loaded = 60, hasMore = true))
        assertFalse(shouldLoadMore(lastVisibleIndex = 20, loaded = 60, hasMore = true))
        assertFalse(shouldLoadMore(lastVisibleIndex = 59, loaded = 60, hasMore = false))
        assertFalse(shouldLoadMore(lastVisibleIndex = -1, loaded = 0, hasMore = true))
    }

    @Test
    fun `the picture badge follows the height`() {
        assertEquals("4K", pictureBadge(2160))
        assertEquals("HD", pictureBadge(1080))
        assertNull(pictureBadge(480))
        assertNull(pictureBadge(null))
    }
}
