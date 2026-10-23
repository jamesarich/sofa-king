package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.jobs.Jobs
import dev.jamesrich.sofaking.backend.store.libraryItems
import dev.jamesrich.sofaking.backend.store.libraryMetadata
import dev.jamesrich.sofaking.backend.store.openStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryMetadataTest {
    private val token = "eyJ" + "a".repeat(60)
    private val media = MediaInfo("matroska,webm", durationMs = 60_000)

    private fun Path.file(relative: String, text: String = "x"): Path = resolve(relative).also {
        Files.createDirectories(it.parent)
        Files.writeString(it, text)
    }

    /** A TMDB stand-in answering [routes] by path; every request must carry the bearer token and the language. */
    private fun tmdb(
        calls: MutableList<String>,
        status: HttpStatusCode = HttpStatusCode.OK,
        routes: Map<String, String>,
    ) = HttpClient(
        MockEngine { request ->
            val path = request.url.encodedPath.removePrefix("/3")
            calls += path + (request.url.parameters["query"]?.let { "?$it" } ?: "")
            assertEquals("Bearer $token", request.headers[HttpHeaders.Authorization])
            assertEquals("en-US", request.url.parameters["language"])
            val body = routes[path]
            if (status != HttpStatusCode.OK) {
                respond("{}", status)
            } else if (body == null) {
                respond("""{"status_code":34}""", HttpStatusCode.NotFound)
            } else {
                respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        },
    )

    private fun service(root: Path, http: HttpClient, clock: MutableClock = MutableClock()) = runBlocking {
        val store = openStore(Files.createTempDirectory("sofa-tmdb-"), clock)
        val metadata =
            LibraryMetadata(
                store,
                TmdbClient(http, TmdbConfig(token), "https://api.themoviedb.org/3"),
                clock,
                pause = Duration.ZERO,
            )
        store to
            LibraryService(
                store,
                clock,
                listOf(MediaRoot("Media", root)),
                LibraryScanner({
                    media
                }),
                Jobs(clock),
                metadata = metadata,
            )
    }

    @Test fun `a movie is searched by title and year, then its details are kept`() = runBlocking {
        val root = Files.createTempDirectory("sofa-media-")
        root.file("movies/Alien (1979)/Alien (1979).mkv")
        val calls = mutableListOf<String>()
        val http = tmdb(
            calls,
            routes = mapOf(
                "/search/movie" to
                    """{"results":[{"id":9,"title":"Alien """ +
                    """Nation","release_date":"1988-10-07"},{"id":""" +
                    """348,"title":"Alien","release_date":"1979-05-25"}]}""",
                "/movie/348" to
                    """{"id":348,"title":"Alien","release_date":"1979-05-25","overview":"In space.","genres":[{"name":"Horror"}],"vote_average":8.149,
                "poster_path":"/poster.jpg","backdrop_path":"/back.jpg",
                "credits":{"cast":[{"name":"Sigourney Weaver"}],"crew":[{"name":"Ridley Scott","job":"Director"}]},
                "release_dates":{"results":[
                  {"iso_3166_1":"US","release_dates":[{"certification":""},{"certification":"R"}]}]}}""",
            ),
        )
        val (store, service) = service(root, http)
        val library = service.create("Movies", LibraryKind.MOVIES, root.resolve("movies").toString())
        service.scan(library.id)
        val movie = store.libraryItems(library.id).single()
        val facts = store.libraryMetadata(listOf(movie.id)).getValue(movie.id)
        assertEquals(348, facts.tmdbId)
        assertEquals("In space.", facts.facts?.overview)
        assertEquals("R", facts.facts?.contentRating)
        assertEquals(8.1, facts.facts?.rating)
        assertEquals(listOf("Ridley Scott"), facts.facts?.directors)
        assertEquals("/poster.jpg", facts.facts?.poster)
        assertEquals(listOf("/search/movie?Alien", "/movie/348"), calls)

        // Fresh lookups are not repeated on the next scan.
        service.scan(library.id)
        assertEquals(2, calls.size)
    }

    @Test
    fun `removing a library does not wait for its TMDB pass, which then leaves no details behind`() = runBlocking {
        val root = Files.createTempDirectory("sofa-media-")
        root.file("movies/Alien (1979)/Alien (1979).mkv")
        val asked = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val http = HttpClient(
            MockEngine { request ->
                asked.complete(Unit)
                gate.await()
                if (request.url.encodedPath.endsWith("/search/movie")) {
                    respond(
                        """{"results":[{"id":348,"title":"Alien","release_date":"1979-05-25"}]}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                } else {
                    respond(
                        """{"id":348,"title":"Alien","release_date":"1979-05-25"}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            },
        )
        val (store, service) = service(root, http)
        val library = service.create("Movies", LibraryKind.MOVIES, root.resolve("movies").toString())
        val scanning = launch { service.scan(library.id) }
        asked.await()
        val movie = store.libraryItems(library.id).single()
        withTimeout(5_000) { service.delete(library.id) }
        gate.complete(Unit)
        scanning.join()
        assertTrue(
            store.libraryMetadata(listOf(movie.id)).isEmpty(),
            "the pass's details for the removed items are gone",
        )
    }

    @Test fun `a show pins its id from a tag and fetches the seasons it holds`() = runBlocking {
        val root = Files.createTempDirectory("sofa-media-")
        root.file("tv/The Office (2005) {tmdb-2316}/Season 02/The Office - S02E01.mkv")
        val calls = mutableListOf<String>()
        val http = tmdb(
            calls,
            routes = mapOf(
                "/tv/2316" to
                    """{"id":2316,"name":"The """ +
                    """Office","first_air_date":"2005-03-24","overview":"Paper.","content_ratings":{"results":[""" +
                    """{"iso_3166_1":"US","rating":"TV-14"}]}}""",
                "/tv/2316/season/2" to
                    """{"name":"Season 2","episodes":[{"episode_number":1,"name":"The """ +
                    """Dundies","air_date":"2005-09-20","still_path":"/still.jpg"}]}""",
            ),
        )
        val (store, service) = service(root, http)
        val library = service.create("TV", LibraryKind.SHOWS, root.resolve("tv").toString())
        service.scan(library.id)
        assertEquals(listOf("/tv/2316", "/tv/2316/season/2"), calls, "no search when a tag names the show")
        val items = store.libraryItems(library.id)
        val known = store.libraryMetadata(items.map { it.id })
        val episode = items.single { it.kind == ItemKind.EPISODE }
        assertEquals("The Dundies", known.getValue(episode.id).facts?.title)
        assertEquals("/still.jpg", known.getValue(episode.id).facts?.poster)
        assertEquals("TV-14", known.getValue(items.single { it.kind == ItemKind.SHOW }.id).facts?.contentRating)

        // A new episode of a known show fetches only its season.
        root.file("tv/The Office (2005) {tmdb-2316}/Season 02/The Office - S02E02.mkv")
        service.scan(library.id)
        assertEquals(listOf("/tv/2316", "/tv/2316/season/2", "/tv/2316/season/2"), calls)
    }

    @Test fun `a refused token stops the run and never shows in the note`() = runBlocking {
        val root = Files.createTempDirectory("sofa-media-")
        root.file("movies/Alien (1979).mkv")
        root.file("movies/Heat (1995).mkv")
        val calls = mutableListOf<String>()
        val (store, service) = service(root, tmdb(calls, HttpStatusCode.Unauthorized, emptyMap()))
        val library = service.create("Movies", LibraryKind.MOVIES, root.resolve("movies").toString())
        service.scan(library.id)
        assertEquals(1, calls.size, "the first refusal ends the run")
        val note = service.library(library.id).scanNote!!
        assertTrue(note.contains("TMDB_AUTH"), note)
        assertFalse(note.contains(token))
        assertTrue(store.libraryMetadata(store.libraryItems(library.id).map { it.id }).isEmpty())
    }

    @Test fun `three lookups in a row that cannot reach TMDB stop the run`() = runBlocking {
        val root = Files.createTempDirectory("sofa-media-")
        for (name in listOf(
            "Alien (1979)",
            "Heat (1995)",
            "Ronin (1998)",
            "Se7en (1995)",
            "Fargo (1996)",
        )) {
            root.file("movies/$name.mkv")
        }
        val calls = mutableListOf<String>()
        val offline = HttpClient(
            MockEngine { request ->
                calls += request.url.encodedPath
                throw java.io.IOException("network is unreachable")
            },
        )
        val (_, service) = service(root, offline)
        val library = service.create("Movies", LibraryKind.MOVIES, root.resolve("movies").toString())
        service.scan(library.id)
        assertEquals(3, calls.size, "the third failure in a row ends the run")
        val note = service.library(library.id).scanNote!!
        assertTrue(note.contains("stopped: TMDB_UNREACHABLE"), note)
    }

    @Test fun `a match needs the same title, and a lone result its year`() {
        val item =
            LibraryItemRecord(
                id = "i",
                libraryId = "l",
                kind = ItemKind.MOVIE,
                title = "Mr. Robot",
                year = 2015,
                addedAt = "",
            )
        assertEquals(
            2,
            pickShow(
                listOf(
                    TmdbShowResult(1, "Mr Robot", firstAirDate = "1999-01-01"),
                    TmdbShowResult(2, "Mr. Robot", firstAirDate = "2015-06-24"),
                ),
                item,
            ),
        )
        assertEquals(3, pickShow(listOf(TmdbShowResult(3, "Robot Show", firstAirDate = "2016-01-01")), item))
        assertNull(pickShow(listOf(TmdbShowResult(4, "Robot Show", firstAirDate = "2001-01-01")), item))
        assertNull(pickShow(emptyList(), item))
    }
}
