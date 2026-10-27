package dev.jamesrich.sofaking.backend.library

import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.jobs.Jobs
import dev.jamesrich.sofaking.backend.store.libraryItems
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
import kotlin.test.assertTrue

class ListenBrainzTest {
    private val band = "0383dadf-2a4e-4d10-a46a-e9e041da8eb3"
    private val other = "1b72331b-3a97-4981-a81c-eeee9c275d28"
    private val third = "2e41ae9c-afd5-4f24-9c4e-0dbe8a32c4e1"

    /** MusicBrainz and ListenBrainz stand-ins: each artist found by id, and ListenBrainz's answers for Band only. */
    private fun http(calls: MutableList<String>) = HttpClient(
        MockEngine { request ->
            val url = request.url
            calls += url.host + url.encodedPath
            assertTrue(request.headers[HttpHeaders.UserAgent].orEmpty().startsWith("SofaKing/test ("))
            val body = when {
                url.encodedPath == "/ws/2/artist/$band" -> """{"id":"$band","name":"Band"}"""

                url.encodedPath == "/ws/2/artist/$other" -> """{"id":"$other","name":"Other"}"""

                url.encodedPath == "/ws/2/artist/$third" -> """{"id":"$third","name":"Third"}"""

                url.encodedPath.startsWith("/ws/2/") -> """{"release-groups":[],"artists":[]}"""

                url.encodedPath == "/1/popularity/top-recordings-for-artist/$band" ->
                    """[{"recording_mbid":"r9","recording_name":"Not here"},
                    {"recording_mbid":"r7","recording_name":"TWO!"},
                    {"recording_mbid":"r1","recording_name":"Called otherwise"},
                    {"recording_mbid":"r8","recording_name":"two"}]"""

                url.encodedPath == "/similar-artists/json" && url.parameters["artist_mbids"] == band ->
                    """[{"artist_mbid":"4a8f5e2b-0000-4000-8000-000000000000"},{"artist_mbid":"$third"},
                    {"artist_mbid":"$band"}]"""

                else -> null
            }
            if (body == null) {
                respond("[]", HttpStatusCode.NotFound)
            } else {
                respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        },
    )

    /** Band's three songs, the first tagged with its recording id, and one song each by Other and Third. */
    private val files = mapOf(
        "Band/Record/01.flac" to AudioTags(
            "One",
            "Band",
            album = "Record",
            track = 1,
            albumArtistId = band,
            recordingId = "r1",
        ),
        "Band/Record/02.flac" to AudioTags("Two", "Band", album = "Record", track = 2, albumArtistId = band),
        "Band/Record/03.flac" to AudioTags("Three", "Band", album = "Record", track = 3, albumArtistId = band),
        "Other/Disc/01.flac" to AudioTags(
            "Song",
            "Other",
            album = "Disc",
            track = 1,
            albumArtistId = other,
            genres = listOf("Rock"),
        ),
        "Third/LP/01.flac" to AudioTags("Tune", "Third", album = "LP", track = 1, albumArtistId = third),
    )

    /** A Music library of [files], scanned twice with MusicBrainz and ListenBrainz asked over [http]. */
    private suspend fun scanned(http: HttpClient): Pair<LibraryService, Map<String, LibraryItemRecord>> {
        val dir = Files.createTempDirectory("sofa-listens-")
        val music = Files.createDirectories(dir.resolve("media/Music"))
        for (path in files.keys) {
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
                tags = files[music.relativize(file).joinToString("/")],
            )
        })
        val service = LibraryService(
            store,
            clock,
            listOf(MediaRoot("Media", dir.resolve("media"))),
            scanner,
            Jobs(clock),
            musicMetadata = MusicMetadata(
                store,
                MusicBrainzClient(http, "test", pause = Duration.ZERO, backoff = Duration.ZERO),
                clock,
                listenBrainz = ListenBrainzClient(http, "test"),
            ),
        )
        val library = service.create("Music", LibraryKind.MUSIC, music.toString())
        service.scan(library.id)
        service.scan(library.id)
        return service to store.libraryItems(library.id).associateBy { it.title }
    }

    @Test fun `an artist's page lists their most played songs and the similar artists first`() = runBlocking {
        val calls = mutableListOf<String>()
        val (service, items) = scanned(http(calls))
        val page = service.catalog.item(items.getValue("Band").id, music = true)
        // Matched by recording id, then by title whatever its case and punctuation, each once, in ListenBrainz's order.
        assertEquals(listOf("Two", "One"), page.popular.map { it.title })
        assertEquals("Third", page.related.firstOrNull()?.title)
        // ListenBrainz is asked about each artist once, and the second scan finds them fresh.
        assertEquals(1, calls.count { it.endsWith("/top-recordings-for-artist/$band") }, calls.toString())
        assertEquals(3, calls.count { it.startsWith("labs.api.listenbrainz.org/") }, calls.toString())
        assertTrue(service.catalog.item(items.getValue("Other").id, music = true).popular.isEmpty())
    }
}
