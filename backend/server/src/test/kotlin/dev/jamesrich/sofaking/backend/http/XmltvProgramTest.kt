package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.catalog.EpisodeNumber
import dev.jamesrich.sofaking.backend.catalog.GuideProgram
import dev.jamesrich.sofaking.backend.catalog.PreviouslyShown
import dev.jamesrich.sofaking.backend.catalog.ProgramRating
import dev.jamesrich.sofaking.backend.catalog.episodeLabel
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XmltvProgramTest {
    private val start = Instant.parse("2026-10-10T13:00:00Z")
    private val end = Instant.parse("2026-10-10T14:00:00Z")
    private val head = "<programme start=\"20261010130000 +0000\" stop=\"20261010140000 +0000\" channel=\"c\">"

    @Test fun `a program with its own numbering emits every system and no synthesized air date`() {
        val program = GuideProgram(
            "x", start, end, "Show <1>", "Plot & more", subTitle = "The \"One\"",
            episodeNumbers = listOf(EpisodeNumber("xmltv_ns", "1.4."), EpisodeNumber("dd_progid", "EP01234567.0005")),
            icon = "https://img.test/a.jpg?w=1&h=2", categories = listOf("Comedy", "Series"),
            rating = ProgramRating("TV-14", "VCHIP"), isNew = true, previouslyShown = PreviouslyShown("20200101000000 +0000"),
            date = "2020", language = "en",
        )
        assertEquals(
            head + "<title lang=\"en\">Show &lt;1&gt;</title><sub-title lang=\"en\">The &quot;One&quot;</sub-title><desc lang=\"en\">Plot &amp; more</desc>" +
                "<date>2020</date><category lang=\"en\">Series</category><category lang=\"en\">Comedy</category>" +
                "<icon src=\"https://img.test/a.jpg?w=1&amp;h=2\"/>" +
                "<episode-num system=\"xmltv_ns\">1.4.</episode-num><episode-num system=\"dd_progid\">EP01234567.0005</episode-num>" +
                "<previously-shown start=\"20200101000000 +0000\"/><new/><rating system=\"VCHIP\"><value>TV-14</value></rating></programme>\n",
            xmltvProgram("c", program, "Kids & Family"),
        )
    }

    @Test fun `a program without numbering keeps the group categories and the synthesized air date`() {
        assertEquals(
            head + "<title>Morning</title><category lang=\"en\">News</category><episode-num system=\"original-air-date\">2026-10-10 13:00:00</episode-num></programme>\n",
            xmltvProgram("c", GuideProgram("x", start, end, "Morning"), "News"),
        )
        assertEquals(
            head + "<title>Morning</title><category lang=\"en\">Series</category><category lang=\"en\">Comedy TV</category>" +
                "<episode-num system=\"original-air-date\">2026-10-10 13:00:00</episode-num></programme>\n",
            xmltvProgram("c", GuideProgram("x", start, end, "Morning"), "Comedy TV"),
        )
    }

    @Test fun `movies get no synthesized air date, by group or by the source's category`() {
        val byGroup = xmltvProgram("c", GuideProgram("x", start, end, "Film"), "Movies")
        assertEquals(head + "<title>Film</title><category lang=\"en\">Movie</category><category lang=\"en\">Movies</category></programme>\n", byGroup)
        val bySource = xmltvProgram("c", GuideProgram("x", start, end, "Film", categories = listOf("Drama", "Movie")), "Entertainment")
        assertEquals(head + "<title>Film</title><category>Movie</category><category>Drama</category></programme>\n", bySource)
        assertFalse(bySource.contains("episode-num"))
    }

    @Test fun `a repeat without a first-shown time and a rating without a system`() {
        val xml = xmltvProgram("c", GuideProgram("x", start, end, "Rerun", previouslyShown = PreviouslyShown(), rating = ProgramRating("TV-G")), null)
        assertTrue(xml.contains("<previously-shown/><rating><value>TV-G</value></rating>"), xml)
        assertTrue(xml.contains("original-air-date"), "a repeat without numbering is still numbered by its start")
    }

    @Test fun `episode labels come from xmltv_ns, then onscreen`() {
        assertEquals("S2 E5", episodeLabel(listOf(EpisodeNumber("onscreen", "Ep 9"), EpisodeNumber("xmltv_ns", "1.4.0/1"))))
        assertEquals("E3", episodeLabel(listOf(EpisodeNumber("xmltv_ns", ".2."))))
        assertEquals("S10", episodeLabel(listOf(EpisodeNumber("xmltv_ns", "9.."))))
        assertEquals("S2 E5", episodeLabel(listOf(EpisodeNumber("onscreen", "S02E05"))))
        assertEquals("Ep 9", episodeLabel(listOf(EpisodeNumber("onscreen", "Ep 9"))))
        assertNull(episodeLabel(listOf(EpisodeNumber("dd_progid", "EP01234567.0005"))))
        assertNull(episodeLabel(emptyList()))
    }

    @Test fun `bounded caps metadata and drops what fails its check`() {
        val program = GuideProgram(
            "x", start, end, "T", subTitle = "T", episodeNumbers = List(12) { EpisodeNumber("xmltv_ns", "0.$it.") } + EpisodeNumber("bad system!", "1"),
            icon = "javascript:alert(1)", categories = List(12) { "C$it" } + "c0", rating = ProgramRating("x".repeat(40)),
            previouslyShown = PreviouslyShown("yesterday"), date = "2024-01-15", language = "english please",
        ).bounded()
        assertNull(program.subTitle, "a sub-title equal to the title says nothing")
        assertEquals(GuideProgram.EPISODE_NUMBER_LIMIT, program.episodeNumbers.size)
        assertNull(program.icon)
        assertEquals((0 until GuideProgram.CATEGORY_LIMIT).map { "C$it" }, program.categories)
        assertNull(program.rating)
        assertEquals(PreviouslyShown(null), program.previouslyShown)
        assertEquals("20240115", program.date)
        assertNull(program.language)
        assertNull(GuideProgram.xmltvDate("15/01/2024"))
        assertEquals("1999", GuideProgram.xmltvDate("1999"))
    }
}
