package dev.jamesrich.sofaking.backend.upstream

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class UpstreamRequestTest {
    @Test fun `redirects strip secrets while retaining CDN playback headers`() = runBlocking<Unit> {
        val engine =
            MockEngine { request ->
                if (request.url.host == "provider.test") {
                    assertEquals("secret", request.headers["X-Api-Key"])
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://cdn.test/live"))
                } else {
                    assertNull(request.headers["X-Api-Key"])
                    assertNull(request.headers[HttpHeaders.Cookie])
                    assertEquals("Sofa", request.headers[HttpHeaders.UserAgent])
                    assertEquals("bytes=0-9", request.headers[HttpHeaders.Range])
                    respond("media")
                }
            }
        HttpClient(engine) { followRedirects = false }.use { http ->
            val body =
                http.withUpstreamResponse(
                    "https://provider.test/live",
                    "https://provider.test/live",
                    mapOf("X-Api-Key" to "secret", "Cookie" to "private", "User-Agent" to "Sofa"),
                    "bytes=0-9",
                ) { it.bodyAsText() }
            assertEquals("media", body)
        }
    }

    @Test fun `nested CDN resources never receive origin credentials`() = runBlocking<Unit> {
        HttpClient(
            MockEngine { request ->
                assertNull(request.headers[HttpHeaders.Authorization])
                respond("segment")
            },
        ) { followRedirects = false }.use { http ->
            assertEquals(
                "segment",
                http.withUpstreamResponse(
                    "https://cdn.test/segment",
                    "https://provider.test/live",
                    mapOf("Authorization" to "Bearer secret"),
                ) { it.bodyAsText() },
            )
        }
    }

    @Test fun `downgrades and redirect loops are rejected`() = runBlocking<Unit> {
        for (location in listOf("http://provider.test/live", "https://provider.test/live")) {
            HttpClient(
                MockEngine {
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, location))
                },
            ) {
                followRedirects = false
            }.use { http ->
                assertFailsWith<UpstreamRejectedException> {
                    http.withUpstreamResponse(
                        "https://provider.test/live",
                        "https://provider.test/live",
                        emptyMap(),
                    ) { it.bodyAsText() }
                }
            }
        }
    }

    @Test fun `loopback link-local and unspecified upstreams are refused while LAN hosts are served`() = runBlocking<Unit> {
        HttpClient(MockEngine { respond("ok") }) { followRedirects = false }.use { http ->
            suspend fun fetch(url: String, policy: UpstreamPolicy = UpstreamPolicy()) =
                http.withUpstreamResponse(url, url, emptyMap(), policy = policy) { it.bodyAsText() }
            assertEquals("ok", fetch("http://192.168.1.3:3000/api/ext/v1/dlhd/1"))
            assertEquals("ok", fetch("http://10.0.0.7/live.ts"))
            assertEquals("ok", fetch("https://provider.test/live.m3u8"))
            for (url in listOf(
                "http://127.0.0.1:8080/healthz", "http://localhost/x", "http://api.localhost/x", "http://[::1]/x", "http://169.254.169.254/latest", "http://[fe80::1]/x", "http://0.0.0.0/x", "http://[::]/x", "http://user:pw@provider.test/x", "ftp://provider.test/x",
                // Spellings the C resolver reads as loopback.
                "http://localhost./x", "http://127.0.0.1./x", "http://0x7f000001/x", "http://0177.0.0.1/x", "http://2130706433/x", "http://127.1/x",
            )) {
                assertFailsWith<UpstreamRejectedException>(url) { fetch(url) }
            }
            assertEquals("ok", fetch("http://127.0.0.1:8080/fixture", UpstreamPolicy(allowLoopback = true)))
            assertEquals("ok", fetch("http://localhost/fixture", UpstreamPolicy(allowLoopback = true)))
            assertFailsWith<UpstreamRejectedException> {
                fetch("http://169.254.1.1/x", UpstreamPolicy(allowLoopback = true))
            }
        }
    }

    @Test fun `redirects to refused addresses are rejected too`() = runBlocking<Unit> {
        HttpClient(
            MockEngine { request ->
                if (request.url.host == "provider.test") {
                    respond(
                        "",
                        HttpStatusCode.Found,
                        headersOf(HttpHeaders.Location, "http://127.0.0.1:8080/internal"),
                    )
                } else {
                    respond("leaked")
                }
            },
        ) { followRedirects = false }.use { http ->
            assertFailsWith<UpstreamRejectedException> {
                http.withUpstreamResponse("http://provider.test/live", "http://provider.test/live", emptyMap()) {
                    it.bodyAsText()
                }
            }
        }
    }
}
