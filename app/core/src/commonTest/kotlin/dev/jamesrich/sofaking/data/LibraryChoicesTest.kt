package dev.jamesrich.sofaking.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryChoicesTest {
    private fun item(
        id: String,
        kind: ItemKind,
        progress: PlayProgress? = null,
        parentId: String? = null,
        unwatched: Int = 0,
        season: Int? = null,
    ) = MediaItem(
        id,
        "lib",
        kind,
        "Title $id",
        parentId = parentId,
        progress = progress,
        unwatched = unwatched,
        season = season,
    )

    @Test
    fun `a movie in progress resumes as does a rewatch part way and one not started plays`() {
        val started = item("m", ItemKind.MOVIE, PlayProgress(60_000, 600_000, watched = false))
        assertEquals(PrimaryAction.Resume(started, 60_000), primaryAction(MediaItemDetail(started)))
        val rewatch = item("m", ItemKind.MOVIE, PlayProgress(120_000, 600_000, watched = true))
        assertEquals(PrimaryAction.Resume(rewatch, 120_000), primaryAction(MediaItemDetail(rewatch)))
        val watched = item("m", ItemKind.MOVIE, PlayProgress(0, 600_000, watched = true))
        assertEquals(PrimaryAction.Play(watched), primaryAction(MediaItemDetail(watched)))
    }

    @Test
    fun `spoilers are hidden only on episodes not started yet`() {
        assertTrue(hidesSpoilers(item("e", ItemKind.EPISODE)))
        assertTrue(hidesSpoilers(item("e", ItemKind.EPISODE, PlayProgress(0, 600_000, watched = false))))
        assertFalse(
            hidesSpoilers(item("e", ItemKind.EPISODE, PlayProgress(30_000, 600_000, watched = false))),
            "started",
        )
        assertFalse(hidesSpoilers(item("e", ItemKind.EPISODE, PlayProgress(0, 600_000, watched = true))), "watched")
        assertFalse(hidesSpoilers(item("m", ItemKind.MOVIE)), "a movie's overview is what it is about")
    }

    @Test
    fun `a show plays the episode the server names next and nothing without one`() {
        val next = item("e2", ItemKind.EPISODE, PlayProgress(30_000, 600_000, watched = false))
        assertEquals(
            PrimaryAction.Resume(next, 30_000),
            primaryAction(MediaItemDetail(item("s", ItemKind.SHOW), next = next)),
        )
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
        assertEquals(
            "s3",
            initialSeasonId(MediaItemDetail(show, seasons, next = item("e", ItemKind.EPISODE, parentId = "s3"))),
        )
        assertEquals("s2", initialSeasonId(MediaItemDetail(show, seasons)), "else the first with something unwatched")
        assertEquals(
            "s1",
            initialSeasonId(
                MediaItemDetail(
                    show,
                    seasons.map {
                        it.copy(unwatched = 0)
                    },
                ),
            ),
            "else the first",
        )
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
        val seasons =
            listOf(
                item("s0", ItemKind.SEASON, season = 0),
                item("s1", ItemKind.SEASON, season = 1),
                item("s2", ItemKind.SEASON, season = 2),
            )
        assertEquals("s2", seasonAfter(seasons, "s1")?.id)
        assertEquals("s1", seasonAfter(seasons, "s0")?.id)
        assertNull(seasonAfter(seasons, "s2"))
        assertNull(
            seasonAfter(listOf(item("s1", ItemKind.SEASON, season = 1), item("s0", ItemKind.SEASON, season = 0)), "s1"),
        )
    }

    @Test
    fun `pickers list text subtitles and start on a forced one`() {
        val detail = MediaItemDetail(
            item("m", ItemKind.MOVIE),
            subtitles = listOf(
                MediaTrackInfo(3, "hdmv_pgs_subtitle", "eng"),
                MediaTrackInfo(4, "subrip", "eng"),
                MediaTrackInfo(5, "subrip", "eng", forced = true),
            ),
            audio = listOf(
                MediaTrackInfo(1, "ac3", "eng", channels = 6),
                MediaTrackInfo(2, "aac", "spa", default = true, channels = 2),
            ),
        )
        assertEquals(listOf(4, 5), textSubtitles(detail).map { it.index })
        assertEquals(listOf(true, false, false), detail.subtitles.map(::isPictureSubtitle))
        assertEquals(5, initialSubtitle(detail)?.index)
        assertEquals(2, defaultAudio(detail)?.index)
        assertNull(initialSubtitle(detail.copy(subtitles = emptyList())))
        val forcedPictures = detail.copy(
            subtitles = listOf(
                MediaTrackInfo(4, "subrip", "eng"),
                MediaTrackInfo(1000, "hdmv_pgs_subtitle", "eng", forced = true),
            ),
        )
        assertEquals(
            1000,
            initialSubtitle(forcedPictures)?.index,
            "a forced picture track is drawn in when there is no forced text",
        )
    }

    @Test
    fun `the tracks picked in a show start its next episode by language`() {
        val detail = MediaItemDetail(
            item("e", ItemKind.EPISODE),
            audio = listOf(MediaTrackInfo(1, "aac", "eng", default = true), MediaTrackInfo(2, "aac", "jpn")),
            subtitles = listOf(
                MediaTrackInfo(3, "subrip", "eng", forced = true),
                MediaTrackInfo(4, "hdmv_pgs_subtitle", "eng"),
                MediaTrackInfo(1000, "subrip", "en"),
            ),
        )
        val choice = trackChoice(detail, audioIndex = 2, subtitleIndex = 1000)
        assertEquals(TrackChoice("jpn", subtitles = true, subtitleLanguage = "en"), choice)
        assertEquals(2, initialAudio(detail, choice)?.index)
        assertEquals(1000, initialSubtitle(detail, choice)?.index, "a full track in the language, text before pictures")
        assertEquals(1, initialAudio(detail, null)?.index)
        assertEquals(
            3,
            initialSubtitle(detail, TrackChoice(subtitles = false))?.index,
            "off still shows forced dialogue",
        )
        assertEquals(
            1,
            initialAudio(detail, TrackChoice("kor"))?.index,
            "a language the file lacks falls back to the default",
        )
        assertEquals(TrackChoice("eng"), trackChoice(detail, audioIndex = 1, subtitleIndex = null))
        val withStream = detail.copy(subtitles = listOf(MediaTrackInfo(5, "subrip", "eng")) + detail.subtitles)
        assertEquals(
            1000,
            initialSubtitle(withStream, TrackChoice(subtitles = true, subtitleLanguage = "eng"))?.index,
            "a subtitle file beats the file's own text stream",
        )
    }

    @Test
    fun `a track label names its language title and format`() {
        assertEquals(
            "English · Commentary · AC3 5.1",
            trackLabel(MediaTrackInfo(1, "ac3", "eng", "Commentary", channels = 6), 1),
        )
        assertEquals("XYZ", trackLabel(MediaTrackInfo(3, "subrip", "xyz"), 1))
        assertEquals(
            "Spanish",
            trackLabel(MediaTrackInfo(3, "subrip", "spa", "Spanish"), 1),
            "a title repeating the language is dropped",
        )
        assertEquals("Track 2", trackLabel(MediaTrackInfo(3, "subrip"), 2))
        assertEquals(
            "English · SDH · External",
            trackLabel(MediaTrackInfo(1001, "subrip", "en", "SDH"), 2),
            "a subtitle file says so",
        )
    }

    @Test
    fun `file facts name the starting sound and each language once`() {
        val detail = MediaItemDetail(
            item(id = "m", kind = ItemKind.MOVIE),
            audio = listOf(
                MediaTrackInfo(1, "aac", "eng", channels = 2),
                MediaTrackInfo(2, "eac3", "en", channels = 6, default = true),
                MediaTrackInfo(3, "ac3", "spa", channels = 6),
                MediaTrackInfo(8, "aac", "zxx", channels = 2),
            ),
            subtitles = listOf(
                MediaTrackInfo(4, "subrip", "eng", forced = true),
                MediaTrackInfo(5, "subrip", "fre"),
                MediaTrackInfo(6, "hdmv_pgs_subtitle", "fra"),
                MediaTrackInfo(7, "subrip", "und"),
            ),
        )
        assertEquals(
            FileFacts("Dolby Digital Plus 5.1", listOf("English", "Spanish"), listOf("French")),
            fileFacts(detail),
        )
    }

    @Test
    fun `file facts are empty without tracks`() {
        assertEquals(
            FileFacts(null, emptyList(), emptyList()),
            fileFacts(MediaItemDetail(item(id = "s", kind = ItemKind.SHOW))),
        )
    }
}
