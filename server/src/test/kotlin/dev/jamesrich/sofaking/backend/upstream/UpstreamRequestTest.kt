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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpstreamRequestTest {
    @Test fun `redirects strip secrets while retaining CDN playback headers`() =
        runBlocking<Unit> {
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

    @Test fun `nested CDN resources never receive origin credentials`() =
        runBlocking<Unit> {
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

    @Test fun `downgrades and redirect loops are rejected`() =
        runBlocking<Unit> {
            for (location in listOf("http://provider.test/live", "https://provider.test/live")) {
                HttpClient(MockEngine { respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, location)) }) {
                    followRedirects = false
                }.use { http ->
                    assertFailsWith<IllegalArgumentException> {
                        http.withUpstreamResponse(
                            "https://provider.test/live",
                            "https://provider.test/live",
                            emptyMap(),
                        ) { it.bodyAsText() }
                    }
                }
            }
        }

    @Test fun `direct probes only accept portable headers`() {
        assertTrue(probeHeadersSafe(mapOf("User-Agent" to "Sofa")))
        assertFalse(probeHeadersSafe(mapOf("Authorization" to "secret")))
        assertFalse(probeHeadersSafe(mapOf("X-Provider-Token" to "secret")))
    }
}
