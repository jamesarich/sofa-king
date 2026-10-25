package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.HdhrConfig
import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.api.LibraryInfo
import dev.jamesrich.sofaking.backend.api.ScanQueued
import dev.jamesrich.sofaking.backend.api.TokenScope
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.library.PathMapping
import dev.jamesrich.sofaking.backend.store.openStore
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.basicAuth
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class HookApiTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val token = "secret".repeat(8)
    private val admin = "curator".repeat(6)
    private val sonarrDownload = javaClass.getResource("/hooks/sonarr-download.json")!!.readText()

    @Test fun `a Sonarr download queues a scan of the library holding its series, behind a hook token`() = hooks {
        assertEquals(listOf(library.id), queued(servarr(sonarrDownload) { bearerAuth(hook) }))
        assertEquals(listOf(library.id), queued(servarr(sonarrDownload) { basicAuth("sonarr", hook) }))
        assertEquals(HttpStatusCode.Unauthorized, servarr(sonarrDownload).status)
        assertEquals(HttpStatusCode.Unauthorized, servarr(sonarrDownload) { bearerAuth(device) }.status)
        assertEquals(HttpStatusCode.Unauthorized, servarr(sonarrDownload) { bearerAuth(admin) }.status)
        assertEquals(listOf(library.id), queued(servarr(sonarrDownload) { bearerAuth(token) }), "the API token too")
    }

    @Test fun `a test, a grab or a folder no library holds queues nothing`() = hooks {
        assertEquals(emptyList<String>(), queued(servarr("""{"eventType":"Test"}""") { bearerAuth(hook) }))
        assertEquals(
            emptyList<String>(),
            queued(servarr(sonarrDownload.replace("\"Download\"", "\"Grab\"")) { bearerAuth(hook) }),
        )
        val movie = """{"eventType":"Download","movie":{"folderPath":"/data/media/movies/Alien (1979)"}}"""
        assertEquals(emptyList<String>(), queued(servarr(movie) { bearerAuth(hook) }))
    }

    @Test fun `the Emby and Jellyfin connection's refresh queues the same scan with its own token header`() = hooks {
        val file = "/data/media/tv/Show (2020)/Season 01/Show - S01E02.mkv"
        val updates = """{"Updates":[{"Path":"$file","UpdateType":"Created"}]}"""
        for (path in listOf("/Library/Media/Updated", "/mediabrowser/Library/Media/Updated")) {
            val response = post(path, updates) { header("X-MediaBrowser-Token", hook) }
            assertEquals(listOf(library.id), queued(response), path)
            val refused = post(path, updates) { header("X-MediaBrowser-Token", device) }
            assertEquals(HttpStatusCode.Unauthorized, refused.status, "a device token on $path")
        }
        assertEquals(
            HttpStatusCode.NoContent,
            post("/Notifications/Admin", """{"Name":"Test from Sonarr"}""") { header("X-Emby-Token", hook) }.status,
        )
        assertEquals(HttpStatusCode.Unauthorized, post("/Notifications/Admin", "{}").status)
    }

    @Test fun `the connection's test and item lookup answer as an empty Emby server would`() = hooks {
        // Sonarr sends both headers.
        val sonarr: HttpRequestBuilder.() -> Unit = {
            header("X-MediaBrowser-Token", hook)
            header(HttpHeaders.Authorization, "MediaBrowser Token=\"$hook\"")
        }
        val test = app.client.get("/System/Configuration", sonarr)
        assertEquals(HttpStatusCode.OK, test.status)
        assertEquals("{}", test.bodyAsText())
        val items = app.client.get("/Items?recursive=true&includeItemTypes=Series&fields=Path,ProviderIds", sonarr)
        assertEquals("""{"Items":[],"TotalRecordCount":0}""", items.bodyAsText())
        assertEquals(HttpStatusCode.Unauthorized, app.client.get("/System/Configuration").status)
    }

    @Test fun `the admin API scans the library holding a path and answers 404 for one no library holds`() = hooks {
        val tv = dir.resolve("media/tv/Show (2020)").toString()
        assertEquals(
            listOf(library.id),
            queued(post("/api/v1/admin/libraries/scan", """{"path":"$tv"}""") { bearerAuth(admin) }),
        )
        assertEquals(
            HttpStatusCode.NotFound,
            post("/api/v1/admin/libraries/scan", """{"path":"/srv/elsewhere"}""") { bearerAuth(admin) }.status,
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            post("/api/v1/admin/libraries/scan", """{"path":"$tv"}""") { bearerAuth(hook) }.status,
        )
    }

    /** A server with a shows library over `media/tv`, which Sonarr sees as `/data/media/tv`, and a hook token. */
    private inner class Hooks(
        val app: ApplicationTestBuilder,
        val dir: Path,
        val library: LibraryInfo,
        val hook: String,
        val device: String,
    ) {
        suspend fun post(path: String, body: String, auth: HttpRequestBuilder.() -> Unit = {}): HttpResponse =
            app.client.post(path) {
                auth()
                contentType(ContentType.Application.Json)
                setBody(body)
            }

        suspend fun servarr(body: String, auth: HttpRequestBuilder.() -> Unit = {}) =
            post("/api/v1/hooks/servarr", body, auth)

        suspend fun queued(response: HttpResponse): List<String> {
            assertEquals(HttpStatusCode.Accepted, response.status, response.bodyAsText())
            return json.decodeFromString<ScanQueued>(response.bodyAsText()).libraries
        }
    }

    private fun hooks(block: suspend Hooks.() -> Unit) = testApplication {
        val dir = Files.createTempDirectory("sofa-hooks-").toRealPath()
        val tv = Files.createDirectories(dir.resolve("media/tv/Show (2020)/Season 01"))
        Files.writeString(tv.resolve("Show - S01E01 - Pilot.mkv"), "x")
        Files.writeString(dir.resolve("sources.json"), """{"providers":[]}""")
        val config =
            ServerConfig(
                "127.0.0.1",
                8080,
                dir,
                dir.resolve("sources.json"),
                token,
                adminToken = admin,
                hdhr = HdhrConfig(enabled = false),
                updateImage = null,
                libraryPathMap = listOf(PathMapping("/data/media/tv", dir.resolve("media/tv"))),
            )
        val store = openStore(dir)
        val runtime = LiveRuntime.create(store, config, MutableClock(), probes = false)
        try {
            application { backendModule(store, MutableClock(), runtime, token, admin, config = config) }
            val created = client.post("/api/v1/admin/libraries") {
                bearerAuth(admin)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"TV","kind":"shows","path":"${dir.resolve("media/tv")}"}""")
            }
            assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
            val hook = runtime.tokens.create("Sonarr", TokenScope.HOOK).second
            val device = runtime.tokens.pair("tv-1", "TV")
            Hooks(this, dir, json.decodeFromString(created.bodyAsText()), hook, device).block()
        } finally {
            runtime.close()
            store.close()
            dir.toFile().deleteRecursively()
        }
    }
}
