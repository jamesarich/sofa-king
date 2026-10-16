package dev.jamesrich.sofaking.data

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class PlaybackSessionsTest {
    /** A server with the session model the app depends on: leases, intent changes, epochs and dropped sessions. */
    private class FakeServer(scope: TestScope) {
        class Session(val id: String, val channelId: String, var intent: String, var epoch: Int = 1)

        val sessions = linkedMapOf<String, Session>()
        var nextId = 1
        var openDelayMs = 0L

        /** Epoch bumps the next renewal should report, as the server does when it swaps the upstream. */
        var bumpEpochOnRenew = false

        val http = FakeHttp(scope) { request ->
            if (request.headers["Authorization"] != "Bearer secret") return@FakeHttp apiError(401, "AUTH_REQUIRED")
            val id = request.path.removePrefix("/api/v1/playback-sessions/").takeIf { request.path.startsWith("/api/v1/playback-sessions/") }
            when {
                request.method == "POST" && request.path == "/api/v1/playback-sessions" -> {
                    val json = Json.parseToJsonElement(request.body).jsonObject
                    val session = Session("s${nextId++}", json["channelId"]!!.jsonPrimitive.content, json["intent"]!!.jsonPrimitive.content)
                    sessions[session.id] = session
                    delay(openDelayMs)
                    json(session.body(), status = 201)
                }
                id != null && request.method == "GET" -> sessions[id]?.let { json(it.body()) } ?: apiError(410, "SESSION_EXPIRED")
                id != null && request.method == "PATCH" -> sessions[id]?.let { session ->
                    Json.parseToJsonElement(request.body).jsonObject["intent"]?.jsonPrimitive?.content?.let { session.intent = it }
                    if (bumpEpochOnRenew) {
                        session.epoch++
                        bumpEpochOnRenew = false
                    }
                    json(session.body())
                } ?: apiError(410, "SESSION_EXPIRED")
                id != null && request.method == "DELETE" -> {
                    sessions.remove(id)
                    respond("", HttpStatusCode.NoContent)
                }
                else -> apiError(404, "RESOURCE_NOT_FOUND")
            }
        }

        val requests get() = http.requests.map { it.toString() }

        fun requestsOfKind(method: String) = http.requestsOfKind(method)

        private fun Session.body() =
            """{"id":"$id","channelId":"$channelId","intent":"$intent","expiresAt":"2026-10-10T10:00:00Z",""" +
                """"descriptor":{"protocol":"hls","mimeType":"application/vnd.apple.mpegurl","url":"/media/sessions/$id/$epoch/root.m3u8","epoch":$epoch}}"""
    }

    /** A configured [PlaybackSessions] renewing every 100 ms on the test's virtual clock, beside its [FakeServer]. */
    private data class Fixture(val fake: FakeServer, val sessions: PlaybackSessions)

    private suspend fun TestScope.fixture(): Fixture {
        val fake = FakeServer(this)
        val sessions = PlaybackSessions(backgroundScope, renewInterval = 100.milliseconds, newIdempotencyKey = { "key-${fake.requests.size}" })
        sessions.configure(fake.http.api())
        return Fixture(fake, sessions)
    }

    private suspend fun FakeServer.awaitRequests(predicate: (List<String>) -> Boolean) = withTimeout(5_000) {
        while (!predicate(requests)) delay(10)
    }

    @Test
    fun `opening a preview returns the resolved media URL with the HLS MIME type`() = runTest {
        val (fake, sessions) = fixture()

        val stream = sessions.tune("c1", PlaybackIntent.PREVIEW)

        assertEquals("c1", stream.channelId)
        assertEquals("s1", stream.sessionId)
        assertEquals("${FAKE_BASE}media/sessions/s1/1/root.m3u8", stream.url)
        assertEquals("application/x-mpegURL", stream.mimeType)
        assertEquals(PlaybackIntent.PREVIEW, stream.intent)
        assertEquals(stream, sessions.current.value)
        val open = fake.requests.single()
        assertTrue(open.startsWith("POST /api/v1/playback-sessions "), open)
        val body = Json.parseToJsonElement(open.substringAfter("playback-sessions ")).jsonObject
        assertEquals("preview", body["intent"]!!.jsonPrimitive.content)
        assertEquals("key-0", body["idempotencyKey"]!!.jsonPrimitive.content)
    }

    @Test
    fun `promoting to watch patches the held session and keeps its stream`() = runTest {
        val (fake, sessions) = fixture()
        val preview = sessions.tune("c1", PlaybackIntent.PREVIEW)

        sessions.setIntent("c1", PlaybackIntent.WATCH)

        val held = sessions.current.value!!
        assertEquals(PlaybackIntent.WATCH, held.intent)
        assertEquals(preview.url, held.url)
        assertEquals(listOf("PATCH /api/v1/playback-sessions/s1 {\"intent\":\"watch\"}"), fake.requestsOfKind("PATCH"))
        assertEquals(1, fake.requestsOfKind("POST").size)
        assertEquals("watch", fake.sessions["s1"]!!.intent)
    }

    @Test
    fun `setting the intent it already has or for another channel sends nothing`() = runTest {
        val (fake, sessions) = fixture()
        sessions.tune("c1", PlaybackIntent.PREVIEW)

        sessions.setIntent("c1", PlaybackIntent.PREVIEW)
        sessions.setIntent("c2", PlaybackIntent.WATCH)

        assertEquals(emptyList(), fake.requestsOfKind("PATCH"))
    }

    @Test
    fun `the lease is renewed on the timer with an empty body`() = runTest {
        val (fake, sessions) = fixture()
        sessions.tune("c1", PlaybackIntent.PREVIEW)

        fake.awaitRequests { requests -> requests.count { it == "PATCH /api/v1/playback-sessions/s1 {}" } >= 2 }
        assertEquals(1, fake.requestsOfKind("POST").size)
    }

    @Test
    fun `a renewal that reports a new epoch publishes the replaced stream`() = runTest {
        val (fake, sessions) = fixture()
        val stream = sessions.tune("c1", PlaybackIntent.PREVIEW)
        fake.bumpEpochOnRenew = true

        val replaced = withTimeout(5_000) { sessions.replaced.first() }

        assertEquals("s1", replaced.sessionId)
        assertEquals(2, replaced.epoch)
        assertNotEquals(stream.url, replaced.url)
        assertEquals("${FAKE_BASE}media/sessions/s1/2/root.m3u8", replaced.url)
        assertEquals(replaced, sessions.current.value)
    }

    @Test
    fun `a session the server dropped is reopened on renewal`() = runTest {
        val (fake, sessions) = fixture()
        sessions.tune("c1", PlaybackIntent.WATCH)
        fake.sessions.clear()

        val replaced = withTimeout(5_000) { sessions.replaced.first() }

        assertEquals("c1", replaced.channelId)
        assertEquals("s2", replaced.sessionId)
        assertEquals(PlaybackIntent.WATCH, replaced.intent)
        assertEquals("watch", fake.sessions["s2"]!!.intent)
        fake.awaitRequests { requests -> requests.any { it == "PATCH /api/v1/playback-sessions/s2 {}" } }
    }

    @Test
    fun `tuning another channel releases the held session first`() = runTest {
        val (fake, sessions) = fixture()
        sessions.tune("c1", PlaybackIntent.PREVIEW)

        val second = sessions.tune("c2", PlaybackIntent.PREVIEW)

        assertEquals("s2", second.sessionId)
        val relevant = fake.requests.filter { !it.startsWith("PATCH") }.map { it.substringBefore(" {") }
        assertEquals(listOf("POST /api/v1/playback-sessions", "DELETE /api/v1/playback-sessions/s1", "POST /api/v1/playback-sessions"), relevant)
        assertEquals(setOf("s2"), fake.sessions.keys)
    }

    @Test
    fun `tuning the held channel again re-reads the session instead of opening another`() = runTest {
        val (fake, sessions) = fixture()
        val first = sessions.tune("c1", PlaybackIntent.WATCH)
        fake.sessions["s1"]!!.epoch = 3

        val again = sessions.tune("c1", PlaybackIntent.WATCH)

        assertEquals(first.sessionId, again.sessionId)
        assertEquals(3, again.epoch)
        assertEquals(listOf("GET /api/v1/playback-sessions/s1"), fake.requestsOfKind("GET"))
        assertEquals(1, fake.requestsOfKind("POST").size)
    }

    @Test
    fun `tuning the held channel with another intent promotes it in place`() = runTest {
        val (fake, sessions) = fixture()
        sessions.tune("c1", PlaybackIntent.PREVIEW)

        val watch = sessions.tune("c1", PlaybackIntent.WATCH)

        assertEquals("s1", watch.sessionId)
        assertEquals(PlaybackIntent.WATCH, watch.intent)
        assertEquals(1, fake.requestsOfKind("POST").size)
        assertEquals("watch", fake.sessions["s1"]!!.intent)
    }

    @Test
    fun `re-reading a session the server dropped opens a new one`() = runTest {
        val (fake, sessions) = fixture()
        sessions.tune("c1", PlaybackIntent.PREVIEW)
        fake.sessions.clear()

        val reopened = sessions.tune("c1", PlaybackIntent.PREVIEW)

        assertEquals("s2", reopened.sessionId)
        assertEquals(2, fake.requestsOfKind("POST").size)
    }

    @Test
    fun `release deletes the session and stops renewing`() = runTest {
        val (fake, sessions) = fixture()
        sessions.tune("c1", PlaybackIntent.PREVIEW)

        sessions.release()

        assertNull(sessions.current.value)
        assertEquals(listOf("DELETE /api/v1/playback-sessions/s1"), fake.requestsOfKind("DELETE"))
        val renewals = fake.requestsOfKind("PATCH").size
        delay(350)
        assertEquals(renewals, fake.requestsOfKind("PATCH").size)
    }

    @Test
    fun `reopen after release opens the released channel with its intent`() = runTest {
        val (fake, sessions) = fixture()
        sessions.tune("c1", PlaybackIntent.PREVIEW)
        sessions.setIntent("c1", PlaybackIntent.WATCH)
        sessions.release()

        val reopened = sessions.reopen()!!

        assertEquals("c1", reopened.channelId)
        assertEquals("s2", reopened.sessionId)
        assertEquals(PlaybackIntent.WATCH, reopened.intent)
        assertEquals("watch", fake.sessions["s2"]!!.intent)
    }

    @Test
    fun `a forgotten release leaves nothing to reopen`() = runTest {
        val (fake, sessions) = fixture()
        sessions.tune("c1", PlaybackIntent.PREVIEW)

        sessions.release(forget = true)

        assertNull(sessions.reopen())
        assertEquals(1, fake.requestsOfKind("POST").size)
    }

    @Test
    fun `a tune canceled while the server answers releases the session it opened`() = runTest {
        val (fake, sessions) = fixture()
        fake.openDelayMs = 400
        val tuning = backgroundScope.async { sessions.tune("c1", PlaybackIntent.PREVIEW) }
        fake.awaitRequests { requests -> requests.any { it.startsWith("POST") } }

        tuning.cancelAndJoin()

        fake.awaitRequests { requests -> requests.any { it == "DELETE /api/v1/playback-sessions/s1" } }
        assertNull(sessions.current.value)
        assertTrue(fake.sessions.isEmpty())
    }

    @Test
    fun `an open that outlasts its timeout fails as an IOException and not a cancellation`() = runTest {
        val fake = FakeServer(this)
        val slow = PlaybackSessions(backgroundScope, renewInterval = 100.milliseconds, newIdempotencyKey = { "slow-key" }, openTimeout = 100.milliseconds)
        slow.configure(fake.http.api())
        fake.openDelayMs = 400

        val failure = runCatching { slow.tune("c1", PlaybackIntent.PREVIEW) }.exceptionOrNull()

        assertFalse(failure is CancellationException, "was $failure")
        assertTrue(failure is SessionTimeoutException, "was $failure")
        assertNull(slow.current.value)
    }

    @Test
    fun `releaseAsync releases the held session without a suspending caller`() = runTest {
        val (fake, sessions) = fixture()
        sessions.tune("c1", PlaybackIntent.PREVIEW)

        sessions.releaseAsync()

        fake.awaitRequests { requests -> requests.any { it == "DELETE /api/v1/playback-sessions/s1" } }
        assertNull(sessions.current.value)
    }

    @Test
    fun `configuring another server releases the held session`() = runTest {
        val (fake, sessions) = fixture()
        sessions.tune("c1", PlaybackIntent.PREVIEW)

        sessions.configure(fake.http.api(token = "other", base = "http://other.test/"))

        assertEquals(listOf("DELETE /api/v1/playback-sessions/s1"), fake.requestsOfKind("DELETE"))
        assertNull(sessions.current.value)
        assertNull(sessions.reopen())
    }

    @Test
    fun `nothing can be tuned before a server is configured`() = runTest {
        val unconfigured = PlaybackSessions(backgroundScope, renewInterval = 100.milliseconds)

        val failure = runCatching { unconfigured.tune("c1", PlaybackIntent.PREVIEW) }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertNull(unconfigured.current.value)
    }
}
