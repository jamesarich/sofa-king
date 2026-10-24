package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.OutputConfig
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.catalog.Fixture
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.catalog.adoptAll
import dev.jamesrich.sofaking.backend.logos.LogoCache
import dev.jamesrich.sofaking.backend.pollUntil
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.contract.ApiError
import dev.jamesrich.sofaking.contract.Catalog
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.request.patch
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.client.statement.request
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.etag
import io.ktor.http.headers
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogoRoutesTest {
    private val token = "secret".repeat(8)
    private val output = "players-" + "x".repeat(32)
    private val base = "http://localhost:80"

    @Test fun `the import fetches each logo once, before anything asks for it`() = logos {
        assertEquals(1, fixture.logoRequests.get())
    }

    @Test fun `the catalog names the cached logo on this server, whatever host the client used`() = logos {
        val response = client.get("/api/v1/channels") { bearerAuth(token) }
        val channels = Json.decodeFromString<Catalog>(response.bodyAsText()).channels
        assertEquals(logoPath, logoOf(response.bodyAsText()), "the catalog's logos are server-relative")
        assertNull(channels.single { it.displayNumber == "5" }.logo)
        val etag = response.headers[HttpHeaders.ETag]!!
        assertEquals(
            HttpStatusCode.NotModified,
            client.get("/api/v1/channels") {
                bearerAuth(token)
                header(HttpHeaders.IfNoneMatch, etag)
            }.status,
        )
        // The body is the same whatever Host the client used, so the ETag is too.
        assertEquals(
            HttpStatusCode.NotModified,
            client.get("/api/v1/channels") {
                bearerAuth(token)
                header(HttpHeaders.Host, "tv.lan:9193")
                header(HttpHeaders.IfNoneMatch, etag)
            }.status,
        )
        val elsewhere = client.get("/api/v1/channels") {
            bearerAuth(token)
            header(HttpHeaders.Host, "tv.lan:9193")
        }
        assertEquals(logoPath, logoOf(elsewhere.bodyAsText()))
    }

    @Test fun `the playlist and guide name the logo at this server, never at its source`() = logos {
        val playlist = client.get("/output/playlist.m3u?token=$output").bodyAsText()
        assertTrue(playlist.contains(" tvg-logo=\"$base$logoPath\" group-title=\"News\",Logo Station"), playlist)
        assertFalse(playlist.contains(fixture.base), playlist)
        val guide = client.get("/output/guide.xml?token=$output").bodyAsText()
        assertTrue(guide.contains("<display-name>11</display-name><icon src=\"$base$logoPath\"/></channel>"), guide)
        assertFalse(guide.contains(fixture.base), guide)
    }

    @Test fun `the cached logo is served with cache headers and no token`() = logos {
        val logo = client.get(logoPath)
        assertEquals(HttpStatusCode.OK, logo.status)
        assertEquals("image/png", logo.headers[HttpHeaders.ContentType])
        assertEquals("public, max-age=86400", logo.headers[HttpHeaders.CacheControl])
        assertEquals("nosniff", logo.headers["X-Content-Type-Options"])
        assertTrue(logo.headers["Content-Security-Policy"]!!.startsWith("default-src 'none'"))
        val etag = assertNotNull(logo.headers[HttpHeaders.ETag])
        assertTrue(etag.matches(Regex("\"[0-9a-f]{64}\"")), etag)
        assertContentEquals(Fixture.PNG, logo.readRawBytes())
        assertEquals(HttpStatusCode.NotModified, client.get(logoPath) { header(HttpHeaders.IfNoneMatch, etag) }.status)
        assertEquals(1, fixture.logoRequests.get(), "the route serves the prefetched file")
    }

    @Test fun `an unknown or malformed logo key answers 404`() = logos {
        for (bad in listOf("/logos/${"0".repeat(64)}", "/logos/not-a-key", "/logos/${key.uppercase()}")) {
            val missing = client.get(bad)
            assertEquals(HttpStatusCode.NotFound, missing.status, bad)
            assertEquals("RESOURCE_NOT_FOUND", Json.decodeFromString<ApiError>(missing.bodyAsText()).code)
        }
    }

    @Test fun `a changed logo URL is a new key the outputs follow at once, and an SVG is served as is`() = logos {
        val svgUrl = "${fixture.base}/logo.svg"
        setLogo(svgUrl)
        val svgPath = "/logos/${LogoCache.keyOf(svgUrl)}"
        assertEquals(svgPath, logoOf(client.get("/api/v1/channels") { bearerAuth(token) }.bodyAsText()))
        val playlist = client.get("/output/playlist.m3u?token=$output").bodyAsText()
        assertTrue(playlist.contains("tvg-logo=\"$base$svgPath\""), playlist)
        val svg = client.get(svgPath)
        assertEquals(HttpStatusCode.OK, svg.status)
        assertEquals("image/svg+xml", svg.headers[HttpHeaders.ContentType])
        assertEquals(Fixture.SVG, svg.bodyAsText())
        assertTrue(svg.headers["Content-Security-Policy"]!!.contains("sandbox"))
    }

    @Test fun `a logo whose source is not an image stays listed and answers 404 here`() = logos {
        val decoyUrl = "${fixture.base}/decoy.png"
        val entry = setLogo(decoyUrl)
        val decoyPath = "/logos/${LogoCache.keyOf(decoyUrl)}"
        val guide = client.get("/output/guide.xml?token=$output").bodyAsText()
        assertTrue(guide.contains("<icon src=\"$base$decoyPath\"/>"), guide)
        assertEquals(HttpStatusCode.NotFound, client.get(decoyPath).status)
        assertEquals(HttpStatusCode.OK, client.get("/output/playlist.m3u?token=$output").status)
        // The admin view keeps the source URL the household edits.
        val admin = client.get("/api/v1/admin/lineup/$entry") { bearerAuth(token) }.bodyAsText()
        assertTrue(admin.contains("\"logo\":\"$decoyUrl\""), admin)
    }

    /** The server over the fixture's feed and its logo feed, adopted, once the import has fetched the logo. */
    private inner class Logos(val app: ApplicationTestBuilder, val fixture: Fixture) {
        val client get() = app.client
        val key = LogoCache.keyOf(fixture.logoUrl)
        val logoPath = "/logos/$key"

        /** The logo of channel 11 in a catalog [body]. */
        fun logoOf(body: String) = channel11(body).logo

        fun channel11(body: String) = Json.decodeFromString<Catalog>(body).channels.single { it.displayNumber == "11" }

        /** Points channel 11's logo at [url] and returns its entry ID. */
        suspend fun setLogo(url: String): String {
            val entry = channel11(client.get("/api/v1/channels") { bearerAuth(token) }.bodyAsText()).id
            client.patch("/api/v1/admin/lineup/$entry") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody("""{"logo":"$url"}""")
            }
            return entry
        }
    }

    private fun logos(block: suspend Logos.() -> Unit) = testApplication {
        val dir = Files.createTempDirectory("sofa-logo-routes-")
        Fixture().use { fixture ->
            val feeds = listOf(fixture.feed, fixture.logoFeed)
            val sources = dir.resolve("sources.json")
            Files.writeString(sources, Json.encodeToString(feeds))
            val clock = MutableClock()
            val store = openStore(dir)
            val outputs = OutputConfig(output, 3)
            val runtime = LiveRuntime.create(
                store,
                ServerConfig("127.0.0.1", 8080, dir, sources, token, allowLoopbackUpstreams = true, outputs = outputs),
                clock,
            )
            try {
                feeds.forEach { runtime.feeds.refresh(it) }
                runtime.adoptAll()
                application { backendModule(store, clock, runtime, token, outputs = outputs) }
                val logos = Logos(this, fixture)
                // The import and the adoption queued a prefetch; the file appears without any request for it.
                // The test body runs on virtual time, so the wait moves to a dispatcher whose delays are real.
                withContext(Dispatchers.Default) {
                    pollUntil(10_000) { Files.exists(dir.resolve("logos").resolve(logos.key)) }
                }
                logos.block()
            } finally {
                runtime.close()
                store.close()
                dir.toFile().deleteRecursively()
            }
        }
    }
}
