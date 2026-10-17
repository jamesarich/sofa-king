package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.*
import dev.jamesrich.sofaking.backend.api.*
import dev.jamesrich.sofaking.backend.catalog.*
import dev.jamesrich.sofaking.backend.store.openStore
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlin.test.*

class LibraryAdminApiTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val token = "secret".repeat(8)
    private val admin = "curator".repeat(6)

    @Test fun `the admin API lists folders, adds, scans, renames and removes libraries`() = testApplication {
        val dir = Files.createTempDirectory("sofa-library-api-")
        val shows = Files.createDirectories(dir.resolve("media/tv/Show (2020)/Season 01"))
        Files.writeString(shows.resolve("Show - S01E01 - Pilot.mkv"), "x")
        Files.writeString(dir.resolve("sources.json"), """{"providers":[]}""")
        val config = ServerConfig("127.0.0.1", 8080, dir, dir.resolve("sources.json"), token, adminToken = admin, hdhr = HdhrConfig(enabled = false), updateImage = null)
        val store = openStore(dir)
        val runtime = LiveRuntime.create(store, config, MutableClock(), probes = false)
        try {
            application { backendModule(store, MutableClock(), runtime, token, admin, config = config) }
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/admin/libraries") { bearerAuth(token) }.status, "the API token does not open admin routes")
            val folders = json.decodeFromString<LibraryFolders>(client.get("/api/v1/admin/libraries/folders") { bearerAuth(admin) }.bodyAsText())
            val tv = dir.resolve("media/tv").toString()
            assertTrue(tv in folders.folders, "$folders")

            val bad = client.post("/api/v1/admin/libraries") { bearerAuth(admin); contentType(ContentType.Application.Json); setBody("""{"name":"TV","kind":"cartoons","path":"$tv"}""") }
            assertEquals(HttpStatusCode.BadRequest, bad.status)
            val outside = client.post("/api/v1/admin/libraries") { bearerAuth(admin); contentType(ContentType.Application.Json); setBody("""{"name":"Etc","kind":"videos","path":"/etc"}""") }
            assertEquals(HttpStatusCode.BadRequest, outside.status)

            val created = client.post("/api/v1/admin/libraries") { bearerAuth(admin); contentType(ContentType.Application.Json); setBody("""{"name":"TV","kind":"shows","path":"$tv"}""") }
            assertEquals(HttpStatusCode.Created, created.status)
            val library = json.decodeFromString<LibraryInfo>(created.bodyAsText())
            val scan = json.decodeFromString<LibraryScanInfo>(client.post("/api/v1/admin/libraries/${library.id}/scan") { bearerAuth(admin) }.bodyAsText())
            assertEquals(1, scan.files)
            val listed = json.decodeFromString<List<LibraryInfo>>(client.get("/api/v1/admin/libraries") { bearerAuth(admin) }.bodyAsText()).single()
            assertEquals(mapOf("show" to 1, "season" to 1, "episode" to 1), listed.items)
            assertEquals(true, listed.scanOk)

            val renamed = client.patch("/api/v1/admin/libraries/${library.id}") { bearerAuth(admin); contentType(ContentType.Application.Json); setBody("""{"name":"Shows"}""") }
            assertEquals("Shows", json.decodeFromString<LibraryInfo>(renamed.bodyAsText()).name)
            assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/admin/libraries/${library.id}") { bearerAuth(admin) }.status)
            assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/admin/libraries/${library.id}") { bearerAuth(admin) }.status)
        } finally { runtime.close(); store.close(); dir.toFile().deleteRecursively() }
    }
}
