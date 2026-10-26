package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.jobs.Jobs
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.libraryItems
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MusicMetadataTest {
    private class Scanned(val store: BackendStore, val items: List<LibraryItemRecord>, val note: String?) {
        fun facts(title: String): MetadataFacts? = runBlocking {
            val item = items.single { it.title == title }
            store.libraryMetadata(listOf(item.id))[item.id]?.facts
        }

        fun looked(title: String): Boolean = runBlocking {
            val item = items.single { it.title == title }
            item.id in store.libraryMetadata(listOf(item.id))
        }
    }

    /**
     * A MusicBrainz stand-in answering [routes] by path, after `/ws/2`; [calls] gets each path, with its search query.
     * Every request must name the server in its User-Agent and ask for JSON.
     */
    private fun musicBrainz(
        calls: MutableList<String>,
        routes: Map<String, String>,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = HttpClient(
        MockEngine { request ->
            val path = request.url.encodedPath.removePrefix("/ws/2")
            calls += path + (request.url.parameters["query"]?.let { "?$it" } ?: "")
            assertTrue(request.headers[HttpHeaders.UserAgent].orEmpty().startsWith("SofaKing/test ("))
            assertEquals("json", request.url.parameters["fmt"])
            val body = routes[path]
            when {
                status != HttpStatusCode.OK -> respond("{}", status)
                body == null -> respond("""{"error":"Not Found"}""", HttpStatusCode.NotFound)
                else -> respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        },
    )

    /** Scans a Music library of [tags]' files, twice, looking each up on [http]. */
    private fun scan(http: HttpClient, tags: Map<String, AudioTags>): Scanned = runBlocking {
        val dir = Files.createTempDirectory("sofa-music-")
        val music = Files.createDirectories(dir.resolve("media/Music"))
        for (path in tags.keys) {
            Files.createDirectories(music.resolve(path).parent)
            Files.writeString(music.resolve(path), "x")
        }
        val clock = MutableClock()
        val store = openStore(dir.resolve("data"), clock)
        val scanner = LibraryScanner({ file ->
            MediaInfo(
                "flac",
                durationMs = 200_000,
                audio = listOf(AudioStream(0, "flac")),
                tags = tags[music.relativize(file).joinToString("/")],
            )
        })
        val metadata = MusicMetadata(store, MusicBrainzClient(http, "test", pause = Duration.ZERO), clock)
        val service = LibraryService(
            store,
            clock,
            listOf(MediaRoot("Media", dir.resolve("media"))),
            scanner,
            Jobs(clock),
            musicMetadata = metadata,
        )
        val library = service.create("Music", LibraryKind.MUSIC, music.toString())
        service.scan(library.id)
        service.scan(library.id)
        val note = service.library(library.id).scanNote
        Scanned(store, store.libraryItems(library.id), note)
    }

    @Test fun `an album pinned by its release group id is fetched, and its artist by the album's credit`() {
        val calls = mutableListOf<String>()
        val http = musicBrainz(
            calls,
            mapOf(
                "/release-group/$GROUP" to
                    """{"id":"$GROUP","title":"Record","first-release-date":"1999-03-01",
                    "artist-credit":[{"name":"Band","artist":{"id":"$ARTIST","name":"Band"}}],
                    "genres":[{"name":"rock","count":3},{"name":"indie","count":5}]}""",
                "/artist/$ARTIST" to
                    """{"id":"$ARTIST","name":"Band","genres":[{"name":"rock","count":2}],
                    "relations":[{"type":"wikidata","url":{"resource":"https://www.wikidata.org/wiki/Q42"}}]}""",
            ),
        )
        val s = scan(
            http,
            mapOf(
                "Band/Record/01.flac" to AudioTags("One", "Band", album = "Record", track = 1, releaseGroupId = GROUP),
            ),
        )
        val album = s.facts("Record")
        assertEquals(GROUP, album?.musicBrainzId)
        assertEquals(ARTIST, album?.artistId)
        assertEquals(1999, album?.year)
        assertEquals(listOf("Indie", "Rock"), album?.genres)
        // Nothing renames what the tags call the album.
        assertNull(album?.title)
        val artist = s.facts("Band")
        assertEquals(ARTIST, artist?.musicBrainzId)
        assertEquals("Q42", artist?.wikidata)
        // The second scan finds both fresh, and asks for nothing.
        assertEquals(listOf("/release-group/$GROUP", "/artist/$ARTIST"), calls)
        assertTrue(s.note.orEmpty().contains("MusicBrainz: 0 fetched"), s.note)
    }

    @Test fun `an album without ids is searched without its edition, and kept only under its own title and artist`() {
        val calls = mutableListOf<String>()
        val http = musicBrainz(
            calls,
            mapOf(
                "/release-group" to
                    """{"release-groups":[
                    {"id":"other","title":"Record Store Day","score":100,
                     "artist-credit":[{"name":"Band","artist":{"id":"$ARTIST","name":"Band"}}]},
                    {"id":"$GROUP","title":"Record","score":95,"first-release-date":"1999",
                     "artist-credit":[{"name":"The Band","artist":{"id":"$ARTIST","name":"The Band"}}]}]}""",
                "/release-group/$GROUP" to
                    """{"id":"$GROUP","title":"Record","first-release-date":"1999",
                    "artist-credit":[{"name":"The Band","artist":{"id":"$ARTIST","name":"The Band"}}],
                    "genres":[{"name":"pop","count":9}]}""",
                "/artist/$ARTIST" to """{"id":"$ARTIST","name":"The Band"}""",
            ),
        )
        val s = scan(
            http,
            mapOf(
                "Band/Record/01.flac" to
                    AudioTags("One", "Band", album = "Record (Deluxe Edition)", track = 1, genres = listOf("Rock")),
            ),
        )
        assertEquals("/release-group?releasegroup:\"Record\" AND artist:\"Band\"", calls.first())
        val album = s.facts("Record (Deluxe Edition)")
        assertEquals(GROUP, album?.musicBrainzId)
        // The files name a genre, so MusicBrainz's are not added.
        assertEquals(emptyList(), album?.genres)
        assertEquals(ARTIST, s.facts("Band")?.musicBrainzId)
        // The artist came from the album's credit, never a search by name.
        assertTrue(calls.none { it.startsWith("/artist?") }, calls.toString())
    }

    @Test fun `an artist whose name two artists share at one score stays unmatched`() {
        val calls = mutableListOf<String>()
        val http = musicBrainz(
            calls,
            mapOf(
                "/release-group" to """{"release-groups":[]}""",
                "/artist" to
                    """{"artists":[{"id":"a","name":"Band","score":100},{"id":"b","name":"Band","score":100}]}""",
            ),
        )
        val s = scan(http, mapOf("Band/Record/01.flac" to AudioTags("One", "Band", album = "Record", track = 1)))
        assertTrue(s.looked("Band"))
        assertNull(s.facts("Band"))
        assertNull(s.facts("Record"))
        assertTrue(calls.none { it.startsWith("/artist/") }, calls.toString())
    }

    @Test fun `a rate limit ends the run and keeps nothing, so the next scan asks again`() {
        val calls = mutableListOf<String>()
        val http = musicBrainz(calls, emptyMap(), HttpStatusCode.ServiceUnavailable)
        val s = scan(
            http,
            mapOf(
                "Band/Record/01.flac" to AudioTags("One", "Band", album = "Record", track = 1),
                "Band/Other/01.flac" to AudioTags("Two", "Band", album = "Other", track = 1),
            ),
        )
        // One call a scan: the first album's search, then the run stops.
        assertEquals(2, calls.size)
        assertTrue(s.items.none { s.looked(it.title) })
        assertTrue(s.note.orEmpty().contains("stopped: MUSICBRAINZ_RATE_LIMITED"), s.note)
    }

    private companion object {
        const val GROUP = "11111111-2222-3333-4444-555555555555"
        const val ARTIST = "66666666-7777-8888-9999-000000000000"
    }
}
