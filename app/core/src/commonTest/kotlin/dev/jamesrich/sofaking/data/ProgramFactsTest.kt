package dev.jamesrich.sofaking.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class ProgramFactsTest {
    private val start = Instant.parse("2026-10-24T21:00:00Z")

    private fun program(title: String, kinds: Set<ProgramKind> = emptySet(), episode: String? = null) =
        Program("c", start, Instant.parse("2026-10-24T21:30:00Z"), title, null, episode = episode, kinds = kinds)

    private val show = """{"kind":"show","year":1996,"endYear":2011,"studio":"Court Channel","seasons":14,""" +
        """"episodes":406,"cast":["Pat Narrator"],"creators":["Dana Maker"],"rating":8.1}"""

    @Test
    fun `the facts go episode first then TMDB then what is next`() {
        val facts = ProgramFacts(
            year = 1996,
            endYear = 2011,
            studio = "Court Channel",
            seasons = 14,
            cast = listOf("Pat Narrator"),
            creators = listOf("Dana Maker"),
            tagline = "Every clue tells a story.",
            rating = 8.1,
        )
        val next = program("Night Drive").copy(start = Instant.parse("2026-10-24T21:30:00Z"))
        assertEquals(
            listOf(
                BreakFact.Episode("S3 E12", null),
                BreakFact.Aired(movie = false, 1996, 2011, "Court Channel"),
                BreakFact.Run(14, null),
                BreakFact.Starring(listOf("Pat Narrator")),
                BreakFact.MadeBy(movie = false, listOf("Dana Maker")),
                BreakFact.Tagline("Every clue tells a story."),
                BreakFact.Rating(8.1),
                BreakFact.Next("Night Drive", next.start.toEpochMilliseconds()),
            ),
            breakFacts(program("Case Files", episode = "S3 E12"), next, facts),
        )
    }

    @Test
    fun `nothing known gives no facts and a show still on the air has no end year`() {
        assertEquals(emptyList(), breakFacts(program("News"), null, null))
        assertEquals(
            listOf(BreakFact.Aired(movie = false, 2020, null, null)),
            breakFacts(null, null, ProgramFacts(year = 2020, endYear = 2020)),
        )
    }

    @Test
    fun `a title is asked once per server and a movie says so`() = runTest {
        val http = FakeHttp(this) { request ->
            json(if ("Night" in request.query) """{"kind":"movie","year":1985}""" else show)
        }
        val book = ProgramFactsBook(MutableStateFlow(http.api()))
        assertEquals(14, book.of(program("Case Files"))?.seasons)
        assertEquals(14, book.of(program("Case Files"))?.seasons)
        val movie = book.of(program("Night Drive", kinds = setOf(ProgramKind.MOVIE)))
        assertEquals(ProgramFacts(movie = true, year = 1985), movie)
        assertEquals(
            listOf("/api/v1/program-facts" to "movie=false", "/api/v1/program-facts" to "movie=true"),
            http.requests.map { it.path to it.query.substringAfter('&') },
        )
    }

    @Test
    fun `no match and an older server give null and a failure asks again`() = runTest {
        var status = 200
        val http = FakeHttp(this) { json(if (status == 200) "{}" else """{"code":"X","message":"m"}""", status) }
        val book = ProgramFactsBook(MutableStateFlow(http.api()))
        assertNull(book.of(program("Paid Programming")))
        assertNull(book.of(program("Paid Programming")))
        assertEquals(1, http.requests.size, "no match is kept")
        status = 500
        assertNull(book.of(program("Case Files")))
        assertNull(book.of(program("Case Files")))
        assertEquals(3, http.requests.size, "a failure is not kept")
        status = 404
        assertNull(book.of(program("Old Server")))
        assertNull(book.of(program("Old Server")))
        assertEquals(4, http.requests.size, "an older server's 404 is kept")
    }
}
