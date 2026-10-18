package dev.jamesrich.sofaking.ui.library

import dev.jamesrich.sofaking.data.ItemKind
import dev.jamesrich.sofaking.data.MediaItem
import dev.jamesrich.sofaking.data.MediaItemDetail
import dev.jamesrich.sofaking.data.MediaShelf
import dev.jamesrich.sofaking.data.PlayProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryChoicesTest {
    private fun item(id: String, kind: ItemKind, progress: PlayProgress? = null, parentId: String? = null, unwatched: Int = 0, season: Int? = null, episode: Int? = null) =
        MediaItem(id, "lib", kind, "Title $id", parentId = parentId, progress = progress, unwatched = unwatched, season = season, episode = episode)

    @Test
    fun `continue watching and episode rows are wide and the rest are posters`() {
        assertEquals(CardStyle.WIDE, cardStyleFor(MediaShelf(CONTINUE_SHELF, "Continue", listOf(item("m", ItemKind.MOVIE)))))
        assertEquals(CardStyle.WIDE, cardStyleFor(MediaShelf("r", "Recent", listOf(item("e", ItemKind.EPISODE), item("v", ItemKind.VIDEO)))))
        assertEquals(CardStyle.POSTER, cardStyleFor(MediaShelf("r", "Recent", listOf(item("m", ItemKind.MOVIE), item("e", ItemKind.EPISODE)))))
        assertEquals(CardStyle.POSTER, cardStyleFor(MediaShelf("r", "Recent", emptyList())))
    }

    @Test
    fun `a movie in progress resumes and one not started plays`() {
        val started = item("m", ItemKind.MOVIE, PlayProgress(60_000, 600_000, watched = false))
        assertEquals(PrimaryAction.Resume(started, 60_000), primaryAction(MediaItemDetail(started)))
        val watched = item("m", ItemKind.MOVIE, PlayProgress(590_000, 600_000, watched = true))
        assertEquals(PrimaryAction.Play(watched), primaryAction(MediaItemDetail(watched)))
    }

    @Test
    fun `a show plays the episode the server names next and nothing without one`() {
        val next = item("e2", ItemKind.EPISODE, PlayProgress(30_000, 600_000, watched = false))
        assertEquals(PrimaryAction.Resume(next, 30_000), primaryAction(MediaItemDetail(item("s", ItemKind.SHOW), next = next)))
        assertNull(primaryAction(MediaItemDetail(item("s", ItemKind.SHOW))))
    }

    @Test
    fun `a show opens on the season holding the next episode`() {
        val seasons = listOf(
            item("s1", ItemKind.SEASON, season = 1),
            item("s2", ItemKind.SEASON, season = 2, unwatched = 3),
            item("s3", ItemKind.SEASON, season = 3, unwatched = 8),
        )
        val show = item("show", ItemKind.SHOW)
        assertEquals("s3", initialSeasonId(MediaItemDetail(show, seasons, next = item("e", ItemKind.EPISODE, parentId = "s3"))))
        assertEquals("s2", initialSeasonId(MediaItemDetail(show, seasons)), "else the first with something unwatched")
        assertEquals("s1", initialSeasonId(MediaItemDetail(show, seasons.map { it.copy(unwatched = 0) })), "else the first")
        assertNull(initialSeasonId(MediaItemDetail(item("m", ItemKind.MOVIE))))
    }

    @Test
    fun `running times round to the minute`() {
        assertEquals(1 to 48, runtimeParts(6_480_000))
        assertEquals(0 to 45, runtimeParts(2_699_000))
        assertNull(runtimeParts(20_000))
        assertNull(runtimeParts(null))
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
