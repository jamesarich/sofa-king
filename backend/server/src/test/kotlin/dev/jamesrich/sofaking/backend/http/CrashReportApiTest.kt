package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.HdhrConfig
import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.api.ClientCrash
import dev.jamesrich.sofaking.backend.api.TokenScope
import dev.jamesrich.sofaking.backend.catalog.Fixture
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.store.BackendStore
import dev.jamesrich.sofaking.backend.store.KEPT_CRASHES
import dev.jamesrich.sofaking.backend.store.addClientCrash
import dev.jamesrich.sofaking.backend.store.clientCrashes
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.backend.store.touchViewer
import dev.jamesrich.sofaking.contract.CrashReport
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrashReportApiTest {
    private val json = Json { encodeDefaults = true }
    private val token = "secret".repeat(8)
    private val admin = "curator".repeat(6)

    private class Crashes(val builder: ApplicationTestBuilder, val runtime: LiveRuntime, val store: BackendStore) {
        suspend fun post(bearer: String?, body: String): HttpResponse = builder.client.post(CRASH_REPORT_PATH) {
            bearer?.let { bearerAuth(it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        suspend fun listed(bearer: String, query: String = ""): HttpResponse =
            builder.client.get("/api/v1/admin/diagnostics/crashes$query") { bearerAuth(bearer) }

        suspend fun device(clientId: String, name: String): String {
            store.touchViewer(clientId, name, "android-tv", Instant.parse("2026-10-10T00:00:00Z"))
            return runtime.tokens.pair(clientId, name)
        }

        suspend fun mcpCrashes(bearer: String, limit: Int? = null): String {
            val response = builder.client.post(MCP_PATH) {
                bearerAuth(bearer)
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Accept, "application/json, text/event-stream")
                setBody(
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", 1)
                        put("method", "tools/call")
                        put(
                            "params",
                            buildJsonObject {
                                put("name", "client_crashes")
                                put("arguments", buildJsonObject { limit?.let { put("limit", it) } })
                            },
                        )
                    }.toString(),
                )
            }
            val result = Json.parseToJsonElement(response.bodyAsText()).jsonObject["result"]!!.jsonObject
            return result["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
        }
    }

    private fun crashes(block: suspend Crashes.() -> Unit) = testApplication {
        val dir = Files.createTempDirectory("sofa-crashes-")
        Fixture().use { fixture ->
            val sources = dir.resolve("sources.json")
            Files.writeString(sources, json.encodeToString(listOf(fixture.feed)))
            val clock = MutableClock()
            val config = ServerConfig(
                "127.0.0.1",
                8080,
                dir,
                sources,
                token,
                allowLoopbackUpstreams = true,
                adminToken = admin,
                hdhr = HdhrConfig(enabled = false),
            )
            val store = openStore(dir)
            val runtime = LiveRuntime.create(store, config, clock, probes = false)
            try {
                application { backendModule(store, clock, runtime, token, admin, config = config) }
                Crashes(this, runtime, store).block()
            } finally {
                runtime.close()
                store.close()
                dir.toFile().deleteRecursively()
            }
        }
    }

    private fun body(report: CrashReport) = json.encodeToString(CrashReport.serializer(), report)

    private val report = CrashReport(
        id = "crash-1",
        occurredAt = "2026-10-28T21:14:05Z",
        exception = "java.lang.IllegalStateException",
        message = "No focus target",
        frames = listOf("dev.jamesrich.sofaking.ui.guide.GuideGrid.focusRow(GuideGrid.kt:212)"),
        appVersion = "0.30.0",
        platform = "android-tv",
        osVersion = "Android 14 (API 34)",
        device = "Chromecast",
    )

    @Test fun `a paired device reports a crash and the admin lists it with the device's name`() = crashes {
        val device = device("tv-1", "Den TV")
        assertEquals(HttpStatusCode.NoContent, post(device, body(report)).status)
        val listed = json.decodeFromString<List<ClientCrash>>(listed(admin).bodyAsText()).single()
        assertEquals("tv-1", listed.clientId)
        assertEquals("Den TV", listed.clientName)
        assertEquals("java.lang.IllegalStateException", listed.exception)
        assertEquals(report.frames, listed.frames)
        assertEquals("0.30.0", listed.appVersion)
    }

    @Test fun `a report on the shared token is named by its device model`() = crashes {
        assertEquals(HttpStatusCode.NoContent, post(token, body(report)).status)
        val listed = json.decodeFromString<List<ClientCrash>>(listed(admin).bodyAsText()).single()
        assertEquals(null, listed.clientId)
        assertEquals("Chromecast", listed.clientName)
    }

    @Test fun `the server keeps every field of the crash report fixture`() = crashes {
        val dir = System.getProperty("sofaking.wireFixtures") ?: error("sofaking.wireFixtures is not set")
        val fixture = File(dir, "v1/crash-report.json").readText()
        assertEquals(HttpStatusCode.NoContent, post(token, fixture).status)
        val kept = json.decodeFromString<List<ClientCrash>>(listed(admin).bodyAsText()).single()
        val sent = Json.decodeFromString<CrashReport>(fixture)
        assertEquals(sent.id, kept.id)
        assertEquals(sent.occurredAt, kept.occurredAt)
        assertEquals(sent.exception, kept.exception)
        assertEquals(sent.message, kept.message)
        assertEquals(sent.frames, kept.frames)
        assertEquals(sent.appVersion, kept.appVersion)
        assertEquals(sent.platform, kept.platform)
        assertEquals(sent.osVersion, kept.osVersion)
        assertEquals(sent.device, kept.device)
    }

    @Test fun `a read token lists crashes but cannot report one`() = crashes {
        val read = runtime.tokens.create("Dashboard", TokenScope.READ).second
        assertEquals(HttpStatusCode.Unauthorized, post(read, body(report)).status)
        assertEquals(HttpStatusCode.NoContent, post(token, body(report)).status)
        assertEquals(HttpStatusCode.OK, listed(read).status)
    }

    @Test fun `a report without a token is refused`() = crashes {
        assertEquals(HttpStatusCode.Unauthorized, post(null, body(report)).status)
        assertEquals(HttpStatusCode.Unauthorized, post("wrong".repeat(8), body(report)).status)
        assertEquals("[]", listed(admin).bodyAsText())
    }

    @Test fun `a hook token cannot report a crash`() = crashes {
        val hook = runtime.tokens.create("Sonarr", TokenScope.HOOK).second
        assertEquals(HttpStatusCode.Unauthorized, post(hook, body(report)).status)
    }

    @Test fun `a report that names no exception is refused`() = crashes {
        assertEquals(HttpStatusCode.BadRequest, post(token, body(report.copy(exception = " "))).status)
        assertEquals(HttpStatusCode.BadRequest, post(token, "not json").status)
        assertEquals("[]", listed(admin).bodyAsText())
    }

    @Test fun `a report over the body limit is refused before it is stored`() = crashes {
        val huge = body(report.copy(frames = List(2000) { "frame$it ".repeat(10) }))
        assertTrue(huge.length > CONTROL_BODY_LIMIT)
        assertEquals(HttpStatusCode.PayloadTooLarge, post(token, huge).status)
        assertEquals("[]", listed(admin).bodyAsText())
    }

    @Test fun `a report is scrubbed again when it arrives`() = crashes {
        val dirty = report.copy(
            message = "Failed https://nas.example/live/ABCDEF0123456789ABCDEF0123456789.m3u8?token=abc " +
                "at 192.168.1.20:8080",
            frames = listOf(
                "okhttp3.Call.execute(Call.kt:1) Bearer sk_0123456789abcdef0123456789abcdef",
                "x.Y.z(Y.kt:2) http://10.0.0.5/stream",
            ),
            device = "TV mail me@example.com",
        )
        assertEquals(HttpStatusCode.NoContent, post(token, body(dirty)).status)
        val text = listed(admin).bodyAsText()
        for (leak in listOf("://", "nas.example", "192.168", "sk_0123", "ABCDEF0123", "me@example", "10.0.0.5")) {
            assertFalse(text.contains(leak), "$leak survived: $text")
        }
        assertTrue(text.contains("[url]"))
    }

    @Test fun `a report posted twice is stored once`() = crashes {
        assertEquals(HttpStatusCode.NoContent, post(token, body(report)).status)
        assertEquals(HttpStatusCode.NoContent, post(token, body(report)).status)
        assertEquals(1, store.clientCrashes(10).size)
    }

    @Test fun `the eleventh report in a minute from one address is limited`() = crashes {
        repeat(CRASH_REPORTS_PER_MINUTE) {
            assertEquals(HttpStatusCode.NoContent, post(token, body(report.copy(id = "c$it"))).status)
        }
        assertEquals(HttpStatusCode.TooManyRequests, post(token, body(report.copy(id = "late"))).status)
    }

    @Test fun `only the newest hundred crashes are kept`() = crashes {
        repeat(KEPT_CRASHES + 5) { n ->
            store.addClientCrash(
                ClientCrash(
                    id = "c%03d".format(n),
                    receivedAt = Instant.parse("2026-10-10T00:00:00Z").plusSeconds(n.toLong()).toString(),
                    exception = "X$n",
                ),
            )
        }
        val kept = store.clientCrashes(KEPT_CRASHES + 50)
        assertEquals(KEPT_CRASHES, kept.size)
        assertEquals("c104", kept.first().id)
        assertEquals("c005", kept.last().id)
    }

    @Test fun `the admin list is bounded and newest first`() = crashes {
        repeat(3) { post(token, body(report.copy(id = "c$it", exception = "E$it"))) }
        assertEquals(HttpStatusCode.BadRequest, listed(admin, "?limit=0").status)
        assertEquals(HttpStatusCode.BadRequest, listed(admin, "?limit=101").status)
        assertEquals(2, json.decodeFromString<List<ClientCrash>>(listed(admin, "?limit=2").bodyAsText()).size)
    }

    @Test fun `the MCP tool lists scrubbed crashes to a read token`() = crashes {
        val dirty = report.copy(
            message = "GET https://nas.example/x failed",
            frames = listOf("a.B.c(B.kt:1) sk_0123456789abcdef0123456789abcdef"),
        )
        assertEquals(HttpStatusCode.NoContent, post(token, body(dirty)).status)
        val read = runtime.tokens.create("Assistant", TokenScope.READ).second
        val text = mcpCrashes(read)
        assertTrue(text.contains("java.lang.IllegalStateException"), text)
        assertFalse(text.contains("://"), text)
        assertFalse(text.contains("sk_0123"), text)
        assertEquals(text, mcpCrashes(admin, limit = 1))
    }

    @Test fun `the home page counts a crash from the last day as needing attention`() = crashes {
        val (_, secret) = runtime.tokens.create("Assistant", TokenScope.READ)
        assertEquals(HttpStatusCode.NoContent, post(token, body(report)).status)
        val attention = mcpAttention(secret)
        assertTrue(attention.contains("1 app crash reported in the last day"), attention)
        assertTrue(attention.contains("java.lang.IllegalStateException"), attention)
    }

    private suspend fun Crashes.mcpAttention(bearer: String): String {
        val response = builder.client.post(MCP_PATH) {
            bearerAuth(bearer)
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            setBody(
                """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"needs_attention","arguments":{}}}""",
            )
        }
        return response.bodyAsText()
    }
}
