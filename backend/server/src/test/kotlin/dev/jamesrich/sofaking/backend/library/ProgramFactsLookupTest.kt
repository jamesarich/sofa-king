package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.http.assertKeepsWireFixture
import dev.jamesrich.sofaking.contract.ProgramFacts
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class ProgramFactsLookupTest {
    private val token = "eyJ" + "a".repeat(60)

    /** A TMDB stand-in answering [routes] by path, and 404 for the rest; each call's path lands in [calls]. */
    private fun lookup(
        calls: MutableList<String>,
        routes: Map<String, String>,
        clock: MutableClock = MutableClock(),
        status: HttpStatusCode = HttpStatusCode.OK,
    ): ProgramFactsLookup {
        val http = HttpClient(
            MockEngine { request ->
                val path = request.url.encodedPath.removePrefix("/3")
                calls += path
                val body = routes[path]
                when {
                    status != HttpStatusCode.OK -> respond("{}", status)
                    body == null -> respond("""{"status_code":34}""", HttpStatusCode.NotFound)
                    else -> respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            },
        )
        return ProgramFactsLookup(TmdbClient(http, TmdbConfig(token)), clock)
    }

    private val show = """{"id":7,"name":"Case Files","first_air_date":"1996-04-21","last_air_date":"2011-06-17",""" +
        """"status":"Ended","networks":[{"name":"Court Channel"}],"number_of_seasons":14,"number_of_episodes":406,""" +
        """"created_by":[{"name":"Dana Maker"}],"tagline":"Every clue tells a story.","vote_average":8.14,""" +
        """"credits":{"cast":[{"name":"Pat Narrator"},{"name":"Sam Expert"},{"name":"Lee Witness"},""" +
        """{"name":"Fourth Billed"}]}}"""

    @Test fun `a show is found by its exact title and keeps the wire fixture`() = runBlocking {
        val calls = mutableListOf<String>()
        val facts = lookup(
            calls,
            mapOf(
                "/search/tv" to """{"results":[{"id":3,"name":"Case Files: Revisited","popularity":90.0},""" +
                    """{"id":7,"name":"Case Files","popularity":20.0}]}""",
                "/tv/7" to show,
            ),
        ).facts("Case Files", movie = false)
        assertEquals(Wire.ItemKind.SHOW, facts.kind)
        assertEquals(1996 to 2011, facts.year to facts.endYear)
        assertEquals(listOf("Pat Narrator", "Sam Expert", "Lee Witness"), facts.cast, "the first three billed")
        assertEquals(8.1, facts.rating)
        assertEquals(listOf("/search/tv", "/tv/7"), calls)
        assertKeepsWireFixture("v1/program-facts.json", Json { encodeDefaults = true }.encodeToString(facts))
    }

    @Test fun `a movie the guide files as one is searched as a movie first`() = runBlocking {
        val calls = mutableListOf<String>()
        val facts = lookup(
            calls,
            mapOf(
                "/search/movie" to """{"results":[{"id":9,"title":"Night Drive","popularity":5.0}]}""",
                "/movie/9" to """{"id":9,"title":"Night Drive","release_date":"1985-07-03",""" +
                    """"production_companies":[{"name":"Big Studio"}],"credits":{"cast":[],""" +
                    """"crew":[{"name":"Ada Director","job":"Director"},{"name":"Writer","job":"Writer"}]}}""",
            ),
        ).facts("Night Drive", movie = true)
        assertEquals(
            ProgramFacts(Wire.ItemKind.MOVIE, 1985, studio = "Big Studio", creators = listOf("Ada Director")),
            facts,
        )
        assertEquals(listOf("/search/movie", "/movie/9"), calls)
    }

    @Test fun `a title TMDB does not list as it is finds nothing and is not asked again for a day`() = runBlocking {
        val calls = mutableListOf<String>()
        val clock = MutableClock()
        val lookup = lookup(
            calls,
            mapOf(
                "/search/tv" to """{"results":[{"id":3,"name":"Local News Now","popularity":9.0}]}""",
                "/search/movie" to """{"results":[]}""",
            ),
            clock,
        )
        assertEquals(ProgramFacts(), lookup.facts("Local News at 5", movie = false))
        assertEquals(ProgramFacts(), lookup.facts("local news at 5!", movie = false))
        assertEquals(listOf("/search/tv", "/search/movie"), calls, "the show search, then the movie search, once")
        clock.now = clock.now.plus(Duration.ofDays(1)).plusSeconds(1)
        lookup.facts("Local News at 5", movie = false)
        assertEquals(4, calls.size)
    }

    @Test fun `a failed lookup is empty and waits before asking again`() = runBlocking {
        val calls = mutableListOf<String>()
        val clock = MutableClock()
        val lookup = lookup(calls, emptyMap(), clock, status = HttpStatusCode.TooManyRequests)
        assertEquals(ProgramFacts(), lookup.facts("Case Files", movie = false))
        lookup.facts("Case Files", movie = false)
        assertEquals(1, calls.size)
        clock.now = clock.now.plus(Duration.ofMinutes(11))
        lookup.facts("Case Files", movie = false)
        assertEquals(2, calls.size)
    }
}
