package dev.jamesrich.sofaking.data

import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import kotlin.time.Instant

class SofaKingRepositoryTest {
    private val server = MockWebServer()
    private val now = Instant.parse("2026-10-10T10:17:42Z")

    /** Answers the catalog with an ETag and the guide per chunk; [guideRevision] is what each guide answer reports. */
    private inner class FakeServer : Dispatcher() {
        var catalogBody = """{"revision":"r1","channels":[{"id":"c1","displayNumber":"1","name":"One","playbackEligible":true}]}"""
        var catalogEtag = "\"r1\""
        var guideRevision = "r1"
        /** One 409 per entry for a guide query that pins a revision, as the server answers when the catalog moved. */
        val conflictsLeft = ConcurrentLinkedQueue<String>()
        val guideQueries = ConcurrentLinkedQueue<String>()
        var catalogRequests = 0

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.url.encodedPath
            return when {
                path == "/api/v1/channels" -> {
                    catalogRequests++
                    if (request.headers["If-None-Match"] == catalogEtag) {
                        MockResponse.Builder().code(304).addHeader("ETag", catalogEtag).build()
                    } else {
                        json(catalogBody, etag = catalogEtag)
                    }
                }
                path == "/api/v1/guide/query" -> {
                    val body = request.body?.utf8().orEmpty()
                    guideQueries += body
                    val pinned = Json.parseToJsonElement(body).jsonObject["revision"]?.jsonPrimitive?.content
                    if (pinned != null && conflictsLeft.poll() != null) {
                        return json("""{"code":"RESYNC_REQUIRED","message":"Guide revision changed","retryable":true}""", code = 409)
                    }
                    val ids = Json.parseToJsonElement(body).jsonObject["channelIds"]!!.jsonArray.map { it.jsonPrimitive.content }
                    val channels = ids.joinToString(",") { id ->
                        """{"channelId":"$id","status":"current","programmes":[{"id":"p-$id","channelId":"$id","startsAt":"2026-10-10T10:00:00Z","endsAt":"2026-10-10T11:00:00Z","title":"Show $id"}]}"""
                    }
                    json("""{"revision":"$guideRevision","from":"x","to":"y","channels":[$channels]}""")
                }
                else -> json("""{"code":"RESOURCE_NOT_FOUND","message":"no","retryable":false}""", code = 404)
            }
        }
    }

    private fun json(body: String, code: Int = 200, etag: String? = null) = MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .apply { if (etag != null) addHeader("ETag", etag) }
        .body(body)
        .build()

    private val fake = FakeServer()

    @Before
    fun setUp() {
        server.dispatcher = fake
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun repository() = SofaKingRepository(SofaKingApi(server.url("/").toString(), "t")) { now }

    private fun channels(count: Int) = List(count) { Channel("c$it", it.toDouble(), "Channel $it", null, listOf("Other")) }

    @Test
    fun `an unchanged catalog is answered from the ETag without a second body`() = runTest {
        val repository = repository()

        val first = repository.loadChannels()
        val second = repository.loadChannels()

        assertEquals(listOf(Channel("c1", 1.0, "One", null, listOf("Other"))), first)
        assertEquals(first, second)
        assertEquals(2, fake.catalogRequests)
    }

    @Test
    fun `a changed catalog replaces the channels`() = runTest {
        val repository = repository()
        repository.loadChannels()
        fake.catalogBody = """{"revision":"r2","channels":[{"id":"c2","displayNumber":"2","name":"Two","playbackEligible":true}]}"""
        fake.catalogEtag = "\"r2\""

        assertEquals(listOf("c2"), repository.loadChannels().map { it.guideId })
    }

    @Test
    fun `the guide is queried in chunks of 200 over the half-hour-aligned 24 hour window`() = runTest {
        val guide = repository().loadGuide(channels(450))

        assertEquals(450, guide.size)
        assertEquals("Show c449", guide["c449"]!!.single().title)
        val queries = fake.guideQueries.map { Json.parseToJsonElement(it).jsonObject }
        val sizes = queries.map { it["channelIds"]!!.jsonArray.size }
        // The first chunk goes alone; the rest run together and may arrive in any order.
        assertEquals(200, sizes.first())
        assertEquals(listOf(200, 50), sizes.drop(1).sortedDescending())
        queries.forEach { query ->
            assertEquals("2026-10-10T10:00:00Z", query["from"]!!.jsonPrimitive.content)
            assertEquals("2026-10-11T10:00:00Z", query["to"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `later chunks pin the revision the first one returned`() = runTest {
        repository().loadGuide(channels(250))

        val revisions = fake.guideQueries.map { Json.parseToJsonElement(it).jsonObject["revision"]?.jsonPrimitive?.content }
        assertEquals(listOf(null, "r1"), revisions)
    }

    @Test
    fun `a revision that moved under the chunks restarts the guide without one`() = runTest {
        fake.conflictsLeft += "once"

        val guide = repository().loadGuide(channels(250))

        assertEquals(250, guide.size)
        val revisions = fake.guideQueries.map { Json.parseToJsonElement(it).jsonObject["revision"]?.jsonPrimitive?.content }
        assertEquals(listOf(null, "r1", null, null), revisions)
    }

    @Test
    fun `every chunk after the first pins the revision the first returned`() = runTest {
        val guide = repository().loadGuide(channels(1_585))

        assertEquals(1_585, guide.size)
        val revisions = fake.guideQueries.map { Json.parseToJsonElement(it).jsonObject["revision"]?.jsonPrimitive?.content }
        assertEquals(8, revisions.size)
        assertEquals(null, revisions.first())
        assertEquals(List(7) { "r1" }, revisions.drop(1))
    }

    @Test
    fun `a conflict on a later chunk while others are in flight still restarts the whole guide`() = runTest {
        fake.conflictsLeft += "once"

        val guide = repository().loadGuide(channels(1_000))

        assertEquals(1_000, guide.size)
        val revisions = fake.guideQueries.map { Json.parseToJsonElement(it).jsonObject["revision"]?.jsonPrimitive?.content }
        // The first attempt's opening chunk, then all five chunks of the restart, unpinned.
        assertEquals(6, revisions.count { it == null })
    }

    @Test
    fun `duplicate channel ids are queried once`() = runTest {
        val twice = channels(3) + channels(3)

        repository().loadGuide(twice)

        assertEquals(listOf(3), fake.guideQueries.map { Json.parseToJsonElement(it).jsonObject["channelIds"]!!.jsonArray.size })
    }
}
