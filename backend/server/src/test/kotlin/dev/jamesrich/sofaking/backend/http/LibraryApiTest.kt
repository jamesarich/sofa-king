package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.*
import dev.jamesrich.sofaking.backend.catalog.*
import dev.jamesrich.sofaking.backend.library.LibraryKind
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.contract.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlinx.serialization.json.Json
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
            assertKeepsWireFixture("v1/library-home.json", homeBody)
        } finally { runtime.close(); store.close(); dir.toFile().deleteRecursively() }
    }
}
