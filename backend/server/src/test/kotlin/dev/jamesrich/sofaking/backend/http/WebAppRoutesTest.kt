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
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebAppRoutesTest {
    private val apiToken = "api".repeat(12)

    private fun bundle(): Path = Files.createTempDirectory("sofa-web-").apply {
        resolve("index.html").writeText("<!doctype html><title>Sofa King</title>")
        resolve("sofaking.js").writeText("console.log('app')")
        resolve("038b81db9c40989b0001.wasm").writeBytes(byteArrayOf(0, 0x61, 0x73, 0x6d))
        resolve("composeResources").createDirectories().resolve("strings.cvr").writeText("x")
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
        assertTrue("script-src 'self' 'wasm-unsafe-eval'" in policy, policy)
        assertTrue("frame-ancestors 'none'" in policy, policy)
        // Coil decodes artwork in a worker from a blob: address.
        assertTrue("worker-src 'self' blob:" in policy, policy)
        assertEquals("no-cache", page.headers[HttpHeaders.CacheControl])
        assertEquals("nosniff", page.headers["X-Content-Type-Options"])

        val resource = client.get("/app/composeResources/strings.cvr")
        assertEquals(HttpStatusCode.OK, resource.status)
        assertEquals("no-cache", resource.headers[HttpHeaders.CacheControl])
    }

    @Test fun `hashed WebAssembly is served as such and cached for good`() = withWebApp(bundle()) {
        val wasm = client.get("/app/038b81db9c40989b0001.wasm")
        assertEquals(HttpStatusCode.OK, wasm.status)
        assertEquals("application/wasm", wasm.headers[HttpHeaders.ContentType])
        assertTrue(wasm.headers[HttpHeaders.CacheControl].orEmpty().contains("max-age=31536000"))
    }

    @Test fun `a file revalidates without its body`() = withWebApp(bundle()) {
        val first = client.get("/app/sofaking.js")
        val modified = first.headers[HttpHeaders.LastModified]!!
        val again = client.get("/app/sofaking.js") { header(HttpHeaders.IfModifiedSince, modified) }
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
        bundle().apply { resolve("sofaking.js.gz").writeBytes(gzip("console.log('app')")) },
    ) {
        val response = client.get("/app/sofaking.js") { header(HttpHeaders.AcceptEncoding, "gzip") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("gzip", response.headers[HttpHeaders.ContentEncoding])
        assertTrue(response.headers["Content-Security-Policy"].orEmpty().contains("wasm-unsafe-eval"))
    }

    private fun gzip(text: String): ByteArray = java.io.ByteArrayOutputStream().also { bytes ->
        java.util.zip.GZIPOutputStream(bytes).use { it.write(text.encodeToByteArray()) }
    }.toByteArray()
}
