package dev.jamesrich.sofaking.backend.library

import java.time.LocalDate
import kotlin.test.*

class LibraryNamesTest {
    @Test fun `movie names read with or without brackets and release tags`() {
        assertEquals(TitleYear("Alien", 1979), titleYear("Alien (1979)"))
        assertEquals(TitleYear("Alien", 1979), titleYear("Alien (1979) [1080p]"))
        assertEquals(TitleYear("Alien", 1979), titleYear("Alien (1979) {tmdb-348}"))
        assertEquals(TitleYear("Alien", 1979), titleYear("Alien.1979.1080p.BluRay.x264"))
        assertEquals(TitleYear("Blade Runner 2049", 2017), titleYear("Blade Runner 2049 (2017)"))
        assertEquals(TitleYear("Home movie", null), titleYear("Home movie"))
    }

    @Test fun `episode numbers read in the Plex, scene and cross forms`() {
        assertEquals(EpisodeName(1, 2, title = "Pilot"), episodeName("Show - S01E02 - Pilot"))
        assertEquals(EpisodeName(1, 2, 3), episodeName("Show.S01E02E03.720p"))
        assertEquals(EpisodeName(1, 2, 3), episodeName("Show - s01e02-e03"))
        assertEquals(EpisodeName(3, 14), episodeName("Show 3x14"))
        assertEquals(EpisodeName(null, null), episodeName("Behind the music"))
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

    @Test fun `extras, samples and hidden files are not videos`() {
        assertTrue(isVideoFile("Alien (1979).mkv"))
        assertFalse(isVideoFile("Alien (1979)-trailer.mkv"))
        assertFalse(isVideoFile(".Alien.mkv"))
        assertFalse(isVideoFile("Alien.nfo"))
        assertTrue(skippedFolder("Featurettes"))
        assertTrue(skippedFolder(".inprogress"))
        assertFalse(skippedFolder("Season 01"))
    }

    @Test fun `sort titles drop a leading article`() {
        assertEquals("matrix", sortTitle("The Matrix"))
        assertEquals("a", sortTitle("A"))
    }
}
