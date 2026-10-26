package dev.jamesrich.sofaking.ui.library

import dev.jamesrich.sofaking.data.CONTINUE_SHELF
import dev.jamesrich.sofaking.data.ItemKind
import dev.jamesrich.sofaking.data.LibraryKind
import dev.jamesrich.sofaking.data.LibrarySort
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
    fun `search groups movies shows the query's person episodes and videos in their order`() {
        val sections =
            searchSections(
                listOf(
                    item("m", ItemKind.MOVIE).copy(title = "Amber Road"),
                    item("s", ItemKind.SHOW).copy(title = "Amber Nights"),
                    item("v", ItemKind.VIDEO).copy(title = "Amber at dusk"),
                    item("c", ItemKind.MOVIE).copy(title = "Night Train"),
                    item("e1", ItemKind.EPISODE).copy(title = "Amber returns"),
                ),
                " amber ",
                listOf("Amber Heard"),
            )
        assertEquals(
            listOf(
                SearchGroup.MOVIES,
                SearchGroup.SHOWS,
                SearchGroup.CAST_AND_CREW,
                SearchGroup.EPISODES,
                SearchGroup.VIDEOS,
            ),
            sections.rows.map { it.group },
        )
        assertEquals(listOf("c"), sections.rows.single { it.group == SearchGroup.CAST_AND_CREW }.items.map { it.id })
        assertEquals("amber", sections.query)
        assertEquals(listOf("Amber Heard"), sections.people)
        assertEquals(CardStyle.WIDE, SearchGroup.EPISODES.style)
        assertFalse(searchSections(emptyList(), "amber").found)
    }

    @Test
    fun `music rows and grids are square and search groups artists albums and songs after the rest`() {
        val album = item("a", ItemKind.ALBUM).copy(title = "Amber", artist = "The Band", year = 2001)
        assertEquals(CardStyle.SQUARE, cardStyleFor(MediaShelf("r", "Recent", listOf(album))))
        assertEquals(CardStyle.SQUARE, gridStyleFor(LibraryKind.MUSIC))
        assertEquals(CardStyle.WIDE, gridStyleFor(LibraryKind.VIDEOS))
        assertEquals(CardStyle.POSTER, gridStyleFor(LibraryKind.MOVIES))
        val sections = searchSections(
            listOf(
                item("t", ItemKind.TRACK).copy(title = "Ambergris"),
                album,
                item("r", ItemKind.ARTIST).copy(title = "Amber Arcades"),
                item("m", ItemKind.MOVIE).copy(title = "Amber Road"),
            ),
            "amber",
        )
        assertEquals(
            listOf(SearchGroup.MOVIES, SearchGroup.ARTISTS, SearchGroup.ALBUMS, SearchGroup.TRACKS),
            sections.rows.map { it.group },
        )
        assertEquals("The Band · 2001", squareSubtitle(album))
        assertNull(squareSubtitle(item("r", ItemKind.ARTIST)))
    }

    @Test
    fun `a music grid sorts by name or date added and has no unwatched filter`() {
        assertEquals(listOf(LibrarySort.TITLE, LibrarySort.ADDED), gridSorts(LibraryKind.MUSIC))
        assertFalse(gridFiltersUnwatched(LibraryKind.MUSIC))
        assertEquals(LibrarySort.entries, gridSorts(LibraryKind.SHOWS))
        assertTrue(gridFiltersUnwatched(LibraryKind.SHOWS))
    }

    @Test
    fun `a track's number carries its disc only on an album of several`() {
        val one = item("1", ItemKind.TRACK).copy(track = 5, disc = 1)
        val two = item("2", ItemKind.TRACK).copy(track = 3, disc = 2)
        assertFalse(hasDiscs(listOf(one, one.copy(track = 6))))
        assertTrue(hasDiscs(listOf(one, two)))
        assertEquals("5", trackNumber(one, discs = false))
        assertEquals("2-03", trackNumber(two, discs = true))
        assertNull(trackNumber(item("3", ItemKind.TRACK), discs = true))
    }

    @Test
    fun `a song's card has no menu entries to mark it watched or play it from the start`() {
        val track = item("t", ItemKind.TRACK, PlayProgress(60_000, 200_000, watched = false))
        assertEquals(emptyList(), cardMenuEntries(track, playFromStart = true))
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
    fun `only Continue watching's cards offer to leave it and last`() {
        val partway = item("m", ItemKind.MOVIE, PlayProgress(60_000, 6_000_000, false))
        assertEquals(
            listOf(CardMenuEntry.PLAY_FROM_START, CardMenuEntry.MARK_WATCHED, CardMenuEntry.REMOVE_FROM_CONTINUE),
            cardMenuEntries(partway, playFromStart = true, removeFromContinue = true),
        )
        val menu = CardMenu(onPlayFromStart = {}, onSetWatched = { _, _ -> })
        val actions = LibraryHomeActions({}, {}, {}, {}, menu = menu, onRemoveFromContinue = {})
        val continuing = MediaShelf(CONTINUE_SHELF, "Continue watching", listOf(partway))
        assertTrue(actions.menuFor(continuing)!!.onRemoveFromContinue != null)
        assertNull(actions.menuFor(MediaShelf("recent", "Recently added", listOf(partway)))!!.onRemoveFromContinue)
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
