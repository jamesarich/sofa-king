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
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryLyricsTest {
    /** A Music library's [lyrics] and its tracks' [ids] by title. */
    private class Library(val lyrics: LibraryLyrics, val ids: Map<String, String>, val clock: MutableClock) {
        fun of(title: String): String? = runBlocking { lyrics.lyrics(ids.getValue(title)) }
    }

    /** One album of three songs, LRCLIB answering [answers] by title and noting [calls], [lrc] beside the first. */
    private fun library(answers: Map<String, String>, calls: MutableList<String>, lrc: String? = null): Library =
        runBlocking {
            val dir = Files.createTempDirectory("sofa-lyrics-")
            val music = Files.createDirectories(dir.resolve("media/Music/Band/Record"))
            val files = mapOf(
                "01 One.flac" to AudioTags("One", "Band", album = "Record", track = 1),
                "02 Two.flac" to AudioTags("Two", "Band", album = "Record", track = 2),
                "03 Three.flac" to AudioTags("Three", "Band", album = "Record", track = 3),
            )
            files.keys.forEach { Files.writeString(music.resolve(it), "x") }
            lrc?.let { Files.writeString(music.resolve("01 One.lrc"), it) }
            val clock = MutableClock()
            val store = openStore(dir.resolve("data"), clock)
            val scanner = LibraryScanner({ file: Path ->
                MediaInfo(
                    "flac",
                    durationMs = 200_400,
                    audio = listOf(AudioStream(0, "flac")),
                    tags = files[file.fileName.toString()],
                )
            })
            val http = HttpClient(
                MockEngine { request ->
                    val title = request.url.parameters["track_name"].orEmpty()
                    calls +=
                        "$title|${request.url.parameters["artist_name"]}|${request.url.parameters["album_name"]}|" +
                        request.url.parameters["duration"]
                    assertTrue(request.headers[HttpHeaders.UserAgent].orEmpty().startsWith("SofaKing/test ("))
                    val body = answers[title]
                    if (body == null) {
                        respond("""{"code":404}""", HttpStatusCode.NotFound)
                    } else {
                        respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                },
            )
            val lyrics = LibraryLyrics(store, LrclibClient(http, "test"), dir.resolve("data/lyrics"), clock)
            val service = LibraryService(
                store,
                clock,
                listOf(MediaRoot("Media", dir.resolve("media"))),
                scanner,
                Jobs(clock),
                lyrics = lyrics,
            )
            val created = service.create("Music", LibraryKind.MUSIC, dir.resolve("media/Music").toString())
            service.scan(created.id)
            val ids = store.libraryItems(created.id).filter {
                it.kind == ItemKind.TRACK
            }.associate { it.title to it.id }
            Library(lyrics, ids, clock)
        }

    @Test fun `an lrc file beside the track wins, else LRCLIB's synced lyrics, asked once`() {
        val calls = mutableListOf<String>()
        val l = library(
            mapOf("Two" to """{"instrumental":false,"plainLyrics":"Two","syncedLyrics":"[00:01.00] Two"}"""),
            calls,
            lrc = "[00:00.50] One",
        )
        assertEquals("[00:00.50] One", l.of("One"))
        assertEquals("[00:01.00] Two", l.of("Two"))
        assertEquals("[00:01.00] Two", l.of("Two"))
        // LRCLIB finds a song by its title, artist, album and length in seconds.
        assertEquals(listOf("Two|Band|Record|200"), calls)
    }

    @Test fun `a song LRCLIB has no lyrics for is asked again only after a week`() {
        val calls = mutableListOf<String>()
        val l = library(mapOf("Two" to """{"instrumental":true}"""), calls)
        assertNull(l.of("Three"))
        assertNull(l.of("Three"))
        assertNull(l.of("Two"), "an instrumental has none")
        assertEquals(2, calls.size, calls.toString())
        l.clock.now = l.clock.now.plus(Duration.ofDays(8))
        assertNull(l.of("Three"))
        assertEquals(3, calls.size, calls.toString())
    }
}
