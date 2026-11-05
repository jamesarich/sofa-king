package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.store.openStore
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebAppRoutesTest {
    private val apiToken = "api".repeat(12)

    private fun bundle(): Path = Files.createTempDirectory("sofa-web-").apply {
        resolve("index.html").writeText("<!doctype html><title>Sofa King</title>")
        resolve("app-3FJ2K9QX.js").writeText("console.log('app')")
        resolve("sw.js").writeText("self.skipWaiting()")
    }

    private fun withWebApp(web: Path?, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        val dir = Files.createTempDirectory("sofa-web-data-")
        val store = openStore(dir)
        try {
            val config = ServerConfig("127.0.0.1", 8080, dir, apiToken = apiToken, webDirectory = web)
            application { backendModule(store, MutableClock(), apiToken = apiToken, config = config) }
            client = createClient { followRedirects = false }
            block()
        } finally {
            store.close()
        }
    }

    @Test fun `the root and the bare path open the web app`() = withWebApp(bundle()) {
        for (path in listOf("/", "/app")) {
            val response = client.get(path)
            assertEquals(HttpStatusCode.Found, response.status, path)
            assertEquals("/app/", response.headers[HttpHeaders.Location], path)
        }
    }

    @Test fun `the page takes no token and carries its own policy`() = withWebApp(bundle()) {
        val page = client.get("/app/")
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue(page.bodyAsText().contains("<title>Sofa King</title>"))
        val policy = page.headers["Content-Security-Policy"].orEmpty()
        assertTrue("script-src 'self';" in policy, policy)
        assertTrue("style-src 'self';" in policy, policy)
        assertTrue("frame-ancestors 'none'" in policy, policy)
        // hls.js starts its worker from a blob: address and its caption tracks have the empty `data:,WEBVTT` source.
        assertTrue("media-src 'self' blob: data:;" in policy, policy)
        assertTrue("worker-src 'self' blob:" in policy, policy)
        assertEquals("no-cache", page.headers[HttpHeaders.CacheControl])
        assertEquals("nosniff", page.headers["X-Content-Type-Options"])
    }

    @Test fun `hashed files are cached for good and the rest revalidate`() = withWebApp(bundle()) {
        val script = client.get("/app/app-3FJ2K9QX.js")
        assertEquals(HttpStatusCode.OK, script.status)
        assertTrue(script.headers[HttpHeaders.CacheControl].orEmpty().contains("max-age=31536000"))
        // The service worker keeps its name, so it revalidates.
        assertEquals("no-cache", client.get("/app/sw.js").headers[HttpHeaders.CacheControl])
    }

    @Test fun `a file revalidates without its body`() = withWebApp(bundle()) {
        val first = client.get("/app/app-3FJ2K9QX.js")
        val modified = first.headers[HttpHeaders.LastModified]!!
        val again = client.get("/app/app-3FJ2K9QX.js") { header(HttpHeaders.IfModifiedSince, modified) }
        assertEquals(HttpStatusCode.NotModified, again.status)
    }

    @Test fun `nothing outside the bundle is served`() = withWebApp(bundle()) {
        assertEquals(HttpStatusCode.NotFound, client.get("/app/missing.js").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/app/%2e%2e/%2e%2e/etc/passwd").status)
    }

    @Test fun `without a bundle there is no web app`() = withWebApp(null) {
        assertEquals(HttpStatusCode.NotFound, client.get("/app/").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/").status)
    }

    @Test fun `an empty folder serves no web app`() = withWebApp(Files.createTempDirectory("sofa-web-empty-")) {
        assertEquals(HttpStatusCode.NotFound, client.get("/app/").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/").status)
    }

    @Test fun `a browser that takes gzip gets the precompressed file`() = withWebApp(
        bundle().apply { resolve("app-3FJ2K9QX.js.gz").writeBytes(gzip("console.log('app')")) },
    ) {
        val response = client.get("/app/app-3FJ2K9QX.js") { header(HttpHeaders.AcceptEncoding, "gzip") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("gzip", response.headers[HttpHeaders.ContentEncoding])
        assertTrue(response.headers["Content-Security-Policy"].orEmpty().contains("script-src 'self';"))
    }

    private fun gzip(text: String): ByteArray = java.io.ByteArrayOutputStream().also { bytes ->
        java.util.zip.GZIPOutputStream(bytes).use { it.write(text.encodeToByteArray()) }
    }.toByteArray()
}
