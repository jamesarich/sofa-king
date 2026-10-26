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
    private class Scanned(
        val store: BackendStore,
        val items: List<LibraryItemRecord>,
        val note: String?,
        val posters: Map<String, ArtSource?>,
    ) {
        fun poster(title: String): ArtSource? = posters[items.single { it.title == title }.id]

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
     * A MusicBrainz stand-in answering [routes] by path, after `/ws/2`, and the Cover Art Archive's under `caa:`, where
     * [COVER] answers a redirect to the image; [calls] gets each path, with its search query. Every request must name
     * the server in its User-Agent, and every MusicBrainz one ask for JSON.
     */
    private fun musicBrainz(
        calls: MutableList<String>,
        routes: Map<String, String>,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = HttpClient(
        MockEngine { request ->
            val caa = request.url.host == "coverartarchive.org"
            val path = if (caa) "caa:" + request.url.encodedPath else request.url.encodedPath.removePrefix("/ws/2")
            calls += path + (request.url.parameters["query"]?.let { "?$it" } ?: "")
            assertTrue(request.headers[HttpHeaders.UserAgent].orEmpty().startsWith("SofaKing/test ("))
            if (!caa) assertEquals("json", request.url.parameters["fmt"])
            val body = routes[path]
            when {
                status != HttpStatusCode.OK -> respond("{}", status)
                body == null -> respond("""{"error":"Not Found"}""", HttpStatusCode.NotFound)
                body == COVER -> respond("", HttpStatusCode.TemporaryRedirect, headersOf(HttpHeaders.Location, IMAGE))
                else -> respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        },
    ) { followRedirects = false }

    /** Scans a Music library of [tags]' files and the [others], twice, looking each up on [http]. */
    private fun scan(http: HttpClient, tags: Map<String, AudioTags>, vararg others: String): Scanned = runBlocking {
        val dir = Files.createTempDirectory("sofa-music-")
        val music = Files.createDirectories(dir.resolve("media/Music"))
        for (path in tags.keys + others) {
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
        val posters = service.catalog.snapshot().views.mapValues { it.value.poster }
        Scanned(store, store.libraryItems(library.id), note, posters)
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
        // The Cover Art Archive has no cover for it, so it keeps none.
        assertEquals(false, album?.coverArt)
        assertNull(s.poster("Record"))
        // The second scan finds both fresh, and asks for nothing.
        assertEquals(listOf("/release-group/$GROUP", "caa:/release-group/$GROUP/front-250", "/artist/$ARTIST"), calls)
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

    @Test fun `an album with no picture of its own shows the Cover Art Archive's, and its artist borrows it`() {
        val calls = mutableListOf<String>()
        val group = """{"id":"$GROUP","title":"Record","artist-credit":[{"name":"Band","artist":{"id":"$ARTIST"}}]}"""
        val routes = mapOf(
            "/release-group/$GROUP" to group,
            "/release-group/$OTHER" to group.replace(GROUP, OTHER),
            "caa:/release-group/$GROUP/front-250" to COVER,
            "caa:/release-group/$OTHER/front-250" to COVER,
        )
        val s = scan(
            musicBrainz(calls, routes),
            mapOf(
                // The newest album, so its artist borrows its cover.
                "Band/Record/01.flac" to
                    AudioTags("One", "Band", album = "Record", track = 1, date = "2001", releaseGroupId = GROUP),
                "Band/Covered/01.flac" to AudioTags("Two", "Band", album = "Covered", releaseGroupId = OTHER),
            ),
            "Band/Covered/cover.jpg",
        )
        assertEquals(true, s.facts("Record")?.coverArt)
        assertEquals(ArtSource.CoverArt(GROUP), s.poster("Record"))
        assertEquals(ArtSource.CoverArt(GROUP), s.poster("Band"))
        // An album with its own cover file keeps it, and the archive is not asked.
        assertEquals(false, s.facts("Covered")?.coverArt)
        assertTrue(s.poster("Covered") is ArtSource.Local)
        assertTrue("caa:/release-group/$OTHER/front-250" !in calls, calls.toString())
    }

    @Test fun `a cover is fetched once through the archive's redirect, and never from a loopback host`() = runBlocking {
        val asked = mutableListOf<String>()
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10)
        val http = HttpClient(
            MockEngine { request ->
                asked += request.url.toString()
                when (request.url.host) {
                    "coverartarchive.org" -> {
                        val to = if (OTHER in request.url.encodedPath) "https://127.0.0.1/x.jpg" else IMAGE
                        respond("", HttpStatusCode.TemporaryRedirect, headersOf(HttpHeaders.Location, to))
                    }

                    else -> respond(jpeg, headers = headersOf(HttpHeaders.ContentType, "image/jpeg"))
                }
            },
        ) { followRedirects = false }
        val artwork = LibraryArtwork(http, Files.createTempDirectory("sofa-art-"))
        val cover = artwork.image(ArtSource.CoverArt(GROUP))
        assertEquals("image/jpeg", cover?.contentType)
        assertTrue(jpeg.contentEquals(Files.readAllBytes(cover!!.file)))
        artwork.image(ArtSource.CoverArt(GROUP))
        assertEquals(listOf("https://coverartarchive.org/release-group/$GROUP/front-500", IMAGE), asked)
        assertNull(artwork.image(ArtSource.CoverArt(OTHER)))
        assertTrue(asked.none { it.contains("127.0.0.1") }, asked.toString())
        assertNull(artwork.image(ArtSource.CoverArt("../../etc/passwd")))
    }

    private companion object {
        const val COVER = "cover"
        const val IMAGE = "https://archive.test/cover-500.jpg"
        const val OTHER = "abcdefab-cdef-abcd-efab-cdefabcdefab"
        const val GROUP = "11111111-2222-3333-4444-555555555555"
        const val ARTIST = "66666666-7777-8888-9999-000000000000"
    }
}
