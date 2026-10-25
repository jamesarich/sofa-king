package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.jobs.Jobs
import dev.jamesrich.sofaking.backend.store.libraryItems
import dev.jamesrich.sofaking.backend.store.libraryMatches
import dev.jamesrich.sofaking.backend.store.libraryMetadata
import dev.jamesrich.sofaking.backend.store.openStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryMatchesTest {
    private val calls = mutableListOf<String>()

    /** TMDB's search knows only "Alien Nation", so `Alyen (1979)` goes unmatched until it is matched by hand. */
    private val http = HttpClient(
        MockEngine { request ->
            val path = request.url.encodedPath.removePrefix("/3")
            calls += path + (request.url.parameters["query"]?.let { "?$it" } ?: "")
            val body = when (path) {
                "/search/movie" -> if (request.url.parameters["query"] == "Alien") {
                    """{"results":[{"id":348,"title":"Alien","release_date":"1979-05-25","overview":"In space."}]}"""
                } else {
                    """{"results":[{"id":9,"title":"Alien Nation","release_date":"1988-10-07"}]}"""
                }

                "/movie/348" -> """{"id":348,"title":"Alien","release_date":"1979-05-25","overview":"In space."}"""

                else -> null
            }
            if (body == null) {
                respond("""{"status_code":34}""", HttpStatusCode.NotFound)
            } else {
                respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        },
    )

    @Test fun `a movie the search missed is matched by hand, kept on every scan, and let go again`() = runBlocking {
        val root = Files.createTempDirectory("sofa-media-")
        Files.createDirectories(root.resolve("movies/Alyen (1979)"))
        Files.writeString(root.resolve("movies/Alyen (1979)/Alyen (1979).mkv"), "x")
        val clock = MutableClock()
        val store = openStore(Files.createTempDirectory("sofa-match-"), clock)
        val metadata = LibraryMetadata(
            store,
            TmdbClient(http, TmdbConfig("eyJ" + "a".repeat(60))),
            clock,
            pause = Duration.ZERO,
        )
        val service = LibraryService(
            store,
            clock,
            listOf(MediaRoot("Media", root)),
            LibraryScanner({ MediaInfo("matroska,webm", durationMs = 60_000) }),
            Jobs(clock),
            metadata = metadata,
        )
        val matches = assertNotNull(service.matches)
        val library = service.create("Movies", LibraryKind.MOVIES, root.resolve("movies").toString())
        val movie = store.libraryItems(library.id).single()
        assertNull(store.libraryMetadata(listOf(movie.id)).getValue(movie.id).tmdbId, "the search missed it")

        val unmatched = matches.rows().single()
        assertEquals(movie.id, unmatched.item.id)
        assertNull(unmatched.chosen)

        val found = matches.search(movie, "Alien", 1979).single()
        assertEquals(348, found.id)
        assertEquals(1979, found.year)
        assertEquals("In space.", found.overview)

        assertFailsWith<TmdbFailure> { matches.choose(movie, 777) }
        assertTrue(store.libraryMatches(listOf(movie.id)).isEmpty(), "an id TMDB does not know is not kept")

        assertEquals("Alien", matches.choose(movie, 348))
        assertFalse(matches.rows().single().fetched, "fetched on the next scan")
        service.scan(library.id)
        assertEquals(348, store.libraryMetadata(listOf(movie.id)).getValue(movie.id).tmdbId)
        val chosen = matches.rows().single()
        assertEquals(348, chosen.chosen)
        assertTrue(chosen.fetched)

        calls.clear()
        service.scan(library.id)
        assertEquals(emptyList<String>(), calls, "a fresh hand match is not looked up again, nor searched")

        assertTrue(matches.clear(movie))
        assertFalse(matches.clear(movie), "a second clear finds nothing to drop")
        service.scan(library.id)
        assertEquals(listOf("/search/movie?Alyen"), calls, "the scan searches for itself again")
        assertNull(store.libraryMetadata(listOf(movie.id)).getValue(movie.id).tmdbId)
        assertNull(matches.rows().single().chosen)
    }
}
