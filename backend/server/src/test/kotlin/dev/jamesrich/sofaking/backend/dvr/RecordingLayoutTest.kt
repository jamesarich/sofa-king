package dev.jamesrich.sofaking.backend.dvr

import dev.jamesrich.sofaking.backend.catalog.EpisodeNumber
import dev.jamesrich.sofaking.backend.catalog.GuideProgram
import dev.jamesrich.sofaking.backend.catalog.SeasonEpisode
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecordingLayoutTest {
    private val zone = ZoneId.of("America/Chicago")
    private val start = Instant.parse("2026-10-18T01:30:00Z") // 20:30 on the 17th in Chicago

    private fun program(
        title: String,
        subTitle: String? = null,
        numbers: List<EpisodeNumber> = emptyList(),
        categories: List<String> = emptyList(),
        date: String? = null,
    ) = GuideProgram(
        "c",
        start,
        start.plusSeconds(1800),
        title,
        subTitle = subTitle,
        episodeNumbers = numbers,
        categories = categories,
        date = date,
    )

    @Test fun `a numbered episode goes in its season folder with SxxEyy`() {
        val title = RecordingTitle.of(program("Star Trek", "The Cage", listOf(EpisodeNumber("xmltv_ns", "0.0.0/1"))))
        assertEquals(
            Path.of("TV/Star Trek/Season 01/Star Trek - S01E01 - The Cage.ts"),
            RecordingLayout.path(title, start, zone),
        )
    }

    @Test fun `an unnumbered airing is named by its local date`() {
        val title = RecordingTitle.of(program("NBC Nightly News", "October 17"))
        assertEquals(
            Path.of("TV/NBC Nightly News/Season 2026/NBC Nightly News - 2026-10-17 - October 17.ts"),
            RecordingLayout.path(title, start, zone),
        )
        val episodeOnly = RecordingTitle("Daily Show", numbering = SeasonEpisode(null, 3))
        assertEquals(
            Path.of("TV/Daily Show/Season 2026/Daily Show - 2026-10-17.ts"),
            RecordingLayout.path(episodeOnly, start, zone),
        )
    }

    @Test fun `a movie gets its own folder with the year`() {
        val title = RecordingTitle.of(program("Heat", categories = listOf("Movie", "Crime"), date = "1995"))
        assertEquals(Path.of("Movies/Heat (1995)/Heat (1995).ts"), RecordingLayout.path(title, start, zone))
        val undated = RecordingTitle.of(program("Heat", categories = listOf("movie")))
        assertEquals(Path.of("Movies/Heat/Heat.ts"), RecordingLayout.path(undated, start, zone))
    }

    @Test fun `a taken name gets the start time, then a counter`() {
        val title = RecordingTitle("News")
        val first = Path.of("TV/News/Season 2026/News - 2026-10-17.ts")
        val second = Path.of("TV/News/Season 2026/News - 2026-10-17 (20.30).ts")
        assertEquals(second, RecordingLayout.path(title, start, zone) { it == first })
        assertEquals(
            Path.of("TV/News/Season 2026/News - 2026-10-17 (20.30) (2).ts"),
            RecordingLayout.path(title, start, zone) { it == first || it == second },
        )
    }

    @Test fun `names drop separators and characters shares refuse`() {
        assertEquals("AC-DC - Live at River Plate", RecordingLayout.clean("AC/DC: Live at River Plate"))
        assertEquals("What If", RecordingLayout.clean("What If...?"))
        assertEquals("a b", RecordingLayout.clean(" a\tb\u0000 "))
        assertEquals("x".repeat(120), RecordingLayout.clean("x".repeat(300)))
        assertEquals(
            Path.of("TV/Unknown/Season 2026/Unknown - 2026-10-17.ts"),
            RecordingLayout.path(RecordingTitle(".."), start, zone),
        )
    }

    @Test fun `the episode title is left out when it repeats the show`() {
        assertEquals(
            Path.of("TV/Jeopardy!/Season 2026/Jeopardy! - 2026-10-17.ts"),
            RecordingLayout.path(RecordingTitle("Jeopardy!", "jeopardy!"), start, zone),
        )
    }

    @Test fun `a running recording is written under the hidden in-progress folder`() {
        assertEquals(
            Path.of(".inprogress/TV/a/Season 01/a - S01E01.ts"),
            RecordingLayout.inProgress(Path.of("TV/a/Season 01/a - S01E01.ts")),
        )
    }

    /** Every folder and the file name of the busiest case: the name, the start time and a counter all taken. */
    private fun assertFits(title: RecordingTitle): Path {
        val taken = mutableSetOf<Path>()
        repeat(120) { taken.add(RecordingLayout.path(title, start, zone) { it in taken }) }
        val path = RecordingLayout.path(title, start, zone) { it in taken }
        for (part in RecordingLayout.inProgress(path)) {
            val bytes = part.toString().toByteArray(Charsets.UTF_8).size
            assertTrue(bytes <= 255, "$bytes bytes in $part")
        }
        return path
    }

    @Test fun `a long show and episode title still make a name a file system takes`() {
        val path = assertFits(RecordingTitle("x".repeat(300), "y".repeat(300), SeasonEpisode(1, 2)))
        // Only the episode's title gives way, so two episodes of a long-titled show never share a name.
        assertTrue(path.fileName.toString().startsWith("x".repeat(120) + " - S01E02 - y"), path.toString())
        assertFits(RecordingTitle("z".repeat(300), "w".repeat(300)))
        assertFits(RecordingTitle("m".repeat(300), movie = true, year = 1999))
    }

    @Test fun `a title in another script is cut by bytes, on a whole character`() {
        // A file name takes the platform's encoding; one that is not UTF-8 cannot hold these titles at all.
        assumeTrue(System.getProperty("sun.jnu.encoding").equals("UTF-8", ignoreCase = true))
        assertFits(RecordingTitle("ж".repeat(300), "д".repeat(300), SeasonEpisode(1, 2)))
        assertFits(RecordingTitle("番組".repeat(150), "回".repeat(300)))
        val tv = "\uD83D\uDCFA"
        assertFits(RecordingTitle(tv.repeat(200), movie = true, year = 1999))
        val numbered = assertFits(RecordingTitle(tv.repeat(50), "z".repeat(300), SeasonEpisode(1, 2)))
        val text = numbered.fileName.toString().removeSuffix(".ts")
        assertEquals(text, String(text.toByteArray(Charsets.UTF_8), Charsets.UTF_8), "no lone surrogate survives")
        assertTrue(text.startsWith(tv.repeat(50) + " - S01E02 - z"), text)
    }
}
