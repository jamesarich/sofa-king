package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.SecretOrigin
import dev.jamesrich.sofaking.backend.SecretsFile
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.library.FanartFailure
import dev.jamesrich.sofaking.backend.library.ListenBrainzFailure
import dev.jamesrich.sofaking.backend.setup.SetupService
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecretRoutesTest {
    private val good = "g".repeat(40)

    private class Case(val path: String, val field: String, val key: String)

    private val fanart = Case("/api/v1/admin/fanart-key", "key", SecretsFile.FANART_KEY)
    private val listenBrainz = Case("/api/v1/admin/listenbrainz-token", "token", SecretsFile.LISTENBRAINZ_TOKEN)

    private fun config(dir: Path, origins: Map<String, SecretOrigin> = emptyMap()) =
        ServerConfig("127.0.0.1", 8080, dir, null, "t".repeat(40), secretOrigins = origins)

    private fun service(dir: Path, origins: Map<String, SecretOrigin>, restart: () -> Unit) = SetupService(
        config(dir, origins),
        restart,
        checkFanart = { if (it != good) throw FanartFailure("FANART_AUTH") },
        checkListenBrainz = { if (it != good) throw ListenBrainzFailure("LISTENBRAINZ_AUTH") },
    )

    private fun routes(case: Case, origins: Map<String, SecretOrigin> = emptyMap(), block: suspend Routes.() -> Unit) =
        testApplication {
            val dir = Files.createTempDirectory("sofa-secret-routes-")
            val routes = Routes(dir)
            try {
                application {
                    installLimits()
                    installStatusPages()
                    routing { route("/api/v1/admin") { setupRoutes(service(dir, origins) { routes.restarts++ }) } }
                }
                routes.client = client
                routes.case = case
                routes.block()
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

    private class Routes(val dir: Path) {
        lateinit var client: io.ktor.client.HttpClient
        lateinit var case: Case
        var restarts = 0

        suspend fun put(value: String) = client.put(case.path) {
            contentType(ContentType.Application.Json)
            setBody("""{"${case.field}":"$value"}""")
        }
    }

    private fun saved(routes: Routes) = SecretsFile(routes.dir.resolve(SecretsFile.NAME)).stored(routes.case.key)

    @Test fun `a fanart key fanart tv takes is stored and the server restarts`() = routes(fanart) {
        val answer = put(good)
        assertEquals(HttpStatusCode.OK, answer.status, answer.bodyAsText())
        assertFalse(good in answer.bodyAsText(), "the key is never echoed")
        assertEquals(good, saved(this))
        assertEquals(1, restarts)
    }

    @Test fun `a fanart key from the environment is a conflict and nothing is written`() =
        routes(fanart, mapOf("SOFA_FANART_KEY" to SecretOrigin.ENVIRONMENT)) {
            val answer = put(good)
            assertEquals(HttpStatusCode.Conflict, answer.status)
            assertTrue("FANART_FROM_ENVIRONMENT" in answer.bodyAsText())
            assertFalse(Files.exists(dir.resolve(SecretsFile.NAME)))
            assertEquals(0, restarts)
        }

    @Test fun `a fanart key fanart tv refuses is rejected and nothing is written`() = routes(fanart) {
        val answer = put("r".repeat(40))
        assertEquals(HttpStatusCode.BadRequest, answer.status)
        assertTrue("FANART_REJECTED" in answer.bodyAsText())
        assertFalse("r".repeat(40) in answer.bodyAsText())
        assertFalse(Files.exists(dir.resolve(SecretsFile.NAME)))
        assertEquals(0, restarts)
    }

    @Test fun `a ListenBrainz token ListenBrainz takes is stored and the server restarts`() = routes(listenBrainz) {
        val answer = put(good)
        assertEquals(HttpStatusCode.OK, answer.status, answer.bodyAsText())
        assertFalse(good in answer.bodyAsText(), "the token is never echoed")
        assertEquals(good, saved(this))
        assertEquals(1, restarts)
    }

    @Test fun `a ListenBrainz token from the environment is a conflict and nothing is written`() =
        routes(listenBrainz, mapOf("SOFA_LISTENBRAINZ_TOKEN" to SecretOrigin.ENVIRONMENT)) {
            val answer = put(good)
            assertEquals(HttpStatusCode.Conflict, answer.status)
            assertTrue("LISTENBRAINZ_FROM_ENVIRONMENT" in answer.bodyAsText())
            assertFalse(Files.exists(dir.resolve(SecretsFile.NAME)))
            assertEquals(0, restarts)
        }

    @Test fun `a ListenBrainz token ListenBrainz refuses is rejected and nothing is written`() = routes(listenBrainz) {
        val answer = put("r".repeat(40))
        assertEquals(HttpStatusCode.BadRequest, answer.status)
        assertTrue("LISTENBRAINZ_REJECTED" in answer.bodyAsText())
        assertFalse("r".repeat(40) in answer.bodyAsText())
        assertFalse(Files.exists(dir.resolve(SecretsFile.NAME)))
        assertEquals(0, restarts)
    }
}
