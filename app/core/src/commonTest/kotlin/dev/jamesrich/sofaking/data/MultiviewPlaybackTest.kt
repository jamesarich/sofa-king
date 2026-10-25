package dev.jamesrich.sofaking.data

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class MultiviewPlaybackTest {
    /** A server with live and multiview sessions: moving a multiview's sound bumps its epoch. */
    private class FakeServer(scope: TestScope) {
        class Multiview(val id: String, val channelIds: List<String>, var audio: Int, var epoch: Int = 1)

        val multiviews = linkedMapOf<String, Multiview>()
        var nextId = 1

        val http = FakeHttp(scope) { request ->
            val multiviewId = request.path.removePrefix("/api/v1/multiview-sessions/").takeIf {
                request.path.startsWith("/api/v1/multiview-sessions/")
            }
            val liveId = request.path.removePrefix("/api/v1/playback-sessions/").takeIf {
                request.path.startsWith("/api/v1/playback-sessions/")
            }
            when {
                request.method == "POST" && request.path == "/api/v1/multiview-sessions" -> {
                    val body = Json.parseToJsonElement(request.body).jsonObject
                    val view = Multiview(
                        "m${nextId++}",
                        body["channelIds"]!!.jsonArray.map { it.jsonPrimitive.content },
                        body["audio"]?.jsonPrimitive?.int ?: 0,
                    )
                    multiviews[view.id] = view
                    json(view.body(), status = 201)
                }

                multiviewId != null && request.method == "PATCH" -> multiviews[multiviewId]?.let { view ->
                    Json.parseToJsonElement(request.body).jsonObject["audio"]?.jsonPrimitive?.intOrNull?.let {
                        view.audio = it
                        view.epoch++
                    }
                    json(view.body())
                } ?: apiError(410, "SESSION_EXPIRED")

                multiviewId != null && request.method == "DELETE" -> {
                    multiviews.remove(multiviewId)
                    respond("", HttpStatusCode.NoContent)
                }

                request.method == "POST" && request.path == "/api/v1/playback-sessions" ->
                    json(liveBody("s${nextId++}"), status = 201)

                liveId != null && request.method == "GET" -> json(liveBody(liveId))

                liveId != null && request.method == "DELETE" -> respond("", HttpStatusCode.NoContent)

                else -> apiError(404, "RESOURCE_NOT_FOUND")
            }
        }

        val requests get() = http.requests.map { it.toString() }

        private fun liveBody(id: String) =
            """{"id":"$id","channelId":"c1","intent":"watch","expiresAt":"2026-10-10T10:00:00Z",""" +
                """"descriptor":{"protocol":"hls","mimeType":"application/vnd.apple.mpegurl","url":"/media/""" +
                """sessions/$id/1/root.m3u8","epoch":1}}"""

        private fun Multiview.body(): String {
            val ids = channelIds.joinToString(",") { "\"$it\"" }
            return """{"id":"$id","channelIds":[$ids],"audio":$audio,"descriptor":{"protocol":"hls",""" +
                """"mimeType":"application/vnd.apple.mpegurl","url":"/media/multiview/$id/$epoch/k/""" +
                """index.m3u8","epoch":$epoch},"expiresAt":"2026-10-10T10:01:00Z"}"""
        }
    }

    private suspend fun TestScope.fixture(): Pair<FakeServer, PlaybackSessions> {
        val fake = FakeServer(this)
        val sessions =
            PlaybackSessions(backgroundScope, renewInterval = 100.milliseconds, newIdempotencyKey = { "key" })
        sessions.configure(fake.http.api())
        return fake to sessions
    }

    @Test
    fun `a multiview plays as one stream named for the channel whose sound plays`() = runTest {
        val (fake, sessions) = fixture()

        val stream = sessions.multiview(listOf("c1", "c2", "c3"), audio = 1)

        assertTrue(stream.isMultiview)
        assertEquals(listOf("c1", "c2", "c3"), stream.tiles)
        assertEquals("c2", stream.channelId)
        assertEquals(1, stream.audioTile)
        assertFalse(stream.seekable)
        assertEquals("${FAKE_BASE}media/multiview/m1/1/k/index.m3u8", stream.url)
        val body = Json.parseToJsonElement(fake.requests.single().substringAfter("multiview-sessions ")).jsonObject
        assertEquals(1, body["audio"]!!.jsonPrimitive.int)
    }

    @Test
    fun `moving the sound restarts the stream under a new URL`() = runTest {
        val (fake, sessions) = fixture()
        val first = sessions.multiview(listOf("c1", "c2"), audio = 0)

        val moved = sessions.moveAudio(1)!!

        assertEquals("PATCH /api/v1/multiview-sessions/m1 {\"audio\":1}", fake.requests.last())
        assertNotEquals(first.url, moved.url)
        assertEquals("c2", moved.channelId)
        assertEquals(moved, sessions.current.value)
        assertEquals(moved, sessions.moveAudio(1), "the tile already playing changes nothing")
    }

    @Test
    fun `the lease is renewed and a dropped multiview opens again with its sound where it was`() = runTest {
        val (fake, sessions) = fixture()
        sessions.multiview(listOf("c1", "c2"), audio = 0)
        sessions.moveAudio(1)
        val renewal = "PATCH /api/v1/multiview-sessions/m1"
        withTimeout(5_000) { while (fake.requests.count { it.startsWith(renewal) } < 2) delay() }

        fake.multiviews.clear()
        val reopened = withTimeout(5_000) { sessions.replaced.first() }

        assertEquals("m2", reopened.sessionId)
        assertEquals(1, reopened.audioTile, "the sound stays on the tile it was moved to")
    }

    @Test
    fun `tuning a channel releases the multiview even for the channel whose sound plays`() = runTest {
        val (fake, sessions) = fixture()
        sessions.multiview(listOf("c1", "c2"), audio = 0)

        val tuned = sessions.tune("c1", PlaybackIntent.WATCH)

        assertFalse(tuned.isMultiview)
        assertTrue("DELETE /api/v1/multiview-sessions/m1" in fake.requests, fake.requests.toString())
        assertTrue(fake.multiviews.isEmpty())
        assertNull(sessions.moveAudio(1), "no multiview is held")
    }

    @Test
    fun `a released multiview reopens with its channels and a closing page releases it at its endpoint`() = runTest {
        val (_, sessions) = fixture()
        sessions.multiview(listOf("c1", "c2", "c3", "c4"), audio = 3)
        assertEquals("${FAKE_BASE}api/v1/multiview-sessions/m1", sessions.exitRelease()?.url)

        sessions.release()
        val reopened = sessions.reopen()!!

        assertEquals(listOf("c1", "c2", "c3", "c4"), reopened.tiles)
        assertEquals(3, reopened.audioTile)
    }

    private suspend fun delay() = kotlinx.coroutines.delay(10)
}
