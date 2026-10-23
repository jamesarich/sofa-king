package dev.jamesrich.sofaking.backend.dvr

import dev.jamesrich.sofaking.backend.catalog.EpisodeNumber
import dev.jamesrich.sofaking.backend.catalog.GuideProgram
import dev.jamesrich.sofaking.backend.catalog.SeasonEpisode
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
