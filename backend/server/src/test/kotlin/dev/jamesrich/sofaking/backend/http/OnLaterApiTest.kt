package dev.jamesrich.sofaking.backend.http

import com.sun.net.httpserver.HttpServer
import dev.jamesrich.sofaking.backend.HdhrConfig
import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.catalog.MediaFixture
import dev.jamesrich.sofaking.backend.catalog.adoptAll
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.contract.ApiError
import dev.jamesrich.sofaking.contract.UpcomingPrograms
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/** On Later over HTTP: each kind picks its programs from the guide, soonest first, within the days asked for. */
class OnLaterApiTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val token = "secret".repeat(8)
    private val xmltvTime = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").withZone(ZoneOffset.UTC)

    /** One XMLTV program [hours] from now, an hour long, with [inner] elements after its title. */
    private fun program(start: Instant, hours: Long, title: String, inner: String = ""): String {
        val from = start.plusSeconds(hours * 3600)
        return "<programme channel=\"live\" start=\"${xmltvTime.format(from)}\" " +
            "stop=\"${xmltvTime.format(from.plusSeconds(3600))}\"><title>$title</title>$inner</programme>"
    }

    /** The live fixture channel with a guide of movies, sports, kids' programs and a premiere, behind the token. */
    private suspend fun ApplicationTestBuilder.onLaterApi(block: suspend ApplicationTestBuilder.() -> Unit) {
        val dir = Files.createTempDirectory("sofa-on-later-api-")
        val start = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val programs = listOf(
            program(start, 1, "Late Movie", "<category>Movie</category><category>Drama</category>"),
            program(start, 2, "Feed Movie", "<category>Movie</category><category>News</category>"),
            program(start, 3, "Big Match", "<category>Sports event</category>"),
            program(start, 4, "Cartoon Hour", "<category>Children</category>"),
            program(
                start,
                5,
                "Fresh Show",
                "<episode-num system=\"xmltv_ns\">1.0.</episode-num><new />",
            ),
            program(start, 6, "Old Show", "<episode-num system=\"xmltv_ns\">1.4.</episode-num><new />"),
            program(start, 30, "Tomorrow Movie", "<category>Movies</category>"),
        )
        val body = (
            "<?xml version=\"1.0\"?><tv><channel id=\"live\"><display-name>Live</display-name></channel>" +
                programs.joinToString("") + "</tv>"
            ).toByteArray()
        val guide = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/guide.xml") { exchange ->
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        MediaFixture().use { media ->
            val feed = media.feed.copy(guideUrl = "http://127.0.0.1:${guide.address.port}/guide.xml")
            val sources = dir.resolve("sources.json")
            Files.writeString(sources, Json.encodeToString(listOf(feed)))
            val config = ServerConfig(
                "127.0.0.1", 8080, dir, sources, token, allowLoopbackUpstreams = true,
                hdhr = HdhrConfig(enabled = false), updateImage = null, recordingsFreeGib = 0, comskip = null,
            )
            val store = openStore(dir)
            val runtime = LiveRuntime.create(store, config, Clock.systemUTC(), probes = false)
            try {
                runtime.feeds.refresh(feed)
                runtime.adoptAll()
                application { backendModule(store, Clock.systemUTC(), runtime, token, config = config) }
                block()
            } finally {
                runtime.close()
                store.close()
                guide.stop(0)
            }
        }
        dir.toFile().deleteRecursively()
    }

    private suspend fun ApplicationTestBuilder.titles(query: String): List<String> {
        val response = client.get("/api/v1/guide/on-later?$query") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return json.decodeFromString<UpcomingPrograms>(response.bodyAsText()).programs.map { it.title }
    }

    @Test fun `each kind lists its programs soonest first within the days asked for`() = testApplication {
        onLaterApi {
            assertEquals(listOf("Late Movie"), titles("kind=movie&days=1"))
            assertEquals(listOf("Late Movie", "Tomorrow Movie"), titles("kind=movie&days=2"))
            assertEquals(listOf("Late Movie"), titles("kind=movie&days=2&limit=1"))
            assertEquals(listOf("Big Match"), titles("kind=sports"))
            assertEquals(listOf("Cartoon Hour"), titles("kind=kids"))
            assertEquals(listOf("Fresh Show"), titles("kind=new"))
        }
    }

    @Test fun `an unknown kind or days out of range answers 400`() = testApplication {
        onLaterApi {
            for (query in listOf("kind=shows", "", "kind=movie&days=8", "kind=movie&days=x", "kind=movie&limit=0")) {
                val response = client.get("/api/v1/guide/on-later?$query") { bearerAuth(token) }
                assertEquals(HttpStatusCode.BadRequest, response.status, query)
                assertEquals("INVALID_REQUEST", json.decodeFromString<ApiError>(response.bodyAsText()).code)
            }
        }
    }
}
