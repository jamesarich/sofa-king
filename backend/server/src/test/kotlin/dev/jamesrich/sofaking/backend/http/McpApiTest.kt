package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.HdhrConfig
import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.api.ServerStatus
import dev.jamesrich.sofaking.backend.api.TokenScope
import dev.jamesrich.sofaking.backend.catalog.Fixture
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.catalog.adoptAll
import dev.jamesrich.sofaking.backend.store.openStore
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpApiTest {
    private val token = "secret".repeat(8)
    private val admin = "curator".repeat(6)

    private class Mcp(val builder: ApplicationTestBuilder, val runtime: LiveRuntime) {
        var id = 0

        suspend fun post(bearer: String?, method: String, params: JsonObject = JsonObject(emptyMap())): HttpResponse =
            builder.client.post(MCP_PATH) {
                bearer?.let { bearerAuth(it) }
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Accept, "application/json, text/event-stream")
                setBody(
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", ++id)
                        put("method", method)
                        put("params", params)
                    }.toString(),
                )
            }

        /** The JSON-RPC result of a [method] call the endpoint answered 200. */
        suspend fun result(bearer: String, method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject {
            val response = post(bearer, method, params)
            val body = response.bodyAsText()
            assertEquals(HttpStatusCode.OK, response.status, body)
            val message = Json.parseToJsonElement(body).jsonObject
            return message["result"]?.jsonObject ?: error("no result: $body")
        }

        suspend fun tools(bearer: String): List<String> =
            result(bearer, "tools/list")["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }

        /** A tool's result: whether it failed, and its text. */
        suspend fun call(
            bearer: String,
            name: String,
            arguments: JsonObject = JsonObject(emptyMap()),
        ): Pair<Boolean, String> {
            val result = result(
                bearer,
                "tools/call",
                buildJsonObject {
                    put("name", name)
                    put("arguments", arguments)
                },
            )
            val failed = result["isError"]?.jsonPrimitive?.boolean == true
            return failed to result["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
        }
    }

    private fun mcp(block: suspend Mcp.() -> Unit) = testApplication {
        val dir = Files.createTempDirectory("sofa-mcp-")
        Fixture().use { fixture ->
            val sources = dir.resolve("sources.json")
            Files.writeString(sources, Json.encodeToString(listOf(fixture.feed)))
            val config = ServerConfig(
                "0.0.0.0", 8080, dir, sources, token, allowLoopbackUpstreams = true, adminToken = admin,
                hdhr = HdhrConfig(enabled = false), revision = "abc1234",
            )
            val store = openStore(dir)
            val runtime = LiveRuntime.create(store, config, MutableClock(), probes = false)
            try {
                runtime.feeds.refresh(fixture.feed)
                runtime.adoptAll()
                application { backendModule(store, MutableClock(), runtime, token, admin, config = config) }
                Mcp(this, runtime).block()
            } finally {
                runtime.close()
                store.close()
                dir.toFile().deleteRecursively()
            }
        }
    }

    @Test fun `initialize names the server and its tools capability, in the SDK's JSON`() = mcp {
        val response = post(
            admin,
            "initialize",
            buildJsonObject {
                put("protocolVersion", "2025-06-18")
                put("capabilities", JsonObject(emptyMap()))
                put(
                    "clientInfo",
                    buildJsonObject {
                        put("name", "test")
                        put("version", "1")
                    },
                )
            },
        )
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, body)
        assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
        assertFalse(body.contains("null"), "the SDK's JSON leaves nulls out: $body")
        val result = Json.parseToJsonElement(body).jsonObject["result"]!!.jsonObject
        assertEquals("sofa-king", result["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("abc1234", result["serverInfo"]!!.jsonObject["version"]!!.jsonPrimitive.content)
        assertTrue("tools" in result["capabilities"]!!.jsonObject)
    }

    @Test fun `a read token is listed the read tools only, the admin token the actions too`() = mcp {
        val read = runtime.tokens.create("Assistant", TokenScope.READ).second
        val reads = tools(read)
        for (expected in listOf("server_status", "needs_attention", "imports", "guide_now", "recordings", "jobs")) {
            assertTrue(expected in reads, "$expected missing from $reads")
        }
        val actions = listOf("run_job", "scan_library")
        assertTrue(actions.none { it in reads }, "a read token sees no action: $reads")
        val all = tools(admin)
        assertTrue(all.containsAll(reads) && all.containsAll(actions), "$all")
        val listed = result(admin, "tools/list")["tools"]!!.jsonArray.associate {
            it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject["annotations"]!!.jsonObject
        }
        assertEquals(true, listed.getValue("server_status")["readOnlyHint"]?.jsonPrimitive?.boolean)
        assertEquals(false, listed.getValue("run_job")["readOnlyHint"]?.jsonPrimitive?.boolean)
    }

    @Test fun `a read token cannot call an action`() = mcp {
        val read = runtime.tokens.create("Assistant", TokenScope.READ).second
        val response = post(
            read,
            "tools/call",
            buildJsonObject {
                put("name", "run_job")
                put("arguments", buildJsonObject { put("job", "logos") })
            },
        )
        val message = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val refused = message["error"] != null ||
            message["result"]?.jsonObject?.get("isError")?.jsonPrimitive?.boolean == true
        assertTrue(refused, "a read token ran an action: $message")
    }

    @Test fun `the endpoint takes a read token or the admin token and no other`() = mcp {
        val read = runtime.tokens.create("Assistant", TokenScope.READ).second
        val hook = runtime.tokens.create("Sonarr", TokenScope.HOOK).second
        val device = runtime.tokens.pair("tv-1", "TV")
        for (opens in listOf(read, admin)) assertEquals(HttpStatusCode.OK, post(opens, "tools/list").status)
        for (refused in listOf(token, device, hook, "wrong", null)) {
            assertEquals(HttpStatusCode.Unauthorized, post(refused, "tools/list").status, "$refused")
        }
    }

    @Test fun `GET opens no stream`() = mcp {
        val response = builder.client.get(MCP_PATH) { bearerAuth(admin) }
        assertEquals(HttpStatusCode.MethodNotAllowed, response.status)
        assertEquals("POST", response.headers[HttpHeaders.Allow])
    }

    @Test fun `server_status answers what the admin status route does`() = mcp {
        val (failed, text) = call(admin, "server_status")
        assertFalse(failed, text)
        val status = Json.decodeFromString<ServerStatus>(text)
        assertEquals("abc1234", status.revision)
        assertEquals(runtime.lineup.entries().size, status.channels.total)
    }

    @Test fun `guide_now names what is on and what is next`() = mcp {
        val (failed, text) = call(admin, "guide_now")
        assertFalse(failed, text)
        val channels = Json.parseToJsonElement(text).jsonArray
        assertTrue(channels.isNotEmpty(), text)
        assertEquals("Morning", channels.first().field("now")?.field("title")?.jsonPrimitive?.content, text)
    }

    @Test fun `a bad argument answers the API's refusal as a tool error`() = mcp {
        val (failed, text) = call(admin, "guide_now", buildJsonObject { put("limit", 0) })
        assertTrue(failed)
        assertTrue(text.startsWith("INVALID_REQUEST: "), text)
        val (unknown, why) = call(admin, "run_job", buildJsonObject { put("job", "no-such-job") })
        assertTrue(unknown)
        assertTrue(why.startsWith("JOB_NOT_FOUND: "), why)
    }

    @Test fun `run_job starts a job the portal can start`() = mcp {
        val (failed, text) = call(admin, "run_job", buildJsonObject { put("job", "logos") })
        assertFalse(failed, text)
        assertTrue(text.contains("\"ok\":true"), text)
    }

    @Test fun `the read tools answer on a server with nothing configured beyond a provider`() = mcp {
        val read = runtime.tokens.create("Assistant", TokenScope.READ).second
        for (name in tools(read)) {
            val arguments = when (name) {
                "search_library" -> buildJsonObject { put("query", "x") }
                "on_later" -> buildJsonObject { put("kind", "movie") }
                else -> JsonObject(emptyMap())
            }
            val (failed, text) = call(read, name, arguments)
            assertFalse(failed, "$name: $text")
        }
    }

    private fun JsonElement.field(name: String): JsonElement? = (this as? JsonObject)?.get(name)
}
