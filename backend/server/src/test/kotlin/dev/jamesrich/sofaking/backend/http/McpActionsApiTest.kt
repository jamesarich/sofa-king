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

class McpActionsApiTest {
    private val token = MCP_TOKEN
    private val admin = MCP_ADMIN

    @Test fun `a read token is listed none of the live TV and lineup actions and cannot call them`() =
        mcp(withPolicy = true) {
            val read = read()
            assertTrue(MCP_ACTION_TOOLS.none { it in tools(read) }, "a read token sees no action: ${tools(read)}")
            assertTrue(tools(admin).containsAll(MCP_ACTION_TOOLS), "the admin token sees them: ${tools(admin)}")
            for (name in MCP_ACTION_TOOLS) {
                val response = post(
                    read,
                    "tools/call",
                    buildJsonObject {
                        put("name", name)
                        put("arguments", JsonObject(emptyMap()))
                    },
                )
                val message = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                val refused = message["error"] != null ||
                    message["result"]?.jsonObject?.get("isError")?.jsonPrimitive?.boolean == true
                assertTrue(refused, "a read token ran $name: $message")
            }
        }

    @Test fun `a destructive action refuses without confirm and says so`() = mcp(withPolicy = true) {
        val source = runtime.feeds.sources().first()
        val (disabled, why) = call(
            admin,
            "set_source_enabled",
            buildJsonObject {
                put("sourceId", source.id)
                put("enabled", false)
            },
        )
        assertTrue(disabled && why.startsWith("CONFIRMATION_REQUIRED: ") && why.contains("confirm true"), why)
        assertFalse(runtime.feeds.sources().first { it.id == source.id }.manuallyDisabled)

        val (applying, applyWhy) = call(admin, "apply_policy", buildJsonObject { put("revision", currentRevision()) })
        assertTrue(applying && applyWhy.startsWith("CONFIRMATION_REQUIRED: "), applyWhy)

        val (updating, updateWhy) = call(admin, "update_now")
        assertTrue(updating && updateWhy.startsWith("CONFIRMATION_REQUIRED: "), updateWhy)
        val (confirmed, noUpdater) = call(admin, "update_now", buildJsonObject { put("confirm", true) })
        assertTrue(confirmed && noUpdater.startsWith("NO_UPDATER: "), "confirm passes the gate: $noUpdater")

        val (bad, badWhy) = call(admin, "update_now", buildJsonObject { put("confirm", "yes") })
        assertTrue(bad && badWhy.startsWith("INVALID_REQUEST: "), badWhy)
    }

    @Test fun `set_source_enabled round-trips through source_health and the sources route`() = mcp(withPolicy = true) {
        val source = runtime.feeds.sources().first()
        val arguments = { enabled: Boolean, confirm: Boolean? ->
            buildJsonObject {
                put("sourceId", source.id)
                put("enabled", enabled)
                confirm?.let { put("confirm", it) }
            }
        }
        val (failed, text) = call(admin, "set_source_enabled", arguments(false, true))
        assertFalse(failed, text)
        assertEquals(true, Json.parseToJsonElement(text).field("manuallyDisabled")!!.jsonPrimitive.boolean, text)
        val (_, health) = call(admin, "source_health", buildJsonObject { put("sourceId", source.id) })
        assertEquals(true, Json.parseToJsonElement(health).field("manuallyDisabled")!!.jsonPrimitive.boolean, health)
        val listed = Json.parseToJsonElement(builder.client.get("/api/v1/sources") { bearerAuth(token) }.bodyAsText())
        val row = listed.jsonArray.first { it.field("id")!!.jsonPrimitive.content == source.id }
        assertEquals(true, row.field("manuallyDisabled")!!.jsonPrimitive.boolean)

        val (enabled, back) = call(admin, "set_source_enabled", arguments(true, null))
        assertFalse(enabled, back)
        assertFalse(runtime.feeds.sources().first { it.id == source.id }.manuallyDisabled)
        val (unknown, why) = call(
            admin,
            "set_source_enabled",
            buildJsonObject {
                put("sourceId", "nope")
                put("enabled", true)
            },
        )
        assertTrue(unknown && why.startsWith("SOURCE_NOT_FOUND: "), why)
    }

    @Test fun `the probe route answers 202 and probe_source queues the same`() = mcp(withPolicy = true, probes = true) {
        val (first, second) = runtime.feeds.sources().map { it.id }
        val route = builder.client.post("/api/v1/admin/sources/$first/probe") { bearerAuth(admin) }
        assertEquals(HttpStatusCode.Accepted, route.status, route.bodyAsText())
        assertEquals(first, Json.parseToJsonElement(route.bodyAsText()).field("sourceId")!!.jsonPrimitive.content)
        assertEquals(
            HttpStatusCode.NotFound,
            builder.client.post("/api/v1/admin/sources/nope/probe") { bearerAuth(admin) }.status,
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            builder.client.post("/api/v1/admin/sources/$second/probe") { bearerAuth(token) }.status,
        )

        val (failed, text) = call(admin, "probe_source", buildJsonObject { put("sourceId", second) })
        assertFalse(failed, text)
        assertTrue(text.contains("Queued") && text.contains("source_health"), text)
        val (unknown, why) = call(admin, "probe_source", buildJsonObject { put("sourceId", "nope") })
        assertTrue(unknown && why.startsWith("SOURCE_NOT_FOUND: "), why)
    }

    @Test fun `probe_source says probes are off when no worker runs`() = mcp(withPolicy = true) {
        val source = runtime.feeds.sources().first().id
        val (failed, text) = call(admin, "probe_source", buildJsonObject { put("sourceId", source) })
        assertTrue(failed && text.startsWith("PROBES_OFF: "), text)
        val route = builder.client.post("/api/v1/admin/sources/$source/probe") { bearerAuth(admin) }
        assertEquals(HttpStatusCode.Conflict, route.status)
    }

    @Test fun `switch_session_source on a channel with one source points at channel`() = mcp {
        val station = runtime.lineup.entries().first { it.name == "Station" }
        val opened = builder.client.post("/api/v1/playback-sessions") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"channelId":"${station.id}","intent":"watch","idempotencyKey":"mcp-switch"}""")
        }
        val sessionId = Json.parseToJsonElement(opened.bodyAsText()).field("id")!!.jsonPrimitive.content
        val (failed, text) = call(admin, "switch_session_source", buildJsonObject { put("sessionId", sessionId) })
        assertTrue(failed && text.startsWith("NO_OTHER_SOURCE: ") && text.contains("call channel"), text)
        assertEquals(1, runtime.sessions.count(), "the session plays on")
        val (gone, why) = call(admin, "switch_session_source", buildJsonObject { put("sessionId", "nope") })
        assertTrue(gone && !why.startsWith("INTERNAL_ERROR"), why)
    }

    @Test fun `edit_station previews by default and writes nothing`() = mcp(withPolicy = true) {
        runtime.policy!!.apply("test")
        val entry = runtime.lineup.entries().first()
        val before = currentRevision()
        val changes = runtime.policy!!.changes(1000).size
        val (failed, text) = call(admin, "edit_station", editNumber(entry.id, before, "777"))
        assertFalse(failed, text)
        val result = Json.parseToJsonElement(text)
        assertEquals(true, result.field("dryRun")!!.jsonPrimitive.boolean, text)
        assertEquals(false, result.field("impact")!!.field("noChange")!!.jsonPrimitive.boolean, text)
        assertEquals(1, result.field("impact")!!.field("changeNumber")!!.field("total")!!.jsonPrimitive.int, text)
        assertEquals(before, result.field("revision")!!.jsonPrimitive.content, "a preview leaves the revision")
        assertEquals(before, currentRevision())
        assertEquals(changes, runtime.policy!!.changes(1000).size, "a preview logs nothing")
        assertNull(result.field("applied"), text)
    }

    @Test fun `edit_station with dryRun false writes it and the change log names mcp`() = mcp(withPolicy = true) {
        runtime.policy!!.apply("test")
        val entry = runtime.lineup.entries().first()
        val before = currentRevision()
        val (failed, text) = call(admin, "edit_station", editNumber(entry.id, before, "777", dryRun = false))
        assertFalse(failed, text)
        val result = Json.parseToJsonElement(text)
        assertEquals(false, result.field("dryRun")!!.jsonPrimitive.boolean, text)
        assertTrue(result.field("applied") != null, text)
        assertEquals("777", runtime.lineup.entry(entry.id)!!.number)
        assertNotEquals(before, currentRevision(), "the policy file changed")
        val change = runtime.policy!!.changes(10).first { it.change.field == "number" }
        assertEquals("mcp", change.change.reason)
        val (_, logged) = call(admin, "policy_changes")
        assertTrue(logged.contains("\"reason\":\"mcp\""), logged)
    }

    @Test fun `a stale revision answers POLICY_CHANGED and says to preview again`() = mcp(withPolicy = true) {
        runtime.policy!!.apply("test")
        val entry = runtime.lineup.entries().first()
        for (dryRun in listOf(true, false)) {
            val (failed, text) = call(admin, "edit_station", editNumber(entry.id, "stale", "777", dryRun = dryRun))
            assertTrue(failed && text.startsWith("POLICY_CHANGED: "), text)
            assertTrue(text.contains("call preview_policy again"), text)
        }
        val (applying, applyWhy) = call(
            admin,
            "apply_policy",
            buildJsonObject {
                put("revision", "stale")
                put("confirm", true)
            },
        )
        assertTrue(applying && applyWhy.startsWith("POLICY_CHANGED: ") && applyWhy.contains("preview_policy"), applyWhy)
        assertNotEquals("777", runtime.lineup.entry(entry.id)!!.number)
        val (unknown, why) = call(admin, "edit_station", editNumber("nope", currentRevision(), "777"))
        assertTrue(unknown && why.startsWith("LINEUP_ENTRY_NOT_FOUND: ") && why.contains("call lineup"), why)
        val (empty, nothing) = call(
            admin,
            "edit_station",
            buildJsonObject {
                put("entryId", entry.id)
                put("revision", currentRevision())
            },
        )
        assertTrue(empty && nothing.startsWith("INVALID_REQUEST: "), nothing)
    }

    @Test fun `apply_policy applies at the current revision once confirmed`() = mcp(withPolicy = true) {
        val (failed, text) = call(
            admin,
            "apply_policy",
            buildJsonObject {
                put("revision", currentRevision())
                put("confirm", true)
            },
        )
        assertFalse(failed, text)
        assertTrue(runtime.lineup.entries().isNotEmpty(), text)
        assertTrue(runtime.policy!!.changes(1000).any { it.change.reason == "mcp" }, "the log names mcp")
    }

    @Test fun `decide_merge previews and then adds the alias`() = mcp(withPolicy = true) {
        runtime.policy!!.apply("test")
        val revision = currentRevision()
        val arguments = { dryRun: Boolean ->
            buildJsonObject {
                put("a", "ghost")
                put("b", "station")
                put("decision", "accept")
                put("revision", revision)
                put("dryRun", dryRun)
            }
        }
        val (previewed, preview) = call(admin, "decide_merge", arguments(true))
        assertFalse(previewed, preview)
        assertTrue(runtime.policy!!.policy.names.aliases.isEmpty(), "a preview writes nothing")
        assertEquals(revision, currentRevision())
        val (failed, text) = call(admin, "decide_merge", arguments(false))
        assertFalse(failed, text)
        assertEquals("station", runtime.policy!!.policy.names.aliases["ghost"], text)
    }

    @Test fun `decide_merge never keeps two keys apart`() = mcp(withPolicy = true) {
        runtime.policy!!.apply("test")
        val (failed, text) = call(
            admin,
            "decide_merge",
            buildJsonObject {
                put("a", "alpha")
                put("b", "station")
                put("decision", "never")
                put("revision", currentRevision())
                put("dryRun", false)
            },
        )
        assertFalse(failed, text)
        assertEquals(listOf(setOf("alpha", "station")), runtime.policy!!.policy.names.never.map { it.toSet() })
        val (bad, why) = call(
            admin,
            "decide_merge",
            buildJsonObject {
                put("a", "station")
                put("b", "station")
                put("decision", "never")
                put("revision", currentRevision())
            },
        )
        assertTrue(bad && why.startsWith("INVALID_REQUEST: "), why)
    }

    @Test fun `decide_guide_match refuses what the routes refuse`() = mcp(withPolicy = true) {
        runtime.policy!!.apply("test")
        val (failed, text) = call(
            admin,
            "decide_guide_match",
            buildJsonObject {
                put("station", "station")
                put("guideId", "no-guide")
                put("channelId", "x")
                put("decision", "accept")
            },
        )
        assertTrue(failed && text.startsWith("GUIDE_CHANNEL_NOT_FOUND: "), text)
        val (all, done) = call(admin, "decide_guide_match", buildJsonObject { put("acceptAll", true) })
        assertFalse(all, done)
        assertEquals(0, Json.parseToJsonElement(done).field("accepted")!!.jsonPrimitive.int, done)
        val (missing, why) = call(admin, "decide_guide_match")
        assertTrue(missing && why.startsWith("INVALID_REQUEST: "), why)
    }

    private suspend fun Mcp.currentRevision(): String =
        Json.parseToJsonElement(call(admin, "preview_policy").second).field("revision")!!.jsonPrimitive.content

    private fun editNumber(entryId: String, revision: String, number: String, dryRun: Boolean? = null) =
        buildJsonObject {
            put("entryId", entryId)
            put("revision", revision)
            put("number", number)
            dryRun?.let { put("dryRun", it) }
        }
}
