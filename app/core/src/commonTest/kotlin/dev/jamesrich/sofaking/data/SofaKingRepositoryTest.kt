package dev.jamesrich.sofaking.data

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class SofaKingRepositoryTest {
    private val now = Instant.parse("2026-10-10T10:17:42Z")

    /** Answers the catalog with an ETag and the guide per chunk; [guideRevision] is what each guide answer reports. */
    private class FakeServer(scope: TestScope) {
        var catalogBody = """{"revision":"r1","channels":[{"id":"c1","displayNumber":"1","name":"One","playbackEligible":true}]}"""
        var catalogEtag = "\"r1\""
        var guideRevision = "r1"

        /** One 409 per count for a guide query that pins a revision, as the server answers when the catalog moved. */
        var conflictsLeft = 0

        /** Channel ids the guide answers as gone from the lineup. */
        val removed = mutableSetOf<String>()

        val http = FakeHttp(scope) { request ->
            when (request.path) {
                "/api/v1/channels" -> if (request.headers["If-None-Match"] == catalogEtag) {
                    respond("", HttpStatusCode.NotModified, headersOf("ETag", catalogEtag))
                } else {
                    json(catalogBody, etag = catalogEtag)
                }
                "/api/v1/guide/query" -> {
                    val query = Json.parseToJsonElement(request.body).jsonObject
                    if (query["revision"] != null && conflictsLeft > 0) {
                        conflictsLeft--
                        return@FakeHttp json("""{"code":"RESYNC_REQUIRED","message":"Guide revision changed","retryable":true}""", status = 409)
                    }
                    val channels = query.channelIds().joinToString(",") { id ->
                        if (id in removed) return@joinToString """{"channelId":"$id","status":"removed","programmes":[]}"""
                        """{"channelId":"$id","status":"available","programmes":[{"id":"p-$id","channelId":"$id","startsAt":"2026-10-10T10:00:00Z","endsAt":"2026-10-10T11:00:00Z","title":"Show $id"}]}"""
                    }
                    json("""{"revision":"$guideRevision","from":"x","to":"y","channels":[$channels]}""")
                }
                else -> apiError(404, "RESOURCE_NOT_FOUND")
            }
        }

        val catalogRequests get() = http.requests.count { it.path == "/api/v1/channels" }
        val guideQueries get() = http.requests.filter { it.path == "/api/v1/guide/query" }.map { Json.parseToJsonElement(it.body).jsonObject }
        val revisions get() = guideQueries.map { it["revision"]?.jsonPrimitive?.content }
    }

    private fun TestScope.fake() = FakeServer(this)

    private fun FakeServer.repository() = SofaKingRepository(http.api(token = "t")) { now }

    private fun channels(count: Int) = List(count) { Channel("c$it", it.toDouble(), "Channel $it", null, listOf("Other")) }

    @Test
    fun `an unchanged catalog is answered from the ETag without a second body`() = runTest {
        val fake = fake()
        val repository = fake.repository()

        val first = repository.loadChannels()
        val second = repository.loadChannels()

        assertEquals(listOf(Channel("c1", 1.0, "One", null, listOf("Other"))), first)
        assertEquals(first, second)
        assertEquals(2, fake.catalogRequests)
    }

    @Test
    fun `a changed catalog replaces the channels`() = runTest {
        val fake = fake()
        val repository = fake.repository()
        repository.loadChannels()
        fake.catalogBody = """{"revision":"r2","channels":[{"id":"c2","displayNumber":"2","name":"Two","playbackEligible":true}]}"""
        fake.catalogEtag = "\"r2\""

        assertEquals(listOf("c2"), repository.loadChannels().map { it.guideId })
    }

    @Test
    fun `the guide is queried in chunks of 200 over the half-hour-aligned 24 hour window`() = runTest {
        val fake = fake()

        val guide = fake.repository().loadGuide(channels(450)).programmes

        assertEquals(450, guide.size)
        assertEquals("Show c449", guide["c449"]!!.single().title)
        val sizes = fake.guideQueries.map { it.channelIds().size }
        // The first chunk goes alone; the rest run together and may arrive in any order.
        assertEquals(200, sizes.first())
        assertEquals(listOf(200, 50), sizes.drop(1).sortedDescending())
        fake.guideQueries.forEach { query ->
            assertEquals("2026-10-10T10:00:00Z", query["from"]!!.jsonPrimitive.content)
            assertEquals("2026-10-11T10:00:00Z", query["to"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `later chunks pin the revision the first one returned`() = runTest {
        val fake = fake()

        fake.repository().loadGuide(channels(250))

        assertEquals(listOf(null, "r1"), fake.revisions)
    }

    @Test
    fun `a revision that moved under the chunks restarts the guide without one`() = runTest {
        val fake = fake()
        fake.conflictsLeft = 1

        val guide = fake.repository().loadGuide(channels(250)).programmes

        assertEquals(250, guide.size)
        assertEquals(listOf(null, "r1", null, null), fake.revisions)
    }

    @Test
    fun `every chunk after the first pins the revision the first returned`() = runTest {
        val fake = fake()

        val guide = fake.repository().loadGuide(channels(1_585)).programmes

        assertEquals(1_585, guide.size)
        assertEquals(listOf(null) + List(7) { "r1" }, fake.revisions)
    }

    @Test
    fun `a conflict on a later chunk while others are in flight still restarts the whole guide`() = runTest {
        val fake = fake()
        fake.conflictsLeft = 1

        val guide = fake.repository().loadGuide(channels(1_000)).programmes

        assertEquals(1_000, guide.size)
        // The first attempt's opening chunk, then all five chunks of the restart, unpinned.
        assertEquals(6, fake.revisions.count { it == null })
    }

    @Test
    fun `a channel the server removed since the catalog loads as removed with no programmes`() = runTest {
        val fake = fake()
        fake.removed += "c1"

        val guide = fake.repository().loadGuide(channels(3))

        assertEquals(setOf("c1"), guide.removed)
        assertEquals(setOf("c0", "c2"), guide.programmes.keys)
    }

    @Test
    fun `duplicate channel ids are queried once`() = runTest {
        val fake = fake()

        fake.repository().loadGuide(channels(3) + channels(3))

        assertEquals(listOf(3), fake.guideQueries.map { it.channelIds().size })
    }
}

private fun JsonObject.channelIds() = getValue("channelIds").jsonArray.map { it.jsonPrimitive.content }
