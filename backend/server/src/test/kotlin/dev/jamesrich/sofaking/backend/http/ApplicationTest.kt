package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.api.*
import dev.jamesrich.sofaking.backend.store.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.ktor.util.cio.ChannelWriteException
import io.ktor.utils.io.ClosedByteChannelException
import io.ktor.utils.io.ClosedWriteChannelException
import io.ktor.utils.io.ConnectionClosedException
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import org.slf4j.event.SubstituteLoggingEvent
import org.slf4j.helpers.SubstituteLogger
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
        assertEquals("RESOURCE_NOT_FOUND", Json.decodeFromString<ApiError>(response.bodyAsText()).code)
    }
    @Test fun `a client hanging up is a debug line while other failures stay errors`() {
        assertTrue(clientAbort(ClosedByteChannelException()))
        assertTrue(clientAbort(ClosedWriteChannelException()))
        assertTrue(clientAbort(ConnectionClosedException("closed")))
        assertTrue(clientAbort(ChannelWriteException("Cannot write to a channel", java.io.IOException("Broken pipe"))))
        assertTrue(clientAbort(java.nio.channels.ClosedChannelException()))
        assertTrue(clientAbort(java.io.IOException("Connection reset by peer")))
        assertTrue(clientAbort(IllegalStateException("wrapped", ClosedByteChannelException())))
        assertFalse(clientAbort(java.io.IOException("No space left on device")))
        assertFalse(clientAbort(IllegalStateException("Store is closed")))
        assertFalse(clientAbort(NullPointerException()))

        val events = java.util.concurrent.ConcurrentLinkedQueue<SubstituteLoggingEvent>()
        testApplication {
            // The test host delivers a body writer's failure to the client channel only, so the hang-up is raised before the
            // body here; the status page sees the same exception either way. Its own fallback is told not to rethrow.
            environment {
                log = SubstituteLogger("test", events, false)
                config = MapApplicationConfig("ktor.test.throwOnException" to "false")
            }
            application {
                backendModule(unreadyStore(), Clock.systemUTC())
                routing {
                    get("/abort") { throw ClosedWriteChannelException() }
                    get("/broken") { throw IllegalStateException("broken") }
                }
            }
            runCatching { client.get("/abort").bodyAsBytes() }
            val logged = events.map { "${it.level}: ${it.message}" }
            assertTrue(events.none { it.level == Level.ERROR }, "client abort logged as $logged")
            assertTrue(events.any { it.level == Level.DEBUG && it.message.startsWith("Client closed the connection") }, "client abort logged as $logged")
            events.clear()
            val broken = client.get("/broken")
            assertEquals(HttpStatusCode.InternalServerError, broken.status)
            assertEquals("INTERNAL_ERROR", Json.decodeFromString<ApiError>(broken.bodyAsText()).code)
            assertTrue(events.any { it.level == Level.ERROR && it.message.startsWith("Request failed") })
        }
    }
    private fun unreadyStore() = object : IdentityStore {
        override suspend fun serverId(): String = error("Unavailable store must not be read")
        override suspend fun ready(): Boolean = false
    }
}
