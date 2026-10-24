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

    @Test fun `every output names the cached logo on this server, which serves it with cache headers and no token`() =
        testApplication {
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
                    ServerConfig(
                        "127.0.0.1",
                        8080,
                        dir,
                        sources,
                        token,
                        allowLoopbackUpstreams = true,
                        outputs = outputs,
                    ),
                    clock,
                )
                try {
                    feeds.forEach { runtime.feeds.refresh(it) }
                    runtime.adoptAll()
                    application { backendModule(store, clock, runtime, token, outputs = outputs) }
                    val key = LogoCache.keyOf(fixture.logoUrl)
                    val logoPath = "/logos/$key"
                    // The import and the adoption queued a prefetch; the file appears without any request for it.
                    // The test body runs on virtual time, so the wait moves to a dispatcher whose delays are real.
                    withContext(Dispatchers.Default) {
                        pollUntil(10_000) { Files.exists(dir.resolve("logos").resolve(key)) }
                    }
                    assertEquals(1, fixture.logoRequests.get())

                    val catalogResponse = client.get("/api/v1/channels") { bearerAuth(token) }
                    val channels = Json.decodeFromString<Catalog>(catalogResponse.bodyAsText()).channels
                    assertEquals(
                        logoPath,
                        channels.single {
                            it.displayNumber == "11"
                        }.logo,
                        "the catalog's logos are server-relative",
                    )
                    assertNull(channels.single { it.displayNumber == "5" }.logo)
                    assertEquals(
                        HttpStatusCode.NotModified,
                        client.get("/api/v1/channels") {
                            bearerAuth(token)
                            header(HttpHeaders.IfNoneMatch, catalogResponse.headers[HttpHeaders.ETag]!!)
                        }.status,
                    )
                    // The body is the same whatever Host the client used, so the ETag is too.
                    assertEquals(
                        HttpStatusCode.NotModified,
                        client.get("/api/v1/channels") {
                            bearerAuth(token)
                            header(HttpHeaders.Host, "tv.lan:9193")
                            header(HttpHeaders.IfNoneMatch, catalogResponse.headers[HttpHeaders.ETag]!!)
                        }.status,
                    )
                    assertEquals(
                        logoPath,
                        Json.decodeFromString<Catalog>(
                            client.get("/api/v1/channels") {
                                bearerAuth(token)
                                header(HttpHeaders.Host, "tv.lan:9193")
                            }.bodyAsText(),
                        ).channels.single { it.displayNumber == "11" }.logo,
                    )

                    val playlist = client.get("/output/playlist.m3u?token=$output").bodyAsText()
                    assertTrue(
                        playlist.contains(" tvg-logo=\"$base$logoPath\" group-title=\"News\",Logo Station"),
                        playlist,
                    )
                    assertFalse(playlist.contains(fixture.base), playlist)
                    val guide = client.get("/output/guide.xml?token=$output").bodyAsText()
                    assertTrue(
                        guide.contains("<display-name>11</display-name><icon src=\"$base$logoPath\"/></channel>"),
                        guide,
                    )
                    assertFalse(guide.contains(fixture.base), guide)

                    val logo = client.get(logoPath)
                    assertEquals(HttpStatusCode.OK, logo.status)
                    assertEquals("image/png", logo.headers[HttpHeaders.ContentType])
                    assertEquals("public, max-age=86400", logo.headers[HttpHeaders.CacheControl])
                    assertEquals("nosniff", logo.headers["X-Content-Type-Options"])
                    assertTrue(logo.headers["Content-Security-Policy"]!!.startsWith("default-src 'none'"))
                    val etag = assertNotNull(logo.headers[HttpHeaders.ETag])
                    assertTrue(etag.matches(Regex("\"[0-9a-f]{64}\"")), etag)
                    assertContentEquals(Fixture.PNG, logo.readRawBytes())
                    assertEquals(
                        HttpStatusCode.NotModified,
                        client.get(logoPath) {
                            header(HttpHeaders.IfNoneMatch, etag)
                        }.status,
                    )
                    assertEquals(1, fixture.logoRequests.get(), "the route serves the prefetched file")

                    for (bad in listOf("/logos/${"0".repeat(64)}", "/logos/not-a-key", "/logos/${key.uppercase()}")) {
                        val missing = client.get(bad)
                        assertEquals(HttpStatusCode.NotFound, missing.status, bad)
                        assertEquals("RESOURCE_NOT_FOUND", Json.decodeFromString<ApiError>(missing.bodyAsText()).code)
                    }

                    // A changed logo URL is a new key: the outputs follow at once and the SVG is served as is.
                    val entry = channels.single { it.displayNumber == "11" }.id
                    val svgUrl = "${fixture.base}/logo.svg"
                    client.patch("/api/v1/admin/lineup/$entry") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody("""{"logo":"$svgUrl"}""")
                    }
                    val svgPath = "/logos/${LogoCache.keyOf(svgUrl)}"
                    assertEquals(
                        svgPath,
                        Json.decodeFromString<Catalog>(
                            client.get("/api/v1/channels") {
                                bearerAuth(token)
                            }.bodyAsText(),
                        ).channels.single { it.displayNumber == "11" }.logo,
                    )
                    assertTrue(
                        client.get(
                            "/output/playlist.m3u?token=$output",
                        ).bodyAsText().contains("tvg-logo=\"$base$svgPath\""),
                    )
                    val svg = client.get(svgPath)
                    assertEquals(HttpStatusCode.OK, svg.status)
                    assertEquals("image/svg+xml", svg.headers[HttpHeaders.ContentType])
                    assertEquals(Fixture.SVG, svg.bodyAsText())
                    assertTrue(svg.headers["Content-Security-Policy"]!!.contains("sandbox"))

                    // A logo whose source is not an image stays in the listing and answers 404 here.
                    val decoyUrl = "${fixture.base}/decoy.png"
                    client.patch("/api/v1/admin/lineup/$entry") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody("""{"logo":"$decoyUrl"}""")
                    }
                    val decoyPath = "/logos/${LogoCache.keyOf(decoyUrl)}"
                    assertTrue(
                        client.get(
                            "/output/guide.xml?token=$output",
                        ).bodyAsText().contains("<icon src=\"$base$decoyPath\"/>"),
                    )
                    assertEquals(HttpStatusCode.NotFound, client.get(decoyPath).status)
                    assertEquals(HttpStatusCode.OK, client.get("/output/playlist.m3u?token=$output").status)
                    // The admin view keeps the source URL the household edits.
                    assertTrue(
                        client.get("/api/v1/admin/lineup/$entry") {
                            bearerAuth(token)
                        }.bodyAsText().contains("\"logo\":\"$decoyUrl\""),
                    )
                } finally {
                    runtime.close()
                    store.close()
                    dir.toFile().deleteRecursively()
                }
            }
        }
}
