package dev.jamesrich.sofaking.data

import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

class PlaybackSessionsTest {
    /** A server with the session model the app depends on: leases, intent changes, epochs and dropped sessions. */
    private class FakeServer : Dispatcher() {
        class Session(val id: String, val channelId: String, var intent: String, var epoch: Int = 1)

        val sessions = linkedMapOf<String, Session>()
        val requests = ConcurrentLinkedQueue<String>()
        var nextId = 1
        var openDelayMs = 0L
        /** Epoch bumps the next renewal should report, as the server does when it swaps the upstream. */
        var bumpEpochOnRenew = false

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.url.encodedPath
            val body = request.body?.utf8().orEmpty()
            requests += "${request.method} $path $body".trim()
            if (request.headers["Authorization"] != "Bearer secret") return error(401, "AUTH_REQUIRED")
            val id = path.removePrefix("/api/v1/playback-sessions/").takeIf { path.startsWith("/api/v1/playback-sessions/") }
            return synchronized(this) {
                when {
                    request.method == "POST" && path == "/api/v1/playback-sessions" -> {
                        val json = Json.parseToJsonElement(body).jsonObject
                        val session = Session("s${nextId++}", json["channelId"]!!.jsonPrimitive.content, json["intent"]!!.jsonPrimitive.content)
                        sessions[session.id] = session
                        json(session, code = 201, delayMs = openDelayMs)
                    }
                    id != null && request.method == "GET" -> sessions[id]?.let { json(it) } ?: error(410, "SESSION_EXPIRED")
                    id != null && request.method == "PATCH" -> sessions[id]?.let { session ->
                        Json.parseToJsonElement(body).jsonObject["intent"]?.jsonPrimitive?.content?.let { session.intent = it }
                        if (bumpEpochOnRenew) {
                            session.epoch++
                            bumpEpochOnRenew = false
                        }
                        json(session)
                    } ?: error(410, "SESSION_EXPIRED")
                    id != null && request.method == "DELETE" -> {
                        sessions.remove(id)
                        MockResponse.Builder().code(204).build()
                    }
                    else -> error(404, "RESOURCE_NOT_FOUND")
                }
            }
        }

        private fun json(session: Session, code: Int = 200, delayMs: Long = 0L) = MockResponse.Builder()
            .code(code)
            .addHeader("Content-Type", "application/json")
            .bodyDelay(delayMs, TimeUnit.MILLISECONDS)
            .body(
                """{"id":"${session.id}","channelId":"${session.channelId}","intent":"${session.intent}","expiresAt":"2026-10-10T10:00:00Z",""" +
                    """"descriptor":{"protocol":"hls","mimeType":"application/vnd.apple.mpegurl","url":"/media/sessions/${session.id}/${session.epoch}/root.m3u8","epoch":${session.epoch}}}""",
            )
            .build()

        private fun error(code: Int, apiCode: String) =
            MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body("""{"code":"$apiCode","message":"$apiCode","retryable":false}""").build()

        fun requestsOfKind(method: String) = requests.filter { it.startsWith(method) }
    }

    private val server = MockWebServer()
    private val fake = FakeServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var sessions: PlaybackSessions

    @Before
    fun setUp() {
        server.dispatcher = fake
        server.start()
        sessions = PlaybackSessions(scope, renewInterval = 100.milliseconds, newIdempotencyKey = { "key-${fake.requests.size}" })
        runBlocking { sessions.configure(SofaKingApi(server.url("/").toString(), "secret")) }
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    private suspend fun awaitRequests(predicate: (List<String>) -> Boolean) = withTimeout(5_000) {
        while (!predicate(fake.requests.toList())) delay(10)
    }

    @Test
    fun `opening a preview returns the resolved media URL with the HLS MIME type`() = runBlocking {
        val stream = sessions.tune("c1", PlaybackIntent.PREVIEW)

        assertEquals("c1", stream.channelId)
        assertEquals("s1", stream.sessionId)
        assertEquals(server.url("/media/sessions/s1/1/root.m3u8").toString(), stream.url)
        assertEquals("application/x-mpegURL", stream.mimeType)
        assertEquals(PlaybackIntent.PREVIEW, stream.intent)
        assertEquals(stream, sessions.current.value)
        val open = fake.requests.single()
        assertTrue(open, open.startsWith("POST /api/v1/playback-sessions "))
        val body = Json.parseToJsonElement(open.substringAfter("playback-sessions ")).jsonObject
        assertEquals("preview", body["intent"]!!.jsonPrimitive.content)
        assertEquals("key-0", body["idempotencyKey"]!!.jsonPrimitive.content)
    }

    @Test
    fun `promoting to watch patches the held session and keeps its stream`() = runBlocking {
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
    fun `setting the intent it already has or for another channel sends nothing`() = runBlocking {
        sessions.tune("c1", PlaybackIntent.PREVIEW)

        sessions.setIntent("c1", PlaybackIntent.PREVIEW)
        sessions.setIntent("c2", PlaybackIntent.WATCH)

        assertEquals(emptyList<String>(), fake.requestsOfKind("PATCH"))
    }

    @Test
    fun `the lease is renewed on the timer with an empty body`() = runBlocking {
        sessions.tune("c1", PlaybackIntent.PREVIEW)

        awaitRequests { requests -> requests.count { it == "PATCH /api/v1/playback-sessions/s1 {}" } >= 2 }
        assertEquals(1, fake.requestsOfKind("POST").size)
    }

    @Test
    fun `a renewal that reports a new epoch publishes the replaced stream`() = runBlocking {
        val stream = sessions.tune("c1", PlaybackIntent.PREVIEW)
        fake.bumpEpochOnRenew = true

        val replaced = withTimeout(5_000) { sessions.replaced.first() }

        assertEquals("s1", replaced.sessionId)
        assertEquals(2, replaced.epoch)
        assertNotEquals(stream.url, replaced.url)
        assertEquals(server.url("/media/sessions/s1/2/root.m3u8").toString(), replaced.url)
        assertEquals(replaced, sessions.current.value)
    }

    @Test
    fun `a session the server dropped is reopened on renewal`() = runBlocking {
        sessions.tune("c1", PlaybackIntent.WATCH)
        synchronized(fake) { fake.sessions.clear() }

        val replaced = withTimeout(5_000) { sessions.replaced.first() }

        assertEquals("c1", replaced.channelId)
        assertEquals("s2", replaced.sessionId)
        assertEquals(PlaybackIntent.WATCH, replaced.intent)
        assertEquals("watch", fake.sessions["s2"]!!.intent)
        awaitRequests { requests -> requests.any { it == "PATCH /api/v1/playback-sessions/s2 {}" } }
    }

    @Test
    fun `tuning another channel releases the held session first`() = runBlocking {
        sessions.tune("c1", PlaybackIntent.PREVIEW)

        val second = sessions.tune("c2", PlaybackIntent.PREVIEW)

        assertEquals("s2", second.sessionId)
        val relevant = fake.requests.filter { !it.startsWith("PATCH") }.map { it.substringBefore(" {") }
        assertEquals(listOf("POST /api/v1/playback-sessions", "DELETE /api/v1/playback-sessions/s1", "POST /api/v1/playback-sessions"), relevant)
        assertEquals(setOf("s2"), fake.sessions.keys)
    }

    @Test
    fun `tuning the held channel again re-reads the session instead of opening another`() = runBlocking {
        val first = sessions.tune("c1", PlaybackIntent.WATCH)
        synchronized(fake) { fake.sessions["s1"]!!.epoch = 3 }

        val again = sessions.tune("c1", PlaybackIntent.WATCH)

        assertEquals(first.sessionId, again.sessionId)
        assertEquals(3, again.epoch)
        assertEquals(listOf("GET /api/v1/playback-sessions/s1"), fake.requestsOfKind("GET"))
        assertEquals(1, fake.requestsOfKind("POST").size)
    }

    @Test
    fun `tuning the held channel with another intent promotes it in place`() = runBlocking {
        sessions.tune("c1", PlaybackIntent.PREVIEW)

        val watch = sessions.tune("c1", PlaybackIntent.WATCH)

        assertEquals("s1", watch.sessionId)
        assertEquals(PlaybackIntent.WATCH, watch.intent)
        assertEquals(1, fake.requestsOfKind("POST").size)
        assertEquals("watch", fake.sessions["s1"]!!.intent)
    }

    @Test
    fun `re-reading a session the server dropped opens a new one`() = runBlocking {
        sessions.tune("c1", PlaybackIntent.PREVIEW)
        synchronized(fake) { fake.sessions.clear() }

        val reopened = sessions.tune("c1", PlaybackIntent.PREVIEW)

        assertEquals("s2", reopened.sessionId)
        assertEquals(2, fake.requestsOfKind("POST").size)
    }

    @Test
    fun `release deletes the session and stops renewing`() = runBlocking {
        sessions.tune("c1", PlaybackIntent.PREVIEW)

        sessions.release()

        assertNull(sessions.current.value)
        assertEquals(listOf("DELETE /api/v1/playback-sessions/s1"), fake.requestsOfKind("DELETE"))
        val renewals = fake.requestsOfKind("PATCH").size
        delay(350)
        assertEquals(renewals, fake.requestsOfKind("PATCH").size)
    }

    @Test
    fun `reopen after release opens the released channel with its intent`() = runBlocking {
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
    fun `a forgotten release leaves nothing to reopen`() = runBlocking {
        sessions.tune("c1", PlaybackIntent.PREVIEW)

        sessions.release(forget = true)

        assertNull(sessions.reopen())
        assertEquals(1, fake.requestsOfKind("POST").size)
    }

    @Test
    fun `a tune canceled while the server answers releases the session it opened`() = runBlocking {
        fake.openDelayMs = 400
        val tuning = scope.async { sessions.tune("c1", PlaybackIntent.PREVIEW) }
        awaitRequests { requests -> requests.any { it.startsWith("POST") } }

        tuning.cancelAndJoin()

        awaitRequests { requests -> requests.any { it == "DELETE /api/v1/playback-sessions/s1" } }
        assertNull(sessions.current.value)
        assertTrue(fake.sessions.isEmpty())
    }

    @Test
    fun `an open that outlasts its timeout fails as an IOException, not a cancellation`() = runBlocking {
        val slow = PlaybackSessions(scope, renewInterval = 100.milliseconds, newIdempotencyKey = { "slow-key" }, openTimeout = 100.milliseconds)
        slow.configure(SofaKingApi(server.url("/").toString(), "secret"))
        fake.openDelayMs = 400

        val failure = runCatching { slow.tune("c1", PlaybackIntent.PREVIEW) }.exceptionOrNull()

        assertTrue("was $failure", failure is InterruptedIOException)
        assertFalse(failure is CancellationException)
        assertNull(slow.current.value)
    }

    @Test
    fun `releaseAsync releases the held session without a suspending caller`() = runBlocking {
        sessions.tune("c1", PlaybackIntent.PREVIEW)

        sessions.releaseAsync()

        awaitRequests { requests -> requests.any { it == "DELETE /api/v1/playback-sessions/s1" } }
        assertNull(sessions.current.value)
    }

    @Test
    fun `configuring another server releases the held session`() = runBlocking {
        sessions.tune("c1", PlaybackIntent.PREVIEW)

        sessions.configure(SofaKingApi("http://127.0.0.1:1/", "other"))

        assertEquals(listOf("DELETE /api/v1/playback-sessions/s1"), fake.requestsOfKind("DELETE"))
        assertNull(sessions.current.value)
        assertNull(sessions.reopen())
    }

    @Test
    fun `nothing can be tuned before a server is configured`() = runBlocking {
        val unconfigured = PlaybackSessions(scope, renewInterval = 100.milliseconds)

        val failure = runCatching { unconfigured.tune("c1", PlaybackIntent.PREVIEW) }.exceptionOrNull()

        assertTrue(failure is java.io.IOException)
        assertNull(unconfigured.current.value)
    }
}
