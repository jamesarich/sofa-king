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
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal val MCP_TOKEN = "secret".repeat(8)
internal val MCP_ADMIN = "curator".repeat(6)
internal val MCP_ACTION_TOOLS = listOf(
    "set_source_enabled",
    "probe_source",
    "switch_session_source",
    "apply_policy",
    "edit_station",
    "decide_merge",
    "decide_guide_match",
)

internal suspend fun Mcp.read() = runtime.tokens.create("Assistant", TokenScope.READ).second

internal fun JsonElement.field(name: String): JsonElement? = (this as? JsonObject)?.get(name)

internal class Mcp(val builder: ApplicationTestBuilder, val runtime: LiveRuntime) {
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

private const val POLICY_TEXT =
    """{"providers":[{"id":"alt"},{"id":"fixture"}],"health":{"requireVerified":false},""" +
        """"numbering":{"floor":100},"stations":{"station":{"group":"Local"}}}"""

/** A server with the fixture provider, or with a lineup policy over the fixture and alt providers. */
internal fun mcp(withPolicy: Boolean = false, probes: Boolean = false, block: suspend Mcp.() -> Unit) =
    testApplication {
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
            }?.also { Files.writeString(it, POLICY_TEXT) }
            val config = ServerConfig(
                "0.0.0.0", 8080, dir, sources, MCP_TOKEN, allowLoopbackUpstreams = true, adminToken = MCP_ADMIN,
                hdhr = HdhrConfig(enabled = false), revision = "abc1234", policyFile = policyFile,
            )
            val store = openStore(dir)
            val runtime = LiveRuntime.create(
                store,
                config,
                MutableClock(),
                policyDebounceMillis = 3_600_000,
                probes = probes,
            )
            try {
                for (feed in feeds) runtime.feeds.refresh(feed)
                runtime.adoptAll()
                application { backendModule(store, MutableClock(), runtime, MCP_TOKEN, MCP_ADMIN, config = config) }
                Mcp(this, runtime).block()
            } finally {
                runtime.close()
                store.close()
                dir.toFile().deleteRecursively()
            }
        }
    }
