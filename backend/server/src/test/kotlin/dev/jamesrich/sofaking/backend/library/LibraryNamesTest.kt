package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.contract.LetterCount
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryNamesTest {
    @Test fun `movie names read with or without brackets and release tags`() {
        assertEquals(TitleYear("Alien", 1979), titleYear("Alien (1979)"))
        assertEquals(TitleYear("Alien", 1979), titleYear("Alien (1979) [1080p]"))
        assertEquals(TitleYear("Alien", 1979), titleYear("Alien (1979) {tmdb-348}"))
        assertEquals(TitleYear("Alien", 1979), titleYear("Alien.1979.1080p.BluRay.x264"))
        assertEquals(TitleYear("Blade Runner 2049", 2017), titleYear("Blade Runner 2049 (2017)"))
        assertEquals(TitleYear("Home movie", null), titleYear("Home movie"))
    }

    @Test fun `title runs count each first letter, articles aside, with digits and symbols under #`() {
        assertEquals(
            listOf(LetterCount("#", 2), LetterCount("A", 2), LetterCount("B", 1)),
            letterRuns(listOf("12 Monkeys", "(500) Days of Summer", "Alien", "The Abyss", "Brazil")),
        )
        assertEquals(emptyList(), letterRuns(emptyList()))
    }

    @Test fun `episode numbers read in the Plex, scene and cross forms`() {
        assertEquals(EpisodeName(1, 2, title = "Pilot"), episodeName("Show - S01E02 - Pilot"))
        assertEquals(EpisodeName(1, 2, 3), episodeName("Show.S01E02E03.720p"))
        assertEquals(EpisodeName(1, 2, 3), episodeName("Show - s01e02-e03"))
        assertEquals(EpisodeName(3, 14), episodeName("Show 3x14"))
        assertEquals(EpisodeName(null, null), episodeName("Behind the music"))
    }

    @Test fun `episode titles drop the quality and release group Sonarr appends`() {
        assertEquals(
            "Island of the Gods",
            episodeName(
                "Ancient Aliens (2010) - S22E03 - Island of the Gods [HULU WEBDL-1080p][AAC 2.0][h264]-RAWR",
            ).title,
        )
        assertEquals("Bugs Weekly", episodeName("The Eric Andre Show - S06E01 - Bugs Weekly WEBDL-1080p").title)
        assertEquals("Unexplained Structures", episodeName("Show - S01E04 - Unexplained Structures WEBDL-720p").title)
        assertEquals("Pilot", episodeName("Show - S01E01 - Pilot Bluray-1080p Proper-GROUP").title)
        assertEquals(
            "A No-Rough-Stuff-Type Deal",
            episodeName(
                "Breaking Bad (2008) - S01E07 - A No-Rough-Stuff-Type Deal [Bluray-1080p][AC3 5.1][x264]-AiRTV",
            ).title,
        )
        assertEquals("Anti-Weed", episodeName("The Eric Andre Show - S06E07 - Anti-Weed WEBDL-1080p").title)
        assertEquals("Spider-Man", episodeName("Show - S01E01 - Spider-Man").title)
        assertEquals(
            TitleYear("A Christmas Story", 1983),
            titleYear("A Christmas Story (1983) {imdb-tt0085334} [Bluray-1080p][AC3 1.0][x264]-CtrlHD"),
        )
        assertEquals(TitleYear("Home movie", null), titleYear("Home movie [DVD]-GROUP"))
    }

    @Test fun `a collection's order number before the title is dropped`() {
        assertEquals(TitleYear("Robot Jox", 1990), titleYear("01 - Robot Jox (1990)"))
        assertEquals(TitleYear("Robot Wars", 1993), titleYear("3 - Robot Wars (1993)"))
        assertEquals(TitleYear("24 - Redemption", 2008), titleYear("24 - Redemption (2008)"))
        assertEquals(TitleYear("1917", 2019), titleYear("1917 (2019)"))
        assertEquals(TitleYear("01", null), titleYear("01"))
    }

    @Test fun `a date-based recording reads its air date and drops the same-day time`() {
        val name = episodeName("Nightly News - 2026-10-17 - Evening Edition (18.30)")
        assertEquals(LocalDate.of(2026, 10, 17), name.aired)
        assertEquals("Evening Edition", name.title)
        assertNull(name.episode)
    }

    @Test fun `season folders and specials`() {
        assertEquals(1, seasonOfFolder("Season 01"))
        assertEquals(2026, seasonOfFolder("Season 2026"))
        assertEquals(0, seasonOfFolder("Specials"))
        assertNull(seasonOfFolder("Extras"))
    }

    @Test fun `samples and hidden files are not videos, and extras are known by folder or suffix`() {
        assertTrue(isVideoFile("Alien (1979).mkv"))
        assertTrue(isVideoFile("Alien (1979)-trailer.mkv"))
        assertFalse(isVideoFile("Alien (1979)-sample.mkv"))
        assertFalse(isVideoFile(".Alien.mkv"))
        assertFalse(isVideoFile("Alien.nfo"))
        assertEquals(ExtraKind.TRAILER to "Alien (1979)", extraSuffix("Alien (1979)-Trailer.mkv"))
        assertNull(extraSuffix("Alien (1979).mkv"))
        assertEquals(ExtraKind.FEATURETTE, extraFolderKind("Featurettes"))
        assertEquals(ExtraKind.OTHER, extraFolderKind("Extras"))
        assertNull(extraFolderKind("Season 01"))
        assertFalse(skippedFolder("Featurettes"))
        assertTrue(skippedFolder("Samples"))
        assertTrue(skippedFolder(".inprogress"))
        assertFalse(skippedFolder("Season 01"))
    }

    @Test fun `sort titles drop a leading article`() {
        assertEquals("matrix", sortTitle("The Matrix"))
        assertEquals("a", sortTitle("A"))
    }
}
