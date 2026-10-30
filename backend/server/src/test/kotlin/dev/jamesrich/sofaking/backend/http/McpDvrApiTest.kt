package dev.jamesrich.sofaking.backend.http

import com.sun.net.httpserver.HttpServer
import dev.jamesrich.sofaking.backend.api.SaveNotificationTarget
import dev.jamesrich.sofaking.backend.dvr.StoredRecording
import dev.jamesrich.sofaking.backend.hash
import dev.jamesrich.sofaking.backend.store.addRecording
import dev.jamesrich.sofaking.backend.store.touchViewer
import dev.jamesrich.sofaking.contract.ApiError
import dev.jamesrich.sofaking.contract.RecordingBreak
import dev.jamesrich.sofaking.contract.ScheduleRecording
import dev.jamesrich.sofaking.contract.Wire
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val DVR_TOOLS = listOf(
    "schedule_recording",
    "cancel_recording",
    "manage_recording_rule",
    "devices",
    "notifications",
)

class McpDvrApiTest {
    private val token = MCP_TOKEN
    private val admin = MCP_ADMIN

    private fun Mcp.args(vararg pairs: Pair<String, Any>): JsonObject = buildJsonObject {
        for ((name, value) in pairs) {
            when (value) {
                is Boolean -> put(name, value)
                is Int -> put(name, value)
                else -> put(name, value.toString())
            }
        }
    }

    private suspend fun Mcp.lineupEntry(): String {
        val (failed, text) = call(admin, "guide_now")
        assertFalse(failed, text)
        return Json.parseToJsonElement(text).field("channels")!!.jsonArray.first()
            .field("channelId")!!.jsonPrimitive.content
    }

    private fun start(hours: Long): String =
        Instant.parse("2026-10-10T13:00:00Z").plus(Duration.ofHours(hours)).toString()

    @Test fun `a read token is listed none of the DVR and household tools and cannot call them`() = mcp {
        val read = read()
        assertTrue(DVR_TOOLS.none { it in tools(read) }, "a read token sees no DVR tool: ${tools(read)}")
        assertTrue(tools(admin).containsAll(DVR_TOOLS), "the admin token sees them: ${tools(admin)}")
        for (name in DVR_TOOLS) {
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

    @Test fun `no tool can create an API token`() = mcp {
        val names = tools(admin)
        assertTrue(names.none { it.contains("token") }, names.toString())
        val (_, text) = call(admin, "devices", args("op" to "create"))
        assertTrue(text.startsWith("INVALID_REQUEST: "), text)
    }

    @Test fun `schedule_recording takes the channel id guide_now returns and shows in recordings`() = mcp {
        val channelId = lineupEntry()
        val arguments = args(
            "channelId" to channelId,
            "startsAt" to start(3),
            "endsAt" to start(4),
            "title" to "Late Show",
        )
        val (failed, text) = call(admin, "schedule_recording", arguments)
        assertFalse(failed, text)
        val scheduled = Json.parseToJsonElement(text)
        assertEquals(Wire.RecordingState.SCHEDULED, scheduled.field("state")!!.jsonPrimitive.content)
        assertEquals("Late Show", scheduled.field("title")!!.jsonPrimitive.content)

        val (_, again) = call(admin, "schedule_recording", arguments)
        assertEquals(
            scheduled.field("id")!!.jsonPrimitive.content,
            Json.parseToJsonElement(again).field("id")!!.jsonPrimitive.content,
        )
        val (_, listed) = call(admin, "recordings")
        assertEquals(1, Json.parseToJsonElement(listed).field("total")!!.jsonPrimitive.int, listed)

        val (unknown, why) = call(
            admin,
            "schedule_recording",
            args(
                "channelId" to "nope",
                "startsAt" to start(3),
                "endsAt" to start(4),
            ),
        )
        assertTrue(unknown && why.startsWith("CHANNEL_NOT_FOUND: "), why)
    }

    @Test fun `recordings shows the commercial scan state, the break count and when it last changed`() = mcp {
        val start = Instant.parse("2026-10-09T13:00:00Z")
        runtime.store.addRecording(
            StoredRecording(
                "scanned", null, "channel", "Channel", start, start.plusSeconds(1800),
                Duration.ofSeconds(60), Duration.ofSeconds(120), "Show",
                state = Wire.RecordingState.COMPLETED, path = "Show/Show.ts", bytes = 8, createdAt = start,
                updatedAt = start, commercials = Wire.CommercialScan.DONE,
                breaks = listOf(RecordingBreak(1_000, 2_500), RecordingBreak(9_000, 12_000)),
            ),
        )
        val (failed, text) = call(admin, "recordings")
        assertFalse(failed, text)
        val row = Json.parseToJsonElement(text).field("recordings")!!.jsonArray.single()
        assertEquals(Wire.CommercialScan.DONE, row.field("commercials")!!.jsonPrimitive.content, text)
        assertEquals(2, row.field("breaks")!!.jsonPrimitive.int, text)
        assertEquals(start.toString(), row.field("updatedAt")!!.jsonPrimitive.content, text)
    }

    @Test fun `a window the route refuses is refused the same by schedule_recording`() = mcp {
        val channelId = lineupEntry()
        val tooLong = ScheduleRecording(channelId, start(3), start(20), "Marathon")
        val route = builder.client.post("/api/v1/recordings") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(ScheduleRecording.serializer(), tooLong))
        }
        assertEquals(HttpStatusCode.BadRequest, route.status)
        val error = Json.decodeFromString<ApiError>(route.bodyAsText())

        val (failed, text) = call(
            admin,
            "schedule_recording",
            args("channelId" to channelId, "startsAt" to start(3), "endsAt" to start(20), "title" to "Marathon"),
        )
        assertTrue(failed, text)
        assertEquals("${error.code}: ${error.message}", text)

        val (backwards, backwardsWhy) = call(
            admin,
            "schedule_recording",
            args("channelId" to channelId, "startsAt" to start(4), "endsAt" to start(3)),
        )
        assertTrue(backwards && backwardsWhy.startsWith("INVALID_REQUEST: "), backwardsWhy)
    }

    private suspend fun Mcp.assertSameRefusal(status: HttpStatusCode, code: String, request: ScheduleRecording) {
        val route = builder.client.post("/api/v1/recordings") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(ScheduleRecording.serializer(), request))
        }
        assertEquals(status, route.status, route.bodyAsText())
        val error = Json.decodeFromString<ApiError>(route.bodyAsText())
        assertEquals(code, error.code)
        val (failed, text) = call(
            admin,
            "schedule_recording",
            args(
                "channelId" to request.channelId,
                "startsAt" to request.startsAt,
                "endsAt" to request.endsAt!!,
            ),
        )
        assertTrue(failed, text)
        assertEquals("${error.code}: ${error.message}", text)
    }

    @Test fun `schedule_recording refuses a start beyond the horizon as the route does`() = mcp {
        val channelId = lineupEntry()
        val far = Instant.parse("2026-10-10T13:00:00Z").plus(MANUAL_HORIZON).plusSeconds(3600)
        assertSameRefusal(
            HttpStatusCode.BadRequest,
            "INVALID_REQUEST",
            ScheduleRecording(channelId, far.toString(), far.plusSeconds(1800).toString()),
        )
    }

    @Test fun `schedule_recording refuses a full schedule as the route does`() = mcp {
        val channelId = lineupEntry()
        val dvr = runtime.dvr!!
        repeat(MAX_SCHEDULED_ONE_OFFS) {
            val at = Instant.parse("2026-10-10T16:00:00Z").plusSeconds(it * 3600L)
            dvr.schedule(ScheduleRecording(channelId, at.toString(), at.plusSeconds(60).toString()))
        }
        val next = Instant.parse("2026-10-10T16:00:00Z").plusSeconds(MAX_SCHEDULED_ONE_OFFS * 3600L)
        assertSameRefusal(
            HttpStatusCode.Conflict,
            "SCHEDULE_FULL",
            ScheduleRecording(channelId, next.toString(), next.plusSeconds(60).toString()),
        )
    }

    @Test fun `cancel_recording of a finished recording needs deleteFile and then removes the file`() = mcp {
        val file = dir.resolve("recordings/Show/Show.ts")
        Files.createDirectories(file.parent)
        Files.writeString(file, "recorded")
        val start = Instant.parse("2026-10-09T13:00:00Z")
        runtime.store.addRecording(
            StoredRecording(
                "done", null, "channel", "Channel", start, start.plusSeconds(1800),
                Duration.ofSeconds(60), Duration.ofSeconds(120), "Show",
                state = Wire.RecordingState.COMPLETED, path = "Show/Show.ts", bytes = 8, createdAt = start,
                updatedAt = start,
            ),
        )
        val recording = buildJsonObject { put("recordingId", "done") }

        val (unconfirmed, askConfirm) = call(admin, "cancel_recording", recording)
        assertTrue(unconfirmed && askConfirm.startsWith("CONFIRMATION_REQUIRED: "), askConfirm)

        val confirmed = buildJsonObject {
            put("recordingId", "done")
            put("confirm", true)
        }
        val (refused, why) = call(admin, "cancel_recording", confirmed)
        assertTrue(refused && why.startsWith("FILE_DELETE_REQUIRED: ") && why.contains("deleteFile true"), why)
        assertTrue(Files.exists(file), "a refusal keeps the file")
        assertEquals(HttpStatusCode.OK, builder.client.get("/api/v1/recordings/done") { bearerAuth(token) }.status)

        val (failed, text) = call(
            admin,
            "cancel_recording",
            buildJsonObject {
                put("recordingId", "done")
                put("confirm", true)
                put("deleteFile", true)
            },
        )
        assertFalse(failed, text)
        assertFalse(Files.exists(file), "deleteFile removes the file")
        assertEquals(
            HttpStatusCode.NotFound,
            builder.client.get("/api/v1/recordings/done") {
                bearerAuth(token)
            }.status,
        )
    }

    @Test fun `cancel_recording cancels a scheduled recording with confirm alone`() = mcp {
        val channelId = lineupEntry()
        val (_, created) = call(
            admin,
            "schedule_recording",
            args("channelId" to channelId, "startsAt" to start(3), "endsAt" to start(4)),
        )
        val id = Json.parseToJsonElement(created).field("id")!!.jsonPrimitive.content
        val (failed, text) = call(
            admin,
            "cancel_recording",
            buildJsonObject {
                put("recordingId", id)
                put("confirm", true)
            },
        )
        assertFalse(failed, text)
        val (_, listed) = call(admin, "recordings")
        assertEquals(0, Json.parseToJsonElement(listed).field("total")!!.jsonPrimitive.int, listed)
    }

    private suspend fun Mcp.rule(vararg pairs: Pair<String, Any>) = call(admin, "manage_recording_rule", args(*pairs))

    @Test fun `manage_recording_rule creates and updates a rule that recording_rules then shows`() = mcp {
        val channelId = lineupEntry()
        val (created, createdText) = rule(
            "op" to "create",
            "title" to "Late Show",
            "keep" to 3,
            "channelId" to channelId,
        )
        assertFalse(created, createdText)
        val made = Json.parseToJsonElement(createdText)
        val id = made.field("id")!!.jsonPrimitive.content
        assertEquals(3, made.field("keep")!!.jsonPrimitive.int)

        val (updated, updatedText) = rule(
            "op" to "update",
            "id" to id,
            "keep" to 7,
            "anyChannel" to true,
            "priority" to 5,
        )
        assertFalse(updated, updatedText)
        val (_, rules) = call(admin, "recording_rules")
        val row = Json.parseToJsonElement(rules).field("items")!!.jsonArray.single()
        assertEquals(7, row.field("keep")!!.jsonPrimitive.int, rules)
        assertEquals(5, row.field("priority")!!.jsonPrimitive.int, rules)
        assertEquals(null, row.field("channelId"), "anyChannel clears the channel")
    }

    @Test fun `manage_recording_rule delete refuses without confirm and then removes the rule`() = mcp {
        val (_, createdText) = rule("op" to "create", "title" to "Late Show")
        val id = Json.parseToJsonElement(createdText).field("id")!!.jsonPrimitive.content

        val (refused, why) = rule("op" to "delete", "id" to id)
        assertTrue(refused && why.startsWith("CONFIRMATION_REQUIRED: "), why)
        val (_, stillThere) = call(admin, "recording_rules")
        assertEquals(1, Json.parseToJsonElement(stillThere).field("items")!!.jsonArray.size, stillThere)

        val (deleted, deletedText) = rule("op" to "delete", "id" to id, "confirm" to true)
        assertFalse(deleted, deletedText)
        assertEquals(
            0,
            Json.parseToJsonElement(deletedText).field("recordingsRemoved")!!.jsonPrimitive.int,
            deletedText,
        )
        val (gone, goneWhy) = rule("op" to "delete", "id" to id, "confirm" to true)
        assertTrue(gone && goneWhy.startsWith("RULE_NOT_FOUND: "), goneWhy)
    }

    @Test fun `manage_recording_rule refuses a changed title and a team rule without terms`() = mcp {
        val (_, createdText) = rule("op" to "create", "title" to "Late Show")
        val id = Json.parseToJsonElement(createdText).field("id")!!.jsonPrimitive.content

        val (retitled, retitledWhy) = rule("op" to "update", "id" to id, "title" to "Other")
        assertTrue(retitled && retitledWhy.startsWith("INVALID_REQUEST: "), retitledWhy)
        val (noTerms, noTermsWhy) = rule("op" to "create", "title" to "Team", "match" to "team")
        assertTrue(noTerms && noTermsWhy.startsWith("INVALID_REQUEST: "), noTermsWhy)
    }

    @Test fun `manage_recording_rule creates a team rule from terms`() = mcp {
        val (team, teamText) = call(
            admin,
            "manage_recording_rule",
            buildJsonObject {
                put("op", "create")
                put("title", "Home team")
                put("match", "team")
                put("terms", buildJsonArray { add(JsonPrimitive("Lions")) })
            },
        )
        assertFalse(team, teamText)
        val match = Json.parseToJsonElement(teamText).field("match")!!
        assertEquals("team", match.field("kind")!!.jsonPrimitive.content)
        assertEquals("Lions", match.field("terms")!!.jsonArray.single().jsonPrimitive.content)
    }

    @Test fun `devices lists no secret or hash and pair issues a code`() = mcp {
        val secret = runtime.tokens.pair("tv-1", "Living room")
        runtime.store.touchViewer("tv-1", "Living room", "tvos", Instant.parse("2026-10-10T13:00:00Z"))
        val (failed, text) = call(admin, "devices", args("op" to "list"))
        assertFalse(failed, text)
        val device = Json.parseToJsonElement(text).field("devices")!!.field("items")!!.jsonArray.single()
        assertEquals("tv-1", device.field("clientId")!!.jsonPrimitive.content)
        assertEquals("Living room", device.field("tokenLabel")!!.jsonPrimitive.content)
        for (hidden in listOf(secret, token, admin)) assertFalse(text.contains(hidden), "the list holds a secret")
        assertFalse(text.contains(hash(secret)), "the list holds the token hash")

        val (paired, pairText) = call(admin, "devices", args("op" to "pair"))
        assertFalse(paired, pairText)
        val code = Json.parseToJsonElement(pairText).field("code")!!.jsonPrimitive.content
        assertTrue(Regex("[0-9]{6}").matches(code), pairText)
        assertEquals(code, runtime.pairing.current()?.code)
    }

    @Test fun `devices forget needs confirm and then revokes the device and its token`() = mcp {
        val secret = runtime.tokens.pair("tv-2", "Bedroom")
        runtime.store.touchViewer("tv-2", "Bedroom", "tvos", Instant.parse("2026-10-10T13:00:00Z"))
        suspend fun status() = builder.client.get("/api/v1/recordings") { bearerAuth(secret) }.status
        assertEquals(HttpStatusCode.OK, status())

        val (refused, why) = call(admin, "devices", args("op" to "forget", "clientId" to "tv-2"))
        assertTrue(refused && why.startsWith("CONFIRMATION_REQUIRED: "), why)
        assertEquals(HttpStatusCode.OK, status(), "a refusal keeps the token")

        val (failed, text) = call(admin, "devices", args("op" to "forget", "clientId" to "tv-2", "confirm" to true))
        assertFalse(failed, text)
        assertEquals(HttpStatusCode.Unauthorized, status())
        val (_, listed) = call(admin, "devices", args("op" to "list"))
        assertEquals(0, Json.parseToJsonElement(listed).field("devices")!!.field("items")!!.jsonArray.size, listed)
        val (unknown, unknownWhy) = call(
            admin,
            "devices",
            args(
                "op" to "forget",
                "clientId" to "tv-2",
                "confirm" to true,
            ),
        )
        assertTrue(unknown && unknownWhy.startsWith("VIEWER_NOT_FOUND: "), unknownWhy)
    }

    @Test fun `notifications list shows a host and never the webhook path and test reaches the target`() = mcp {
        val hits = AtomicInteger()
        val target = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/hooks/private-path") { exchange ->
                hits.incrementAndGet()
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
            }
            start()
        }
        try {
            val url = "http://127.0.0.1:${target.address.port}/hooks/private-path"
            val saved = runtime.notifications!!.create(SaveNotificationTarget("Hooks", "webhook", url, "hush-hush"))

            val (failed, text) = call(admin, "notifications", args("op" to "list"))
            assertFalse(failed, text)
            assertFalse(text.contains("private-path"), "the path is the credential: $text")
            assertFalse(text.contains("hush-hush"), text)
            val row = Json.parseToJsonElement(text).field("targets")!!.field("items")!!.jsonArray.single()
            assertEquals("127.0.0.1:${target.address.port}", row.field("host")!!.jsonPrimitive.content)

            val (sent, sentText) = call(admin, "notifications", args("op" to "test", "targetId" to saved.id))
            assertFalse(sent, sentText)
            assertTrue(Json.parseToJsonElement(sentText).field("ok")!!.jsonPrimitive.boolean, sentText)
            assertEquals(1, hits.get())

            val (_, withDeliveries) = call(admin, "notifications", args("op" to "list", "targetId" to saved.id))
            assertEquals(
                "test",
                Json.parseToJsonElement(withDeliveries).field("recent")!!.field("items")!!.jsonArray.single()
                    .field("event")!!.jsonPrimitive.content,
            )
            val (missing, missingWhy) = call(admin, "notifications", args("op" to "test", "targetId" to "nope"))
            assertTrue(missing && missingWhy.contains("NOT_FOUND"), missingWhy)
        } finally {
            target.stop(0)
        }
    }
}
