package dev.jamesrich.sofaking.data

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class LibraryPlaybackTest {
    /** A server with live and VOD sessions: a VOD open answers [mode], and a PATCH with a start bumps the epoch. */
    private class FakeServer(scope: TestScope) {
        class Vod(
            val id: String,
            val itemId: String,
            val mode: String,
            var startMs: Long,
            var epoch: Int = 1,
            val recordingId: String? = null,
            val intent: String = "watch",
        )

        val vods = linkedMapOf<String, Vod>()

        /** False for a server that predates previews: it ignores the intent and answers without one. */
        var knowsPreviews = true
        var mode = "remux"
        var nextId = 1
        var openDelayMs = 0L

        val http = FakeHttp(scope) { request ->
            val vodId = request.path.removePrefix("/api/v1/vod-sessions/").takeIf {
                request.path.startsWith("/api/v1/vod-sessions/")
            }
            val liveId = request.path.removePrefix("/api/v1/playback-sessions/").takeIf {
                request.path.startsWith("/api/v1/playback-sessions/")
            }
            when {
                request.method == "POST" && request.path == "/api/v1/vod-sessions" -> {
                    val body = Json.parseToJsonElement(request.body).jsonObject
                    val start = body["startMs"]?.jsonPrimitive?.longOrNull ?: 0
                    val recordingId = body["recordingId"]?.jsonPrimitive?.contentOrNull
                    val vod = Vod(
                        "v${nextId++}",
                        body["itemId"]?.jsonPrimitive?.content.orEmpty(),
                        mode,
                        if (mode == "direct") {
                            0
                        } else {
                            start
                        },
                        recordingId = recordingId,
                        intent = body["intent"]?.jsonPrimitive?.contentOrNull.takeIf { knowsPreviews } ?: "watch",
                    )
                    vods[vod.id] = vod
                    delay(openDelayMs)
                    json(vod.body(), status = 201)
                }

                vodId != null && request.method == "PATCH" -> vods[vodId]?.let { vod ->
                    Json.parseToJsonElement(request.body).jsonObject["startMs"]?.jsonPrimitive?.longOrNull?.let {
                        if (vod.mode != "direct") {
                            vod.startMs = it
                            vod.epoch++
                        }
                    }
                    json(vod.body())
                } ?: apiError(410, "SESSION_EXPIRED")

                vodId != null && request.method == "DELETE" -> {
                    vods.remove(vodId)
                    respond("", HttpStatusCode.NoContent)
                }

                request.method == "POST" && request.path == "/api/v1/playback-sessions" ->
                    json(liveBody("s${nextId++}"), status = 201)

                liveId != null && request.method == "PATCH" -> json(liveBody(liveId))

                liveId != null && request.method == "DELETE" -> respond("", HttpStatusCode.NoContent)

                else -> apiError(404, "RESOURCE_NOT_FOUND")
            }
        }

        val requests get() = http.requests.map { it.toString() }

        private fun liveBody(id: String) =
            """{"id":"$id","channelId":"c1","intent":"watch","expiresAt":"2026-10-10T10:00:00Z",""" +
                """"descriptor":{"protocol":"hls","mimeType":"application/vnd.apple.mpegurl","url":"/media/""" +
                """sessions/""" +
                """$id/1/root.m3u8","epoch":1}}"""

        private fun Vod.body(): String {
            val descriptor = if (mode == "direct") {
                """{"protocol":"file","mimeType":"video/x-matroska","url":"/media/vod/$id/$epoch/k/""" +
                    """file.mkv","epoch":$epoch}"""
            } else {
                """{"protocol":"hls","mimeType":"application/vnd.apple.mpegurl","url":"/media/vod/$id/$epoch/k/""" +
                    """index.m3u8","epoch":$epoch}"""
            }
            val recording = recordingId?.let { ""","recordingId":"$it"""" }.orEmpty() +
                if (knowsPreviews) ""","intent":"$intent"""" else ""
            return """{"id":"$id","itemId":"$itemId","mode":"$mode","descriptor":$descriptor,"startMs":$startMs,""" +
                """"durationMs":3600000,"expiresAt":"2026-10-10T10:05:00Z"$recording}"""
        }
    }

    private val tv =
        PlaybackProfile(
            listOf("mp4", "mkv"),
            listOf("h264", "hevc"),
            listOf("aac", "ac3"),
            maxHeight = 2160,
            hdr = true,
        )

    private suspend fun TestScope.fixture(): Pair<FakeServer, PlaybackSessions> {
        val fake = FakeServer(this)
        val sessions =
            PlaybackSessions(backgroundScope, renewInterval = 100.milliseconds, newIdempotencyKey = { "key" })
        sessions.configure(fake.http.api())
        return fake to sessions
    }

    @Test
    fun `playing an item sends the profile and start and gives a seekable stream that starts there`() = runTest {
        val (fake, sessions) = fixture()

        val stream = sessions.play("m1", tv, startMs = 120_000)

        assertEquals("m1", stream.itemId)
        assertEquals("", stream.channelId)
        assertTrue(stream.seekable)
        assertEquals("remux", stream.mode)
        assertEquals("${FAKE_BASE}media/vod/v1/1/k/index.m3u8", stream.url)
        assertEquals(HLS_MIME_TYPE, stream.mimeType)
        assertEquals(120_000, stream.offsetMs, "a converted stream's 0 is where it was asked to start")
        assertEquals(120_000, stream.startAtMs)
        assertEquals(3_600_000, stream.itemDurationMs)
        val body = Json.parseToJsonElement(fake.requests.single().substringAfter("vod-sessions ")).jsonObject
        assertEquals("m1", body["itemId"]!!.jsonPrimitive.content)
        assertEquals(120_000, body["startMs"]!!.jsonPrimitive.longOrNull)
        assertEquals("""["mp4","mkv"]""", body["profile"]!!.jsonObject["containers"].toString())
    }

    @Test
    fun `a recording opens by its id and seeks restart it like an item`() = runTest {
        val (fake, sessions) = fixture()

        val stream = sessions.playRecording("r1", tv, startMs = 60_000)

        assertEquals("r1", stream.itemId, "the screen tells an item stream by its id")
        assertTrue(stream.seekable)
        val body = Json.parseToJsonElement(fake.requests.single().substringAfter("vod-sessions ")).jsonObject
        assertEquals("r1", body["recordingId"]!!.jsonPrimitive.content)
        assertNull(body["itemId"], "an empty item id is the default, so it is left out")
        assertEquals(60_000, body["startMs"]!!.jsonPrimitive.longOrNull)

        val restarted = sessions.restartAt(900_000)!!
        assertEquals("r1", restarted.itemId)
        assertEquals(900_000, restarted.offsetMs)

        sessions.reportPosition(950_000)
        sessions.release()
        assertEquals("r1", sessions.reopen()!!.itemId)
        val reopened = Json.parseToJsonElement(fake.requests.last().substringAfter("vod-sessions ")).jsonObject
        assertEquals("r1", reopened["recordingId"]!!.jsonPrimitive.content, "a reopen asks for the recording again")
        assertEquals(950_000, reopened["startMs"]!!.jsonPrimitive.longOrNull)
    }

    @Test
    fun `releasing an item that is no longer held leaves the next item's session alone`() = runTest {
        val (fake, sessions) = fixture()
        sessions.play("e1", tv)
        val next = sessions.play("e2", tv)

        sessions.release(forget = true, itemId = "e1")

        assertEquals(next, sessions.current.value)
        assertEquals(setOf("v2"), fake.vods.keys)
        sessions.release(forget = true, itemId = "e2")
        assertNull(sessions.current.value)
    }

    @Test
    fun `a file played as it is starts at 0 and the player seeks to the resume point`() = runTest {
        val (fake, sessions) = fixture()
        fake.mode = "direct"

        val stream = sessions.play("m1", tv, startMs = 120_000)

        assertEquals("video/x-matroska", stream.mimeType)
        assertEquals(0, stream.offsetMs)
        assertEquals(120_000, stream.startAtMs)
        assertEquals(stream, sessions.restartAt(600_000), "a direct file seeks in the player, so nothing restarts")
        assertTrue(fake.requests.none { it.startsWith("PATCH") })
    }

    @Test
    fun `a seek past the written edge restarts the converted stream under a new URL`() = runTest {
        val (fake, sessions) = fixture()
        val first = sessions.play("m1", tv)

        val restarted = sessions.restartAt(1_800_000)!!

        assertEquals("PATCH /api/v1/vod-sessions/v1 {\"startMs\":1800000}", fake.requests.last())
        assertNotEquals(first.url, restarted.url)
        assertEquals(2, restarted.epoch)
        assertEquals(1_800_000, restarted.offsetMs)
        assertEquals(restarted, sessions.current.value)
    }

    @Test
    fun `playing an item releases the live channel and tuning a channel releases the item`() = runTest {
        val (fake, sessions) = fixture()
        sessions.tune("c1", PlaybackIntent.WATCH)

        sessions.play("m1", tv)
        assertTrue("DELETE /api/v1/playback-sessions/s1" in fake.requests, fake.requests.toString())

        sessions.tune("c1", PlaybackIntent.WATCH)
        assertTrue("DELETE /api/v1/vod-sessions/v2" in fake.requests, fake.requests.toString())
        assertTrue(fake.vods.isEmpty())
    }

    @Test
    fun `a closing page releases the held item at its own endpoint`() = runTest {
        val (_, sessions) = fixture()
        sessions.play("m1", tv)

        assertEquals("${FAKE_BASE}api/v1/vod-sessions/v1", sessions.exitRelease()?.url)
    }

    @Test
    fun `a released item reopens where the player last was`() = runTest {
        val (fake, sessions) = fixture()
        sessions.play("m1", tv, startMs = 10_000, audioIndex = 2, subtitleIndex = 1000)
        sessions.reportPosition(95_000)

        sessions.release()
        assertNull(sessions.current.value)
        val reopened = sessions.reopen()!!

        assertEquals("m1", reopened.itemId)
        assertEquals(95_000, reopened.startAtMs)
        val body = Json.parseToJsonElement(fake.requests.last().substringAfter("vod-sessions ")).jsonObject
        assertEquals(95_000, body["startMs"]!!.jsonPrimitive.longOrNull)
        assertEquals(2, body["audioIndex"]!!.jsonPrimitive.longOrNull?.toInt())
        assertEquals(1000, body["subtitleIndex"]!!.jsonPrimitive.longOrNull?.toInt(), "the drawn subtitle stays on")
        assertNull(body["wholePlaylist"], "a player that restarts conversions itself leaves it out")
    }

    @Test
    fun `the item lease is renewed and a dropped session reopens where the player was`() = runTest {
        val (fake, sessions) = fixture()
        sessions.play("m1", tv)
        withTimeout(5_000) { while (fake.requests.none { it == "PATCH /api/v1/vod-sessions/v1 {}" }) delay(10) }

        sessions.reportPosition(42_000)
        fake.vods.clear()
        val reopened = withTimeout(5_000) { sessions.current.filterNotNull().first { it.sessionId != "v1" } }
        assertEquals("m1", reopened.itemId)
        assertEquals(42_000, reopened.offsetMs)
    }

    private fun FakeServer.opens() = requests.filter { it.startsWith("POST /api/v1/vod-sessions") }

    @Test
    fun `a trailer opens as a preview and a play sends no intent`() = runTest {
        val (fake, sessions) = fixture()

        val trailer = sessions.preview("t1", tv)

        assertEquals(PlaybackIntent.PREVIEW, trailer?.intent)
        assertEquals("t1", trailer?.itemId)
        assertEquals(trailer, sessions.current.value)
        val body = Json.parseToJsonElement(fake.opens().single().substringAfter("vod-sessions ")).jsonObject
        assertEquals("preview", body["intent"]!!.jsonPrimitive.content)
        sessions.play("m1", tv)
        val play = Json.parseToJsonElement(fake.opens().last().substringAfter("vod-sessions ")).jsonObject
        assertNull(play["intent"], "an older server reads the request it always did")
    }

    @Test
    fun `a trailer never opens while the app holds a session`() = runTest {
        val (fake, sessions) = fixture()
        sessions.tune("c1", PlaybackIntent.PREVIEW)

        assertNull(sessions.preview("t1", tv))

        assertTrue(fake.opens().isEmpty())
        assertEquals("c1", sessions.current.value?.channelId)
        sessions.play("m1", tv)
        assertNull(sessions.preview("t1", tv))
        assertEquals("m1", sessions.current.value?.itemId)
    }

    @Test
    fun `a trailer a server held as a watch is released at once`() = runTest {
        val (fake, sessions) = fixture()
        fake.knowsPreviews = false

        assertNull(sessions.preview("t1", tv))

        assertNull(sessions.current.value)
        assertTrue(fake.vods.isEmpty())
        assertTrue("DELETE /api/v1/vod-sessions/v1" in fake.requests)
    }

    @Test
    fun `a trailer is renewed and not opened again when the server drops it`() = runTest {
        val (fake, sessions) = fixture()
        sessions.preview("t1", tv)
        withTimeout(5_000) { while (fake.requests.none { it == "PATCH /api/v1/vod-sessions/v1 {}" }) delay(10) }

        fake.vods.clear()
        withTimeout(5_000) { while (sessions.current.value != null) delay(10) }

        delay(500)
        assertEquals(1, fake.opens().size)
        assertNull(sessions.current.value)
    }

    @Test
    fun `releasing a trailer leaves nothing to reopen and keeps what an earlier stop left`() = runTest {
        val (fake, sessions) = fixture()
        sessions.preview("t1", tv)
        sessions.release()
        assertNull(sessions.reopen())

        sessions.play("m1", tv)
        sessions.reportPosition(42_000)
        sessions.release()
        val trailer = sessions.preview("t1", tv)
        sessions.release(itemId = "t1")

        assertEquals("t1", trailer?.itemId)
        val back = sessions.reopen()
        assertEquals("m1", back?.itemId)
        assertEquals(42_000, back?.offsetMs)
        assertEquals(4, fake.opens().size)
    }

    @Test
    fun `playing an item or tuning a channel releases the trailer first`() = runTest {
        val (fake, sessions) = fixture()
        sessions.preview("t1", tv)

        val played = sessions.play("m1", tv)

        assertEquals("m1", played.itemId)
        assertEquals(listOf("v2"), fake.vods.keys.toList())
        assertTrue("DELETE /api/v1/vod-sessions/v1" in fake.requests)
        sessions.preview("t1", tv)
        assertEquals("m1", sessions.current.value?.itemId, "the trailer found the player taken")
        sessions.tune("c1", PlaybackIntent.PREVIEW)
        assertTrue("DELETE /api/v1/vod-sessions/v2" in fake.requests)
    }

    @Test
    fun `a trailer canceled while the server answers releases the session it opened`() = runTest {
        val (fake, sessions) = fixture()
        fake.openDelayMs = 400
        val opening = backgroundScope.async { sessions.preview("t1", tv) }
        withTimeout(5_000) { while (fake.opens().isEmpty()) delay(10) }

        opening.cancelAndJoin()

        withTimeout(5_000) { while ("DELETE /api/v1/vod-sessions/v1" !in fake.requests) delay(10) }
        assertNull(sessions.current.value)
        assertTrue(fake.vods.isEmpty())
    }

    @Test
    fun `an item play canceled while the server answers releases the session it opened`() = runTest {
        val (fake, sessions) = fixture()
        fake.openDelayMs = 400
        val playing = backgroundScope.async { sessions.play("m1", tv) }
        withTimeout(5_000) { while (fake.requests.none { it.startsWith("POST /api/v1/vod-sessions") }) delay(10) }

        playing.cancelAndJoin()

        withTimeout(5_000) { while ("DELETE /api/v1/vod-sessions/v1" !in fake.requests) delay(10) }
        assertNull(sessions.current.value)
        assertTrue(fake.vods.isEmpty())
    }
}
