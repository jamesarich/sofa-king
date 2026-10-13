package dev.jamesrich.sofaking.backend.http

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.store.openStore
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import org.slf4j.LoggerFactory
import kotlin.test.*

class AccessLogTest {
    private val apiToken = "api".repeat(12)

    @Test fun `each request gets an id in X-Request-Id and one access line with it, without the query or media paths`() = testApplication {
        val dir = Files.createTempDirectory("sofa-access-")
        val store = openStore(dir)
        val logger = (LoggerFactory.getILoggerFactory() as LoggerContext).getLogger("dev.jamesrich.sofaking.backend.http.Access")
        val events = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(events)
        try {
            application { backendModule(store, MutableClock(), apiToken = apiToken) }
            val first = client.get("/api/v1/bootstrap?token=$apiToken") { bearerAuth(apiToken) }
            val second = client.get("/api/v1/bootstrap") { bearerAuth(apiToken) }
            val firstId = assertNotNull(first.headers[HttpHeaders.XRequestId])
            assertTrue(firstId.matches(Regex("[0-9a-f]{16}")), firstId)
            assertNotEquals(firstId, second.headers[HttpHeaders.XRequestId])
            for (path in listOf("/media/sessions/s/1/t", "/logos/abc", "/output/stream/x.ts", "/auto/v1", "/healthz", "/readyz", "//media/sessions/s/1/t")) {
                assertNotNull(client.get { url { encodedPath = path } }.headers[HttpHeaders.XRequestId], path)
            }

            val lines = events.list.toList()
            assertEquals(2, lines.size, lines.joinToString("\n") { it.formattedMessage })
            assertTrue(lines[0].formattedMessage.matches(Regex("request method=GET path=/api/v1/bootstrap status=200 durationMs=\\d+")), lines[0].formattedMessage)
            assertEquals(firstId, lines[0].mdcPropertyMap["requestId"])
            assertTrue(lines.none { "token" in it.formattedMessage || apiToken in it.formattedMessage })
        } finally {
            logger.detachAppender(events)
            store.close(); dir.toFile().deleteRecursively()
        }
    }
}
