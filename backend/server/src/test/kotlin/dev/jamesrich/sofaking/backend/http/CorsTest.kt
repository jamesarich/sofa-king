package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.CorsOrigin
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.store.openStore
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.request.options
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.cookies
import io.ktor.http.headers
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CorsTest {
    private val apiToken = "api".repeat(12)

    @Test fun `SOFA_CORS_ORIGINS takes exact http and https origins`() {
        assertEquals(
            listOf(CorsOrigin("https", "tv.example.com"), CorsOrigin("http", "lan.local:8080")),
            ServerConfig.corsOrigins(" https://TV.example.com , http://lan.local:8080,https://tv.example.com "),
        )
        assertEquals(emptyList(), ServerConfig.corsOrigins(null))
        assertEquals(emptyList(), ServerConfig.corsOrigins(" , "))
        for (bad in listOf(
            "*",
            "tv.example.com",
            "https://tv.example.com/app",
            "https://tv.example.com?x=1",
            "ftp://tv.example.com",
            "https://user@tv.example.com",
        )) {
            val failure = assertFailsWith<IllegalArgumentException>(bad) { ServerConfig.corsOrigins(bad) }
            assertTrue(failure.message!!.startsWith("SOFA_CORS_ORIGINS"), failure.message)
        }
    }

    @Test
    fun `a listed origin may call the API, preflights pass before auth, and others get no grant`() = testApplication {
        val dir = Files.createTempDirectory("sofa-cors-")
        val store = openStore(dir)
        try {
            val config =
                ServerConfig(
                    "127.0.0.1",
                    8080,
                    dir,
                    apiToken = apiToken,
                    corsOrigins = ServerConfig.corsOrigins("https://tv.example.com"),
                )
            application { backendModule(store, MutableClock(), apiToken = apiToken, config = config) }

            val preflight = client.options("/api/v1/bootstrap") {
                header(HttpHeaders.Origin, "https://tv.example.com")
                header(HttpHeaders.AccessControlRequestMethod, "GET")
                header(HttpHeaders.AccessControlRequestHeaders, "authorization")
            }
            assertEquals(HttpStatusCode.OK, preflight.status, "a preflight carries no token and must not be refused")
            assertEquals("https://tv.example.com", preflight.headers[HttpHeaders.AccessControlAllowOrigin])
            assertTrue(
                preflight.headers[HttpHeaders.AccessControlAllowHeaders].orEmpty().contains(
                    "Authorization",
                    ignoreCase = true,
                ),
            )

            val call = client.get("/api/v1/bootstrap") {
                bearerAuth(apiToken)
                header(HttpHeaders.Origin, "https://tv.example.com")
            }
            assertEquals(HttpStatusCode.OK, call.status)
            assertEquals("https://tv.example.com", call.headers[HttpHeaders.AccessControlAllowOrigin])
            assertTrue(
                call.headers[HttpHeaders.AccessControlExposeHeaders].orEmpty().contains("ETag", ignoreCase = true),
            )
            assertNull(call.headers[HttpHeaders.AccessControlAllowCredentials], "bearer tokens, not cookies")

            val other = client.get("/api/v1/bootstrap") {
                bearerAuth(apiToken)
                header(HttpHeaders.Origin, "https://evil.example")
            }
            assertNull(other.headers[HttpHeaders.AccessControlAllowOrigin])
        } finally {
            store.close()
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun `without SOFA_CORS_ORIGINS no response carries a CORS grant`() = testApplication {
        val dir = Files.createTempDirectory("sofa-cors-off-")
        val store = openStore(dir)
        try {
            application {
                backendModule(
                    store,
                    MutableClock(),
                    apiToken = apiToken,
                    config = ServerConfig("127.0.0.1", 8080, dir, apiToken = apiToken),
                )
            }
            val call = client.get("/api/v1/bootstrap") {
                bearerAuth(apiToken)
                header(HttpHeaders.Origin, "https://tv.example.com")
            }
            assertEquals(HttpStatusCode.OK, call.status)
            assertNull(call.headers[HttpHeaders.AccessControlAllowOrigin])
        } finally {
            store.close()
            dir.toFile().deleteRecursively()
        }
    }
}
