package dev.jamesrich.sofaking.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LibraryChoicesTest {
    private fun item(id: String, kind: ItemKind, progress: PlayProgress? = null, parentId: String? = null, unwatched: Int = 0, season: Int? = null) =
        MediaItem(id, "lib", kind, "Title $id", parentId = parentId, progress = progress, unwatched = unwatched, season = season)

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
    fun `the episode after one is the next in its season and none after the last`() {
        val episodes = listOf(item("e1", ItemKind.EPISODE), item("e2", ItemKind.EPISODE), item("e3", ItemKind.EPISODE))
        assertEquals("e2", episodeAfter(episodes, "e1")?.id)
        assertNull(episodeAfter(episodes, "e3"))
        assertNull(episodeAfter(episodes, "missing"))
    }

    @Test
    fun `the season after one skips Specials`() {
        val seasons = listOf(item("s0", ItemKind.SEASON, season = 0), item("s1", ItemKind.SEASON, season = 1), item("s2", ItemKind.SEASON, season = 2))
        assertEquals("s2", seasonAfter(seasons, "s1")?.id)
        assertEquals("s1", seasonAfter(seasons, "s0")?.id)
        assertNull(seasonAfter(seasons, "s2"))
        assertNull(seasonAfter(listOf(item("s1", ItemKind.SEASON, season = 1), item("s0", ItemKind.SEASON, season = 0)), "s1"))
    }
}
