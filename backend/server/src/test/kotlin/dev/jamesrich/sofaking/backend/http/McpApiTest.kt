package dev.jamesrich.sofaking.backend.http

import dev.jamesrich.sofaking.backend.HdhrConfig
import dev.jamesrich.sofaking.backend.LiveRuntime
import dev.jamesrich.sofaking.backend.ServerConfig
import dev.jamesrich.sofaking.backend.api.ServerStatus
import dev.jamesrich.sofaking.backend.api.TokenScope
import dev.jamesrich.sofaking.backend.catalog.Fixture
import dev.jamesrich.sofaking.backend.catalog.MutableClock
import dev.jamesrich.sofaking.backend.catalog.adoptAll
import dev.jamesrich.sofaking.backend.health.FailureClass
import dev.jamesrich.sofaking.backend.health.Observation
import dev.jamesrich.sofaking.backend.store.openStore
import dev.jamesrich.sofaking.backend.store.recordObservation
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    private val policyText = """{"providers":[{"id":"alt"},{"id":"fixture"}],"health":{"requireVerified":false},""" +
        """"numbering":{"floor":100},"stations":{"station":{"group":"Local"}}}"""

    /** A server with the fixture provider, or with a lineup policy over the fixture and alt providers. */
    private fun mcp(withPolicy: Boolean = false, block: suspend Mcp.() -> Unit) = testApplication {
        val dir = Files.createTempDirectory("sofa-mcp-")
        Fixture().use { fixture ->
            val sources = dir.resolve("sources.json")
            val feeds = if (withPolicy) listOf(fixture.feed, fixture.altFeed) else listOf(fixture.feed)
            Files.writeString(
                sources,
                if (withPolicy) {
                    """{"providers":${Json.encodeToString(
                        feeds,
                    )},"guides":[]}"""
                } else {
                    Json.encodeToString(feeds)
                },
            )
            val policyFile = dir.resolve("policy.json").takeIf {
                withPolicy
            }?.also { Files.writeString(it, policyText) }
            val config = ServerConfig(
                "0.0.0.0", 8080, dir, sources, token, allowLoopbackUpstreams = true, adminToken = admin,
                hdhr = HdhrConfig(enabled = false), revision = "abc1234", policyFile = policyFile,
            )
            val store = openStore(dir)
            val runtime = LiveRuntime.create(
                store,
                config,
                MutableClock(),
                policyDebounceMillis = 3_600_000,
                probes = false,
            )
            try {
                for (feed in feeds) runtime.feeds.refresh(feed)
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
        val page = Json.parseToJsonElement(text)
        val channels = page.field("channels")!!.jsonArray
        assertTrue(channels.isNotEmpty(), text)
        assertEquals("Morning", channels.first().field("now")?.field("title")?.jsonPrimitive?.content, text)
        assertEquals(channels.size, page.field("total")!!.jsonPrimitive.int)
        assertNull(page.field("nextOffset"), "one page holds them all: $text")
    }

    @Test fun `guide_now pages with offset and says where the next page starts`() = mcp(withPolicy = true) {
        val (_, whole) = call(admin, "guide_now")
        val total = Json.parseToJsonElement(whole).field("total")!!.jsonPrimitive.int
        val (failed, text) = call(admin, "guide_now", buildJsonObject { put("limit", 1) })
        assertFalse(failed, text)
        val page = Json.parseToJsonElement(text)
        assertEquals(1, page.field("channels")!!.jsonArray.size, text)
        if (total > 1) assertEquals(1, page.field("nextOffset")!!.jsonPrimitive.int, text)
    }

    @Test fun `a bad argument answers the API's refusal as a tool error`() = mcp {
        val (failed, text) = call(admin, "guide_now", buildJsonObject { put("limit", 0) })
        assertTrue(failed)
        assertTrue(text.startsWith("INVALID_REQUEST: "), text)
        val (unknown, why) = call(admin, "run_job", buildJsonObject { put("job", "no-such-job") })
        assertTrue(unknown)
        assertTrue(why.startsWith("JOB_NOT_FOUND: "), why)
        assertTrue(why.contains("call jobs"), "the error names the next step: $why")
        val (noLibrary, library) = call(admin, "scan_library", buildJsonObject { put("library", "no-such-library") })
        assertTrue(noLibrary)
        assertTrue(library.startsWith("LIBRARY_NOT_FOUND: ") && library.contains("call libraries"), library)
    }

    @Test fun `run_job starts a job the portal can start`() = mcp {
        val (failed, text) = call(admin, "run_job", buildJsonObject { put("job", "logos") })
        assertFalse(failed, text)
        assertTrue(text.contains("\"ok\":true"), text)
    }

    @Test fun `the read tools answer on a server with nothing configured beyond a provider`() = mcp {
        val read = runtime.tokens.create("Assistant", TokenScope.READ).second
        for (name in tools(read)) {
            val (failed, text) = call(read, name, argumentsFor(name))
            if (name in POLICY_TOOLS) {
                assertTrue(failed && text.startsWith("POLICY_NOT_CONFIGURED: "), "$name: $text")
            } else {
                assertFalse(failed, "$name: $text")
            }
        }
    }

    @Test fun `the policy tools answer from the policy when one is configured`() = mcp(withPolicy = true) {
        val read = runtime.tokens.create("Assistant", TokenScope.READ).second
        for (name in POLICY_TOOLS) {
            val (failed, text) = call(read, name, argumentsFor(name))
            assertFalse(failed, "$name: $text")
        }
        val (_, explained) = call(read, "explain_station", buildJsonObject { put("key", "station") })
        val explanation = Json.parseToJsonElement(explained)
        assertEquals("Local", explanation.field("station")?.field("group")?.jsonPrimitive?.content, explained)
        assertEquals(2, explanation.field("candidates")!!.jsonArray.size, explained)
        val (_, preview) = call(read, "preview_policy")
        assertEquals(1, Json.parseToJsonElement(preview).field("merges")?.field("total")?.jsonPrimitive?.int, preview)
    }

    @Test fun `source_problems leaves out healthy sources and takes the state names`() = mcp(withPolicy = true) {
        val sources = runtime.feeds.sources()
        assertEquals(2, sources.size)
        val healthy = sources.first()
        val now = Instant.parse("2026-10-10T13:00:00Z")
        runtime.store.recordObservation(healthy.id, Observation("ok", now, true), now)
        assertEquals("HEALTHY", runtime.feeds.sources().first { it.id == healthy.id }.status)

        val (failed, text) = call(admin, "source_problems")
        assertFalse(failed, text)
        val problems = Json.parseToJsonElement(text)
        val listed = problems.field("sources")!!.jsonArray.map { it.field("sourceId")!!.jsonPrimitive.content }
        assertEquals(listOf(sources.last().id), listed, text)
        assertEquals(2, problems.field("total")!!.jsonPrimitive.int)
        assertEquals(1, problems.field("count")!!.field("HEALTHY")!!.jsonPrimitive.int, text)

        for (spelling in listOf("HEALTHY", "healthy")) {
            val (_, only) = call(admin, "source_problems", buildJsonObject { put("status", spelling) })
            val row = Json.parseToJsonElement(only).field("sources")!!.jsonArray.single()
            assertEquals(healthy.id, row.field("sourceId")!!.jsonPrimitive.content, only)
            assertEquals(healthy.channelId, row.field("providerChannelId")!!.jsonPrimitive.content, only)
            assertEquals(1, row.field("entryIds")!!.jsonArray.size, only)
        }
        val (rejected, why) = call(admin, "source_problems", buildJsonObject { put("status", "withdrawn") })
        assertTrue(rejected && why.startsWith("INVALID_REQUEST: "), why)
    }

    @Test fun `lineup pages with offset and names where the next page starts`() = mcp(withPolicy = true) {
        val (_, first) = call(admin, "lineup", buildJsonObject { put("limit", 1) })
        val page = Json.parseToJsonElement(first)
        assertEquals(2, page.field("total")!!.jsonPrimitive.int, first)
        assertEquals(1, page.field("entries")!!.jsonArray.size, first)
        assertEquals(1, page.field("nextOffset")!!.jsonPrimitive.int, first)
        val (_, second) = call(
            admin,
            "lineup",
            buildJsonObject {
                put("limit", 1)
                put("offset", 1)
            },
        )
        val last = Json.parseToJsonElement(second)
        assertEquals(1, last.field("entries")!!.jsonArray.size, second)
        assertNull(last.field("nextOffset"), second)
        val ids = listOf(page, last).map {
            it.field("entries")!!.jsonArray.single().field("id")!!.jsonPrimitive.content
        }
        assertEquals(runtime.lineup.entries().map { it.id }.toSet(), ids.toSet())
    }

    @Test fun `lineup filters by name and state and reports a row's fields`() = mcp(withPolicy = true) {
        val entry = runtime.lineup.entries().first()
        val (_, named) = call(admin, "lineup", buildJsonObject { put("q", entry.name.take(3)) })
        val row = Json.parseToJsonElement(named).field("entries")!!.jsonArray
            .first { it.field("id")!!.jsonPrimitive.content == entry.id }
        assertEquals(entry.number, row.field("number")!!.jsonPrimitive.content)
        assertEquals(entry.offeringIds.size, row.field("sourceCount")!!.jsonPrimitive.int)
        val (_, off) = call(admin, "lineup", buildJsonObject { put("state", "off") })
        assertEquals(0, Json.parseToJsonElement(off).field("total")!!.jsonPrimitive.int, off)
        val (rejected, why) = call(admin, "lineup", buildJsonObject { put("state", "maybe") })
        assertTrue(rejected && why.startsWith("INVALID_REQUEST: "), why)
    }

    @Test fun `channel resolves a lineup entry id or a provider channel id or a number`() = mcp(withPolicy = true) {
        val entry = runtime.lineup.entries().first()
        val source = runtime.feeds.sources().first { it.id in entry.offeringIds }

        val (_, byEntry) = call(admin, "channel", buildJsonObject { put("id", entry.id) })
        val direct = Json.parseToJsonElement(byEntry)
        assertEquals(entry.id, direct.field("id")!!.jsonPrimitive.content, byEntry)
        assertEquals("lineupEntry", direct.field("resolvedFrom")!!.jsonPrimitive.content)
        assertEquals(source.id, direct.field("sources")!!.jsonArray.single().field("sourceId")!!.jsonPrimitive.content)

        val (_, byProvider) = call(admin, "channel", buildJsonObject { put("id", source.channelId) })
        val resolved = Json.parseToJsonElement(byProvider)
        assertEquals(entry.id, resolved.field("id")!!.jsonPrimitive.content, byProvider)
        assertEquals("providerChannel", resolved.field("resolvedFrom")!!.jsonPrimitive.content)

        val (_, byNumber) = call(admin, "channel", buildJsonObject { put("number", entry.number) })
        assertEquals(entry.id, Json.parseToJsonElement(byNumber).field("id")!!.jsonPrimitive.content, byNumber)

        val (missing, why) = call(admin, "channel", buildJsonObject { put("id", "no-such-id") })
        assertTrue(missing && why.startsWith("CHANNEL_NOT_FOUND: ") && why.contains("call lineup"), why)
        val (both, neither) = call(admin, "channel")
        assertTrue(both && neither.startsWith("INVALID_REQUEST: "), neither)
    }

    @Test fun `source_health explains one source and sends its entries`() = mcp(withPolicy = true) {
        val source = runtime.feeds.sources().first()
        val now = Instant.parse("2026-10-10T13:00:00Z")
        runtime.store.recordObservation(source.id, Observation("bad", now, false, FailureClass.OFFERING), now)
        val (failed, text) = call(read(), "source_health", buildJsonObject { put("sourceId", source.id) })
        assertFalse(failed, text)
        val health = Json.parseToJsonElement(text)
        assertEquals("SUSPECT", health.field("status")!!.jsonPrimitive.content, text)
        assertEquals(
            "OFFERING",
            health.field("failures")!!.jsonArray.single().field("failureClass")!!.jsonPrimitive.content,
        )
        assertEquals(1, health.field("entryIds")!!.jsonArray.size, text)
        val (unknown, why) = call(admin, "source_health", buildJsonObject { put("sourceId", "nope") })
        assertTrue(unknown && why.startsWith("SOURCE_NOT_FOUND: ") && why.contains("source_problems"), why)
    }

    @Test fun `recordings filters by state and says when a page is cut short`() = mcp {
        val (failed, text) = call(
            admin,
            "recordings",
            buildJsonObject {
                put("state", "scheduled")
                put("limit", 1)
            },
        )
        assertFalse(failed, text)
        val page = Json.parseToJsonElement(text)
        assertEquals(false, page.field("truncated")!!.jsonPrimitive.boolean, text)
        assertTrue(page.field("recordings")!!.jsonArray.isEmpty(), text)
        val (rejected, why) = call(admin, "recordings", buildJsonObject { put("state", "canceled") })
        assertTrue(rejected && why.startsWith("INVALID_REQUEST: "), why)
    }

    @Test fun `every list tool honors limit`() = mcp(withPolicy = true) {
        val limited = buildJsonObject { put("limit", 1) }
        val tools = mapOf(
            "lineup" to limited, "source_problems" to limited, "guide_now" to limited, "recordings" to limited,
            "recording_rules" to limited, "watching_now" to limited, "policy_findings" to limited,
            "preview_policy" to limited, "policy_changes" to limited, "guide_matches" to limited,
            "playback_history" to limited,
            "recent_log" to buildJsonObject {
                put("limit", 1)
                put("level", "info")
            },
            "on_later" to buildJsonObject {
                put("kind", "movie")
                put("limit", 1)
            },
        )
        for ((name, arguments) in tools) {
            val (failed, text) = call(admin, name, arguments)
            assertFalse(failed, "$name: $text")
            for (rows in objectLists(Json.parseToJsonElement(text), depth = 0)) {
                assertTrue(rows.size <= 1, "$name lists ${rows.size} rows with limit 1: $text")
            }
        }
    }

    @Test fun `no read tool returns a URL or a token or a request header`() = mcp(withPolicy = true) {
        val read = runtime.tokens.create("Assistant", TokenScope.READ).second
        val device = runtime.tokens.pair("tv-1", "TV")
        val secrets = listOf(token, admin, read, device)
        val actions = setOf("run_job", "scan_library", "check_for_update", "update_now")
        for (name in tools(admin).filter { it !in actions }) {
            val (_, text) = call(admin, name, argumentsFor(name))
            assertFalse(text.contains("://"), "$name: $text")
            // The log ring is shared by every test in the process, so only its other lines say the fields are clean.
            if (name != "recent_log") {
                assertFalse(text.contains("[url removed]") || text.contains("[token removed]"), "$name: $text")
            }
            for (secret in secrets) assertFalse(text.contains(secret), "$name leaks a token: $text")
            assertFalse(text.contains("Bearer", ignoreCase = true), "$name: $text")
        }
    }

    @Test fun `playback_history and plex_status are listed only to the admin token`() = mcp {
        val read = runtime.tokens.create("Assistant", TokenScope.READ).second
        val reads = tools(read)
        assertTrue(listOf("playback_history", "plex_status").none { it in reads }, "$reads")
        val all = tools(admin)
        assertTrue(all.containsAll(listOf("playback_history", "plex_status")), "$all")
        val refused = post(
            read,
            "tools/call",
            buildJsonObject {
                put("name", "playback_history")
                put("arguments", JsonObject(emptyMap()))
            },
        )
        val message = Json.parseToJsonElement(refused.bodyAsText()).jsonObject
        val denied = message["error"] != null ||
            message["result"]?.jsonObject?.get("isError")?.jsonPrimitive?.boolean == true
        assertTrue(denied, "a read token ran playback_history: $message")
        val (history, text) = call(admin, "playback_history", buildJsonObject { put("kind", "vod") })
        assertFalse(history, text)
        val (bad, why) = call(admin, "playback_history", buildJsonObject { put("since", "yesterday") })
        assertTrue(bad && why.startsWith("INVALID_REQUEST: "), why)
        val (plex, notConfigured) = call(admin, "plex_status")
        assertTrue(plex && notConfigured.startsWith("PLEX_NOT_CONFIGURED: "), notConfigured)
    }

    @Test fun `guide_channels lists a guide's channels and refuses an unknown guide`() = mcp {
        val guide = runtime.guides.statuses.first().id
        val (failed, text) = call(
            admin,
            "guide_channels",
            buildJsonObject {
                put("guideId", guide)
                put("limit", 1)
            },
        )
        assertFalse(failed, text)
        assertTrue(text.contains("channelId") && !text.contains("icon"), text)
        val (unknown, why) = call(admin, "guide_channels", buildJsonObject { put("guideId", "nope") })
        assertTrue(unknown && why.startsWith("GUIDE_NOT_FOUND: ") && why.contains("call imports"), why)
    }

    private suspend fun Mcp.read() = runtime.tokens.create("Assistant", TokenScope.READ).second

    /** Arguments that let each tool that takes some answer on the fixture. */
    private suspend fun Mcp.argumentsFor(name: String): JsonObject = buildJsonObject {
        when (name) {
            "search_library" -> put("query", "x")
            "on_later" -> put("kind", "movie")
            "source_health" -> put("sourceId", runtime.feeds.sources().first().id)
            "channel" -> put("id", runtime.lineup.entries().first().id)
            "explain_station" -> put("key", "station")
            "guide_channels" -> put("guideId", runtime.guides.statuses.firstOrNull()?.id ?: "none")
        }
    }

    /** The arrays of objects within two levels of [element]: the rows a list tool returns. */
    private fun objectLists(element: JsonElement, depth: Int): List<JsonArray> = when (element) {
        is JsonArray -> listOf(element).filter { rows -> rows.all { it is JsonObject } } +
            if (depth < 2) element.flatMap { objectLists(it, depth + 1) } else emptyList()

        is JsonObject -> if (depth < 2) element.values.flatMap { objectLists(it, depth + 1) } else emptyList()

        else -> emptyList()
    }

    private fun JsonElement.field(name: String): JsonElement? = (this as? JsonObject)?.get(name)

    private companion object {
        val POLICY_TOOLS =
            listOf("policy_findings", "preview_policy", "policy_changes", "explain_station", "guide_matches")
    }
}
