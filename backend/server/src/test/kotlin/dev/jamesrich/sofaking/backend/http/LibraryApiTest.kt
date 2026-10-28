package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.HdhrConfig
import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.library.INTRO_JOB
import dev.jamesrich.sofaking.backend.library.IntroRecord
import dev.jamesrich.sofaking.backend.library.IntroSpan
import dev.jamesrich.sofaking.backend.library.LibraryKind
import dev.jamesrich.sofaking.backend.library.LibraryRecord
import dev.jamesrich.sofaking.backend.library.ScrubRecord
import dev.jamesrich.sofaking.backend.library.fileKey
import dev.jamesrich.sofaking.backend.pollUntil
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.libraryItems
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.backend.store.saveLibraryFingerprint
import dev.jamesrich.sofaking.backend.store.saveLibraryScrub
import dev.jamesrich.sofaking.contract.Bootstrap
import dev.jamesrich.sofaking.contract.Library
import dev.jamesrich.sofaking.contract.LibraryHome
import dev.jamesrich.sofaking.contract.LibraryItem
import dev.jamesrich.sofaking.contract.LibraryItemDetail
import dev.jamesrich.sofaking.contract.LibraryPage
import dev.jamesrich.sofaking.contract.MediaSegment
import dev.jamesrich.sofaking.contract.ScrubThumbnails
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.request.options
import io.ktor.client.request.put
import io.ktor.client.request.query
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headers
import io.ktor.http.path
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryApiTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val token = "secret".repeat(8)
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())

    @Test fun `the libraries are behind the token and the bootstrap says the server has them`() = twoEpisodes {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/libraries").status)
        assertTrue(json.decodeFromString<Bootstrap>(text("/api/v1/bootstrap")).capabilities.library)
        assertEquals(
            listOf(Library(library.id, "TV", "shows", 1)),
            json.decodeFromString<List<Library>>(text("/api/v1/libraries")),
        )
    }

    @Test fun `a library pages its items and refuses an unknown sort or library`() = twoEpisodes {
        val pageBody = text("/api/v1/libraries/${library.id}/items?sort=added&limit=1")
        val page = json.decodeFromString<LibraryPage>(pageBody)
        assertEquals("Show", page.items.single().title)
        assertNull(page.next)
        assertEquals(HttpStatusCode.BadRequest, get("/api/v1/libraries/${library.id}/items?sort=size").status)
        assertEquals(HttpStatusCode.NotFound, get("/api/v1/libraries/nope/items").status)
        assertKeepsWireFixture("v1/library-page.json", pageBody)
    }

    @Test fun `a show's page leads to its season and names the next episode`() = twoEpisodes {
        val show = json.decodeFromString<LibraryItemDetail>(text("/api/v1/library/items/${top().id}"))
        assertEquals("Pilot", show.next?.title)
        val seasonBody = text("/api/v1/library/items/${show.children.single().id}")
        val season = json.decodeFromString<LibraryItemDetail>(seasonBody)
        assertEquals(listOf("Pilot", "Second"), season.children.map { it.title })
        assertKeepsWireFixture("v1/library-item-detail.json", seasonBody)
    }

    @Test fun `artwork takes no token and serves only what the library holds`() = twoEpisodes {
        val show = top()
        val poster = client.get(show.poster!!)
        assertEquals(HttpStatusCode.OK, poster.status)
        assertEquals(ContentType.Image.JPEG, poster.contentType())
        assertEquals(
            HttpStatusCode.NotModified,
            client.get(show.poster!!) { header(HttpHeaders.IfNoneMatch, poster.headers[HttpHeaders.ETag]!!) }.status,
        )
        assertEquals(HttpStatusCode.NotFound, client.get("/artwork/${episodes().first.id}/poster").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/artwork/${show.id}/thumb").status)
    }

    @Test fun `watch state marks an episode watched and fills the home shelves`() = twoEpisodes {
        val (pilot, second) = episodes()
        val put = play(pilot.id, """{"positionMs":5700000,"durationMs":6000000}""")
        assertTrue(json.decodeFromString<LibraryItem>(put.bodyAsText()).playState!!.watched)
        play(second.id, """{"positionMs":600000,"durationMs":6000000}""")
        val homeBody = text("/api/v1/library/home")
        val home = json.decodeFromString<LibraryHome>(homeBody)
        assertEquals(listOf(Wire.ShelfId.CONTINUE, Wire.ShelfId.RECENT), home.shelves.map { it.id })
        assertEquals(second.id, home.shelves.first().items.single().id)
        assertKeepsWireFixture("v1/library-home.json", homeBody)
    }

    @Test fun `search finds an episode by title and needs a query`() = twoEpisodes {
        assertEquals(1, json.decodeFromString<LibraryPage>(text("/api/v1/library/search?q=pilot")).items.size)
        assertEquals(HttpStatusCode.BadRequest, get("/api/v1/library/search").status)
    }

    @Test fun `an episode's page carries its intro, credits and scrub thumbnails`() = twoEpisodes {
        val (pilot, second) = episodes()
        // The scan queued an intro pass of its own, which reads these files that hold no audio and saves that it
        // found nothing, so the test waits for that pass before it writes what one would have found.
        pollUntil(10_000) {
            val pass = runtime.jobs.snapshot().single { it.id == INTRO_JOB }
            pass.lastStartedAt != null && !pass.running
        }
        val pilotRecord = store.libraryItems(library.id).single { it.id == pilot.id }
        store.saveLibraryFingerprint(
            IntroRecord(
                pilot.id,
                fileKey(pilotRecord),
                null,
                IntroSpan(5_000, 65_000),
                "2026-10-10T13:00:00Z",
                tailStartMs = 0,
                credits = IntroSpan(1_300_000, 1_380_000),
            ),
        )
        // And the scrub thumbnails made from it, served like artwork with no token.
        store.saveLibraryScrub(
            ScrubRecord(pilot.id, fileKey(pilotRecord), 10_000, 240, 136, 10, 10, 1, 3, "2026-10-10T13:00:00Z"),
        )
        Files.write(Files.createDirectories(dir.resolve("scrub/${pilot.id}")).resolve("0.jpg"), jpeg)
        // A started episode, so the page carries its play state as the fixture does.
        assertEquals(HttpStatusCode.OK, play(pilot.id, """{"positionMs":600000,"durationMs":6000000}""").status)
        val episodeBody = text("/api/v1/library/items/${pilot.id}")
        val episode = json.decodeFromString<LibraryItemDetail>(episodeBody)
        assertEquals(
            listOf(
                MediaSegment(Wire.SegmentKind.INTRO, 5_000, 65_000),
                MediaSegment(Wire.SegmentKind.CREDITS, 1_300_000, 1_380_000),
            ),
            episode.segments,
        )
        val scrub = assertNotNull(episode.scrub)
        assertEquals(ScrubThumbnails(10_000, 240, 136, 10, 10, 3, scrub.sheets), scrub)
        val served = client.get(scrub.sheets.single())
        assertEquals(HttpStatusCode.OK, served.status)
        assertEquals(ContentType.Image.JPEG, served.contentType())
        assertEquals(HttpStatusCode.NotFound, client.get("/artwork/${pilot.id}/scrub/1").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/artwork/${second.id}/scrub/0").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/artwork/a.b/scrub/0").status)
        assertNull(json.decodeFromString<LibraryItemDetail>(text("/api/v1/library/items/${second.id}")).scrub)
        assertKeepsWireFixture("v1/library-episode-detail.json", episodeBody)
    }

    @Test fun `a library's grid filters by genre and sorts by what was watched last`() = libraryServer(
        "sofa-library-genre-",
        { movies ->
            movie(movies, "Alpha (2001)", "<genre>Drama</genre>")
            movie(movies, "Bravo (2002)", "<genre>Comedy</genre><genre>drama</genre>")
            movie(movies, "Charlie (2003)", "")
        },
    ) {
        suspend fun page(query: String) =
            json.decodeFromString<LibraryPage>(text("/api/v1/libraries/${library.id}/items?$query"))
        val all = page("sort=title")
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), all.items.map { it.title })
        assertEquals(listOf("Comedy", "Drama"), all.genres)
        assertEquals(listOf("Alpha", "Bravo"), page("genre=drama").items.map { it.title })
        assertEquals(2, page("genre=drama").total)
        assertEquals(listOf("Comedy", "Drama"), page("genre=drama").genres, "the filter still lists every genre")
        assertEquals(HttpStatusCode.OK, play(all.items[2].id, """{"positionMs":600000,"durationMs":6000000}""").status)
        clock.now = clock.now.plusSeconds(60)
        assertEquals(HttpStatusCode.OK, play(all.items[1].id, """{"positionMs":600000,"durationMs":6000000}""").status)
        assertEquals(listOf("Bravo", "Charlie", "Alpha"), page("sort=watched").items.map { it.title })
    }

    @Test fun `a lookup finds a movie by any id its name, its nfo or TMDB gives`() = libraryServer(
        "sofa-library-lookup-",
        { movies ->
            movie(movies, "Alien (1979) {tmdb-348}", "<uniqueid type=\"imdb\">tt0078748</uniqueid>")
            movie(movies, "Heat (1995) [imdbid-tt0113277]", "")
        },
    ) {
        suspend fun lookup(query: String) =
            json.decodeFromString<LibraryPage>(text("/api/v1/library/lookup?$query")).items.map { it.title }
        assertEquals(listOf("Alien"), lookup("tmdb=348"))
        assertEquals(listOf("Alien"), lookup("imdb=TT0078748"))
        assertEquals(listOf("Alien", "Heat"), lookup("tmdb=348&imdb=tt0113277"))
        assertEquals(emptyList(), lookup("tvdb=81189"))
        val alien = json.decodeFromString<LibraryPage>(text("/api/v1/library/lookup?tmdb=348")).items.single()
        assertEquals(348, alien.tmdbId)
        assertEquals("tt0078748", alien.imdbId)
        for (bad in listOf("", "tmdb=x", "tmdb=0", "imdb=0078748")) {
            assertEquals(HttpStatusCode.BadRequest, get("/api/v1/library/lookup?$bad").status, bad)
        }
    }

    @Test fun `a movie's page lists its extras and the movies sharing the most genres`() = alienMovies {
        val page = json.decodeFromString<LibraryPage>(text("/api/v1/libraries/${library.id}/items"))
        val body = text("/api/v1/library/items/${page.items.first().id}")
        val detail = json.decodeFromString<LibraryItemDetail>(body)
        assertEquals(listOf("Trailer", "The Beast Within"), detail.extras.map { it.title })
        assertEquals(listOf(Wire.ExtraKind.TRAILER, Wire.ExtraKind.BEHIND_THE_SCENES), detail.extras.map { it.extra })
        assertEquals(setOf(Wire.ItemKind.EXTRA), detail.extras.map { it.kind }.toSet())
        assertEquals(emptyList(), detail.children)
        assertEquals(listOf("Prometheus", "Aliens"), detail.related.map { it.title }, "two genres shared, then one")
        assertEquals(
            emptyList(),
            json.decodeFromString<LibraryItemDetail>(text("/api/v1/library/items/${page.items[2].id}")).related,
        )
        assertKeepsWireFixture("v1/library-movie-detail.json", body)
    }

    @Test fun `extras stay out of the grid, the library's count and search`() = alienMovies {
        val page = json.decodeFromString<LibraryPage>(text("/api/v1/libraries/${library.id}/items"))
        assertEquals(listOf("Alien", "Aliens", "Heat", "Prometheus"), page.items.map { it.title })
        assertEquals(4, json.decodeFromString<List<Library>>(text("/api/v1/libraries")).single().items)
        assertEquals(emptyList(), json.decodeFromString<LibraryPage>(text("/api/v1/library/search?q=trailer")).items)
    }

    @Test fun `an extra plays and keeps its place but stays off Continue watching`() = alienMovies {
        val alien = top()
        val trailer = json.decodeFromString<LibraryItemDetail>(text("/api/v1/library/items/${alien.id}")).extras.first()
        assertEquals(
            Wire.ItemKind.EXTRA,
            json.decodeFromString<LibraryItemDetail>(text("/api/v1/library/items/${trailer.id}")).item.kind,
        )
        play(trailer.id, """{"positionMs":30000,"durationMs":120000}""")
        val home = json.decodeFromString<LibraryHome>(text("/api/v1/library/home"))
        assertEquals(listOf(Wire.ShelfId.RECENT), home.shelves.map { it.id })
        assertEquals(4, home.shelves.single().items.size)
    }

    @Test fun `an episode with every fact a scan reads keeps every field of the library item fixture`() = fullEpisode {
        val season = catalog.item(top().id).children.single()
        val pilot = catalog.item(season.id).children.single()
        val put = play(pilot.id, """{"positionMs":600000,"durationMs":2640000}""")
        assertEquals(HttpStatusCode.OK, put.status, put.bodyAsText())
        val itemBody = put.bodyAsText()
        val item = json.decodeFromString<LibraryItem>(itemBody)
        assertEquals(Triple(1, 1, 2), Triple(item.season, item.episode, item.episodeEnd))
        assertEquals(180, item.videoHeight)
        assertTrue(item.hdr, "FFprobe read the PQ transfer")
        assertEquals(600000, item.playState?.positionMs)
        // Only an extra says what kind of extra it is, only music has artists, albums and track numbers, and only a
        // song someone played has a last play.
        val music = setOf("artistId", "artist", "trackArtist", "album", "track", "disc", "lastPlayedAt")
        val nulls =
            Json.parseToJsonElement(itemBody).jsonObject.filterValues { it is JsonNull }.keys - "extra" - music
        assertTrue(nulls.isEmpty(), "the item still has nulls: $nulls")
        assertKeepsWireFixture("v1/library-item.json", itemBody)
    }

    @Test fun `the library list keeps every field of its fixture`() = fullEpisode {
        val body = text("/api/v1/libraries")
        assertEquals(listOf(Library(library.id, "TV", "shows", 1)), json.decodeFromString<List<Library>>(body))
        assertKeepsWireFixture("v1/libraries.json", body)
    }

    /** A scanned library and the server reading it. */
    private inner class Lib(
        val app: ApplicationTestBuilder,
        val dir: Path,
        val store: BackendStore,
        val runtime: LiveRuntime,
        val clock: MutableClock,
        val library: LibraryRecord,
    ) {
        val client get() = app.client
        val catalog get() = runtime.libraries!!.catalog

        suspend fun get(path: String): HttpResponse = client.get(path) { bearerAuth(token) }

        suspend fun text(path: String): String = get(path).bodyAsText()

        /** The library's first top-level item. */
        suspend fun top() = catalog.items(library.id, null, false, null, 10).items.first()

        /** The show's two episodes. */
        suspend fun episodes(): Pair<LibraryItem, LibraryItem> {
            val season = catalog.item(top().id).children.single()
            val (pilot, second) = catalog.item(season.id).children
            return pilot to second
        }

        suspend fun play(itemId: String, body: String) = client.put("/api/v1/library/items/$itemId/play-state") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    /** Runs [block] against a [kind] library over `media/<folder>`, which [media] fills, once it is scanned. */
    private fun libraryServer(
        prefix: String,
        media: (Path) -> Unit,
        kind: LibraryKind = LibraryKind.MOVIES,
        block: suspend Lib.() -> Unit,
    ) = testApplication {
        val dir = Files.createTempDirectory(prefix)
        val folder = Files.createDirectories(dir.resolve(if (kind == LibraryKind.SHOWS) "media/tv" else "media/movies"))
        media(folder)
        Files.writeString(dir.resolve("sources.json"), """{"providers":[]}""")
        val config =
            ServerConfig(
                "127.0.0.1",
                8080,
                dir,
                dir.resolve("sources.json"),
                token,
                hdhr = HdhrConfig(enabled = false),
                updateImage = null,
            )
        val store = openStore(dir)
        val clock = MutableClock()
        val runtime = LiveRuntime.create(store, config, clock, probes = false)
        try {
            val name = if (kind == LibraryKind.SHOWS) "TV" else "Movies"
            val library = runtime.libraries!!.create(name, kind, folder.toString())
            runtime.libraries!!.scan(library.id)
            application { backendModule(store, clock, runtime, token, config = config) }
            Lib(this, dir, store, runtime, clock, library).block()
        } finally {
            runtime.close()
            store.close()
            dir.toFile().deleteRecursively()
        }
    }

    /** A movie folder [name] with an NFO holding [facts]. */
    private fun movie(movies: Path, name: String, facts: String) {
        val folder = Files.createDirectories(movies.resolve(name))
        Files.writeString(folder.resolve("$name.mkv"), "x")
        Files.writeString(folder.resolve("$name.nfo"), "<movie>$facts</movie>")
    }

    /** A show with a poster and two episodes, Pilot and Second, that hold no media. */
    private fun twoEpisodes(block: suspend Lib.() -> Unit) = libraryServer("sofa-library-read-", { tv ->
        val season = Files.createDirectories(tv.resolve("Show (2020)/Season 01"))
        Files.writeString(season.resolve("Show - S01E01 - Pilot.mkv"), "x")
        Files.writeString(season.resolve("Show - S01E02 - Second.mkv"), "x")
        Files.write(season.parent.resolve("poster.jpg"), jpeg)
    }, LibraryKind.SHOWS, block)

    /** Three Alien movies and Heat, rated and in genres, Alien with a trailer and a behind-the-scenes extra. */
    private fun alienMovies(block: suspend Lib.() -> Unit) = libraryServer("sofa-library-extras-", { movies ->
        for ((name, genres, rating) in listOf(
            Triple("Alien (1979)", listOf("Horror", "Science Fiction"), 8.5),
            Triple("Aliens (1986)", listOf("Action", "Science Fiction"), 8.4),
            Triple("Prometheus (2012)", listOf("Science Fiction", "horror"), 7.0),
            Triple("Heat (1995)", listOf("Crime"), 8.3),
        )) {
            movie(movies, name, genres.joinToString("") { "<genre>$it</genre>" } + "<rating>$rating</rating>")
        }
        Files.writeString(movies.resolve("Alien (1979)/Alien (1979)-trailer.mkv"), "x")
        Files.createDirectories(movies.resolve("Alien (1979)/Behind The Scenes"))
        Files.writeString(movies.resolve("Alien (1979)/Behind The Scenes/The Beast Within.mkv"), "x")
    }, block = block)

    /** A double episode in HDR with a poster, fanart, a thumbnail and an NFO holding every fact a scan reads. */
    private fun fullEpisode(block: suspend Lib.() -> Unit) = libraryServer("sofa-library-full-", { tv ->
        val show = Files.createDirectories(tv.resolve("Show (2020)"))
        Files.write(show.resolve("poster.jpg"), jpeg)
        Files.write(show.resolve("fanart.jpg"), jpeg)
        val episode = show.resolve("Season 01/Show - S01E01-E02 - Pilot.mkv")
        hdrClip(episode)
        Files.write(episode.resolveSibling("Show - S01E01-E02 - Pilot-thumb.jpg"), jpeg)
        Files.writeString(
            episode.resolveSibling("Show - S01E01-E02 - Pilot.nfo"),
            "<episodedetails><title>Pilot</title><season>1</season><episode>1</episode><aired>2020-01-05</aired>\n" +
                "<plot>Where it starts.</plot><runtime>44</runtime><genre>Drama</genre><mpaa>TV-14</mpaa>" +
                "<rating>8.1</rating><uniqueid type=\"tmdb\">62085</uniqueid>" +
                "<uniqueid type=\"tvdb\">349232</uniqueid>" +
                "<uniqueid type=\"imdb\">tt0959621</uniqueid></episodedetails>",
        )
    }, LibraryKind.SHOWS, block)

    /** A two-second Matroska clip whose video is tagged as HDR10 (PQ transfer, BT.2020). */
    private fun hdrClip(file: Path) {
        Files.createDirectories(file.parent)
        val process = ProcessBuilder(
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-y", "-f", "lavfi", "-i",
            "testsrc2=size=320x180:rate=15",
            // The frames carry the tags too: newer FFmpeg writes the filter graph's color
            // properties, not the output options.
            "-t", "2", "-vf", "setparams=color_trc=smpte2084:color_primaries=bt2020:colorspace=bt2020nc",
            "-pix_fmt", "yuv420p", "-c:v", "mpeg2video", "-color_trc", "smpte2084", "-color_primaries", "bt2020",
            "-colorspace", "bt2020nc",
            file.toString(),
        ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "FFmpeg could not write the clip" }
    }
}
