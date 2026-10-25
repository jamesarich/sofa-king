package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.HdhrConfig
import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.catalog.MediaFixture
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.catalog.adoptAll
import dev.jamesrich.sofaking.backend.multiview.MultiviewMode
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.contract.MultiviewSession
import dev.jamesrich.sofaking.contract.OpenMultiview
import dev.jamesrich.sofaking.contract.UpdateMultiview
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Multiview end to end: two lineup channels drawn by FFmpeg into one HLS stream, and refused where it cannot be. */
class MultiviewTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val token = "secret".repeat(8)

    @Test fun `without a GPU encoder multiview is not offered and a session is refused`() = server(MultiviewMode.AUTO) {
        assertFalse(multiviewCapability(), "the capability is off")
        val refused = app.client.post("/api/v1/multiview-sessions") { body(OpenMultiview(listOf("a", "b"))) }
        assertEquals(HttpStatusCode.ServiceUnavailable, refused.status)
        assertTrue("MULTIVIEW_UNAVAILABLE" in refused.bodyAsText())
    }

    @Test fun `two channels play as one stream whose sound can move`() = MediaFixture().use { media ->
        server(MultiviewMode.ON, media) {
            assertTrue(multiviewCapability(), "software multiview is offered when switched on")
            runtime.feeds.refresh(media.feed)
            runtime.adoptAll()
            val ids = listOf("VOD", "Live").map { name -> runtime.lineup.entries().single { it.name == name }.id }
            val one = app.client.post("/api/v1/multiview-sessions") { body(OpenMultiview(ids + ids.first())) }
            assertEquals(HttpStatusCode.BadRequest, one.status, "a channel twice is refused")

            val created = app.client.post("/api/v1/multiview-sessions") { body(OpenMultiview(ids, audio = 1)) }
            assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
            val session = json.decodeFromString<MultiviewSession>(created.bodyAsText())
            assertEquals(1, session.audio)
            val playlist = app.client.get(session.descriptor.url).bodyAsText()
            val segment = playlist.lines().firstOrNull { it.endsWith(".ts") }
            assertTrue(segment != null, playlist + logs(dir))
            assertTrue(runtime.sessions.count() >= 2, "each tile holds a session")
            val url = session.descriptor.url.substringBeforeLast('/') + "/$segment"
            assertTrue(app.client.get(url).bodyAsBytes().isNotEmpty())

            val moved = app.client.patch("/api/v1/multiview-sessions/${session.id}") { body(UpdateMultiview(0)) }
            val update = json.decodeFromString<MultiviewSession>(moved.bodyAsText())
            assertEquals(0, update.audio)
            assertNotEquals(session.descriptor.url, update.descriptor.url, "the stream starts again for the player")
            assertEquals(HttpStatusCode.NotFound, app.client.get(session.descriptor.url).status)

            val gone = app.client.delete("/api/v1/multiview-sessions/${session.id}") { bearerAuth(token) }
            assertEquals(HttpStatusCode.NoContent, gone.status)
            assertEquals(HttpStatusCode.NotFound, app.client.get(update.descriptor.url).status)
        }
    }

    private class Multiview(val app: ApplicationTestBuilder, val runtime: LiveRuntime, val dir: Path)

    private suspend fun Multiview.multiviewCapability(): Boolean {
        val bootstrap = app.client.get("/api/v1/bootstrap") { bearerAuth(token) }.bodyAsText()
        return json.parseToJsonElement(bootstrap).jsonObject["capabilities"]!!.jsonObject["multiview"]!!
            .jsonPrimitive.boolean
    }

    private inline fun <reified T> io.ktor.client.request.HttpRequestBuilder.body(value: T) {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(value))
    }

    /** What the multiview FFmpeg said, for a failure's message. */
    private fun logs(dir: Path): String = Files.walk(dir.resolve("multiview")).use { paths ->
        paths.filter { it.fileName.toString() == "ffmpeg.log" }.toList().joinToString("\n") {
            "$it: ${Files.readString(it)}"
        }
    }

    /** A server with multiview in [mode] and [media]'s feed when given. */
    private fun server(mode: MultiviewMode, media: MediaFixture? = null, block: suspend Multiview.() -> Unit) =
        testApplication {
            val dir = Files.createTempDirectory("sofa-multiview-")
            val feed = media?.let { """{"id":"media","playlistUrl":"${it.feed.playlistUrl}","probe":false}""" }
            Files.writeString(dir.resolve("sources.json"), """{"providers":[${feed.orEmpty()}]}""")
            val config = ServerConfig(
                "127.0.0.1",
                8080,
                dir,
                dir.resolve("sources.json"),
                token,
                hdhr = HdhrConfig(enabled = false),
                updateImage = null,
                allowLoopbackUpstreams = media != null,
                multiview = mode,
            )
            val store = openStore(dir)
            val runtime = LiveRuntime.create(store, config, MutableClock(), probes = false)
            try {
                application { backendModule(store, MutableClock(), runtime, token, config = config) }
                Multiview(this, runtime, dir).block()
            } finally {
                runtime.close()
                store.close()
                dir.toFile().deleteRecursively()
            }
        }
}
