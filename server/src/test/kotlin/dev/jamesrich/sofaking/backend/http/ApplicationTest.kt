package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.api.*
import dev.jamesrich.sofaking.backend.store.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.time.*
import java.util.UUID
import kotlin.test.*

class ApplicationTest {
    @Test fun `bootstrap is typed native and truthful`() = testApplication {
        val dir = Files.createTempDirectory("sofa-api-test-")
        val store = openStore(dir)
        try {
            application { backendModule(store, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)) }
            val response = client.get("/api/v1/bootstrap")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.decodeFromString<Bootstrap>(response.bodyAsText())
            UUID.fromString(body.serverId)
            assertEquals("1970-01-01T00:00:00Z", body.serverTime)
            assertEquals(Capabilities(), body.capabilities)
            assertEquals(store.serverId(), body.serverId)
        } finally { store.close(); dir.toFile().deleteRecursively() }
    }
    @Test fun `liveness does not require ready storage`() = testApplication {
        application { backendModule(unreadyStore(), Clock.systemUTC()) }
        assertEquals(HttpStatusCode.OK, client.get("/healthz").status)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/readyz").status)
        val bootstrap = client.get("/api/v1/bootstrap")
        assertEquals(HttpStatusCode.ServiceUnavailable, bootstrap.status)
        assertEquals("NOT_READY", Json.decodeFromString<ApiError>(bootstrap.bodyAsText()).code)
    }
    @Test fun `unknown API route has structured error`() = testApplication {
        application { backendModule(unreadyStore(), Clock.systemUTC()) }
        val response = client.get("/api/v1/missing")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("NOT_FOUND", Json.decodeFromString<ApiError>(response.bodyAsText()).code)
    }
    private fun unreadyStore() = object : IdentityStore {
        override suspend fun serverId(): String = error("Unavailable store must not be read")
        override suspend fun ready(): Boolean = false
    }
}
