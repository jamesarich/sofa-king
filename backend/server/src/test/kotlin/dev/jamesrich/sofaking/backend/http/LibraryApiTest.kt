package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.*
import dev.jamesrich.sofaking.backend.catalog.*
import dev.jamesrich.sofaking.backend.library.IntroRecord
import dev.jamesrich.sofaking.backend.library.IntroSpan
import dev.jamesrich.sofaking.backend.library.LibraryKind
import dev.jamesrich.sofaking.backend.library.ScrubRecord
import dev.jamesrich.sofaking.backend.library.fileKey
import dev.jamesrich.sofaking.backend.store.libraryItems
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.backend.store.saveLibraryFingerprint
import dev.jamesrich.sofaking.backend.store.saveLibraryScrub
import dev.jamesrich.sofaking.contract.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

class LibraryApiTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val token = "secret".repeat(8)

    @Test fun `the app reads libraries, items, home and artwork, and records watch state`() = testApplication {
        val dir = Files.createTempDirectory("sofa-library-read-")
        val show = Files.createDirectories(dir.resolve("media/tv/Show (2020)/Season 01"))
        Files.writeString(show.resolve("Show - S01E01 - Pilot.mkv"), "x")
        Files.writeString(show.resolve("Show - S01E02 - Second.mkv"), "x")
        Files.write(show.parent.resolve("poster.jpg"), byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()))
        Files.writeString(dir.resolve("sources.json"), """{"providers":[]}""")
        val config = ServerConfig("127.0.0.1", 8080, dir, dir.resolve("sources.json"), token, hdhr = HdhrConfig(enabled = false), updateImage = null)
        val store = openStore(dir)
        val runtime = LiveRuntime.create(store, config, MutableClock(), probes = false)
        try {
            val libraries = runtime.libraries!!
            val library = libraries.create("TV", LibraryKind.SHOWS, dir.resolve("media/tv").toString())
            libraries.scan(library.id)
            application { backendModule(store, MutableClock(), runtime, token, config = config) }
            suspend fun get(path: String) = client.get(path) { bearerAuth(token) }

            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/libraries").status)
            assertTrue(json.decodeFromString<Bootstrap>(get("/api/v1/bootstrap").bodyAsText()).capabilities.library)
            assertEquals(listOf(Library(library.id, "TV", "shows", 1)), json.decodeFromString<List<Library>>(get("/api/v1/libraries").bodyAsText()))

            val pageBody = get("/api/v1/libraries/${library.id}/items?sort=added&limit=1").bodyAsText()
            val page = json.decodeFromString<LibraryPage>(pageBody)
            assertEquals("Show", page.items.single().title)
            assertNull(page.next)
            assertEquals(HttpStatusCode.BadRequest, get("/api/v1/libraries/${library.id}/items?sort=size").status)
            assertEquals(HttpStatusCode.NotFound, get("/api/v1/libraries/nope/items").status)

            val showDetail = json.decodeFromString<LibraryItemDetail>(get("/api/v1/library/items/${page.items.single().id}").bodyAsText())
            val seasonBody = get("/api/v1/library/items/${showDetail.children.single().id}").bodyAsText()
            val (pilot, second) = json.decodeFromString<LibraryItemDetail>(seasonBody).children
            assertEquals("Pilot", showDetail.next?.title)

            // Artwork takes no token, and serves only what the library holds.
            val poster = client.get(page.items.single().poster!!)
            assertEquals(HttpStatusCode.OK, poster.status)
            assertEquals(ContentType.Image.JPEG, poster.contentType())
            assertEquals(HttpStatusCode.NotModified, client.get(page.items.single().poster!!) { header(HttpHeaders.IfNoneMatch, poster.headers[HttpHeaders.ETag]!!) }.status)
            assertEquals(HttpStatusCode.NotFound, client.get("/artwork/${pilot.id}/poster").status)
            assertEquals(HttpStatusCode.NotFound, client.get("/artwork/${page.items.single().id}/thumb").status)

            val put = client.put("/api/v1/library/items/${pilot.id}/play-state") {
                bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"positionMs":5700000,"durationMs":6000000}""")
            }
            assertTrue(json.decodeFromString<LibraryItem>(put.bodyAsText()).playState!!.watched)
            client.put("/api/v1/library/items/${second.id}/play-state") {
                bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"positionMs":600000,"durationMs":6000000}""")
            }
            val homeBody = get("/api/v1/library/home").bodyAsText()
            val home = json.decodeFromString<LibraryHome>(homeBody)
            assertEquals(listOf(Wire.ShelfId.CONTINUE, Wire.ShelfId.RECENT), home.shelves.map { it.id })
            assertEquals(second.id, home.shelves.first().items.single().id)
            assertEquals(1, json.decodeFromString<LibraryPage>(get("/api/v1/library/search?q=pilot").bodyAsText()).items.size)
            assertEquals(HttpStatusCode.BadRequest, get("/api/v1/library/search").status)

            assertKeepsWireFixture("v1/library-page.json", pageBody)
            assertKeepsWireFixture("v1/library-item-detail.json", seasonBody)

            // An episode's page carries the intro and credits intro detection found in it.
            val pilotRecord = store.libraryItems(library.id).single { it.id == pilot.id }
            store.saveLibraryFingerprint(
                IntroRecord(pilot.id, fileKey(pilotRecord), null, IntroSpan(5_000, 65_000), "2026-10-10T13:00:00Z", tailStartMs = 0, credits = IntroSpan(1_300_000, 1_380_000)),
            )
            // And the scrub thumbnails made from it, served like artwork with no token.
            store.saveLibraryScrub(ScrubRecord(pilot.id, fileKey(pilotRecord), 10_000, 240, 136, 10, 10, 1, 3, "2026-10-10T13:00:00Z"))
            val sheet = Files.createDirectories(dir.resolve("scrub/${pilot.id}")).resolve("0.jpg")
            Files.write(sheet, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()))
            val episodeBody = get("/api/v1/library/items/${pilot.id}").bodyAsText()
            val episode = json.decodeFromString<LibraryItemDetail>(episodeBody)
            assertEquals(
                listOf(MediaSegment(Wire.SegmentKind.INTRO, 5_000, 65_000), MediaSegment(Wire.SegmentKind.CREDITS, 1_300_000, 1_380_000)),
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
            assertNull(json.decodeFromString<LibraryItemDetail>(get("/api/v1/library/items/${second.id}").bodyAsText()).scrub)
            assertKeepsWireFixture("v1/library-episode-detail.json", episodeBody)
            assertKeepsWireFixture("v1/library-home.json", homeBody)
        } finally { runtime.close(); store.close(); dir.toFile().deleteRecursively() }
    }
    @Test fun `a library's grid filters by genre and sorts by what was watched last`() = testApplication {
        val dir = Files.createTempDirectory("sofa-library-genre-")
        val movies = Files.createDirectories(dir.resolve("media/movies"))
        for ((name, genres) in listOf("Alpha (2001)" to listOf("Drama"), "Bravo (2002)" to listOf("Comedy", "drama"), "Charlie (2003)" to emptyList())) {
            val folder = Files.createDirectories(movies.resolve(name))
            Files.writeString(folder.resolve("$name.mkv"), "x")
            Files.writeString(folder.resolve("$name.nfo"), "<movie>" + genres.joinToString("") { "<genre>$it</genre>" } + "</movie>")
        }
        Files.writeString(dir.resolve("sources.json"), """{"providers":[]}""")
        val config = ServerConfig("127.0.0.1", 8080, dir, dir.resolve("sources.json"), token, hdhr = HdhrConfig(enabled = false), updateImage = null)
        val store = openStore(dir)
        val clock = MutableClock()
        val runtime = LiveRuntime.create(store, config, clock, probes = false)
        try {
            val library = runtime.libraries!!.create("Movies", LibraryKind.MOVIES, movies.toString())
            runtime.libraries!!.scan(library.id)
            application { backendModule(store, clock, runtime, token, config = config) }
            suspend fun page(query: String) =
                json.decodeFromString<LibraryPage>(client.get("/api/v1/libraries/${library.id}/items?$query") { bearerAuth(token) }.bodyAsText())

            val all = page("sort=title")
            assertEquals(listOf("Alpha", "Bravo", "Charlie"), all.items.map { it.title })
            assertEquals(listOf("Comedy", "Drama"), all.genres)
            assertEquals(listOf("Alpha", "Bravo"), page("genre=drama").items.map { it.title })
            assertEquals(2, page("genre=drama").total)
            assertEquals(listOf("Comedy", "Drama"), page("genre=drama").genres, "the filter still lists every genre")

            suspend fun played(item: LibraryItem) = client.put("/api/v1/library/items/${item.id}/play-state") {
                bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"positionMs":600000,"durationMs":6000000}""")
            }
            assertEquals(HttpStatusCode.OK, played(all.items[2]).status)
            clock.now = clock.now.plusSeconds(60)
            assertEquals(HttpStatusCode.OK, played(all.items[1]).status)
            assertEquals(listOf("Bravo", "Charlie", "Alpha"), page("sort=watched").items.map { it.title })
        } finally { runtime.close(); store.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun `an episode with every fact a scan reads keeps every field of the library item and library list fixtures`() = testApplication {
        val dir = Files.createTempDirectory("sofa-library-full-")
        val show = Files.createDirectories(dir.resolve("media/tv/Show (2020)"))
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        Files.write(show.resolve("poster.jpg"), jpeg)
        Files.write(show.resolve("fanart.jpg"), jpeg)
        val episode = show.resolve("Season 01/Show - S01E01-E02 - Pilot.mkv")
        hdrClip(episode)
        Files.write(episode.resolveSibling("Show - S01E01-E02 - Pilot-thumb.jpg"), jpeg)
        Files.writeString(episode.resolveSibling("Show - S01E01-E02 - Pilot.nfo"), """
            <episodedetails><title>Pilot</title><season>1</season><episode>1</episode><aired>2020-01-05</aired>
            <plot>Where it starts.</plot><runtime>44</runtime><genre>Drama</genre><mpaa>TV-14</mpaa><rating>8.1</rating></episodedetails>
        """.trimIndent())
        Files.writeString(dir.resolve("sources.json"), """{"providers":[]}""")
        val config = ServerConfig("127.0.0.1", 8080, dir, dir.resolve("sources.json"), token, hdhr = HdhrConfig(enabled = false), updateImage = null)
        val store = openStore(dir)
        val clock = MutableClock()
        val runtime = LiveRuntime.create(store, config, clock, probes = false)
        try {
            val libraries = runtime.libraries!!
            val library = libraries.create("TV", LibraryKind.SHOWS, dir.resolve("media/tv").toString())
            libraries.scan(library.id)
            val catalog = libraries.catalog
            val season = catalog.item(catalog.items(library.id, null, false, null, 10).items.single().id).children.single()
            val pilot = catalog.item(season.id).children.single()
            application { backendModule(store, clock, runtime, token, config = config) }

            val put = client.put("/api/v1/library/items/${pilot.id}/play-state") {
                bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"positionMs":600000,"durationMs":2640000}""")
            }
            assertEquals(HttpStatusCode.OK, put.status, put.bodyAsText())
            val itemBody = put.bodyAsText()
            val item = json.decodeFromString<LibraryItem>(itemBody)
            assertEquals(Triple(1, 1, 2), Triple(item.season, item.episode, item.episodeEnd))
            assertEquals(180, item.videoHeight)
            assertTrue(item.hdr, "FFprobe read the PQ transfer")
            assertEquals(600000, item.playState?.positionMs)
            val nulls = Json.parseToJsonElement(itemBody).jsonObject.filterValues { it is JsonNull }.keys
            assertTrue(nulls.isEmpty(), "the item still has nulls: $nulls")
            assertKeepsWireFixture("v1/library-item.json", itemBody)

            val librariesBody = client.get("/api/v1/libraries") { bearerAuth(token) }.bodyAsText()
            assertEquals(listOf(Library(library.id, "TV", "shows", 1)), json.decodeFromString<List<Library>>(librariesBody))
            assertKeepsWireFixture("v1/libraries.json", librariesBody)
        } finally { runtime.close(); store.close(); dir.toFile().deleteRecursively() }
    }

    /** A two-second Matroska clip whose video is tagged as HDR10 (PQ transfer, BT.2020). */
    private fun hdrClip(file: Path) {
        Files.createDirectories(file.parent)
        val process = ProcessBuilder(
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-y", "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=15",
            // The frames carry the tags too: newer FFmpeg writes the filter graph's color properties, not the output options.
            "-t", "2", "-vf", "setparams=color_trc=smpte2084:color_primaries=bt2020:colorspace=bt2020nc",
            "-pix_fmt", "yuv420p", "-c:v", "mpeg2video", "-color_trc", "smpte2084", "-color_primaries", "bt2020", "-colorspace", "bt2020nc",
            file.toString(),
        ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "FFmpeg could not write the clip" }
    }
}
